package com.example.data.repository

import android.content.Context
import com.example.data.model.SystemMetricsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ShellExecutionResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val clearRequested: Boolean = false,
    val newPromptPath: String = "~",
)

class AlpacaBootstrapEngine(
    context: Context,
    private val systemMonitor: SystemMonitorRepository,
) {
    private val linuxSession = RealLinuxSession(context)
    private var preferredShellLabel = "/system/bin/sh"

    fun setShell(shellPath: String) {
        preferredShellLabel = shellPath
    }

    fun currentDisplayDir(): String = linuxSession.currentDisplayDir()

    fun currentAbsoluteDir(): String = linuxSession.currentAbsoluteDir()

    fun detectedShellBinary(): String = linuxSession.detectShellBinary()

    fun interrupt() {
        linuxSession.interrupt()
    }

    suspend fun writeScriptToWorkspace(filename: String, content: String): String =
        withContext(Dispatchers.IO) {
            val sanitized = filename.trim()
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .ifEmpty { "script.sh" }
            val target = File(linuxSession.scriptsDir, sanitized)
            target.writeText(content)
            target.setExecutable(true)
            systemMonitor.recordWorkspaceIoBytes(content.toByteArray().size.toLong())
            target.canonicalPath
        }

    suspend fun readWorkspaceFile(path: String): String? =
        withContext(Dispatchers.IO) {
            val file = linuxSession.resolveWorkspaceFile(path)
            if (file.exists() && file.isFile) {
                val text = file.readText()
                systemMonitor.recordWorkspaceIoBytes(text.toByteArray().size.toLong())
                text
            } else {
                null
            }
        }

    suspend fun listWorkspaceScripts(): List<String> =
        withContext(Dispatchers.IO) {
            linuxSession.scriptsDir.listFiles()
                ?.filter { it.isFile }
                ?.map { "~/scripts/${it.name}" }
                ?.sorted()
                ?: emptyList()
        }

    suspend fun execute(
        rawInput: String,
        metrics: SystemMetricsSnapshot,
        commandHistory: List<String>,
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return@withContext ShellExecutionResult("", "", 0, newPromptPath = currentDisplayDir())
        }

        if (trimmed == "help") {
            return@withContext ShellExecutionResult(
                stdout = helpText(),
                stderr = "",
                exitCode = 0,
                newPromptPath = currentDisplayDir(),
            )
        }

        val result = linuxSession.execute(trimmed)
        if (result.stdout.isNotEmpty()) {
            systemMonitor.recordWorkspaceIoBytes(result.stdout.toByteArray().size.toLong())
        }

        // Provide an honest architectural hint if user invokes distro package managers locally on Android SELinux
        val firstWord = trimmed.substringBefore(' ').trim()
        if (result.exitCode == 127 && firstWord in setOf("apk", "apt", "apt-get", "pacman", "yum", "dnf", "docker")) {
            val hint = "Note: Local mode executes directly on Android's Linux kernel (${detectedShellBinary()}). " +
                "Android targetSdk 36 SELinux blocks running downloaded distro ELF binaries in app storage. " +
                "Tap 'Container' in the header to attach an interactive SSH2 Alpine/Ubuntu/Docker container."
            val combinedErr = if (result.stderr.isBlank()) hint else "${result.stderr}\n$hint"
            return@withContext result.copy(stderr = combinedErr)
        }

        result
    }

    private fun helpText(): String =
        """
        Real Linux Execution Modes (No Simulation):
          1. Local Kernel Shell (${detectedShellBinary()}):
             Persistent OS shell process running directly on this device's Linux kernel.
             Inspect live OS & kernel: uname -a, cat /proc/version, cat /proc/cpuinfo,
             cat /proc/meminfo, ps, df -h, id, env, ls -la, find, grep, awk, sed, tar, sh ~/sysinfo.sh
          2. Real Linux Container (Header -> Container):
             Connect via SSH2 PTY to an actual Alpine, Ubuntu, Debian, Docker, or Termux proot-distro container
             for full apk/apt package management, compilers, git, and interactive Linux userland.
          Keys: TAB (complete), ^C (interrupt running process), ↑/↓ (history), clear (wipe buffer)
        """.trimIndent()
}
