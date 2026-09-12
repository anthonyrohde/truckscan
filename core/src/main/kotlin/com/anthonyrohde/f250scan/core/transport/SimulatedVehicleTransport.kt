package com.anthonyrohde.f250scan.core.transport

import com.anthonyrohde.f250scan.core.isotp.IsoTpFrame
import com.anthonyrohde.f250scan.core.isotp.IsoTpSegmenter
import com.anthonyrohde.f250scan.core.util.Hex
import com.anthonyrohde.f250scan.core.util.u8
import kotlinx.coroutines.delay

/**
 * A fake truck.
 *
 * Speaks enough of the ELM327 command set and enough UDS to exercise the whole
 * stack - discovery, identification, DTC reads, As-Built reads and writes -
 * without a vehicle, an adapter, or a cable. That matters for two reasons:
 * the UI can be built and demonstrated on a desk, and the protocol layers get
 * end-to-end coverage in tests rather than only unit coverage of their parts.
 *
 * It is a simulator, not a model of a real F-250: the module list is
 * representative and the As-Built bytes are invented. It will not tell you
 * anything about your actual truck.
 */
class SimulatedVehicleTransport(
    /** Report as an STN adapter, so multi-bus paths are exercised. */
    private val reportAsStn: Boolean = true,
    /** Artificial latency per exchange, to approximate real bus timing. */
    private val latencyMillis: Long = 2,
) : ObdTransport {

    override var isOpen: Boolean = false
        private set

    override val description: String =
        "Simulated vehicle (${if (reportAsStn) "STN2120" else "ELM327"})"

    private val outbound = StringBuilder()
    private var inbound = StringBuilder()

    private var txHeader: Int = 0x7DF
    private var rxFilter: Int? = null
    private var headersOn = true
    private var responsesOn = true
    private var echoOn = false

    /** Partially received multi-frame request, keyed by target module. */
    private val pendingRequests = mutableMapOf<Int, MutableList<Byte>>()
    private var pendingExpectedLength = 0

    // ------------------------------------------------------------- simulated ECUs

    /**
     * One simulated module.
     *
     * [bitrateBps] is what makes the simulator model bus affinity: a module
     * only answers while the adapter is configured for its bus. Without that
     * the simulator answers everything regardless of bus selection, which
     * hides exactly the class of bug where code forgets to switch buses
     * before addressing a module.
     */
    private class SimModule(
        val requestId: Int,
        val code: String,
        val bitrateBps: Int,
        val dids: MutableMap<Int, ByteArray>,
        val dtcs: MutableList<Triple<Int, Int, Int>>, // code high, code low, status
    ) {
        val responseId: Int get() = requestId + 8
    }

    /** Bitrate the adapter is currently configured for. Defaults to HS-CAN. */
    private var selectedBitrate: Int = HIGH_SPEED_BPS

    /** Rate staged by `ATPB`, applied when `ATSPB` selects protocol B. */
    private var stagedProtocolBBitrate: Int = HIGH_SPEED_BPS

    private fun ascii(text: String, length: Int): ByteArray {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        return if (bytes.size >= length) bytes.copyOf(length)
        else bytes + ByteArray(length - bytes.size) { 0x20 }
    }

    private val modules: Map<Int, SimModule> = listOf(
        SimModule(
            requestId = 0x7E0, code = "PCM", bitrateBps = HIGH_SPEED_BPS,
            dids = mutableMapOf(
                0xF190 to ascii("1FT8W2BT7NEC12345", 17),
                0xF187 to ascii("LC3A-14C204-BFD", 16),
                // Distinct from the part number: a module's calibration level
                // moves with reflashes while its hardware part number does not,
                // and showing the same string for both hides that.
                0xF188 to ascii("LC3A-14C204-AKG", 16),
                0xF18C to ascii("PCM0012345678", 14),
                // Two invented configuration blocks, with a two's complement
                // checksum as the final byte so checksum detection has
                // something consistent to find.
                0xDE00 to withTwosComplement(byteArrayOf(0x0F, 0x14, 0x00, 0x04, 0x00, 0x00)),
                0xDE01 to withTwosComplement(byteArrayOf(0x22, 0x00, 0x11, 0x03)),
            ),
            dtcs = mutableListOf(
                // P0299 turbo underboost, FTB 0x00, confirmed + MIL requested.
                Triple(0x02, 0x99, 0x8B),
                // P242F DPF ash accumulation, pending only.
                Triple(0x24, 0x2F, 0x04),
            ),
        ),
        SimModule(
            requestId = 0x7E1, code = "TCM", bitrateBps = HIGH_SPEED_BPS,
            dids = mutableMapOf(
                0xF190 to ascii("1FT8W2BT7NEC12345", 17),
                0xF187 to ascii("LC3P-7J012-AC", 14),
            ),
            dtcs = mutableListOf(),
        ),
        SimModule(
            requestId = 0x726, code = "BCM", bitrateBps = MEDIUM_SPEED_BPS,
            dids = mutableMapOf(
                0xF190 to ascii("1FT8W2BT7NEC12345", 17),
                0xF187 to ascii("LC3T-14B476-AKE", 16),
                0xDE00 to withTwosComplement(byteArrayOf(0x41, 0x02, 0x00, 0x00, 0x1C, 0x08)),
                0xDE01 to withTwosComplement(byteArrayOf(0x00, 0x30, 0x00, 0x00)),
                0xDE02 to withTwosComplement(byteArrayOf(0x7F, 0x11, 0x02, 0x00, 0x00, 0x40)),
            ),
            dtcs = mutableListOf(
                // U0155 lost comms with IPC, historic.
                Triple(0xC1, 0x55, 0x28),
            ),
        ),
        SimModule(
            requestId = 0x720, code = "IPC", bitrateBps = MEDIUM_SPEED_BPS,
            dids = mutableMapOf(
                0xF187 to ascii("LC3T-10849-AAB", 14),
                0xDE00 to withTwosComplement(byteArrayOf(0x10, 0x00, 0x04, 0x22)),
            ),
            dtcs = mutableListOf(),
        ),
        SimModule(
            requestId = 0x7D0, code = "APIM", bitrateBps = MEDIUM_SPEED_BPS,
            dids = mutableMapOf(
                0xF187 to ascii("LU5T-14G371-BAE", 16),
                0xDE00 to withTwosComplement(byteArrayOf(0x00, 0x00, 0x00, 0x05, 0x00, 0x00)),
                0xDE01 to withTwosComplement(byteArrayOf(0x06, 0x0B, 0x00, 0x00)),
            ),
            dtcs = mutableListOf(),
        ),
    ).associateBy { it.requestId }

    /** Live parameter values the simulator reports for mode 01. */
    private val livePids: Map<Int, ByteArray> = mapOf(
        0x00 to byteArrayOf(0xBE.toByte(), 0x3F, 0xA8.toByte(), 0x13),
        0x04 to byteArrayOf(0x4C),
        0x05 to byteArrayOf(0x5A), // 90 - 40 = 50 C
        0x0B to byteArrayOf(0x68),
        0x0C to byteArrayOf(0x0C, 0x50), // (0x0C50)/4 = 788 rpm
        0x0D to byteArrayOf(0x00),
        0x0F to byteArrayOf(0x46),
        0x10 to byteArrayOf(0x02, 0x1C),
        0x11 to byteArrayOf(0x1A),
        0x1F to byteArrayOf(0x03, 0x20),
        0x2F to byteArrayOf(0x9A.toByte()),
        0x42 to byteArrayOf(0x36, 0xB0.toByte()), // 14.0 V
        0x46 to byteArrayOf(0x3C),
        0x5C to byteArrayOf(0x64),
        0x5E to byteArrayOf(0x00, 0x3C),
    )

    // ------------------------------------------------------------------ transport

    override suspend fun open() {
        isOpen = true
        outbound.clear()
        inbound.clear()
    }

    override suspend fun close() {
        isOpen = false
    }

    override suspend fun write(bytes: ByteArray) {
        if (!isOpen) throw TransportClosedException()
        outbound.append(String(bytes, Charsets.US_ASCII))

        // Process every complete command in the buffer.
        while (true) {
            val index = outbound.indexOf("\r")
            if (index < 0) break
            val command = outbound.substring(0, index).trim()
            outbound.delete(0, index + 1)
            handleCommand(command)
        }
    }

    override suspend fun read(timeoutMillis: Long): ByteArray {
        if (!isOpen) throw TransportClosedException()
        if (inbound.isEmpty()) {
            delay(minOf(timeoutMillis, latencyMillis))
            if (inbound.isEmpty()) return ByteArray(0)
        }
        val text = inbound.toString()
        inbound = StringBuilder()
        return text.toByteArray(Charsets.US_ASCII)
    }

    // -------------------------------------------------------------- command layer

    private fun respond(text: String) {
        inbound.append(text)
    }

    private fun prompt() = respond(">")

    private fun ok() {
        respond("OK\r")
        prompt()
    }

    private suspend fun handleCommand(command: String) {
        if (echoOn && command.isNotEmpty()) respond("$command\r")

        val upper = command.uppercase()
        when {
            command.isEmpty() -> prompt()

            upper == "ATZ" -> {
                respond("\rELM327 v1.5\r")
                prompt()
            }
            upper == "ATI" -> {
                respond(if (reportAsStn) "STN2120 v5.6.7\r" else "ELM327 v1.5\r")
                prompt()
            }
            upper == "STI" -> {
                if (reportAsStn) { respond("STN2120 v5.6.7\r"); prompt() }
                else { respond("?\r"); prompt() }
            }
            upper == "@1" -> { respond("SIMULATED OBD ADAPTER\r"); prompt() }

            upper == "ATE0" -> { echoOn = false; ok() }
            upper == "ATE1" -> { echoOn = true; ok() }
            upper == "ATH0" -> { headersOn = false; ok() }
            upper == "ATH1" -> { headersOn = true; ok() }
            upper == "ATR0" -> { responsesOn = false; ok() }
            upper == "ATR1" -> { responsesOn = true; ok() }

            upper.startsWith("ATSH") -> {
                txHeader = upper.removePrefix("ATSH").trim().toIntOrNull(16) ?: txHeader
                ok()
            }
            upper.startsWith("ATCRA") -> {
                val arg = upper.removePrefix("ATCRA").trim()
                rxFilter = if (arg.isEmpty()) null else arg.toIntOrNull(16)
                ok()
            }
            upper == "ATMA" -> {
                // Monitor-all: emit a plausible broadcast frame so bus probing
                // sees traffic, as it would on a live vehicle.
                respond("7E8034100BE\r")
                prompt()
            }

            // --- bus selection. Tracked so module bus affinity can be modelled.
            upper.startsWith("ATPB") -> {
                // AT PB <options> <divisor>; data rate = 500 kbps / divisor.
                val divisor = upper.removePrefix("ATPB").trim()
                    .split(Regex("\\s+")).getOrNull(1)?.toIntOrNull(16)
                stagedProtocolBBitrate =
                    if (divisor != null && divisor > 0) HIGH_SPEED_BPS / divisor
                    else HIGH_SPEED_BPS
                ok()
            }
            upper == "ATSPB" -> { selectedBitrate = stagedProtocolBBitrate; ok() }
            upper == "ATSP6" || upper == "ATSP7" -> { selectedBitrate = HIGH_SPEED_BPS; ok() }
            upper.startsWith("STPBR") -> {
                upper.removePrefix("STPBR").trim().toIntOrNull()
                    ?.let { selectedBitrate = it }
                ok()
            }
            upper.startsWith("STP ") -> { selectedBitrate = HIGH_SPEED_BPS; ok() }

            // Everything else in the AT/ST space is accepted without effect.
            upper.startsWith("AT") || upper.startsWith("ST") -> ok()

            Hex.isHex(command) -> handleFrame(Hex.decode(command))

            else -> { respond("?\r"); prompt() }
        }
    }

    /** Handles a transmitted CAN frame addressed to [txHeader]. */
    private suspend fun handleFrame(data: ByteArray) {
        delay(latencyMillis)

        val frame = IsoTpFrame.parse(data)
        if (frame == null) {
            respond("NO DATA\r"); prompt(); return
        }

        val request: ByteArray? = when (frame) {
            is IsoTpFrame.Single -> frame.payload

            is IsoTpFrame.First -> {
                // Accept the first frame and ask for the rest.
                pendingRequests[txHeader] = frame.payload.toMutableList()
                pendingExpectedLength = frame.totalLength
                emitFrame(responseIdFor(txHeader), byteArrayOf(0x30, 0x00, 0x00))
                prompt()
                return
            }

            is IsoTpFrame.Consecutive -> {
                val buffer = pendingRequests[txHeader]
                if (buffer == null) { prompt(); return }
                buffer += frame.payload.toList()
                if (buffer.size < pendingExpectedLength) { prompt(); return }
                pendingRequests.remove(txHeader)
                buffer.take(pendingExpectedLength).toByteArray()
            }

            is IsoTpFrame.FlowControl -> { prompt(); return }
        }

        if (request == null || request.isEmpty()) { prompt(); return }

        if (!responsesOn) { prompt(); return }

        val response = buildResponse(txHeader, request)
        if (response == null) {
            respond("NO DATA\r"); prompt(); return
        }

        IsoTpSegmenter.segment(response).forEach { emitFrame(responseIdFor(txHeader), it.encode()) }
        prompt()
    }

    private fun responseIdFor(requestId: Int): Int = when (requestId) {
        0x7DF -> 0x7E8
        else -> requestId + 8
    }

    private fun emitFrame(id: Int, payload: ByteArray) {
        val prefix = if (headersOn) Hex.encode(id, 3) else ""
        respond(prefix + Hex.encode(payload) + "\r")
    }

    // ------------------------------------------------------------------ UDS logic

    private fun buildResponse(requestId: Int, request: ByteArray): ByteArray? {
        // Functional address: only the powertrain answers mode 01/03/09.
        val module = if (requestId == 0x7DF) modules[0x7E0] else modules[requestId] ?: return null
        if (module == null) return null

        // Bus affinity: a module is electrically unreachable unless the adapter
        // is configured for the bus it sits on. Silence, exactly as on a truck.
        if (module.bitrateBps != selectedBitrate) return null

        val service = request.u8(0)

        return when (service) {
            // Mode 01 - live data
            0x01 -> {
                if (request.size < 2) return negative(service, 0x13)
                val pid = request.u8(1)
                val value = livePids[pid] ?: return negative(service, 0x31)
                byteArrayOf(0x41, pid.toByte()) + value
            }

            // Mode 03 - stored DTCs, two bytes each
            0x03 -> byteArrayOf(0x43) +
                module.dtcs.flatMap { listOf(it.first.toByte(), it.second.toByte()) }
                    .toByteArray()

            // Mode 09 - vehicle information
            0x09 -> {
                if (request.size < 2) return negative(service, 0x13)
                when (request.u8(1)) {
                    0x02 -> byteArrayOf(0x49, 0x02, 0x01) +
                        (module.dids[0xF190] ?: ascii("UNKNOWN", 17))
                    else -> negative(service, 0x31)
                }
            }

            // 0x10 DiagnosticSessionControl
            0x10 -> {
                if (request.size < 2) return negative(service, 0x13)
                byteArrayOf(0x50, request[1], 0x00, 0x32, 0x01, 0xF4.toByte())
            }

            // 0x11 ECUReset
            0x11 -> byteArrayOf(0x51, request.getOrElse(1) { 0x01 })

            // 0x14 ClearDiagnosticInformation
            0x14 -> { module.dtcs.clear(); byteArrayOf(0x54) }

            // 0x19 ReadDTCInformation
            0x19 -> {
                if (request.size < 2) return negative(service, 0x13)
                when (request.u8(1)) {
                    0x01 -> byteArrayOf(
                        0x59, 0x01, 0xFF.toByte(), 0x00,
                        (module.dtcs.size shr 8).toByte(), module.dtcs.size.toByte(),
                    )
                    0x02 -> {
                        val mask = request.getOrElse(2) { 0xFF.toByte() }.toInt() and 0xFF
                        val records = module.dtcs
                            .filter { it.third and mask != 0 }
                            .flatMap {
                                // Three code bytes (the third is the failure
                                // type) plus the status byte.
                                listOf(
                                    it.first.toByte(), it.second.toByte(), 0x00,
                                    it.third.toByte(),
                                )
                            }
                        byteArrayOf(0x59, 0x02, 0xFF.toByte()) + records.toByteArray()
                    }
                    0x04 -> byteArrayOf(0x59, 0x04) +
                        request.copyOfRange(2, minOf(5, request.size)) +
                        byteArrayOf(0x01, 0x02, 0x0C, 0x50, 0x05, 0x5A)
                    else -> negative(service, 0x12)
                }
            }

            // 0x22 ReadDataByIdentifier
            0x22 -> {
                if (request.size < 3) return negative(service, 0x13)
                val did = (request.u8(1) shl 8) or request.u8(2)
                val value = module.dids[did] ?: return negative(service, 0x31)
                byteArrayOf(0x62, request[1], request[2]) + value
            }

            // 0x27 SecurityAccess - always issues a seed, never accepts a key.
            // That is what a current Ford module does to a tool without Ford's
            // key material, and the app needs to handle it gracefully.
            0x27 -> {
                if (request.size < 2) return negative(service, 0x13)
                val sub = request.u8(1)
                if (sub % 2 == 1) {
                    byteArrayOf(0x67, sub.toByte(), 0x4A, 0x7C)
                } else {
                    negative(service, 0x35) // invalid key
                }
            }

            // 0x2E WriteDataByIdentifier
            0x2E -> {
                if (request.size < 4) return negative(service, 0x13)
                val did = (request.u8(1) shl 8) or request.u8(2)
                if (!module.dids.containsKey(did)) return negative(service, 0x31)
                // Gated on security access, which the simulator never grants.
                negative(service, 0x33)
            }

            // 0x31 RoutineControl
            0x31 -> {
                if (request.size < 4) return negative(service, 0x13)
                byteArrayOf(0x71, request[1], request[2], request[3])
            }

            // 0x3E TesterPresent
            0x3E -> byteArrayOf(0x7E, request.getOrElse(1) { 0x00 })

            // 0x85 ControlDTCSetting
            0x85 -> byteArrayOf(0xC5.toByte(), request.getOrElse(1) { 0x01 })

            else -> negative(service, 0x11)
        }
    }

    private fun negative(service: Int, nrc: Int) =
        byteArrayOf(0x7F, service.toByte(), nrc.toByte())

    companion object {
        private const val HIGH_SPEED_BPS = 500_000
        private const val MEDIUM_SPEED_BPS = 125_000

        /** Appends a two's complement checksum, matching one of the candidates. */
        private fun withTwosComplement(data: ByteArray): ByteArray {
            val sum = data.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
            return data + byteArrayOf((((0x100 - (sum and 0xFF)) and 0xFF)).toByte())
        }
    }
}
