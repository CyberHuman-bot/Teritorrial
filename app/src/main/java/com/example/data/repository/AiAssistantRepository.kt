package com.example.data.repository

import android.util.Log
import com.example.data.model.AiSuggestion
import com.example.data.model.BottleneckAnalysis
import com.example.data.model.ChatMessage
import com.example.data.model.CodeGenResult
import com.example.data.model.DebugIssue
import com.example.data.model.DebugReport
import com.example.data.model.OptimizationAction
import com.example.data.model.SystemMetricsSnapshot
import com.example.data.model.VoiceCommandTranslation
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class AiAssistantRepository {
    private val defaultModel by lazy {
        Firebase.ai.generativeModel("gemini-3.8-flash")
    }

    private fun getStructuredModel(
        modelName: String,
        temperature: Float,
        schema: Schema,
        systemPrompt: String,
    ): GenerativeModel {
        val safeModel = if (modelName in ALLOWED_MODELS) modelName else "gemini-3.8-flash"
        return Firebase.ai.generativeModel(
            modelName = safeModel,
            generationConfig = generationConfig {
                this.temperature = temperature.coerceIn(0f, 1f)
                responseMimeType = "application/json"
                responseSchema = schema
            },
            systemInstruction = content { text(systemPrompt) },
        )
    }

    suspend fun suggestTerminalCommands(
        partialInput: String,
        currentDir: String,
        lastCommand: String?,
        lastError: String?,
        modelName: String,
        temperature: Double,
    ): Result<List<AiSuggestion>> {
        return try {
            val schema = Schema.obj(
                properties = mapOf(
                    "suggestions" to Schema.array(
                        items = Schema.obj(
                            properties = mapOf(
                                "command" to Schema.string("Executable Alpine Linux v3.21 shell command"),
                                "title" to Schema.string("Short 2-5 word label"),
                                "category" to Schema.enumeration(
                                    listOf("snippet", "optimization", "fix", "completion"),
                                    "Suggestion category"
                                ),
                                "reason" to Schema.string("Concise 1-sentence reason"),
                            )
                        ),
                        description = "Up to 3 relevant real-time shell suggestions"
                    )
                )
            )
            val model = getStructuredModel(
                modelName = modelName,
                temperature = temperature.toFloat(),
                schema = schema,
                systemPrompt = "You are an Alpine Linux v3.21 (musl/ash + POSIX) terminal copilot. Suggest concise, accurate, safe shell commands and system optimization snippets."
            )

            val prompt = buildString {
                appendLine("Current working directory: $currentDir")
                appendLine("User current input: \"$partialInput\"")
                if (!lastCommand.isNullOrBlank()) appendLine("Last executed command: $lastCommand")
                if (!lastError.isNullOrBlank()) appendLine("Last command error: $lastError")
                appendLine("Provide 2 to 3 actionable shell command or code snippet suggestions.")
            }

            val response = withTimeout(30_000L) {
                model.generateContent(prompt)
            }
            val raw = response.text ?: throw IllegalStateException("Empty AI suggestion response.")
            val json = JSONObject(raw)
            val arr = json.optJSONArray("suggestions") ?: return Result.success(emptyList())
            val parsed = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AiSuggestion(
                    command = obj.optString("command").trim(),
                    title = obj.optString("title").trim(),
                    category = obj.optString("category", "snippet"),
                    reason = obj.optString("reason").trim(),
                )
            }.filter { it.command.isNotEmpty() }.take(3)
            Result.success(parsed)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Suggestion timed out", e)
            Result.failure(Exception("AI suggestion timed out."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Suggestion failed", e)
            Result.failure(e)
        }
    }

    suspend fun naturalLanguageToShell(
        spokenOrTypedIntent: String,
        currentDir: String,
        preferredShell: String,
        modelName: String,
    ): Result<String> {
        return try {
            val schema = Schema.obj(
                properties = mapOf(
                    "command" to Schema.string("Single valid Alpine Linux v3.21 shell command"),
                )
            )
            val model = getStructuredModel(
                modelName = modelName,
                temperature = 0.2f,
                schema = schema,
                systemPrompt = "Convert natural language requests into a single executable Alpine Linux v3.21 ($preferredShell) command using standard POSIX/BusyBox/apk utilities. Return only the command."
            )
            val response = withTimeout(30_000L) {
                model.generateContent("CWD: $currentDir\nRequest: $spokenOrTypedIntent")
            }
            val raw = response.text ?: throw IllegalStateException("Empty translation response.")
            val cmd = JSONObject(raw).getString("command").trim()
            Result.success(cmd)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Voice-to-shell timed out", e)
            Result.failure(Exception("AI command translation timed out."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Voice-to-shell failed", e)
            Result.failure(e)
        }
    }

    suspend fun audioToShellCommand(
        audioBytes: ByteArray,
        mimeType: String,
        currentDir: String,
        preferredShell: String,
        modelName: String,
    ): Result<VoiceCommandTranslation> {
        return try {
            if (audioBytes.isEmpty()) {
                return Result.failure(IllegalArgumentException("Recorded audio clip is empty."))
            }
            if (audioBytes.size > 15 * 1024 * 1024) {
                return Result.failure(IllegalArgumentException("Audio clip exceeds 15MB limit."))
            }
            val schema = Schema.obj(
                properties = mapOf(
                    "transcript" to Schema.string("Exact verbatim transcription of the user's spoken audio"),
                    "command" to Schema.string("Single valid executable Alpine Linux v3.21 shell command"),
                    "explanation" to Schema.string("Brief 1-sentence summary of what the command does"),
                )
            )
            val model = getStructuredModel(
                modelName = modelName,
                temperature = 0.2f,
                schema = schema,
                systemPrompt = "You are a voice-driven Alpine Linux v3.21 ($preferredShell) terminal assistant. Transcribe the user's spoken audio accurately and map their intent to a single safe POSIX/BusyBox/apk shell command."
            )
            val prompt = content {
                inlineData(audioBytes, mimeType)
                text(
                    "Current working directory: $currentDir. " +
                        "Transcribe what was spoken and return the exact Alpine Linux v3.21 ($preferredShell) shell command."
                )
            }
            val response = withTimeout(45_000L) {
                model.generateContent(prompt)
            }
            val raw = response.text ?: throw IllegalStateException("Empty audio translation response.")
            val json = JSONObject(raw)
            val transcript = json.optString("transcript").trim()
            val command = json.optString("command").trim()
            val explanation = json.optString("explanation").trim()
            if (command.isEmpty()) {
                Result.failure(IllegalStateException("Could not determine a shell command from the recording."))
            } else {
                Result.success(
                    VoiceCommandTranslation(
                        transcript = transcript.ifEmpty { command },
                        command = command,
                        explanation = explanation,
                    )
                )
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Audio-to-shell timed out", e)
            Result.failure(Exception("Voice audio processing timed out after 45s."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Audio-to-shell failed", e)
            Result.failure(e)
        }
    }

    suspend fun transcribeAudio(
        audioBytes: ByteArray,
        mimeType: String,
        modelName: String,
    ): Result<String> {
        return try {
            if (audioBytes.isEmpty()) {
                return Result.failure(IllegalArgumentException("Recorded audio clip is empty."))
            }
            val schema = Schema.obj(
                properties = mapOf(
                    "transcript" to Schema.string("Verbatim transcription of the spoken audio"),
                )
            )
            val model = getStructuredModel(
                modelName = modelName,
                temperature = 0.1f,
                schema = schema,
                systemPrompt = "Transcribe the user's spoken prompt verbatim with accurate technical Linux/programming terminology."
            )
            val response = withTimeout(45_000L) {
                model.generateContent(
                    content {
                        inlineData(audioBytes, mimeType)
                        text("Transcribe this voice prompt verbatim.")
                    }
                )
            }
            val raw = response.text ?: throw IllegalStateException("Empty audio transcription response.")
            val transcript = JSONObject(raw).optString("transcript").trim()
            if (transcript.isEmpty()) {
                Result.failure(IllegalStateException("No speech detected in audio recording."))
            } else {
                Result.success(transcript)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Audio transcription timed out", e)
            Result.failure(Exception("Audio transcription timed out."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Audio transcription failed", e)
            Result.failure(e)
        }
    }

    suspend fun analyzeSystemBottlenecks(
        metrics: SystemMetricsSnapshot,
        recentCommands: List<String>,
        cpuThreshold: Long,
        memThreshold: Long,
        modelName: String,
        temperature: Double,
    ): Result<BottleneckAnalysis> {
        return try {
            val schema = Schema.obj(
                properties = mapOf(
                    "severity" to Schema.enumeration(
                        listOf("nominal", "warning", "critical"),
                        "Overall health severity"
                    ),
                    "bottleneckArea" to Schema.string("Primary resource area (CPU, Memory, Disk I/O, Network, or Balanced)"),
                    "summary" to Schema.string("Concise 2-sentence engineering diagnosis"),
                    "actions" to Schema.array(
                        items = Schema.obj(
                            properties = mapOf(
                                "title" to Schema.string("Short optimization action title"),
                                "impact" to Schema.string("Expected impact e.g. 'Reduces RAM pressure'"),
                                "command" to Schema.string("Safe executable Alpine Linux command"),
                                "rationale" to Schema.string("1-sentence technical explanation"),
                            )
                        ),
                        description = "2 to 3 actionable optimization commands"
                    )
                )
            )
            val model = getStructuredModel(
                modelName = modelName,
                temperature = temperature.toFloat(),
                schema = schema,
                systemPrompt = "You are a senior Linux kernel & Android systems performance engineer analyzing real-time Alpine Linux v3.21 rootfs telemetry. Provide concrete, accurate bottleneck diagnostics and safe executable shell optimization/inspection commands."
            )
            val prompt = buildString {
                appendLine("Real-Time Telemetry Snapshot:")
                appendLine("- CPU: ${"%.1f".format(metrics.cpuPercent)}% (threshold: $cpuThreshold%, cores: ${metrics.cpuCores}, freq: ${metrics.cpuFreqMhz} MHz)")
                appendLine("- Memory: ${metrics.memUsedMb}MB / ${metrics.memTotalMb}MB (${"%.1f".format(metrics.memPercent)}%, threshold: $memThreshold%, JVM heap: ${metrics.jvmHeapUsedMb}MB)")
                appendLine("- Disk: ${"%.2f".format(metrics.diskUsedGb)}GB / ${"%.2f".format(metrics.diskTotalGb)}GB (${"%.1f".format(metrics.diskPercent)}%), I/O rate: ${"%.1f".format(metrics.diskIoKbps)} KB/s")
                appendLine("- Network: RX ${"%.1f".format(metrics.netRxKbps)} KB/s, TX ${"%.1f".format(metrics.netTxKbps)} KB/s, session total: ${"%.2f".format(metrics.totalNetMb)} MB")
                appendLine("- Active threads: ${metrics.processCount}, Uptime: ${metrics.uptimeSeconds}s")
                if (recentCommands.isNotEmpty()) {
                    appendLine("- Recent shell commands: ${recentCommands.takeLast(5).joinToString(" | ")}")
                }
            }
            val response = withTimeout(45_000L) {
                model.generateContent(prompt)
            }
            val raw = response.text ?: throw IllegalStateException("Empty bottleneck analysis response.")
            val json = JSONObject(raw)
            val actionsJson = json.optJSONArray("actions")
            val actions = if (actionsJson != null) {
                (0 until actionsJson.length()).map { i ->
                    val o = actionsJson.getJSONObject(i)
                    OptimizationAction(
                        title = o.optString("title"),
                        impact = o.optString("impact"),
                        command = o.optString("command"),
                        rationale = o.optString("rationale"),
                    )
                }
            } else {
                emptyList()
            }
            Result.success(
                BottleneckAnalysis(
                    severity = json.optString("severity", "nominal"),
                    bottleneckArea = json.optString("bottleneckArea", "Balanced"),
                    summary = json.optString("summary", "System resources are operating within expected parameters."),
                    actions = actions,
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Bottleneck analysis timed out", e)
            Result.failure(Exception("Bottleneck analysis timed out after 45s."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Bottleneck analysis failed", e)
            Result.failure(e)
        }
    }

    suspend fun generateCode(
        description: String,
        targetLanguage: String,
        preferredShell: String,
        modelName: String,
        temperature: Double,
    ): Result<CodeGenResult> {
        return try {
            val schema = Schema.obj(
                properties = mapOf(
                    "title" to Schema.string("Filename with extension e.g. log_rotate.sh"),
                    "language" to Schema.enumeration(
                        listOf("sh", "bash", "python", "c", "awk", "sed", "dockerfile", "other"),
                        "Language identifier"
                    ),
                    "code" to Schema.string("Complete clean, runnable source code"),
                    "explanation" to Schema.string("Clear technical summary of how the script works"),
                    "apkDependencies" to Schema.array(
                        items = Schema.string(),
                        description = "Optional Alpine Linux v3.21 apk packages needed"
                    ),
                    "runCommand" to Schema.string("Exact command to execute this file inside ~/scripts/"),
                )
            )
            val model = getStructuredModel(
                modelName = modelName,
                temperature = temperature.toFloat(),
                schema = schema,
                systemPrompt = "You are an expert Linux systems programmer targeting Alpine Linux v3.21 (musl, BusyBox ash, $preferredShell). Generate clean, idiomatic, production-safe scripts and snippets."
            )
            val response = withTimeout(60_000L) {
                model.generateContent(
                    "Target language: $targetLanguage\n" +
                        "Preferred shell: $preferredShell\n" +
                        "Task description: $description"
                )
            }
            val raw = response.text ?: throw IllegalStateException("Empty code generation response.")
            val json = JSONObject(raw)
            val depsArr = json.optJSONArray("apkDependencies")
            val deps = if (depsArr != null) {
                (0 until depsArr.length()).map { depsArr.getString(it) }
            } else {
                emptyList()
            }
            Result.success(
                CodeGenResult(
                    title = json.optString("title", "generated_script.$targetLanguage"),
                    language = json.optString("language", targetLanguage),
                    code = json.optString("code"),
                    explanation = json.optString("explanation"),
                    apkDependencies = deps,
                    runCommand = json.optString("runCommand"),
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Code generation timed out", e)
            Result.failure(Exception("Code generation timed out after 60s."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Code generation failed", e)
            Result.failure(e)
        }
    }

    suspend fun debugCode(
        sourceCode: String,
        errorOutput: String,
        language: String,
        modelName: String,
        temperature: Double,
    ): Result<DebugReport> {
        return try {
            val schema = Schema.obj(
                properties = mapOf(
                    "rootCause" to Schema.string("Concise explanation of the primary bug or failure"),
                    "issues" to Schema.array(
                        items = Schema.obj(
                            properties = mapOf(
                                "lineHint" to Schema.string("Line number or symbol reference"),
                                "severity" to Schema.enumeration(listOf("error", "warning", "optimization")),
                                "description" to Schema.string("What is wrong"),
                                "fixSummary" to Schema.string("How it was fixed"),
                            )
                        )
                    ),
                    "fixedCode" to Schema.string("Complete corrected source code"),
                    "optimizationNotes" to Schema.string("Performance or POSIX portability notes"),
                    "testCommand" to Schema.string("Command to verify the fixed script"),
                )
            )
            val model = getStructuredModel(
                modelName = modelName,
                temperature = temperature.toFloat(),
                schema = schema,
                systemPrompt = "You are an expert Linux/POSIX debugger and code reviewer for Alpine Linux v3.21. Identify syntax, logic, security, and performance bugs accurately and provide a drop-in corrected implementation."
            )
            val prompt = buildString {
                appendLine("Language: $language")
                if (errorOutput.isNotBlank()) {
                    appendLine("Observed error / stderr:\n$errorOutput")
                }
                appendLine("Source code to debug:\n$sourceCode")
            }
            val response = withTimeout(60_000L) {
                model.generateContent(prompt)
            }
            val raw = response.text ?: throw IllegalStateException("Empty debug response.")
            val json = JSONObject(raw)
            val issuesArr = json.optJSONArray("issues")
            val issues = if (issuesArr != null) {
                (0 until issuesArr.length()).map { i ->
                    val o = issuesArr.getJSONObject(i)
                    DebugIssue(
                        lineHint = o.optString("lineHint", "General"),
                        severity = o.optString("severity", "error"),
                        description = o.optString("description"),
                        fixSummary = o.optString("fixSummary"),
                    )
                }
            } else {
                emptyList()
            }
            Result.success(
                DebugReport(
                    rootCause = json.optString("rootCause"),
                    issues = issues,
                    fixedCode = json.optString("fixedCode"),
                    optimizationNotes = json.optString("optimizationNotes"),
                    testCommand = json.optString("testCommand"),
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Debugging timed out", e)
            Result.failure(Exception("Code debugging timed out after 60s."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Debugging failed", e)
            Result.failure(e)
        }
    }

    suspend fun sendChatMessage(
        history: List<ChatMessage>,
        newUserMessage: String,
        preferredShell: String,
        modelName: String,
        temperature: Double,
    ): Result<String> {
        return try {
            val safeModel = if (modelName in ALLOWED_MODELS) modelName else "gemini-3.8-flash"
            val chatModel = if (safeModel == "gemini-3.8-flash" && temperature == 0.4) {
                defaultModel
            } else {
                Firebase.ai.generativeModel(
                    modelName = safeModel,
                    generationConfig = generationConfig {
                        this.temperature = temperature.toFloat().coerceIn(0f, 1f)
                    },
                    systemInstruction = content {
                        text(
                            "You are Alpaca AI, a senior Linux sysadmin and shell/code mentor embedded inside Alpaca Shell ($preferredShell). Give crisp, practical answers with runnable shell commands when helpful."
                        )
                    }
                )
            }
            val chatHistory = history
                .filter { !it.isError && it.content.isNotBlank() }
                .map { msg ->
                    content(role = if (msg.role == "user") "user" else "model") {
                        text(msg.content)
                    }
                }
            val chat = chatModel.startChat(history = chatHistory)
            val response = withTimeout(60_000L) {
                chat.sendMessage(newUserMessage)
            }
            val text = response.text?.trim()
            if (text.isNullOrEmpty()) {
                Result.failure(IllegalStateException("Model returned an empty response."))
            } else {
                Result.success(text)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Chat timed out", e)
            Result.failure(Exception("AI response timed out after 60s."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Chat failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "AiAssistantRepo"
        private val ALLOWED_MODELS = setOf(
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite",
            "gemini-4-argon"
        )
    }
}
