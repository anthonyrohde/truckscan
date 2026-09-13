package com.anthonyrohde.truckscan.core.session

import com.anthonyrohde.truckscan.core.adapter.ElmAdapter
import com.anthonyrohde.truckscan.core.isotp.IsoTpChannel
import com.anthonyrohde.truckscan.core.pid.PidCatalog
import com.anthonyrohde.truckscan.core.transport.ObdTransport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What happens when the vehicle says it does not have a parameter.
 *
 * This is the shape of a real failure. A 2022 F-250 offers 27 of the
 * catalogue's parameters and refuses the rest with 7F 01 31 in about fifty
 * milliseconds. The poller only recognised a positive 41-echo as an answer, so
 * a refusal was treated as "not the reply I wanted" and it waited out the full
 * one-second timeout - per parameter, per sweep. Forty parameters the engine
 * does not have made a forty-second sweep, and during each of those idle
 * seconds a late reply to the previous parameter arrived and was consumed by
 * the wrong request, so the sweep walked out of step and parameters the vehicle
 * definitely supports went blank too.
 *
 * On the screen that looked like "most of the live statistics are not reporting
 * any values", which is a description of a display problem and was nothing of
 * the kind.
 */
class LiveRefusalTest {

    /** Answers a small set of parameters and refuses everything else. */
    private class Truck(private val supports: Set<Int>) : ObdTransport {
        val requestedPids = mutableListOf<Int>()
        private val pending = ArrayDeque<String>()

        override val isOpen = true
        override val description = "refusing truck"
        override suspend fun open() = Unit
        override suspend fun close() = Unit

        override suspend fun write(bytes: ByteArray) {
            val cmd = String(bytes, Charsets.US_ASCII).trim()
            val reply = when {
                cmd.isEmpty() -> return
                cmd.startsWith("AT") || cmd.startsWith("ST") -> listOf("OK")
                cmd.startsWith("02 01") || cmd.startsWith("0201") -> {
                    val pid = cmd.removePrefix("02").removePrefix("01")
                        .replace(" ", "").take(2).toInt(16)
                    requestedPids += pid
                    if (pid in supports) {
                        // 41 <pid> <one byte of value>
                        listOf("7E8 03 41 %02X 40".format(pid).replace(" ", ""))
                    } else {
                        listOf("7E8037F013100000000")
                    }
                }
                else -> listOf("NO DATA")
            }
            pending += reply.joinToString("\r", postfix = "\r>")
        }

        override suspend fun read(timeoutMillis: Long): ByteArray {
            if (pending.isEmpty()) return ByteArray(0)
            return pending.removeFirst().toByteArray(Charsets.US_ASCII)
        }
    }

    private fun poller(truck: Truck): LiveDataPoller {
        val adapter = ElmAdapter(truck)
        return LiveDataPoller(IsoTpChannel(adapter))
    }

    /** Coolant is supported here; throttle is not. */
    private val coolant = checkNotNull(PidCatalog.byKey("coolant"))
    private val throttle = checkNotNull(PidCatalog.byKey("throttle"))
    private val ambient = checkNotNull(PidCatalog.byKey("ambient"))

    @Test
    fun `a refusal is an answer, not something to wait out`() = runBlocking {
        val truck = Truck(supports = setOf(coolant.id, ambient.id))
        val sample = poller(truck).sampleOnce(listOf(coolant, throttle, ambient))

        // The refusal must not have eaten the sweep: the parameters after it
        // are the ones that used to go blank.
        assertTrue(coolant.key in sample.values, "coolant should have answered")
        assertTrue(
            ambient.key in sample.values,
            "ambient comes after the refused parameter and must still answer",
        )
        assertTrue(throttle.key in sample.refusedKeys)
        assertFalse(throttle.key in sample.values)
    }

    @Test
    fun `a refused parameter is not asked for again`() = runBlocking {
        val truck = Truck(supports = setOf(coolant.id))
        val poller = poller(truck)

        poller.sampleOnce(listOf(coolant, throttle))
        val afterFirst = truck.requestedPids.count { it == throttle.id }
        assertEquals(1, afterFirst)

        repeat(5) { poller.sampleOnce(listOf(coolant, throttle)) }

        assertEquals(
            1,
            truck.requestedPids.count { it == throttle.id },
            "the vehicle's capabilities do not change between sweeps, so one " +
                "refusal is enough: ${truck.requestedPids}",
        )
        // And it keeps being reported as refused rather than silently vanishing.
        val later = poller.sampleOnce(listOf(coolant, throttle))
        assertTrue(throttle.key in later.refusedKeys)
        assertTrue(throttle.id in poller.refusedPids)
    }

    /**
     * Refused and "missed this sweep" have to stay distinguishable: one is
     * permanent and the gauge should say so, the other resolves next time.
     */
    @Test
    fun `a refusal is reported separately from a miss`() = runBlocking {
        val truck = Truck(supports = setOf(coolant.id))
        val sample = poller(truck).sampleOnce(listOf(coolant, throttle))

        assertTrue(throttle.key in sample.refusedKeys)
        assertTrue(throttle.key in sample.failedKeys, "a refusal is also a failure to read")
        assertTrue(sample.refusedKeys.none { it in sample.values.keys })
    }

    /**
     * The support query walks banks until one is refused. That refusal ends the
     * chain cleanly rather than aborting the whole query - this truck answers
     * 7F 01 31 to bank 0xC0, which is how the end is found.
     */
    @Test
    fun `the supported-pid query ends at the first bank the vehicle refuses`() = runBlocking {
        // Bank 0x00 answers with a mask whose continuation bit is set, so the
        // query asks for 0x20, which this vehicle refuses.
        val truck = object : ObdTransport {
            val asked = mutableListOf<Int>()
            private val pending = ArrayDeque<String>()
            override val isOpen = true
            override val description = "banks"
            override suspend fun open() = Unit
            override suspend fun close() = Unit
            override suspend fun write(bytes: ByteArray) {
                val cmd = String(bytes, Charsets.US_ASCII).trim()
                val reply = when {
                    cmd.isEmpty() -> return
                    cmd.startsWith("AT") || cmd.startsWith("ST") -> listOf("OK")
                    cmd.startsWith("0201") -> {
                        val pid = cmd.removePrefix("0201").take(2).toInt(16)
                        asked += pid
                        if (pid == 0x00) listOf("7E80641009819001700") else listOf("7E8037F013100000000")
                    }
                    else -> listOf("NO DATA")
                }
                pending += reply.joinToString("\r", postfix = "\r>")
            }
            override suspend fun read(timeoutMillis: Long): ByteArray {
                if (pending.isEmpty()) return ByteArray(0)
                return pending.removeFirst().toByteArray(Charsets.US_ASCII)
            }
        }

        val supported = LiveDataPoller(IsoTpChannel(ElmAdapter(truck))).readSupportedPids()

        assertEquals(listOf(0x00, 0x20), truck.asked, "it should stop at the first refusal")
        // The first bank still decoded, so the query is not wasted.
        assertTrue(0x0C in supported, "engine speed is in the bank that answered")
    }
}
