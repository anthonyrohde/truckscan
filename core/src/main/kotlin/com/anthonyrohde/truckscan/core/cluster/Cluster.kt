package com.anthonyrohde.truckscan.core.cluster

import com.anthonyrohde.truckscan.core.pid.MeasuredSupport
import com.anthonyrohde.truckscan.core.pid.PidCatalog
import com.anthonyrohde.truckscan.core.pid.PidValue

/**
 * The dial geometry and readings behind the dash cluster.
 *
 * ## Why the maths is here and not in the screen
 *
 * The Android module cannot be compiled in the environment this project is
 * developed in - no Android SDK - so anything written there is unverified until
 * CI builds it, and untestable after that. Angles, tick positions, zone bands,
 * clamping and unit arithmetic are exactly the things that are wrong by a
 * factor or a sign and look almost right. So they live here, in plain Kotlin,
 * with tests, and the screen is left with nothing to do but draw what it is
 * handed.
 *
 * ## Angles
 *
 * Degrees, clockwise, zero at three o'clock - the convention Compose's
 * `drawArc` already uses, so nothing has to convert between two of them. A dial
 * that sweeps from lower-left to lower-right starts at 150 and sweeps 240.
 */
object Cluster {

    /** Where a dial's number comes from. */
    sealed interface Source {
        /** One catalogue parameter, by key. */
        data class Single(val key: String) : Source

        /**
         * One parameter minus another, which is how boost is measured.
         *
         * A turbocharged engine's manifold pressure is absolute: at rest it
         * reads atmospheric, around 101 kPa at sea level. What a boost gauge
         * shows is the amount above atmospheric, so the ambient reading has to
         * be subtracted rather than assumed - a truck at altitude, or a
         * barometer moving through a front, is several kPa of error otherwise.
         */
        data class Difference(val minuend: String, val subtrahend: String) : Source
    }

    data class Dial(
        val id: String,
        val label: String,
        val unit: String,
        val min: Double,
        val max: Double,
        val majorTick: Double,
        val minorPerMajor: Int,
        val decimals: Int,
        val startAngleDeg: Double,
        val sweepDeg: Double,
        val source: Source,
        /** Reading at or above which the dial is amber. */
        val cautionFrom: Double? = null,
        /** Reading at or above which the dial is red. */
        val dangerFrom: Double? = null,
        /** Divides the tick labels, e.g. 1000 for a tachometer reading 0-5. */
        val labelDivisor: Double = 1.0,
    ) {
        /** Parameter keys this dial needs before it can show anything. */
        val keys: List<String>
            get() = when (source) {
                is Source.Single -> listOf(source.key)
                is Source.Difference -> listOf(source.minuend, source.subtrahend)
            }

        val span: Double get() = max - min
    }

    /** A number in the panel rather than on a dial. */
    data class Readout(
        val id: String,
        val label: String,
        val source: Source,
        val decimals: Int,
        val unit: String,
    )

    data class Tick(
        val value: Double,
        val angleDeg: Double,
        val major: Boolean,
        val label: String?,
    )

    /** A coloured band on the dial face. */
    data class Band(val startAngleDeg: Double, val sweepDeg: Double, val danger: Boolean)

    enum class Severity { NORMAL, CAUTION, DANGER }

    /**
     * What a dial should show right now.
     *
     * [value] null and [supported] true means the sweep has not brought this
     * one back yet. [supported] false means the vehicle does not offer it at
     * all, which is a different thing and must look different - a dial that is
     * blank because the truck cannot answer should say so rather than sit
     * waiting forever.
     */
    data class Reading(
        val dial: Dial,
        val value: Double?,
        val supported: Boolean,
        val verified: Boolean,
    ) {
        val severity: Severity
            get() {
                val v = value ?: return Severity.NORMAL
                dial.dangerFrom?.let { if (v >= it) return Severity.DANGER }
                dial.cautionFrom?.let { if (v >= it) return Severity.CAUTION }
                return Severity.NORMAL
            }

        val angleDeg: Double? get() = value?.let { angleFor(dial, it) }

        /** 0 at the start of the sweep, 1 at the end, clamped. */
        val fraction: Double? get() = value?.let { fractionFor(dial, it) }

        val text: String
            get() {
                if (!supported) return "n/a"
                val v = value ?: return "--"
                return format(v, dial.decimals)
            }
    }

    // ------------------------------------------------------------ geometry

    fun fractionFor(dial: Dial, value: Double): Double {
        if (dial.span == 0.0) return 0.0
        return ((value - dial.min) / dial.span).coerceIn(0.0, 1.0)
    }

    fun angleFor(dial: Dial, value: Double): Double =
        dial.startAngleDeg + dial.sweepDeg * fractionFor(dial, value)

    /**
     * Every tick on the face, majors labelled.
     *
     * Built from the range rather than a count, so a dial whose range changes
     * cannot end up with ticks that do not land on its numbers.
     */
    fun ticksFor(dial: Dial): List<Tick> {
        if (dial.majorTick <= 0 || dial.span <= 0) return emptyList()
        val step = dial.majorTick / (dial.minorPerMajor + 1).coerceAtLeast(1)
        val count = Math.round(dial.span / step).toInt()
        return (0..count).map { i ->
            val value = dial.min + i * step
            val major = i % (dial.minorPerMajor + 1) == 0
            Tick(
                value = value,
                angleDeg = angleFor(dial, value),
                major = major,
                label = if (major) format(value / dial.labelDivisor, labelDecimals(dial)) else null,
            )
        }
    }

    private fun labelDecimals(dial: Dial): Int =
        if (dial.labelDivisor > 1.0) 0 else dial.decimals.coerceAtMost(1)

    /** The amber and red arcs, in draw order, or empty if the dial has none. */
    fun bandsFor(dial: Dial): List<Band> = buildList {
        val danger = dial.dangerFrom
        val caution = dial.cautionFrom
        if (caution != null) {
            val end = danger ?: dial.max
            if (end > caution) add(bandBetween(dial, caution, end, danger = false))
        }
        if (danger != null && dial.max > danger) {
            add(bandBetween(dial, danger, dial.max, danger = true))
        }
    }

    private fun bandBetween(dial: Dial, from: Double, to: Double, danger: Boolean): Band {
        val start = angleFor(dial, from)
        return Band(start, angleFor(dial, to) - start, danger)
    }

    // ------------------------------------------------------------- readings

    /**
     * Resolves a dial against the latest sweep.
     *
     * A [Source.Difference] needs both halves: half an answer is not a smaller
     * answer, it is a wrong one, so boost stays blank until ambient pressure
     * has been read too.
     */
    fun read(
        dial: Dial,
        values: Map<String, PidValue>,
        supportedPidIds: Set<Int> = MeasuredSupport.SUPER_DUTY_2022_PCM_DATA,
    ): Reading {
        val pids = dial.keys.map { PidCatalog.byKey(it) }
        val supported = pids.all { it != null && it.id in supportedPidIds }
        val verified = pids.all { it != null && it.decodeVerified }

        val value = when (val source = dial.source) {
            is Source.Single -> values[source.key]?.value
            is Source.Difference -> {
                val a = values[source.minuend]?.value
                val b = values[source.subtrahend]?.value
                if (a == null || b == null) null else a - b
            }
        }
        return Reading(dial, value, supported, verified)
    }

    fun readAll(
        values: Map<String, PidValue>,
        supportedPidIds: Set<Int> = MeasuredSupport.SUPER_DUTY_2022_PCM_DATA,
    ): List<Reading> = LAYOUT.all.map { read(it, values, supportedPidIds) }

    /** Every parameter key the cluster wants, for the poller to request. */
    fun requiredKeys(): List<String> =
        (LAYOUT.all.flatMap { it.keys } + LAYOUT.readouts.flatMap { readoutKeys(it) }).distinct()

    private fun readoutKeys(readout: Readout): List<String> = when (val s = readout.source) {
        is Source.Single -> listOf(s.key)
        is Source.Difference -> listOf(s.minuend, s.subtrahend)
    }

    // ------------------------------------------------------------ formatting

    fun format(value: Double, decimals: Int): String {
        if (decimals <= 0) return Math.round(value).toString()
        var factor = 1L
        repeat(decimals) { factor *= 10 }
        val scaled = Math.round(value * factor)
        val whole = scaled / factor
        val frac = kotlin.math.abs(scaled % factor)
        val sign = if (scaled < 0 && whole == 0L) "-" else ""
        return "$sign$whole.${frac.toString().padStart(decimals, '0')}"
    }

    // ----------------------------------------------------------------- demo

    /**
     * Plausible readings so the face can be judged without a vehicle.
     *
     * This exists because a cluster is a visual design and the truck is not
     * always available - a layout with every dial reading "--" cannot be
     * assessed. The numbers are a truck at a steady 90 km/h, warmed up, on a
     * light throttle.
     *
     * Anything that shows these MUST label itself as a demonstration, in a way
     * that cannot be mistaken for a reading. A gauge that lies convincingly is
     * the worst failure available to this app, and inventing numbers is exactly
     * how that happens.
     */
    fun demoValues(): Map<String, PidValue> {
        val demo = mapOf(
            "rpm" to 1750.0,
            "speed" to 92.0,
            "coolant" to 88.0,
            "oil_temp" to 96.0,
            "fuel_level" to 64.0,
            "map_ext" to 178.0,
            "baro" to 101.0,
            "voltage" to 14.1,
            "egt1" to 412.0,
            "ambient" to 19.0,
            "load" to 38.0,
            "cact" to 41.0,
            "odometer" to 35707.4,
        )
        return demo.mapNotNull { (key, value) ->
            PidCatalog.byKey(key)?.let { pid -> key to PidValue(pid, value, ByteArray(pid.byteCount)) }
        }.toMap()
    }

    // --------------------------------------------------------------- layout

    data class Layout(
        val tachometer: Dial,
        val speedometer: Dial,
        val arcs: List<Dial>,
        val readouts: List<Readout>,
    ) {
        val all: List<Dial> get() = listOf(tachometer, speedometer) + arcs
    }

    /**
     * The face itself.
     *
     * Laid out after the truck's own cluster: two large dials with a row of
     * small arcs above them and a data panel between. The ranges are this
     * vehicle's - a 6.7 diesel turns 5000 rpm, not 8000, and a tachometer
     * marked to 8000 wastes three quarters of its sweep on numbers the engine
     * cannot reach.
     *
     * Oil pressure is on the real dash and is deliberately absent here. OBD-II
     * does not carry it, and a gauge showing something else under that name
     * would be the single most misleading thing this app could draw. Oil
     * temperature takes the slot and says so.
     */
    val LAYOUT = Layout(
        tachometer = Dial(
            id = "tach",
            label = "RPM x1000",
            unit = "rpm",
            min = 0.0, max = 5000.0,
            majorTick = 1000.0, minorPerMajor = 4,
            decimals = 0,
            startAngleDeg = 150.0, sweepDeg = 240.0,
            source = Source.Single("rpm"),
            // The photographed cluster reddens from just past 4, and this
            // engine's governor is a long way below a petrol V8's.
            dangerFrom = 4200.0,
            labelDivisor = 1000.0,
        ),
        speedometer = Dial(
            id = "speed",
            label = "km/h",
            unit = "km/h",
            min = 0.0, max = 200.0,
            majorTick = 20.0, minorPerMajor = 1,
            decimals = 0,
            startAngleDeg = 150.0, sweepDeg = 240.0,
            source = Source.Single("speed"),
        ),
        arcs = listOf(
            Dial(
                id = "oil_temp",
                label = "OIL TEMP",
                unit = "C",
                min = 40.0, max = 150.0,
                majorTick = 55.0, minorPerMajor = 4,
                decimals = 0,
                startAngleDeg = 180.0, sweepDeg = 180.0,
                source = Source.Single("oil_temp"),
                cautionFrom = 120.0, dangerFrom = 135.0,
            ),
            Dial(
                id = "coolant",
                label = "COOLANT",
                unit = "C",
                min = 40.0, max = 130.0,
                majorTick = 45.0, minorPerMajor = 4,
                decimals = 0,
                startAngleDeg = 180.0, sweepDeg = 180.0,
                source = Source.Single("coolant"),
                cautionFrom = 105.0, dangerFrom = 115.0,
            ),
            Dial(
                id = "fuel",
                label = "FUEL",
                unit = "%",
                min = 0.0, max = 100.0,
                majorTick = 25.0, minorPerMajor = 0,
                decimals = 0,
                startAngleDeg = 180.0, sweepDeg = 180.0,
                source = Source.Single("fuel_level"),
            ),
            Dial(
                id = "boost",
                label = "TURBO kPa",
                unit = "kPa",
                min = 0.0, max = 280.0,
                majorTick = 70.0, minorPerMajor = 4,
                decimals = 0,
                startAngleDeg = 180.0, sweepDeg = 180.0,
                // Manifold pressure is absolute; boost is what is above
                // ambient, so ambient is measured rather than assumed.
                source = Source.Difference("map_ext", "baro"),
            ),
        ),
        readouts = listOf(
            Readout("odometer", "ODOMETER", Source.Single("odometer"), 1, "km"),
            Readout("voltage", "BATTERY", Source.Single("voltage"), 1, "V"),
            Readout("egt", "EGT", Source.Single("egt1"), 0, "C"),
            Readout("ambient", "AMBIENT", Source.Single("ambient"), 0, "C"),
            Readout("load", "LOAD", Source.Single("load"), 0, "%"),
            Readout("cact", "CHARGE AIR", Source.Single("cact"), 0, "C"),
        ),
    )
}
