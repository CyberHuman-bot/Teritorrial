package com.example.data.repository

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class PosixShellBridge(
    private val rootDir: File,
    private val systemMonitor: SystemMonitorRepository,
) {
    private val shellBinary: String?
        get() = listOf("/system/bin/sh", "/bin/sh").firstOrNull { File(it).exists() }

    fun supportsNativePosix(): Boolean = shellBinary != null

    fun rewriteVirtualPathsForHost(rawCommand: String): String {
        val rootPath = rootDir.canonicalPath.trimEnd('/')
        val homeHost = File(rootDir, "home/alpine").canonicalPath
        var out = rawCommand
        // Expand ~/ and standalone ~ to alpine home inside rootfs
        out = out.replace(Regex("(?<=^|\\s|[\"'=:])~/"), "$homeHost/")
        out = out.replace(Regex("(?<=^|\\s|[\"'=:])~(?=$|\\s|[\"'])"), homeHost)
        // Map legacy /home/alpaca references to /home/alpine
        out = out.replace("/home/alpaca", "/home/alpine")
        // Map virtual Alpine rootfs prefixes into the real app rootfs directory
        val virtualPrefixes = listOf("/etc", "/home", "/var", "/lib", "/tmp", "/usr", "/bin", "/sbin")
        for (prefix in virtualPrefixes) {
            out = out.replace(
                Regex("(?<=^|\\s|[\"'=:])${Regex.escape(prefix)}(?=/|\\s|$|[\"';|&])"),
                "$rootPath$prefix"
            )
        }
        return out
    }

    fun executePosixCommand(
        commandLine: String,
        currentHostDir: File,
        currentDisplayDir: String,
        envVars: Map<String, String>,
        stdin: String? = null,
    ): ShellExecutionResult {
        val sh = shellBinary ?: return ShellExecutionResult(
            stdout = "",
            stderr = "ash: POSIX shell binary unavailable in this environment",
            exitCode = 127,
            newPromptPath = currentDisplayDir,
        )

        return try {
            val mappedCommand = rewriteVirtualPathsForHost(commandLine)
            val pb = ProcessBuilder(sh, "-c", mappedCommand)
            val safeDir = if (currentHostDir.exists() && currentHostDir.isDirectory) currentHostDir else rootDir
            pb.directory(safeDir)

            val env = pb.environment()
            val homeHost = File(rootDir, "home/alpine").apply { mkdirs() }.canonicalPath
            val tmpHost = File(rootDir, "tmp").apply { mkdirs() }.canonicalPath
            env.putAll(envVars)
            env["HOME"] = homeHost
            env["TMPDIR"] = tmpHost
            env["ALPINE_ROOT"] = rootDir.canonicalPath
            val hostPath = env["PATH"].orEmpty()
            if (!hostPath.contains("/system/bin")) {
                env["PATH"] = if (hostPath.isBlank()) "/system/bin:/system/xbin:/usr/bin:/bin"
                else "/system/bin:/system/xbin:$hostPath"
            }

            val process = pb.start()
            if (stdin != null) {
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(stdin)
                    if (!stdin.endsWith("\n")) writer.newLine()
                }
            } else {
                process.outputStream.close()
            }

            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ShellExecutionResult("", "ash: command timed out after 8s", 124, newPromptPath = currentDisplayDir)
            }

            val rootPrefix = rootDir.canonicalPath.trimEnd('/')
            val stdout = process.inputStream.bufferedReader().readText()
                .replace(rootPrefix, "")
                .trimEnd()
            val stderr = process.errorStream.bufferedReader().readText()
                .replace(rootPrefix, "")
                .trimEnd()
            ShellExecutionResult(stdout, stderr, process.exitValue(), newPromptPath = currentDisplayDir)
        } catch (e: Exception) {
            ShellExecutionResult(
                stdout = "",
                stderr = "ash: ${e.localizedMessage ?: "native execution failed"}",
                exitCode = 1,
                newPromptPath = currentDisplayDir,
            )
        }
    }

    fun executeSystemBinary(cmd: String, args: List<String>, currentDisplayDir: String): ShellExecutionResult {
        return try {
            val binary = listOf("/system/bin/$cmd", "/usr/bin/$cmd", "/bin/$cmd")
                .firstOrNull { File(it).exists() }
                ?: return ShellExecutionResult(
                    stdout = "",
                    stderr = "ash: $cmd: utility unavailable on this Android build",
                    exitCode = 127,
                    newPromptPath = currentDisplayDir,
                )

            val safeArgs = args.take(8)
            val pb = ProcessBuilder(listOf(binary) + safeArgs)
            val process = pb.start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ShellExecutionResult("", "$cmd: timed out after 5s", 124, newPromptPath = currentDisplayDir)
            }
            val stdout = process.inputStream.bufferedReader().readText().trimEnd()
            val stderr = process.errorStream.bufferedReader().readText().trimEnd()
            ShellExecutionResult(stdout, stderr, process.exitValue(), newPromptPath = currentDisplayDir)
        } catch (e: Exception) {
            ShellExecutionResult("", "$cmd: ${e.localizedMessage ?: "execution failed"}", 1, newPromptPath = currentDisplayDir)
        }
    }

    fun executeCurl(
        args: List<String>,
        currentDisplayDir: String,
        resolveFile: (String) -> File,
    ): ShellExecutionResult {
        if (args.isEmpty()) {
            return ShellExecutionResult("", "curl: try 'curl https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/'", 2, newPromptPath = currentDisplayDir)
        }
        var headOnly = false
        var silent = false
        var outputPath: String? = null
        var targetUrl: String? = null
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "-I", "--head" -> headOnly = true
                "-s", "--silent", "-sS", "-fsSL", "-fL" -> silent = true
                "-L", "--location" -> Unit
                "-o", "--output" -> {
                    outputPath = args.getOrNull(i + 1)
                    i++
                }
                else -> if (!arg.startsWith("-")) {
                    targetUrl = arg
                }
            }
            i++
        }
        val rawUrl = targetUrl ?: return ShellExecutionResult("", "curl: no URL specified", 3, newPromptPath = currentDisplayDir)
        val normalizedUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl else "https://$rawUrl"

        return try {
            val conn = (URL(normalizedUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                requestMethod = if (headOnly) "HEAD" else "GET"
                setRequestProperty("User-Agent", "curl/8.12.1 (Alpine Linux 3.21)")
            }
            val code = conn.responseCode
            val msg = conn.responseMessage.orEmpty()

            if (headOnly) {
                val headers = buildString {
                    appendLine("HTTP/1.1 $code $msg")
                    conn.headerFields.forEach { (k, v) ->
                        if (k != null) appendLine("$k: ${v.joinToString(", ")}")
                    }
                }.trimEnd()
                conn.disconnect()
                return ShellExecutionResult(headers, "", if (code in 200..399) 0 else 22, newPromptPath = currentDisplayDir)
            }

            val stream = if (code in 200..399) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val bytes = stream.use { it.readBytes() }
            conn.disconnect()
            systemMonitor.recordWorkspaceIoBytes(bytes.size.toLong())

            if (outputPath != null) {
                val outFile = resolveFile(outputPath)
                outFile.parentFile?.mkdirs()
                outFile.writeBytes(bytes)
                val info = if (silent) "" else "Saved ${bytes.size} bytes to $outputPath (HTTP $code)"
                ShellExecutionResult(info, "", if (code in 200..399) 0 else 22, newPromptPath = currentDisplayDir)
            } else {
                val body = String(bytes, Charsets.UTF_8).trimEnd()
                ShellExecutionResult(body, "", if (code in 200..399) 0 else 22, newPromptPath = currentDisplayDir)
            }
        } catch (e: Exception) {
            ShellExecutionResult("", "curl: (${e.javaClass.simpleName}) ${e.localizedMessage ?: "request failed"}", 7, newPromptPath = currentDisplayDir)
        }
    }
}
