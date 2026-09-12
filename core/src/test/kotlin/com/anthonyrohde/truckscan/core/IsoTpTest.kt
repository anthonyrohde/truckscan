package com.anthonyrohde.truckscan.core

import com.anthonyrohde.truckscan.core.isotp.IsoTpAssembler
import com.anthonyrohde.truckscan.core.isotp.IsoTpFrame
import com.anthonyrohde.truckscan.core.isotp.IsoTpSegmenter
import com.anthonyrohde.truckscan.core.util.Hex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IsoTpFrameTest {

    @Test
    fun `single frame round trips with padding`() {
        val frame = IsoTpFrame.Single(Hex.decode("62F19001"))
        val encoded = frame.encode()

        assertEquals(8, encoded.size, "frames must be padded to 8 bytes")
        // PCI byte 0x04 (four payload bytes), the payload, then zero padding.
        assertEquals("0462F19001000000", Hex.encode(encoded))

        val parsed = IsoTpFrame.parse(encoded)
        assertEquals(frame, parsed, "padding must not leak into the payload")
    }

    @Test
    fun `first frame encodes a 12 bit length`() {
        val payload = ByteArray(6) { it.toByte() }
        val frame = IsoTpFrame.First(totalLength = 0x123, payload = payload)
        val encoded = frame.encode()

        // 0x1L LL where LLL is the length: 0x123 -> 0x11 0x23
        assertEquals(0x11, encoded[0].toInt() and 0xFF)
        assertEquals(0x23, encoded[1].toInt() and 0xFF)
        assertEquals(0x123, IsoTpFrame.firstFrameLength(encoded))
    }

    @Test
    fun `consecutive frame carries its sequence number in the low nibble`() {
        val frame = IsoTpFrame.Consecutive(sequenceNumber = 5, payload = ByteArray(7) { 0xAB.toByte() })
        val encoded = frame.encode()

        assertEquals(0x25, encoded[0].toInt() and 0xFF)
        val parsed = IsoTpFrame.parse(encoded) as IsoTpFrame.Consecutive
        assertEquals(5, parsed.sequenceNumber)
    }

    @Test
    fun `flow control decodes separation time in both encodings`() {
        // 0x00-0x7F is a millisecond count.
        val millis = IsoTpFrame.parse(Hex.decode("30000A")) as IsoTpFrame.FlowControl
        assertEquals(IsoTpFrame.FlowStatus.CONTINUE_TO_SEND, millis.flowStatus)
        assertEquals(10_000, millis.separationTimeMicros)

        // 0xF1-0xF9 is hundreds of microseconds.
        val micros = IsoTpFrame.parse(Hex.decode("3000F5")) as IsoTpFrame.FlowControl
        assertEquals(500, micros.separationTimeMicros)

        // Reserved values must fall back to the 127 ms maximum, not be guessed.
        val reserved = IsoTpFrame.parse(Hex.decode("3000AA")) as IsoTpFrame.FlowControl
        assertEquals(127_000, reserved.separationTimeMicros)
    }

    @Test
    fun `wait and overflow flow statuses are distinguished`() {
        val wait = IsoTpFrame.parse(Hex.decode("310000")) as IsoTpFrame.FlowControl
        assertEquals(IsoTpFrame.FlowStatus.WAIT, wait.flowStatus)

        val overflow = IsoTpFrame.parse(Hex.decode("320000")) as IsoTpFrame.FlowControl
        assertEquals(IsoTpFrame.FlowStatus.OVERFLOW, overflow.flowStatus)
    }

    @Test
    fun `unknown pci and malformed frames parse to null rather than throwing`() {
        // 0x40 is not a defined PCI type.
        assertNull(IsoTpFrame.parse(Hex.decode("4000000000000000")))
        // Single frame claiming 7 bytes but carrying 2.
        assertNull(IsoTpFrame.parse(Hex.decode("07AABB")))
        // Single frame with a zero length is invalid.
        assertNull(IsoTpFrame.parse(Hex.decode("0000000000000000")))
        assertNull(IsoTpFrame.parse(ByteArray(0)))
    }

    @Test
    fun `escape form carries a 32 bit length`() {
        val frame = IsoTpFrame.First(totalLength = 0x1_0000, payload = ByteArray(2))
        val encoded = frame.encode(padTo = 0)

        assertEquals(0x10, encoded[0].toInt() and 0xFF)
        assertEquals(0x00, encoded[1].toInt() and 0xFF)
        assertEquals(0x1_0000, IsoTpFrame.firstFrameLength(encoded))
    }
}

class IsoTpSegmenterTest {

    @Test
    fun `payload of seven bytes or fewer becomes one single frame`() {
        for (size in 1..7) {
            val frames = IsoTpSegmenter.segment(ByteArray(size))
            assertEquals(1, frames.size, "$size bytes should be a single frame")
            assertInstanceOf(IsoTpFrame.Single::class.java, frames.single())
        }
    }

    @Test
    fun `eight bytes needs a first frame and one consecutive frame`() {
        val frames = IsoTpSegmenter.segment(ByteArray(8) { it.toByte() })

        assertEquals(2, frames.size)
        val first = frames[0] as IsoTpFrame.First
        assertEquals(8, first.totalLength)
        assertEquals(6, first.payload.size, "a first frame carries exactly 6 bytes")

        val second = frames[1] as IsoTpFrame.Consecutive
        assertEquals(1, second.sequenceNumber, "consecutive numbering starts at 1")
        assertEquals(2, second.payload.size, "final frame carries only the remainder")
    }

    @Test
    fun `segmentation preserves every byte in order`() {
        val payload = ByteArray(100) { (it * 7).toByte() }
        val frames = IsoTpSegmenter.segment(payload)

        val rebuilt = frames.flatMap { frame ->
            when (frame) {
                is IsoTpFrame.First -> frame.payload.toList()
                is IsoTpFrame.Consecutive -> frame.payload.toList()
                else -> emptyList()
            }
        }.toByteArray()

        assertTrue(payload.contentEquals(rebuilt), "round trip must preserve the payload")
    }

    @Test
    fun `sequence numbers wrap from 15 back to 0`() {
        // 6 bytes in the first frame, then 16 consecutive frames of 7.
        val payload = ByteArray(6 + 7 * 16)
        val frames = IsoTpSegmenter.segment(payload)
        val sequences = frames.filterIsInstance<IsoTpFrame.Consecutive>().map { it.sequenceNumber }

        assertEquals(16, sequences.size)
        assertEquals((1..15).toList() + 0, sequences, "SN must wrap modulo 16")
    }

    @Test
    fun `flow control is only required for multi frame messages`() {
        assertTrue(!IsoTpSegmenter.requiresFlowControl(ByteArray(7)))
        assertTrue(IsoTpSegmenter.requiresFlowControl(ByteArray(8)))
    }
}

class IsoTpAssemblerTest {

    @Test
    fun `single frame completes immediately`() {
        val assembler = IsoTpAssembler()
        val progress = assembler.accept(IsoTpFrame.Single(Hex.decode("620102")))

        val complete = assertInstanceOf(IsoTpAssembler.Progress.Complete::class.java, progress)
        assertEquals("620102", Hex.encode(complete.payload))
        assertTrue(!assembler.isActive, "assembler must reset after completing")
    }

    @Test
    fun `segmented message reassembles across frames`() {
        val assembler = IsoTpAssembler()
        val payload = ByteArray(20) { (it + 1).toByte() }
        val frames = IsoTpSegmenter.segment(payload)

        val firstProgress = assembler.accept(frames[0])
        val needsFc = assertInstanceOf(
            IsoTpAssembler.Progress.NeedsFlowControl::class.java,
            firstProgress,
        )
        assertEquals(20, needsFc.totalLength)

        val midProgress = assembler.accept(frames[1])
        val continuing = assertInstanceOf(
            IsoTpAssembler.Progress.Continuing::class.java,
            midProgress,
        )
        assertEquals(13, continuing.received)

        val finalProgress = assembler.accept(frames[2])
        val complete = assertInstanceOf(
            IsoTpAssembler.Progress.Complete::class.java,
            finalProgress,
        )
        assertTrue(payload.contentEquals(complete.payload))
    }

    @Test
    fun `trailing padding is trimmed to the declared length`() {
        val assembler = IsoTpAssembler()
        // Declares 10 bytes: 6 in the first frame, 4 in a padded consecutive frame.
        assembler.accept(IsoTpFrame.First(10, ByteArray(6) { 0x11 }))
        val progress = assembler.accept(
            IsoTpFrame.Consecutive(1, byteArrayOf(0x22, 0x22, 0x22, 0x22, 0x00, 0x00, 0x00)),
        )

        val complete = assertInstanceOf(IsoTpAssembler.Progress.Complete::class.java, progress)
        assertEquals(10, complete.payload.size, "padding must not be included")
        assertEquals("11111111111122222222", Hex.encode(complete.payload))
    }

    @Test
    fun `dropped frame is detected rather than silently corrupting the message`() {
        val assembler = IsoTpAssembler()
        assembler.accept(IsoTpFrame.First(20, ByteArray(6)))

        // Jump straight to sequence 2: frame 1 was lost.
        val progress = assembler.accept(IsoTpFrame.Consecutive(2, ByteArray(7)))

        val failed = assertInstanceOf(IsoTpAssembler.Progress.Failed::class.java, progress)
        assertTrue(
            failed.reason.contains("expected SN=1"),
            "failure must name the expected sequence number: ${failed.reason}",
        )
    }

    @Test
    fun `consecutive frame without a first frame is ignored`() {
        val assembler = IsoTpAssembler()
        val progress = assembler.accept(IsoTpFrame.Consecutive(1, ByteArray(7)))
        assertInstanceOf(IsoTpAssembler.Progress.Ignored::class.java, progress)
    }

    @Test
    fun `first frame declaring a single frame length is rejected`() {
        val assembler = IsoTpAssembler()
        val progress = assembler.accept(IsoTpFrame.First(5, ByteArray(6)))
        assertInstanceOf(IsoTpAssembler.Progress.Failed::class.java, progress)
    }

    @Test
    fun `a new single frame abandons an incomplete message`() {
        val assembler = IsoTpAssembler()
        assembler.accept(IsoTpFrame.First(20, ByteArray(6)))
        assertTrue(assembler.isActive)

        val progress = assembler.accept(IsoTpFrame.Single(byteArrayOf(0x7F, 0x22, 0x31)))
        assertInstanceOf(IsoTpAssembler.Progress.Complete::class.java, progress)
        assertTrue(!assembler.isActive)
    }
}
