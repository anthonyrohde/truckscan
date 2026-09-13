package com.anthonyrohde.truckscan.core.session

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One file that answers "what is working and what is not".
 *
 * Assembled rather than dumped. A raw traffic log says everything and
 * therefore nothing: finding out whether a module answers means reading
 * hundreds of lines and counting. This puts the answers at the top - which
 * modules replied, which parameters report, how sweeps are timed - and leaves
 * the raw exchange underneath for when the summary is not enough.
 *
 * Free of Android so it can be tested, which matters: a diagnostic that is
 * wrong about what is working is worse than none, because it sends whoever
 * reads it somewhere else entirely.
 */
data class DiagnosticReport(
    val generatedAtMillis: Long,
    val appVersion: String,
    val device: String,
    val androidVersion: String,
    val adapter: String,
    val transport: String,
    val multiBus: Boolean?,
    val activeBus: String?,
    val connection: String,
    val modules: List<ModuleLine>,
    /**
     * What happened the last time modules were scanned, if they ever were.
     *
     * An empty module list on its own is ambiguous in the worst way: it means
     * either "nobody has scanned" or "a scan ran and the vehicle said nothing",
     * and those call for opposite next steps. Never infer one from the other.
     */
    val moduleScan: ModuleScan?,
    /** Records that say something is wrong. See [ModuleDtcResult.faults]. */
    val faults: List<String>,
    /**
     * Records that only say a monitor has not run yet, counted rather than
     * listed.
     *
     * A status-mask read returns a module's whole DTC table. This truck's PCM
     * returns 344 records of which three are faults, and printing all of them
     * put those three under 439 lines of "not tested this cycle" in a report
     * whose entire purpose is to be read by someone looking for a problem.
     */
    val monitorsNotRun: Int = 0,
    /**
     * How many modules a fault scan actually attempted to read.
     *
     * Needed to tell "nobody has run a scan" apart from "a scan ran and
     * everything it could read was clean" - both show up as an empty
     * [faults] list otherwise, and they call for opposite conclusions. A
     * report that said "None recorded, or no scan has been run" after a scan
     * had in fact run and found three of four modules unreadable is the exact
     * failure this exists to prevent, and it happened.
     */
    val faultScanModuleCount: Int = 0,
    /** Modules the fault scan could not read at all. See [ModuleDtcResult.error]. */
    val unreadableModuleCount: Int = 0,
    val supportedPidCount: Int?,
    val parametersOffered: Int,
    /** What the live stream is actually polling. */
    val parametersWatched: Int,
    /** What is ticked on the screen, which can differ mid-session. */
    val parametersSelected: Int,
    val health: List<ParameterHealth>,
    val timing: LiveHealth.SweepTiming,
    val recentLog: List<String>,
) {
    data class ModuleScan(
        val ranAtMillis: Long,
        val full: Boolean,
        val found: Int,
        /** Buses that showed no traffic at all while being scanned. */
        val quietBuses: List<String> = emptyList(),
        /** True when a disconnect has since discarded the results. */
        val discarded: Boolean = false,
    )

    data class ModuleLine(
        val name: String,
        val address: String,
        val bus: String,
        val answered: Boolean,
        val detail: String = "",
    )

    fun render(): String = buildString {
        heading("TRUCK SCAN DIAGNOSTIC REPORT")
        line("Generated", TIMESTAMP.format(Instant.ofEpochMilli(generatedAtMillis)))
        line("App", appVersion)
        line("Device", device)
        line("Android", androidVersion)

        heading("ADAPTER")
        line("Identity", adapter)
        line("Transport", transport)
        line("Multi-bus", multiBus?.let { if (it) "yes" else "no - HS-CAN1 only" } ?: "unknown")
        line("Active bus", activeBus ?: "none")
        line("Connection", connection)

        heading("MODULES")
        val scan = moduleScan
        if (modules.isEmpty()) {
            when {
                scan == null ->
                    appendLine("  No module scan has been run this session.")

                scan.discarded -> {
                    appendLine(
                        "  A ${scanKind(scan)} ran at ${TIMESTAMP.format(Instant.ofEpochMilli(scan.ranAtMillis))} " +
                            "and found ${scan.found}, but a disconnect has since",
                    )
                    appendLine("  discarded the results. Scan again while connected.")
                }

                else -> {
                    appendLine(
                        "  A ${scanKind(scan)} ran at " +
                            "${TIMESTAMP.format(Instant.ofEpochMilli(scan.ranAtMillis))} and " +
                            "no module answered.",
                    )
                    appendLine()
                    appendLine("  Every address was probed and none replied.")
                    appendLine()
                    appendLine("  Worth checking in order: the adapter fully seated in the")
                    appendLine("  port, the ignition on, and then the Probe screen, which")
                    appendLine("  shows whether the vehicle answers a direct request at all.")
                    if (scan.quietBuses.isNotEmpty()) {
                        appendLine()
                        appendLine(
                            "  No free-running traffic was seen on: " +
                                scan.quietBuses.joinToString(", ") + ".",
                        )
                        appendLine("  That is recorded for completeness and means very little:")
                        appendLine("  behind a gateway nothing is ever overheard, and this")
                        appendLine("  vehicle reports silence even while a module is answering.")
                    }
                }
            }
        } else {
            scan?.let {
                appendLine(
                    "  ${scanKind(it)} at " +
                        TIMESTAMP.format(Instant.ofEpochMilli(it.ranAtMillis)),
                )
            }
            val answered = modules.count { it.answered }
            appendLine("  $answered of ${modules.size} answered.")
            appendLine()
            for (m in modules.sortedBy { it.answered }) {
                val mark = if (m.answered) "ok  " else "MISS"
                appendLine(
                    "  $mark  ${m.name.padEnd(8)} ${m.address.padEnd(5)} " +
                        "${m.bus.padEnd(9)} ${m.detail}".trimEnd(),
                )
            }
        }

        heading("FAULT CODES")
        if (faultScanModuleCount == 0) {
            appendLine("  None recorded, or no scan has been run.")
        } else {
            if (faults.isEmpty()) {
                appendLine("  No faults in the modules that were read.")
            } else {
                faults.forEach { appendLine("  $it") }
            }
            if (unreadableModuleCount > 0) {
                appendLine()
                appendLine(
                    "  $unreadableModuleCount module(s) could not be read, so this is " +
                        "not a clean bill of health.",
                )
            }
        }
        if (monitorsNotRun > 0) {
            appendLine()
            appendLine(
                "  $monitorsNotRun further record(s) say only that a monitor has not run " +
                    "since the last clear. They are not faults and are not listed.",
            )
        }

        heading("LIVE DATA")
        line("Vehicle reports supported", supportedPidCount?.toString() ?: "not asked")
        line("Parameters offered", parametersOffered.toString())
        line("Parameters streaming", parametersWatched.toString())
        if (parametersSelected != parametersWatched) {
            line(
                "Parameters selected",
                "$parametersSelected (changed since the stream started, so the " +
                    "difference is not a failure)",
            )
        }

        if (timing.count == 0) {
            appendLine()
            appendLine("  No sweeps recorded. Live data has not been started this session.")
        } else {
            line("Sweeps", timing.count.toString())
            line("Median sweep", "${timing.medianMillis} ms")
            if (timing.slowCount > 0) {
                line(
                    "Slow sweeps",
                    "${timing.slowCount} of ${timing.count}, median " +
                        "${timing.slowMedianMillis} ms (a sweep past a second means at " +
                        "least one request timed out)",
                )
            } else {
                line("Slow sweeps", "none")
            }
        }

        if (health.isNotEmpty()) {
            appendLine()
            appendLine("  Per parameter, worst first:")
            appendLine()
            for (p in health) {
                val note = when {
                    p.neverAnswered -> "  <- never answered; likely not supported"
                    p.percent < 90 -> "  <- intermittent"
                    else -> ""
                }
                appendLine(
                    "  ${p.percent.toString().padStart(3)}%  " +
                        "${p.successes}/${p.attempts}".padEnd(9) +
                        "${p.name}$note",
                )
            }
        }

        heading("ADAPTER LOG (last ${recentLog.size} lines)")
        if (recentLog.isEmpty()) {
            appendLine("  Empty.")
        } else {
            recentLog.forEach { appendLine("  $it") }
        }
    }

    private fun scanKind(scan: ModuleScan) = if (scan.full) "full sweep" else "quick scan"

    private fun StringBuilder.heading(text: String) {
        appendLine()
        appendLine(text)
        appendLine("-".repeat(text.length))
    }

    private fun StringBuilder.line(label: String, value: String) {
        appendLine("  ${label.padEnd(26)}$value")
    }

    private companion object {
        val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)
    }
}
