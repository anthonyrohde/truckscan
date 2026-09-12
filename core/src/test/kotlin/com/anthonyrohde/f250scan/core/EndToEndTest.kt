package com.anthonyrohde.f250scan.core

import com.anthonyrohde.f250scan.core.adapter.CanBus
import com.anthonyrohde.f250scan.core.ford.ChecksumStrategy
import com.anthonyrohde.f250scan.core.pid.PidCatalog
import com.anthonyrohde.f250scan.core.session.BlockChange
import com.anthonyrohde.f250scan.core.session.ConnectionState
import com.anthonyrohde.f250scan.core.session.DiagnosticEngine
import com.anthonyrohde.f250scan.core.session.WriteBlocker
import com.anthonyrohde.f250scan.core.session.WriteOutcome
import com.anthonyrohde.f250scan.core.transport.SimulatedVehicleTransport
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

        val reparsed = com.anthonyrohde.f250scan.core.ford.AsBuiltBlock.parseAll(text)
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

    // ------------------------------------------------------- As-Built write path

    @Test
    fun `write is refused without a backup`() = runBlocking {
        val engine = engine()
        engine.connect()
        val bcm = engine.quickScanVehicle().first { it.module.code == "BCM" }

        val outcome = engine.asBuiltWriter.write(bcm, backup = null, changes = emptyList())

        val blocked = assertInstanceOf(WriteOutcome.Blocked::class.java, outcome)
        assertInstanceOf(WriteBlocker.NoBackup::class.java, blocked.blocker)
    }

    @Test
    fun `write is refused when supply voltage is too low`() = runBlocking {
        val engine = engine()
        engine.connect()
        val bcm = engine.quickScanVehicle().first { it.module.code == "BCM" }
        val snapshot = engine.asBuiltReader.snapshot(bcm, ranges = listOf(0xDE00..0xDE0F))

        val change = changeFirstByte(snapshot)
        val outcome = engine.asBuiltWriter.write(
            bcm, snapshot, listOf(change), measuredVoltage = 11.4,
        )

        val blocked = assertInstanceOf(WriteOutcome.Blocked::class.java, outcome)
        val lowVoltage = assertInstanceOf(WriteBlocker.LowVoltage::class.java, blocked.blocker)
        assertTrue(lowVoltage.explanation.contains("11.40 V"), lowVoltage.explanation)
        assertTrue(lowVoltage.explanation.contains("charger"))
    }

    @Test
    fun `write is refused when nothing actually changed`() = runBlocking {
        val engine = engine()
        engine.connect()
        val bcm = engine.quickScanVehicle().first { it.module.code == "BCM" }
        val snapshot = engine.asBuiltReader.snapshot(bcm, ranges = listOf(0xDE00..0xDE0F))

        val noOp = BlockChange(
            did = snapshot.sourceDids.first(),
            before = snapshot.blocks.first(),
            after = snapshot.blocks.first(),
        )
        val outcome = engine.asBuiltWriter.write(bcm, snapshot, listOf(noOp), 14.0)

        val blocked = assertInstanceOf(WriteOutcome.Blocked::class.java, outcome)
        assertInstanceOf(WriteBlocker.NothingToDo::class.java, blocked.blocker)
    }

    @Test
    fun `write stops at security access and says why, leaving the module untouched`() = runBlocking {
        val engine = engine()
        engine.connect()
        val bcm = engine.quickScanVehicle().first { it.module.code == "BCM" }
        val snapshot = engine.asBuiltReader.snapshot(bcm, ranges = listOf(0xDE00..0xDE0F))

        val outcome = engine.asBuiltWriter.write(
            bcm, snapshot, listOf(changeFirstByte(snapshot)), measuredVoltage = 14.0,
        )

        // This is the realistic outcome on a current Ford: the module issues a
        // seed and refuses every key we can compute.
        val blocked = assertInstanceOf(WriteOutcome.Blocked::class.java, outcome)
        val denied = assertInstanceOf(WriteBlocker.SecurityDenied::class.java, blocked.blocker)
        assertTrue(
            denied.explanation.contains("not known to this app") ||
                denied.explanation.contains("Ford proprietary"),
            "must explain the limitation honestly: ${denied.explanation}",
        )

        // And critically: the module's configuration is unchanged.
        val after = engine.asBuiltReader.snapshot(bcm, ranges = listOf(0xDE00..0xDE0F))
        assertEquals(
            snapshot.blocks, after.blocks,
            "a blocked write must not have modified the module",
        )
    }

    @Test
    fun `block change describes the exact bytes that differ`() = runBlocking {
        val engine = engine()
        engine.connect()
        val bcm = engine.quickScanVehicle().first { it.module.code == "BCM" }
        val snapshot = engine.asBuiltReader.snapshot(bcm, ranges = listOf(0xDE00..0xDE0F))

        val description = changeFirstByte(snapshot).describe()

        assertTrue(description.contains("before:"), description)
        assertTrue(description.contains("after:"), description)
        assertTrue(description.contains("changed byte(s): 0"), description)
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
            com.anthonyrohde.f250scan.core.session.RoutineResult.Completed::class.java,
            result,
        )
    }

    @Test
    fun `no routine discovery function exists`() {
        // Deliberate: sweeping RoutineControl identifiers would command unknown
        // functions on a live vehicle. If someone adds one, this should fail.
        val methods = com.anthonyrohde.f250scan.core.session.ServiceRoutineRunner::class.java
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

        val identification = com.anthonyrohde.f250scan.core.session.ModuleIdentification.read(
            com.anthonyrohde.f250scan.core.uds.UdsClient(
                engine.channel, 0x7E0, 0x7E8, "PCM",
            ),
            listOf(com.anthonyrohde.f250scan.core.session.IdentificationDid.VIN),
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
        val profile = com.anthonyrohde.f250scan.core.trace.LearnedProfile.deserialise(
            """
            # f250scan learned profile v1
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
            com.anthonyrohde.f250scan.core.ford.ChecksumStrategy.TWOS_COMPLEMENT,
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
        val profile = com.anthonyrohde.f250scan.core.trace.LearnedProfile.deserialise(
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

    /** Produces a change that flips one byte of the first configuration block. */
    private fun changeFirstByte(
        snapshot: com.anthonyrohde.f250scan.core.ford.AsBuiltSnapshot,
    ): BlockChange {
        val before = snapshot.blocks.first()
        val edited = before.data.copyOf().also { it[0] = (it[0] + 1).toByte() }
        return BlockChange(
            did = snapshot.sourceDids.first { it >= 0xDE00 },
            before = before,
            after = before.copy(data = edited),
        )
    }
}
