package com.anthonyrohde.truckscan.core.session

import com.anthonyrohde.truckscan.core.dtc.Dtc
import com.anthonyrohde.truckscan.core.adapter.BusRouter
import com.anthonyrohde.truckscan.core.isotp.IsoTpChannel
import com.anthonyrohde.truckscan.core.uds.UdsClient
import com.anthonyrohde.truckscan.core.uds.UdsNegativeResponseException

/** Faults read from one module. */
data class ModuleDtcResult(
    val module: DiscoveredModule,
    val dtcs: List<Dtc>,
    /** Set when the module could not be read, with a human-readable reason. */
    val error: String? = null,
) {
    val hasFaults: Boolean get() = dtcs.isNotEmpty()
    val confirmedCount: Int get() = dtcs.count { it.status.confirmed }
    val pendingCount: Int get() = dtcs.count { it.status.pending && !it.status.confirmed }
}

/** A whole-vehicle fault scan. */
data class VehicleDtcScan(
    val results: List<ModuleDtcResult>,
    val scannedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    val allDtcs: List<Dtc> get() = results.flatMap { it.dtcs }
    val modulesWithFaults: List<ModuleDtcResult> get() = results.filter { it.hasFaults }
    val totalFaults: Int get() = allDtcs.size
    val unreadableModules: List<ModuleDtcResult> get() = results.filter { it.error != null }

    /** Plain-text report, for sharing or attaching to a forum post. */
    fun toReport(): String = buildString {
        appendLine("Fault scan - ${results.size} module(s), $totalFaults fault(s)")
        appendLine()
        if (modulesWithFaults.isEmpty()) {
            appendLine("No faults stored in any module that answered.")
        }
        for (result in modulesWithFaults) {
            appendLine("${result.module.module.code} (${result.module.module.addressLabel}) " +
                "on ${result.module.bus.displayName}")
            for (dtc in result.dtcs) {
                appendLine("  ${dtc.displayCode}  ${dtc.description}")
                appendLine("      status: ${dtc.status.describe()}")
            }
            appendLine()
        }
        if (unreadableModules.isNotEmpty()) {
            appendLine("Modules that could not be read:")
            unreadableModules.forEach {
                appendLine("  ${it.module.module.code}: ${it.error}")
            }
        }
    }
}

/**
 * Reads and clears faults across modules.
 *
 * Reads service 0x19 (ReadDTCInformation) and falls back to legacy OBD-II mode
 * 03 for modules that do not implement it. The fallback matters on a mixed-age
 * vehicle: a module that only speaks mode 03 would otherwise silently report
 * no faults, which is the worst possible failure for a diagnostic tool.
 */
class DtcScanner(
    private val channel: IsoTpChannel,
    private val busRouter: BusRouter? = null,
    private val logger: ((String) -> Unit)? = null,
) {
    suspend fun readModule(module: DiscoveredModule): ModuleDtcResult {
        // A whole-vehicle scan walks modules across several buses, so the
        // adapter has to follow the module rather than stay where discovery
        // left it.
        try {
            busRouter?.ensureBus(module.bus)
        } catch (e: Exception) {
            return ModuleDtcResult(module, emptyList(), e.message ?: "Bus unavailable")
        }
        val client = clientFor(module)

        // Preferred path: UDS, which gives us status bytes and failure types.
        try {
            val body = client.readDtcsByStatusMask(STATUS_MASK_ALL)
            return ModuleDtcResult(module, Dtc.parseDtcListResponse(body))
        } catch (e: UdsNegativeResponseException) {
            logger?.invoke("${module.module.code}: service 0x19 rejected (${e.message})")
        } catch (e: Exception) {
            logger?.invoke("${module.module.code}: service 0x19 failed (${e.message})")
        }

        // Fallback: legacy mode 03, two-byte codes and no status detail.
        return try {
            val body = channel.request(
                module.module.requestId,
                module.module.responseId,
                byteArrayOf(0x03),
                3_000,
            )
            if (body.isEmpty() || body[0] != 0x43.toByte()) {
                ModuleDtcResult(module, emptyList(), "Module did not answer mode 03 either")
            } else {
                ModuleDtcResult(module, Dtc.parseMode03Response(body.copyOfRange(1, body.size)))
            }
        } catch (e: Exception) {
            ModuleDtcResult(module, emptyList(), e.message ?: "Unreadable")
        }
    }

    suspend fun scanAll(
        modules: List<DiscoveredModule>,
        onProgress: ((done: Int, total: Int, current: DiscoveredModule) -> Unit)? = null,
    ): VehicleDtcScan {
        val results = mutableListOf<ModuleDtcResult>()
        modules.forEachIndexed { index, module ->
            onProgress?.invoke(index, modules.size, module)
            results += readModule(module)
        }
        return VehicleDtcScan(results)
    }

    /** Freeze-frame data captured when [dtc] set, if the module stored any. */
    suspend fun readSnapshot(module: DiscoveredModule, dtc: Dtc): ByteArray? {
        if (dtc.rawBytes.size < 3) return null
        runCatching { busRouter?.ensureBus(module.bus) }.onFailure { return null }
        return runCatching {
            clientFor(module).readDtcSnapshot(dtc.rawBytes.copyOfRange(0, 3))
        }.getOrNull()
    }

    /**
     * Clears stored faults in one module.
     *
     * Worth knowing before calling: clearing also discards freeze-frame data
     * and resets readiness monitors, so the underlying fault becomes harder to
     * diagnose. The UI confirms before reaching this.
     */
    suspend fun clearModule(module: DiscoveredModule): Result<Unit> = runCatching {
        busRouter?.ensureBus(module.bus)
        clientFor(module).clearDiagnosticInformation()
        logger?.invoke("Cleared faults in ${module.module.code}")
        Unit
    }

    /** Clears every module, reporting per-module outcomes rather than stopping. */
    suspend fun clearAll(modules: List<DiscoveredModule>): Map<DiscoveredModule, Result<Unit>> =
        modules.associateWith { clearModule(it) }

    private fun clientFor(module: DiscoveredModule) = UdsClient(
        channel,
        module.module.requestId,
        module.module.responseId,
        module.module.code,
        logger,
    )

    companion object {
        /** Every status bit, so historic and pending codes come back too. */
        const val STATUS_MASK_ALL = 0xFF

        /** Confirmed faults only - what a workshop would call "stored codes". */
        const val STATUS_MASK_CONFIRMED = 0x08
    }
}
