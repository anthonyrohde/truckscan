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
        health: List<ParameterHealth> = emptyList(),
        timing: LiveHealth.SweepTiming = LiveHealth.SweepTiming(0, 0, 0, 0, 0),
        faults: List<String> = emptyList(),
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
        faults = faults,
        supportedPidCount = 67,
        parametersOffered = 45,
        parametersWatched = 5,
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

    @Test
    fun `an empty module list says no scan was run rather than none found`() {
        assertTrue(report().contains("No module scan has been run"))
    }
}
