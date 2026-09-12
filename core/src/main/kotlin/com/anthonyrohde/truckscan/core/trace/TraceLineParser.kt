package com.anthonyrohde.truckscan.core.trace

import com.anthonyrohde.truckscan.core.util.Hex

/**
 * Turns lines of a CAN bus log into frames.
 *
 * ## Why this is written to be format-tolerant
 *
 * Diagnostic tools do not agree on a log format, and the exact shape FORScan
 * emits is not something this project can assert. Rather than hard-code one
 * layout and fail silently on anything else, the parser recognises the general
 * *shape* of a logged CAN frame - an optional timestamp, some optional
 * direction or bus markers, a CAN identifier, and a run of hex payload bytes -
 * and ignores everything it cannot make sense of.
 *
 * Concretely it handles all of these, and others like them:
 *
 *     726#0322DE0000000000
 *     (1718088210.123) can0 726#0322DE00
 *     10:23:45.123 TX 0726 03 22 DE 00 00 00 00 00
 *     [10:23:45.123] 726: 0322DE0000000000
 *     2024-06-11 10:23:45.123  ->  0726  [8]  03 22 DE 00 00 00 00 00
 *
 * Direction markers are parsed when present but never relied upon: the analyzer
 * decides what is a request and what is a response from the UDS service byte,
 * which is unambiguous and works on logs that record no direction at all.
 */
object TraceLineParser {

    /** Leading timestamp forms, stripped before tokenising. */
    private val TIMESTAMP_PATTERNS = listOf(
        // (1718088210.123456) - candump absolute
        Regex("""^\(\s*(\d+\.\d+)\s*\)\s*"""),
        // 2024-06-11 10:23:45.123 or 2024-06-11T10:23:45,123
        Regex("""^\[?\s*\d{4}-\d{2}-\d{2}[ T](\d{1,2}:\d{2}:\d{2}(?:[.,]\d{1,9})?)\s*]?\s*"""),
        // 10:23:45.123
        Regex("""^\[?\s*(\d{1,2}:\d{2}:\d{2}(?:[.,]\d{1,9})?)\s*]?\s*"""),
        // 12345.678 ms style leading float
        Regex("""^\[?\s*(\d+[.,]\d+)\s*]?\s+"""),
    )

    /** Tokens that appear around frames and carry no payload. */
    private val NOISE_TOKENS = setOf(
        "TX", "RX", "T", "R", "->", "<-", "=>", "<=", "SEND", "SENT", "RECV",
        "RECEIVE", "RECEIVED", "REQ", "REQUEST", "RESP", "RESPONSE", "OUT", "IN",
    )

    private val BUS_NAME = Regex("""^(v?can\d+|hs-?can\d?|ms-?can|bus\d*)$""", RegexOption.IGNORE_CASE)

    /** Direction, when the line states one. Advisory only. */
    enum class Direction { TRANSMIT, RECEIVE, UNKNOWN }

    /**
     * Parses one line, or returns null if it is not a frame.
     *
     * Returning null rather than throwing is deliberate: real logs are full of
     * headers, prose and blank lines, and a parser that objects to them would
     * be useless on an actual capture.
     */
    fun parseLine(line: String, lineNumber: Int = 0): TraceFrame? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("#") || trimmed.startsWith("//") || trimmed.startsWith(";")) {
            return null
        }

        var body = trimmed
        var timestamp: Double? = null

        for (pattern in TIMESTAMP_PATTERNS) {
            val match = pattern.find(body) ?: continue
            timestamp = parseTimestamp(match.groupValues.getOrNull(1))
            body = body.removeRange(match.range)
            break
        }

        // candump style: identifier and payload joined by '#'.
        if (body.contains('#')) {
            parseHashForm(body, lineNumber, timestamp, trimmed)?.let { return it }
        }

        return parseTokenForm(body, lineNumber, timestamp, trimmed)
    }

    /** Parses a whole log. */
    fun parse(text: String): Pair<List<TraceFrame>, TraceParseReport> {
        val frames = mutableListOf<TraceFrame>()
        val skipped = mutableListOf<String>()
        var total = 0

        text.lineSequence().forEachIndexed { index, line ->
            total++
            val frame = parseLine(line, index + 1)
            if (frame != null) {
                frames += frame
            } else if (line.isNotBlank() && skipped.size < SAMPLE_SKIPPED_LIMIT) {
                skipped += line.trim()
            }
        }

        // Normalise timestamps to seconds from the start of the capture, which
        // is all the analyzer needs and avoids caring whether the log used
        // wall-clock or monotonic time.
        val base = frames.firstNotNullOfOrNull { it.relativeSeconds }
        val normalised = if (base != null) {
            frames.map { frame ->
                frame.relativeSeconds?.let { frame.copy(relativeSeconds = it - base) } ?: frame
            }
        } else {
            frames
        }

        return normalised to TraceParseReport(
            totalLines = total,
            framesParsed = normalised.size,
            linesSkipped = total - normalised.size,
            sampleSkippedLines = skipped,
        )
    }

    // ------------------------------------------------------------------ internals

    private fun parseHashForm(
        body: String,
        lineNumber: Int,
        timestamp: Double?,
        raw: String,
    ): TraceFrame? {
        val hashIndex = body.indexOf('#')
        val left = body.substring(0, hashIndex).trim().split(Regex("\\s+")).lastOrNull() ?: return null
        val right = body.substring(hashIndex + 1).trim().split(Regex("\\s+")).firstOrNull() ?: return null

        val id = canIdOrNull(left) ?: return null
        val payload = payloadOrNull(listOf(right)) ?: return null
        return TraceFrame(lineNumber, id, payload, timestamp, raw)
    }

    private fun parseTokenForm(
        body: String,
        lineNumber: Int,
        timestamp: Double?,
        raw: String,
    ): TraceFrame? {
        val tokens = body
            .split(Regex("[\\s,|]+"))
            .map { it.trim().trim('[', ']', '(', ')', ':', '<', '>') }
            .filter { it.isNotEmpty() }
            .filterNot { it.uppercase() in NOISE_TOKENS }
            .filterNot { BUS_NAME.matches(it) }

        if (tokens.isEmpty()) return null

        // The identifier is the first token that looks like a CAN ID: 3 or 4
        // hex digits for 11-bit, 8 for 29-bit. Payload bytes are two digits, so
        // they cannot be confused with one.
        val idIndex = tokens.indexOfFirst { canIdOrNull(it) != null }
        if (idIndex < 0) return null

        val id = canIdOrNull(tokens[idIndex]) ?: return null
        val payload = payloadOrNull(tokens.drop(idIndex + 1)) ?: return null
        if (payload.isEmpty()) return null

        return TraceFrame(lineNumber, id, payload, timestamp, raw)
    }

    /** A token is a CAN ID if it is 3, 4 or 8 hex digits. */
    private fun canIdOrNull(token: String): Int? {
        val clean = token.trim().removePrefix("0x").removePrefix("0X")
        if (clean.length !in setOf(3, 4, 8)) return null
        if (!Hex.isHex(clean)) return null
        val value = clean.toLongOrNull(16) ?: return null
        // 29-bit is the widest a CAN identifier goes.
        if (value > 0x1FFF_FFFF) return null
        return value.toInt()
    }

    /**
     * Concatenates the remaining hex tokens into a payload.
     *
     * Odd-length tokens are skipped rather than accepted: they are DLC markers
     * and column separators, and splicing one in would shift every subsequent
     * byte by a nibble.
     */
    private fun payloadOrNull(tokens: List<String>): ByteArray? {
        val hex = tokens
            .map { it.removePrefix("0x").removePrefix("0X") }
            .takeWhile { Hex.isHex(it) || it.length % 2 != 0 }
            .filter { Hex.isHex(it) && it.length % 2 == 0 }
            .joinToString("")

        if (hex.isEmpty()) return null
        val bytes = Hex.decodeOrNull(hex) ?: return null
        // A classic CAN frame carries at most 8 bytes; anything beyond that is
        // another column in the log, not payload.
        return if (bytes.size > 8) bytes.copyOf(8) else bytes
    }

    private fun parseTimestamp(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val normalised = text.replace(',', '.')

        // hh:mm:ss.sss
        if (normalised.contains(':')) {
            val parts = normalised.split(':')
            if (parts.size != 3) return null
            val hours = parts[0].toDoubleOrNull() ?: return null
            val minutes = parts[1].toDoubleOrNull() ?: return null
            val seconds = parts[2].toDoubleOrNull() ?: return null
            return hours * 3600 + minutes * 60 + seconds
        }
        return normalised.toDoubleOrNull()
    }

    private const val SAMPLE_SKIPPED_LIMIT = 5
}
