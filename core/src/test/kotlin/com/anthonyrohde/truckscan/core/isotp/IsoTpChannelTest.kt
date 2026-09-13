package com.anthonyrohde.truckscan.core.isotp

import com.anthonyrohde.truckscan.core.adapter.ElmAdapter
import com.anthonyrohde.truckscan.core.transport.ObdTransport
import com.anthonyrohde.truckscan.core.util.toHex
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for two faults that passed against the simulator and failed
 * against a 2022 F-250, both found by reading a capture from the vehicle.
 *
 * The simulator could not have caught either: one depends on a module packing
 * two messages into one adapter read, the other on ELM firmware interrupting
 * reception when a command arrives mid-burst. Both are reproduced here from the
 * bytes the truck actually sent.
 */
class IsoTpChannelTest {

    /**
     * Answers AT and ST commands the way an adapter does, and lets a test
     * script what comes back for a data frame.
     *
     * Records every line written, which is how the flow-control ordering is
     * asserted: the bug was not a wrong byte but a correct byte at the wrong
     * moment.
     */
    private class ScriptedTransport(
        private val onDataFrame: (String) -> List<String>,
    ) : ObdTransport {
        /**
         * Writes and reads in one ordered list, because the fault being tested
         * is an ordering fault. Write order alone cannot show it: reading the
         * bus writes nothing, so "ATR1 before the frames" and "ATR1 after the
         * frames" look identical in a list of commands.
         */
        val events = mutableListOf<String>()
        val written get() = events.filter { it.startsWith("W:") }.map { it.removePrefix("W:") }
        private val pending = ArrayDeque<String>()

        override val isOpen = true
        override val description = "scripted"
        override suspend fun open() = Unit
        override suspend fun close() = Unit

        override suspend fun write(bytes: ByteArray) {
            val cmd = String(bytes, Charsets.US_ASCII).trim()
            events += "W:" + cmd
            val reply = when {
                cmd.isEmpty() -> listOf<String>()
                cmd.startsWith("AT") || cmd.startsWith("ST") -> listOf("OK")
                else -> onDataFrame(cmd)
            }
            pending += reply.joinToString("\r", postfix = "\r>")
        }

        override suspend fun read(timeoutMillis: Long): ByteArray {
            if (pending.isEmpty()) return ByteArray(0)
            val chunk = pending.removeFirst()
            events += "R:" + chunk.replace("\r", " ").trim()
            return chunk.toByteArray(Charsets.US_ASCII)
        }
    }

    private fun channel(transport: ScriptedTransport) =
        IsoTpChannel(ElmAdapter(transport)) to transport

    /**
     * The PCM answered `19 02 FF` with a "response pending" single frame and
     * the first frame of the real reply in the same read:
     *
     *     << 7E8037F197800000000 | 7E813235902FF050700
     *
     * The single frame completed and the first frame went in the bin, so the
     * deferred receive waited for something that had already arrived while the
     * module waited for flow control that was never sent. Five second stall,
     * every time.
     */
    @Test
    fun `first frame arriving beside a response pending is not discarded`() = runBlocking {
        val transport = ScriptedTransport { cmd ->
            when {
                // The 19 02 FF request: pending, then the real reply's first
                // frame, both in one read - exactly as logged.
                cmd.startsWith("031902FF") -> listOf(
                    "7E8037F197800000000",
                    "7E81009590201020304",
                )
                // Flow control: consecutive frames follow.
                cmd.startsWith("3000") -> listOf("7E8210506070809")
                else -> listOf("NO DATA")
            }
        }
        val (ch, _) = channel(transport)

        val pending = ch.request(0x7E0, 0x7E8, byteArrayOf(0x19, 0x02, 0xFF.toByte()))
        assertEquals("7F1978", pending.toHex(), "the pending reply should come back first")

        // The real answer must assemble from the first frame that arrived
        // alongside it, without the adapter being read for it again.
        val real = ch.receive(0x7E8, 1_000)
        assertEquals("590201020304050607", real.toHex().take(18))
    }

    /**
     * After flow control goes out the module streams consecutive frames
     * immediately. Restoring ATR1 at that moment put an AT command into the
     * middle of the burst, and ELM firmware services the command instead of
     * the bus - the frames were gone before anything read them.
     *
     * The fix is an ordering guarantee, so that is what this asserts.
     */
    @Test
    fun `no command is sent between flow control and the consecutive frames`() = runBlocking {
        val transport = ScriptedTransport { cmd ->
            when {
                cmd.startsWith("0322F190") -> listOf("7E81009620F1901020304")
                cmd.startsWith("3000") -> listOf("7E8210506070809")
                else -> listOf("NO DATA")
            }
        }
        val (ch, t) = channel(transport)

        runCatching { ch.request(0x7E0, 0x7E8, byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte())) }

        val fc = t.events.indexOfFirst { it.startsWith("W:3000") }
        val consecutive = t.events.indexOfFirst { it.startsWith("R:") && it.contains("7E821") }
        val restore = t.events.indexOfFirst { it == "W:ATR1" }

        assertTrue(fc >= 0, "flow control should have been sent: ${t.events}")
        assertTrue(consecutive > fc, "consecutive frames should follow flow control: ${t.events}")
        assertTrue(
            restore > consecutive,
            "ATR1 was restored before the consecutive frames were read, which is " +
                "the command that landed mid-burst on the vehicle: ${t.events}",
        )
    }

    /** Responses must be re-enabled once the message is in, not left off. */
    @Test
    fun `responses are restored after reassembly finishes`() = runBlocking {
        val transport = ScriptedTransport { cmd ->
            when {
                cmd.startsWith("0322F190") -> listOf("7E81009620F1901020304")
                cmd.startsWith("3000") -> listOf("7E8210506070809")
                else -> listOf("NO DATA")
            }
        }
        val (ch, t) = channel(transport)

        runCatching { ch.request(0x7E0, 0x7E8, byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte())) }

        val fcIndex = t.written.indexOfFirst { it.startsWith("3000") }
        assertTrue(
            t.written.drop(fcIndex).any { it == "ATR1" },
            "ATR1 must be restored after the message completes: ${t.events}",
        )
    }
}
