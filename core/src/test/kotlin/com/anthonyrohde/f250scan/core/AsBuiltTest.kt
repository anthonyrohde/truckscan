package com.anthonyrohde.f250scan.core

import com.anthonyrohde.f250scan.core.ford.AsBuiltBlock
import com.anthonyrohde.f250scan.core.ford.ChecksumStrategy
import com.anthonyrohde.f250scan.core.util.Hex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsBuiltFormatTest {

    @Test
    fun `ford format line parses into address block data and checksum`() {
        val block = AsBuiltBlock.parse("726-01-01 0F14 0004 0000 0A")

        assertNotNull(block)
        assertEquals(0x726, block!!.moduleAddress)
        assertEquals("01-01", block.blockId)
        assertEquals("0F1400040000", Hex.encode(block.data))
        assertEquals(0x0A, block.checksum)
    }

    @Test
    fun `format round trips back to the original text`() {
        val original = "7D0-02-01 0000 0005 1234 7F"
        val block = AsBuiltBlock.parse(original)
        assertEquals(original, block?.format())
    }

    @Test
    fun `line without a checksum keeps every byte as data`() {
        val block = AsBuiltBlock.parse("726-01-01 0F14 0004")

        assertNotNull(block)
        assertEquals("0F140004", Hex.encode(block!!.data))
        assertNull(block.checksum, "no trailing single byte means no checksum")
    }

    @Test
    fun `comments and blank lines are skipped`() {
        assertNull(AsBuiltBlock.parse(""))
        assertNull(AsBuiltBlock.parse("   "))
        assertNull(AsBuiltBlock.parse("# a comment"))
        assertNull(AsBuiltBlock.parse("; another comment"))
    }

    @Test
    fun `malformed lines return null rather than partial data`() {
        assertNull(AsBuiltBlock.parse("not-a-block"))
        assertNull(AsBuiltBlock.parse("726"))
        assertNull(AsBuiltBlock.parse("ZZZ-01-01 0000"))
    }

    @Test
    fun `whole file parses and ignores surrounding noise`() {
        val text = """
            # BCM As-Built
            726-01-01 4102 0000 1C08 99
            726-01-02 0030 0000 D0

            not a block at all
            726-01-03 7F11 0200 0040 23
        """.trimIndent()

        val blocks = AsBuiltBlock.parseAll(text)
        assertEquals(3, blocks.size)
        assertEquals(listOf("01-01", "01-02", "01-03"), blocks.map { it.blockId })
    }

    @Test
    fun `irregular spacing is tolerated`() {
        val block = AsBuiltBlock.parse("  726-01-01    0F14  0004   0000   0A  ")
        assertNotNull(block)
        assertEquals("0F1400040000", Hex.encode(block!!.data))
        assertEquals(0x0A, block.checksum)
    }
}

class ChecksumStrategyTest {

    private fun blockWith(data: String, checksum: Int) =
        AsBuiltBlock(0x726, "01-01", Hex.decode(data), checksum)

    @Test
    fun `twos complement makes data plus checksum sum to zero`() {
        val data = Hex.decode("410200001C08")
        val checksum = ChecksumStrategy.TWOS_COMPLEMENT.compute(data)
        val total = data.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) } + checksum
        assertEquals(0, total and 0xFF)
    }

    @Test
    fun `ones complement makes data plus checksum sum to 0xFF`() {
        val data = Hex.decode("410200001C08")
        val checksum = ChecksumStrategy.ONES_COMPLEMENT.compute(data)
        val total = data.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) } + checksum
        assertEquals(0xFF, total and 0xFF)
    }

    @Test
    fun `detection identifies the algorithm from consistent blocks`() {
        val data1 = Hex.decode("410200001C08")
        val data2 = Hex.decode("00300000")
        val blocks = listOf(
            blockWith("410200001C08", ChecksumStrategy.TWOS_COMPLEMENT.compute(data1)),
            blockWith("00300000", ChecksumStrategy.TWOS_COMPLEMENT.compute(data2)),
        )

        assertEquals(ChecksumStrategy.TWOS_COMPLEMENT, ChecksumStrategy.detect(blocks))
    }

    @Test
    fun `detection returns null when no algorithm explains the data`() {
        val blocks = listOf(
            blockWith("410200001C08", 0x01),
            blockWith("00300000", 0x02),
            blockWith("7F11020000", 0x03),
        )
        assertNull(
            ChecksumStrategy.detect(blocks),
            "refusing to guess is the correct behaviour here",
        )
    }

    @Test
    fun `detection demands unanimity across blocks`() {
        val good = Hex.decode("410200001C08")
        val blocks = listOf(
            blockWith("410200001C08", ChecksumStrategy.TWOS_COMPLEMENT.compute(good)),
            blockWith("00300000", 0x77), // deliberately wrong
        )
        assertNull(
            ChecksumStrategy.detect(blocks),
            "a partial match is coincidence, not the algorithm",
        )
    }

    @Test
    fun `detection returns null when no block carries a checksum`() {
        val blocks = listOf(AsBuiltBlock(0x726, "01-01", Hex.decode("4102"), null))
        assertNull(ChecksumStrategy.detect(blocks))
    }

    @Test
    fun `recomputing the checksum after an edit produces a valid block`() {
        val original = blockWith("410200001C08", 0x99)
        assertTrue(original.isChecksumValid(ChecksumStrategy.TWOS_COMPLEMENT))

        // Edit a byte; the old checksum must now be wrong.
        val edited = original.copy(data = Hex.decode("410200001C09"))
        assertFalse(edited.isChecksumValid(ChecksumStrategy.TWOS_COMPLEMENT))

        val fixed = edited.withRecomputedChecksum(ChecksumStrategy.TWOS_COMPLEMENT)
        assertTrue(fixed.isChecksumValid(ChecksumStrategy.TWOS_COMPLEMENT))
        assertEquals(0x98, fixed.checksum)
    }

    @Test
    fun `write payload includes the checksum only when asked`() {
        val block = blockWith("4102", 0xBD)
        assertEquals("4102BD", Hex.encode(block.payloadForWrite(includeChecksum = true)))
        assertEquals("4102", Hex.encode(block.payloadForWrite(includeChecksum = false)))
    }
}
