package com.anthonyrohde.truckscan.core

import com.anthonyrohde.truckscan.core.dtc.Dtc
import com.anthonyrohde.truckscan.core.dtc.DtcCatalog
import com.anthonyrohde.truckscan.core.dtc.DtcStatus
import com.anthonyrohde.truckscan.core.dtc.DtcSystem
import com.anthonyrohde.truckscan.core.util.Hex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DtcDecodeTest {

    @Test
    fun `powertrain code decodes from its bit layout`() {
        // 0x02 = 00 00 0010 -> P, digit2 0, digit3 2. 0x99 -> digits 9 and 9.
        val (system, code) = Dtc.decodeCodeString(0x02, 0x99)
        assertEquals(DtcSystem.POWERTRAIN, system)
        assertEquals("P0299", code)
    }

    @Test
    fun `code with hex digits decodes correctly`() {
        // 0x24 = 00 10 0100 -> P, digit2 2, digit3 4. 0x2F -> digits 2 and F.
        val (system, code) = Dtc.decodeCodeString(0x24, 0x2F)
        assertEquals(DtcSystem.POWERTRAIN, system)
        assertEquals("P242F", code, "hex nibbles must not be decimalised")
    }

    @Test
    fun `all four system prefixes decode from the top two bits`() {
        assertEquals("P0101", Dtc.decodeCodeString(0x01, 0x01).second)
        assertEquals("C0101", Dtc.decodeCodeString(0x41, 0x01).second)
        assertEquals("B0101", Dtc.decodeCodeString(0x81, 0x01).second)
        assertEquals("U0101", Dtc.decodeCodeString(0xC1, 0x01).second)
    }

    @Test
    fun `network code decodes`() {
        val (system, code) = Dtc.decodeCodeString(0xC1, 0x55)
        assertEquals(DtcSystem.NETWORK, system)
        assertEquals("U0155", code)
    }

    @Test
    fun `failure type byte is retained and displayed`() {
        val dtc = Dtc.fromUds(Hex.decode("02991C"), statusByte = 0x08)
        assertEquals("P0299", dtc.code)
        assertEquals(0x1C, dtc.failureTypeByte)
        assertEquals("P0299:1C", dtc.displayCode)
        assertTrue(
            dtc.description.contains("voltage out of range"),
            "failure type must be described: ${dtc.description}",
        )
    }

    @Test
    fun `zero failure type is not shown as a suffix`() {
        val dtc = Dtc.fromUds(Hex.decode("029900"), statusByte = 0x08)
        assertEquals("P0299", dtc.displayCode)
    }
}

class DtcStatusTest {

    @Test
    fun `status bits decode independently`() {
        val status = DtcStatus(0x8B) // 1000 1011
        assertTrue(status.testFailed)
        assertTrue(status.testFailedThisOperationCycle)
        assertFalse(status.pending)
        assertTrue(status.confirmed)
        assertTrue(status.warningIndicatorRequested)
        assertTrue(status.isCurrentlyFailing)
    }

    @Test
    fun `historic fault is distinguished from a currently failing one`() {
        val historic = DtcStatus(0x28) // confirmed-since-clear, not failing now
        assertFalse(historic.isCurrentlyFailing)
        assertTrue(historic.testFailedSinceLastClear)

        val live = DtcStatus(0x09)
        assertTrue(live.isCurrentlyFailing)
    }

    @Test
    fun `empty status reports no flags rather than an empty string`() {
        assertEquals("no status flags set", DtcStatus(0x00).describe())
    }
}

class DtcResponseParsingTest {

    @Test
    fun `service 0x19 response parses into four byte records`() {
        // sub-function echo, availability mask, then two 4-byte records.
        val body = Hex.decode("02FF" + "029900" + "8B" + "242F00" + "04")
        val dtcs = Dtc.parseDtcListResponse(body)

        assertEquals(2, dtcs.size)
        assertEquals("P0299", dtcs[0].code)
        assertTrue(dtcs[0].status.confirmed)
        assertEquals("P242F", dtcs[1].code)
        assertTrue(dtcs[1].status.pending)
        assertFalse(dtcs[1].status.confirmed)
    }

    @Test
    fun `all zero padding records are skipped`() {
        val body = Hex.decode("02FF" + "02990088" + "00000000" + "00000000")
        val dtcs = Dtc.parseDtcListResponse(body)
        assertEquals(1, dtcs.size, "padding must not appear as a fault")
    }

    @Test
    fun `truncated final record is dropped rather than throwing`() {
        // Second record is only 3 of 4 bytes: a dropped CAN frame.
        val body = Hex.decode("02FF" + "02990088" + "242F00")
        val dtcs = Dtc.parseDtcListResponse(body)
        assertEquals(1, dtcs.size, "the complete record should still be reported")
    }

    @Test
    fun `empty dtc list yields no faults`() {
        assertTrue(Dtc.parseDtcListResponse(Hex.decode("02FF")).isEmpty())
        assertTrue(Dtc.parseDtcListResponse(ByteArray(0)).isEmpty())
    }

    @Test
    fun `legacy mode 03 response parses two byte codes`() {
        val dtcs = Dtc.parseMode03Response(Hex.decode("0299" + "242F" + "0000"))

        assertEquals(2, dtcs.size)
        assertEquals("P0299", dtcs[0].code)
        assertEquals("P242F", dtcs[1].code)
        assertTrue(dtcs[0].status.confirmed, "mode 03 returns stored codes by definition")
    }
}

class DtcCatalogTest {

    @Test
    fun `known diesel codes have descriptions`() {
        assertTrue(DtcCatalog.isKnown("P0299"))
        assertTrue(DtcCatalog.isKnown("P242F"))
        assertTrue(DtcCatalog.isKnown("P20EE"))
        assertTrue(DtcCatalog.isKnown("U0155"))
        assertEquals(
            "Turbocharger/supercharger underboost",
            DtcCatalog.describe("P0299"),
        )
    }

    @Test
    fun `unknown code says so instead of inventing a description`() {
        val description = DtcCatalog.describe("P1234")
        assertFalse(DtcCatalog.isKnown("P1234"))
        assertTrue(
            description.contains("No description available"),
            "must not fabricate: $description",
        )
        assertTrue(
            description.contains("Ford-specific"),
            "should point the user somewhere useful",
        )
    }

    @Test
    fun `unknown code still reports a known failure type`() {
        val description = DtcCatalog.describe("P1234", 0x11)
        assertTrue(description.contains("circuit short to ground"))
    }

    @Test
    fun `catalog is case insensitive`() {
        assertEquals(DtcCatalog.describe("P0299"), DtcCatalog.describe("p0299"))
    }
}
