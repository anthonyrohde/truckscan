package com.anthonyrohde.f250scan.core.trace

import com.anthonyrohde.f250scan.core.ford.AsBuiltBlock
import com.anthonyrohde.f250scan.core.ford.AsBuiltDidMap
import com.anthonyrohde.f250scan.core.ford.ChecksumStrategy
import com.anthonyrohde.f250scan.core.isotp.IsoTpAssembler
import com.anthonyrohde.f250scan.core.isotp.IsoTpFrame
import com.anthonyrohde.f250scan.core.uds.UdsService
import com.anthonyrohde.f250scan.core.util.u8

/** One reassembled diagnostic message recovered from a capture. */
data class TraceMessage(
    val canId: Int,
    val payload: ByteArray,
    val lineNumber: Int,
    val relativeSeconds: Double?,
) {
    val serviceByte: Int get() = payload.u8(0)

    /** True when this is a request: the first byte is a service identifier. */
    val isRequest: Boolean
        get() = UdsService.fromSid(serviceByte) != null || serviceByte in LEGACY_MODES

    /** True when this is a reply: a positive echo, or a negative response. */
    val isResponse: Boolean
        get() = serviceByte == NEGATIVE_RESPONSE ||
            UdsService.fromSid(serviceByte - 0x40) != null ||
            (serviceByte - 0x40) in LEGACY_MODES

    val isNegative: Boolean get() = serviceByte == NEGATIVE_RESPONSE

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TraceMessage) return false
        return canId == other.canId && payload.contentEquals(other.payload) &&
            lineNumber == other.lineNumber
    }

    override fun hashCode(): Int = 31 * (31 * canId + lineNumber) + payload.contentHashCode()

    companion object {
        const val NEGATIVE_RESPONSE = 0x7F

        /** Legacy OBD-II modes, which some Ford modules still answer. */
        val LEGACY_MODES = setOf(0x01, 0x02, 0x03, 0x04, 0x06, 0x07, 0x09, 0x0A)
    }
}

/** A request paired with the reply it drew. */
data class TraceExchange(
    val requestId: Int,
    val responseId: Int,
    val request: TraceMessage,
    val response: TraceMessage?,
)

/**
 * Reconstructs diagnostic conversations from a raw bus capture.
 *
 * Works in three passes:
 *
 *  1. Reassemble ISO-TP per CAN identifier, reusing the same
 *     [IsoTpAssembler] the live stack uses. A capture is just the same frames
 *     without the timing, so the tested reassembler applies unchanged.
 *  2. Classify each message as a request or a reply from its service byte.
 *     Request identifiers occupy 0x10-0x3E and 0x85, replies 0x50-0x7E and
 *     0xC5, so the two never collide and no direction marker is needed - which
 *     matters because plenty of logs do not record one.
 *  3. Pair requests with replies using Ford's convention that a response
 *     identifier is the request identifier plus eight, then read the learned
 *     facts out of the pairs.
 */
class TraceLogAnalyzer(private val logger: ((String) -> Unit)? = null) {

    fun analyse(
        frames: List<TraceFrame>,
        sourceDescription: String = "bus capture",
    ): LearnedProfile {
        val messages = reassemble(frames)
        val exchanges = pair(messages)
        val modules = learn(exchanges)

        logger?.invoke(
            "Analysed ${frames.size} frame(s) into ${messages.size} message(s) and " +
                "${exchanges.size} exchange(s) across ${modules.size} module(s)",
        )

        return LearnedProfile(
            modules = modules,
            sourceDescription = sourceDescription,
            framesParsed = frames.size,
            messagesReconstructed = messages.size,
        )
    }

    /** Convenience: parse and analyse in one step. */
    fun analyseText(
        text: String,
        sourceDescription: String = "bus capture",
    ): Pair<LearnedProfile, TraceParseReport> {
        val (frames, report) = TraceLineParser.parse(text)
        return analyse(frames, sourceDescription) to report
    }

    // ------------------------------------------------------------------ pass one

    /**
     * Reassembles ISO-TP messages, one assembler per CAN identifier.
     *
     * Flow control frames are ignored by the assembler, which is correct here:
     * in a capture they appear on the opposite identifier and carry no payload
     * of their own.
     */
    internal fun reassemble(frames: List<TraceFrame>): List<TraceMessage> {
        val assemblers = mutableMapOf<Int, IsoTpAssembler>()
        val messages = mutableListOf<TraceMessage>()

        for (frame in frames) {
            val isoTp = IsoTpFrame.parse(frame.data) ?: continue
            val assembler = assemblers.getOrPut(frame.canId) { IsoTpAssembler() }

            when (val progress = assembler.accept(isoTp)) {
                is IsoTpAssembler.Progress.Complete ->
                    messages += TraceMessage(
                        frame.canId,
                        progress.payload,
                        frame.lineNumber,
                        frame.relativeSeconds,
                    )

                is IsoTpAssembler.Progress.Failed -> {
                    // A capture can miss frames; drop the partial message and
                    // resynchronise rather than abandoning the whole log.
                    logger?.invoke("Line ${frame.lineNumber}: ${progress.reason}")
                    assembler.reset()
                }

                else -> Unit
            }
        }
        return messages
    }

    // ------------------------------------------------------------------ pass two

    internal fun pair(messages: List<TraceMessage>): List<TraceExchange> {
        val pending = mutableMapOf<Int, ArrayDeque<TraceMessage>>()
        val exchanges = mutableListOf<TraceExchange>()

        for (message in messages) {
            if (message.payload.isEmpty()) continue

            if (message.isRequest) {
                pending.getOrPut(message.canId) { ArrayDeque() }.addLast(message)
                continue
            }

            if (!message.isResponse) continue

            // Ford convention: response identifier is the request plus eight.
            val requestId = requestIdFor(message.canId)
            val queue = pending[requestId]
            val request = queue?.removeFirstOrNull()

            if (request != null) {
                // A "response pending" reply is an interim acknowledgement, not
                // the answer; put the request back so it pairs with the real one.
                if (message.isNegative && message.payload.size >= 3 &&
                    message.payload.u8(2) == RESPONSE_PENDING
                ) {
                    queue.addFirst(request)
                    continue
                }
                exchanges += TraceExchange(requestId, message.canId, request, message)
            }
        }

        // Requests that never drew a reply are still evidence the tool sent them.
        for ((canId, queue) in pending) {
            for (orphan in queue) {
                exchanges += TraceExchange(canId, canId + 8, orphan, null)
            }
        }

        return exchanges.sortedBy { it.request.lineNumber }
    }

    /**
     * Maps a response identifier back to its request identifier.
     *
     * Standard OBD-II responders answer in 0x7E8-0x7EF to a functional request
     * at 0x7DF; everything else follows the plus-eight rule.
     */
    private fun requestIdFor(responseId: Int): Int = responseId - 8

    // ---------------------------------------------------------------- pass three

    internal fun learn(exchanges: List<TraceExchange>): List<LearnedModule> {
        data class Accumulator(
            val config: LinkedHashMap<Int, ByteArray> = linkedMapOf(),
            val ident: LinkedHashMap<Int, ByteArray> = linkedMapOf(),
            val written: LinkedHashSet<Int> = linkedSetOf(),
            val routines: LinkedHashSet<Int> = linkedSetOf(),
            val seedKeys: MutableList<SeedKeyObservation> = mutableListOf(),
            /** Seed awaiting the key that answers it, by security level. */
            val pendingSeeds: MutableMap<Int, ByteArray> = mutableMapOf(),
        )

        val byModule = linkedMapOf<Int, Accumulator>()
        val responseIds = mutableMapOf<Int, Int>()

        for (exchange in exchanges) {
            val accumulator = byModule.getOrPut(exchange.requestId) { Accumulator() }
            responseIds[exchange.requestId] = exchange.responseId

            val request = exchange.request.payload
            val response = exchange.response?.payload
            val positive = exchange.response?.isNegative == false

            when (request.u8(0)) {
                UdsService.READ_DATA_BY_IDENTIFIER.sid -> {
                    if (request.size < 3 || response == null || !positive) continue
                    val did = (request.u8(1) shl 8) or request.u8(2)
                    // Response is 0x62, the echoed identifier, then the data.
                    if (response.size < 3) continue
                    val echoed = (response.u8(1) shl 8) or response.u8(2)
                    if (echoed != did) continue

                    val data = response.copyOfRange(3, response.size)
                    if (data.isEmpty()) continue

                    if (LearnedProfile.isConfiguration(did)) {
                        accumulator.config[did] = data
                    } else {
                        accumulator.ident[did] = data
                    }
                }

                UdsService.WRITE_DATA_BY_IDENTIFIER.sid -> {
                    if (request.size < 3) continue
                    val did = (request.u8(1) shl 8) or request.u8(2)
                    accumulator.written += did
                    // A write also reveals the identifier's content and length.
                    if (request.size > 3 && LearnedProfile.isConfiguration(did)) {
                        accumulator.config.putIfAbsent(
                            did,
                            request.copyOfRange(3, request.size),
                        )
                    }
                }

                UdsService.ROUTINE_CONTROL.sid -> {
                    if (request.size < 4) continue
                    accumulator.routines += (request.u8(2) shl 8) or request.u8(3)
                }

                UdsService.SECURITY_ACCESS.sid -> {
                    if (request.size < 2) continue
                    val subFunction = request.u8(1)

                    if (subFunction % 2 == 1) {
                        // Odd sub-function requests a seed; the seed is in the reply.
                        if (response == null || !positive || response.size < 3) continue
                        accumulator.pendingSeeds[subFunction] =
                            response.copyOfRange(2, response.size)
                    } else {
                        // Even sub-function submits the key for the preceding seed.
                        val level = subFunction - 1
                        val seed = accumulator.pendingSeeds.remove(level) ?: continue
                        if (request.size < 3) continue
                        accumulator.seedKeys += SeedKeyObservation(
                            securityLevel = level,
                            seed = seed,
                            key = request.copyOfRange(2, request.size),
                            accepted = positive,
                        )
                    }
                }
            }
        }

        return byModule.map { (requestId, accumulator) ->
            LearnedModule(
                requestId = requestId,
                responseId = responseIds[requestId] ?: (requestId + 8),
                configurationDids = accumulator.config,
                identificationDids = accumulator.ident,
                writtenDids = accumulator.written,
                routineIds = accumulator.routines,
                seedKeyObservations = accumulator.seedKeys,
                checksumStrategy = detectChecksum(requestId, accumulator.config),
            )
        }.filter { it.hasAnything }
    }

    /**
     * Identifies the checksum algorithm from real blocks.
     *
     * Same approach as the live reader: treat the final byte as a checksum and
     * keep the algorithm only if it explains every block unanimously. A capture
     * usually gives more blocks to test against than a single live read, so
     * this is the most reliable place the question gets answered.
     */
    private fun detectChecksum(
        moduleAddress: Int,
        config: Map<Int, ByteArray>,
    ): ChecksumStrategy? {
        val blocks = config
            .filter { it.value.size >= 2 }
            .map { (did, data) ->
                AsBuiltBlock(
                    moduleAddress = moduleAddress,
                    blockId = AsBuiltDidMap.blockIdForDid(did),
                    data = data.copyOfRange(0, data.size - 1),
                    checksum = data[data.size - 1].toInt() and 0xFF,
                )
            }
        return ChecksumStrategy.detect(blocks)
    }

    companion object {
        private const val RESPONSE_PENDING = 0x78
    }
}
