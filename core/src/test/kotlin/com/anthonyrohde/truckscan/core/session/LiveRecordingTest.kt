package com.anthonyrohde.truckscan.core.session

import com.anthonyrohde.truckscan.core.pid.Pid
import com.anthonyrohde.truckscan.core.pid.PidValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveRecordingTest {

    private fun pid(key: String, name: String, unit: String) = Pid(
        key = key,
        id = 0x0C,
        name = name,
        unit = unit,
        byteCount = 2,
        minValue = 0.0,
        maxValue = 8_000.0,
        decoder = { 0.0 },
    )

    private fun sample(millis: Long, vararg readings: Pair<Pid, Double>) = LiveDataSample(
        values = readings.associate { (p, v) ->
            p.key to PidValue(p, v, ByteArray(0), millis)
        },
        timestampMillis = millis,
    )

    private val rpm = pid("rpm", "Engine speed", "rpm")
    private val coolant = pid("coolant", "Coolant", "C")

    @Test
    fun `csv carries a header and one row per sweep`() {
        val rec = LiveRecording()
        rec.add(sample(1_000, rpm to 812.0, coolant to 88.0))
        rec.add(sample(1_500, rpm to 1904.0, coolant to 89.0))

        val lines = rec.toCsv().trim().lines()
        assertEquals("time_utc,elapsed_s,Engine speed (rpm),Coolant (C)", lines[0])
        assertEquals(3, lines.size)
        assertTrue(lines[1].endsWith(",0,812,88"), lines[1])
        assertTrue(lines[2].endsWith(",0.5,1904,89"), lines[2])
    }

    /**
     * A sensor that only reports once the engine is warm turns up part way
     * through. Its column has to exist for the whole file, blank until it does.
     */
    @Test
    fun `a parameter appearing later gets a column and blanks before it`() {
        val egt = pid("egt1", "EGT 1", "C")
        val rec = LiveRecording()
        rec.add(sample(0, rpm to 800.0))
        rec.add(sample(1_000, rpm to 850.0, egt to 320.0))

        val lines = rec.toCsv().trim().lines()
        assertEquals("time_utc,elapsed_s,Engine speed (rpm),EGT 1 (C)", lines[0])
        assertTrue(lines[1].endsWith(",800,"), "missing reading should be blank: ${lines[1]}")
        assertTrue(lines[2].endsWith(",850,320"), lines[2])
    }

    /**
     * A gap is the truth. Zero would read as a real measurement of nothing,
     * and on a temperature trace that is a very different story.
     */
    @Test
    fun `a sweep that did not decode leaves the cell blank rather than zero`() {
        val rec = LiveRecording()
        rec.add(sample(0, rpm to 800.0, coolant to 88.0))
        rec.add(sample(500, rpm to 810.0, coolant to Double.NaN))

        val lines = rec.toCsv().trim().lines()
        assertTrue(lines[1].endsWith(",800,88"), lines[1])
        assertTrue(lines[2].endsWith(",810,"), "the gap should be empty: ${lines[2]}")
    }

    /** A parameter that never decodes at all earns no column. */
    @Test
    fun `a parameter that never decodes gets no column`() {
        val rec = LiveRecording()
        rec.add(sample(0, rpm to 800.0, coolant to Double.NaN))

        assertEquals("time_utc,elapsed_s,Engine speed (rpm)", rec.toCsv().lines()[0])
    }

    @Test
    fun `whole numbers lose the decimal point and fractions keep it`() {
        assertEquals("812", LiveRecording.trimNumber(812.0, 3))
        assertEquals("88.5", LiveRecording.trimNumber(88.5, 3))
        assertEquals("14.237", LiveRecording.trimNumber(14.23749, 3))
        assertEquals("-3.25", LiveRecording.trimNumber(-3.25, 3))
        assertEquals("", LiveRecording.trimNumber(Double.NaN, 3))
    }

    /** Exponent notation is not read back as a number by every spreadsheet. */
    @Test
    fun `small magnitudes stay in plain notation`() {
        val text = LiveRecording.trimNumber(0.0001, 4)
        assertFalse(text.contains("E") || text.contains("e"), "got $text")
        assertEquals("0.0001", text)
    }

    @Test
    fun `timestamps are sortable utc`() {
        assertEquals("1970-01-01T00:00:01.500Z", LiveRecording.isoUtc(1_500))
    }

    /** A heading containing a comma must not split the row. */
    @Test
    fun `headings are quoted when they would break the row`() {
        val odd = pid("x", "Boost, corrected", "kPa")
        val rec = LiveRecording()
        rec.add(sample(0, odd to 101.0))

        val header = rec.toCsv().lines()[0]
        assertTrue(header.contains("\"Boost, corrected (kPa)\""), header)
    }

    @Test
    fun `recording stops accepting rows at the cap rather than growing forever`() {
        val rec = LiveRecording(maxRows = 3)
        repeat(3) { assertTrue(rec.add(sample(it * 100L, rpm to 800.0))) }
        assertTrue(rec.isFull)
        assertFalse(rec.add(sample(400, rpm to 800.0)))
        assertEquals(3, rec.rowCount)
    }

    @Test
    fun `duration spans first sample to last`() {
        val rec = LiveRecording()
        rec.add(sample(10_000, rpm to 800.0))
        rec.add(sample(25_000, rpm to 800.0))
        assertEquals(15.0, rec.durationSeconds)
    }
}
