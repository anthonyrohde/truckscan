package com.anthonyrohde.truckscan.core.session

import com.anthonyrohde.truckscan.core.pid.Pid
import com.anthonyrohde.truckscan.core.pid.PidValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveValueHoldTest {

    private fun pid(key: String) = Pid(
        key = key, id = 0x05, name = key, unit = "C", byteCount = 1,
        minValue = 0.0, maxValue = 200.0, decoder = { 0.0 },
    )

    private val coolant = pid("coolant")
    private val rpm = pid("rpm")

    private fun sample(millis: Long, vararg readings: Pair<Pid, Double>) = LiveDataSample(
        values = readings.associate { (p, v) -> p.key to PidValue(p, v, ByteArray(0), millis) },
        timestampMillis = millis,
    )

    /**
     * The behaviour this exists for: one sweep misses coolant, and the gauge
     * must keep showing 88 rather than emptying and refilling.
     */
    @Test
    fun `a parameter missing from one sweep keeps its last reading`() {
        val hold = LiveValueHold()
        hold.accept(sample(0, coolant to 88.0, rpm to 800.0))

        val after = hold.accept(sample(500, rpm to 810.0))

        assertEquals(88.0, after["coolant"]?.value)
        assertEquals(810.0, after["rpm"]?.value)
    }

    /** A held reading keeps the time it was actually measured, not now. */
    @Test
    fun `a held reading keeps its original timestamp`() {
        val hold = LiveValueHold()
        hold.accept(sample(1_000, coolant to 88.0))
        val after = hold.accept(sample(4_000, rpm to 800.0))

        assertEquals(1_000L, after["coolant"]?.timestampMillis)
    }

    @Test
    fun `a new reading replaces the held one`() {
        val hold = LiveValueHold()
        hold.accept(sample(0, coolant to 88.0))
        val after = hold.accept(sample(500, coolant to 91.0))

        assertEquals(91.0, after["coolant"]?.value)
    }

    @Test
    fun `a reading goes stale once nothing has updated it`() {
        val hold = LiveValueHold(staleAfterMillis = 5_000)
        hold.accept(sample(0, coolant to 88.0))

        assertFalse(hold.isStale("coolant", 4_999))
        assertTrue(hold.isStale("coolant", 5_001))
        assertEquals(setOf("coolant"), hold.staleKeys(5_001))
    }

    @Test
    fun `a parameter never seen is stale rather than absent`() {
        assertTrue(LiveValueHold().isStale("never", 0))
    }

    @Test
    fun `clearing drops everything held`() {
        val hold = LiveValueHold()
        hold.accept(sample(0, coolant to 88.0))
        hold.clear()

        assertTrue(hold.accept(sample(100, rpm to 800.0)).keys == setOf("rpm"))
    }
}
