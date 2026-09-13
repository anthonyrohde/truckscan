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
    /**
     * False when the bus showed no traffic at all when it was selected.
     *
     * Worth carrying up to the UI. Probing 256 addresses on a sleeping bus
     * takes the better part of a minute and cannot succeed, and "no modules
     * answered" is a very different report when the bus was never awake.
     */
    val busHadTraffic: Boolean = true,
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
        // Bus selection is its own exchange - candidate sequences, a traffic
        // probe - and must not interleave with anything else on this adapter,
        // including a live-data poll that happens to be running at the same
        // time. See ElmAdapter.exclusive.
        val selection = adapter.exclusive { adapter.selectBus(bus) }
        logger?.invoke(
            "Scanning ${bus.displayName} via ${selection.sequenceLabel}" +
                if (selection.trafficObserved) " - traffic seen" else "",
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
                DiscoveryProgress(
                    bus, index + 1, addresses.size, found.size, requestId,
                    busHadTraffic = selection.trafficObserved,
                ),
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
        // Every known address, not just the ones whose profile lists this bus.
        //
        // `expectedBuses` says where a module physically sits in the loom, and
        // using it to decide where to look was wrong. On a 2022 F-250 the
        // gateway presents modules on the powertrain bus regardless: the parking
        // aid module and the SYNC module are documented as MS-CAN and HS-CAN2,
        // and both answer on HS-CAN1 - measured, with a TesterPresent to 736 and
        // 7D0 returning 73E and 7D8. Filtering by the profile meant a quick scan
        // never asked, so they could not be found on the only bus that can reach
        // them.
        //
        // Twenty-odd addresses instead of ten is a second of scanning. Not
        // asking is a module that does not exist as far as the app is concerned.
        val addresses = VehicleProfiles.SUPER_DUTY_2022
            .map { it.requestId }
            .distinct()
        // A more generous wait than the full sweep uses, because the cost of
        // waiting is per address and there are twenty-odd here rather than 256.
        // Measured on the truck: a module answers in 56-64 ms and an empty
        // address is ruled out in 120-137 ms, so 150 was only about twice the
        // observed answer time. That is a thin margin on a bus with an engine
        // running on it, and the penalty for being wrong is a module that
        // silently does not exist.
        return scanBus(bus, addresses, probeTimeoutMillis = 300, onProgress = onProgress)
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
