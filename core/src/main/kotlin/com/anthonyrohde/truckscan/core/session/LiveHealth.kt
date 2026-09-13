package com.anthonyrohde.truckscan.core.session

/** How reliably one parameter has been answering. */
data class ParameterHealth(
    val key: String,
    val name: String,
    val attempts: Int,
    val successes: Int,
) {
    val percent: Int get() = if (attempts == 0) 0 else successes * 100 / attempts

    /** Asked for repeatedly and never once answered. */
    val neverAnswered: Boolean get() = attempts > 0 && successes == 0
}

/**
 * Counts how often each parameter actually answers, and how long sweeps take.
 *
 * This is the part of a diagnostic report worth having. "Live data is not
 * working" covers several different faults - a parameter the vehicle does not
 * support, one that answers intermittently, or a bus that has stopped
 * responding altogether - and they need different fixes. Counting per parameter
 * separates them without anybody having to watch a gauge and form an
 * impression.
 */
class LiveHealth {

    private val attempts = LinkedHashMap<String, Int>()
    private val successes = LinkedHashMap<String, Int>()
    private val names = LinkedHashMap<String, String>()
    private val sweepMillis = mutableListOf<Long>()

    var sweepCount: Int = 0
        private set

    fun accept(sample: LiveDataSample) {
        sweepCount++
        if (sample.sweepMillis > 0) sweepMillis += sample.sweepMillis

        for ((key, value) in sample.values) {
            names[key] = value.pid.name
            attempts[key] = (attempts[key] ?: 0) + 1
            successes[key] = (successes[key] ?: 0) + 1
        }
        for (key in sample.failedKeys) {
            attempts[key] = (attempts[key] ?: 0) + 1
            successes.putIfAbsent(key, 0)
        }
    }

    fun clear() {
        attempts.clear()
        successes.clear()
        names.clear()
        sweepMillis.clear()
        sweepCount = 0
    }

    /** Worst first, since that is what anyone reading this is looking for. */
    fun parameters(): List<ParameterHealth> =
        attempts.keys.map { key ->
            ParameterHealth(
                key = key,
                name = names[key] ?: key,
                attempts = attempts[key] ?: 0,
                successes = successes[key] ?: 0,
            )
        }.sortedBy { it.percent }

    /**
     * Sweep timing, split at one second.
     *
     * The split is not arbitrary: a sweep of a handful of parameters should
     * take a few hundred milliseconds, and anything past a second means at
     * least one request ran to its timeout. Counting the two separately shows
     * a stutter that an average would hide.
     */
    fun timing(): SweepTiming {
        if (sweepMillis.isEmpty()) return SweepTiming(0, 0, 0, 0, 0)
        val sorted = sweepMillis.sorted()
        val fast = sorted.filter { it < SLOW_SWEEP_MS }
        val slow = sorted.filter { it >= SLOW_SWEEP_MS }
        return SweepTiming(
            count = sorted.size,
            medianMillis = sorted[sorted.size / 2],
            slowCount = slow.size,
            slowMedianMillis = if (slow.isEmpty()) 0 else slow[slow.size / 2],
            fastMedianMillis = if (fast.isEmpty()) 0 else fast[fast.size / 2],
        )
    }

    data class SweepTiming(
        val count: Int,
        val medianMillis: Long,
        val slowCount: Int,
        val slowMedianMillis: Long,
        val fastMedianMillis: Long,
    )

    private companion object {
        const val SLOW_SWEEP_MS = 1_000L
    }
}
