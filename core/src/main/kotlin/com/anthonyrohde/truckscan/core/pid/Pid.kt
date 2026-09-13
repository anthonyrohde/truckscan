package com.anthonyrohde.truckscan.core.pid

import com.anthonyrohde.truckscan.core.util.u8
import kotlin.math.roundToLong

/** How a parameter should be drawn. */
enum class GaugeStyle {
    /** Full circular dial with a needle. Tachometer, speedometer. */
    DIAL,

    /** Half-circle arc. Temperatures, levels, pressures. */
    ARC,

    /** Horizontal bar. Counters and anything without a meaningful sweep. */
    BAR,
}

/** Severity of a band on a gauge, mapped to the status palette by the UI. */
enum class ZoneSeverity { NORMAL, CAUTION, WARNING, CRITICAL }

/**
 * A band on a gauge, e.g. a tachometer redline.
 *
 * Zones exist so a reading can be judged at a glance without knowing the
 * numbers. Colour never carries that alone: the UI pairs the band with the
 * numeric readout and a state label, since a colour-blind reader or a sunlit
 * screen must still convey it.
 */
data class GaugeZone(
    val from: Double,
    val to: Double,
    val severity: ZoneSeverity,
    val label: String,
) {
    operator fun contains(value: Double): Boolean = value >= from && value < to
}

/** A decoded live-data sample. */
data class PidValue(
    val pid: Pid,
    val value: Double,
    val raw: ByteArray,
    val timestampMillis: Long = System.currentTimeMillis(),
) {
    val formatted: String get() = pid.format(value)

    /** The zone this reading falls in, if the parameter defines any. */
    val zone: GaugeZone? get() = pid.zones.firstOrNull { value in it }

    val severity: ZoneSeverity get() = zone?.severity ?: ZoneSeverity.NORMAL

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PidValue) return false
        return pid.key == other.pid.key && value == other.value
    }

    override fun hashCode(): Int = 31 * pid.key.hashCode() + value.hashCode()
}

/**
 * A readable live parameter.
 *
 * @param key unique identity for this parameter. Distinct from [id] because
 *   several diesel PIDs pack multiple sensors into one response - the four
 *   exhaust gas temperatures all arrive under PID 0x78 - and each sensor
 *   deserves its own gauge. The poller requests each [id] once and decodes
 *   every parameter sharing it from that one response.
 * @param byteCount payload bytes the module must return before decoding is
 *   attempted. Decoding a short reply would produce a plausible wrong number,
 *   which is worse than an error.
 */
data class Pid(
    val key: String,
    val id: Int,
    val name: String,
    val unit: String,
    val byteCount: Int,
    val minValue: Double,
    val maxValue: Double,
    val mode: Int = 0x01,
    val decimals: Int = 1,
    val style: GaugeStyle = GaugeStyle.ARC,
    val zones: List<GaugeZone> = emptyList(),
    /** Shorter name for a gauge face, where space is tight. */
    val shortName: String = name,
    /** True when the value is a count or duration rather than a measurement. */
    val isCounter: Boolean = false,
    /**
     * False when the scaling in [decoder] has never been checked against a
     * vehicle that answers this PID.
     *
     * The legislated PIDs are single bytes with scalings that have not changed
     * since 1996. The manufacturer-extended ones are multi-byte structures
     * whose layout is documented inconsistently, and a wrong divisor produces a
     * plausible number rather than an error - the worst kind of wrong for a
     * gauge. A false here is not a reason to hide the reading; it is a reason
     * to say, on the gauge, that it has not been confirmed.
     */
    val decodeVerified: Boolean = true,
    private val decoder: (ByteArray) -> Double,
) {
    val hexId: String get() = id.toString(16).uppercase().padStart(2, '0')

    fun decode(payload: ByteArray): PidValue? {
        if (payload.size < byteCount) return null
        val value = runCatching { decoder(payload) }.getOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        return PidValue(this, value, payload.copyOf())
    }

    fun format(value: Double): String {
        val number = if (decimals == 0) {
            value.roundToLong().toString()
        } else {
            val factor = when (decimals) { 1 -> 10.0; 2 -> 100.0; else -> 1000.0 }
            val rounded = (value * factor).roundToLong() / factor
            val whole = rounded.toLong()
            val frac = ((kotlin.math.abs(rounded - whole)) * factor).roundToLong()
            "$whole.${frac.toString().padStart(decimals, '0')}"
        }
        return if (unit.isEmpty()) number else "$number $unit"
    }

    /**
     * Fraction of the way through the expected range, for gauge rendering.
     *
     * [minValue] and [maxValue] describe the range worth *displaying*, not the
     * range the PID can encode. Several have theoretical maxima orders of
     * magnitude above anything an engine produces - mass air flow reaches
     * 655 g/s on paper and about 400 in practice - and using those flattens a
     * gauge into a permanently empty bar. Decoding is unaffected.
     */
    fun normalise(value: Double): Double =
        if (maxValue <= minValue) 0.0
        else ((value - minValue) / (maxValue - minValue)).coerceIn(0.0, 1.0)
}
