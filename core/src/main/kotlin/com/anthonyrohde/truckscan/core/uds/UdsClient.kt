package com.anthonyrohde.truckscan.core.uds

import com.anthonyrohde.truckscan.core.isotp.IsoTpChannel
import com.anthonyrohde.truckscan.core.util.toHex
import com.anthonyrohde.truckscan.core.util.u8

/**
 * A UDS conversation with one module.
 *
 * Each instance is bound to a request/response CAN ID pair, so one is created
 * per module rather than shared. All the service methods funnel through
 * [execute], which is where the response-pending dance and negative-response
 * translation live.
 */
class UdsClient(
    private val channel: IsoTpChannel,
    val txId: Int,
    val rxId: Int,
    val moduleName: String,
    private val logger: ((String) -> Unit)? = null,
) {
    companion object {
        /** Bit set on a sub-function to tell the module not to bother replying. */
        const val SUPPRESS_POSITIVE_RESPONSE = 0x80

        private const val NEGATIVE_RESPONSE_SID = 0x7F

        /**
         * How many consecutive 0x78 "response pending" replies to tolerate.
         *
         * A module doing real work - a DPF regeneration request, an As-Built
         * write, a security handshake - legitimately stalls the tester for
         * several seconds. Capping this stops a wedged module from hanging the
         * UI forever.
         */
        const val MAX_PENDING_RESPONSES = 30

        const val DEFAULT_TIMEOUT_MS = 2_000L
        /** Writes and routines get longer, since flash takes time to settle. */
        const val EXTENDED_TIMEOUT_MS = 10_000L
    }

    // ------------------------------------------------------------ core exchange

    /**
     * Sends a request and returns the response payload with the SID stripped.
     *
     * @throws UdsNegativeResponseException if the module rejects the request.
     */
    suspend fun execute(
        service: UdsService,
        requestData: ByteArray = ByteArray(0),
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ): ByteArray = channel.exclusive { executeLocked(service, requestData, timeoutMillis) }

    /**
     * The whole conversation, not just the first request, is one exchange: a
     * "response pending" can retry `channel.receive` several times before the
     * real answer arrives, and nothing else may touch the adapter's header or
     * filter while that is happening. [execute] holds [IsoTpChannel.exclusive]
     * for the whole of this function - see [ElmAdapter.exclusive] for why that
     * matters: a fault scan and a live-data poll running at once on a real
     * truck corrupted each other's requests this way.
     *
     * Kept as a separate function using plain `return` throughout, rather than
     * inlined into the `exclusive { }` block with a labelled one - a
     * `while (true)` as a lambda's last statement has no useful type of its
     * own, and this sidesteps asking the compiler to infer one through two
     * layers of generic delegation.
     */
    private suspend fun executeLocked(
        service: UdsService,
        requestData: ByteArray,
        timeoutMillis: Long,
    ): ByteArray {
        val request = byteArrayOf(service.sid.toByte()) + requestData
        var response = channel.request(txId, rxId, request, timeoutMillis)

        var pendingCount = 0
        while (true) {
            if (response.isEmpty()) {
                throw UdsProtocolException("Empty response from $moduleName")
            }

            // Negative response: 0x7F, echoed SID, NRC.
            if (response.u8(0) == NEGATIVE_RESPONSE_SID) {
                if (response.size < 3) {
                    throw UdsProtocolException(
                        "Truncated negative response from $moduleName: ${response.toHex()}",
                    )
                }
                val echoedSid = response.u8(1)
                val nrc = response.u8(2)

                if (nrc == NegativeResponseCode.RESPONSE_PENDING.code) {
                    if (++pendingCount > MAX_PENDING_RESPONSES) {
                        throw UdsProtocolException(
                            "$moduleName kept replying 'response pending' " +
                                "($MAX_PENDING_RESPONSES times) without finishing " +
                                "${service.label}.",
                        )
                    }
                    log("$moduleName: response pending ($pendingCount), waiting")
                    response = channel.receive(rxId, timeoutMillis.coerceAtLeast(2_000))
                    continue
                }

                throw UdsNegativeResponseException(echoedSid, nrc, moduleName)
            }

            if (response.u8(0) != service.positiveResponseSid) {
                throw UdsProtocolException(
                    "$moduleName answered ${service.label} with SID " +
                        "0x${response.u8(0).toString(16).uppercase()}, expected " +
                        "0x${service.positiveResponseSid.toString(16).uppercase()}",
                )
            }

            return response.copyOfRange(1, response.size)
        }
    }

    /** Like [execute] but returns null on a negative response instead of throwing.
     *  Used by the discovery scans, where "not supported" is an ordinary result. */
    suspend fun tryExecute(
        service: UdsService,
        requestData: ByteArray = ByteArray(0),
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ): ByteArray? = runCatching { execute(service, requestData, timeoutMillis) }.getOrNull()

    // ---------------------------------------------------------------- services

    /** Service 0x10. Returns the P2/P2* timing parameters if the module sends them. */
    suspend fun startSession(session: DiagnosticSession): ByteArray =
        execute(UdsService.DIAGNOSTIC_SESSION_CONTROL, byteArrayOf(session.code.toByte()))

    /** Service 0x3E. Keeps a non-default session alive; must be sent every ~2 s. */
    suspend fun testerPresent(suppressResponse: Boolean = true) {
        if (suppressResponse) {
            channel.exclusive {
                channel.send(
                    txId,
                    byteArrayOf(
                        UdsService.TESTER_PRESENT.sid.toByte(),
                        SUPPRESS_POSITIVE_RESPONSE.toByte(),
                    ),
                )
            }
        } else {
            execute(UdsService.TESTER_PRESENT, byteArrayOf(0x00))
        }
    }

    /** Service 0x22. */
    suspend fun readDataByIdentifier(did: Int, timeoutMillis: Long = DEFAULT_TIMEOUT_MS): ByteArray {
        val body = execute(
            UdsService.READ_DATA_BY_IDENTIFIER,
            byteArrayOf((did shr 8).toByte(), did.toByte()),
            timeoutMillis,
        )
        if (body.size < 2) {
            throw UdsProtocolException(
                "$moduleName returned a $did read with no identifier echo: ${body.toHex()}",
            )
        }
        val echoed = (body.u8(0) shl 8) or body.u8(1)
        if (echoed != did) {
            throw UdsProtocolException(
                "$moduleName echoed DID 0x${echoed.toString(16).uppercase()} " +
                    "for a request of 0x${did.toString(16).uppercase()}",
            )
        }
        return body.copyOfRange(2, body.size)
    }

    /** Service 0x22, tolerant variant for sweeping unknown identifier ranges. */
    suspend fun tryReadDataByIdentifier(did: Int, timeoutMillis: Long = 700): ByteArray? =
        runCatching { readDataByIdentifier(did, timeoutMillis) }.getOrNull()

    /** Service 0x2E. */
    suspend fun writeDataByIdentifier(
        did: Int,
        data: ByteArray,
        timeoutMillis: Long = EXTENDED_TIMEOUT_MS,
    ): ByteArray = execute(
        UdsService.WRITE_DATA_BY_IDENTIFIER,
        byteArrayOf((did shr 8).toByte(), did.toByte()) + data,
        timeoutMillis,
    )

    /** Service 0x19 0x02. */
    suspend fun readDtcsByStatusMask(statusMask: Int = 0xFF): ByteArray = execute(
        UdsService.READ_DTC_INFORMATION,
        byteArrayOf(DtcReportType.BY_STATUS_MASK.code.toByte(), statusMask.toByte()),
        timeoutMillis = 5_000,
    )

    /** Service 0x19 0x01. Cheap way to tell whether a module has faults at all. */
    suspend fun readDtcCount(statusMask: Int = 0xFF): ByteArray = execute(
        UdsService.READ_DTC_INFORMATION,
        byteArrayOf(DtcReportType.NUMBER_BY_STATUS_MASK.code.toByte(), statusMask.toByte()),
    )

    /** Service 0x19 0x04 - freeze frame data captured when the DTC set. */
    suspend fun readDtcSnapshot(dtcMaskThreeBytes: ByteArray, recordNumber: Int = 0xFF): ByteArray {
        require(dtcMaskThreeBytes.size == 3) { "A UDS DTC mask is 3 bytes" }
        return execute(
            UdsService.READ_DTC_INFORMATION,
            byteArrayOf(DtcReportType.SNAPSHOT_BY_DTC.code.toByte()) +
                dtcMaskThreeBytes + byteArrayOf(recordNumber.toByte()),
            timeoutMillis = 5_000,
        )
    }

    /** Service 0x14. `0xFFFFFF` clears every group. */
    suspend fun clearDiagnosticInformation(groupOfDtc: Int = 0xFFFFFF): ByteArray = execute(
        UdsService.CLEAR_DIAGNOSTIC_INFORMATION,
        byteArrayOf(
            (groupOfDtc shr 16).toByte(),
            (groupOfDtc shr 8).toByte(),
            groupOfDtc.toByte(),
        ),
        timeoutMillis = EXTENDED_TIMEOUT_MS,
    )

    /** Service 0x27, seed request. Odd sub-function = request seed. */
    suspend fun requestSeed(level: Int): ByteArray =
        execute(UdsService.SECURITY_ACCESS, byteArrayOf(level.toByte()), 5_000)

    /** Service 0x27, key submission. Even sub-function = send key. */
    suspend fun sendKey(level: Int, key: ByteArray): ByteArray =
        execute(UdsService.SECURITY_ACCESS, byteArrayOf((level + 1).toByte()) + key, 5_000)

    /** Service 0x31. */
    suspend fun routineControl(
        type: RoutineControlType,
        routineId: Int,
        options: ByteArray = ByteArray(0),
        timeoutMillis: Long = EXTENDED_TIMEOUT_MS,
    ): ByteArray = execute(
        UdsService.ROUTINE_CONTROL,
        byteArrayOf(type.code.toByte(), (routineId shr 8).toByte(), routineId.toByte()) + options,
        timeoutMillis,
    )

    /** Service 0x2F. */
    suspend fun ioControl(
        did: Int,
        controlParameter: Int,
        controlState: ByteArray = ByteArray(0),
    ): ByteArray = execute(
        UdsService.INPUT_OUTPUT_CONTROL_BY_IDENTIFIER,
        byteArrayOf((did shr 8).toByte(), did.toByte(), controlParameter.toByte()) + controlState,
        EXTENDED_TIMEOUT_MS,
    )

    /** Service 0x11. */
    suspend fun ecuReset(type: ResetType = ResetType.HARD): ByteArray =
        execute(UdsService.ECU_RESET, byteArrayOf(type.code.toByte()), EXTENDED_TIMEOUT_MS)

    /** Service 0x85. Suspends DTC logging so test routines do not set codes. */
    suspend fun controlDtcSetting(enabled: Boolean): ByteArray = execute(
        UdsService.CONTROL_DTC_SETTING,
        byteArrayOf(if (enabled) 0x01 else 0x02),
    )

    private fun log(message: String) = logger?.invoke(message)
}
