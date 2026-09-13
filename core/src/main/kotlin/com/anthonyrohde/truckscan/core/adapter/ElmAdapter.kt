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
     * False means "none was seen", which is NOT the same as "the bus is
     * asleep". Measured on a 2022 F-250: with the ignition on and a module
     * answering a request 150 ms earlier, ATMA, STM and STMA all reported
     * silence, with and without a receive filter. On a vehicle whose OBD port
     * sits behind a gateway there is simply nothing to overhear - diagnostic
     * traffic is routed on request and the internal buses are not mirrored.
     *
     * So a false here carries no information about the vehicle and must never
     * be turned into advice about the ignition. It told the user to check a key
     * that was already on, and sent the author of this code looking in the
     * wrong place for an afternoon.
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
        val desc = rawCommand("@1").let { if (it.isSuccess) it.text.trim() else "" }

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
     * A sequence that applies cleanly but sees no traffic is still accepted -
     * with the key off, a body bus is genuinely silent, and refusing to
     * proceed would make the app unusable in a garage. [BusSelection.trafficObserved]
     * carries that distinction up to the UI.
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
                if (r.status == AdapterResponse.Status.UNKNOWN_COMMAND) {
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
     * Listens for any valid frame using the adapter's monitor-all mode.
     *
     * `ATMA` streams until interrupted by any character, so we always send the
     * interrupt and drain, even on the failure path, or the next command would
     * read monitor output instead of its own reply.
     */
    private suspend fun probeBusTraffic(durationMillis: Long = 400): Boolean {
        writeLine("ATMA")
        var sawFrame = false
        val deadline = System.currentTimeMillis() + durationMillis
        try {
            while (System.currentTimeMillis() < deadline) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val chunk = transport.read(remaining.coerceAtMost(150))
                if (chunk.isEmpty()) continue
                buffer.append(String(chunk, Charsets.US_ASCII))
                if (drainLines().any { CanFrame.parse(it, extendedAddressing) != null }) {
                    sawFrame = true
                    break
                }
            }
        } finally {
            // Interrupt the monitor and resynchronise on the prompt.
            transport.write(byteArrayOf('\r'.code.toByte()))
            runCatching { readUntilPrompt(1_000) }
            buffer.clear()
        }
        return sawFrame
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
     * every transmitted frame. When streaming the consecutive frames of a
     * segmented request that costs 200 ms per frame for no benefit, so the
     * ISO-TP layer switches responses off for the middle of a burst and back on
     * for the frame whose reply it actually wants.
     */
    suspend fun setResponsesEnabled(enabled: Boolean) = mutex.withLock {
        rawCommand(if (enabled) "ATR1" else "ATR0")
        Unit
    }

    /**
     * Sends a raw frame without waiting for a reply, returning any frames that
     * happened to arrive in the same read. Requires `ATR0`.
     *
     * The read itself is not optional - the adapter emits a prompt even with
     * ATR0, and leaving it buffered would corrupt the next reply. What is not
     * optional either is keeping what comes with it: after a flow control
     * frame the module starts sending immediately, and with a separation time
     * of zero its first consecutive frames land inside this window. Discarding
     * them, as this used to, loses the front of the message and the rest never
     * adds up.
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
