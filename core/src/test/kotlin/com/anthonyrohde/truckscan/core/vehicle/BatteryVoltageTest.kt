package com.anthonyrohde.truckscan.core.vehicle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The readings in this test are the ones the truck actually gave, in the
 * session that ended with it not starting.
 */
class BatteryVoltageTest {

    @Test
    fun `the replies the adapter actually sent all parse`() {
        assertEquals(11.7, BatteryVoltage.parse("11.7V"))
        assertEquals(10.0, BatteryVoltage.parse("10.0V"))
        assertEquals(12.4, BatteryVoltage.parse("12.4 V"))
        assertEquals(13.8, BatteryVoltage.parse("ATRV\r13.8V"))
    }

    @Test
    fun `a reply that is not a voltage is not guessed at`() {
        // A misparse that reads high removes the warning, which is the one
        // failure this must not have.
        assertNull(BatteryVoltage.parse("?"))
        assertNull(BatteryVoltage.parse("NO DATA"))
        assertNull(BatteryVoltage.parse("OK"))
        assertNull(BatteryVoltage.parse(""))
        assertNull(BatteryVoltage.parse("ELM327 v1.4b"))
    }

    /**
     * 10.0 V was on screen with the PCM answering, and was read as "the truck
     * is awake" rather than "the battery is going flat". Both were true.
     */
    @Test
    fun `ten volts is called a starting problem, not a diagnostic one`() {
        assertEquals(BatteryVoltage.State.CRITICAL, BatteryVoltage.classify(10.0))
        val said = BatteryVoltage.describe(10.0)
        assertTrue(said.contains("starting problem"), said)
        assertTrue(said.contains("two batteries"), said)
    }

    @Test
    fun `the rest of the scale`() {
        assertEquals(BatteryVoltage.State.CHARGING, BatteryVoltage.classify(14.2))
        assertEquals(BatteryVoltage.State.HEALTHY, BatteryVoltage.classify(12.6))
        assertEquals(BatteryVoltage.State.DISCHARGING, BatteryVoltage.classify(12.0))
        assertEquals(BatteryVoltage.State.TOO_LOW_TO_TRUST, BatteryVoltage.classify(11.3))
        assertEquals(BatteryVoltage.State.CRITICAL, BatteryVoltage.classify(10.9))
    }

    /**
     * A module that is short of volts and a module that is absent look the
     * same, so the low-voltage wording has to reach for that explicitly.
     */
    @Test
    fun `below the trust threshold it warns that a missing module may be volts`() {
        val said = BatteryVoltage.describe(11.2)
        assertTrue(said.contains("short of volts"), said)
    }

    @Test
    fun `the session that flattened it is described as a drop, not a number`() {
        val trend = BatteryVoltage.Trend(startVolts = 11.7, endVolts = 10.0, minutesElapsed = 40.0)
        assertEquals(1.7, trend.drop, 1e-9)
        assertEquals(1.7 / 40.0, trend.voltsPerMinute!!, 1e-9)

        val said = trend.describe()
        assertTrue(said.contains("Down 1.7 V in 40 minutes"), said)
        assertTrue(said.contains("starting problem"), said)
    }

    @Test
    fun `a battery that is holding up reports no drop`() {
        val trend = BatteryVoltage.Trend(12.6, 12.6, 20.0)
        assertNull(trend.voltsPerMinute)
        assertNull(trend.minutesToFloor)
        assertTrue(trend.describe().contains("healthy"), trend.describe())
    }

    /** Charging cannot be extrapolated to a floor it is moving away from. */
    @Test
    fun `a rising voltage has no time remaining`() {
        val trend = BatteryVoltage.Trend(12.4, 14.1, 5.0)
        assertNull(trend.voltsPerMinute)
        assertTrue(trend.describe().contains("charging"), trend.describe())
    }

    @Test
    fun `a short run does not extrapolate from noise`() {
        // Half a minute of drift is not a trend.
        assertNull(BatteryVoltage.Trend(12.4, 12.3, 0.5).voltsPerMinute)
    }
}
