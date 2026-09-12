package com.anthonyrohde.f250scan.core

import com.anthonyrohde.f250scan.core.adapter.CanFrame
import com.anthonyrohde.f250scan.core.pid.PidCatalog
import com.anthonyrohde.f250scan.core.pid.ZoneSeverity
import com.anthonyrohde.f250scan.core.util.Hex
import com.anthonyrohde.f250scan.core.util.u16
import com.anthonyrohde.f250scan.core.util.u8
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HexTest {

    @Test
    fun `encode and decode round trip`() {
        val bytes = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
        assertEquals("007F80FF", Hex.encode(bytes))
        assertTrue(bytes.contentEquals(Hex.decode("007F80FF")))
    }

    @Test
    fun `decode ignores whitespace`() {
        assertTrue(Hex.decode("00 7F 80 FF").contentEquals(Hex.decode("007F80FF")))
        assertTrue(Hex.decode("00\r\n7F").contentEquals(byteArrayOf(0x00, 0x7F)))
    }

    @Test
    fun `odd length and non hex input are rejected loudly`() {
        // Silently dropping a nibble would corrupt a module write, so this
        // must throw rather than do its best.
        assertThrows(IllegalArgumentException::class.java) { Hex.decode("ABC") }
        assertThrows(IllegalArgumentException::class.java) { Hex.decode("ZZ") }
        assertNull(Hex.decodeOrNull("ABC"))
    }

    @Test
    fun `unsigned helpers do not sign extend`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        assertEquals(255, bytes.u8(0))
        assertEquals(0xFFFE, bytes.u16(0))
    }

    @Test
    fun `separator is applied between bytes only`() {
        assertEquals("AA BB CC", Hex.encode(Hex.decode("AABBCC"), " "))
        assertEquals("", Hex.encode(ByteArray(0), " "))
    }
}

class CanFrameTest {

    @Test
    fun `eleven bit frame parses id and payload`() {
        val frame = CanFrame.parse("7E8064100BE3FA813", extendedId = false)

        assertNotNull(frame)
        assertEquals(0x7E8, frame!!.id)
        assertEquals("064100BE3FA813", Hex.encode(frame.data))
    }

    @Test
    fun `twenty nine bit frame uses an eight character id`() {
        val frame = CanFrame.parse("18DAF110064100BE", extendedId = true)

        assertNotNull(frame)
        assertEquals(0x18DAF110, frame!!.id)
        assertEquals("064100BE", Hex.encode(frame.data))
        assertTrue(frame.isExtendedId)
    }

    @Test
    fun `status words are not mistaken for frames`() {
        assertNull(CanFrame.parse("NO DATA", extendedId = false))
        assertNull(CanFrame.parse("SEARCHING...", extendedId = false))
        assertNull(CanFrame.parse("OK", extendedId = false))
        assertNull(CanFrame.parse("?", extendedId = false))
        assertNull(CanFrame.parse("", extendedId = false))
        assertNull(CanFrame.parse("CAN ERROR", extendedId = false))
    }

    @Test
    fun `truncated and misaligned lines are rejected`() {
        // ID only, no payload.
        assertNull(CanFrame.parse("7E8", extendedId = false))
        // Odd number of payload nibbles: a corrupt line.
        assertNull(CanFrame.parse("7E8064100BE3FA81", extendedId = false))
    }

    @Test
    fun `whitespace and prompt characters are stripped`() {
        val frame = CanFrame.parse("7E8 06 41 00 BE 3F A8 13 >", extendedId = false)
        assertNotNull(frame)
        assertEquals(0x7E8, frame!!.id)
    }

    @Test
    fun `equality compares payload contents not references`() {
        val a = CanFrame(0x7E8, byteArrayOf(1, 2, 3))
        val b = CanFrame(0x7E8, byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}

class PidDecodeTest {

    @Test
    fun `engine rpm uses the quarter rpm scaling`() {
        // 0x0C50 = 3152, divided by 4 = 788 rpm.
        val value = PidCatalog.ENGINE_RPM.decode(byteArrayOf(0x0C, 0x50))
        assertNotNull(value)
        assertEquals(788.0, value!!.value, 0.001)
        assertEquals("788 rpm", value.formatted)
    }

    @Test
    fun `temperatures apply the minus forty offset`() {
        assertEquals(50.0, PidCatalog.COOLANT_TEMP.decode(byteArrayOf(0x5A))!!.value, 0.001)
        assertEquals(-40.0, PidCatalog.COOLANT_TEMP.decode(byteArrayOf(0x00))!!.value, 0.001)
        assertEquals(30.0, PidCatalog.AMBIENT_AIR_TEMP.decode(byteArrayOf(0x46))!!.value, 0.001)
    }

    @Test
    fun `control module voltage scales by a thousand`() {
        // 0x36B0 = 14000 -> 14.0 V
        val value = PidCatalog.CONTROL_MODULE_VOLTAGE.decode(byteArrayOf(0x36, 0xB0.toByte()))
        assertEquals(14.0, value!!.value, 0.001)
        assertEquals("14.00 V", value.formatted)
    }

    @Test
    fun `percentage pids scale to a hundred over 255`() {
        assertEquals(100.0, PidCatalog.ENGINE_LOAD.decode(byteArrayOf(0xFF.toByte()))!!.value, 0.01)
        assertEquals(0.0, PidCatalog.ENGINE_LOAD.decode(byteArrayOf(0x00))!!.value, 0.01)
        assertEquals(50.196, PidCatalog.ENGINE_LOAD.decode(byteArrayOf(0x80.toByte()))!!.value, 0.01)
    }

    @Test
    fun `mass air flow scales by a hundredth`() {
        assertEquals(5.40, PidCatalog.MAF_RATE.decode(byteArrayOf(0x02, 0x1C))!!.value, 0.001)
    }

    @Test
    fun `torque pids apply the signed offset`() {
        assertEquals(-125.0, PidCatalog.ACTUAL_TORQUE.decode(byteArrayOf(0x00))!!.value, 0.001)
        assertEquals(0.0, PidCatalog.ACTUAL_TORQUE.decode(byteArrayOf(0x7D))!!.value, 0.001)
    }

    @Test
    fun `short payload decodes to null rather than a plausible wrong number`() {
        assertNull(PidCatalog.ENGINE_RPM.decode(byteArrayOf(0x0C)))
        assertNull(PidCatalog.MAF_RATE.decode(ByteArray(0)))
    }

    @Test
    fun `support mask decodes to pid numbers`() {
        // 0xBE3FA813 - the classic example mask from the OBD-II literature.
        val supported = PidCatalog.decodeSupportMask(
            0x00,
            byteArrayOf(0xBE.toByte(), 0x3F, 0xA8.toByte(), 0x13),
        )

        // 0xBE = 1011 1110 -> PIDs 1, 3, 4, 5, 6, 7
        assertTrue(supported.containsAll(listOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07)))
        assertTrue(!supported.contains(0x02))
        assertTrue(!supported.contains(0x08))
        // 0x13 = 0001 0011 -> PIDs 0x1C, 0x1F, 0x20
        assertTrue(supported.containsAll(listOf(0x1C, 0x1F, 0x20)))
    }

    @Test
    fun `support mask offsets correctly for higher banks`() {
        val supported = PidCatalog.decodeSupportMask(0x20, byteArrayOf(0x80.toByte(), 0, 0, 0))
        assertEquals(listOf(0x21), supported)
    }

    @Test
    fun `normalise clamps to the declared range`() {
        assertEquals(0.0, PidCatalog.ENGINE_RPM.normalise(-100.0), 0.001)
        assertEquals(1.0, PidCatalog.ENGINE_RPM.normalise(99_999.0), 0.001)
        assertEquals(0.5, PidCatalog.ENGINE_RPM.normalise(2000.0), 0.001)
    }

    @Test
    fun `catalog has no duplicate parameter keys`() {
        // Keys must be unique; PID ids deliberately are not, because several
        // diesel PIDs carry more than one sensor.
        val keys = PidCatalog.ALL.map { it.key }
        assertEquals(keys.size, keys.distinct().size, "duplicate keys would shadow")
    }

    @Test
    fun `exhaust gas temperatures share one pid but not one key`() {
        val egt = listOf(
            PidCatalog.EGT_1, PidCatalog.EGT_2, PidCatalog.EGT_3, PidCatalog.EGT_4,
        )
        assertEquals(setOf(0x78), egt.map { it.id }.toSet(), "all four ride on PID 0x78")
        assertEquals(4, egt.map { it.key }.distinct().size)
    }

    @Test
    fun `bitmask packed sensors decode from their own slot`() {
        // Support byte says all four present, then four 16-bit values,
        // each (256*hi + lo) / 10 - 40.
        val payload = Hex.decode("0F" + "0E10" + "0D16" + "0C80" + "0BEA")

        assertEquals(320.0, PidCatalog.EGT_1.decode(payload)!!.value, 0.01)
        assertEquals(295.0, PidCatalog.EGT_2.decode(payload)!!.value, 0.01)
        assertEquals(280.0, PidCatalog.EGT_3.decode(payload)!!.value, 0.01)
        assertEquals(265.0, PidCatalog.EGT_4.decode(payload)!!.value, 0.01)
    }

    @Test
    fun `an absent packed sensor decodes to nothing rather than a wrong number`() {
        // Only sensors 1 and 3 present. Sensor 3's data is the SECOND pair,
        // not the third: absent sensors occupy no bytes.
        val payload = Hex.decode("05" + "0E10" + "0C80")

        assertEquals(320.0, PidCatalog.EGT_1.decode(payload)!!.value, 0.01)
        assertNull(PidCatalog.EGT_2.decode(payload), "sensor 2 is not fitted")
        assertEquals(
            280.0, PidCatalog.EGT_3.decode(payload)!!.value, 0.01,
            "sensor 3 must be read from the second slot, not the third",
        )
        assertNull(PidCatalog.EGT_4.decode(payload))
    }

    @Test
    fun `zones classify a reading and name the condition`() {
        val cold = PidCatalog.COOLANT_TEMP.decode(byteArrayOf(0x50))!!   // 40 C
        val normal = PidCatalog.COOLANT_TEMP.decode(byteArrayOf(0x7D))!! // 85 C
        val hot = PidCatalog.COOLANT_TEMP.decode(byteArrayOf(0x92.toByte()))!! // 106 C
        val boiling = PidCatalog.COOLANT_TEMP.decode(byteArrayOf(0x9C.toByte()))!! // 116 C

        assertEquals(ZoneSeverity.CAUTION, cold.severity)
        assertEquals(ZoneSeverity.NORMAL, normal.severity)
        assertEquals(ZoneSeverity.WARNING, hot.severity)
        assertEquals(ZoneSeverity.CRITICAL, boiling.severity)
        assertEquals("Overheating", boiling.zone?.label)
    }

    @Test
    fun `a parameter with no zones is always normal`() {
        val maf = PidCatalog.MAF_RATE.decode(byteArrayOf(0x02, 0x1C))!!
        assertEquals(ZoneSeverity.NORMAL, maf.severity)
        assertNull(maf.zone)
    }

    @Test
    fun `new diesel decoders match hand computed values`() {
        // Rail pressure: (0x0BB8) * 10 = 30000 kPa, i.e. 300 bar at idle.
        assertEquals(
            30_000.0,
            PidCatalog.FUEL_RAIL_PRESSURE.decode(byteArrayOf(0x0B, 0xB8.toByte()))!!.value,
            0.01,
        )
        // Injection timing: (0x6B80 / 128) - 210 = 5 degrees.
        assertEquals(
            5.0,
            PidCatalog.INJECTION_TIMING.decode(byteArrayOf(0x6B, 0x80.toByte()))!!.value,
            0.01,
        )
        // Fuel trim is centred on 128, not 0.
        assertEquals(0.0, PidCatalog.SHORT_TRIM_1.decode(byteArrayOf(0x80.toByte()))!!.value, 0.01)
        // Exhaust pressure: support byte then (0x3480) / 128 = 105 kPa.
        assertEquals(
            105.0,
            PidCatalog.EXHAUST_PRESSURE.decode(Hex.decode("013480"))!!.value,
            0.01,
        )
    }
}
