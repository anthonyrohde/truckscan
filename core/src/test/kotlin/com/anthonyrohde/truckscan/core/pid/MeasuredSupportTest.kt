package com.anthonyrohde.truckscan.core.pid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the decode of the bitmaps a real PCM returned.
 *
 * The value here is not the decoder, which is simple. It is that the numbers on
 * the right hand side were read off a truck, so if someone later changes the bit
 * order or the base offset the failure names a vehicle rather than a unit test.
 */
class MeasuredSupportTest {

    @Test
    fun `the measured bitmaps decode to the pids the truck listed`() {
        val expected = (
            "01 04 05 0C 0D 10 1C 1E 1F 20 21 2F 30 31 33 40 41 42 46 49 4A 51 5C 5D 60 " +
                "61 62 63 64 65 67 68 69 6A 6B 6D 6F 70 71 73 77 78 7A 7D 7E 7F 80 81 82 " +
                "83 85 87 88 89 8A 8B 8C 8E 8F 9B 9D 9E A0 A1 A2 A5 A6"
            ).split(" ").map { it.toInt(16) }

        assertEquals(expected, MeasuredSupport.SUPER_DUTY_2022_PCM.toList())
    }

    /**
     * The gauges a dash display can actually show. Each of these was claimed by
     * the PCM; none of them is assumed.
     */
    @Test
    fun `the readings a cluster needs are all supported`() {
        val needed = mapOf(
            0x0C to "engine RPM",
            0x0D to "vehicle speed",
            0x05 to "coolant temperature",
            0x5C to "engine oil temperature",
            0x2F to "fuel tank level",
            0x42 to "control module voltage",
            0x46 to "ambient air temperature",
            0x04 to "calculated engine load",
            0x33 to "barometric pressure",
            0x87 to "intake manifold absolute pressure",
            0x70 to "boost pressure control",
            0xA6 to "odometer",
        )
        for ((pid, what) in needed) {
            assertTrue(
                pid in MeasuredSupport.SUPER_DUTY_2022_PCM_DATA,
                "$what (PID %02X) is not in the measured set".format(pid),
            )
        }
    }

    /**
     * Oil *pressure* is on the dash and OBD-II does not carry it, so a cluster
     * must not pretend to have it. PID 0x0B, manifold pressure, is genuinely
     * absent here too - 0x87 is what this engine offers instead.
     */
    @Test
    fun `readings this truck does not offer are absent`() {
        assertFalse(0x0B in MeasuredSupport.SUPER_DUTY_2022_PCM_DATA)
        assertFalse(0x11 in MeasuredSupport.SUPER_DUTY_2022_PCM_DATA)
    }

    @Test
    fun `continuation markers are separated from readings`() {
        assertTrue(0x20 in MeasuredSupport.SUPER_DUTY_2022_PCM)
        assertFalse(0x20 in MeasuredSupport.SUPER_DUTY_2022_PCM_DATA)
        assertEquals(62, MeasuredSupport.SUPER_DUTY_2022_PCM_DATA.size)
    }

    /** How much of the catalogue this truck can actually drive. */
    @Test
    fun `the catalogue is split into what this truck has and has not`() {
        val supported = MeasuredSupport.supportedCatalogPids()
        val unsupported = MeasuredSupport.unsupportedCatalogPids()
        assertEquals(PidCatalog.ALL.size, supported.size + unsupported.size)
        assertTrue(supported.isNotEmpty(), "no catalogue entry matched the measured set")
    }
}
