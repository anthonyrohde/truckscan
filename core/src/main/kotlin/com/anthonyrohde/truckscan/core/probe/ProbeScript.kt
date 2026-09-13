package com.anthonyrohde.truckscan.core.probe

import com.anthonyrohde.truckscan.core.isotp.IsoTpFrame
import com.anthonyrohde.truckscan.core.util.Hex

/**
 * A script of adapter commands, checked before any of it reaches the bus.
 *
 * This exists so protocol questions can be answered by measurement rather than
 * inference. Several things in this codebase are currently reasoned from logs -
 * which MS-CAN initialisation byte a real adapter accepts, whether restoring
 * ATR1 after flow control really costs the consecutive frames, whether the line
 * rate can be raised past 115,200 - and a guess that looks right is the most
 * expensive kind.
 *
 * ## Why it refuses things
 *
 * A console that puts arbitrary frames on a diagnostic bus can reset modules,
 * erase fault history, unlock programming and write configuration. This one
 * cannot: every frame carrying a UDS or OBD service is checked against a list
 * of services that only ever read, and anything else is refused with the reason
 * shown rather than quietly dropped.
 *
 * The list is deliberately short. It is not "everything believed to be safe" -
 * it is the minimum that answers real questions, which is a much easier thing
 * to be sure about.
 */
object ProbeScript {

    /** Services that return information and change nothing. */
    private val READ_ONLY_SERVICES = mapOf(
        0x01 to "OBD-II current data",
        0x02 to "OBD-II freeze frame",
        0x03 to "OBD-II stored fault codes",
        0x07 to "OBD-II pending fault codes",
        0x09 to "OBD-II vehicle information",
        0x0A to "OBD-II permanent fault codes",
        0x19 to "UDS ReadDTCInformation",
        0x22 to "UDS ReadDataByIdentifier",
        0x3E to "UDS TesterPresent",
    )

    /** Refused, with the reason the user sees. */
    private val REFUSED_SERVICES = mapOf(
        0x10 to "changes the diagnostic session, which unlocks other services",
        0x11 to "resets the module",
        0x14 to "erases fault codes and freeze-frame data",
        0x27 to "security access - the gateway to programming",
        0x28 to "stops modules communicating",
        0x2E to "writes configuration to the module",
        0x2F to "drives the module's inputs and outputs",
        0x31 to "runs a routine inside the module",
        0x34 to "starts a firmware download",
        0x35 to "starts a firmware upload",
        0x36 to "transfers firmware data",
        0x37 to "ends a firmware transfer",
        0x85 to "turns fault code recording on or off",
    )

    sealed interface Line {
        val raw: String

        /** A comment or blank line. Kept so a transcript reads like the script. */
        data class Note(override val raw: String) : Line

        /** An AT or ST command: adapter configuration, nothing on the bus. */
        data class Adapter(override val raw: String, val command: String) : Line

        /** A CAN frame carrying a permitted service. */
        data class Frame(
            override val raw: String,
            val bytes: ByteArray,
            val service: Int?,
            val description: String,
        ) : Line

        /** Refused, and why. Never sent. */
        data class Refused(override val raw: String, val reason: String) : Line
    }

    data class Parsed(val lines: List<Line>) {
        val refusals: List<Line.Refused> get() = lines.filterIsInstance<Line.Refused>()
        val willSend: Int
            get() = lines.count { it is Line.Adapter || it is Line.Frame }
        val isSafe: Boolean get() = refusals.isEmpty()
    }

    fun parse(script: String): Parsed = Parsed(script.lines().map(::parseLine))

    private fun parseLine(raw: String): Line {
        val text = raw.substringBefore('#').trim()
        if (text.isEmpty()) return Line.Note(raw)

        val upper = text.uppercase().replace(" ", "")
        if (upper.startsWith("@")) return atSignCommand(raw, text, upper)
        if (upper.startsWith("AT") || upper.startsWith("ST")) return adapterCommand(raw, text, upper)

        val bytes = Hex.decodeOrNull(upper)
            ?: return Line.Refused(raw, "not an AT/ST command and not valid hex")
        if (bytes.isEmpty()) return Line.Refused(raw, "empty frame")
        if (bytes.size > 8) {
            return Line.Refused(raw, "a CAN frame carries at most 8 bytes, this has ${bytes.size}")
        }
        return classifyFrame(raw, bytes)
    }

    /**
     * Programmable parameters are the one adapter command with lasting effect:
     * `AT PP xx SV yy` survives a power cycle and can leave an adapter behaving
     * oddly long after the script that set it is forgotten.
     */
    private fun adapterCommand(raw: String, text: String, upper: String): Line =
        if (upper.startsWith("ATPP") && "SV" in upper) {
            Line.Refused(
                raw,
                "sets a programmable parameter, which persists across power cycles",
            )
        } else {
            Line.Adapter(raw, text)
        }

    /**
     * The `@` commands ask the adapter about itself. `@3` is the exception: it
     * writes a device identifier that persists, so it is refused for the same
     * reason as a programmable parameter.
     */
    private fun atSignCommand(raw: String, text: String, upper: String): Line = when {
        upper == "@1" || upper == "@2" -> Line.Adapter(raw, text)
        upper.startsWith("@3") -> Line.Refused(
            raw,
            "writes a device identifier into the adapter, which persists",
        )
        else -> Line.Refused(raw, "unrecognised '@' command")
    }

    private fun classifyFrame(raw: String, bytes: ByteArray): Line {
        val frame = IsoTpFrame.parse(bytes)

        // Flow control carries no service - it is the receiver granting the
        // sender permission to continue, and is exactly what has to be
        // exercised to answer the question about consecutive frames.
        if (frame is IsoTpFrame.FlowControl) {
            return Line.Frame(raw, bytes, null, "ISO-TP flow control")
        }

        val service = when (frame) {
            is IsoTpFrame.Single -> frame.payload.firstOrNull()?.toInt()?.and(0xFF)
            is IsoTpFrame.First -> frame.payload.firstOrNull()?.toInt()?.and(0xFF)
            // A consecutive frame is a continuation; its service was checked on
            // the first frame that opened the message.
            is IsoTpFrame.Consecutive -> return Line.Frame(
                raw, bytes, null, "ISO-TP consecutive frame",
            )
            else -> null
        } ?: return Line.Refused(raw, "could not tell which service this frame requests")

        READ_ONLY_SERVICES[service]?.let { name ->
            return Line.Frame(raw, bytes, service, name)
        }
        REFUSED_SERVICES[service]?.let { why ->
            return Line.Refused(raw, "service 0x${hex(service)} $why")
        }
        return Line.Refused(
            raw,
            "service 0x${hex(service)} is not on the read-only list, so it is not sent",
        )
    }

    private fun hex(value: Int) = value.toString(16).uppercase().padStart(2, '0')

    /** Human-readable summary of what a script will do, for showing before it runs. */
    fun describe(parsed: Parsed): String = buildString {
        appendLine("${parsed.willSend} command(s) will be sent.")
        if (parsed.refusals.isEmpty()) {
            appendLine("Nothing in this script can change the vehicle.")
        } else {
            appendLine()
            appendLine("${parsed.refusals.size} line(s) will NOT be sent:")
            parsed.refusals.forEach { appendLine("  ${it.raw.trim()} - ${it.reason}") }
        }
    }
}
