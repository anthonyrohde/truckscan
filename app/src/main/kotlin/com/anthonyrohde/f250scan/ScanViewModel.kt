package com.anthonyrohde.f250scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anthonyrohde.f250scan.core.adapter.CanBus
import com.anthonyrohde.f250scan.core.ford.AsBuiltSnapshot
import com.anthonyrohde.f250scan.core.pid.Pid
import com.anthonyrohde.f250scan.core.pid.PidCatalog
import com.anthonyrohde.f250scan.core.session.BlockChange
import com.anthonyrohde.f250scan.core.session.ConnectionState
import com.anthonyrohde.f250scan.core.session.DiagnosticEngine
import com.anthonyrohde.f250scan.core.session.DiscoveredModule
import com.anthonyrohde.f250scan.core.session.LiveDataSample
import com.anthonyrohde.f250scan.core.session.RoutineResult
import com.anthonyrohde.f250scan.core.session.ServiceRoutine
import com.anthonyrohde.f250scan.core.session.VehicleDtcScan
import com.anthonyrohde.f250scan.core.session.WriteOutcome
import com.anthonyrohde.f250scan.core.trace.LearnedProfile
import com.anthonyrohde.f250scan.core.trace.TraceLogAnalyzer
import com.anthonyrohde.f250scan.core.trace.TraceParseReport
import com.anthonyrohde.f250scan.data.SessionLog
import com.anthonyrohde.f250scan.data.ProfileStore
import com.anthonyrohde.f250scan.data.SnapshotStore
import com.anthonyrohde.f250scan.data.StoredProfile
import com.anthonyrohde.f250scan.data.StoredSnapshot
import com.anthonyrohde.f250scan.transport.AdapterCatalog
import com.anthonyrohde.f250scan.transport.AdapterChoice
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

    private val _selectedPids = MutableStateFlow(DEFAULT_PID_SELECTION)
    val selectedPids: StateFlow<Set<Int>> = _selectedPids.asStateFlow()

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

    fun togglePid(pidId: Int) {
        val current = _selectedPids.value
        _selectedPids.value = if (pidId in current) current - pidId else current + pidId
    }

    fun startLiveData() {
        val active = engine ?: return
        val pids = _availablePids.value.filter { it.id in _selectedPids.value }
        if (pids.isEmpty()) {
            _message.value = "Select at least one parameter to watch."
            return
        }

        stopLiveData()
        liveDataJob = viewModelScope.launch {
            try {
                active.liveData.stream(pids, intervalMillis = 200)
                    .collect { _liveSample.value = it }
            } catch (e: Exception) {
                _message.value = e.message
            }
        }
    }

    fun stopLiveData() {
        liveDataJob?.cancel()
        liveDataJob = null
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

            _message.value = buildString {
                append("Saved ${snapshot.blocks.size} block(s) from ")
                append("${module.module.code} to ${file.name}. ")
                if (snapshot.checksumStrategy == null) {
                    append(
                        "The checksum algorithm could not be determined from these " +
                            "blocks, so writes will be refused - the backup itself is " +
                            "still valid.",
                    )
                } else {
                    append("Checksum: ${snapshot.checksumStrategy.label}.")
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

    /** Validates without touching the vehicle, for the confirmation screen. */
    suspend fun validateWrite(
        module: DiscoveredModule,
        changes: List<BlockChange>,
    ): String? {
        val active = engine ?: return "Not connected."
        val backup = snapshotStore.latestFor(module.module.code)
        val voltage = runCatching { active.readControlModuleVoltage() }.getOrNull()
        return active.asBuiltWriter.validate(backup, changes, voltage)?.explanation
    }

    fun writeAsBuilt(
        module: DiscoveredModule,
        changes: List<BlockChange>,
    ) = viewModelScope.launch {
        val active = engine ?: return@launch
        _busy.value = Busy.Working("Writing ${module.module.code}")
        try {
            val backup = snapshotStore.latestFor(module.module.code)
            val voltage = runCatching { active.readControlModuleVoltage() }.getOrNull()

            when (val outcome = active.asBuiltWriter.write(module, backup, changes, voltage)) {
                is WriteOutcome.Success ->
                    _message.value = "Wrote and verified ${outcome.written.size} block(s). " +
                        "Cycle the ignition for the change to take effect."

                is WriteOutcome.Blocked ->
                    _message.value = outcome.blocker.explanation

                is WriteOutcome.PartialFailure -> _message.value = buildString {
                    append("Write failed at DID ")
                    append(outcome.failedAt.did.toString(16).uppercase())
                    append(": ${outcome.reason}. ")
                    append(outcome.restoreDetail)
                }

                is WriteOutcome.VerificationFailed -> _message.value = buildString {
                    append("The module accepted the write but read back something ")
                    append("different, so it did not take. ")
                    append(
                        if (outcome.restored) {
                            "The original value has been restored."
                        } else {
                            "The original value could NOT be restored - restore from " +
                                "your backup file before driving."
                        },
                    )
                }
            }
        } catch (e: Exception) {
            _message.value = e.message
        } finally {
            _busy.value = Busy.Idle
        }
    }

    // ----------------------------------------------------------- trace importing

    fun refreshProfiles() = viewModelScope.launch {
        _profiles.value = profileStore.list()
    }

    /**
     * Imports a bus capture and learns what it can from it.
     *
     * [text] is the raw log contents; [name] is shown so the user can tell
     * profiles apart later. Nothing about the vehicle is touched - this is
     * purely reading a file.
     */
    fun importTrace(text: String, name: String) = viewModelScope.launch {
        _busy.value = Busy.Working("Analysing $name")
        try {
            val (profile, report) = TraceLogAnalyzer(log::append).analyseText(text, name)
            _lastImportReport.value = report

            if (report.isEmpty) {
                _message.value = report.describe()
                return@launch
            }
            if (profile.isEmpty) {
                _message.value = "Read ${report.framesParsed} frame(s) from $name, but " +
                    "found no configuration reads or writes to learn from. A capture of " +
                    "a module configuration read is what this needs."
                return@launch
            }

            val file = profileStore.save(profile)
            profileStore.setActive(file)
            _activeProfile.value = profile
            applyProfileToEngine(profile)
            refreshProfiles()

            _message.value = "Learned from $name: " +
                "${profile.modules.count { it.hasAnything }} module(s), saved as ${file.name}."
        } catch (e: Exception) {
            _message.value = "Could not analyse $name: ${e.message}"
        } finally {
            _busy.value = Busy.Idle
        }
    }

    fun activateProfile(stored: StoredProfile) = viewModelScope.launch {
        val profile = profileStore.load(stored.file)
        if (profile == null) {
            _message.value = "Could not read ${stored.file.name}."
            return@launch
        }
        profileStore.setActive(stored.file)
        _activeProfile.value = profile
        applyProfileToEngine(profile)
        _message.value = "Applied ${stored.file.name}."
    }

    fun clearActiveProfile() = viewModelScope.launch {
        profileStore.setActive(null)
        _activeProfile.value = null
        applyProfileToEngine(null)
        _message.value = "Profile cleared. As-Built reads will fall back to sweeping."
    }

    fun deleteProfile(stored: StoredProfile) = viewModelScope.launch {
        profileStore.delete(stored.file)
        if (_activeProfile.value?.sourceDescription == stored.sourceDescription) {
            _activeProfile.value = null
            applyProfileToEngine(null)
        }
        refreshProfiles()
    }

    /** Pushes the profile into the engine, if one is connected. */
    private fun applyProfileToEngine(profile: LearnedProfile?) {
        engine?.applyLearnedProfile(profile)
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

    companion object {
        /** A sensible default dashboard for a diesel: the things that matter first. */
        private val DEFAULT_PID_SELECTION = setOf(
            PidCatalog.ENGINE_RPM.id,
            PidCatalog.COOLANT_TEMP.id,
            PidCatalog.ENGINE_LOAD.id,
            PidCatalog.FUEL_RAIL_PRESSURE.id,
            PidCatalog.CONTROL_MODULE_VOLTAGE.id,
            PidCatalog.DPF_TEMP_BANK1.id,
        )
    }
}
