package com.anthonyrohde.f250scan.core.session

import com.anthonyrohde.f250scan.core.isotp.IsoTpChannel
import com.anthonyrohde.f250scan.core.uds.DiagnosticSession
import com.anthonyrohde.f250scan.core.uds.ResetType
import com.anthonyrohde.f250scan.core.uds.RoutineControlType
import com.anthonyrohde.f250scan.core.uds.UdsClient
import com.anthonyrohde.f250scan.core.util.toHex

/**
 * How dangerous a routine is, which decides how much confirmation the UI asks for.
 */
enum class RoutineRisk(val label: String) {
    /** Reads or resets volatile data. Worst case is relearning drive cycles. */
    LOW("Low risk"),

    /** Moves an actuator or suppresses fault logging. Vehicle must be stationary. */
    MODERATE("Moderate - vehicle must be stationary"),

    /** Commands something with mechanical or thermal consequences. */
    HIGH("High - understand this fully before running it"),
}

/**
 * An executable service routine.
 *
 * @param routineId the UDS RoutineControl identifier. Null for routines built
 *   on a standard service (an ECU reset, say) rather than on service 0x31.
 * @param preconditions plain-language conditions the person must satisfy. These
 *   are shown and acknowledged before running; a module enforces its own and
 *   answers NRC 0x22 when they are not met, but telling someone up front beats
 *   letting them guess at a rejection.
 */
data class ServiceRoutine(
    val id: String,
    val name: String,
    val description: String,
    val moduleCode: String,
    val risk: RoutineRisk,
    val routineId: Int? = null,
    val options: ByteArray = ByteArray(0),
    val preconditions: List<String> = emptyList(),
    val requiresSecurityAccess: Boolean = true,
    val expectedDurationSeconds: Int = 5,
) {
    override fun equals(other: Any?): Boolean = other is ServiceRoutine && id == other.id
    override fun hashCode(): Int = id.hashCode()
}

sealed class RoutineResult {
    data class Completed(val response: ByteArray, val detail: String) : RoutineResult()
    data class Rejected(val reason: String) : RoutineResult()
    data class Failed(val reason: String) : RoutineResult()
}

/**
 * Runs service routines.
 *
 * ## Why the built-in catalog is short
 *
 * The routines people want on a 6.7L - injector cutout balance tests, a forced
 * DPF regeneration, a KAM reset, brake bleed, steering angle calibration - are
 * invoked through UDS RoutineControl with identifiers Ford does not publish.
 * The transport for them is fully implemented here, and [runCustom] will drive
 * any identifier you supply. What this app will not do is guess.
 *
 * That restraint is deliberate, not laziness. Sweeping RoutineControl
 * identifiers to see what responds means commanding unknown functions on a
 * live vehicle: the identifier space includes things that energise injectors,
 * cycle ABS valves and command a regeneration that puts exhaust over 600 °C.
 * A discovery scan is safe for *reads* - see [AsBuiltReader] - and is not safe
 * here, so there is no routine-discovery function and there should not be one.
 *
 * Supply identifiers from a source you trust, and they will run with the same
 * session handling, security access and error reporting as everything else.
 */
class ServiceRoutineRunner(
    private val channel: IsoTpChannel,
    private val logger: ((String) -> Unit)? = null,
) {
    /**
     * Standard-service operations that need no proprietary identifier.
     *
     * These are genuinely useful and genuinely safe to ship: all four are
     * defined by ISO 14229 rather than by Ford.
     */
    val standardOperations: List<ServiceRoutine> = listOf(
        ServiceRoutine(
            id = "ecu_reset_hard",
            name = "Module reset",
            description = "Commands a hard reset of the module (UDS service 0x11). " +
                "Clears volatile state and restarts its software. On a PCM this " +
                "discards some learned values, which will relearn over the next " +
                "few drive cycles.",
            moduleCode = "*",
            risk = RoutineRisk.MODERATE,
            preconditions = listOf(
                "Vehicle stationary with the parking brake on",
                "Engine off, ignition on",
                "Do not reset a module while it is being programmed",
            ),
            requiresSecurityAccess = false,
            expectedDurationSeconds = 10,
        ),
        ServiceRoutine(
            id = "dtc_logging_off",
            name = "Suspend fault logging",
            description = "Stops the module storing new DTCs (UDS service 0x85). " +
                "Useful while unplugging a sensor deliberately. Re-enable it, or " +
                "cycle the ignition, when finished.",
            moduleCode = "*",
            risk = RoutineRisk.MODERATE,
            preconditions = listOf("Remember to re-enable logging afterwards"),
            requiresSecurityAccess = false,
            expectedDurationSeconds = 1,
        ),
        ServiceRoutine(
            id = "dtc_logging_on",
            name = "Resume fault logging",
            description = "Re-enables DTC storage (UDS service 0x85).",
            moduleCode = "*",
            risk = RoutineRisk.LOW,
            requiresSecurityAccess = false,
            expectedDurationSeconds = 1,
        ),
        ServiceRoutine(
            id = "clear_dtcs",
            name = "Clear stored faults",
            description = "Clears DTCs and freeze-frame data (UDS service 0x14). " +
                "Also resets readiness monitors, so an emissions test will need a " +
                "full drive cycle afterwards.",
            moduleCode = "*",
            risk = RoutineRisk.LOW,
            preconditions = listOf(
                "Record the faults first - clearing discards the freeze frames " +
                    "that explain them",
            ),
            requiresSecurityAccess = false,
            expectedDurationSeconds = 3,
        ),
    )

    /** Runs one of [standardOperations]. */
    suspend fun runStandard(
        routine: ServiceRoutine,
        module: DiscoveredModule,
    ): RoutineResult {
        val client = clientFor(module)
        return try {
            runCatching { client.startSession(DiagnosticSession.EXTENDED) }

            when (routine.id) {
                "ecu_reset_hard" -> {
                    client.ecuReset(ResetType.HARD)
                    RoutineResult.Completed(
                        ByteArray(0),
                        "${module.module.code} reset. Give it a few seconds to restart.",
                    )
                }
                "dtc_logging_off" -> {
                    client.controlDtcSetting(enabled = false)
                    RoutineResult.Completed(
                        ByteArray(0),
                        "Fault logging suspended in ${module.module.code}.",
                    )
                }
                "dtc_logging_on" -> {
                    client.controlDtcSetting(enabled = true)
                    RoutineResult.Completed(
                        ByteArray(0),
                        "Fault logging resumed in ${module.module.code}.",
                    )
                }
                "clear_dtcs" -> {
                    client.clearDiagnosticInformation()
                    RoutineResult.Completed(
                        ByteArray(0),
                        "Faults cleared in ${module.module.code}.",
                    )
                }
                else -> RoutineResult.Rejected(
                    "'${routine.id}' is not a standard operation. Use runCustom() with " +
                        "its RoutineControl identifier.",
                )
            }
        } catch (e: Exception) {
            RoutineResult.Failed(e.message ?: "Routine failed with no detail")
        }
    }

    /**
     * Runs an arbitrary RoutineControl identifier.
     *
     * For identifiers you have obtained from a trustworthy source. The caller
     * is responsible for knowing what the routine does - this drives it and
     * reports what came back.
     */
    suspend fun runCustom(
        module: DiscoveredModule,
        routineId: Int,
        options: ByteArray = ByteArray(0),
        securityAccess: com.anthonyrohde.f250scan.core.ford.SecurityAccessManager? = null,
        pollForResults: Boolean = true,
        timeoutMillis: Long = 20_000,
    ): RoutineResult {
        val client = clientFor(module)

        return try {
            client.startSession(DiagnosticSession.EXTENDED)
            securityAccess?.requestAccess(client)

            logger?.invoke(
                "Starting routine 0x${routineId.toString(16).uppercase()} on " +
                    module.module.code,
            )
            val started = client.routineControl(
                RoutineControlType.START,
                routineId,
                options,
                timeoutMillis,
            )

            if (!pollForResults) {
                return RoutineResult.Completed(started, "Routine started: ${started.toHex(" ")}")
            }

            // Many routines return immediately and require a separate results
            // request once they have finished running.
            val results = runCatching {
                client.routineControl(
                    RoutineControlType.REQUEST_RESULTS,
                    routineId,
                    timeoutMillis = timeoutMillis,
                )
            }.getOrNull()

            RoutineResult.Completed(
                results ?: started,
                "Routine completed. Response: ${(results ?: started).toHex(" ")}",
            )
        } catch (e: Exception) {
            RoutineResult.Failed(e.message ?: "Routine failed with no detail")
        }
    }

    /** Attempts to stop a running routine. */
    suspend fun stopCustom(module: DiscoveredModule, routineId: Int): RoutineResult = try {
        val response = clientFor(module)
            .routineControl(RoutineControlType.STOP, routineId)
        RoutineResult.Completed(response, "Stop requested.")
    } catch (e: Exception) {
        RoutineResult.Failed(e.message ?: "Could not stop the routine")
    }

    private fun clientFor(module: DiscoveredModule) = UdsClient(
        channel,
        module.module.requestId,
        module.module.responseId,
        module.module.code,
        logger,
    )
}
