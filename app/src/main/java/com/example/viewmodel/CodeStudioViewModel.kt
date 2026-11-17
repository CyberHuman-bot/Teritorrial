package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ChatMessage
import com.example.data.model.CodeGenResult
import com.example.data.model.DebugReport
import com.example.data.model.SavedScript
import com.example.data.model.TerminalProfile
import com.example.data.model.UiState
import com.example.data.repository.AiAssistantRepository
import com.example.data.repository.AlpacaBootstrapEngine
import com.example.data.repository.ProfileRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class CodeStudioViewModel(
    private val currentUserId: String,
    private val profileRepository: ProfileRepository,
    private val aiRepository: AiAssistantRepository,
    private val shellEngine: AlpacaBootstrapEngine,
) : ViewModel() {

    private val chatIdGen = AtomicLong(1L)

    val scriptsState: StateFlow<UiState<List<SavedScript>>> =
        profileRepository.observeScripts(currentUserId)
            .map<List<SavedScript>, UiState<List<SavedScript>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val root = firestoreError ?: error
                Log.w(TAG, "Failed to observe saved scripts", root)
                emit(UiState.Error(root.message ?: "Failed to load saved scripts"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )

    private val _genPrompt = MutableStateFlow("")
    val genPrompt: StateFlow<String> = _genPrompt.asStateFlow()

    private val _selectedLanguage = MutableStateFlow("sh")
    val selectedLanguage: StateFlow<String> = _selectedLanguage.asStateFlow()

    private val _codeGenState = MutableStateFlow<UiState<CodeGenResult?>>(UiState.Success(null))
    val codeGenState: StateFlow<UiState<CodeGenResult?>> = _codeGenState.asStateFlow()

    private val _debugCodeInput = MutableStateFlow(
        "#!/bin/ash\n# Paste buggy shell/Python/AWK code here\nfor f in $(ls /var/log)\ndo\n  echo \$f\n  cat /var/log/\$f | grep ERROR\ndone"
    )
    val debugCodeInput: StateFlow<String> = _debugCodeInput.asStateFlow()

    private val _debugErrorInput = MutableStateFlow("")
    val debugErrorInput: StateFlow<String> = _debugErrorInput.asStateFlow()

    private val _debugState = MutableStateFlow<UiState<DebugReport?>>(UiState.Success(null))
    val debugState: StateFlow<UiState<DebugReport?>> = _debugState.asStateFlow()

    private val _chatMessages = MutableStateFlow(
        listOf(
            ChatMessage(
                id = chatIdGen.getAndIncrement(),
                role = "model",
                content = "I am Alpaca AI, your Linux systems & scripting copilot. Ask me to write BusyBox ash one-liners, troubleshoot permissions, or tune memory/CPU usage."
            )
        )
    )
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isChatSending = MutableStateFlow(false)
    val isChatSending: StateFlow<Boolean> = _isChatSending.asStateFlow()

    private val _workspaceFiles = MutableStateFlow<List<String>>(emptyList())
    val workspaceFiles: StateFlow<List<String>> = _workspaceFiles.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    init {
        refreshWorkspaceFiles()
    }

    fun refreshWorkspaceFiles() {
        viewModelScope.launch {
            _workspaceFiles.value = shellEngine.listWorkspaceScripts()
        }
    }

    fun loadWorkspaceFileIntoDebugger(virtualPath: String) {
        viewModelScope.launch {
            val content = shellEngine.readWorkspaceFile(virtualPath)
            if (content != null) {
                _debugCodeInput.value = content
                _toastMessage.value = "Loaded $virtualPath into Debugger"
            }
        }
    }

    fun onGenPromptChanged(value: String) {
        _genPrompt.value = value
    }

    fun onLanguageSelected(lang: String) {
        _selectedLanguage.value = lang
    }

    fun onDebugCodeChanged(value: String) {
        _debugCodeInput.value = value
    }

    fun onDebugErrorChanged(value: String) {
        _debugErrorInput.value = value
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun generateScript(profile: TerminalProfile?) {
        val desc = _genPrompt.value.trim()
        if (desc.isEmpty()) {
            _toastMessage.value = "Describe the script or snippet you want to generate."
            return
        }
        viewModelScope.launch {
            _codeGenState.value = UiState.Loading
            val result = aiRepository.generateCode(
                description = desc,
                targetLanguage = _selectedLanguage.value,
                preferredShell = profile?.preferredShell ?: "/bin/ash",
                modelName = profile?.aiModelName ?: "gemini-3.8-flash",
                temperature = profile?.aiTemperature ?: 0.4,
            )
            result.onSuccess { generated ->
                _codeGenState.value = UiState.Success(generated)
            }.onFailure { err ->
                _codeGenState.value = UiState.Error(
                    err.localizedMessage ?: "Code generation failed."
                )
            }
        }
    }

    fun debugUserCode(profile: TerminalProfile?) {
        val code = _debugCodeInput.value.trim()
        if (code.isEmpty()) {
            _toastMessage.value = "Paste code to debug."
            return
        }
        viewModelScope.launch {
            _debugState.value = UiState.Loading
            val result = aiRepository.debugCode(
                sourceCode = code,
                errorOutput = _debugErrorInput.value.trim(),
                language = _selectedLanguage.value,
                modelName = profile?.aiModelName ?: "gemini-3.8-flash",
                temperature = profile?.aiTemperature ?: 0.2,
            )
            result.onSuccess { report ->
                _debugState.value = UiState.Success(report)
            }.onFailure { err ->
                _debugState.value = UiState.Error(
                    err.localizedMessage ?: "Code debugging failed."
                )
            }
        }
    }

    fun applyDebugFixToEditor(fixedCode: String) {
        _debugCodeInput.value = fixedCode
        _toastMessage.value = "Applied fixed code to editor."
    }

    fun saveScriptToCloud(
        profileId: String,
        title: String,
        language: String,
        code: String,
        description: String,
        sourceType: String,
    ) {
        viewModelScope.launch {
            val result = profileRepository.saveScript(
                userId = currentUserId,
                profileId = profileId,
                title = title,
                language = language,
                code = code,
                description = description,
                sourceType = sourceType,
            )
            result.onSuccess {
                _toastMessage.value = "Saved '$title' to your profile library."
            }.onFailure { err ->
                _toastMessage.value = err.localizedMessage ?: "Failed to save script."
            }
        }
    }

    fun deleteScript(scriptId: String) {
        viewModelScope.launch {
            profileRepository.deleteScript(currentUserId, scriptId)
                .onSuccess { _toastMessage.value = "Script deleted." }
                .onFailure { err -> _toastMessage.value = err.localizedMessage ?: "Delete failed." }
        }
    }

    fun sendChatMessage(text: String, profile: TerminalProfile?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isChatSending.value) return
        val historyBefore = _chatMessages.value
        _chatMessages.value = historyBefore + ChatMessage(
            id = chatIdGen.getAndIncrement(),
            role = "user",
            content = trimmed,
        )
        viewModelScope.launch {
            _isChatSending.value = true
            val result = aiRepository.sendChatMessage(
                history = historyBefore,
                newUserMessage = trimmed,
                preferredShell = profile?.preferredShell ?: "/bin/ash",
                modelName = profile?.aiModelName ?: "gemini-3.8-flash",
                temperature = profile?.aiTemperature ?: 0.4,
            )
            _isChatSending.value = false
            result.onSuccess { reply ->
                _chatMessages.value = _chatMessages.value + ChatMessage(
                    id = chatIdGen.getAndIncrement(),
                    role = "model",
                    content = reply,
                )
            }.onFailure { err ->
                _chatMessages.value = _chatMessages.value + ChatMessage(
                    id = chatIdGen.getAndIncrement(),
                    role = "model",
                    content = err.localizedMessage ?: "Failed to reach Gemini.",
                    isError = true,
                )
            }
        }
    }

    companion object {
        private const val TAG = "CodeStudioVM"
    }
}
