package com.anthonyrohde.truckscan.core.session

import com.anthonyrohde.truckscan.core.adapter.CanBus
import com.anthonyrohde.truckscan.core.adapter.ElmAdapter
import com.anthonyrohde.truckscan.core.ford.FordModule
import com.anthonyrohde.truckscan.core.ford.ModuleCategory
import com.anthonyrohde.truckscan.core.ford.VehicleProfiles
import com.anthonyrohde.truckscan.core.isotp.IsoTpChannel
import com.anthonyrohde.truckscan.core.uds.UdsClient
import com.anthonyrohde.truckscan.core.uds.UdsNegativeResponseException
import com.anthonyrohde.truckscan.core.uds.UdsService

/** A module that actually answered, and where. */
data class DiscoveredModule(
    val module: FordModule,
    val bus: CanBus,
    /** True when this address is in the known profile, false when it is new. */
    val isKnown: Boolean,
    val identification: ModuleIdentification? = null,
    val dtcCount: Int? = null,
) {
    val displayName: String get() = if (isKnown) {
        "${module.code} - ${module.name}"
    } else {
        "Unknown module at ${module.addressLabel}"
    }
}

data class DiscoveryProgress(
    val bus: CanBus,
    val addressesProbed: Int,
    val addressesTotal: Int,
    val found: Int,
    val currentAddress: Int,
)

/**
 * Finds which modules are present and on which bus.
 *
 * This is the authority on vehicle content, deliberately in preference to the
 * static table in [VehicleProfiles]. Trim levels, options and the gateway's
 * routing all change what answers where, so the app asks rather than assumes.
 *
 * The probe is a UDS TesterPresent, which every UDS module answers and which
 * changes nothing in the vehicle. Addresses that reject it with a negative
 * response still count as present: a module that says "I will not do that"
 * has, by replying at all, told us it exists.
 */
class ModuleDiscovery(
    private val adapter: ElmAdapter,
    private val channel: IsoTpChannel,
    private val logger: ((String) -> Unit)? = null,
) {
    /**
     * Probes [addresses] on [bus].
     *
     * @param probeTimeoutMillis per-address wait. The default is tight on
     *   purpose: a full 256-address sweep at 2 s per miss would take eight
     *   minutes, and an absent module answers in well under 150 ms or not
     *   at all.
     */
    suspend fun scanBus(
        bus: CanBus,
        addresses: List<Int> = VehicleProfiles.discoveryAddresses(),
        probeTimeoutMillis: Long = 150,
        readIdentification: Boolean = true,
        onProgress: ((DiscoveryProgress) -> Unit)? = null,
    ): List<DiscoveredModule> {
        val selection = adapter.selectBus(bus)
        logger?.invoke(
            "Scanning ${bus.displayName} via ${selection.sequenceLabel}" +
                if (selection.trafficObserved) "" else " (bus quiet - is the ignition on?)",
        )

        val found = mutableListOf<DiscoveredModule>()

        addresses.forEachIndexed { index, requestId ->
            val responseId = requestId + 8
            val known = VehicleProfiles.moduleForRequestId(requestId)
            val module = known ?: FordModule(
                code = "UNK_${requestId.toString(16).uppercase()}",
                name = "Unidentified module",
                requestId = requestId,
                expectedBuses = listOf(bus),
                category = ModuleCategory.UNKNOWN,
            )

            val client = UdsClient(channel, requestId, responseId, module.code, logger)

            if (probe(client, probeTimeoutMillis)) {
                logger?.invoke("Found ${module.code} at ${module.addressLabel} on ${bus.displayName}")
                val identification = if (readIdentification) {
                    runCatching { ModuleIdentification.read(client, IDENTIFICATION_SUBSET) }
                        .getOrNull()
                } else {
                    null
                }
                found += DiscoveredModule(
                    module = module.copy(responseId = responseId),
                    bus = bus,
                    isKnown = known != null,
                    identification = identification,
                )
            }

            onProgress?.invoke(
                DiscoveryProgress(bus, index + 1, addresses.size, found.size, requestId),
            )
        }

        return found
    }

    /** Scans several buses, skipping any the adapter cannot reach. */
    suspend fun scanBuses(
        buses: List<CanBus>,
        onProgress: ((DiscoveryProgress) -> Unit)? = null,
    ): Map<CanBus, List<DiscoveredModule>> {
        val results = linkedMapOf<CanBus, List<DiscoveredModule>>()
        for (bus in buses) {
            if (bus != CanBus.HS_CAN1 && !adapter.adapterIdentity.supportsMultiBus) {
                logger?.invoke(adapter.adapterIdentity.multiBusLimitationMessage(bus))
                continue
            }
            results[bus] = runCatching {
                scanBus(bus, onProgress = onProgress)
            }.getOrElse { error ->
                logger?.invoke("Skipped ${bus.displayName}: ${error.message}")
                emptyList()
            }
        }
        return results
    }

    /**
     * Fast scan of only the known addresses for this vehicle.
     *
     * Around twenty probes rather than 256, so it finishes in a couple of
     * seconds. This is what the UI should run on connect; the full sweep is for
     * when someone is hunting a module the profile does not list.
     */
    suspend fun quickScan(
        bus: CanBus,
        onProgress: ((DiscoveryProgress) -> Unit)? = null,
    ): List<DiscoveredModule> {
        val addresses = VehicleProfiles.SUPER_DUTY_2022
            .filter { bus in it.expectedBuses }
            .map { it.requestId }
            .distinct()
        return scanBus(bus, addresses, onProgress = onProgress)
    }

    /**
     * Is anything at this address?
     *
     * A negative response counts as present, because only a real module bothers
     * to refuse. Absence shows up as a timeout, which surfaces here as an
     * exception from the ISO-TP layer.
     */
    private suspend fun probe(client: UdsClient, timeoutMillis: Long): Boolean = try {
        client.execute(UdsService.TESTER_PRESENT, byteArrayOf(0x00), timeoutMillis)
        true
    } catch (e: UdsNegativeResponseException) {
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        /**
         * Identification DIDs read during discovery.
         *
         * Kept short so a scan stays quick; the module detail screen reads the
         * full set on demand.
         */
        private val IDENTIFICATION_SUBSET = listOf(
            IdentificationDid.SPARE_PART_NUMBER,
            IdentificationDid.ECU_SOFTWARE_NUMBER,
            IdentificationDid.VIN,
        )
    }
}
