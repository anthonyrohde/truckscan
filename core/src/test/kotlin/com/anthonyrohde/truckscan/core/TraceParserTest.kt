package com.anthonyrohde.truckscan.core

import com.anthonyrohde.truckscan.core.trace.TraceLineParser
import com.anthonyrohde.truckscan.core.util.Hex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The parser has to cope with whatever a tool happened to write, so these cover
 * the shapes seen in the wild rather than one blessed format.
 */
class TraceParserTest {

    @Test
    fun `candump hash form`() {
        val frame = TraceLineParser.parseLine("726#0322DE0000000000")
        assertNotNull(frame)
        assertEquals(0x726, frame!!.canId)
        assertEquals("0322DE0000000000", Hex.encode(frame.data))
    }

    @Test
    fun `candump with absolute timestamp and interface`() {
        val frame = TraceLineParser.parseLine("(1718088210.123456) can0 726#0322DE00")
        assertNotNull(frame)
        assertEquals(0x726, frame!!.canId)
        assertEquals("0322DE00", Hex.encode(frame.data))
        assertEquals(1718088210.123456, frame.relativeSeconds!!, 0.001)
    }

    @Test
    fun `clock timestamp with direction marker and spaced bytes`() {
        val frame = TraceLineParser.parseLine("10:23:45.123 TX 0726 03 22 DE 00 00 00 00 00")
        assertNotNull(frame)
        assertEquals(0x726, frame!!.canId)
        assertEquals("0322DE0000000000", Hex.encode(frame.data))
        // 10h 23m 45.123s
        assertEquals(37425.123, frame.relativeSeconds!!, 0.001)
    }

    @Test
    fun `bracketed timestamp with colon after the identifier`() {
        val frame = TraceLineParser.parseLine("[10:23:45.123] 726: 0322DE0000000000")
        assertNotNull(frame)
        assertEquals(0x726, frame!!.canId)
        assertEquals("0322DE0000000000", Hex.encode(frame.data))
    }

    @Test
    fun `iso date with arrow direction and a dlc column`() {
        val frame = TraceLineParser.parseLine(
            "2024-06-11 10:23:45.123  ->  0726  [8]  03 22 DE 00 00 00 00 00",
        )
        assertNotNull(frame)
        assertEquals(0x726, frame!!.canId)
        // The [8] DLC marker must not be spliced into the payload.
        assertEquals("0322DE0000000000", Hex.encode(frame.data))
    }

    @Test
    fun `twenty nine bit identifier`() {
        val frame = TraceLineParser.parseLine("18DAF110#0322DE00")
        assertNotNull(frame)
        assertEquals(0x18DAF110, frame!!.canId)
    }

    @Test
    fun `every format yields the same frame`() {
        val variants = listOf(
            "726#0322DE0000000000",
            "726 03 22 DE 00 00 00 00 00",
            "10:23:45.123 RX 726 0322DE0000000000",
            "[10:23:45.123] 0726: 03 22 DE 00 00 00 00 00",
            "(100.5) can0 726#0322DE0000000000",
        )
        val parsed = variants.map { TraceLineParser.parseLine(it) }

        assertTrue(parsed.all { it != null }, "all variants should parse")
        assertTrue(parsed.all { it!!.canId == 0x726 })
        assertTrue(parsed.all { Hex.encode(it!!.data) == "0322DE0000000000" })
    }

    @Test
    fun `prose comments and blank lines are skipped`() {
        assertNull(TraceLineParser.parseLine(""))
        assertNull(TraceLineParser.parseLine("   "))
        assertNull(TraceLineParser.parseLine("# FORScan trace log"))
        assertNull(TraceLineParser.parseLine("// connecting to adapter"))
        assertNull(TraceLineParser.parseLine("Connecting to vehicle, please wait"))
        assertNull(TraceLineParser.parseLine("Module BCM identified as LC3T-14B476-AKE"))
    }

    @Test
    fun `payload is capped at eight bytes`() {
        // Trailing columns in a log must not run into the payload.
        val frame = TraceLineParser.parseLine("726 03 22 DE 00 00 00 00 00 AA BB CC")
        assertNotNull(frame)
        assertEquals(8, frame!!.data.size)
    }

    @Test
    fun `a line with no payload is not a frame`() {
        assertNull(TraceLineParser.parseLine("726"))
        assertNull(TraceLineParser.parseLine("10:23:45.123 726"))
    }

    @Test
    fun `whole log parses and reports what it skipped`() {
        val log = """
            FORScan trace log
            Connecting to adapter...
            10:23:45.100 TX 726 0322DE0000000000
            10:23:45.150 RX 72E 07 62 DE 00 41 02 00
            not a frame either
        """.trimIndent()

        val (frames, report) = TraceLineParser.parse(log)

        assertEquals(2, frames.size)
        assertEquals(5, report.totalLines)
        assertEquals(3, report.linesSkipped)
        assertTrue(report.sampleSkippedLines.contains("Connecting to adapter..."))
        assertTrue(report.describe().contains("Recognised 2"))
    }

    @Test
    fun `timestamps are normalised to the start of the capture`() {
        val log = """
            10:23:45.000 TX 726 0322DE0000000000
            10:23:46.500 RX 72E 0762DE00410200
        """.trimIndent()

        val (frames, _) = TraceLineParser.parse(log)
        assertEquals(0.0, frames[0].relativeSeconds!!, 0.001)
        assertEquals(1.5, frames[1].relativeSeconds!!, 0.001)
    }

    @Test
    fun `a file with no frames reports why`() {
        val (frames, report) = TraceLineParser.parse("just\nsome\nprose\n")
        assertTrue(frames.isEmpty())
        assertTrue(report.isEmpty)
        assertTrue(report.describe().contains("does not look like a bus trace"))
    }
}
