package com.anthonyrohde.truckscan.core.session

import com.anthonyrohde.truckscan.core.pid.PidValue

/**
 * Keeps the last reading for each parameter so a gauge does not blank on one
 * missed sweep.
 *
 * A sweep asks for each parameter in turn and any one of them can miss - a
 * module busy with something else, a reply that arrives a moment late. Showing
 * the raw sweep means every such miss empties a gauge and the next sweep fills
 * it again, which on a dash reads as the app being broken even when the truck
 * and the data are fine. Coolant temperature does not actually vanish for half
 * a second.
 *
 * What is emphatically not done here is inventing readings. A held value is the
 * real last measurement with the real time it was taken, so a caller can say
 * how old it is and fade it when it stops meaning anything. Recording keeps the
 * raw sweeps, not these - a log is a record of what was measured, and a held
 * value repeated fifty times would read as fifty measurements.
 */
class LiveValueHold(
    /** After this long with no update, a held reading is no longer current. */
    private val staleAfterMillis: Long = 5_000,
) {
    private val held = LinkedHashMap<String, PidValue>()

    /** Merges [sample] over what is held and returns the current picture. */
    fun accept(sample: LiveDataSample): Map<String, PidValue> {
        for ((key, value) in sample.values) held[key] = value
        return held.toMap()
    }

    /** Keys whose last reading is older than the staleness window. */
    fun staleKeys(nowMillis: Long): Set<String> =
        held.filterValues { nowMillis - it.timestampMillis > staleAfterMillis }.keys

    fun isStale(key: String, nowMillis: Long): Boolean {
        val value = held[key] ?: return true
        return nowMillis - value.timestampMillis > staleAfterMillis
    }

    fun clear() = held.clear()
}
