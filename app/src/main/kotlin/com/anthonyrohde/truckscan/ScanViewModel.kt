package com.anthonyrohde.truckscan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anthonyrohde.truckscan.core.adapter.CanBus
import com.anthonyrohde.truckscan.core.ford.AsBuiltSnapshot
import com.anthonyrohde.truckscan.core.pid.Pid
import com.anthonyrohde.truckscan.core.pid.PidCatalog
import com.anthonyrohde.truckscan.core.pid.PidValue
import com.anthonyrohde.truckscan.core.session.LiveRecording
import com.anthonyrohde.truckscan.core.cluster.Cluster
import com.anthonyrohde.truckscan.core.pid.MeasuredSupport
import com.anthonyrohde.truckscan.core.probe.ProbeLibrary
import com.anthonyrohde.truckscan.core.vehicle.BatteryVoltage
import com.anthonyrohde.truckscan.core.probe.ProbeRunner
import com.anthonyrohde.truckscan.core.probe.ProbeScript
import com.anthonyrohde.truckscan.core.session.DiagnosticReport
import com.anthonyrohde.truckscan.core.util.Hex
import com.anthonyrohde.truckscan.core.session.LiveHealth
import com.anthonyrohde.truckscan.core.session.LiveValueHold
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

    /**
     * Whether a sweep is running, as state the UI can actually observe.
     *
     * The screen used to infer this from "a sample exists", and stopLiveData
     * left the last sample in place, so Stop changed nothing on screen and both
     * buttons looked dead. isStreaming is a plain property, so composition is
     * never told when it changes and could not be used either.
     */
    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

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
    /**
     * The recording in progress, or the last one taken.
     *
     * Kept separate from the live sample so that stopping the stream does not
     * lose what was recorded - the whole point is to look at it afterwards.
     */
    private var recording: LiveRecording? = null

    /**
     * Last known reading per parameter, so a gauge does not empty because one
     * sweep missed it. Reset whenever a new stream starts, since values from a
     * previous session say nothing about this one.
     */
    private var hold = LiveValueHold()

    /**
     * How reliably each parameter answers, for the diagnostic report.
     *
     * Kept for the whole session rather than per stream: "this never answers"
     * is only worth saying after enough attempts to mean it.
     */
    private val health = LiveHealth()

    /** Supported mode 01 count as the vehicle reported it, for the report. */
    private var supportedPidCount: Int? = null

    /**
     * What the live stream is actually polling.
     *
     * Not the same as the current selection: a stream polls the list it was
     * started with, so ticking more parameters afterwards changes the selection
     * without changing what is being read. The report has to say what is
     * happening, not what is ticked - the difference reads as parameters
     * silently failing when nothing is wrong at all.
     */
    private var streamingPids: List<Pid> = emptyList()

    /**
     * The last module scan, kept even after a disconnect clears its results.
     *
     * Without it an empty module list is ambiguous between "nobody scanned"
     * and "the vehicle answered nothing", which are opposite problems.
     */
    private var lastModuleScan: DiagnosticReport.ModuleScan? = null

    private val _recordedRows = MutableStateFlow(0)
    val recordedRows: StateFlow<Int> = _recordedRows.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** Parameters whose last reading is old enough to distrust. */
    private val _staleKeys = MutableStateFlow<Set<String>>(emptySet())
    val staleKeys: StateFlow<Set<String>> = _staleKeys.asStateFlow()

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
                    // Ask the vehicle what it has, here, rather than only after
                    // a module scan. Without this the live screen offered all
                    // 47 catalogue parameters, twenty of which this engine does
                    // not have, and spent most of every sweep collecting
                    // refusals. Failure keeps the full catalogue: an unanswered
                    // query means we do not know, not that nothing is there.
                    runCatching { created.liveData.availableParameters() }
                        .onSuccess { pids ->
                            _availablePids.value = pids
                            val offered = pids.map { p -> p.key }.toSet()
                            val keep = _selectedPids.value intersect offered
                            _selectedPids.value = keep.ifEmpty {
                                PidCatalog.DEFAULT_SELECTION.toSet() intersect offered
                            }
                        }

                    val state = created.connectionState.value
                    // Battery first. It is the only thing here that can leave
                    // someone stranded, and it outranks anything about buses.
                    val volts = (state as? ConnectionState.Connected)?.batteryVolts
                    val warning = volts
                        ?.takeIf {
                            BatteryVoltage.classify(it) == BatteryVoltage.State.CRITICAL ||
                                BatteryVoltage.classify(it) ==
                                BatteryVoltage.State.TOO_LOW_TO_TRUST
                        }
                        ?.let { BatteryVoltage.describe(it) }

                    _message.value = when {
                        warning != null -> warning
                        state is ConnectionState.Connected && !state.busTrafficSeen ->
                            // Says nothing about the ignition on its own. Nothing
                            // answering is worth reporting; why it did not answer
                            // is not something this can know.
                            "Connected to ${it.model}. Nothing answered on the " +
                                "powertrain bus - scan modules to find out what is there."
                        else -> null
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
        // The results are gone but the fact of the scan is not: a report that
        // forgot it would say nobody had scanned, which is a different problem
        // from one whose answers were thrown away.
        lastModuleScan = lastModuleScan?.copy(discarded = true)
    }

    // --------------------------------------------------------------- discovery

    /** [full] sweeps every address rather than only the known ones. */
    fun scanModules(full: Boolean = false) = viewModelScope.launch {
        val active = engine ?: return@launch

        // A full sweep probes 256 addresses on each bus and takes minutes, so
        // it is worth saying so before it starts rather than after.
        //
        // What is NOT said any more is anything about the bus being silent.
        // That warning was here, and it was wrong: seeing no free-running
        // traffic says nothing about whether the vehicle is awake, so it told
        // people to check an ignition that was already on and talked them out
        // of a sweep that would have worked.
        if (full) {
            _message.value = "Sweeping every address on each bus. This takes a " +
                "couple of minutes."
        }

        val quietBuses = linkedSetOf<String>()
        _busy.value = Busy.Scanning(if (full) "Full module sweep" else "Scanning modules", 0f)
        try {
            val found = if (full) {
                active.fullScanVehicle { progress ->
                    if (!progress.busHadTraffic) quietBuses += progress.bus.displayName
                    _busy.value = Busy.Scanning(
                        "Sweeping ${progress.bus.displayName} " +
                            "(${progress.addressesProbed}/${progress.addressesTotal})" +
                            if (progress.busHadTraffic) "" else " - bus silent, nothing will answer",
                        progress.addressesProbed.toFloat() / progress.addressesTotal,
                    )
                }
            } else {
                active.quickScanVehicle { progress ->
                    if (!progress.busHadTraffic) quietBuses += progress.bus.displayName
                    _busy.value = Busy.Scanning(
                        "Scanning ${progress.bus.displayName}",
                        progress.addressesProbed.toFloat() / progress.addressesTotal,
                    )
                }
            }
            _modules.value = found
            lastModuleScan = DiagnosticReport.ModuleScan(
                ranAtMillis = System.currentTimeMillis(),
                full = full,
                found = found.size,
                quietBuses = quietBuses.toList(),
            )
            supportedPidCount = runCatching { active.liveData.readSupportedPids().size }.getOrNull()
            _availablePids.value = runCatching { active.liveData.availableParameters() }
                .getOrDefault(PidCatalog.ALL)

            if (found.isEmpty()) {
                _message.value = "No modules answered. Check the adapter is fully " +
                    "seated in the OBD port and the ignition is on, then try the " +
                    "Probe screen - it will show whether the vehicle answers a " +
                    "direct request at all."
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
        _streaming.value = true
        streamingPids = pids
        hold = LiveValueHold()
        _staleKeys.value = emptySet()
        liveDataJob = viewModelScope.launch {
            try {
                active.liveData.stream(pids, intervalMillis = 200)
                    .collect { sample ->
                        // Recording gets the raw sweep and the screen gets the
                        // held one. A log is a record of what was measured, so
                        // repeating a held reading into it would turn one
                        // measurement into fifty; a gauge is a display of what
                        // is true now, and blanking it because a single request
                        // missed says something false.
                        if (_isRecording.value) {
                            val active = recording
                            if (active != null && !active.add(sample)) {
                                _isRecording.value = false
                                _message.value =
                                    "Recording stopped: reached ${active.rowCount} rows. " +
                                        "Save it before starting another."
                            }
                            _recordedRows.value = active?.rowCount ?: 0
                        }

                        health.accept(sample)
                        val shown = sample.copy(values = hold.accept(sample))
                        _liveSample.value = shown
                        _staleKeys.value = hold.staleKeys(sample.timestampMillis)
                        _alerts.value = shown.values.values
                            .filter { it.severity != ZoneSeverity.NORMAL }
                            .sortedByDescending { it.severity.ordinal }
                    }
            } catch (e: Exception) {
                _message.value = e.message
            }
        }
    }

    /**
     * Begins recording the live stream, starting it if it is not already running.
     *
     * Recording without polling would be an empty file, and having to start two
     * things in the right order before driving off is a trap.
     */
    fun startRecording() {
        if (!isStreaming) startLiveData()
        if (!isStreaming) return
        recording = LiveRecording()
        _recordedRows.value = 0
        _isRecording.value = true
    }

    /** Stops recording but leaves the stream running and the data intact. */
    fun stopRecording() {
        _isRecording.value = false
    }

    /** The recording as CSV, or null when nothing has been recorded. */
    fun recordingCsv(): String? = recording?.takeIf { it.rowCount > 0 }?.toCsv()

    fun recordingSummary(): String {
        val active = recording ?: return "Nothing recorded yet."
        if (active.rowCount == 0) return "Nothing recorded yet."
        val seconds = active.durationSeconds
        return "${active.rowCount} samples over ${"%.0f".format(seconds)} s"
    }

    fun stopLiveData() {
        liveDataJob?.cancel()
        liveDataJob = null
        _streaming.value = false
        // The gauges are showing readings that are no longer being refreshed.
        // Leaving them there is a display claiming to be live when it is not,
        // which is the same failure as a stale warning below.
        _liveSample.value = null
        // Polling has stopped, so nothing more can be recorded. The data
        // already captured stays put until a new recording replaces it.
        _isRecording.value = false
        // Stale alerts are worse than none: a warning left on screen after
        // polling stopped implies the condition is still being watched.
        _alerts.value = emptyList()
    }

    // ------------------------------------------------------------- cluster

    /**
     * Starts polling exactly what the dash cluster draws.
     *
     * The cluster asks for a fixed short list rather than whatever happens to
     * be ticked on the live screen, because a dial with no source is worse than
     * no dial: it sits blank and looks broken. Twelve parameters also sweep far
     * faster than forty, which is what a needle needs to move smoothly.
     */
    fun startCluster() {
        val wanted = Cluster.requiredKeys().toSet()
        val offered = _availablePids.value.map { it.key }.toSet()
        val usable = wanted intersect offered
        if (usable.isEmpty()) {
            _message.value = "None of the cluster's parameters are available on this vehicle."
            return
        }
        _selectedPids.value = usable
        startLiveData()
    }

    /**
     * Turns the latest sweep into dial readings.
     *
     * The supported set comes from what this vehicle answered when asked, not
     * from the recorded 2022 F-250, so the cluster marks a dial unavailable on
     * the truck in front of it rather than on the one in the test fixture.
     */
    fun clusterReadings(sample: LiveDataSample?): List<Cluster.Reading> {
        val supported = _availablePids.value.map { it.id }.toSet()
            .ifEmpty { MeasuredSupport.SUPER_DUTY_2022_PCM_DATA }
        return Cluster.readAll(sample?.values ?: emptyMap(), supported)
    }

    /** One panel number, resolved the same way the dials are. */
    fun clusterReadout(readout: Cluster.Readout, sample: LiveDataSample?): String {
        val key = (readout.source as? Cluster.Source.Single)?.key ?: return "--"
        val value = sample?.values?.get(key)?.value ?: return "--"
        return Cluster.format(value, readout.decimals)
    }

    val isStreaming: Boolean get() = liveDataJob?.isActive == true

    // ------------------------------------------------------- diagnostic report

    /**
     * Assembles everything known about this session into one report.
     *
     * Built here rather than in the UI because it draws on state the screens do
     * not share, and rendered in :core so its content is tested - a diagnostic
     * that is wrong about what is working sends whoever reads it somewhere
     * else entirely.
     */
    fun buildDiagnosticReport(
        appVersion: String,
        device: String,
        androidVersion: String,
    ): String {
        val state = _connection.value
        val connected = state as? ConnectionState.Connected
        val scan = _faults.value

        // A module is taken as answering unless the fault scan says otherwise,
        // since discovery only lists what replied in the first place.
        val errorsByCode = scan?.results
            ?.filter { it.error != null }
            ?.associate { it.module.module.code to it.error.orEmpty() }
            ?: emptyMap()

        val modules = _modules.value.map { discovered ->
            val code = discovered.module.code
            val failure = errorsByCode[code]
            DiagnosticReport.ModuleLine(
                name = code,
                address = Hex.encode(discovered.module.requestId, 3),
                bus = discovered.bus.displayName,
                answered = failure == null,
                detail = failure ?: discovered.identification?.partNumber.orEmpty(),
            )
        }

        // result.faults, not result.dtcs. A module answers a status-mask read
        // with its whole DTC table, and listing all of it put this truck's three
        // real faults - two confirmed at the body module and a confirmed
        // low-voltage code at the SYNC module - under 439 lines of "not tested
        // this cycle", in the one file meant to be read by someone hunting a
        // problem.
        val faultLines = scan?.results.orEmpty().flatMap { result ->
            result.faults.map { dtc ->
                "${result.module.module.code}  ${dtc.displayCode}  ${dtc.description} " +
                    "[${dtc.status.describe()}]"
            }
        }
        val monitorsNotRun = scan?.results.orEmpty().sumOf { it.notRunCount }

        return DiagnosticReport(
            generatedAtMillis = System.currentTimeMillis(),
            appVersion = appVersion,
            device = device,
            androidVersion = androidVersion,
            adapter = connected?.identity?.model ?: "not connected",
            transport = engine?.transportDescription ?: "none",
            multiBus = connected?.identity?.supportsMultiBus,
            activeBus = connected?.activeBus?.displayName,
            connection = when (state) {
                is ConnectionState.Connected -> "Connected"
                is ConnectionState.Connecting -> "Connecting: ${state.step}"
                is ConnectionState.Failed -> "Failed: ${state.reason}"
                ConnectionState.Disconnected -> "Disconnected"
            },
            modules = modules,
            moduleScan = lastModuleScan,
            faults = faultLines,
            monitorsNotRun = monitorsNotRun,
            supportedPidCount = supportedPidCount,
            parametersOffered = _availablePids.value.size,
            parametersWatched = streamingPids.size.takeIf { it > 0 }
                ?: _selectedPids.value.size,
            parametersSelected = _selectedPids.value.size,
            health = health.parameters(),
            timing = health.timing(),
            recentLog = log.entries.value.takeLast(DIAGNOSTIC_LOG_LINES)
                .map { "${it.time}  ${it.message}" },
        ).render()
    }

    // ------------------------------------------------------------------ probe

    val investigations: List<ProbeLibrary.Investigation> get() = ProbeLibrary.ALL

    private val _probeTranscript = MutableStateFlow<String?>(null)
    val probeTranscript: StateFlow<String?> = _probeTranscript.asStateFlow()

    /** What a script would do, shown before it is allowed to run. */
    fun describeScript(script: String): String =
        ProbeScript.describe(ProbeScript.parse(script))

    /**
     * Runs a script and keeps the transcript.
     *
     * The parse happens here as well as in the preview, so what runs is checked
     * rather than trusted to have been checked - a preview the user never
     * looked at is not a safety control.
     */
    fun runProbe(title: String, script: String) = viewModelScope.launch {
        val active = engine ?: run {
            _message.value = "Connect to an adapter first."
            return@launch
        }
        val parsed = ProbeScript.parse(script)
        _busy.value = Busy.Working("Probing")
        try {
            val steps = ProbeRunner(active.adapter).run(parsed) { done, total ->
                _busy.value = Busy.Working("Probing ($done/$total)")
            }
            _probeTranscript.value = ProbeRunner.transcript(title, steps)
            val refused = parsed.refusals.size
            _message.value = if (refused == 0) {
                "Probe finished: ${steps.count { it.sent }} command(s) sent."
            } else {
                "Probe finished. $refused line(s) were refused and not sent."
            }
        } catch (e: Exception) {
            _message.value = e.message
        } finally {
            _busy.value = Busy.Idle
        }
    }

    fun clearProbeTranscript() {
        _probeTranscript.value = null
    }

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

    private companion object {
        /**
         * Adapter log lines carried in the diagnostic report.
         *
         * Enough to show how a failure unfolded without turning the report
         * into the log it exists to summarise.
         */
        const val DIAGNOSTIC_LOG_LINES = 400
    }
}
