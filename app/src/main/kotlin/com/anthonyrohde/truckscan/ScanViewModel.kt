package com.anthonyrohde.truckscan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anthonyrohde.truckscan.core.adapter.CanBus
import com.anthonyrohde.truckscan.core.ford.AsBuiltSnapshot
import com.anthonyrohde.truckscan.core.pid.Pid
import com.anthonyrohde.truckscan.core.pid.PidCatalog
import com.anthonyrohde.truckscan.core.pid.PidValue
import com.anthonyrohde.truckscan.core.pid.ZoneSeverity
import com.anthonyrohde.truckscan.core.session.ConnectionState
import com.anthonyrohde.truckscan.core.session.DiagnosticEngine
import com.anthonyrohde.truckscan.core.session.DiscoveredModule
import com.anthonyrohde.truckscan.core.session.LiveDataSample
import com.anthonyrohde.truckscan.core.session.RoutineResult
import com.anthonyrohde.truckscan.core.session.ServiceRoutine
import com.anthonyrohde.truckscan.core.session.VehicleDtcScan
import com.anthonyrohde.truckscan.core.trace.LearnedProfile
import com.anthonyrohde.truckscan.core.trace.TraceLogAnalyzer
import com.anthonyrohde.truckscan.core.trace.TraceParseReport
import com.anthonyrohde.truckscan.data.SessionLog
import com.anthonyrohde.truckscan.data.ProfileStore
import com.anthonyrohde.truckscan.data.SnapshotStore
import com.anthonyrohde.truckscan.data.StoredProfile
import com.anthonyrohde.truckscan.data.StoredSnapshot
import com.anthonyrohde.truckscan.transport.AdapterCatalog
import com.anthonyrohde.truckscan.transport.AdapterChoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Long-running operation currently in progress, for progress indicators. */
sealed class Busy {
    data object Idle : Busy()
    data class Connecting(val label: String) : Busy()
    data class Scanning(val label: String, val progress: Float?) : Busy()
    data class Working(val label: String) : Busy()
}

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    val log = SessionLog()
    private val snapshotStore = SnapshotStore(application)
    private val profileStore = ProfileStore(application)
    val adapterCatalog = AdapterCatalog(application)

    private var engine: DiagnosticEngine? = null
    private var liveDataJob: Job? = null

    private val _adapters = MutableStateFlow<List<AdapterChoice>>(emptyList())
    val adapters: StateFlow<List<AdapterChoice>> = _adapters.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _modules = MutableStateFlow<List<DiscoveredModule>>(emptyList())
    val modules: StateFlow<List<DiscoveredModule>> = _modules.asStateFlow()

    private val _faults = MutableStateFlow<VehicleDtcScan?>(null)
    val faults: StateFlow<VehicleDtcScan?> = _faults.asStateFlow()

    private val _busy = MutableStateFlow<Busy>(Busy.Idle)
    val busy: StateFlow<Busy> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _liveSample = MutableStateFlow<LiveDataSample?>(null)
    val liveSample: StateFlow<LiveDataSample?> = _liveSample.asStateFlow()

    private val _availablePids = MutableStateFlow<List<Pid>>(PidCatalog.ALL)
    val availablePids: StateFlow<List<Pid>> = _availablePids.asStateFlow()

    private val _selectedPids = MutableStateFlow(PidCatalog.DEFAULT_SELECTION.toSet())
    val selectedPids: StateFlow<Set<String>> = _selectedPids.asStateFlow()

    /**
     * Readings currently outside their normal band, worst first.
     *
     * This is the warning-light feed. It is derived rather than stored so it
     * cannot drift from the readings on screen, and it is deliberately ordered
     * by severity: on a dash-mounted phone the first line is the only one
     * guaranteed to be read.
     */
    private val _alerts = MutableStateFlow<List<PidValue>>(emptyList())
    val alerts: StateFlow<List<PidValue>> = _alerts.asStateFlow()

    private val _snapshots = MutableStateFlow<List<StoredSnapshot>>(emptyList())
    val snapshots: StateFlow<List<StoredSnapshot>> = _snapshots.asStateFlow()

    private val _activeSnapshot = MutableStateFlow<AsBuiltSnapshot?>(null)
    val activeSnapshot: StateFlow<AsBuiltSnapshot?> = _activeSnapshot.asStateFlow()

    private val _profiles = MutableStateFlow<List<StoredProfile>>(emptyList())
    val profiles: StateFlow<List<StoredProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<LearnedProfile?>(null)
    val activeProfile: StateFlow<LearnedProfile?> = _activeProfile.asStateFlow()

    private val _lastImportReport = MutableStateFlow<TraceParseReport?>(null)
    val lastImportReport: StateFlow<TraceParseReport?> = _lastImportReport.asStateFlow()

    val standardRoutines: List<ServiceRoutine>
        get() = engine?.routines?.standardOperations ?: emptyList()

    init {
        refreshAdapters()
        refreshSnapshots()
        refreshProfiles()
        // Reapply whatever profile was active last time, so a learned identifier
        // map does not have to be re-imported after every restart.
        viewModelScope.launch { _activeProfile.value = profileStore.loadActive() }
    }

    fun refreshAdapters() {
        _adapters.value = runCatching { adapterCatalog.available() }.getOrDefault(emptyList())
    }

    fun dismissMessage() {
        _message.value = null
    }

    // -------------------------------------------------------------- connection

    fun connect(choice: AdapterChoice) = viewModelScope.launch {
        _busy.value = Busy.Connecting(choice.label)
        try {
            val transport = adapterCatalog.createTransport(choice)
            val created = DiagnosticEngine(transport, logger = log::append)
            engine = created
            // Carry any active learned profile into the new session.
            _activeProfile.value?.let { created.applyLearnedProfile(it) }

            val result = created.connect(CanBus.HS_CAN1)
            _connection.value = created.connectionState.value

            result.onFailure { _message.value = it.message }
                .onSuccess {
                    val state = created.connectionState.value
                    if (state is ConnectionState.Connected && !state.busTrafficSeen) {
                        _message.value = "Connected to ${it.model}, but the bus is quiet. " +
                            "Turn the ignition on so the modules are awake."
                    }
                }
        } catch (e: Exception) {
            _connection.value = ConnectionState.Failed(e.message ?: "Connection failed")
            _message.value = e.message
        } finally {
            _busy.value = Busy.Idle
        }
    }

    fun disconnect() = viewModelScope.launch {
        stopLiveData()
        engine?.disconnect()
        engine = null
        _connection.value = ConnectionState.Disconnected
        _modules.value = emptyList()
        _faults.value = null
    }

    // --------------------------------------------------------------- discovery

    /** [full] sweeps every address rather than only the known ones. */
    fun scanModules(full: Boolean = false) = viewModelScope.launch {
        val active = engine ?: return@launch
        _busy.value = Busy.Scanning(if (full) "Full module sweep" else "Scanning modules", 0f)
        try {
            val found = if (full) {
                active.fullScanVehicle { progress ->
                    _busy.value = Busy.Scanning(
                        "Sweeping ${progress.bus.displayName} " +
                            "(${progress.addressesProbed}/${progress.addressesTotal})",
                        progress.addressesProbed.toFloat() / progress.addressesTotal,
                    )
                }
            } else {
                active.quickScanVehicle { progress ->
                    _busy.value = Busy.Scanning(
                        "Scanning ${progress.bus.displayName}",
                        progress.addressesProbed.toFloat() / progress.addressesTotal,
                    )
                }
            }
            _modules.value = found
            _availablePids.value = runCatching { active.liveData.availableParameters() }
                .getOrDefault(PidCatalog.ALL)

            if (found.isEmpty()) {
                _message.value = "No modules answered. Check the ignition is on and the " +
                    "adapter is fully seated in the OBD port."
            }
        } catch (e: Exception) {
            _message.value = e.message
        } finally {
            _busy.value = Busy.Idle
        }
    }

    // -------------------------------------------------------------------- DTCs

    fun scanFaults() = viewModelScope.launch {
        val active = engine ?: return@launch
        _busy.value = Busy.Scanning("Reading faults", 0f)
        try {
            _faults.value = active.scanFaults { done, total, current ->
                _busy.value = Busy.Scanning(
                    "Reading ${current.module.code}",
                    done.toFloat() / total.coerceAtLeast(1),
                )
            }
        } catch (e: Exception) {
            _message.value = e.message
        } finally {
            _busy.value = Busy.Idle
        }
    }

    fun clearFaults(module: DiscoveredModule) = viewModelScope.launch {
        val active = engine ?: return@launch
        _busy.value = Busy.Working("Clearing ${module.module.code}")
        try {
            active.dtcScanner.clearModule(module)
                .onSuccess { _message.value = "Cleared faults in ${module.module.code}." }
                .onFailure { _message.value = "Could not clear: ${it.message}" }
            scanFaults().join()
        } finally {
            _busy.value = Busy.Idle
        }
    }

    // --------------------------------------------------------------- live data

    fun togglePid(key: String) {
        val current = _selectedPids.value
        _selectedPids.value = if (key in current) current - key else current + key
    }

    fun selectOnly(keys: Collection<String>) {
        _selectedPids.value = keys.toSet()
    }

    fun startLiveData() {
        val active = engine ?: return
        val pids = _availablePids.value.filter { it.key in _selectedPids.value }
        if (pids.isEmpty()) {
            _message.value = "Select at least one parameter to watch."
            return
        }

        stopLiveData()
        liveDataJob = viewModelScope.launch {
            try {
                active.liveData.stream(pids, intervalMillis = 200)
                    .collect { sample ->
                        _liveSample.value = sample
                        _alerts.value = sample.values.values
                            .filter { it.severity != ZoneSeverity.NORMAL }
                            .sortedByDescending { it.severity.ordinal }
                    }
            } catch (e: Exception) {
                _message.value = e.message
            }
        }
    }

    fun stopLiveData() {
        liveDataJob?.cancel()
        liveDataJob = null
        // Stale alerts are worse than none: a warning left on screen after
        // polling stopped implies the condition is still being watched.
        _alerts.value = emptyList()
    }

    val isStreaming: Boolean get() = liveDataJob?.isActive == true

    // ---------------------------------------------------------------- As-Built

    fun refreshSnapshots() = viewModelScope.launch {
        _snapshots.value = snapshotStore.list()
    }

    /**
     * Reads a module's configuration and saves it as a backup immediately.
     *
     * Reading and saving are one action on purpose: a snapshot that exists only
     * in memory is not a backup, and the writer will not proceed without a
     * stored one.
     */
    fun backupAsBuilt(module: DiscoveredModule) = viewModelScope.launch {
        val active = engine ?: return@launch
        _busy.value = Busy.Scanning("Reading ${module.module.code} configuration", 0f)
        try {
            val snapshot = active.snapshotAsBuilt(module) { progress ->
                _busy.value = Busy.Scanning(
                    "Probing identifiers (${progress.didsProbed}/${progress.didsTotal}), " +
                        "${progress.blocksFound} found",
                    progress.didsProbed.toFloat() / progress.didsTotal,
                )
            }

            if (snapshot.blocks.isEmpty()) {
                _message.value = "${module.module.code} returned no configuration blocks. " +
                    "It may not expose As-Built data, or may need a session this app " +
                    "could not establish."
                return@launch
            }

            val file = snapshotStore.save(snapshot)
            _activeSnapshot.value = snapshot
            refreshSnapshots()

            // Bound to a local: Kotlin will not smart-cast a public property
            // declared in another module, since nothing stops it changing
            // between the null check and the use.
            val strategy = snapshot.checksumStrategy
            _message.value = buildString {
                append("Saved ${snapshot.blocks.size} block(s) from ")
                append("${module.module.code} to ${file.name}. ")
                if (strategy == null) {
                    append(
                        "The checksum algorithm could not be determined from these " +
                            "blocks, so writes will be refused - the backup itself is " +
                            "still valid.",
                    )
                } else {
                    append("Checksum: ${strategy.label}.")
                }
            }
        } catch (e: Exception) {
            _message.value = e.message
        } finally {
            _busy.value = Busy.Idle
        }
    }

    fun loadSnapshot(stored: StoredSnapshot) = viewModelScope.launch {
        _activeSnapshot.value = snapshotStore.load(stored.file)
        if (_activeSnapshot.value == null) {
            _message.value = "Could not read ${stored.file.name}."
        }
    }

    fun deleteSnapshot(stored: StoredSnapshot) = viewModelScope.launch {
        snapshotStore.delete(stored.file)
        refreshSnapshots()
    }

    // ---------------------------------------------------------------- routines

    fun runRoutine(routine: ServiceRoutine, module: DiscoveredModule) = viewModelScope.launch {
        val active = engine ?: return@launch
        _busy.value = Busy.Working(routine.name)
        try {
            _message.value = when (val result = active.routines.runStandard(routine, module)) {
                is RoutineResult.Completed -> result.detail
                is RoutineResult.Rejected -> "Rejected: ${result.reason}"
                is RoutineResult.Failed -> "Failed: ${result.reason}"
            }
        } finally {
            _busy.value = Busy.Idle
        }
    }

    override fun onCleared() {
        stopLiveData()
        super.onCleared()
    }

}
