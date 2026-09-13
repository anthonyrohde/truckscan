package com.anthonyrohde.truckscan.core.session

import com.anthonyrohde.truckscan.core.pid.Pid
import com.anthonyrohde.truckscan.core.pid.PidValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveHealthTest {

    private fun pid(key: String, name: String) = Pid(
        key = key, id = 0x05, name = name, unit = "", byteCount = 1,
        minValue = 0.0, maxValue = 100.0, decoder = { 0.0 },
    )

    private val coolant = pid("coolant", "Engine coolant temperature")
    private val egt = pid("egt1", "Exhaust gas temperature 1")

    @Test
    fun `counts a parameter that always answers and one that never does`() {
        val health = LiveHealth()
        repeat(4) { i ->
            health.accept(
                LiveDataSample(
                    values = mapOf("coolant" to PidValue(coolant, 88.0, ByteArray(0), i * 100L)),
                    failedKeys = setOf("egt1"),
                    sweepMillis = 480,
                ),
            )
        }

        val byKey = health.parameters().associateBy { it.key }
        assertEquals(100, byKey.getValue("coolant").percent)
        assertEquals(0, byKey.getValue("egt1").percent)
        assertTrue(byKey.getValue("egt1").neverAnswered)
        assertFalse(byKey.getValue("coolant").neverAnswered)
    }

    @Test
    fun `worst parameter is listed first`() {
        val health = LiveHealth()
        health.accept(
            LiveDataSample(
                values = mapOf("coolant" to PidValue(coolant, 88.0, ByteArray(0), 0)),
                failedKeys = setOf("egt1"),
                sweepMillis = 400,
            ),
        )
        assertEquals("egt1", health.parameters().first().key)
    }

    /** An average would hide a stutter. The split is the whole point. */
    @Test
    fun `timing separates fast sweeps from ones that hit a timeout`() {
        val health = LiveHealth()
        listOf(480L, 470L, 2_860L, 500L, 2_900L).forEach {
            health.accept(LiveDataSample(values = emptyMap(), sweepMillis = it))
        }

        val timing = health.timing()
        assertEquals(5, timing.count)
        assertEquals(2, timing.slowCount)
        assertEquals(2_900L, timing.slowMedianMillis)
        assertEquals(480L, timing.fastMedianMillis)
    }

    @Test
    fun `timing of nothing is empty rather than an error`() {
        assertEquals(0, LiveHealth().timing().count)
    }
}

class DiagnosticReportTest {

    private fun report(
        modules: List<DiagnosticReport.ModuleLine> = emptyList(),
        moduleScan: DiagnosticReport.ModuleScan? = null,
        health: List<ParameterHealth> = emptyList(),
        timing: LiveHealth.SweepTiming = LiveHealth.SweepTiming(0, 0, 0, 0, 0),
        faults: List<String> = emptyList(),
        monitorsNotRun: Int = 0,
        // A fault scan was run against this many modules. Left at 0 means
        // "never scanned", which several tests below deliberately exercise;
        // anything that supplies faults or a monitor count should also say a
        // scan happened, or it is testing a state that cannot occur.
        faultScanModuleCount: Int = 0,
        unreadableModuleCount: Int = 0,
    ) = DiagnosticReport(
        generatedAtMillis = 1_757_000_000_000,
        appVersion = "1.0.0 (build 2)",
        device = "Google Pixel 9",
        androidVersion = "17 (API 37)",
        adapter = "STN2231 v5.8.1",
        transport = "OBDLink EX @ 115 kbaud",
        multiBus = true,
        activeBus = "HS-CAN1",
        connection = "Connected",
        modules = modules,
        moduleScan = moduleScan,
        faults = faults,
        monitorsNotRun = monitorsNotRun,
        faultScanModuleCount = faultScanModuleCount,
        unreadableModuleCount = unreadableModuleCount,
        supportedPidCount = 67,
        parametersOffered = 45,
        parametersWatched = 5,
        parametersSelected = 5,
        health = health,
        timing = timing,
        recentLog = listOf(">> ATI", "<< ELM327 v1.4b"),
    ).render()

    @Test
    fun `header carries what is needed to answer questions about a report`() {
        val text = report()
        assertTrue(text.contains("STN2231 v5.8.1"), text)
        assertTrue(text.contains("Google Pixel 9"), text)
        assertTrue(text.contains("OBDLink EX @ 115 kbaud"), text)
        assertTrue(text.contains("1.0.0 (build 2)"), text)
    }

    /** Modules that did not answer are the point, so they sort to the top. */
    @Test
    fun `modules that missed are listed before ones that answered`() {
        val text = report(
            modules = listOf(
                DiagnosticReport.ModuleLine("PCM", "7E0", "HS-CAN1", answered = true),
                DiagnosticReport.ModuleLine("APIM", "7D0", "HS-CAN2", answered = false, detail = "no response"),
            ),
        )
        val lines = text.lines().filter { it.contains("PCM") || it.contains("APIM") }
        assertTrue(lines.first().contains("APIM"), lines.toString())
        assertTrue(text.contains("1 of 2 answered"), text)
    }

    @Test
    fun `a parameter that never answered is called out as unsupported`() {
        val text = report(
            health = listOf(
                ParameterHealth("egt1", "Exhaust gas temperature 1", attempts = 40, successes = 0),
                ParameterHealth("coolant", "Engine coolant temperature", attempts = 40, successes = 40),
            ),
        )
        assertTrue(text.contains("never answered"), text)
        assertTrue(text.contains("Exhaust gas temperature 1"), text)
    }

    @Test
    fun `an intermittent parameter is distinguished from a dead one`() {
        val text = report(
            health = listOf(
                ParameterHealth("rpm", "Engine speed", attempts = 100, successes = 78),
            ),
        )
        assertTrue(text.contains("intermittent"), text)
        assertFalse(text.contains("never answered"), text)
    }

    @Test
    fun `slow sweeps are reported with what they mean`() {
        val text = report(timing = LiveHealth.SweepTiming(78, 500, 19, 2_860, 480))
        assertTrue(text.contains("19 of 78"), text)
        assertTrue(text.contains("timed out"), text)
    }

    @Test
    fun `a session with no live data says so rather than showing zeroes`() {
        val text = report()
        assertTrue(text.contains("Live data has not been started"), text)
    }

    /**
     * The three states are genuinely different and must never be conflated.
     * Reporting "no scan has been run" to somebody who just ran one sends them
     * to look for a bug in the app rather than at the key in the ignition.
     */
    @Test
    fun `never scanned is distinct from scanned and silent`() {
        assertTrue(report().contains("No module scan has been run this session"))

        val silent = report(
            moduleScan = DiagnosticReport.ModuleScan(
                ranAtMillis = 1_757_000_000_000, full = true, found = 0,
            ),
        )
        assertFalse(silent.contains("No module scan has been run"), silent)
        assertTrue(silent.contains("no module answered"), silent)
        assertTrue(silent.contains("full sweep"), silent)
    }

    /**
     * A sweep that found nothing on a sleeping bus and one that found nothing
     * on a live bus are different problems. The first is a key in the wrong
     * position; the second is worth reading the log over.
     */
    @Test
    fun `a silent bus is named as the reason, and a live one is not`() {
        val asleep = report(
            moduleScan = DiagnosticReport.ModuleScan(
                ranAtMillis = 1_757_000_000_000, full = true, found = 0,
                quietBuses = listOf("HS-CAN1", "MS-CAN"),
            ),
        )
        assertTrue(asleep.contains("HS-CAN1, MS-CAN"), asleep)
        // Recorded, but explicitly not treated as a diagnosis.
        assertTrue(asleep.contains("means very little"), asleep)

        val awake = report(
            moduleScan = DiagnosticReport.ModuleScan(
                ranAtMillis = 1_757_000_000_000, full = true, found = 0,
            ),
        )
        assertTrue(awake.contains("none replied"), awake)
    }

    @Test
    fun `results thrown away by a disconnect say so rather than reading as silence`() {
        val text = report(
            moduleScan = DiagnosticReport.ModuleScan(
                ranAtMillis = 1_757_000_000_000, full = true, found = 6, discarded = true,
            ),
        )
        assertTrue(text.contains("disconnect"), text)
        assertTrue(text.contains("found 6"), text)
        assertFalse(text.contains("no module answered"), text)
    }

    @Test
    fun `a scan that found modules is stamped with when and what kind`() {
        val text = report(
            modules = listOf(
                DiagnosticReport.ModuleLine("PCM", "7E0", "HS-CAN1", answered = true),
            ),
            moduleScan = DiagnosticReport.ModuleScan(
                ranAtMillis = 1_757_000_000_000, full = false, found = 1,
            ),
        )
        assertTrue(text.contains("quick scan at"), text)
    }
    /**
     * The report exists to be read by someone hunting a problem.
     *
     * A real one from a 2022 F-250 listed three confirmed faults - two at the
     * body module and a low-voltage code at the SYNC module - under 439 lines
     * of "not tested this cycle", because a status-mask read returns a module's
     * whole DTC table and all of it was being printed. The three that mattered
     * were invisible.
     */
    @Test
    fun `monitors that have not run are counted, not listed`() {
        val text = report(
            faults = listOf(
                "BCM  B156B:15  circuit short to battery or open [confirmed]",
                "APIM  U3003:16  Battery voltage out of range [confirmed]",
            ),
            monitorsNotRun = 341,
            faultScanModuleCount = 4,
        )

        assertTrue(text.contains("B156B:15"), text)
        assertTrue(text.contains("U3003:16"), text)
        assertTrue(text.contains("341 further record(s)"), text)
        assertTrue(text.contains("They are not faults"), text)
        // The three real ones must be findable without scrolling past hundreds.
        assertTrue(
            text.lines().count { it.trim().startsWith("BCM") || it.trim().startsWith("APIM") } == 2,
            "only the faults themselves should be listed",
        )
    }

    @Test
    fun `a clean module says nothing about monitors it did not have to count`() {
        assertFalse(report().contains("further record"))
    }

    /**
     * The actual bug, reproduced on a real truck: a fault scan ran, one of
     * four modules answered clean and three could not be read, and the report
     * said "None recorded, or no scan has been run" - indistinguishable from
     * never having scanned at all. An empty faults list means three different
     * things and each needs its own sentence.
     */
    @Test
    fun `never scanned, scanned clean, and scanned with unreadable modules all read differently`() {
        val neverScanned = report()
        assertTrue(neverScanned.contains("None recorded, or no scan has been run"), neverScanned)

        val scannedClean = report(faultScanModuleCount = 4)
        assertFalse(scannedClean.contains("no scan has been run"), scannedClean)
        assertTrue(scannedClean.contains("No faults in the modules that were read"), scannedClean)

        val partiallyUnreadable = report(faultScanModuleCount = 4, unreadableModuleCount = 3)
        assertTrue(
            partiallyUnreadable.contains("No faults in the modules that were read"),
            "the one module that did answer clean must still be reported: $partiallyUnreadable",
        )
        assertTrue(
            partiallyUnreadable.contains("3 module(s) could not be read"),
            partiallyUnreadable,
        )
        assertTrue(
            partiallyUnreadable.contains("not a clean bill of health"),
            partiallyUnreadable,
        )
    }

class DiagnosticReportSelectionTest {

    /**
     * Ticking more parameters after starting a stream changes the selection but
     * not what is being polled. Reporting the selection made five healthy
     * parameters look like five out of twenty-seven.
     */
    @Test
    fun `a selection changed mid-session is called out rather than read as failure`() {
        val text = DiagnosticReport(
            generatedAtMillis = 0,
            appVersion = "1.0.0",
            device = "Pixel",
            androidVersion = "17",
            adapter = "STN2231",
            transport = "USB",
            multiBus = true,
            activeBus = "HS-CAN1",
            connection = "Connected",
            modules = emptyList(),
            moduleScan = null,
            faults = emptyList(),
            supportedPidCount = 67,
            parametersOffered = 27,
            parametersWatched = 5,
            parametersSelected = 27,
            health = emptyList(),
            timing = LiveHealth.SweepTiming(183, 240, 0, 0, 240),
            recentLog = emptyList(),
        ).render()

        assertTrue(text.contains("Parameters streaming"), text)
        assertTrue(text.contains("not a failure"), text)
    }

}
}
