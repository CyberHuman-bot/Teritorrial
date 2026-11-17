package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AiSuggestion
import com.example.data.model.BottleneckAnalysis
import com.example.data.model.EntryType
import com.example.data.model.SystemMetricsSnapshot
import com.example.data.model.TerminalEntry
import com.example.data.model.TerminalProfile
import com.example.data.model.UiState
import com.example.data.repository.AiAssistantRepository
import com.example.data.repository.AlpacaBootstrapEngine
import com.example.data.repository.ProfileRepository
import com.example.data.repository.SystemMonitorRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class TerminalMonitorViewModel(
    private val currentUserId: String,
    private val profileRepository: ProfileRepository,
    private val shellEngine: AlpacaBootstrapEngine,
    private val systemMonitor: SystemMonitorRepository,
    private val aiRepository: AiAssistantRepository,
) : ViewModel() {

    private val entryIdGen = AtomicLong(1L)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    val profilesState: StateFlow<UiState<List<TerminalProfile>>> =
        profileRepository.observeProfiles(currentUserId)
            .map<List<TerminalProfile>, UiState<List<TerminalProfile>>> { list ->
                UiState.Success(list)
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val root = firestoreError ?: error
                Log.w(TAG, "Failed to observe profiles", root)
                emit(UiState.Error(root.message ?: "Failed to load terminal profiles"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )

    val activeProfile: StateFlow<TerminalProfile?> =
        profilesState.map { state ->
            when (state) {
                is UiState.Success -> state.data.firstOrNull { it.activeProfile } ?: state.data.firstOrNull()
                else -> null
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = null
        )

    val metricsState: StateFlow<SystemMetricsSnapshot> =
        systemMonitor.observeMetrics(1500L)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = SystemMetricsSnapshot()
            )

    private val _entries = MutableStateFlow(
        listOf(
            TerminalEntry(
                id = entryIdGen.getAndIncrement(),
                timestamp = nowStr(),
                text = "Alpaca Linux v3.21 Bootstrap RootFS Ready (musl/ash)\n" +
                    "Type 'help' for shell builtins, 'apk info' for packages, or tap Mic for voice.",
                type = EntryType.SYSTEM,
            )
        )
    )
    val entries: StateFlow<List<TerminalEntry>> = _entries.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _currentDirDisplay = MutableStateFlow("~")
    val currentDirDisplay: StateFlow<String> = _currentDirDisplay.asStateFlow()

    private val _suggestionsState = MutableStateFlow<UiState<List<AiSuggestion>>>(UiState.Success(emptyList()))
    val suggestionsState: StateFlow<UiState<List<AiSuggestion>>> = _suggestionsState.asStateFlow()

    private val _bottleneckState = MutableStateFlow<UiState<BottleneckAnalysis?>>(UiState.Success(null))
    val bottleneckState: StateFlow<UiState<BottleneckAnalysis?>> = _bottleneckState.asStateFlow()

    private val _proactiveAlert = MutableStateFlow<BottleneckAnalysis?>(null)
    val proactiveAlert: StateFlow<BottleneckAnalysis?> = _proactiveAlert.asStateFlow()

    private val _voiceAiTranslate = MutableStateFlow(true)
    val voiceAiTranslate: StateFlow<Boolean> = _voiceAiTranslate.asStateFlow()

    private val _isTranslatingVoice = MutableStateFlow(false)
    val isTranslatingVoice: StateFlow<Boolean> = _isTranslatingVoice.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private var suggestionDebounceJob: Job? = null
    private var lastAutoAnalysisMillis = 0L
    private var lastExecutedCommand: String? = null
    private var lastCommandError: String? = null
    private var historyIndex = -1

    init {
        viewModelScope.launch {
            profileRepository.ensureDefaultProfile(currentUserId)
                .onFailure { e ->
                    Log.w(TAG, "Default profile seed failed: ${e.message}")
                }
        }

        // Sync active profile shell into bootstrap engine & observe threshold breaches for proactive AI alerts
        viewModelScope.launch {
            activeProfile.collect { profile ->
                if (profile != null) {
                    shellEngine.setShell(profile.preferredShell)
                }
            }
        }

        viewModelScope.launch {
            combine(metricsState, activeProfile) { metrics, profile -> metrics to profile }
                .collect { (metrics, profile) ->
                    if (profile != null && profile.proactiveMonitoring) {
                        val cpuExceeded = metrics.cpuPercent >= profile.cpuAlertThreshold
                        val memExceeded = metrics.memPercent >= profile.memAlertThreshold
                        val now = System.currentTimeMillis()
                        if ((cpuExceeded || memExceeded) && now - lastAutoAnalysisMillis > 45_000L) {
                            lastAutoAnalysisMillis = now
                            runBottleneckAnalysis(isProactive = true)
                        }
                    }
                }
        }
    }

    fun onInputChanged(newText: String) {
        _inputText.value = newText
        historyIndex = -1
        val profile = activeProfile.value
        if (profile?.autoSuggestEnabled == true && newText.trim().length >= 3) {
            suggestionDebounceJob?.cancel()
            suggestionDebounceJob = viewModelScope.launch {
                delay(750L)
                fetchSuggestions(newText.trim())
            }
        }
    }

    fun toggleVoiceAiTranslate() {
        _voiceAiTranslate.value = !_voiceAiTranslate.value
    }

    fun dismissProactiveAlert() {
        _proactiveAlert.value = null
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun cycleHistory(up: Boolean) {
        val history = activeProfile.value?.commandHistory.orEmpty()
        if (history.isEmpty()) return
        historyIndex = if (up) {
            if (historyIndex == -1) history.lastIndex else (historyIndex - 1).coerceAtLeast(0)
        } else {
            if (historyIndex == -1 || historyIndex >= history.lastIndex) -1 else historyIndex + 1
        }
        _inputText.value = if (historyIndex == -1) "" else history[historyIndex]
    }

    fun applySuggestion(command: String, runImmediately: Boolean = false) {
        _inputText.value = command
        if (runImmediately) {
            executeCommand(command)
        }
    }

    fun handleVoiceTranscript(transcript: String) {
        val clean = transcript.trim()
        if (clean.isEmpty()) return
        if (!_voiceAiTranslate.value) {
            _inputText.value = clean
            return
        }
        viewModelScope.launch {
            _isTranslatingVoice.value = true
            val profile = activeProfile.value
            val result = aiRepository.naturalLanguageToShell(
                spokenOrTypedIntent = clean,
                currentDir = _currentDirDisplay.value,
                preferredShell = profile?.preferredShell ?: "/bin/ash",
                modelName = profile?.aiModelName ?: "gemini-3.8-flash",
            )
            _isTranslatingVoice.value = false
            result.onSuccess { cmd ->
                _inputText.value = cmd
                appendEntry(
                    text = "Voice -> \"$clean\" mapped to `$cmd`",
                    type = EntryType.AI_SUGGESTION
                )
            }.onFailure { err ->
                _inputText.value = clean
                _statusBanner.value = err.localizedMessage ?: "Voice AI translation failed; kept raw transcript."
            }
        }
    }

    fun requestManualSuggestions() {
        viewModelScope.launch {
            fetchSuggestions(_inputText.value.trim())
        }
    }

    fun executeCommand(rawCommand: String = _inputText.value) {
        val command = rawCommand.trim()
        if (command.isEmpty()) return

        _inputText.value = ""
        historyIndex = -1
        suggestionDebounceJob?.cancel()

        val profile = activeProfile.value
        val promptBase = profile?.promptSymbol ?: "alpaca:~$"
        val promptStr = "$promptBase [${_currentDirDisplay.value}]"
        appendEntry(prompt = promptStr, text = command, type = EntryType.COMMAND)

        viewModelScope.launch {
            val currentHistory = profile?.commandHistory.orEmpty()
            val result = shellEngine.execute(
                rawInput = command,
                metrics = metricsState.value,
                commandHistory = currentHistory,
            )
            _currentDirDisplay.value = result.newPromptPath

            if (result.clearRequested) {
                _entries.value = emptyList()
            } else {
                if (result.stdout.isNotEmpty()) {
                    appendEntry(text = result.stdout, type = EntryType.OUTPUT, exitCode = result.exitCode)
                }
                if (result.stderr.isNotEmpty()) {
                    appendEntry(text = result.stderr, type = EntryType.ERROR, exitCode = result.exitCode)
                }
            }

            lastExecutedCommand = command
            lastCommandError = if (result.exitCode != 0) result.stderr else null

            // Persist bounded command history to active Firestore profile
            if (profile != null && profile.id.isNotBlank()) {
                val nextHistory = (currentHistory + command).takeLast(50)
                profileRepository.updateCommandHistory(currentUserId, profile.id, nextHistory)
            }

            // On command failure or when auto-suggest is active, fetch contextual AI fix/next-step suggestions
            if (result.exitCode != 0 || profile?.autoSuggestEnabled == true) {
                fetchSuggestions(partial = "")
            }
        }
    }

    fun runBottleneckAnalysis(isProactive: Boolean = false) {
        viewModelScope.launch {
            if (!isProactive) {
                _bottleneckState.value = UiState.Loading
            }
            val profile = activeProfile.value
            val result = aiRepository.analyzeSystemBottlenecks(
                metrics = metricsState.value,
                recentCommands = profile?.commandHistory.orEmpty(),
                cpuThreshold = profile?.cpuAlertThreshold ?: 75L,
                memThreshold = profile?.memAlertThreshold ?: 80L,
                modelName = profile?.aiModelName ?: "gemini-3.8-flash",
                temperature = profile?.aiTemperature ?: 0.4,
            )
            result.onSuccess { analysis ->
                _bottleneckState.value = UiState.Success(analysis)
                if (isProactive && analysis.severity != "nominal") {
                    _proactiveAlert.value = analysis
                }
            }.onFailure { err ->
                if (!isProactive) {
                    _bottleneckState.value = UiState.Error(
                        err.localizedMessage ?: "Failed to analyze system telemetry."
                    )
                }
            }
        }
    }

    suspend fun deployAndRunScript(filename: String, code: String): String {
        val virtualPath = shellEngine.writeScriptToWorkspace(filename, code)
        executeCommand("chmod +x $virtualPath && sh $virtualPath")
        return virtualPath
    }

    suspend fun deployScriptOnly(filename: String, code: String): String {
        return shellEngine.writeScriptToWorkspace(filename, code)
    }

    private suspend fun fetchSuggestions(partial: String) {
        val profile = activeProfile.value
        _suggestionsState.value = UiState.Loading
        val result = aiRepository.suggestTerminalCommands(
            partialInput = partial,
            currentDir = _currentDirDisplay.value,
            lastCommand = lastExecutedCommand,
            lastError = lastCommandError,
            modelName = profile?.aiModelName ?: "gemini-3.8-flash",
            temperature = profile?.aiTemperature ?: 0.4,
        )
        result.onSuccess { suggestions ->
            _suggestionsState.value = UiState.Success(suggestions)
        }.onFailure { err ->
            _suggestionsState.value = UiState.Error(
                err.localizedMessage ?: "AI suggestions unavailable."
            )
        }
    }

    private fun appendEntry(
        prompt: String = "",
        text: String,
        type: EntryType,
        exitCode: Int = 0,
    ) {
        val entry = TerminalEntry(
            id = entryIdGen.getAndIncrement(),
            timestamp = nowStr(),
            prompt = prompt,
            text = text,
            type = type,
            exitCode = exitCode,
        )
        _entries.value = (_entries.value + entry).takeLast(200)
    }

    private fun nowStr(): String = timeFmt.format(Date())

    companion object {
        private const val TAG = "TerminalMonitorVM"
    }
}
