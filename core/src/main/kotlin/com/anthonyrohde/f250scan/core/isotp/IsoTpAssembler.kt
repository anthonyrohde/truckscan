package com.anthonyrohde.f250scan.core.isotp

/**
 * Reassembles an incoming ISO-TP message frame by frame.
 *
 * Stateful but I/O free. The caller feeds it frames and reacts to the returned
 * [Progress]; this keeps sequence-number and length validation - the checks
 * that catch a dropped consecutive frame - out of the networking code and
 * under test.
 */
class IsoTpAssembler {

    sealed class Progress {
        /** Message finished and validated. */
        data class Complete(val payload: ByteArray) : Progress() {
            override fun equals(other: Any?) =
                other is Complete && payload.contentEquals(other.payload)
            override fun hashCode() = payload.contentHashCode()
        }

        /**
         * A first frame was accepted; the caller must now send flow control.
         * [totalLength] lets the UI show progress on a long As-Built read.
         */
        data class NeedsFlowControl(val totalLength: Int) : Progress()

        /** More consecutive frames expected. */
        data class Continuing(val received: Int, val totalLength: Int) : Progress()

        /** Frame was not part of this message and was ignored. */
        object Ignored : Progress()

        data class Failed(val reason: String) : Progress()
    }

    private var buffer: ByteArray = ByteArray(0)
    private var expectedLength: Int = 0
    private var nextSequence: Int = 1
    private var active: Boolean = false

    val isActive: Boolean get() = active
    val totalLength: Int get() = expectedLength
    val receivedLength: Int get() = buffer.size

    fun reset() {
        buffer = ByteArray(0)
        expectedLength = 0
        nextSequence = 1
        active = false
    }

    fun accept(frame: IsoTpFrame): Progress = when (frame) {
        is IsoTpFrame.Single -> {
            reset()
            Progress.Complete(frame.payload)
        }

        is IsoTpFrame.First -> {
            reset()
            if (frame.totalLength <= 7) {
                // A first frame declaring a length that fits in a single frame
                // is malformed; treating it as valid would desynchronise the
                // sequence counter for everything that follows.
                Progress.Failed(
                    "First frame declares length ${frame.totalLength}, " +
                        "which should have been sent as a single frame",
                )
            } else {
                active = true
                expectedLength = frame.totalLength
                // A first frame carries exactly 6 payload bytes; trim any
                // padding the module added to fill the 8-byte frame.
                buffer = frame.payload.copyOfRange(0, minOf(6, frame.payload.size))
                nextSequence = 1
                Progress.NeedsFlowControl(frame.totalLength)
            }
        }

        is IsoTpFrame.Consecutive -> when {
            !active -> Progress.Ignored

            frame.sequenceNumber != nextSequence -> Progress.Failed(
                "Out-of-order consecutive frame: expected SN=$nextSequence, " +
                    "got SN=${frame.sequenceNumber}. A frame was dropped - " +
                    "reduce the request rate or tighten the receive filter.",
            )

            else -> {
                val remaining = expectedLength - buffer.size
                val take = minOf(remaining, frame.payload.size)
                buffer += frame.payload.copyOfRange(0, take)
                nextSequence = (nextSequence + 1) and 0x0F

                if (buffer.size >= expectedLength) {
                    val complete = buffer.copyOf()
                    reset()
                    Progress.Complete(complete)
                } else {
                    Progress.Continuing(buffer.size, expectedLength)
                }
            }
        }

        // Flow control is a sender-side concern; a receiver seeing one is
        // looking at the other half of a conversation, not at its own message.
        is IsoTpFrame.FlowControl -> Progress.Ignored
    }
}
