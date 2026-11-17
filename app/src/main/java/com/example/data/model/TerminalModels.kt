package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

enum class ShellType(val path: String, val label: String) {
    ASH("/bin/ash", "Almquist Shell (ash)"),
    SH("/bin/sh", "POSIX Shell (sh)"),
    BASH("/bin/bash", "Bourne Again (bash)"),
    ZSH("/bin/zsh", "Z Shell (zsh)");

    companion object {
        fun fromPath(raw: String): ShellType =
            entries.firstOrNull { it.path == raw } ?: ASH
    }
}

enum class AiModelOption(val modelId: String, val displayName: String, val summary: String) {
    FLASH_38("gemini-3.8-flash", "Gemini 3.8 Flash", "Balanced speed & deep reasoning"),
    FLASH_LITE_35("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "Ultra-fast inline suggestions"),
    ARGON_4("gemini-4-argon", "Gemini 4 Argon", "Complex debugging & kernel analysis");

    companion object {
        fun fromModelId(raw: String): AiModelOption =
            entries.firstOrNull { it.modelId == raw } ?: FLASH_38
    }
}

@Keep
@IgnoreExtraProperties
data class TerminalProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "Alpaca Core",
    val preferredShell: String = ShellType.ASH.path,
    val promptSymbol: String = "alpaca:~$",
    val aiModelName: String = AiModelOption.FLASH_38.modelId,
    val aiTemperature: Double = 0.4,
    val proactiveMonitoring: Boolean = true,
    val autoSuggestEnabled: Boolean = true,
    val cpuAlertThreshold: Long = 75L,
    val memAlertThreshold: Long = 80L,
    val activeProfile: Boolean = false,
    val commandHistory: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class SavedScript(
    @DocumentId val id: String = "",
    val userId: String = "",
    val profileId: String = "",
    val title: String = "",
    val language: String = "sh",
    val code: String = "",
    val description: String = "",
    val sourceType: String = "generated",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

enum class EntryType {
    COMMAND,
    OUTPUT,
    ERROR,
    SYSTEM,
    AI_SUGGESTION,
}

data class TerminalEntry(
    val id: Long,
    val timestamp: String,
    val prompt: String = "",
    val text: String,
    val type: EntryType,
    val exitCode: Int = 0,
)

data class AiSuggestion(
    val command: String,
    val title: String,
    val category: String,
    val reason: String,
)

data class SystemMetricsSnapshot(
    val cpuPercent: Float = 0f,
    val cpuFreqMhz: Int = 0,
    val cpuCores: Int = 4,
    val memUsedMb: Long = 0L,
    val memTotalMb: Long = 2048L,
    val memPercent: Float = 0f,
    val jvmHeapUsedMb: Long = 0L,
    val diskUsedGb: Float = 0f,
    val diskTotalGb: Float = 16f,
    val diskPercent: Float = 0f,
    val diskIoKbps: Float = 0f,
    val netRxKbps: Float = 0f,
    val netTxKbps: Float = 0f,
    val totalNetMb: Float = 0f,
    val processCount: Int = 1,
    val uptimeSeconds: Long = 0L,
    val cpuHistory: List<Float> = emptyList(),
    val memHistory: List<Float> = emptyList(),
    val diskIoHistory: List<Float> = emptyList(),
    val netHistory: List<Float> = emptyList(),
)

data class OptimizationAction(
    val title: String,
    val impact: String,
    val command: String,
    val rationale: String,
)

data class BottleneckAnalysis(
    val severity: String,
    val bottleneckArea: String,
    val summary: String,
    val actions: List<OptimizationAction>,
    val analyzedAtMillis: Long = System.currentTimeMillis(),
)

data class CodeGenResult(
    val title: String,
    val language: String,
    val code: String,
    val explanation: String,
    val apkDependencies: List<String>,
    val runCommand: String,
)

data class DebugIssue(
    val lineHint: String,
    val severity: String,
    val description: String,
    val fixSummary: String,
)

data class DebugReport(
    val rootCause: String,
    val issues: List<DebugIssue>,
    val fixedCode: String,
    val optimizationNotes: String,
    val testCommand: String,
)

data class ChatMessage(
    val id: Long,
    val role: String, // "user" or "model"
    val content: String,
    val suggestedCommand: String? = null,
    val isError: Boolean = false,
)

data class VoiceCommandTranslation(
    val transcript: String,
    val command: String,
    val explanation: String = "",
)
