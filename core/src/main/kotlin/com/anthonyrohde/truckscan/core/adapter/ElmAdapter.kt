package com.anthonyrohde.truckscan.core.adapter

import com.anthonyrohde.truckscan.core.transport.ObdTransport
import com.anthonyrohde.truckscan.core.transport.TransportException
import com.anthonyrohde.truckscan.core.util.Hex
import com.anthonyrohde.truckscan.core.util.toHex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Outcome of an adapter command, after status words have been classified. */
data class AdapterResponse(
    val command: String,
    val lines: List<String>,
    val status: Status,
) {
    enum class Status {
        OK,
        /** Adapter did not recognise the command - `?` reply. */
        UNKNOWN_COMMAND,
        /** Request went out, nothing answered. Normal when probing addresses. */
        NO_DATA,
        /** Adapter could not reach the bus at the configured bitrate. */
        UNABLE_TO_CONNECT,
        /** Bus-level fault: wrong bitrate, wrong pins, or nothing driving the bus. */
        BUS_ERROR,
        /** Adapter's receive buffer overflowed - back off the request rate. */
        BUFFER_FULL,
        TIMEOUT,
    }

    val isSuccess: Boolean get() = status == Status.OK
    val text: String get() = lines.joinToString(" ")
}

class AdapterException(message: String, cause: Throwable? = null) :
    TransportException(message, cause)

/** Result of trying to bring up a bus, including which sequence actually worked. */
data class BusSelection(
    val bus: CanBus,
    val sequenceLabel: String,
    /** True when we saw real frames after configuring. False means configured
     *  but silent - which is expected if the ignition is off. */
    /**
     * True when free-running traffic was actually seen on the bus.
     *
     * True means a module answered a request on this bus just now. False means
     * none did, which is worth reporting and still is not a diagnosis - a bus
     * with nothing on it and a bus whose modules are asleep look identical.
     *
     * This used to be the result of listening with `ATMA`, and in that form it
     * carried no information at all. Measured on a 2022 F-250: with the
     * ignition on and a module answering a request 150 ms earlier, `ATMA`,
     * `STM` and `STMA` all reported silence, with and without a receive filter.
     * The OBD port sits behind a gateway that routes diagnostic traffic on
     * request and does not mirror the internal buses, so there is nothing to
     * overhear. A false from that told the user to check a key that was already
     * on, and sent the author of this code looking in the wrong place for an
     * afternoon. It now comes from asking rather than listening.
     */
    val trafficObserved: Boolean,
)

/**
 * The ELM327 / STN command layer.
 *
 * Owns the request-response discipline of the adapter's serial protocol: every
 * command is terminated with CR and the adapter replies with one or more CR
 * separated lines followed by a `>` prompt. All access is serialised through a
 * mutex because there is exactly one pipe and interleaving two requests
 * produces responses that cannot be attributed.
 */
class ElmAdapter(
    private val transport: ObdTransport,
    private val logger: ((String) -> Unit)? = null,
) {
    private val mutex = Mutex()
    private val buffer = StringBuilder()

    private var identity: AdapterIdentity = AdapterIdentity("")
    private var currentBus: CanBus? = null

    /**
     * Initialisation sequences already proven to work for a bus.
     *
     * Bringing a bus up the first time means trying candidate sequences and
     * listening for traffic, which costs a few hundred milliseconds. Returning
     * to a bus we have already brought up should not pay that again, and with
     * per-module bus routing we return to buses constantly.
     */
    private val provenSequences = mutableMapOf<CanBus, BusInit.Sequence>()
    private var extendedAddressing: Boolean = false
    private var txHeader: Int? = null
    private var rxFilter: Int? = null

    val adapterIdentity: AdapterIdentity get() = identity
    val selectedBus: CanBus? get() = currentBus

    companion object {
        private const val PROMPT = '>'
        const val DEFAULT_TIMEOUT_MS = 2_000L
        private const val RESET_TIMEOUT_MS = 6_000L

        /** Status words the adapter emits that are not frame data. */
        private val STATUS_WORDS = mapOf(
            "NO DATA" to AdapterResponse.Status.NO_DATA,
            "UNABLE TO CONNECT" to AdapterResponse.Status.UNABLE_TO_CONNECT,
            "CAN ERROR" to AdapterResponse.Status.BUS_ERROR,
            "BUS ERROR" to AdapterResponse.Status.BUS_ERROR,
            "BUS BUSY" to AdapterResponse.Status.BUS_ERROR,
            "FB ERROR" to AdapterResponse.Status.BUS_ERROR,
            "DATA ERROR" to AdapterResponse.Status.BUS_ERROR,
            "BUFFER FULL" to AdapterResponse.Status.BUFFER_FULL,
            "STOPPED" to AdapterResponse.Status.NO_DATA,
        )

        /** Lines that carry no information and are dropped before parsing. */
        private val NOISE = setOf("SEARCHING...", "SEARCHING", "OK", "")
    }

    // ---------------------------------------------------------------- connect

    /**
     * Opens the transport and brings the adapter to a known state.
     *
     * `ATZ` is a full reset and takes up to a second on real hardware, hence
     * the longer timeout. We deliberately do not select a protocol here -
     * that is [selectBus]'s job, because the right protocol depends on which
     * bus the caller wants.
     */
    suspend fun connect(): AdapterIdentity = mutex.withLock {
        if (!transport.isOpen) transport.open()
        buffer.clear()

        // A clean CR first: if the adapter was mid-command from a previous
        // session it will flush and give us a prompt to synchronise on.
        runCatching { rawCommand("", RESET_TIMEOUT_MS) }

        val reset = rawCommand("ATZ", RESET_TIMEOUT_MS)
        val elmId = reset.lines.lastOrNull { it.isNotBlank() } ?: ""

        for (cmd in BusInit.COMMON_SETUP) {
            val r = rawCommand(cmd)
            if (r.status == AdapterResponse.Status.UNKNOWN_COMMAND) {
                log("Adapter rejected '$cmd' - continuing, some clones omit it")
            }
        }
        // 200 ms per-request ceiling. Long enough for a slow module, short
        // enough that probing 40 addresses does not take a minute.
        rawCommand("ATST 32")

        val ati = rawCommand("ATI").lines.lastOrNull { it.isNotBlank() } ?: elmId
        val sti = rawCommand("STI").let { if (it.isSuccess) it.text.trim() else "" }
        // STDI, not @1. An OBDLink EX r2.2.1 answers "?" to @1 and names itself
        // to STDI; the app showed "Unknown adapter" for want of asking the
        // command the hardware implements.
        val desc = rawCommand("STDI").let { if (it.isSuccess) it.text.trim() else "" }
            .ifBlank { rawCommand("@1").let { if (it.isSuccess) it.text.trim() else "" } }

        identity = AdapterIdentity(
            elmIdentifier = ati,
            stnIdentifier = sti,
            deviceDescription = desc,
        )
        // The transport names the line rate it settled on. Worth a line in the
        // log: a link established at the wrong rate looks identical to a dead
        // vehicle from every screen in the app, and this is what tells them
        // apart at a glance.
        log("Transport: ${transport.description}")
        log("Connected to ${identity.model} (multi-bus: ${identity.supportsMultiBus})")
        identity
    }

    suspend fun disconnect() = mutex.withLock {
        provenSequences.clear()
        currentBus = null
        txHeader = null
        rxFilter = null
        transport.close()
    }

    // ------------------------------------------------------------ bus control

    /**
     * Configures the adapter for [bus], trying each candidate sequence until
     * one produces traffic.
     *
     * A sequence that applies cleanly but gets no answer is still accepted -
     * with the key off every bus is genuinely silent, and refusing to proceed
     * would make the app unusable in a garage. [BusSelection.trafficObserved]
     * carries that distinction up to the UI, and now means something: it is the
     * result of asking a module a question, not of listening to a monitor that
     * reports silence on this vehicle whatever is happening.
     */
    suspend fun selectBus(bus: CanBus): BusSelection = mutex.withLock {
        if (bus != CanBus.HS_CAN1 && !identity.supportsMultiBus) {
            throw AdapterException(identity.multiBusLimitationMessage(bus))
        }

        // Fast path: we have already established what works for this bus.
        provenSequences[bus]?.let { proven ->
            proven.commands.forEach { rawCommand(it) }
            currentBus = bus
            extendedAddressing = bus.extendedAddressing
            txHeader = null
            rxFilter = null
            return@withLock BusSelection(bus, proven.label, trafficObserved = true)
        }

        val candidates = BusInit.candidatesFor(bus, identity)
        if (candidates.isEmpty()) {
            throw AdapterException(
                "${'$'}{bus.displayName} is on pins ${'$'}{bus.canHighPin}/${'$'}{bus.canLowPin}, and no " +
                    "transceiver in ${'$'}{identity.model} is wired to them. Nothing plugged " +
                    "into the OBD connector can reach it - the adapter's protocol table " +
                    "has entries for pins 6/14, 3/11 and 1, and nothing else.",
            )
        }
        var lastApplied: BusInit.Sequence? = null

        for (sequence in candidates) {
            var applied = true
            for (cmd in sequence.commands) {
                val r = rawCommand(cmd)
                // STPBRR is a "confirm new bitrate" command that some firmware
                // omits; never fail a sequence just because it is missing.
                if (r.status == AdapterResponse.Status.UNKNOWN_COMMAND &&
                    !cmd.startsWith("STPBRR")
                ) {
                    applied = false
                    break
                }
            }
            if (!applied) {
                log("Bus init '${sequence.label}' not supported by adapter")
                continue
            }
            lastApplied = sequence

            currentBus = bus
            extendedAddressing = bus.extendedAddressing
            txHeader = null
            rxFilter = null

            if (probeBusTraffic()) {
                log("Bus ${bus.displayName} up via '${sequence.label}' - traffic seen")
                provenSequences[bus] = sequence
                return@withLock BusSelection(bus, sequence.label, trafficObserved = true)
            }
            log("Bus init '${sequence.label}' applied but bus is silent")
        }

        if (lastApplied == null) {
            throw AdapterException(
                "Could not configure ${bus.displayName}: adapter rejected every " +
                    "known initialisation sequence.",
            )
        }

        // The adapter is left configured by whichever sequence was applied
        // last, not the first one that applied cleanly. Reporting the first
        // would name a configuration the adapter is not in, and a log that
        // misnames the state it is describing costs more time than no log.
        currentBus = bus
        extendedAddressing = bus.extendedAddressing
        txHeader = null
        rxFilter = null
        BusSelection(bus, lastApplied.label, trafficObserved = false)
    }

    /**
     * Asks whether anything on this bus will answer.
     *
     * This used to listen with `ATMA` and treat any frame as proof of life.
     * That was measured on a 2022 F-250 to be worthless and possibly harmful:
     * `ATMA`, `STM` and `STMA` all returned `STOPPED` with the PCM answering
     * requests 150 ms earlier, so every bus looked silent - and leaving a
     * monitor running, then interrupting and resynchronising it, sits directly
     * in front of the first real request on a freshly selected bus.
     *
     * Sending one request instead is cheap and unambiguous. On the powertrain
     * bus the broadcast address brought back two modules in 60 ms.
     */
    private suspend fun probeBusTraffic(durationMillis: Long = 400): Boolean {
        val (txId, payload) = currentBus?.livenessProbe ?: return false

        val header = txId.toString(16).uppercase().padStart(3, '0')
        if (rawCommand("ATSH $header").status == AdapterResponse.Status.UNKNOWN_COMMAND) {
            return false
        }
        // No receive filter: on this truck more than one module answers the
        // broadcast, and filtering to one of them would throw away the
        // evidence that the others are there.
        rawCommand("ATCRA")

        val frame = byteArrayOf(payload.size.toByte()) + payload +
            ByteArray(7 - payload.size) { 0x00 }
        val reply = rawCommand(frame.toHex(), durationMillis)
        return reply.lines.any { CanFrame.parse(it, extendedAddressing) != null }
    }

    // -------------------------------------------------------------- addressing

    /** Sets the CAN ID used for transmitted frames (`ATSH`). */
    suspend fun setTxHeader(id: Int) = mutex.withLock {
        if (txHeader == id) return@withLock
        val width = if (extendedAddressing) 8 else 3
        val r = rawCommand("ATSH ${Hex.encode(id, width)}")
        if (!r.isSuccess && r.status == AdapterResponse.Status.UNKNOWN_COMMAND) {
            throw AdapterException("Adapter rejected transmit header ${Hex.encode(id, width)}")
        }
        txHeader = id
    }

    /**
     * Restricts received frames to [id] (`ATCRA`).
     *
     * Filtering in the adapter rather than in software matters a lot on a busy
     * 500 kbps bus: without it the adapter's buffer fills with unrelated
     * traffic and multi-frame reassembly starts dropping consecutive frames.
     */
    suspend fun setRxFilter(id: Int) = mutex.withLock {
        if (rxFilter == id) return@withLock
        val width = if (extendedAddressing) 8 else 3
        rawCommand("ATCRA ${Hex.encode(id, width)}")
        rxFilter = id
    }

    suspend fun clearRxFilter() = mutex.withLock {
        rawCommand("ATCRA")
        rxFilter = null
    }

    // ------------------------------------------------------------ frame level

    /**
     * Sends one raw CAN frame and collects everything that answers.
     *
     * With `ATCAF0` in force the adapter transmits our bytes verbatim, so the
     * caller is responsible for the ISO-TP PCI byte. That is by design - see
     * [com.anthonyrohde.truckscan.core.isotp.IsoTpChannel].
     */
    suspend fun sendFrameAndCollect(
        data: ByteArray,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ): Pair<List<CanFrame>, AdapterResponse.Status> = mutex.withLock {
        require(data.size <= 8) { "A CAN 2.0 frame carries at most 8 bytes, got ${data.size}" }
        val response = rawCommand(data.toHex(), timeoutMillis)
        val frames = response.lines.mapNotNull { CanFrame.parse(it, extendedAddressing) }
        frames to response.status
    }

    /**
     * Reads any further frames without transmitting.
     *
     * Used to collect the consecutive frames of a segmented response after the
     * flow-control frame has gone out.
     */
    suspend fun collectFrames(timeoutMillis: Long): List<CanFrame> = mutex.withLock {
        val response = readUntilPrompt(timeoutMillis)
        response.lines.mapNotNull { CanFrame.parse(it, extendedAddressing) }
    }

    /**
     * Turns the adapter's wait-for-response behaviour on or off (`ATR1`/`ATR0`).
     *
     * With responses on, the adapter sits waiting for the ATST timeout after
     * every transmitted frame. When streaming the consecutive frames of a long
     * *request* that costs time for no benefit, so the ISO-TP send path
     * switches responses off for the middle of a burst and back on for the
     * frame whose reply it actually wants.
     *
     * Do not reach for this on the receive path. Measured on an OBDLink EX
     * against a running truck: a flow control sent plainly returned both
     * consecutive frames of a VIN in 58 ms, and the same flow control behind
     * `ATR0` returned a bare prompt and nothing else. There is no timeout there
     * worth avoiding, and suppressing responses loses the burst rather than
     * protecting it.
     */
    suspend fun setResponsesEnabled(enabled: Boolean) = mutex.withLock {
        rawCommand(if (enabled) "ATR1" else "ATR0")
        Unit
    }

    /**
     * Sends a raw frame and returns whatever arrives in the same read.
     *
     * The name is now half wrong and kept for the callers: it does not wait for
     * a *reply to this frame*, but it does read, and what it reads matters. A
     * flow control frame is the case that proves it - the module starts sending
     * the moment it is granted, and with a separation time of zero the whole
     * remainder of the message lands inside this window. On a running truck a
     * 17-byte VIN came back complete in 58 ms this way. Discarding the read, as
     * this used to, loses the message and the rest never adds up.
     */
    suspend fun sendFrameNoWait(data: ByteArray): List<CanFrame> = mutex.withLock {
        require(data.size <= 8) { "A CAN 2.0 frame carries at most 8 bytes, got ${data.size}" }
        val response = rawCommand(data.toHex(), 400)
        response.lines.mapNotNull { CanFrame.parse(it, extendedAddressing) }
    }

    /** Escape hatch for raw AT/ST commands, e.g. a terminal screen in the UI. */
    suspend fun command(cmd: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MS): AdapterResponse =
        mutex.withLock { rawCommand(cmd, timeoutMillis) }

    // ------------------------------------------------------------- plumbing

    private suspend fun rawCommand(
        cmd: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ): AdapterResponse {
        writeLine(cmd)
        return readUntilPrompt(timeoutMillis).copy(command = cmd)
    }

    private suspend fun writeLine(cmd: String) {
        if (cmd.isNotEmpty()) log(">> $cmd")
        transport.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
    }

    /**
     * Accumulates bytes until the `>` prompt arrives or the deadline passes.
     *
     * The adapter is free to split a response across reads, so we cannot treat
     * a single empty read as end-of-response; only the prompt or the timeout
     * ends it.
     */
    private suspend fun readUntilPrompt(timeoutMillis: Long): AdapterResponse {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var sawPrompt = false

        while (System.currentTimeMillis() < deadline) {
            if (buffer.contains(PROMPT)) { sawPrompt = true; break }
            val remaining = deadline - System.currentTimeMillis()
            val chunk = transport.read(remaining.coerceAtLeast(1))
            if (chunk.isNotEmpty()) buffer.append(String(chunk, Charsets.US_ASCII))
        }
        if (!sawPrompt && buffer.contains(PROMPT)) sawPrompt = true

        val lines = drainLines()
        if (lines.isNotEmpty()) log("<< ${lines.joinToString(" | ")}")

        val status = when {
            lines.any { it == "?" } -> AdapterResponse.Status.UNKNOWN_COMMAND
            else -> lines.firstNotNullOfOrNull { line ->
                STATUS_WORDS.entries.firstOrNull { line.contains(it.key) }?.value
            } ?: if (sawPrompt) AdapterResponse.Status.OK else AdapterResponse.Status.TIMEOUT
        }

        return AdapterResponse(
            command = "",
            lines = lines.filterNot { it in NOISE || it == "?" },
            status = status,
        )
    }

    /**
     * Splits complete lines out of the buffer, keeping any partial tail.
     *
     * Retaining the tail is what makes a response that straddles two transport
     * reads reassemble correctly instead of being parsed as two corrupt frames.
     */
    private fun drainLines(): List<String> {
        val text = buffer.toString()
        val promptIndex = text.indexOf(PROMPT)
        val consumable = if (promptIndex >= 0) text.substring(0, promptIndex) else text

        val parts = consumable.split('\r', '\n')
        val complete: List<String>
        if (promptIndex >= 0) {
            complete = parts
            buffer.setLength(0)
            buffer.append(text.substring(promptIndex + 1))
        } else {
            // Last element may be an incomplete line; hold it back.
            complete = parts.dropLast(1)
            buffer.setLength(0)
            buffer.append(parts.last())
        }
        return complete.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun log(message: String) = logger?.invoke(message)
}
