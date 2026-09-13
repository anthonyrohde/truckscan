package com.anthonyrohde.truckscan.core.isotp

import com.anthonyrohde.truckscan.core.adapter.AdapterResponse
import com.anthonyrohde.truckscan.core.adapter.CanFrame
import com.anthonyrohde.truckscan.core.adapter.ElmAdapter
import com.anthonyrohde.truckscan.core.transport.TransportException
import com.anthonyrohde.truckscan.core.util.Hex
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
     * Runs [block] as one exchange with the adapter, excluding any other
     * exchange - a request, a bus switch, another module's poll - until it
     * finishes.
     *
     * [request], [receive] and [send] each talk to the adapter over several
     * separate calls (set header, set filter, transmit, collect), and a caller
     * that needs more than one of those calls to happen as a unit - a UDS
     * conversation that may retry across a "response pending", a parameter read
     * that keeps reading past a reply meant for something else - must wrap the
     * whole thing in this, not just one call. See [ElmAdapter.exclusive] for
     * why this is a second lock rather than reusing the one inside those
     * individual calls.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T = adapter.exclusive(block)

    /**
     * Frames that arrived with the previous message but belong to the next one.
     *
     * One adapter read routinely returns more than one CAN frame, and they are
     * not always one message. A module answering 0x78 "response pending" and
     * then immediately sending the real reply puts both in the same read: the
     * single frame completes, and the first frame of what follows is still in
     * hand. Dropping it deadlocks the exchange - the module waits for flow
     * control that is never sent, because the receiver is waiting for a first
     * frame it already threw away.
     */
    private val carried = ArrayDeque<CanFrame>()

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

        // A new request starts a new conversation; anything held over from the
        // last one is stale and must not contaminate this reply.
        carried.clear()

        val frames = IsoTpSegmenter.segment(payload)
        log("TX ${payload.toHex()} as ${frames.size} frame(s)")

        val replyFrames: List<CanFrame> = if (frames.size == 1) {
            sendAndCollect(frames.single(), timeoutMillis)
        } else {
            sendSegmented(frames, timeoutMillis)
        }

        return reassemble(ours(replyFrames, rxId), timeoutMillis, rxId)
    }

    /**
     * Keeps only the frames that came from the address this exchange is with.
     *
     * The adapter's own receive filter should have done this, and usually has.
     * What it cannot do is unsend a frame that was already in flight when the
     * filter changed: that frame is sitting in the serial buffer, and the next
     * command reads it as its own reply.
     *
     * On a truck that produced 52 modules at consecutive addresses - 755, 756,
     * 757 and on - none of which exist. Each request was being shown the
     * previous address's answer, so a scan that met one slow module reported a
     * module at every address after it. Consecutive addresses are the signature:
     * a real vehicle does not fill a block.
     *
     * Checking the identifier is the only defence that does not depend on the
     * adapter or the operating system's buffering, because the expected
     * identifier is the one piece of information this layer definitely has.
     */
    private fun ours(frames: List<CanFrame>, rxId: Int): List<CanFrame> {
        if (frames.all { it.id == rxId }) return frames
        val (mine, strays) = frames.partition { it.id == rxId }
        log(
            "Discarded ${strays.size} frame(s) not from ${Hex.encode(rxId, 3)}: " +
                strays.joinToString(" ") { it.toString() },
        )
        return mine
    }

    /**
     * Reassembles a message without sending anything first.
     *
     * Used when a module has replied "response pending" (NRC 0x78): the real
     * answer arrives unprompted, so re-sending the request would be wrong.
     */
    suspend fun receive(rxId: Int, timeoutMillis: Long): ByteArray {
        adapter.setRxFilter(rxId)

        // Frames held over from the previous message come first. When a module
        // sends 0x78 and the real reply back to back, the reply's first frame
        // is already here and reading the adapter again would wait for
        // something that has already arrived.
        val held = carried.toList()
        carried.clear()

        val frames = held + if (held.isEmpty()) {
            adapter.collectFrames(timeoutMillis)
        } else {
            emptyList()
        }
        if (frames.isEmpty()) {
            throw IsoTpException("Timed out waiting for a deferred response from the module.")
        }
        return reassemble(ours(frames, rxId), timeoutMillis, rxId)
    }

    /**
     * Fire-and-forget, for TesterPresent with the suppress-response bit set.
     *
     * `ATR0` is right here and nowhere else: the request explicitly asks the
     * module not to answer, so there is no reply to lose and nothing is
     * collected from the read. Everywhere else it was found to hide frames
     * rather than save time.
     */
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
        // A module may start answering before we have finished asking. Frames
        // that arrive while the request is still streaming are the front of
        // that answer, and are carried to the reassembler rather than dropped.
        val early = mutableListOf<CanFrame>()

        // No ATR0 here, and that is a change made on evidence from the other
        // direction. Suppressing responses was measured to make the adapter
        // return a bare prompt with no frames at all, which would make the
        // `early` collection below permanently empty and could swallow a
        // block-size flow control the module sends mid-transfer. The cost of
        // not suppressing is roughly 30 ms per frame on a request long enough
        // to need segmenting, which is rare and worth paying.
        //
        // UNTESTED ON HARDWARE. Nothing this app does routinely sends a request
        // longer than seven bytes, and the read-only probe console cannot test
        // it: a first frame begins 0x10, which is also the byte for
        // DiagnosticSessionControl, so the allowlist refuses to put it on a bus.
        // That refusal is correct and stays. This path is reasoned, not measured,
        // and is marked as such rather than described as if it were proven.
        consecutive.dropLast(1).forEachIndexed { index, frame ->
            early += adapter.sendFrameNoWait(frame.encode(padTo, config.padByte))
            if (gapMicros > 0) delay((gapMicros / 1000L).coerceAtLeast(1))
            // Honour the receiver's block size: after every `blockSize`
            // frames it expects to send us another flow control frame.
            if (blockSize > 0 && (index + 1) % blockSize == 0 &&
                index != consecutive.size - 2
            ) {
                val next = awaitFlowControlOnly()
                if (next.flowStatus == IsoTpFrame.FlowStatus.OVERFLOW) {
                    throw IsoTpException("Module reported overflow mid-transfer")
                }
            }
        }

        val (last, status) = adapter.sendFrameAndCollect(
            consecutive.last().encode(padTo, config.padByte),
            timeoutMillis,
        )
        val all = early + last
        if (all.isEmpty()) throwForStatus(status)
        return all
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
    private suspend fun reassemble(
        initial: List<CanFrame>,
        timeoutMillis: Long,
        rxId: Int,
    ): ByteArray {
        if (initial.isEmpty()) {
            throw IsoTpException(
                "Nothing arrived from ${Hex.encode(rxId, 3)}. Any frames that did " +
                    "arrive came from another address and were not this module's reply.",
            )
        }
        val assembler = IsoTpAssembler()
        val queue = ArrayDeque(initial)

        try {
            return reassembleLoop(assembler, queue, timeoutMillis, rxId) { }
        } finally {
            // Whatever is left belongs to the next message.
            carried.addAll(queue)
        }
    }

    private suspend fun reassembleLoop(
        assembler: IsoTpAssembler,
        queue: ArrayDeque<CanFrame>,
        timeoutMillis: Long,
        rxId: Int,
        onFlowControlSent: () -> Unit,
    ): ByteArray {
        var flowControlSent = false
        var declaredLength = 0
        val initial = queue.toList()

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
                // Anything that arrives in the same read as the flow control
                // goes straight back into the queue rather than being thrown
                // away - with no separation time the module's first
                // consecutive frames get there that fast.
                val alongside = sendFlowControl()
                flowControlSent = true
                onFlowControlSent()
                val mine = ours(alongside, rxId)
                if (mine.isNotEmpty()) {
                    queue.addAll(mine)
                    continue
                }
            }

            // Scale the read window with the declared size: a long As-Built
            // read can be dozens of frames.
            val extra = (declaredLength / 7L) * 10L
            val window = (config.consecutiveTimeoutMillis + extra)
                .coerceAtMost(timeoutMillis + 10_000)

            val more = ours(adapter.collectFrames(window), rxId)
            if (more.isEmpty()) {
                throw IsoTpException(
                    "Incomplete reply: got ${assembler.receivedLength} of " +
                        "${assembler.totalLength} bytes before the module went quiet.",
                )
            }
            queue.addAll(more)
        }
    }

    /**
     * Grants the module the rest of the transfer, and returns whatever it sends
     * back in the same read - which is normally the whole remainder.
     *
     * This used to wrap the flow control in `ATR0`/`ATR1` on the reasoning that
     * the adapter would otherwise sit out the ATST timeout waiting for a reply
     * to a frame that has none, and that restoring responses mid-burst would
     * make the firmware service the AT command instead of the bus and lose the
     * consecutive frames. Both halves were measured on an OBDLink EX against a
     * running 2022 F-250, and both were wrong:
     *
     *   - Sent plainly, a flow control for a 17-byte VIN returned both
     *     consecutive frames in 58 ms. There is no timeout to avoid.
     *   - With `ATR0` in front of it, the same flow control returned a bare
     *     prompt in 29 ms and no frames at all.
     *   - An `ATCRA` deliberately inserted between the first frame and the
     *     flow control cost nothing: the consecutive frames still arrived.
     *
     * So the suppression this code was built around was not protecting the
     * burst, it was hiding it. Send the frame and read the answer.
     */
    private suspend fun sendFlowControl(): List<CanFrame> {
        val fc = IsoTpFrame.FlowControl(
            IsoTpFrame.FlowStatus.CONTINUE_TO_SEND,
            config.blockSize,
            config.separationTimeRaw,
        )
        return adapter.sendFrameNoWait(fc.encode(padTo, config.padByte))
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
