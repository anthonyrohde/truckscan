package com.anthonyrohde.truckscan.core.probe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The allowlist is the safety boundary of the whole probe feature, so it is
 * tested from both directions: the things that must go through, and every
 * service that must not, named one at a time.
 */
class ProbeScriptTest {

    private fun one(line: String) = ProbeScript.parse(line).lines.first()

    // ------------------------------------------------------------ allowed

    @Test
    fun `adapter commands pass through`() {
        for (cmd in listOf("ATI", "ATZ", "ATSH 7E0", "ATCRA 7E8", "STPBR 500000", "ATMA")) {
            assertInstanceOf(ProbeScript.Line.Adapter::class.java, one(cmd), cmd)
        }
    }

    @Test
    fun `tester present is allowed because it changes nothing`() {
        val line = assertInstanceOf(ProbeScript.Line.Frame::class.java, one("023E000000000000"))
        assertEquals(0x3E, line.service)
    }

    @Test
    fun `reading data and fault codes is allowed`() {
        assertInstanceOf(ProbeScript.Line.Frame::class.java, one("0322F19000000000"))
        assertInstanceOf(ProbeScript.Line.Frame::class.java, one("031902FF00000000"))
        assertInstanceOf(ProbeScript.Line.Frame::class.java, one("0201050000000000"))
        assertInstanceOf(ProbeScript.Line.Frame::class.java, one("0103000000000000"))
    }

    /** Needed to answer the flow-control question, and carries no service. */
    @Test
    fun `flow control is allowed`() {
        val line = assertInstanceOf(ProbeScript.Line.Frame::class.java, one("3000000000000000"))
        assertEquals(null, line.service)
        assertTrue(line.description.contains("flow control"))
    }

    @Test
    fun `comments and blank lines are kept but send nothing`() {
        val parsed = ProbeScript.parse("# a note\n\nATI\n")
        assertEquals(1, parsed.willSend)
        assertTrue(parsed.isSafe)
    }

    @Test
    fun `a trailing comment does not stop the command running`() {
        assertInstanceOf(ProbeScript.Line.Adapter::class.java, one("ATI   # identify"))
    }

    // ------------------------------------------------------------ refused

    /**
     * Every one named individually. A list like this is worth the repetition:
     * if somebody later widens the allowlist, the test that breaks tells them
     * exactly which door they opened.
     */
    @Test
    fun `services that change the vehicle are refused`() {
        val cases = mapOf(
            "0211000000000000" to "resets",
            "0414FFFFFF000000" to "erases",
            "0227010000000000" to "security",
            "022801000000000 0" to "communicating",
            "062E F186 01 02 00" to "writes",
            "04310101FF000000" to "routine",
            "0210030000000000" to "session",
            "032F F0 00 000000" to "inputs",
            "0285020000000000" to "fault code recording",
        )
        for ((frame, expectation) in cases) {
            val line = one(frame)
            val refused = assertInstanceOf(
                ProbeScript.Line.Refused::class.java, line, "should be refused: $frame",
            )
            assertTrue(
                refused.reason.contains(expectation, ignoreCase = true),
                "reason for $frame was '${refused.reason}'",
            )
        }
    }

    @Test
    fun `an unknown service is refused rather than assumed harmless`() {
        val refused = assertInstanceOf(ProbeScript.Line.Refused::class.java, one("02BB000000000000"))
        assertTrue(refused.reason.contains("not on the read-only list"), refused.reason)
    }

    @Test
    fun `firmware transfer services are all refused`() {
        for (service in listOf(0x34, 0x35, 0x36, 0x37)) {
            val frame = "02" + service.toString(16).padStart(2, '0') + "000000000000"
            assertInstanceOf(ProbeScript.Line.Refused::class.java, one(frame), frame)
        }
    }

    /** Persists across a power cycle, so it outlives the script that set it. */
    @Test
    fun `setting a programmable parameter is refused`() {
        val refused = assertInstanceOf(
            ProbeScript.Line.Refused::class.java, one("AT PP 2F SV 19"),
        )
        assertTrue(refused.reason.contains("persists"), refused.reason)
        // Reading them back is harmless.
        assertInstanceOf(ProbeScript.Line.Adapter::class.java, one("ATPPS"))
    }

    /**
     * Found by the test that checks the shipped investigations: @1 is a normal
     * ELM327 command and was being refused as malformed hex.
     */
    @Test
    fun `at-sign commands are handled, and the one that writes is refused`() {
        assertInstanceOf(ProbeScript.Line.Adapter::class.java, one("@1"))
        assertInstanceOf(ProbeScript.Line.Adapter::class.java, one("@2"))

        val refused = assertInstanceOf(ProbeScript.Line.Refused::class.java, one("@3 TRUCKSCAN"))
        assertTrue(refused.reason.contains("persists"), refused.reason)
    }

    @Test
    fun `malformed input is refused rather than sent`() {
        assertInstanceOf(ProbeScript.Line.Refused::class.java, one("hello world"))
        assertInstanceOf(ProbeScript.Line.Refused::class.java, one("0123456789ABCDEF01"))
    }

    // ------------------------------------------------------------ summary

    @Test
    fun `a script is only safe when nothing in it was refused`() {
        assertTrue(ProbeScript.parse("ATI\n023E000000000000").isSafe)

        val mixed = ProbeScript.parse("ATI\n0211000000000000")
        assertFalse(mixed.isSafe)
        assertEquals(1, mixed.refusals.size)
        assertEquals(1, mixed.willSend)
    }

    @Test
    fun `the description says what will happen before anything runs`() {
        val safe = ProbeScript.describe(ProbeScript.parse("ATI\n023E000000000000"))
        assertTrue(safe.contains("2 command(s)"), safe)
        assertTrue(safe.contains("Nothing in this script can change the vehicle"), safe)

        val unsafe = ProbeScript.describe(ProbeScript.parse("0211000000000000"))
        assertTrue(unsafe.contains("will NOT be sent"), unsafe)
        assertTrue(unsafe.contains("resets the module"), unsafe)
    }
}
