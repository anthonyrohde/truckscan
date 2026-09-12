package com.anthonyrohde.truckscan.core.session

import com.anthonyrohde.truckscan.core.adapter.AdapterIdentity
import com.anthonyrohde.truckscan.core.adapter.BusRouter
import com.anthonyrohde.truckscan.core.adapter.CanBus
import com.anthonyrohde.truckscan.core.adapter.ElmAdapter
import com.anthonyrohde.truckscan.core.ford.SecurityAccessManager
import com.anthonyrohde.truckscan.core.isotp.IsoTpChannel
import com.anthonyrohde.truckscan.core.isotp.IsoTpConfig
import com.anthonyrohde.truckscan.core.pid.PidCatalog
import com.anthonyrohde.truckscan.core.trace.LearnedProfile
import com.anthonyrohde.truckscan.core.trace.securityAccessManager
import com.anthonyrohde.truckscan.core.transport.ObdTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Connecting(val step: String) : ConnectionState()
    data class Connected(
        val identity: AdapterIdentity,
        val activeBus: CanBus,
        val busTrafficSeen: Boolean,
    ) : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}

/**
 * Single entry point for the app.
 *
 * Owns the adapter, the ISO-TP channel and the feature objects built on them,
 * and exposes connection state as a flow so the UI can follow it. Everything
 * below this is independent of Android, so the same engine drives the app, the
 * tests and the simulator.
 */
class DiagnosticEngine(
    private val transport: ObdTransport,
    isoTpConfig: IsoTpConfig = IsoTpConfig(),
    private val logger: ((String) -> Unit)? = null,
) {
    val adapter = ElmAdapter(transport, logger)
    val channel = IsoTpChannel(adapter, isoTpConfig, logger)

    /**
     * Keeps the adapter on the bus each module actually sits on.
     *
     * Shared by every feature object, because a module is unreachable unless
     * its bus is selected and callers address modules across buses freely.
     */
    val busRouter = BusRouter(adapter, logger)

    val discovery = ModuleDiscovery(adapter, channel, logger)
    val dtcScanner = DtcScanner(channel, busRouter, logger)
    val liveData = LiveDataPoller(channel, busRouter, logger)
    val asBuiltReader = AsBuiltReader(channel, busRouter, logger)
    val routines = ServiceRoutineRunner(channel, busRouter, logger)

    /**
     * Security access, used by service routines that require an authenticated
     * session. Injected so a derivation worked out later can be dropped in.
     */
    var securityAccessManager: SecurityAccessManager = SecurityAccessManager(logger = logger)

    /**
     * Facts learned from an imported bus capture, if one has been loaded.
     *
     * Setting this replaces guesswork with measurement in three places: As-Built
     * reads go straight to the identifiers known to exist, the checksum
     * algorithm is taken from real blocks, and any captured security handshake
     * becomes available for replay.
     */
    private val _learnedProfile = MutableStateFlow<LearnedProfile?>(null)
    val learnedProfile: StateFlow<LearnedProfile?> = _learnedProfile.asStateFlow()

    fun applyLearnedProfile(profile: LearnedProfile?) {
        _learnedProfile.value = profile
        securityAccessManager = profile?.securityAccessManager(logger)
            ?: SecurityAccessManager(logger = logger)
        logger?.invoke(
            profile?.let { "Applied learned profile: ${it.modules.size} module(s)" }
                ?: "Cleared learned profile",
        )
    }

    /**
     * Reads a module's configuration, using the learned profile when it covers
     * this module and falling back to the discovery sweep when it does not.
     */
    suspend fun snapshotAsBuilt(
        module: DiscoveredModule,
        onProgress: ((AsBuiltScanProgress) -> Unit)? = null,
    ): com.anthonyrohde.truckscan.core.ford.AsBuiltSnapshot {
        val learned = _learnedProfile.value?.module(module.module.requestId)
        return if (learned != null && learned.readOrder().isNotEmpty()) {
            logger?.invoke(
                "Using ${learned.readOrder().size} learned identifier(s) for " +
                    "${module.module.code} instead of a range sweep",
            )
            asBuiltReader.snapshotFromProfile(module, learned, onProgress)
        } else {
            asBuiltReader.snapshot(module, onProgress = onProgress)
        }
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredModules = MutableStateFlow<List<DiscoveredModule>>(emptyList())
    val discoveredModules: StateFlow<List<DiscoveredModule>> = _discoveredModules.asStateFlow()

    /** Opens the adapter and brings up [initialBus]. */
    suspend fun connect(initialBus: CanBus = CanBus.HS_CAN1): Result<AdapterIdentity> = try {
        _connectionState.value = ConnectionState.Connecting("Opening adapter")
        val identity = adapter.connect()

        _connectionState.value = ConnectionState.Connecting("Selecting ${initialBus.displayName}")
        val selection = adapter.selectBus(initialBus)

        _connectionState.value = ConnectionState.Connected(
            identity = identity,
            activeBus = selection.bus,
            busTrafficSeen = selection.trafficObserved,
        )
        Result.success(identity)
    } catch (e: Exception) {
        _connectionState.value = ConnectionState.Failed(e.message ?: "Connection failed")
        Result.failure(e)
    }

    suspend fun disconnect() {
        busRouter.reset()
        runCatching { adapter.disconnect() }
        _discoveredModules.value = emptyList()
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Buses worth scanning with the connected adapter.
     *
     * An ELM327-class adapter gets HS-CAN1 only, because that is all it is
     * wired to; offering the rest would produce empty scans and the impression
     * the truck has no body modules.
     */
    fun scannableBuses(): List<CanBus> =
        if (adapter.adapterIdentity.supportsMultiBus) {
            listOf(CanBus.HS_CAN1, CanBus.MS_CAN, CanBus.HS_CAN2, CanBus.HS_CAN3)
        } else {
            listOf(CanBus.HS_CAN1)
        }

    /** Quick scan of known addresses on every reachable bus. */
    suspend fun quickScanVehicle(
        onProgress: ((DiscoveryProgress) -> Unit)? = null,
    ): List<DiscoveredModule> {
        val found = mutableListOf<DiscoveredModule>()
        for (bus in scannableBuses()) {
            found += runCatching { discovery.quickScan(bus, onProgress) }.getOrDefault(emptyList())
        }
        // The gateway can make one module answer on two buses. Keep the first,
        // so the UI shows each module once rather than implying duplicates.
        val deduplicated = found.distinctBy { it.module.requestId }
        _discoveredModules.value = deduplicated
        return deduplicated
    }

    /** Exhaustive sweep of every address on every reachable bus. */
    suspend fun fullScanVehicle(
        onProgress: ((DiscoveryProgress) -> Unit)? = null,
    ): List<DiscoveredModule> {
        val results = discovery.scanBuses(scannableBuses(), onProgress)
        val deduplicated = results.values.flatten().distinctBy { it.module.requestId }
        _discoveredModules.value = deduplicated
        return deduplicated
    }

    /** Scans faults across whatever discovery found, running it first if needed. */
    suspend fun scanFaults(
        onProgress: ((done: Int, total: Int, current: DiscoveredModule) -> Unit)? = null,
    ): VehicleDtcScan {
        val modules = _discoveredModules.value.ifEmpty { quickScanVehicle() }
        return dtcScanner.scanAll(modules, onProgress)
    }

    /**
     * Reads control module voltage, for the pre-write safety check.
     *
     * Returns null rather than a default when it cannot be read: a made-up
     * voltage would defeat the check it exists to support.
     */
    suspend fun readControlModuleVoltage(): Double? =
        liveData.sampleOnce(listOf(PidCatalog.CONTROL_MODULE_VOLTAGE))
            .values[PidCatalog.CONTROL_MODULE_VOLTAGE.key]
            ?.value

    /** The VIN, from whichever discovered module reports one. */
    fun vin(): String? = _discoveredModules.value
        .firstNotNullOfOrNull { it.identification?.vin }
        ?.takeIf { it.length == 17 }
}
