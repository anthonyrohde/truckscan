package com.anthonyrohde.truckscan.core.session

import com.anthonyrohde.truckscan.core.adapter.ElmAdapter
import com.anthonyrohde.truckscan.core.isotp.IsoTpChannel
import com.anthonyrohde.truckscan.core.pid.PidCatalog
import com.anthonyrohde.truckscan.core.transport.ObdTransport
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * On a real truck, RPM and speed shared one sweep with twenty-nine other
 * parameters and updated once every few seconds - a sweep is only as fast as
 * its slowest member, and a dash cannot show that as live. This is the fix:
 * [LiveDataPoller.streamPrioritized] reads a small fast set every tick and
 * rotates through everything else a few at a time, relying on
 * [LiveValueHold] - already built for a missed sweep - to keep a slow
 * parameter's last reading visible between its own updates.
 */
class PrioritizedStreamTest {

    /** Answers any mode 01 PID with one byte equal to the low byte of the PID. */
    private class Truck : ObdTransport {
        val requestedPids = mutableListOf<Int>()
        private val pending = ArrayDeque<String>()
        override val isOpen = true
        override val description = "prioritized-stream truck"
        override suspend fun open() = Unit
        override suspend fun close() = Unit
        override suspend fun write(bytes: ByteArray) {
            val cmd = String(bytes, Charsets.US_ASCII).trim()
            if (cmd.isEmpty()) return
            if (cmd.startsWith("AT") || cmd.startsWith("ST")) {
                pending += "OK\r>"
                return
            }
            val pid = cmd.removePrefix("02").removePrefix("01").take(2).toInt(16)
            requestedPids += pid
            // PCI 06: six bytes follow - 41, the PID, and four data bytes, more
            // than any catalogue entry needs, so byteCount never trips a decode.
            pending += "7E80641%02X%02X%02X%02X%02X\r>".format(pid, pid, pid, pid, pid)
        }
        override suspend fun read(timeoutMillis: Long): ByteArray {
            if (pending.isEmpty()) return ByteArray(0)
            return pending.removeFirst().toByteArray(Charsets.US_ASCII)
        }
    }

    private fun poller(): LiveDataPoller {
        val adapter = ElmAdapter(Truck())
        return LiveDataPoller(IsoTpChannel(adapter))
    }

    private val rpm = checkNotNull(PidCatalog.byKey("rpm"))
    private val speed = checkNotNull(PidCatalog.byKey("speed"))
    private val coolant = checkNotNull(PidCatalog.byKey("coolant"))
    private val oilTemp = checkNotNull(PidCatalog.byKey("oil_temp"))
    private val voltage = checkNotNull(PidCatalog.byKey("voltage"))

    @Test
    fun `the fast set answers on every tick`() = runBlocking {
        val samples = poller()
            .streamPrioritized(
                fast = listOf(rpm, speed),
                slow = listOf(coolant, oilTemp, voltage),
                intervalMillis = 0,
            )
            .take(6)
            .toList()

        assertEquals(6, samples.size)
        for (sample in samples) {
            assertTrue(rpm.key in sample.values, "rpm missing from a tick: $sample")
            assertTrue(speed.key in sample.values, "speed missing from a tick: $sample")
        }
    }

    /**
     * One slow parameter per tick by default. Three of them means every one
     * has appeared exactly once by the fourth tick's start - not zero, not
     * fast enough to just be luck.
     */
    @Test
    fun `the slow set rotates rather than all arriving at once or never`() = runBlocking {
        val samples = poller()
            .streamPrioritized(
                fast = listOf(rpm),
                slow = listOf(coolant, oilTemp, voltage),
                intervalMillis = 0,
            )
            .take(3)
            .toList()

        // Exactly one slow reading per tick - the fast set is not what is
        // being tested here, so it is left out of the count.
        for (sample in samples) {
            val slowKeysThisTick = sample.values.keys - rpm.key
            assertEquals(1, slowKeysThisTick.size, "expected one slow key: $sample")
        }

        val seenAcrossThreeTicks = samples.flatMap { it.values.keys - rpm.key }.toSet()
        assertEquals(setOf(coolant.key, oilTemp.key, voltage.key), seenAcrossThreeTicks)
    }

    /** A larger chunk catches up faster, at the cost of a heavier tick. */
    @Test
    fun `slowPerTick controls how many of the slow set are read together`() = runBlocking {
        val samples = poller()
            .streamPrioritized(
                fast = listOf(rpm),
                slow = listOf(coolant, oilTemp, voltage),
                intervalMillis = 0,
                slowPerTick = 3,
            )
            .take(1)
            .toList()

        val slowKeys = samples.single().values.keys - rpm.key
        assertEquals(setOf(coolant.key, oilTemp.key, voltage.key), slowKeys)
    }

    /** No slow parameters at all is just [LiveDataPoller.stream] on the fast set. */
    @Test
    fun `an empty slow set still streams the fast one`() = runBlocking {
        val samples = poller()
            .streamPrioritized(fast = listOf(rpm, speed), slow = emptyList(), intervalMillis = 0)
            .take(3)
            .toList()
        for (sample in samples) {
            assertEquals(setOf(rpm.key, speed.key), sample.values.keys)
        }
    }
}
