package com.anthonyrohde.f250scan.core.isotp

import com.anthonyrohde.f250scan.core.util.u32
import com.anthonyrohde.f250scan.core.util.u8

/**
 * ISO 15765-2 (ISO-TP) transport frames.
 *
 * ISO-TP is what carries a UDS message longer than 7 bytes over CAN, which is
 * nearly every interesting diagnostic request: a DTC list, an As-Built block,
 * a module's calibration string. Getting the framing exactly right is the
 * difference between reading a module and staring at a timeout, so this file
 * is pure data with no I/O and is covered directly by tests.
 */
sealed class IsoTpFrame {

    /** A whole message that fits in one frame: PCI `0x0N` where N is the length. */
    data class Single(val payload: ByteArray) : IsoTpFrame() {
        init {
            require(payload.size in 1..7) {
                "A single frame carries 1..7 bytes, got ${payload.size}"
            }
        }
        override fun equals(other: Any?) =
            other is Single && payload.contentEquals(other.payload)
        override fun hashCode() = payload.contentHashCode()
    }

    /** Start of a segmented message: PCI `0x1L LL`, total length then 6 bytes. */
    data class First(val totalLength: Int, val payload: ByteArray) : IsoTpFrame() {
        override fun equals(other: Any?) = other is First &&
            totalLength == other.totalLength && payload.contentEquals(other.payload)
        override fun hashCode() = 31 * totalLength + payload.contentHashCode()
    }

    /** Continuation: PCI `0x2N` where N is a sequence number that wraps at 16. */
    data class Consecutive(val sequenceNumber: Int, val payload: ByteArray) : IsoTpFrame() {
        override fun equals(other: Any?) = other is Consecutive &&
            sequenceNumber == other.sequenceNumber && payload.contentEquals(other.payload)
        override fun hashCode() = 31 * sequenceNumber + payload.contentHashCode()
    }

    /** Receiver's permission to continue: PCI `0x3S`, block size, separation time. */
    data class FlowControl(
        val flowStatus: FlowStatus,
        val blockSize: Int,
        val separationTimeRaw: Int,
    ) : IsoTpFrame() {
        /**
         * Decoded separation time in microseconds.
         *
         * 0x00..0x7F is a plain millisecond count. 0xF1..0xF9 is 100..900
         * microseconds. Everything else is reserved, and ISO says a receiver
         * must treat reserved values as the 127 ms maximum rather than
         * guessing - guessing fast here causes dropped frames.
         */
        val separationTimeMicros: Int
            get() = when (separationTimeRaw) {
                in 0x00..0x7F -> separationTimeRaw * 1000
                in 0xF1..0xF9 -> (separationTimeRaw - 0xF0) * 100
                else -> 127_000
            }
    }

    enum class FlowStatus(val code: Int) {
        CONTINUE_TO_SEND(0x0),
        WAIT(0x1),
        OVERFLOW(0x2),
        ;
        companion object {
            fun fromCode(code: Int): FlowStatus? = entries.firstOrNull { it.code == code }
        }
    }

    /** Serialises this frame, padded to [padTo] bytes with [padByte]. */
    fun encode(padTo: Int = 8, padByte: Byte = 0x00): ByteArray {
        val body: ByteArray = when (this) {
            is Single -> byteArrayOf(payload.size.toByte()) + payload

            is First -> {
                if (totalLength <= MAX_CLASSIC_LENGTH) {
                    byteArrayOf(
                        (0x10 or ((totalLength shr 8) and 0x0F)).toByte(),
                        (totalLength and 0xFF).toByte(),
                    ) + payload
                } else {
                    // Escape form: FF_DL of zero, then a 32-bit length.
                    byteArrayOf(
                        0x10, 0x00,
                        (totalLength shr 24).toByte(),
                        (totalLength shr 16).toByte(),
                        (totalLength shr 8).toByte(),
                        totalLength.toByte(),
                    ) + payload
                }
            }

            is Consecutive ->
                byteArrayOf((0x20 or (sequenceNumber and 0x0F)).toByte()) + payload

            is FlowControl -> byteArrayOf(
                (0x30 or flowStatus.code).toByte(),
                blockSize.toByte(),
                separationTimeRaw.toByte(),
            )
        }
        if (body.size >= padTo) return body
        return body + ByteArray(padTo - body.size) { padByte }
    }

    companion object {
        /** Largest length expressible in a classic 12-bit FF_DL field. */
        const val MAX_CLASSIC_LENGTH = 0xFFF

        /**
         * Parses a CAN frame payload as an ISO-TP frame.
         *
         * Returns null for an unrecognised PCI type rather than throwing, so a
         * stray frame from an unrelated module on a shared bus is skipped
         * instead of aborting a reassembly in progress.
         */
        fun parse(data: ByteArray): IsoTpFrame? {
            if (data.isEmpty()) return null
            val pci = data.u8(0)

            return when (pci and 0xF0) {
                0x00 -> {
                    val length = pci and 0x0F
                    if (length !in 1..7 || data.size < 1 + length) null
                    else Single(data.copyOfRange(1, 1 + length))
                }

                0x10 -> {
                    if (data.size < 2) return null
                    val shortLength = ((pci and 0x0F) shl 8) or data.u8(1)
                    if (shortLength == 0) {
                        // Escape form carries a 32-bit length in bytes 2..5.
                        if (data.size < 6) return null
                        val long = data.u32(2)
                        if (long <= 0 || long > Int.MAX_VALUE) return null
                        First(long.toInt(), data.copyOfRange(6, data.size))
                    } else {
                        if (data.size < 3) return null
                        First(shortLength, data.copyOfRange(2, data.size))
                    }
                }

                0x20 -> Consecutive(pci and 0x0F, data.copyOfRange(1, data.size))

                0x30 -> {
                    if (data.size < 3) return null
                    val status = FlowStatus.fromCode(pci and 0x0F) ?: return null
                    FlowControl(status, data.u8(1), data.u8(2))
                }

                else -> null
            }
        }

        /** Convenience for the common two-byte header case. */
        fun firstFrameLength(data: ByteArray): Int? =
            (parse(data) as? First)?.totalLength
    }
}
