package com.anthonyrohde.truckscan.core.transport

import com.anthonyrohde.truckscan.core.isotp.IsoTpFrame
import com.anthonyrohde.truckscan.core.isotp.IsoTpSegmenter
import com.anthonyrohde.truckscan.core.util.Hex
import com.anthonyrohde.truckscan.core.util.u8
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

    /**
     * Which physical transceiver the adapter is on, and at what rate.
     *
     * Both are needed, and keeping only the rate was a real bug. An adapter
     * has one transceiver per pair of connector pins; a command can retune a
     * transceiver but cannot move it to other pins. `STP 33` + `STPBR 125000`
     * therefore gives 125 kbps on the *high speed* pins, which reaches nothing
     * - and a simulator that tracked only the rate would have answered as
     * though it had reached MS-CAN. It did, for months.
     */
    private enum class Transceiver { HIGH_SPEED, MEDIUM_SPEED, SINGLE_WIRE }

    private var selectedTransceiver: Transceiver = Transceiver.HIGH_SPEED
    private var selectedBitrate: Int = HIGH_SPEED_BPS

    /** Rate staged by `ATPB`, applied when `ATSPB` selects protocol B. */
    private var stagedProtocolBBitrate: Int = HIGH_SPEED_BPS

    /**
     * The adapter's protocol table, read off real hardware with `STP xx` then
     * `STPRS`. Anything not here answers `?`, as the chip does.
     */
    private fun protocolTable(number: Int): Pair<Transceiver, Int>? = when (number) {
        0x31, 0x32, 0x33, 0x34 -> Transceiver.HIGH_SPEED to HIGH_SPEED_BPS
        0x35, 0x36 -> Transceiver.HIGH_SPEED to 250_000
        0x51, 0x52, 0x53, 0x54 -> Transceiver.MEDIUM_SPEED to MEDIUM_SPEED_BPS
        0x61, 0x62, 0x63, 0x64 -> Transceiver.SINGLE_WIRE to 33_333
        else -> null
    }

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

    /**
     * Live parameter values the simulator reports for mode 01.
     *
     * A warm 6.7L at idle: 788 rpm, 85 degrees coolant, 300 bar rail, exhaust
     * temperatures in the low 300s. Chosen so every gauge reads in its normal
     * band, which makes the warning panel's behaviour obvious when a value is
     * pushed out of range.
     *
     * The support masks are computed to match exactly what is served here, so
     * the app's "which parameters does this vehicle have" query behaves as it
     * would on a real truck rather than advertising everything.
     */
    private val livePids: Map<Int, ByteArray> = mapOf(
        // Support masks. Bit 0 of the last byte of each says a further bank follows.
        0x00 to byteArrayOf(0x1E, 0x7F, 0x80.toByte(), 0x03),
        0x20 to byteArrayOf(0xA0.toByte(), 0x1B, 0xA0.toByte(), 0x01),
        0x40 to byteArrayOf(0x6C, 0xDC.toByte(), 0x00, 0x1D),
        0x60 to byteArrayOf(0xE0.toByte(), 0x00, 0x23, 0x10),

        0x04 to byteArrayOf(0x4C),                       // load 29.8%
        0x05 to byteArrayOf(0x7D),                       // coolant 85 C
        0x06 to byteArrayOf(0x80.toByte()),              // short trim 0%
        0x07 to byteArrayOf(0x84.toByte()),              // long trim +3.1%
        0x0A to byteArrayOf(0x64),                       // fuel pressure 300 kPa
        0x0B to byteArrayOf(0x68),                       // MAP 104 kPa
        0x0C to byteArrayOf(0x0C, 0x50),                 // 788 rpm
        0x0D to byteArrayOf(0x00),                       // stationary
        0x0E to byteArrayOf(0x80.toByte()),              // timing 0 deg
        0x0F to byteArrayOf(0x46),                       // intake air 30 C
        0x10 to byteArrayOf(0x02, 0x1C),                 // MAF 5.40 g/s
        0x11 to byteArrayOf(0x1A),                       // throttle 10.2%
        0x1F to byteArrayOf(0x03, 0x20),                 // 800 s since start
        0x21 to byteArrayOf(0x00, 0x00),                 // no distance with MIL
        0x23 to byteArrayOf(0x0B, 0xB8.toByte()),        // rail 30000 kPa
        0x2C to byteArrayOf(0x33),                       // commanded EGR 20%
        0x2D to byteArrayOf(0x80.toByte()),              // EGR error 0%
        0x2F to byteArrayOf(0x9A.toByte()),              // fuel 60%
        0x30 to byteArrayOf(0x05),                       // 5 warm-ups
        0x31 to byteArrayOf(0x04, 0xD2.toByte()),        // 1234 km since clear
        0x33 to byteArrayOf(0x65),                       // baro 101 kPa
        0x42 to byteArrayOf(0x36, 0xB0.toByte()),        // 14.00 V
        0x43 to byteArrayOf(0x00, 0x50),                 // absolute load 31.4%
        0x45 to byteArrayOf(0x0A),                       // relative throttle 3.9%
        0x46 to byteArrayOf(0x3C),                       // ambient 20 C
        0x49 to byteArrayOf(0x1A),                       // pedal D 10.2%
        0x4A to byteArrayOf(0x18),                       // pedal E 9.4%
        0x4C to byteArrayOf(0x20),                       // commanded throttle 12.5%
        0x4D to byteArrayOf(0x00, 0x00),                 // no time with MIL
        0x4E to byteArrayOf(0x00, 0xF0.toByte()),        // 240 min since clear
        0x5C to byteArrayOf(0x73),                       // oil 75 C
        0x5D to byteArrayOf(0x6B, 0x80.toByte()),        // injection timing 5 deg
        0x5E to byteArrayOf(0x00, 0x78),                 // fuel rate 6.0 L/h
        0x61 to byteArrayOf(0x8C.toByte()),              // demanded torque 15%
        0x62 to byteArrayOf(0x8A.toByte()),              // actual torque 13%
        0x63 to byteArrayOf(0x06, 0x40),                 // reference torque 1600 Nm
        0x73 to byteArrayOf(0x01, 0x34, 0x80.toByte()),  // exhaust pressure 105 kPa
        0x77 to byteArrayOf(0x01, 0x4B, 0x00),           // charge air 35 C
        // Four exhaust gas temperatures behind one bitmask-packed PID.
        0x78 to byteArrayOf(
            0x0F,                                        // all four sensors present
            0x0E, 0x10,                                  // EGT1 320 C
            0x0D, 0x16,                                  // EGT2 295 C
            0x0C, 0x80.toByte(),                         // EGT3 280 C
            0x0B, 0xEA.toByte(),                         // EGT4 265 C
        ),
        0x7C to byteArrayOf(
            0x01, 0x0A, 0xF0.toByte(), 0x0A, 0xF0.toByte(),
            0x0A, 0xF0.toByte(), 0x0A, 0xF0.toByte(),    // DPF 240 C
        ),
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
            // Protocol B is an ELM327 protocol, and the ELM327 command set has
            // no second transceiver: this retunes the high speed pins.
            upper == "ATSPB" -> {
                selectedTransceiver = Transceiver.HIGH_SPEED
                selectedBitrate = stagedProtocolBBitrate
                ok()
            }
            upper == "ATSP6" || upper == "ATSP7" -> {
                selectedTransceiver = Transceiver.HIGH_SPEED
                selectedBitrate = HIGH_SPEED_BPS
                ok()
            }
            // STPBR retunes whichever transceiver the protocol already chose.
            upper.startsWith("STPBR") -> {
                upper.removePrefix("STPBR").trim().toIntOrNull()
                    ?.let { selectedBitrate = it }
                ok()
            }
            upper.startsWith("STP ") -> {
                val number = upper.removePrefix("STP ").trim().toIntOrNull(16)
                val entry = number?.let { protocolTable(it) }
                if (entry == null) {
                    respond("?\r")
                    prompt()
                } else {
                    selectedTransceiver = entry.first
                    selectedBitrate = entry.second
                    ok()
                }
            }

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
        // is on its pins *and* at its rate. Silence, exactly as on a truck.
        val onItsPins = selectedTransceiver == when (module.bitrateBps) {
            MEDIUM_SPEED_BPS -> Transceiver.MEDIUM_SPEED
            else -> Transceiver.HIGH_SPEED
        }
        if (!onItsPins || module.bitrateBps != selectedBitrate) return null

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
