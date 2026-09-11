package com.anthonyrohde.f250scan.core.uds

/** UDS (ISO 14229-1) service identifiers we implement. */
enum class UdsService(val sid: Int, val label: String) {
    DIAGNOSTIC_SESSION_CONTROL(0x10, "DiagnosticSessionControl"),
    ECU_RESET(0x11, "ECUReset"),
    CLEAR_DIAGNOSTIC_INFORMATION(0x14, "ClearDiagnosticInformation"),
    READ_DTC_INFORMATION(0x19, "ReadDTCInformation"),
    READ_DATA_BY_IDENTIFIER(0x22, "ReadDataByIdentifier"),
    READ_MEMORY_BY_ADDRESS(0x23, "ReadMemoryByAddress"),
    SECURITY_ACCESS(0x27, "SecurityAccess"),
    COMMUNICATION_CONTROL(0x28, "CommunicationControl"),
    WRITE_DATA_BY_IDENTIFIER(0x2E, "WriteDataByIdentifier"),
    INPUT_OUTPUT_CONTROL_BY_IDENTIFIER(0x2F, "InputOutputControlByIdentifier"),
    ROUTINE_CONTROL(0x31, "RoutineControl"),
    TESTER_PRESENT(0x3E, "TesterPresent"),
    CONTROL_DTC_SETTING(0x85, "ControlDTCSetting"),
    ;

    /** A positive reply echoes the SID with bit 6 set. */
    val positiveResponseSid: Int get() = sid or 0x40

    companion object {
        private val BY_SID = entries.associateBy { it.sid }
        fun fromSid(sid: Int): UdsService? = BY_SID[sid]
    }
}

/** Diagnostic session types (service 0x10 sub-function). */
enum class DiagnosticSession(val code: Int, val label: String) {
    DEFAULT(0x01, "Default"),
    PROGRAMMING(0x02, "Programming"),
    EXTENDED(0x03, "Extended diagnostic"),
    SAFETY_SYSTEM(0x04, "Safety system diagnostic"),
    ;
}

/** ECUReset sub-functions (service 0x11). */
enum class ResetType(val code: Int, val label: String) {
    HARD(0x01, "Hard reset"),
    KEY_OFF_ON(0x02, "Key off/on reset"),
    SOFT(0x03, "Soft reset"),
}

/** RoutineControl sub-functions (service 0x31). */
enum class RoutineControlType(val code: Int, val label: String) {
    START(0x01, "Start routine"),
    STOP(0x02, "Stop routine"),
    REQUEST_RESULTS(0x03, "Request routine results"),
}

/** ReadDTCInformation sub-functions (service 0x19). */
enum class DtcReportType(val code: Int, val label: String) {
    NUMBER_BY_STATUS_MASK(0x01, "Number of DTCs by status mask"),
    BY_STATUS_MASK(0x02, "DTCs by status mask"),
    SNAPSHOT_IDENTIFICATION(0x03, "Snapshot record identification"),
    SNAPSHOT_BY_DTC(0x04, "Snapshot records by DTC"),
    EXTENDED_DATA_BY_DTC(0x06, "Extended data records by DTC"),
    SUPPORTED_DTCS(0x0A, "All supported DTCs"),
}

/**
 * Negative response codes (ISO 14229-1 Annex A).
 *
 * The [advice] strings exist because a bare "NRC 0x33" tells a user nothing.
 * Most real-world dead ends when talking to a Ford module are one of
 * [SECURITY_ACCESS_DENIED], [CONDITIONS_NOT_CORRECT] or
 * [SUB_FUNCTION_NOT_SUPPORTED_IN_ACTIVE_SESSION], and each needs a different
 * fix from the person holding the phone.
 */
enum class NegativeResponseCode(val code: Int, val label: String, val advice: String) {
    GENERAL_REJECT(0x10, "General reject", "Module refused the request without saying why."),
    SERVICE_NOT_SUPPORTED(0x11, "Service not supported", "This module does not implement that service."),
    SUB_FUNCTION_NOT_SUPPORTED(0x12, "Sub-function not supported", "The module does not support that variant of the request."),
    INCORRECT_MESSAGE_LENGTH(0x13, "Incorrect message length or invalid format", "Request was malformed - this is a bug, please report it."),
    RESPONSE_TOO_LONG(0x14, "Response too long", "The reply will not fit in a single transport message."),
    BUSY_REPEAT_REQUEST(0x21, "Busy, repeat request", "Module is busy; the request will be retried."),
    CONDITIONS_NOT_CORRECT(0x22, "Conditions not correct", "Vehicle state blocks this. Usually means engine running when it must be off, or the reverse."),
    REQUEST_SEQUENCE_ERROR(0x24, "Request sequence error", "A prerequisite step was skipped - typically security access or a session change."),
    NO_RESPONSE_FROM_SUBNET(0x25, "No response from subnet component", "A module behind the gateway did not answer."),
    FAILURE_PREVENTS_EXECUTION(0x26, "Failure prevents execution of requested action", "An existing fault in the module blocks this request."),
    REQUEST_OUT_OF_RANGE(0x31, "Request out of range", "That identifier or address does not exist on this module."),
    SECURITY_ACCESS_DENIED(0x33, "Security access denied", "The module requires an authenticated session before it will allow this."),
    INVALID_KEY(0x35, "Invalid key", "The security key we computed was wrong for this module."),
    EXCEEDED_NUMBER_OF_ATTEMPTS(0x36, "Exceeded number of attempts", "Too many failed security attempts. The module is locked out - cycle the ignition."),
    REQUIRED_TIME_DELAY_NOT_EXPIRED(0x37, "Required time delay not expired", "Module is enforcing a lockout delay after a failed attempt. Wait and retry."),
    UPLOAD_DOWNLOAD_NOT_ACCEPTED(0x70, "Upload/download not accepted", "Module refused to begin a data transfer."),
    TRANSFER_DATA_SUSPENDED(0x71, "Transfer data suspended", "The transfer was interrupted."),
    GENERAL_PROGRAMMING_FAILURE(0x72, "General programming failure", "The module failed while writing. Verify its configuration before driving."),
    WRONG_BLOCK_SEQUENCE_COUNTER(0x73, "Wrong block sequence counter", "Transfer blocks arrived out of order."),
    RESPONSE_PENDING(0x78, "Request received, response pending", "Module is working on it; we are waiting."),
    SUB_FUNCTION_NOT_SUPPORTED_IN_ACTIVE_SESSION(0x7E, "Sub-function not supported in active session", "Needs an extended or programming session first."),
    SERVICE_NOT_SUPPORTED_IN_ACTIVE_SESSION(0x7F, "Service not supported in active session", "Needs an extended or programming session first."),
    RPM_TOO_HIGH(0x81, "RPM too high", "Shut the engine off and retry."),
    RPM_TOO_LOW(0x82, "RPM too low", "The engine needs to be running for this."),
    ENGINE_IS_RUNNING(0x83, "Engine is running", "Turn the engine off and retry."),
    ENGINE_IS_NOT_RUNNING(0x84, "Engine is not running", "Start the engine and retry."),
    VEHICLE_SPEED_TOO_HIGH(0x87, "Vehicle speed too high", "Bring the vehicle to a stop."),
    VOLTAGE_TOO_HIGH(0x92, "Voltage too high", "Battery voltage is out of range for this operation."),
    VOLTAGE_TOO_LOW(0x93, "Voltage too low", "Battery voltage is too low. Put a charger on it before programming."),
    ;

    companion object {
        private val BY_CODE = entries.associateBy { it.code }
        fun fromCode(code: Int): NegativeResponseCode? = BY_CODE[code]

        fun describe(code: Int): String =
            fromCode(code)?.let { "${it.label} (0x${code.toString(16).uppercase()})" }
                ?: "Unknown negative response 0x${code.toString(16).uppercase()}"
    }
}

/** Thrown when a module answers a request with 0x7F. */
class UdsNegativeResponseException(
    val service: Int,
    val nrc: Int,
    val moduleName: String? = null,
) : Exception(buildMessage(service, nrc, moduleName)) {

    val code: NegativeResponseCode? = NegativeResponseCode.fromCode(nrc)

    companion object {
        private fun buildMessage(service: Int, nrc: Int, moduleName: String?): String {
            val serviceLabel = UdsService.fromSid(service)?.label
                ?: "service 0x${service.toString(16).uppercase()}"
            val where = moduleName?.let { " from $it" } ?: ""
            val known = NegativeResponseCode.fromCode(nrc)
            return if (known != null) {
                "$serviceLabel rejected$where: ${known.label} " +
                    "(0x${nrc.toString(16).uppercase()}). ${known.advice}"
            } else {
                "$serviceLabel rejected$where with unknown NRC " +
                    "0x${nrc.toString(16).uppercase()}"
            }
        }
    }
}

class UdsProtocolException(message: String) : Exception(message)
