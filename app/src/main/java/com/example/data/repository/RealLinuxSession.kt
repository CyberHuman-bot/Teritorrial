package com.example.data.repository

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RealLinuxSession(context: Context) {
    private val filesRoot = context.applicationContext.filesDir
    val workspaceDir = File(filesRoot, "linux_workspace").apply { mkdirs() }
    val scriptsDir = File(workspaceDir, "scripts").apply { mkdirs() }
    val projectsDir = File(workspaceDir, "projects").apply { mkdirs() }
    private val tmpDir = File(workspaceDir, "tmp").apply { mkdirs() }

    private val lock = Any()
    private val seq = AtomicInteger(0)

    private var process: Process? = null
    private var stdinWriter: BufferedWriter? = null
    private val stdoutQueue = LinkedBlockingQueue<String>()
    private val stderrQueue = LinkedBlockingQueue<String>()
    private var stdoutThread: Thread? = null
    private var stderrThread: Thread? = null

    @Volatile
    private var currentHostDir: File = workspaceDir

    init {
        migrateLegacyScriptsIfNeeded()
        seedWorkspaceFilesIfNeeded()
    }

    fun currentDisplayDir(): String {
        val wsPath = workspaceDir.canonicalPath
        val curPath = currentHostDir.canonicalPath
        return when {
            curPath == wsPath -> "~"
            curPath.startsWith("$wsPath/") -> "~/" + curPath.removePrefix("$wsPath/")
            else -> curPath
        }
    }

    fun currentAbsoluteDir(): String = currentHostDir.canonicalPath

    fun resolveWorkspaceFile(path: String): File {
        val trimmed = path.trim()
        return when {
            trimmed == "~" -> workspaceDir
            trimmed.startsWith("~/") -> File(workspaceDir, trimmed.removePrefix("~/"))
            trimmed.startsWith("/home/alpine/") -> File(workspaceDir, trimmed.removePrefix("/home/alpine/"))
            trimmed.startsWith("/home/alpaca/") -> File(workspaceDir, trimmed.removePrefix("/home/alpaca/"))
            trimmed.startsWith("/") -> File(trimmed)
            else -> File(currentHostDir, trimmed)
        }.canonicalFile
    }

    fun execute(commandLine: String, timeoutSeconds: Long = 15L): ShellExecutionResult {
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) {
            return ShellExecutionResult("", "", 0, newPromptPath = currentDisplayDir())
        }
        if (trimmed == "clear") {
            return ShellExecutionResult("", "", 0, clearRequested = true, newPromptPath = currentDisplayDir())
        }

        synchronized(lock) {
            ensureShellRunning()
            val writer = stdinWriter ?: return ShellExecutionResult(
                stdout = "",
                stderr = "Failed to start local Linux shell (/system/bin/sh).",
                exitCode = 127,
                newPromptPath = currentDisplayDir(),
            )

            stdoutQueue.clear()
            stderrQueue.clear()

            val tokenId = seq.incrementAndGet()
            val outMarker = "__LINUX_OUT_DONE_${tokenId}__"
            val errMarker = "__LINUX_ERR_DONE_${tokenId}__"
            val preparedCmd = expandWorkspaceTilde(trimmed)

            try {
                writer.write(preparedCmd)
                writer.newLine()
                writer.write("__os_rc=\$?")
                writer.newLine()
                writer.write("printf '\\n%s:%s:%s\\n' '$outMarker' \"\$__os_rc\" \"\$PWD\"")
                writer.newLine()
                writer.write("printf '%s\\n' '$errMarker' >&2")
                writer.newLine()
                writer.flush()
            } catch (e: Exception) {
                destroyShellLocked()
                return ShellExecutionResult(
                    stdout = "",
                    stderr = "Shell write error: ${e.localizedMessage ?: "broken pipe"}",
                    exitCode = 1,
                    newPromptPath = currentDisplayDir(),
                )
            }

            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            val outLines = mutableListOf<String>()
            var exitCode = 0
            var completed = false

            while (true) {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
                if (remainingMs <= 0L) break
                val line = stdoutQueue.poll(remainingMs, TimeUnit.MILLISECONDS) ?: break
                if (line == EOF_SENTINEL) break

                if (line.startsWith("$outMarker:")) {
                    val payload = line.removePrefix("$outMarker:")
                    val colonIdx = payload.indexOf(':')
                    if (colonIdx != -1) {
                        exitCode = payload.substring(0, colonIdx).toIntOrNull() ?: 0
                        val newPwd = payload.substring(colonIdx + 1)
                        if (newPwd.isNotBlank()) {
                            val dir = File(newPwd)
                            if (dir.exists() && dir.isDirectory) {
                                currentHostDir = dir
                            }
                        }
                    }
                    completed = true
                    break
                } else {
                    outLines.add(line)
                }
            }

            if (!completed) {
                val partialOut = outLines.joinToString("\n").trimEnd()
                val partialErr = drainRemaining(stderrQueue, errMarker)
                destroyShellLocked()
                val timeoutMsg = "Process timed out after ${timeoutSeconds}s and shell was reset."
                val combinedErr = if (partialErr.isBlank()) timeoutMsg else "$partialErr\n$timeoutMsg"
                return ShellExecutionResult(
                    stdout = partialOut,
                    stderr = combinedErr,
                    exitCode = 124,
                    newPromptPath = currentDisplayDir(),
                )
            }

            // Wait briefly for stderr marker so stderr lines from the command arrive completely
            val errLines = mutableListOf<String>()
            val errDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400L)
            while (true) {
                val remMs = TimeUnit.NANOSECONDS.toMillis(errDeadline - System.nanoTime())
                if (remMs <= 0L) break
                val errLine = stderrQueue.poll(remMs, TimeUnit.MILLISECONDS) ?: break
                if (errLine == EOF_SENTINEL || errLine == errMarker) break
                errLines.add(errLine)
            }

            // Trim the extra leading newline injected by printf '\n%s...' when stdout was empty
            val rawStdout = outLines.joinToString("\n").trimEnd('\n')
            val rawStderr = errLines.joinToString("\n").trimEnd('\n')
            return ShellExecutionResult(
                stdout = rawStdout,
                stderr = rawStderr,
                exitCode = exitCode,
                newPromptPath = currentDisplayDir(),
            )
        }
    }

    fun interrupt() {
        synchronized(lock) {
            destroyShellLocked()
        }
    }

    fun detectShellBinary(): String {
        return listOf("/system/bin/sh", "/bin/sh").firstOrNull { File(it).exists() } ?: "/system/bin/sh"
    }

    private fun ensureShellRunning() {
        val current = process
        if (current != null && current.isAlive) return
        destroyShellLocked()

        val sh = detectShellBinary()
        val pb = ProcessBuilder(sh)
        pb.directory(if (currentHostDir.exists() && currentHostDir.isDirectory) currentHostDir else workspaceDir)
        val env = pb.environment()
        env["HOME"] = workspaceDir.canonicalPath
        env["TMPDIR"] = tmpDir.canonicalPath
        env["TERM"] = "xterm-256color"
        val existingPath = env["PATH"].orEmpty()
        env["PATH"] = if (existingPath.isBlank()) {
            "/system/bin:/system/xbin:/sbin:/vendor/bin:/usr/local/bin:/usr/bin:/bin"
        } else {
            "/system/bin:/system/xbin:$existingPath"
        }

        val proc = pb.start()
        process = proc
        stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))

        stdoutThread = Thread {
            try {
                BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        stdoutQueue.offer(line)
                    }
                }
            } catch (_: Exception) {
            } finally {
                stdoutQueue.offer(EOF_SENTINEL)
            }
        }.apply {
            name = "RealLinuxStdoutReader"
            isDaemon = true
            start()
        }

        stderrThread = Thread {
            try {
                BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        stderrQueue.offer(line)
                    }
                }
            } catch (_: Exception) {
            } finally {
                stderrQueue.offer(EOF_SENTINEL)
            }
        }.apply {
            name = "RealLinuxStderrReader"
            isDaemon = true
            start()
        }
    }

    private fun destroyShellLocked() {
        try {
            stdinWriter?.close()
        } catch (_: Exception) {
        }
        stdinWriter = null
        try {
            process?.destroyForcibly()
        } catch (_: Exception) {
        }
        process = null
        stdoutThread = null
        stderrThread = null
    }

    private fun drainRemaining(queue: LinkedBlockingQueue<String>, marker: String): String {
        val lines = mutableListOf<String>()
        while (true) {
            val item = queue.poll() ?: break
            if (item == EOF_SENTINEL || item == marker) break
            lines.add(item)
        }
        return lines.joinToString("\n").trimEnd()
    }

    private fun expandWorkspaceTilde(command: String): String {
        val home = workspaceDir.canonicalPath
        return command
            .replace("/home/alpine", home)
            .replace("/home/alpaca", home)
    }

    private fun migrateLegacyScriptsIfNeeded() {
        val candidates = listOf(
            File(filesRoot, "alpine_rootfs/home/alpine/scripts"),
            File(filesRoot, "alpaca_rootfs/home/alpaca/scripts"),
        )
        for (dir in candidates) {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        val dest = File(scriptsDir, file.name)
                        if (!dest.exists()) {
                            file.copyTo(dest, overwrite = false)
                        }
                    }
                }
            }
        }
    }

    private fun seedWorkspaceFilesIfNeeded() {
        val readme = File(workspaceDir, "README.md")
        if (!readme.exists()) {
            readme.writeText(
                "# Real Linux Kernel Workspace (/system/bin/sh)\n" +
                    "- Commands execute directly in a persistent Android Linux kernel shell.\n" +
                    "- Inspect live hardware/kernel: `uname -a`, `cat /proc/cpuinfo`, `cat /proc/meminfo`, `ps -A`, `df -h`.\n" +
                    "- For full distro containers (Alpine apk / Ubuntu apt / Docker), tap the Container SSH pill in the header.\n"
            )
        }
        val sysinfo = File(workspaceDir, "sysinfo.sh")
        if (!sysinfo.exists()) {
            sysinfo.writeText(
                "#!/system/bin/sh\n" +
                    "echo \"=== Live Linux Kernel Telemetry ===\"\n" +
                    "uname -a\n" +
                    "id\n" +
                    "uptime\n" +
                    "df -h .\n"
            )
            sysinfo.setExecutable(true)
        }
    }

    companion object {
        private const val EOF_SENTINEL = "__ALPACA_EOF_SENTINEL__"
    }
}
