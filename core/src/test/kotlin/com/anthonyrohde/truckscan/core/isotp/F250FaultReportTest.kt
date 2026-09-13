package com.anthonyrohde.truckscan.core.isotp

import com.anthonyrohde.truckscan.core.adapter.ElmAdapter
import com.anthonyrohde.truckscan.core.dtc.Dtc
import com.anthonyrohde.truckscan.core.transport.ObdTransport
import com.anthonyrohde.truckscan.core.uds.UdsClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Replays a real fault report from a 2022 F-250 PCM, byte for byte.
 *
 * The app once reported a single fault code from this vehicle. The bus returns
 * 344 records - 1379 bytes over 197 consecutive frames, preceded by a
 * "response pending" that arrives in the same adapter read as the first frame
 * of the real answer. Every one of those is a chance to lose the message, and a
 * fault scanner that silently returns one code instead of 344 is worse than one
 * that fails outright.
 *
 * The frames in `f250-dtc-report.txt` are the transcript, unedited.
 */
class F250FaultReportTest {

    private fun transcript(): List<String> =
        checkNotNull(javaClass.getResourceAsStream("/f250-dtc-report.txt")) {
            "the recorded fault report is missing"
        }.bufferedReader().readLines().filter { it.isNotBlank() }

    /**
     * The module answers the request with two messages in one read - the
     * response-pending and the first frame of the real reply - then sends the
     * rest once flow control grants it.
     */
    private class Replay(private val lines: List<String>) : ObdTransport {
        val written = mutableListOf<String>()
        private val pending = ArrayDeque<String>()
        var flowControls = 0
            private set

        override val isOpen = true
        override val description = "f250 replay"
        override suspend fun open() = Unit
        override suspend fun close() = Unit

        override suspend fun write(bytes: ByteArray) {
            val cmd = String(bytes, Charsets.US_ASCII).trim()
            written += cmd
            val reply = when {
                cmd.isEmpty() -> emptyList()
                cmd.startsWith("AT") || cmd.startsWith("ST") -> listOf("OK")
                cmd.startsWith("031902FF") -> lines.take(2)
                cmd.startsWith("30") -> { flowControls++; lines.drop(2) }
                else -> listOf("NO DATA")
            }
            if (reply.isNotEmpty()) pending += reply.joinToString("\r", postfix = "\r>")
        }

        override suspend fun read(timeoutMillis: Long): ByteArray {
            if (pending.isEmpty()) return ByteArray(0)
            return pending.removeFirst().toByteArray(Charsets.US_ASCII)
        }
    }

    @Test
    fun `the whole fault report survives the response-pending and the burst`() = runBlocking {
        val transport = Replay(transcript())
        val channel = IsoTpChannel(ElmAdapter(transport))
        val client = UdsClient(channel, 0x7E0, 0x7E8, "PCM")

        val body = client.readDtcsByStatusMask(0xFF)

        // The module sent 0x563 bytes; UdsClient strips the 0x59 service echo,
        // so the body is the sub-function, the availability mask, and 344
        // four-byte records.
        assertEquals(0x563 - 1, body.size, "the reply was truncated")
        assertEquals(0x02.toByte(), body[0])
        assertEquals(0xFF.toByte(), body[1])
        assertEquals((body.size - 2) % 4, 0, "the records are not 4-byte aligned")

        val dtcs = Dtc.parseDtcListResponse(body)
        assertEquals(344, dtcs.size, "every record must survive, not just the first")
    }

    /**
     * One flow control, and no adapter command between it and the frames it
     * releases. Sending a second would be a protocol error against a module
     * that was granted the whole transfer.
     */
    @Test
    fun `exactly one flow control is sent and nothing follows it mid-burst`() = runBlocking {
        val transport = Replay(transcript())
        val channel = IsoTpChannel(ElmAdapter(transport))
        UdsClient(channel, 0x7E0, 0x7E8, "PCM").readDtcsByStatusMask(0xFF)

        assertEquals(1, transport.flowControls)
        val fc = transport.written.indexOfFirst { it.startsWith("30") }
        assertTrue(
            transport.written.drop(fc + 1).none { it.startsWith("AT") || it.startsWith("ST") },
            "nothing may be sent while the burst is arriving: ${transport.written.drop(fc + 1)}",
        )
    }

    /** The statuses are the diagnosis, so a decode that loses them is useless. */
    @Test
    fun `the status bytes decode to what the truck reported`() = runBlocking {
        val transport = Replay(transcript())
        val channel = IsoTpChannel(ElmAdapter(transport))
        val body = UdsClient(channel, 0x7E0, 0x7E8, "PCM").readDtcsByStatusMask(0xFF)
        val dtcs = Dtc.parseDtcListResponse(body)

        val byStatus = dtcs.groupingBy { it.status.raw }.eachCount()
        assertEquals(mapOf(0x40 to 295, 0x50 to 49), byStatus)

        // Both values are "not completed" bits and nothing else. 0x40 is
        // testNotCompletedThisOperationCycle; 0x50 adds
        // testNotCompletedSinceLastClear. The failure bits - testFailed,
        // testFailedThisOperationCycle, pending, confirmed, and crucially
        // testFailedSinceLastClear at 0x20 - are clear on all 344 records.
        //
        // Worth spelling out, because 0x50 reads like a failure at a glance and
        // was briefly reported as one. This truck has no faults; it has 344
        // monitors that have not run.
        assertTrue(dtcs.none { it.status.testFailed })
        assertTrue(dtcs.none { it.status.testFailedThisOperationCycle })
        assertTrue(dtcs.none { it.status.pending })
        assertTrue(dtcs.none { it.status.confirmed })
        assertTrue(dtcs.none { it.status.testFailedSinceLastClear })
        assertTrue(dtcs.none { it.status.warningIndicatorRequested })
        assertTrue(dtcs.none { it.status.isCurrentlyFailing })
    }

    /**
     * The number a person reads. 344 records, none of them a fault.
     *
     * The report used to count records, so this truck would have shown "344
     * faults found" while being entirely healthy. Anything that says something
     * is wrong - failed, failing this cycle, pending, confirmed, failed since
     * the last clear, or asking for a warning lamp - counts. "Has not run yet"
     * does not.
     */
    @Test
    fun `a healthy truck with 344 records reports no faults`() = runBlocking {
        val transport = Replay(transcript())
        val channel = IsoTpChannel(ElmAdapter(transport))
        val body = UdsClient(channel, 0x7E0, 0x7E8, "PCM").readDtcsByStatusMask(0xFF)
        val dtcs = Dtc.parseDtcListResponse(body)

        assertEquals(344, dtcs.size)
        assertEquals(0, dtcs.count { it.status.isNoteworthy }, "nothing here is a fault")
    }
}
