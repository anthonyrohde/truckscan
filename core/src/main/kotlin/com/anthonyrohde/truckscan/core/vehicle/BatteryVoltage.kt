package com.anthonyrohde.truckscan.core.vehicle

/**
 * Reads and interprets the adapter's battery voltage.
 *
 * ## Why this exists
 *
 * A diagnostic session runs with the ignition on and the engine off, which is
 * a steady drain with nothing replacing it. On a 6.7 diesel that is not a small
 * one. During four probe runs over about forty minutes this project watched
 * `ATRV` fall to 10.0 V with the PCM still answering, said nothing about it,
 * and the truck would not start afterwards. The number was on the screen the
 * whole time.
 *
 * So the rule here is that voltage is never just logged. It is classified, it
 * is compared against where it started, and when it is heading somewhere bad
 * the tool says so in words rather than leaving a number for someone to notice.
 *
 * ## What the thresholds are and are not
 *
 * These are the conventional figures for a 12 V lead-acid system, and they are
 * approximate. Voltage sags under load, wiring drops some of it before the OBD
 * port, temperature moves the numbers, and two batteries in parallel - which is
 * what a Super Duty has - hide a weak one behind a good one. Treat a single
 * reading as a hint.
 *
 * The trend is the part worth trusting, because it is arithmetic on two
 * measurements rather than a claim about chemistry.
 */
object BatteryVoltage {

    /** Below this, modules start dropping off the bus and a scan is unreliable. */
    const val MODULES_UNRELIABLE_VOLTS = 11.5

    /** Roughly where a starter motor stops being able to turn a cold diesel. */
    const val WILL_NOT_START_VOLTS = 11.0

    /** Above this the alternator is clearly working. */
    const val CHARGING_VOLTS = 13.2

    enum class State {
        /** Engine running: the alternator is replacing what is being used. */
        CHARGING,

        /** Healthy and not charging. A session can run for a while. */
        HEALTHY,

        /** Usable, but this is a battery being drawn down. */
        DISCHARGING,

        /** Low enough that module behaviour cannot be trusted. */
        TOO_LOW_TO_TRUST,

        /** Low enough to be a starting problem, not a diagnostic one. */
        CRITICAL,
    }

    /**
     * Parses an `ATRV` reply such as `12.4V`, `11.7 V` or `ATRV 13.8V`.
     *
     * Returns null rather than guessing: a misparsed voltage that reads high is
     * worse than no voltage at all, because it removes the warning.
     */
    fun parse(reply: String): Double? =
        // The lookarounds are not decoration. Without them "ELM327 v1.4b" - the
        // adapter's own identification string - parses as 27.0 volts, which is
        // the one failure this must not have: a reading that looks fine and
        // silently removes the warning.
        Regex("""(?<![\d.])(\d{1,2}(?:\.\d+)?)\s*V(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
            .find(reply)
            ?.groupValues?.get(1)
            ?.toDoubleOrNull()
            ?.takeIf { it in 1.0..40.0 }

    fun classify(volts: Double): State = when {
        volts >= CHARGING_VOLTS -> State.CHARGING
        volts < WILL_NOT_START_VOLTS -> State.CRITICAL
        volts < MODULES_UNRELIABLE_VOLTS -> State.TOO_LOW_TO_TRUST
        volts < 12.2 -> State.DISCHARGING
        else -> State.HEALTHY
    }

    /** One sentence for a person, naming what to do rather than what to think. */
    fun describe(volts: Double): String = when (classify(volts)) {
        State.CHARGING ->
            "%.1f V - the engine is running and charging. Nothing here will flatten it."
                .format(volts)

        State.HEALTHY ->
            "%.1f V - healthy, but the engine is not running, so this is a battery being used."
                .format(volts)

        State.DISCHARGING ->
            ("%.1f V - down on a rested battery. With the ignition on and the engine off " +
                "this will keep falling. Start the engine if the work allows it.")
                .format(volts)

        State.TOO_LOW_TO_TRUST ->
            ("%.1f V - too low to trust a scan. Modules drop off the bus around here, so a " +
                "module that does not answer may be short of volts rather than absent. " +
                "Start the engine or charge before reading anything into the results.")
                .format(volts)

        State.CRITICAL ->
            ("%.1f V - this is now a starting problem, not a diagnostic one. Stop, turn the " +
                "ignition off and charge it. A Super Duty has two batteries in parallel and " +
                "one weak cell drags the pair down.")
                .format(volts)
    }

    /**
     * What happened to the battery across a session.
     *
     * [minutesToFloor] is a straight-line extrapolation to
     * [WILL_NOT_START_VOLTS], which a discharge curve is not. It is here to
     * answer "do I have time for another scan", and it is deliberately reported
     * as an order of magnitude rather than a countdown.
     */
    data class Trend(
        val startVolts: Double,
        val endVolts: Double,
        val minutesElapsed: Double,
    ) {
        val drop: Double get() = startVolts - endVolts

        val voltsPerMinute: Double?
            get() = if (minutesElapsed >= 1.0 && drop > 0) drop / minutesElapsed else null

        val minutesToFloor: Double?
            get() = voltsPerMinute?.let { rate ->
                ((endVolts - WILL_NOT_START_VOLTS) / rate).takeIf { it >= 0 }
            }

        fun describe(): String = buildString {
            append(BatteryVoltage.describe(endVolts))
            if (drop >= 0.1 && minutesElapsed >= 1.0) {
                append(
                    "\nDown %.1f V in %.0f minutes (%.1f V to %.1f V)."
                        .format(drop, minutesElapsed, startVolts, endVolts),
                )
                minutesToFloor?.let { minutes ->
                    append(
                        "\nAt that rate it reaches %.1f V in roughly %.0f more minutes - a " .format(
                            WILL_NOT_START_VOLTS, minutes,
                        ) + "straight line through a curve, so treat it as an order of " +
                            "magnitude, not a countdown.",
                    )
                }
            }
        }
    }
}
