package com.anthonyrohde.truckscan.core.adapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdapterProbeTest {

    private val cr = 0x0D.toChar()

    @Test
    fun `accepts an ELM327 identifying itself`() {
        assertTrue(AdapterProbe.looksLikeAdapter("ATI$cr${cr}ELM327 v1.5$cr$cr>"))
    }

    @Test
    fun `accepts an STN chipset`() {
        assertTrue(AdapterProbe.looksLikeAdapter("STN2120 v5.6.7$cr>"))
    }

    @Test
    fun `accepts an OBDLink by vendor name`() {
        assertTrue(AdapterProbe.looksLikeAdapter("OBDLink EX r5.8$cr>"))
    }

    @Test
    fun `accepts a bare prompt from a clone that answers nothing useful`() {
        assertTrue(AdapterProbe.looksLikeAdapter("$cr$cr>"))
    }

    @Test
    fun `rejects silence`() {
        assertFalse(AdapterProbe.looksLikeAdapter(""))
        assertFalse(AdapterProbe.looksLikeAdapter("   $cr "))
    }

    /**
     * The case this exists for. Reading a 115,200 baud adapter at 2 Mbit/s
     * yields bytes rather than silence, and by chance some of them are 0x3E.
     * Accepting that as a prompt is what left the app "connected" to an
     * adapter that was not listening.
     */
    @Test
    fun `rejects misframed noise even when it contains a prompt byte`() {
        val noise = charArrayOf(
            0xFE.toChar(), 0x81.toChar(), '>', 0xC3.toChar(), 0x02.toChar(), 0xFF.toChar(),
        ).concatToString()
        assertFalse(AdapterProbe.looksLikeAdapter(noise))
    }

    @Test
    fun `rejects printable text carrying no prompt and no known name`() {
        assertFalse(AdapterProbe.looksLikeAdapter("SEARCHING...${cr}NO DATA$cr"))
    }

    @Test
    fun `printable fraction is one for clean ascii and zero for binary`() {
        assertEquals(1.0, AdapterProbe.printableFraction("ELM327 v1.5$cr>"))
        val binary = charArrayOf(
            0xFE.toChar(), 0x81.toChar(), 0xC3.toChar(), 0x02.toChar(),
        ).concatToString()
        assertEquals(0.0, AdapterProbe.printableFraction(binary))
    }
}
