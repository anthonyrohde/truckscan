package com.anthonyrohde.truckscan.core.trace

import com.anthonyrohde.truckscan.core.adapter.CanFrame
import com.anthonyrohde.truckscan.core.util.Hex

/** One CAN frame recovered from a log line. */
data class TraceFrame(
    val lineNumber: Int,
    val canId: Int,
    val data: ByteArray,
    /** Seconds from the start of the capture, when the log carried timestamps. */
    val relativeSeconds: Double?,
    val rawLine: String,
) {
    fun toCanFrame(): CanFrame = CanFrame(canId, data)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TraceFrame) return false
        return canId == other.canId && data.contentEquals(other.data) &&
            lineNumber == other.lineNumber
    }

    override fun hashCode(): Int = 31 * (31 * lineNumber + canId) + data.contentHashCode()

    override fun toString(): String = "${Hex.encode(canId, 3)}#${Hex.encode(data)}"
}

/** Summary of how a log parsed, so the UI can say why a file produced nothing. */
data class TraceParseReport(
    val totalLines: Int,
    val framesParsed: Int,
    val linesSkipped: Int,
    /** First few unparsed lines, to help diagnose an unsupported format. */
    val sampleSkippedLines: List<String>,
) {
    val isEmpty: Boolean get() = framesParsed == 0

    fun describe(): String = when {
        framesParsed == 0 && totalLines == 0 -> "The file is empty."
        framesParsed == 0 -> "No CAN frames were recognised in $totalLines lines. " +
            "This does not look like a bus trace. Make sure the log contains raw " +
            "frame data rather than only decoded values."
        else -> "Recognised $framesParsed frame(s) from $totalLines line(s)."
    }
}
