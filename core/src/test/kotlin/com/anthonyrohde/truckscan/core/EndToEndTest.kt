package com.anthonyrohde.truckscan.core

import com.anthonyrohde.truckscan.core.adapter.CanBus
import com.anthonyrohde.truckscan.core.ford.ChecksumStrategy
import com.anthonyrohde.truckscan.core.pid.PidCatalog
import com.anthonyrohde.truckscan.core.session.ConnectionState
import com.anthonyrohde.truckscan.core.session.DiagnosticEngine
import com.anthonyrohde.truckscan.core.transport.SimulatedVehicleTransport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Drives the whole stack - adapter command layer, ISO-TP, UDS, Ford logic -
 * against the vehicle simulator.
 *
 * These are the tests that would catch a regression in how the layers fit
 * together, as opposed to the unit tests which cover each layer's arithmetic.
 */
class EndToEndTest {

    private fun engine(stn: Boolean = true) = DiagnosticEngine(
        SimulatedVehicleTransport(reportAsStn = stn, latencyMillis = 0),
    )

    // --------------------------------------------------------------- connection

    @Test
    fun `connects and reports adapter identity`() = runBlocking {
        val engine = engine()
        val result = engine.connect()

        assertTrue(result.isSuccess, "connection should succeed: ${result.exceptionOrNull()}")
        val state = assertInstanceOf(
            ConnectionState.Connected::class.java,
            engine.connectionState.value,
        )
        assertEquals(CanBus.HS_CAN1, state.activeBus)
        assertTrue(state.identity.isStn, "simulator reports itself as STN hardware")
        assertTrue(state.identity.supportsMultiBus)
    }

    @Test
    fun `elm class adapter is offered only the powertrain bus`() = runBlocking {
        val engine = engine(stn = false)
        engine.connect()

        assertFalse(engine.adapter.adapterIdentity.supportsMultiBus)
        assertEquals(
            listOf(CanBus.HS_CAN1),
            engine.scannableBuses(),
            "an ELM327 is wired to pins 6/14 only and must not be offered other buses",
        )
    }

    @Test
    fun `selecting an unreachable bus on an elm adapter fails with a useful message`() = runBlocking {
        val engine = engine(stn = false)
        engine.connect()

        val error = runCatching { engine.adapter.selectBus(CanBus.MS_CAN) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(
            error!!.message!!.contains("OBDLink"),
            "the message should say what hardware would work: ${error.message}",
        )
        assertTrue(error.message!!.contains("pins 3/11"))
    }

    // ---------------------------------------------------------------- discovery

    @Test
    fun `quick scan finds the simulated modules`() = runBlocking {
        val engine = engine()
        engine.connect()

        val modules = engine.quickScanVehicle()
        val codes = modules.map { it.module.code }.toSet()

        assertTrue(codes.contains("PCM"), "found: $codes")
        assertTrue(codes.contains("TCM"), "found: $codes")
        assertTrue(codes.contains("BCM"), "found: $codes")
        assertTrue(codes.contains("IPC"), "found: $codes")
        assertTrue(codes.contains("APIM"), "found: $codes")
    }

    @Test
    fun `discovery reads identification and deduplicates across buses`() = runBlocking {
        val engine = engine()
        engine.connect()

        val modules = engine.quickScanVehicle()
        val addresses = modules.map { it.module.requestId }
        assertEquals(
            addresses.size, addresses.distinct().size,
            "a gateway answering on two buses must not appear as two modules",
        )

        val pcm = modules.first { it.module.code == "PCM" }
        assertEquals("1FT8W2BT7NEC12345", pcm.identification?.vin)
        assertEquals("1FT8W2BT7NEC12345", engine.vin())
    }

    @Test
    fun `absent addresses are not reported as modules`() = runBlocking {
        val engine = engine()
        engine.connect()

        // 0x7AA is not in the simulator's module map.
        val found = engine.discovery.scanBus(
            CanBus.HS_CAN1,
            addresses = listOf(0x7AA),
            readIdentification = false,
        )
        assertTrue(found.isEmpty(), "nothing should be reported at an empty address")
    }

    // --------------------------------------------------------------------- DTCs

    @Test
    fun `fault scan reads and decodes codes from multiple modules`() = runBlocking {
        val engine = engine()
        engine.connect()
        engine.quickScanVehicle()

        val scan = engine.scanFaults()
        val codes = scan.allDtcs.map { it.code }.toSet()

        assertTrue(codes.contains("P0299"), "found: $codes")
        assertTrue(codes.contains("P242F"), "found: $codes")
        assertTrue(codes.contains("U0155"), "found: $codes")

        val turbo = scan.allDtcs.first { it.code == "P0299" }
        assertTrue(turbo.status.confirmed)
        assertTrue(turbo.status.warningIndicatorRequested)
        assertEquals("Turbocharger/supercharger underboost", turbo.description)

        val dpf = scan.allDtcs.first { it.code == "P242F" }
        assertTrue(dpf.status.pending)
        assertFalse(dpf.status.confirmed)
    }

    @Test
    fun `fault report names the module and bus for each code`() = runBlocking {
        val engine = engine()
        engine.connect()
        engine.quickScanVehicle()

        val report = engine.scanFaults().toReport()

        assertTrue(report.contains("PCM"), report)
        assertTrue(report.contains("P0299"), report)
        assertTrue(report.contains("HS-CAN1"), report)
        assertTrue(report.contains("Turbocharger"), report)
    }

    @Test
    fun `clearing faults removes them from the module`() = runBlocking {
        val engine = engine()
        engine.connect()
        val modules = engine.quickScanVehicle()
        val pcm = modules.first { it.module.code == "PCM" }

        assertTrue(engine.dtcScanner.readModule(pcm).hasFaults)

        val cleared = engine.dtcScanner.clearModule(pcm)
        assertTrue(cleared.isSuccess, "clear should succeed: ${cleared.exceptionOrNull()}")

        assertFalse(
            engine.dtcScanner.readModule(pcm).hasFaults,
            "faults should be gone after clearing",
        )
    }

    // ---------------------------------------------------------------- live data

    @Test
    fun `live data decodes real values through the full stack`() = runBlocking {
        val engine = engine()
        engine.connect()

        val sample = engine.liveData.sampleOnce(
            listOf(
                PidCatalog.ENGINE_RPM,
                PidCatalog.COOLANT_TEMP,
                PidCatalog.CONTROL_MODULE_VOLTAGE,
            ),
        )

        assertEquals(788.0, sample.values[PidCatalog.ENGINE_RPM.key]!!.value, 0.001)
        assertEquals(85.0, sample.values[PidCatalog.COOLANT_TEMP.key]!!.value, 0.001)
        assertEquals(14.0, sample.values[PidCatalog.CONTROL_MODULE_VOLTAGE.key]!!.value, 0.001)
        assertTrue(sample.failedKeys.isEmpty())
    }

    @Test
    fun `supported pid query returns the vehicle's capability`() = runBlocking {
        val engine = engine()
        engine.connect()

        val supported = engine.liveData.readSupportedPids()
        assertTrue(supported.isNotEmpty())
        assertTrue(supported.contains(PidCatalog.ENGINE_RPM.id), "RPM should be supported")
    }

    @Test
    fun `control module voltage is readable for the pre write safety check`() = runBlocking {
        val engine = engine()
        engine.connect()

        assertEquals(14.0, engine.readControlModuleVoltage()!!, 0.001)
    }

    // ----------------------------------------------------------------- As-Built

    @Test
    fun `as built snapshot discovers blocks and identifies the checksum algorithm`() = runBlocking {
        val engine = engine()
        engine.connect()
        val modules = engine.quickScanVehicle()
        val bcm = modules.first { it.module.code == "BCM" }

        val snapshot = engine.asBuiltReader.snapshot(
            bcm,
            // Narrowed from the full sweep purely to keep the test quick; the
            // discovery mechanism exercised is identical.
            ranges = listOf(0xDE00..0xDE0F, 0xF187..0xF187),
        )

        assertEquals(3, snapshot.blocks.size, "BCM has three configuration blocks")
        assertEquals(
            ChecksumStrategy.TWOS_COMPLEMENT,
            snapshot.checksumStrategy,
            "the algorithm should be measured from the data, not assumed",
        )
        assertTrue(snapshot.isRestorable)
        assertEquals("LC3T-14B476-AKE", snapshot.partNumber)

        // Every block must validate under the detected algorithm.
        snapshot.blocks.forEach {
            assertTrue(
                it.isChecksumValid(ChecksumStrategy.TWOS_COMPLEMENT),
                "block ${it.blockId} failed validation",
            )
        }
    }

    @Test
    fun `snapshot exports to ford as built text that parses back`() = runBlocking {
        val engine = engine()
        engine.connect()
        val bcm = engine.quickScanVehicle().first { it.module.code == "BCM" }

        val snapshot = engine.asBuiltReader.snapshot(bcm, ranges = listOf(0xDE00..0xDE0F))
        val text = snapshot.toFordFormat()

        assertTrue(text.contains("726-"), text)
        assertTrue(text.contains("Two's complement"), text)

        val reparsed = com.anthonyrohde.truckscan.core.ford.AsBuiltBlock.parseAll(text)
        assertEquals(
            snapshot.blocks.size, reparsed.size,
            "an exported snapshot must be re-importable",
        )
        assertEquals(snapshot.blocks.first(), reparsed.first())
    }

    @Test
    fun `identification dids are excluded from configuration blocks`() = runBlocking {
        val engine = engine()
        engine.connect()
        val pcm = engine.quickScanVehicle().first { it.module.code == "PCM" }

        val snapshot = engine.asBuiltReader.snapshot(
            pcm,
            ranges = listOf(0xDE00..0xDE0F, 0xF187..0xF190),
        )

        // The VIN and part number must not be presented as config blocks, or
        // checksum detection would fail on their ASCII contents.
        assertEquals(2, snapshot.blocks.size)
        assertNotNull(snapshot.checksumStrategy)
        assertEquals("1FT8W2BT7NEC12345", snapshot.vin)
    }

    // ---------------------------------------------------------- service routines

    @Test
    fun `standard operations run against a module`() = runBlocking {
        val engine = engine()
        engine.connect()
        val pcm = engine.quickScanVehicle().first { it.module.code == "PCM" }

        val suspend = engine.routines.standardOperations.first { it.id == "dtc_logging_off" }
        val result = engine.routines.runStandard(suspend, pcm)

        assertInstanceOf(
            com.anthonyrohde.truckscan.core.session.RoutineResult.Completed::class.java,
            result,
        )
    }

    @Test
    fun `no routine discovery function exists`() {
        // Deliberate: sweeping RoutineControl identifiers would command unknown
        // functions on a live vehicle. If someone adds one, this should fail.
        val methods = com.anthonyrohde.truckscan.core.session.ServiceRoutineRunner::class.java
            .methods.map { it.name }

        assertTrue(
            methods.none { it.contains("discover", ignoreCase = true) },
            "routine discovery must not be added: found $methods",
        )
    }

    // ---------------------------------------------------- ISO-TP regression cover

    @Test
    fun `segmented reply already buffered by the adapter is reassembled`() = runBlocking {
        // Regression: reassembly used to send flow control and then read for
        // the consecutive frames, discarding the ones the adapter had already
        // buffered. Any reply over 7 bytes failed. The VIN is 17 bytes, so a
        // successful read here proves the drain-before-flow-control ordering.
        val engine = engine()
        engine.connect()
        val pcm = engine.discovery.scanBus(
            CanBus.HS_CAN1,
            addresses = listOf(0x7E0),
            readIdentification = false,
        ).single()

        val identification = com.anthonyrohde.truckscan.core.session.ModuleIdentification.read(
            com.anthonyrohde.truckscan.core.uds.UdsClient(
                engine.channel, 0x7E0, 0x7E8, "PCM",
            ),
            listOf(com.anthonyrohde.truckscan.core.session.IdentificationDid.VIN),
        )

        assertEquals("1FT8W2BT7NEC12345", identification.vin)
        assertEquals(17, identification.vin!!.length)
        assertEquals("PCM", pcm.module.code)
    }

    // ------------------------------------------------- learned profile integration

    @Test
    fun `an imported profile makes as built reads use the learned identifiers`() = runBlocking {
        val engine = engine()
        engine.connect()
        val bcm = engine.quickScanVehicle().first { it.module.code == "BCM" }

        // A capture covering only DE00 and DE01, not the whole DE00-DE3F range.
        val profile = com.anthonyrohde.truckscan.core.trace.LearnedProfile.deserialise(
            """
            # truckscan learned profile v1
            # source: bcm-capture.txt
            module 726 72E
              checksum TWOS_COMPLEMENT
              config DE00 410200001C0899
              config DE01 00300000D0
            """.trimIndent(),
        )
        assertNotNull(profile)
        engine.applyLearnedProfile(profile)

        val probed = mutableListOf<Int>()
        val snapshot = engine.snapshotAsBuilt(bcm) { probed += it.currentDid }

        // Exactly the learned identifiers were read - not a 448-wide sweep.
        assertEquals(listOf(0xDE00, 0xDE01), probed)
        assertEquals(2, snapshot.blocks.size)
        assertEquals(
            com.anthonyrohde.truckscan.core.ford.ChecksumStrategy.TWOS_COMPLEMENT,
            snapshot.checksumStrategy,
        )
        assertTrue(snapshot.isRestorable)
    }

    @Test
    fun `without a profile the reader falls back to sweeping`() = runBlocking {
        val engine = engine()
        engine.connect()
        val bcm = engine.quickScanVehicle().first { it.module.code == "BCM" }
        engine.applyLearnedProfile(null)

        val probed = mutableListOf<Int>()
        engine.asBuiltReader.snapshot(bcm, ranges = listOf(0xDE00..0xDE05)) {
            probed += it.currentDid
        }

        assertEquals(6, probed.size, "the whole requested range should be swept")
    }

    @Test
    fun `a profile for a different module does not affect this one`() = runBlocking {
        val engine = engine()
        engine.connect()
        val bcm = engine.quickScanVehicle().first { it.module.code == "BCM" }

        // Profile covers the APIM (7D0), not the BCM (726).
        val profile = com.anthonyrohde.truckscan.core.trace.LearnedProfile.deserialise(
            """
            module 7D0 7D8
              config DE00 0000000500009C
            """.trimIndent(),
        )
        engine.applyLearnedProfile(profile)

        // Falls back to the sweep for the uncovered module rather than reading
        // the other module's identifiers.
        val snapshot = engine.asBuiltReader.snapshot(bcm, ranges = listOf(0xDE00..0xDE05))
        assertEquals(3, snapshot.blocks.size, "BCM's own three blocks")
    }

    // ------------------------------------------------------------------ helpers

}
