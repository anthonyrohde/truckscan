package com.anthonyrohde.truckscan.core.isotp

import com.anthonyrohde.truckscan.core.adapter.AdapterResponse
import com.anthonyrohde.truckscan.core.adapter.CanFrame
import com.anthonyrohde.truckscan.core.adapter.ElmAdapter
import com.anthonyrohde.truckscan.core.transport.TransportException
import com.anthonyrohde.truckscan.core.util.toHex
import kotlinx.coroutines.delay

data class IsoTpConfig(
    /**
     * Block size we advertise when receiving. Zero means "send everything
     * without pausing for further flow control", which is what we want: the
     * adapter buffers the burst and one read collects the whole message.
     */
    val blockSize: Int = 0,
    /** Separation time we request, in the ISO-TP raw encoding. 0 = no delay. */
    val separationTimeRaw: Int = 0,
    /**
     * Pad every frame to 8 bytes. Many Ford modules reject short frames
     * outright, so this defaults on.
     */
    val padFrames: Boolean = true,
    val padByte: Byte = 0x00,
    /** How long to wait for the receiver's flow control frame (ISO N_Bs). */
    val flowControlTimeoutMillis: Long = 1_000,
    /** How long to wait for consecutive frames of a response (ISO N_Cr). */
    val consecutiveTimeoutMillis: Long = 2_000,
    /** Guard against a module that parks us in WAIT indefinitely. */
    val maxWaitFrames: Int = 8,
)

class IsoTpException(message: String) : TransportException(message)

/**
 * A point-to-point ISO-TP conversation with one module.
 *
 * Sits between [ElmAdapter] (raw frames) and the UDS client (whole messages).
 * Handles segmentation, flow control in both directions, and the reassembly of
 * segmented responses.
 */
class IsoTpChannel(
    private val adapter: ElmAdapter,
    private val config: IsoTpConfig = IsoTpConfig(),
    private val logger: ((String) -> Unit)? = null,
) {
    private val padTo: Int get() = if (config.padFrames) 8 else 0

    /**
     * Sends [payload] to [txId] and returns the reassembled reply from [rxId].
     *
     * @param timeoutMillis how long to wait for the first reply frame. Callers
     *   raise this for operations a module is slow to answer, such as a
     *   security access request or a routine that runs a self test.
     */
    suspend fun request(
        txId: Int,
        rxId: Int,
        payload: ByteArray,
        timeoutMillis: Long = ElmAdapter.DEFAULT_TIMEOUT_MS,
    ): ByteArray {
        adapter.setTxHeader(txId)
        adapter.setRxFilter(rxId)

        val frames = IsoTpSegmenter.segment(payload)
        log("TX ${payload.toHex()} as ${frames.size} frame(s)")

        val replyFrames: List<CanFrame> = if (frames.size == 1) {
            sendAndCollect(frames.single(), timeoutMillis)
        } else {
            sendSegmented(frames, timeoutMillis)
        }

        return reassemble(replyFrames, timeoutMillis)
    }

    /**
     * Reassembles a message without sending anything first.
     *
     * Used when a module has replied "response pending" (NRC 0x78): the real
     * answer arrives unprompted, so re-sending the request would be wrong.
     */
    suspend fun receive(rxId: Int, timeoutMillis: Long): ByteArray {
        adapter.setRxFilter(rxId)
        val frames = adapter.collectFrames(timeoutMillis)
        if (frames.isEmpty()) {
            throw IsoTpException("Timed out waiting for a deferred response from the module.")
        }
        return reassemble(frames, timeoutMillis)
    }

    /** Fire-and-forget, for TesterPresent with the suppress-response bit set. */
    suspend fun send(txId: Int, payload: ByteArray) {
        adapter.setTxHeader(txId)
        val frames = IsoTpSegmenter.segment(payload)
        adapter.setResponsesEnabled(false)
        try {
            frames.forEach { adapter.sendFrameNoWait(it.encode(padTo, config.padByte)) }
        } finally {
            adapter.setResponsesEnabled(true)
        }
    }

    private suspend fun sendAndCollect(frame: IsoTpFrame, timeoutMillis: Long): List<CanFrame> {
        val (received, status) = adapter.sendFrameAndCollect(
            frame.encode(padTo, config.padByte),
            timeoutMillis,
        )
        if (received.isEmpty()) throwForStatus(status)
        return received
    }

    /**
     * Sends a multi-frame request: first frame, wait for flow control, then
     * stream the consecutive frames.
     */
    private suspend fun sendSegmented(
        frames: List<IsoTpFrame>,
        timeoutMillis: Long,
    ): List<CanFrame> {
        val first = frames.first()
        val consecutive = frames.drop(1)

        // The first frame's reply is the receiver's flow control.
        var fc = awaitFlowControl(first)
        var waits = 0
        while (fc.flowStatus == IsoTpFrame.FlowStatus.WAIT) {
            if (++waits > config.maxWaitFrames) {
                throw IsoTpException(
                    "Module asked us to wait ${config.maxWaitFrames} times without " +
                        "accepting data. It is busy or not in a session that permits " +
                        "this request.",
                )
            }
            fc = awaitFlowControlOnly()
        }
        if (fc.flowStatus == IsoTpFrame.FlowStatus.OVERFLOW) {
            throw IsoTpException(
                "Module reported buffer overflow: the request (${frames.size} frames) " +
                    "is larger than it can accept.",
            )
        }

        val blockSize = fc.blockSize
        val gapMicros = fc.separationTimeMicros

        // Stream everything but the final frame without waiting; the last one
        // carries the timeout we want to collect the actual response on.
        adapter.setResponsesEnabled(false)
        try {
            consecutive.dropLast(1).forEachIndexed { index, frame ->
                adapter.sendFrameNoWait(frame.encode(padTo, config.padByte))
                if (gapMicros > 0) delay((gapMicros / 1000L).coerceAtLeast(1))
                // Honour the receiver's block size: after every `blockSize`
                // frames it expects to send us another flow control frame.
                if (blockSize > 0 && (index + 1) % blockSize == 0 &&
                    index != consecutive.size - 2
                ) {
                    adapter.setResponsesEnabled(true)
                    val next = awaitFlowControlOnly()
                    if (next.flowStatus == IsoTpFrame.FlowStatus.OVERFLOW) {
                        throw IsoTpException("Module reported overflow mid-transfer")
                    }
                    adapter.setResponsesEnabled(false)
                }
            }
        } finally {
            adapter.setResponsesEnabled(true)
        }

        return sendAndCollect(consecutive.last(), timeoutMillis)
    }

    private suspend fun awaitFlowControl(first: IsoTpFrame): IsoTpFrame.FlowControl {
        val (received, status) = adapter.sendFrameAndCollect(
            first.encode(padTo, config.padByte),
            config.flowControlTimeoutMillis,
        )
        if (received.isEmpty()) throwForStatus(status)
        return received.firstNotNullOfOrNull { IsoTpFrame.parse(it.data) as? IsoTpFrame.FlowControl }
            ?: throw IsoTpException(
                "Expected a flow control frame after the first frame but got " +
                    received.joinToString(" ") { it.toString() },
            )
    }

    private suspend fun awaitFlowControlOnly(): IsoTpFrame.FlowControl {
        val received = adapter.collectFrames(config.flowControlTimeoutMillis)
        return received.firstNotNullOfOrNull { IsoTpFrame.parse(it.data) as? IsoTpFrame.FlowControl }
            ?: throw IsoTpException("Timed out waiting for a flow control frame")
    }

    /**
     * Turns received frames into one message, sending flow control and reading
     * further frames only when the reply is segmented and not already in hand.
     *
     * The ordering here matters and is easy to get wrong. Frames already
     * buffered by the adapter are drained into the assembler *before* any flow
     * control goes out, because whether the consecutive frames have already
     * arrived depends on the adapter's firmware and its receive window. Sending
     * flow control first and then reading would discard frames we were already
     * holding - and a spurious flow control frame to a module that has finished
     * transmitting is at best noise on the bus.
     *
     * So: drain, and only if the message is still incomplete ask for the rest.
     */
    private suspend fun reassemble(initial: List<CanFrame>, timeoutMillis: Long): ByteArray {
        val assembler = IsoTpAssembler()
        val queue = ArrayDeque(initial)
        var flowControlSent = false
        var declaredLength = 0

        while (true) {
            // Drain everything we currently hold.
            while (queue.isNotEmpty()) {
                val canFrame = queue.removeFirst()
                val frame = IsoTpFrame.parse(canFrame.data) ?: continue

                when (val progress = assembler.accept(frame)) {
                    is IsoTpAssembler.Progress.Complete -> return progress.payload

                    is IsoTpAssembler.Progress.NeedsFlowControl -> {
                        declaredLength = progress.totalLength
                        log("RX segmented reply, $declaredLength bytes")
                    }

                    is IsoTpAssembler.Progress.Continuing,
                    is IsoTpAssembler.Progress.Ignored,
                    -> Unit

                    is IsoTpAssembler.Progress.Failed ->
                        throw IsoTpException(progress.reason)
                }
            }

            if (!assembler.isActive) {
                throw IsoTpException(
                    "No ISO-TP message could be assembled from: " +
                        initial.joinToString(" ") { it.toString() },
                )
            }

            // Mid-message with nothing left to process: the module is waiting
            // on us. Grant it the rest of the transfer, once.
            if (!flowControlSent) {
                sendFlowControl()
                flowControlSent = true
            }

            // Scale the read window with the declared size: a long As-Built
            // read can be dozens of frames.
            val extra = (declaredLength / 7L) * 10L
            val window = (config.consecutiveTimeoutMillis + extra)
                .coerceAtMost(timeoutMillis + 10_000)

            val more = adapter.collectFrames(window)
            if (more.isEmpty()) {
                throw IsoTpException(
                    "Incomplete reply: got ${assembler.receivedLength} of " +
                        "${assembler.totalLength} bytes before the module went quiet.",
                )
            }
            queue.addAll(more)
        }
    }

    private suspend fun sendFlowControl() {
        val fc = IsoTpFrame.FlowControl(
            IsoTpFrame.FlowStatus.CONTINUE_TO_SEND,
            config.blockSize,
            config.separationTimeRaw,
        )
        adapter.setResponsesEnabled(false)
        try {
            adapter.sendFrameNoWait(fc.encode(padTo, config.padByte))
        } finally {
            adapter.setResponsesEnabled(true)
        }
    }

    private fun throwForStatus(status: AdapterResponse.Status): Nothing = throw IsoTpException(
        when (status) {
            AdapterResponse.Status.NO_DATA ->
                "No response from module. It may be absent, asleep, or on a different bus."
            AdapterResponse.Status.BUS_ERROR ->
                "CAN bus error. Check the adapter is on the right pins and the " +
                    "bitrate matches the bus."
            AdapterResponse.Status.UNABLE_TO_CONNECT ->
                "Adapter could not connect to the bus. Is the ignition on?"
            AdapterResponse.Status.BUFFER_FULL ->
                "Adapter receive buffer overflowed. Reduce the polling rate."
            AdapterResponse.Status.TIMEOUT ->
                "Adapter did not return a prompt - it may have stopped responding."
            AdapterResponse.Status.UNKNOWN_COMMAND ->
                "Adapter rejected the frame. It may not support raw CAN mode (ATCAF0)."
            AdapterResponse.Status.OK ->
                "No frames received from module."
        },
    )

    private fun log(message: String) = logger?.invoke(message)
}
