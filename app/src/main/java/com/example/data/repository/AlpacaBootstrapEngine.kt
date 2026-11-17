package com.example.data.repository

import android.content.Context
import android.os.Build
import com.example.data.model.SystemMetricsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

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
    private val rootDir = File(context.applicationContext.filesDir, "alpaca_rootfs")
    private val homeVirtualPath = "/home/alpaca"
    private var currentVirtualDir = homeVirtualPath

    private val envVars = linkedMapOf(
        "USER" to "alpaca",
        "LOGNAME" to "alpaca",
        "HOME" to "/home/alpaca",
        "SHELL" to "/bin/ash",
        "TERM" to "xterm-256color",
        "DISTRO" to "Alpaca Linux 3.21.0-bootstrap",
        "PATH" to "/usr/local/bin:/usr/bin:/bin:/sbin",
        "LANG" to "C.UTF-8",
    )

    private val apkRepositoryCatalog = mapOf(
        "curl" to "8.11.1-r0 - URL retrieval utility and library",
        "git" to "2.47.1-r0 - Distributed version control system",
        "htop" to "3.3.0-r0 - Interactive process viewer",
        "python3" to "3.12.8-r0 - High-level scripting language",
        "jq" to "1.7.1-r0 - Lightweight command-line JSON processor",
        "vim" to "9.1.0800-r0 - Improved vi text editor",
        "bash" to "5.2.37-r0 - GNU Bourne Again SHell",
        "zsh" to "5.9-r4 - Very advanced programmable command interpreter",
        "gawk" to "5.3.1-r0 - GNU pattern scanning and processing language",
        "sqlite" to "3.47.2-r0 - C library implementing an SQL database engine",
        "openssh-client" to "9.9_p1-r2 - OpenBSD SSH client utilities",
        "iperf3" to "3.17.1-r0 - Network bandwidth measurement tool",
        "strace" to "6.12-r0 - System call tracer",
        "tar" to "1.35-r2 - Utility used to store, backup, and transport files",
        "musl-dev" to "1.2.5-r8 - musl C library development headers",
    )

    init {
        bootstrapRootFsIfNeeded()
    }

    fun setShell(shellPath: String) {
        envVars["SHELL"] = shellPath
    }

    fun currentDisplayDir(): String {
        return if (currentVirtualDir == homeVirtualPath) "~"
        else if (currentVirtualDir.startsWith("$homeVirtualPath/")) {
            "~/" + currentVirtualDir.removePrefix("$homeVirtualPath/")
        } else {
            currentVirtualDir
        }
    }

    suspend fun writeScriptToWorkspace(filename: String, content: String): String =
        withContext(Dispatchers.IO) {
            bootstrapRootFsIfNeeded()
            val sanitized = filename.trim()
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .ifEmpty { "script.sh" }
            val scriptsDir = resolveHostFile("/home/alpaca/scripts")
            scriptsDir.mkdirs()
            val target = File(scriptsDir, sanitized)
            target.writeText(content)
            systemMonitor.recordWorkspaceIoBytes(content.toByteArray().size.toLong())
            "/home/alpaca/scripts/$sanitized"
        }

    suspend fun readWorkspaceFile(virtualPath: String): String? =
        withContext(Dispatchers.IO) {
            bootstrapRootFsIfNeeded()
            val file = resolveHostFile(virtualPath)
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
            bootstrapRootFsIfNeeded()
            val scriptsDir = resolveHostFile("/home/alpaca/scripts")
            scriptsDir.listFiles()?.map { "/home/alpaca/scripts/${it.name}" }?.sorted() ?: emptyList()
        }

    suspend fun execute(
        rawInput: String,
        metrics: SystemMetricsSnapshot,
        commandHistory: List<String>,
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        bootstrapRootFsIfNeeded()
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return@withContext ShellExecutionResult("", "", 0, newPromptPath = currentDisplayDir())
        }

        // Split chained commands by && or ; (respecting quotes simply)
        val segments = splitChainedCommands(trimmed)
        val outBuilder = StringBuilder()
        val errBuilder = StringBuilder()
        var lastExitCode = 0
        var clearReq = false

        for ((operator, cmdSegment) in segments) {
            if (operator == "&&" && lastExitCode != 0) break
            val res = executeSingleCommandLine(cmdSegment, metrics, commandHistory)
            if (res.clearRequested) {
                clearReq = true
                outBuilder.clear()
                errBuilder.clear()
            } else {
                if (res.stdout.isNotEmpty()) {
                    if (outBuilder.isNotEmpty()) outBuilder.append('\n')
                    outBuilder.append(res.stdout)
                }
                if (res.stderr.isNotEmpty()) {
                    if (errBuilder.isNotEmpty()) errBuilder.append('\n')
                    errBuilder.append(res.stderr)
                }
            }
            lastExitCode = res.exitCode
        }

        ShellExecutionResult(
            stdout = outBuilder.toString(),
            stderr = errBuilder.toString(),
            exitCode = lastExitCode,
            clearRequested = clearReq,
            newPromptPath = currentDisplayDir(),
        )
    }

    private fun executeSingleCommandLine(
        line: String,
        metrics: SystemMetricsSnapshot,
        commandHistory: List<String>,
    ): ShellExecutionResult {
        val expandedLine = expandVariables(line)

        // Check output redirection (> or >>) outside quotes
        var redirectFile: String? = null
        var appendMode = false
        var commandPart = expandedLine

        val appendIdx = findUnquotedOperator(expandedLine, ">>")
        val overwriteIdx = if (appendIdx == -1) findUnquotedOperator(expandedLine, ">") else -1
        if (appendIdx != -1) {
            appendMode = true
            commandPart = expandedLine.substring(0, appendIdx).trim()
            redirectFile = expandedLine.substring(appendIdx + 2).trim()
        } else if (overwriteIdx != -1) {
            appendMode = false
            commandPart = expandedLine.substring(0, overwriteIdx).trim()
            redirectFile = expandedLine.substring(overwriteIdx + 1).trim()
        }

        // Pipeline support (cmd1 | cmd2 | ...)
        val stages = splitByUnquotedPipe(commandPart)
        var pipeInput: String? = null
        var lastResult = ShellExecutionResult("", "", 0, newPromptPath = currentDisplayDir())

        for (stage in stages) {
            lastResult = runAtomicCommand(stage.trim(), pipeInput, metrics, commandHistory)
            if (lastResult.exitCode != 0 && stages.size > 1) {
                return lastResult
            }
            pipeInput = lastResult.stdout
        }

        if (redirectFile != null && lastResult.exitCode == 0) {
            val cleanTarget = tokenize(redirectFile).firstOrNull()
            if (cleanTarget.isNullOrBlank()) {
                return ShellExecutionResult("", "ash: syntax error near unexpected token `newline'", 2, newPromptPath = currentDisplayDir())
            }
            val hostFile = resolveHostFile(cleanTarget)
            hostFile.parentFile?.mkdirs()
            val payload = if (lastResult.stdout.isEmpty()) "" else lastResult.stdout + "\n"
            if (appendMode) {
                hostFile.appendText(payload)
            } else {
                hostFile.writeText(payload)
            }
            systemMonitor.recordWorkspaceIoBytes(payload.toByteArray().size.toLong())
            return ShellExecutionResult("", lastResult.stderr, 0, newPromptPath = currentDisplayDir())
        }

        return lastResult
    }

    private fun runAtomicCommand(
        commandLine: String,
        stdin: String?,
        metrics: SystemMetricsSnapshot,
        commandHistory: List<String>,
    ): ShellExecutionResult {
        val tokens = tokenize(commandLine)
        if (tokens.isEmpty()) return ShellExecutionResult("", "", 0, newPromptPath = currentDisplayDir())
        val cmd = tokens[0]
        val args = tokens.drop(1)

        // Handle inline VAR=VALUE assignment
        if (cmd.contains('=') && !cmd.startsWith("=") && args.isEmpty()) {
            val key = cmd.substringBefore('=')
            val value = cmd.substringAfter('=')
            if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                envVars[key] = value
                return ShellExecutionResult("", "", 0, newPromptPath = currentDisplayDir())
            }
        }

        return when (cmd) {
            "help" -> ok(helpText())
            "clear" -> ShellExecutionResult("", "", 0, clearRequested = true, newPromptPath = currentDisplayDir())
            "pwd" -> ok(currentVirtualDir)
            "whoami" -> ok(envVars["USER"] ?: "alpaca")
            "id" -> ok("uid=1000(alpaca) gid=1000(alpaca) groups=1000(alpaca),10(wheel)")
            "hostname" -> ok("alpaca-android-${Build.DEVICE.lowercase().take(12)}")
            "date" -> ok(SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.US).format(Date()))
            "echo", "printf" -> handleEcho(args, cmd == "printf")
            "env" -> ok(envVars.entries.joinToString("\n") { "${it.key}=${it.value}" })
            "export" -> handleExport(args)
            "unset" -> {
                args.forEach { envVars.remove(it) }
                ok("")
            }
            "which" -> handleWhich(args)
            "uname" -> handleUname(args)
            "uptime" -> handleUptime(metrics)
            "free" -> handleFree(args, metrics)
            "df" -> handleDf(args, metrics)
            "ps", "top" -> handlePsTop(metrics)
            "history" -> {
                val out = commandHistory.mapIndexed { idx, s -> "${(idx + 1).toString().padStart(4)}  $s" }.joinToString("\n")
                ok(out)
            }
            "cd" -> handleCd(args)
            "ls", "ll", "la" -> handleLs(cmd, args)
            "cat" -> handleCat(args, stdin)
            "head" -> handleHeadTail(args, stdin, isHead = true)
            "tail" -> handleHeadTail(args, stdin, isHead = false)
            "wc" -> handleWc(args, stdin)
            "grep" -> handleGrep(args, stdin)
            "find" -> handleFind(args)
            "mkdir" -> handleMkdir(args)
            "touch" -> handleTouch(args)
            "rm" -> handleRm(args)
            "cp" -> handleCp(args)
            "mv" -> handleMv(args)
            "chmod" -> handleChmod(args)
            "apk" -> handleApk(args)
            "sh", "ash", "bash", "zsh" -> handleRunScript(args, metrics, commandHistory)
            "getprop", "vmstat", "ip", "netstat", "ping" -> runSystemBinaryBridge(cmd, args)
            else -> {
                // Check if user ran a script directly like ./sysinfo.sh
                if (cmd.startsWith("./") || cmd.startsWith("/")) {
                    val file = resolveHostFile(cmd)
                    if (file.exists() && file.isFile) {
                        handleRunScript(listOf(cmd) + args, metrics, commandHistory)
                    } else {
                        err("ash: $cmd: not found", 127)
                    }
                } else {
                    err("ash: $cmd: not found (try 'help' or 'apk search $cmd')", 127)
                }
            }
        }
    }

    private fun handleEcho(args: List<String>, isPrintf: Boolean): ShellExecutionResult {
        val text = args.joinToString(" ")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
        return ok(if (isPrintf) text else text)
    }

    private fun handleExport(args: List<String>): ShellExecutionResult {
        if (args.isEmpty()) {
            return ok(envVars.entries.joinToString("\n") { "export ${it.key}=\"${it.value}\"" })
        }
        for (arg in args) {
            if (arg.contains('=')) {
                val key = arg.substringBefore('=')
                val value = arg.substringAfter('=')
                if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                    envVars[key] = value
                } else {
                    return err("ash: export: `$arg': not a valid identifier", 1)
                }
            }
        }
        return ok("")
    }

    private fun handleWhich(args: List<String>): ShellExecutionResult {
        val builtins = setOf(
            "ash", "sh", "bash", "ls", "cat", "grep", "find", "mkdir", "touch", "rm",
            "cp", "mv", "pwd", "cd", "echo", "env", "apk", "free", "df", "ps", "top",
            "uname", "uptime", "head", "tail", "wc", "chmod", "whoami", "id", "date"
        )
        val target = args.firstOrNull() ?: return err("which: missing operand", 1)
        return if (target in builtins) {
            ok("/bin/$target")
        } else {
            val world = resolveHostFile("/etc/apk/world").readLines()
            if (target in world) ok("/usr/bin/$target") else err("which: no $target in (${envVars["PATH"]})", 1)
        }
    }

    private fun handleUname(args: List<String>): ShellExecutionResult {
        val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "aarch64"
        val kernel = System.getProperty("os.version") ?: "6.1.75-android"
        val host = "alpaca-bootstrap"
        val flags = args.joinToString("")
        return when {
            flags.contains("a") -> ok("Linux $host $kernel #1 SMP PREEMPT $arch GNU/Linux (Alpaca 3.21)")
            flags.contains("r") -> ok(kernel)
            flags.contains("m") -> ok(arch)
            flags.contains("n") -> ok(host)
            else -> ok("Linux")
        }
    }

    private fun handleUptime(metrics: SystemMetricsSnapshot): ShellExecutionResult {
        val totalSec = metrics.uptimeSeconds
        val hrs = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        val load1 = String.format(Locale.US, "%.2f", (metrics.cpuPercent / 100f) * metrics.cpuCores)
        val now = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        return ok(" $now up ${hrs}h ${mins}m,  1 user,  load average: $load1, $load1, $load1")
    }

    private fun handleFree(args: List<String>, metrics: SystemMetricsSnapshot): ShellExecutionResult {
        val human = args.any { it.contains("h") }
        val total = metrics.memTotalMb
        val used = metrics.memUsedMb
        val free = (total - used).coerceAtLeast(0L)
        return if (human) {
            val totalG = String.format(Locale.US, "%.1fGi", total / 1024f)
            val usedG = String.format(Locale.US, "%.1fGi", used / 1024f)
            val freeG = String.format(Locale.US, "%.1fGi", free / 1024f)
            ok(
                "               total        used        free      shared  buff/cache   available\n" +
                    "Mem:           ${totalG.padEnd(10)} ${usedG.padEnd(10)} ${freeG.padEnd(10)}  12Mi       128Mi       $freeG\n" +
                    "JVM-Heap:      ${metrics.jvmHeapUsedMb}Mi"
            )
        } else {
            ok(
                "               total        used        free      shared  buff/cache   available\n" +
                    "Mem:         ${total.toString().padStart(7)}     ${used.toString().padStart(7)}     ${free.toString().padStart(7)}          12         128     ${free.toString().padStart(7)}\n" +
                    "JVM-Heap:    ${metrics.jvmHeapUsedMb.toString().padStart(7)} MiB"
            )
        }
    }

    private fun handleDf(args: List<String>, metrics: SystemMetricsSnapshot): ShellExecutionResult {
        val human = args.any { it.contains("h") }
        val totalStr = if (human) String.format(Locale.US, "%.1fG", metrics.diskTotalGb) else "${(metrics.diskTotalGb * 1024 * 1024).toLong()}"
        val usedStr = if (human) String.format(Locale.US, "%.1fG", metrics.diskUsedGb) else "${(metrics.diskUsedGb * 1024 * 1024).toLong()}"
        val availGb = (metrics.diskTotalGb - metrics.diskUsedGb).coerceAtLeast(0f)
        val availStr = if (human) String.format(Locale.US, "%.1fG", availGb) else "${(availGb * 1024 * 1024).toLong()}"
        val pct = "${metrics.diskPercent.toInt()}%"
        return ok(
            "Filesystem                Size      Used Available Use% Mounted on\n" +
                "/dev/alpaca_rootfs   ${totalStr.padStart(9)} ${usedStr.padStart(9)} ${availStr.padStart(9)} ${pct.padStart(4)} /"
        )
    }

    private fun handlePsTop(metrics: SystemMetricsSnapshot): ShellExecutionResult {
        val pid = android.os.Process.myPid()
        val cpu = String.format(Locale.US, "%.1f", metrics.cpuPercent)
        val mem = String.format(Locale.US, "%.1f", metrics.memPercent)
        val shell = envVars["SHELL"] ?: "/bin/ash"
        return ok(
            "  PID USER       VSZ STAT CPU% MEM% COMMAND\n" +
                "    1 root      1420 S    0.0  0.1 /sbin/init alpaca-bootstrap\n" +
                "  ${(pid - 1).coerceAtLeast(2).toString().padStart(3)} alpaca    4096 S    0.2  0.4 /usr/sbin/telemetry-agent\n" +
                "  ${pid.toString().padStart(3)} alpaca   ${(metrics.jvmHeapUsedMb * 1024).toString().padStart(5)} R   $cpu  $mem $shell (active, ${metrics.processCount} threads)"
        )
    }

    private fun handleCd(args: List<String>): ShellExecutionResult {
        val target = args.firstOrNull() ?: homeVirtualPath
        val resolvedVirtual = normalizeVirtualPath(target)
        val host = resolveHostFile(resolvedVirtual)
        return when {
            !host.exists() -> err("ash: cd: $target: No such file or directory", 1)
            !host.isDirectory -> err("ash: cd: $target: Not a directory", 1)
            else -> {
                currentVirtualDir = resolvedVirtual
                envVars["PWD"] = resolvedVirtual
                ok("")
            }
        }
    }

    private fun handleLs(cmd: String, args: List<String>): ShellExecutionResult {
        val flags = args.filter { it.startsWith("-") }.joinToString("")
        val showAll = flags.contains("a") || cmd == "la"
        val longFmt = flags.contains("l") || cmd == "ll" || cmd == "la"
        val paths = args.filter { !it.startsWith("-") }.ifEmpty { listOf(".") }

        val targetVirtual = normalizeVirtualPath(paths.first())
        val targetHost = resolveHostFile(targetVirtual)
        if (!targetHost.exists()) return err("ls: ${paths.first()}: No such file or directory", 1)

        val entries = if (targetHost.isDirectory) {
            (targetHost.listFiles()?.toList() ?: emptyList())
                .filter { showAll || !it.name.startsWith(".") }
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        } else {
            listOf(targetHost)
        }

        if (!longFmt) {
            return ok(entries.joinToString("  ") { if (it.isDirectory) "${it.name}/" else it.name })
        }

        val dateFmt = SimpleDateFormat("MMM dd HH:mm", Locale.US)
        val lines = entries.map { file ->
            val typeChar = if (file.isDirectory) 'd' else '-'
            val perms = if (file.canExecute() || file.name.endsWith(".sh")) "rwxr-xr-x" else "rw-r--r--"
            val size = file.length().toString().padStart(7)
            val mtime = dateFmt.format(Date(file.lastModified()))
            val displayName = if (file.isDirectory) "${file.name}/" else file.name
            "$typeChar$perms 1 alpaca alpaca $size $mtime $displayName"
        }
        return ok("total ${entries.size}\n" + lines.joinToString("\n"))
    }

    private fun handleCat(args: List<String>, stdin: String?): ShellExecutionResult {
        if (args.isEmpty()) {
            return if (stdin != null) ok(stdin) else err("cat: missing file operand", 1)
        }
        val sb = StringBuilder()
        for (path in args) {
            val file = resolveHostFile(path)
            if (!file.exists()) return err("cat: $path: No such file or directory", 1)
            if (file.isDirectory) return err("cat: $path: Is a directory", 1)
            val content = file.readText()
            systemMonitor.recordWorkspaceIoBytes(content.toByteArray().size.toLong())
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(content.trimEnd('\n'))
        }
        return ok(sb.toString())
    }

    private fun handleHeadTail(args: List<String>, stdin: String?, isHead: Boolean): ShellExecutionResult {
        var count = 10
        val fileArgs = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            if (args[i] == "-n" && i + 1 < args.size) {
                count = args[i + 1].toIntOrNull()?.coerceAtLeast(1) ?: 10
                i += 2
            } else if (args[i].startsWith("-n")) {
                count = args[i].removePrefix("-n").toIntOrNull()?.coerceAtLeast(1) ?: 10
                i++
            } else {
                fileArgs.add(args[i])
                i++
            }
        }
        val lines = if (fileArgs.isEmpty()) {
            stdin?.lines() ?: return err("${if (isHead) "head" else "tail"}: missing input", 1)
        } else {
            val file = resolveHostFile(fileArgs.first())
            if (!file.exists() || file.isDirectory) return err("Cannot read ${fileArgs.first()}", 1)
            file.readLines()
        }
        val sliced = if (isHead) lines.take(count) else lines.takeLast(count)
        return ok(sliced.joinToString("\n"))
    }

    private fun handleWc(args: List<String>, stdin: String?): ShellExecutionResult {
        val flags = args.filter { it.startsWith("-") }.joinToString("")
        val fileArgs = args.filter { !it.startsWith("-") }
        val text = if (fileArgs.isEmpty()) {
            stdin ?: return err("wc: missing operand", 1)
        } else {
            val f = resolveHostFile(fileArgs.first())
            if (!f.exists() || f.isDirectory) return err("wc: ${fileArgs.first()}: No such file", 1)
            f.readText()
        }
        val lineCount = if (text.isEmpty()) 0 else text.lines().size
        val wordCount = if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size
        val byteCount = text.toByteArray().size
        return when {
            flags.contains("l") -> ok("$lineCount")
            flags.contains("w") -> ok("$wordCount")
            flags.contains("c") -> ok("$byteCount")
            else -> ok("  $lineCount  $wordCount  $byteCount ${fileArgs.firstOrNull().orEmpty()}".trimEnd())
        }
    }

    private fun handleGrep(args: List<String>, stdin: String?): ShellExecutionResult {
        val flags = args.filter { it.startsWith("-") }.joinToString("")
        val ignoreCase = flags.contains("i")
        val showLines = flags.contains("n")
        val positional = args.filter { !it.startsWith("-") }
        if (positional.isEmpty()) return err("grep: missing pattern", 2)
        val pattern = positional[0]
        val regex = try {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (_: Exception) {
            return err("grep: invalid pattern '$pattern'", 2)
        }

        val matches = mutableListOf<String>()
        if (positional.size == 1) {
            val inputLines = stdin?.lines() ?: return err("grep: missing file operand", 2)
            inputLines.forEachIndexed { idx, l ->
                if (regex.containsMatchIn(l)) {
                    matches.add(if (showLines) "${idx + 1}:$l" else l)
                }
            }
        } else {
            val target = resolveHostFile(positional[1])
            if (!target.exists()) return err("grep: ${positional[1]}: No such file or directory", 2)
            val files = if (target.isDirectory && flags.contains("r")) {
                target.walkTopDown().filter { it.isFile }.toList()
            } else if (target.isFile) {
                listOf(target)
            } else {
                return err("grep: ${positional[1]}: Is a directory (use -r)", 2)
            }
            for (f in files) {
                val rel = toVirtualPath(f)
                f.readLines().forEachIndexed { idx, l ->
                    if (regex.containsMatchIn(l)) {
                        val prefix = if (files.size > 1) "$rel:" else ""
                        val ln = if (showLines) "${idx + 1}:" else ""
                        matches.add("$prefix$ln$l")
                    }
                }
            }
        }
        return if (matches.isEmpty()) ShellExecutionResult("", "", 1, newPromptPath = currentDisplayDir())
        else ok(matches.joinToString("\n"))
    }

    private fun handleFind(args: List<String>): ShellExecutionResult {
        val startPath = args.firstOrNull { !it.startsWith("-") } ?: "."
        val nameIdx = args.indexOf("-name")
        val namePattern = if (nameIdx != -1 && nameIdx + 1 < args.size) args[nameIdx + 1] else null
        val startFile = resolveHostFile(startPath)
        if (!startFile.exists()) return err("find: '$startPath': No such file or directory", 1)

        val nameRegex = namePattern?.let {
            Regex("^" + Regex.escape(it).replace("\\*", ".*").replace("\\?", ".") + "$")
        }
        val results = startFile.walkTopDown()
            .filter { nameRegex == null || nameRegex.matches(it.name) }
            .map { toVirtualPath(it) }
            .toList()
        return ok(results.joinToString("\n"))
    }

    private fun handleMkdir(args: List<String>): ShellExecutionResult {
        val paths = args.filter { !it.startsWith("-") }
        if (paths.isEmpty()) return err("mkdir: missing operand", 1)
        val parents = args.any { it.contains("p") }
        for (p in paths) {
            val dir = resolveHostFile(p)
            if (dir.exists()) {
                if (!parents) return err("mkdir: cannot create directory '$p': File exists", 1)
            } else {
                val created = if (parents) dir.mkdirs() else dir.mkdir()
                if (!created) return err("mkdir: cannot create directory '$p'", 1)
            }
        }
        return ok("")
    }

    private fun handleTouch(args: List<String>): ShellExecutionResult {
        if (args.isEmpty()) return err("touch: missing file operand", 1)
        for (p in args) {
            val f = resolveHostFile(p)
            f.parentFile?.mkdirs()
            if (!f.exists()) f.createNewFile() else f.setLastModified(System.currentTimeMillis())
        }
        return ok("")
    }

    private fun handleRm(args: List<String>): ShellExecutionResult {
        val flags = args.filter { it.startsWith("-") }.joinToString("")
        val recursive = flags.contains("r") || flags.contains("R")
        val force = flags.contains("f")
        val paths = args.filter { !it.startsWith("-") }
        if (paths.isEmpty()) return err("rm: missing operand", 1)

        for (p in paths) {
            val f = resolveHostFile(p)
            if (f == rootDir) return err("rm: refusing to remove root directory '/'", 1)
            if (!f.exists()) {
                if (!force) return err("rm: cannot remove '$p': No such file or directory", 1)
                continue
            }
            if (f.isDirectory && !recursive) {
                return err("rm: cannot remove '$p': Is a directory (use -r)", 1)
            }
            if (recursive) f.deleteRecursively() else f.delete()
        }
        return ok("")
    }

    private fun handleCp(args: List<String>): ShellExecutionResult {
        val paths = args.filter { !it.startsWith("-") }
        if (paths.size < 2) return err("cp: missing destination file operand", 1)
        val src = resolveHostFile(paths[0])
        var dst = resolveHostFile(paths[1])
        if (!src.exists()) return err("cp: cannot stat '${paths[0]}': No such file", 1)
        if (dst.isDirectory) dst = File(dst, src.name)
        src.copyRecursively(dst, overwrite = true)
        return ok("")
    }

    private fun handleMv(args: List<String>): ShellExecutionResult {
        val paths = args.filter { !it.startsWith("-") }
        if (paths.size < 2) return err("mv: missing destination file operand", 1)
        val src = resolveHostFile(paths[0])
        var dst = resolveHostFile(paths[1])
        if (!src.exists()) return err("mv: cannot stat '${paths[0]}': No such file", 1)
        if (dst.isDirectory) dst = File(dst, src.name)
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            src.copyRecursively(dst, overwrite = true)
            src.deleteRecursively()
        }
        return ok("")
    }

    private fun handleChmod(args: List<String>): ShellExecutionResult {
        if (args.size < 2) return err("chmod: missing operand", 1)
        val mode = args[0]
        val f = resolveHostFile(args[1])
        if (!f.exists()) return err("chmod: cannot access '${args[1]}': No such file", 1)
        if (mode.contains("x") || mode.startsWith("7")) {
            f.setExecutable(true)
        }
        return ok("")
    }

    private fun handleApk(args: List<String>): ShellExecutionResult {
        if (args.isEmpty()) {
            return ok("apk-tools 2.14.6 (Alpaca Linux bootstrap)\nUsage: apk [update | search <pkg> | add <pkg> | del <pkg> | info | list]")
        }
        val worldFile = resolveHostFile("/etc/apk/world")
        val installed = worldFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()

        return when (val sub = args[0]) {
            "update" -> ok(
                "fetch https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64/APKINDEX.tar.gz\n" +
                    "fetch https://dl-cdn.alpinelinux.org/alpine/v3.21/community/aarch64/APKINDEX.tar.gz\n" +
                    "v3.21.0-alpaca [https://dl-cdn.alpinelinux.org/alpine/v3.21/main]\n" +
                    "OK: ${apkRepositoryCatalog.size + installed.size} distinct packages available"
            )
            "info", "list" -> ok(installed.sorted().joinToString("\n") { "$it - installed" })
            "search" -> {
                val query = args.getOrNull(1)?.lowercase().orEmpty()
                val matches = apkRepositoryCatalog.entries
                    .filter { query.isEmpty() || it.key.contains(query) || it.value.lowercase().contains(query) }
                    .map { "${it.key}-${it.value}" }
                if (matches.isEmpty()) err("ERROR: no packages matched '$query'", 1) else ok(matches.joinToString("\n"))
            }
            "add" -> {
                val pkgs = args.drop(1).filter { !it.startsWith("-") }
                if (pkgs.isEmpty()) return err("apk add: specify package name(s)", 1)
                val log = StringBuilder()
                for ((idx, pkg) in pkgs.withIndex()) {
                    if (pkg in installed) {
                        log.append("(${idx + 1}/${pkgs.size}) $pkg already installed\n")
                    } else if (pkg in apkRepositoryCatalog || pkg.matches(Regex("[a-z0-9._-]+"))) {
                        installed.add(pkg)
                        val ver = apkRepositoryCatalog[pkg]?.substringBefore(" -") ?: "1.0.0-r0"
                        log.append("(${idx + 1}/${pkgs.size}) Installing $pkg ($ver)\n")
                    } else {
                        return err("ERROR: unable to select package '$pkg'", 1)
                    }
                }
                worldFile.writeText(installed.sorted().joinToString("\n") + "\n")
                log.append("OK: ${installed.size} packages in /etc/apk/world")
                ok(log.toString())
            }
            "del" -> {
                val pkgs = args.drop(1).filter { !it.startsWith("-") }
                if (pkgs.isEmpty()) return err("apk del: specify package name(s)", 1)
                val log = StringBuilder()
                for (pkg in pkgs) {
                    if (pkg in setOf("alpaca-baselayout", "busybox", "musl", "apk-tools")) {
                        return err("ERROR: cannot remove essential base package '$pkg'", 1)
                    }
                    if (installed.remove(pkg)) {
                        log.append("Purging $pkg\n")
                    } else {
                        log.append("Package $pkg not installed\n")
                    }
                }
                worldFile.writeText(installed.sorted().joinToString("\n") + "\n")
                log.append("OK: ${installed.size} packages remaining")
                ok(log.toString())
            }
            else -> err("apk: unknown subcommand '$sub' (try: update, search, add, del, info)", 1)
        }
    }

    private fun handleRunScript(
        args: List<String>,
        metrics: SystemMetricsSnapshot,
        commandHistory: List<String>,
    ): ShellExecutionResult {
        val target = args.firstOrNull { !it.startsWith("-") }
            ?: return err("sh: specify a script path, e.g. sh ~/sysinfo.sh", 1)
        val scriptFile = resolveHostFile(target)
        if (!scriptFile.exists() || !scriptFile.isFile) {
            return err("sh: can't open '$target': No such file or directory", 2)
        }
        val lines = scriptFile.readLines()
        val out = StringBuilder()
        val err = StringBuilder()
        var exitCode = 0
        for (line in lines) {
            val clean = line.trim()
            if (clean.isEmpty() || clean.startsWith("#")) continue
            val res = executeSingleCommandLine(clean, metrics, commandHistory)
            if (res.stdout.isNotEmpty()) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(res.stdout)
            }
            if (res.stderr.isNotEmpty()) {
                if (err.isNotEmpty()) err.append('\n')
                err.append(res.stderr)
            }
            exitCode = res.exitCode
        }
        return ShellExecutionResult(out.toString(), err.toString(), exitCode, newPromptPath = currentDisplayDir())
    }

    private fun runSystemBinaryBridge(cmd: String, args: List<String>): ShellExecutionResult {
        return try {
            val binary = "/system/bin/$cmd"
            if (!File(binary).exists()) {
                return err("ash: $cmd: native utility unavailable on this Android build", 127)
            }
            val safeArgs = args.take(6)
            val pb = ProcessBuilder(listOf(binary) + safeArgs)
            val process = pb.start()
            val finished = process.waitFor(4, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return err("$cmd: timed out after 4s", 124)
            }
            val stdout = process.inputStream.bufferedReader().readText().trimEnd()
            val stderr = process.errorStream.bufferedReader().readText().trimEnd()
            ShellExecutionResult(stdout, stderr, process.exitValue(), newPromptPath = currentDisplayDir())
        } catch (e: Exception) {
            err("$cmd: ${e.localizedMessage ?: "execution failed"}", 1)
        }
    }

    private fun resolveHostFile(virtualOrRelativePath: String): File {
        val norm = normalizeVirtualPath(virtualOrRelativePath)
        val relative = norm.removePrefix("/")
        val resolved = if (relative.isEmpty()) rootDir else File(rootDir, relative)
        val canonicalRoot = rootDir.canonicalFile
        val canonicalTarget = resolved.canonicalFile
        return if (canonicalTarget.path.startsWith(canonicalRoot.path)) {
            canonicalTarget
        } else {
            canonicalRoot
        }
    }

    private fun normalizeVirtualPath(path: String): String {
        val expanded = when {
            path == "~" -> homeVirtualPath
            path.startsWith("~/") -> homeVirtualPath + path.removePrefix("~")
            path.startsWith("/") -> path
            else -> "$currentVirtualDir/$path"
        }
        val stack = ArrayDeque<String>()
        for (part in expanded.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return if (stack.isEmpty()) "/" else "/" + stack.joinToString("/")
    }

    private fun toVirtualPath(hostFile: File): String {
        val rootPath = rootDir.canonicalPath
        val filePath = hostFile.canonicalPath
        val rel = filePath.removePrefix(rootPath).replace(File.separatorChar, '/')
        return if (rel.isEmpty()) "/" else rel
    }

    private fun expandVariables(input: String): String {
        var result = input
        for ((k, v) in envVars) {
            result = result.replace("\${$k}", v).replace("\$$k", v)
        }
        return result
    }

    private fun splitChainedCommands(input: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val current = StringBuilder()
        var pendingOp = ""
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == '\'' && !inDouble -> { inSingle = !inSingle; current.append(c) }
                c == '"' && !inSingle -> { inDouble = !inDouble; current.append(c) }
                !inSingle && !inDouble && c == '&' && i + 1 < input.length && input[i + 1] == '&' -> {
                    result.add(pendingOp to current.toString().trim())
                    current.clear()
                    pendingOp = "&&"
                    i++
                }
                !inSingle && !inDouble && c == ';' -> {
                    result.add(pendingOp to current.toString().trim())
                    current.clear()
                    pendingOp = ";"
                }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotBlank()) {
            result.add(pendingOp to current.toString().trim())
        }
        return result
    }

    private fun splitByUnquotedPipe(input: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        for (c in input) {
            when {
                c == '\'' && !inDouble -> { inSingle = !inSingle; current.append(c) }
                c == '"' && !inSingle -> { inDouble = !inDouble; current.append(c) }
                !inSingle && !inDouble && c == '|' -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        result.add(current.toString())
        return result
    }

    private fun findUnquotedOperator(input: String, op: String): Int {
        var inSingle = false
        var inDouble = false
        for (i in 0..input.length - op.length) {
            val c = input[i]
            if (c == '\'' && !inDouble) inSingle = !inSingle
            else if (c == '"' && !inSingle) inDouble = !inDouble
            else if (!inSingle && !inDouble && input.startsWith(op, i)) return i
        }
        return -1
    }

    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        for (c in input) {
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c.isWhitespace() && !inSingle && !inDouble -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    private fun bootstrapRootFsIfNeeded() {
        val marker = File(rootDir, "etc/os-release")
        if (marker.exists()) return

        listOf(
            "bin", "etc/apk", "home/alpaca/scripts", "home/alpaca/projects",
            "usr/bin", "usr/local/bin", "var/log", "tmp"
        ).forEach { File(rootDir, it).mkdirs() }

        File(rootDir, "etc/os-release").writeText(
            """
            NAME="Alpaca Linux"
            ID=alpaca
            VERSION_ID=3.21.0-bootstrap
            PRETTY_NAME="Alpaca Linux v3.21 (musl/ash bootstrap)"
            HOME_URL="https://alpinelinux.org/"
            """.trimIndent() + "\n"
        )
        File(rootDir, "etc/motd").writeText(
            "Welcome to Alpaca Linux v3.21 (bootstrap rootfs)!\n" +
                "Type 'help' for commands, 'apk list' for packages, or tap Mic for voice.\n"
        )
        File(rootDir, "etc/apk/repositories").writeText(
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/v3.21/community\n"
        )
        File(rootDir, "etc/apk/world").writeText(
            "alpaca-baselayout\napk-tools\nbusybox\nmusl\nash\ncurl\ngit\nhtop\npython3\n"
        )
        File(rootDir, "home/alpaca/README.md").writeText(
            "# Alpaca Linux Bootstrap Workspace\n" +
                "- Run `sh ~/sysinfo.sh` to inspect live device & rootfs telemetry.\n" +
                "- Use `apk search <pkg>` / `apk add <pkg>` to manage bootstrap packages.\n" +
                "- Scripts generated in Code AI can be saved directly to ~/scripts/.\n"
        )
        File(rootDir, "home/alpaca/sysinfo.sh").writeText(
            "#!/bin/ash\n" +
                "echo \"=== Alpaca Linux System Snapshot ===\"\n" +
                "uname -a\n" +
                "uptime\n" +
                "free -h\n" +
                "df -h\n"
        )
        File(rootDir, "home/alpaca/scripts/disk_audit.sh").writeText(
            "#!/bin/ash\n" +
                "echo \"Checking /home/alpaca workspace files...\"\n" +
                "find /home/alpaca\n" +
                "df -h\n"
        )
        File(rootDir, "var/log/alpaca-boot.log").writeText(
            "[0.000] alpaca-init: mounted rootfs at /home/alpaca\n" +
                "[0.120] apk-tools: initialized package index (v3.21)\n" +
                "[0.240] ai-copilot: Firebase AI Logic telemetry bridge armed\n"
        )
    }

    private fun ok(stdout: String) =
        ShellExecutionResult(stdout = stdout, stderr = "", exitCode = 0, newPromptPath = currentDisplayDir())

    private fun err(stderr: String, code: Int) =
        ShellExecutionResult(stdout = "", stderr = stderr, exitCode = code, newPromptPath = currentDisplayDir())

    private fun helpText(): String =
        """
        Alpaca Linux v3.21 Bootstrap Shell Builtins:
          Files & Nav : ls [-la], cd, pwd, cat, head, tail, wc, grep [-inr], find, mkdir [-p], touch, rm [-rf], cp, mv, chmod
          System & IO : uname [-a], uptime, free [-h], df [-h], ps, top, env, export, which, whoami, id, date, history, clear
          Packages    : apk [update | search <pkg> | add <pkg> | del <pkg> | info]
          Scripting   : sh <script.sh> | ash <script.sh> | pipes (|) | redirect (> / >>) | chains (&& / ;)
          Native Sys  : getprop, vmstat, ip, netstat, ping -c 1 <host>
        """.trimIndent()
}
