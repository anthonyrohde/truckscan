package com.anthonyrohde.truckscan.core

import com.anthonyrohde.truckscan.core.adapter.CanBus
import com.anthonyrohde.truckscan.core.session.DiagnosticEngine
import com.anthonyrohde.truckscan.core.transport.SimulatedVehicleTransport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers bus routing, which is easy to get wrong and silent when it is.
 *
 * A module is unreachable unless the adapter is configured for the bus it sits
 * on. Discovery selects each bus as it sweeps, but everything afterwards
 * addresses modules in arbitrary order across buses. Without routing those
 * operations run on whichever bus was selected last and simply time out - and
 * because a timeout reads as "module not present", the failure is quiet.
 *
 * The simulator models bus affinity precisely so these tests can catch that:
 * its powertrain modules answer only at 500 kbps and its body modules only at
 * 125 kbps, exactly as on the vehicle.
 */
class BusRoutingTest {

    private fun engine(stn: Boolean = true) = DiagnosticEngine(
        SimulatedVehicleTransport(reportAsStn = stn, latencyMillis = 0),
    )

    @Test
    fun `reading a body module works even when the adapter is left on the powertrain bus`() =
        runBlocking {
            val engine = engine()
            engine.connect(CanBus.HS_CAN1)
            val modules = engine.quickScanVehicle()

            // Put the adapter firmly back on the powertrain bus.
            engine.adapter.selectBus(CanBus.HS_CAN1)
            assertEquals(CanBus.HS_CAN1, engine.adapter.selectedBus)

            // The BCM lives on MS-CAN. Reading it must follow it there.
            val bcm = modules.first { it.module.code == "BCM" }
            val result = engine.dtcScanner.readModule(bcm)

            assertEquals(null, result.error, "BCM should be readable: ${result.error}")
            assertTrue(result.hasFaults, "the simulated BCM stores U0155")
            assertEquals("U0155", result.dtcs.single().code)
        }

    @Test
    fun `a whole vehicle fault scan crosses buses correctly`() = runBlocking {
        val engine = engine()
        engine.connect()
        engine.quickScanVehicle()

        val scan = engine.scanFaults()
        val codes = scan.allDtcs.map { it.code }.toSet()

        // P0299 and P242F are on the PCM (HS-CAN1); U0155 is on the BCM (MS-CAN).
        // Getting all three proves the scan followed modules across buses.
        assertTrue(codes.contains("P0299"), "found: $codes")
        assertTrue(codes.contains("P242F"), "found: $codes")
        assertTrue(codes.contains("U0155"), "found: $codes")
        assertTrue(
            scan.unreadableModules.isEmpty(),
            "no module should be unreadable: " +
                scan.unreadableModules.map { "${it.module.module.code}=${it.error}" },
        )
    }

    @Test
    fun `as built read follows the module to its bus`() = runBlocking {
        val engine = engine()
        engine.connect()
        val bcm = engine.quickScanVehicle().first { it.module.code == "BCM" }
        engine.adapter.selectBus(CanBus.HS_CAN1)

        val snapshot = engine.asBuiltReader.snapshot(bcm, ranges = listOf(0xDE00..0xDE0F))

        assertEquals(3, snapshot.blocks.size, "BCM has three configuration blocks")
        assertNotNull(snapshot.checksumStrategy)
    }

    @Test
    fun `live data returns to the powertrain bus after a body module read`() = runBlocking {
        val engine = engine()
        engine.connect()
        val bcm = engine.quickScanVehicle().first { it.module.code == "BCM" }

        // Reading the BCM leaves the adapter on MS-CAN.
        engine.dtcScanner.readModule(bcm)
        assertEquals(CanBus.MS_CAN, engine.adapter.selectedBus)

        // Legislated OBD-II is answered by the powertrain, so this has to come back.
        val voltage = engine.readControlModuleVoltage()
        assertNotNull(voltage, "control module voltage must be readable")
        assertEquals(14.0, voltage!!, 0.001)
    }

    @Test
    fun `a request for the bus already selected does not touch the adapter`() = runBlocking {
        val engine = engine()
        engine.connect(CanBus.HS_CAN1)
        engine.busRouter.reset()

        val first = engine.busRouter.ensureBus(CanBus.HS_CAN1)
        val second = engine.busRouter.ensureBus(CanBus.HS_CAN1)

        assertFalse(first, "already on this bus")
        assertFalse(second)
        assertEquals(0, engine.busRouter.switchCount)
        assertEquals(2, engine.busRouter.skippedCount)
    }

    @Test
    fun `repeated reads of the same module switch buses only once`() = runBlocking {
        val engine = engine()
        engine.connect()
        val bcm = engine.quickScanVehicle().first { it.module.code == "BCM" }
        engine.adapter.selectBus(CanBus.HS_CAN1)
        engine.busRouter.reset()

        repeat(5) { engine.dtcScanner.readModule(bcm) }

        assertEquals(
            1, engine.busRouter.switchCount,
            "only the first read should need a switch",
        )
        assertEquals(4, engine.busRouter.skippedCount)
    }

    @Test
    fun `returning to a bus reuses the proven setup instead of reprobing`() = runBlocking {
        val engine = engine()
        engine.connect(CanBus.HS_CAN1)

        // Bring MS-CAN up once, then bounce between the two.
        val firstMs = engine.adapter.selectBus(CanBus.MS_CAN)
        assertTrue(firstMs.trafficObserved)

        engine.adapter.selectBus(CanBus.HS_CAN1)
        val secondMs = engine.adapter.selectBus(CanBus.MS_CAN)

        assertEquals(
            firstMs.sequenceLabel, secondMs.sequenceLabel,
            "the sequence proven to work should be reused verbatim",
        )
        assertTrue(secondMs.trafficObserved)
    }

    @Test
    fun `an elm class adapter explains the hardware limit instead of timing out`() = runBlocking {
        val engine = engine(stn = false)
        engine.connect(CanBus.HS_CAN1)

        val error = runCatching { engine.busRouter.ensureBus(CanBus.MS_CAN) }.exceptionOrNull()

        assertNotNull(error, "an ELM327 cannot reach MS-CAN and should say so")
        assertTrue(error!!.message!!.contains("pins 3/11"), error.message!!)
        assertTrue(error.message!!.contains("OBDLink"))
    }

    @Test
    fun `a module on an unreachable bus reports why rather than appearing fault free`() =
        runBlocking {
            val engine = engine(stn = false)
            engine.connect(CanBus.HS_CAN1)

            // Discover on the only bus this adapter can reach, then hand the
            // scanner a module that claims to be on MS-CAN.
            val onPowertrain = engine.quickScanVehicle()
            val pcm = onPowertrain.first { it.module.code == "PCM" }
            val pretendBodyModule = pcm.copy(bus = CanBus.MS_CAN)

            val result = engine.dtcScanner.readModule(pretendBodyModule)

            // The distinction that matters: an unreachable module must not be
            // reported as a module with no faults.
            assertNotNull(result.error, "must report the reason, not silence")
            assertFalse(result.hasFaults)
            assertTrue(result.error!!.contains("OBDLink"), result.error!!)
        }

    @Test
    fun `simultaneous access skips switches once a bus has been brought up`() = runBlocking {
        val engine = engine()
        engine.connect(CanBus.HS_CAN1)
        engine.busRouter.reset()

        // Sequential switching is the default and stays correct everywhere.
        assertFalse(engine.busRouter.simultaneousBusAccess)
        engine.busRouter.ensureBus(CanBus.MS_CAN)
        engine.busRouter.ensureBus(CanBus.HS_CAN1)
        assertEquals(2, engine.busRouter.switchCount)

        // With simultaneous access, a bus already brought up needs no switch.
        engine.busRouter.simultaneousBusAccess = true
        val switched = engine.busRouter.ensureBus(CanBus.MS_CAN)
        assertFalse(switched, "MS-CAN was already brought up")
        assertEquals(2, engine.busRouter.switchCount)
    }
}
