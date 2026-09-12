package com.anthonyrohde.truckscan.core.isotp

/**
 * Splits an outgoing message into ISO-TP frames.
 *
 * Kept pure so the segmentation arithmetic - which is where off-by-one errors
 * silently truncate a module write - can be tested exhaustively without any
 * adapter present.
 */
object IsoTpSegmenter {

    /**
     * The frames needed to send [payload].
     *
     * For a message of 7 bytes or fewer this is a single frame. Otherwise it is
     * a first frame carrying 6 bytes followed by consecutive frames of up to 7
     * bytes each, with sequence numbers starting at 1 and wrapping modulo 16.
     */
    fun segment(payload: ByteArray): List<IsoTpFrame> {
        require(payload.isNotEmpty()) { "Cannot send an empty ISO-TP message" }

        if (payload.size <= 7) return listOf(IsoTpFrame.Single(payload))

        val frames = mutableListOf<IsoTpFrame>()
        frames += IsoTpFrame.First(payload.size, payload.copyOfRange(0, 6))

        var offset = 6
        var sequence = 1
        while (offset < payload.size) {
            val end = minOf(offset + 7, payload.size)
            frames += IsoTpFrame.Consecutive(sequence, payload.copyOfRange(offset, end))
            offset = end
            sequence = (sequence + 1) and 0x0F
        }
        return frames
    }

    /** True when [payload] needs flow control, i.e. more than one frame. */
    fun requiresFlowControl(payload: ByteArray): Boolean = payload.size > 7
}
