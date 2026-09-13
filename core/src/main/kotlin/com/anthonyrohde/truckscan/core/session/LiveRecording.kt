package com.anthonyrohde.truckscan.core.session

import com.anthonyrohde.truckscan.core.pid.PidValue
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Accumulates live data samples and renders them as CSV.
 *
 * The point is the faults you cannot watch for. A coolant spike on a long
 * climb, boost falling away under load, an exhaust temperature that only
 * misbehaves once the truck is hot - none of those can be caught by staring at
 * a gauge while driving, and none of them leave a fault code until they are
 * already bad. Recording turns them into something you can look at afterwards,
 * plot, and compare against the next run.
 *
 * Kept in :core, and free of Android, so the CSV it produces can be tested
 * rather than eyeballed.
 */
class LiveRecording(
    /**
     * Rows kept before recording stops accepting more.
     *
     * At roughly five samples a second this is a bit over two hours, which is
     * a long drive. The cap exists because the alternative - growing until the
     * app is killed - loses the whole recording rather than the end of it.
     */
    private val maxRows: Int = 40_000,
) {
    /** One sweep: when it was taken, and what each parameter read. */
    private data class Row(val timestampMillis: Long, val values: Map<String, Double>)

    private val rows = mutableListOf<Row>()

    /**
     * Column key to heading, in the order first seen.
     *
     * Parameters can appear part way through - a sensor that only reports once
     * the engine is running, say - so the column set is built up as it goes
     * rather than fixed at the start.
     */
    private val headings = LinkedHashMap<String, String>()

    var startedAtMillis: Long = 0L
        private set

    val rowCount: Int get() = rows.size
    val isFull: Boolean get() = rows.size >= maxRows

    /** Seconds covered, from the first sample to the last. */
    val durationSeconds: Double
        get() = if (rows.size < 2) {
            0.0
        } else {
            (rows.last().timestampMillis - rows.first().timestampMillis) / 1000.0
        }

    fun clear() {
        rows.clear()
        headings.clear()
        startedAtMillis = 0L
    }

    /**
     * Records one sweep. Returns false once [maxRows] is reached.
     *
     * Readings that did not decode are left out rather than written as zero: a
     * gap in a column is the truth, and a zero would look like a real reading
     * of nothing.
     */
    fun add(sample: LiveDataSample): Boolean {
        if (isFull) return false
        if (rows.isEmpty()) startedAtMillis = sample.timestampMillis

        val values = LinkedHashMap<String, Double>(sample.values.size)
        for ((key, reading) in sample.values) {
            if (reading.value.isNaN()) continue
            headings.getOrPut(key) { heading(reading) }
            values[key] = reading.value
        }
        rows += Row(sample.timestampMillis, values)
        return true
    }

    /** `Engine coolant temperature (C)`, or just the name when it has no unit. */
    private fun heading(reading: PidValue): String {
        val unit = reading.pid.unit.trim()
        return if (unit.isEmpty()) reading.pid.name else "${reading.pid.name} ($unit)"
    }

    /**
     * The recording as CSV, with a UTC timestamp and an elapsed column.
     *
     * Elapsed seconds are there because they are what you plot against;
     * absolute time is there because it is what lets a recording be lined up
     * with the adapter log, or with something that happened at a known moment.
     */
    fun toCsv(): String = buildString {
        append(csvRow(listOf("time_utc", "elapsed_s") + headings.values))
        val keys = headings.keys.toList()
        for (row in rows) {
            val cells = ArrayList<String>(keys.size + 2)
            cells += isoUtc(row.timestampMillis)
            cells += trimNumber((row.timestampMillis - startedAtMillis) / 1000.0, 3)
            for (key in keys) {
                val value = row.values[key]
                cells += if (value == null) "" else trimNumber(value, 3)
            }
            append(csvRow(cells))
        }
    }

    private fun csvRow(cells: List<String>): String =
        cells.joinToString(",") { escape(it) } + "\n"

    /** Quotes a cell only when it would otherwise break the row. */
    private fun escape(cell: String): String =
        if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + cell.replace("\"", "\"\"") + "\""
        } else {
            cell
        }

    companion object {

        private val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /**
         * A number without spurious precision or a trailing `.0`.
         *
         * Engine speed is a whole number and should read as one; a lambda
         * reading genuinely needs its decimals. Formatting everything the same
         * way gives one of them a column full of noise. BigDecimal is used
         * rather than string formatting because it is the only way to get
         * plain notation at every magnitude - a spreadsheet will not read
         * `1.0E-4` back as a number reliably.
         */
        fun trimNumber(value: Double, decimals: Int): String {
            if (value.isNaN() || value.isInfinite()) return ""
            return BigDecimal(value)
                .setScale(decimals, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
        }

        /** `2026-09-13T01:40:05.123Z`, so a spreadsheet sorts it correctly. */
        fun isoUtc(millis: Long): String = TIMESTAMP.format(Instant.ofEpochMilli(millis))
    }
}
