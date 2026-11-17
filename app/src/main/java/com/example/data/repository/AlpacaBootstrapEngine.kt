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
    private val filesRoot = context.applicationContext.filesDir
    private val rootDir = File(filesRoot, "alpine_rootfs")
    private val legacyRootDir = File(filesRoot, "alpaca_rootfs")
    private val homeVirtualPath = "/home/alpine"
    private var currentVirtualDir = homeVirtualPath

    private val apkManager = AlpineApkManager(rootDir)
    private val posixBridge = PosixShellBridge(rootDir, systemMonitor)

    private val envVars = linkedMapOf(
        "USER" to "alpine",
        "LOGNAME" to "alpine",
        "HOME" to homeVirtualPath,
        "PWD" to homeVirtualPath,
        "SHELL" to "/bin/ash",
        "TERM" to "xterm-256color",
        "DISTRO" to "Alpine Linux ${AlpineApkManager.ALPINE_VERSION}",
        "ARCH" to AlpineApkManager.detectAlpineArch(),
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "LANG" to "C.UTF-8",
    )

    init {
        bootstrapRootFsIfNeeded()
    }

    fun setShell(shellPath: String) {
        envVars["SHELL"] = shellPath
    }

    fun currentDisplayDir(): String {
        return when {
            currentVirtualDir == homeVirtualPath -> "~"
            currentVirtualDir.startsWith("$homeVirtualPath/") ->
                "~/" + currentVirtualDir.removePrefix("$homeVirtualPath/")
            else -> currentVirtualDir
        }
    }

    suspend fun writeScriptToWorkspace(filename: String, content: String): String =
        withContext(Dispatchers.IO) {
            bootstrapRootFsIfNeeded()
            val sanitized = filename.trim()
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .ifEmpty { "script.sh" }
            val scriptsDir = resolveHostFile("$homeVirtualPath/scripts")
            scriptsDir.mkdirs()
            val target = File(scriptsDir, sanitized)
            target.writeText(content)
            systemMonitor.recordWorkspaceIoBytes(content.toByteArray().size.toLong())
            "$homeVirtualPath/scripts/$sanitized"
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
            val scriptsDir = resolveHostFile("$homeVirtualPath/scripts")
            scriptsDir.listFiles()?.map { "$homeVirtualPath/scripts/${it.name}" }?.sorted() ?: emptyList()
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

        // If user entered a multi-statement shell construct (for/while/if/case or command substitution),
        // pass directly to real POSIX /system/bin/sh inside alpine_rootfs when available.
        if (isCompoundPosixConstruct(trimmed) && posixBridge.supportsNativePosix()) {
            return@withContext posixBridge.executePosixCommand(
                commandLine = trimmed,
                currentHostDir = resolveHostFile(currentVirtualDir),
                currentDisplayDir = currentDisplayDir(),
                envVars = envVars,
            )
        }

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
                return err("ash: syntax error near unexpected token `newline'", 2)
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
        if (tokens.isEmpty()) return ok("")
        val cmd = tokens[0]
        val args = tokens.drop(1)

        if (cmd.contains('=') && !cmd.startsWith("=") && args.isEmpty()) {
            val key = cmd.substringBefore('=')
            val value = cmd.substringAfter('=')
            if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                envVars[key] = value
                return ok("")
            }
        }

        return when (cmd) {
            "help" -> ok(helpText())
            "clear" -> ShellExecutionResult("", "", 0, clearRequested = true, newPromptPath = currentDisplayDir())
            "pwd" -> ok(currentVirtualDir)
            "whoami" -> ok(envVars["USER"] ?: "alpine")
            "id" -> ok("uid=1000(alpine) gid=1000(alpine) groups=1000(alpine),10(wheel)")
            "hostname" -> ok("alpine-${Build.DEVICE.lowercase(Locale.US).take(12).ifEmpty { "android" }}")
            "date" -> ok(SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.US).format(Date()))
            "echo", "printf" -> handleEcho(args)
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
            "apk" -> {
                val res = apkManager.execute(args)
                ShellExecutionResult(res.stdout, res.stderr, res.exitCode, newPromptPath = currentDisplayDir())
            }
            "curl", "wget" -> posixBridge.executeCurl(args, currentDisplayDir(), ::resolveHostFile)
            "sh", "ash", "bash", "zsh" -> handleRunScript(args, metrics, commandHistory)
            "getprop", "vmstat", "ip", "netstat", "ping" -> posixBridge.executeSystemBinary(cmd, args, currentDisplayDir())
            else -> {
                if (cmd.startsWith("./") || cmd.startsWith("/")) {
                    val file = resolveHostFile(cmd)
                    if (file.exists() && file.isFile) {
                        handleRunScript(listOf(cmd) + args, metrics, commandHistory)
                    } else {
                        err("ash: $cmd: not found", 127)
                    }
                } else if (posixBridge.supportsNativePosix() && cmd in NATIVE_POSIX_TOOLS) {
                    posixBridge.executePosixCommand(
                        commandLine = commandLine,
                        currentHostDir = resolveHostFile(currentVirtualDir),
                        currentDisplayDir = currentDisplayDir(),
                        envVars = envVars,
                        stdin = stdin,
                    )
                } else {
                    err("ash: $cmd: not found (try 'help' or 'apk search $cmd')", 127)
                }
            }
        }
    }

    private fun handleEcho(args: List<String>): ShellExecutionResult {
        val text = args.joinToString(" ")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
        return ok(text)
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
            "uname", "uptime", "head", "tail", "wc", "chmod", "whoami", "id", "date", "curl"
        )
        val target = args.firstOrNull() ?: return err("which: missing operand", 1)
        return when {
            target in builtins -> ok("/bin/$target")
            target in NATIVE_POSIX_TOOLS -> ok("/usr/bin/$target")
            else -> {
                val world = resolveHostFile("/etc/apk/world").takeIf { it.exists() }?.readLines().orEmpty()
                if (target in world) ok("/usr/bin/$target")
                else err("which: no $target in (${envVars["PATH"]})", 1)
            }
        }
    }

    private fun handleUname(args: List<String>): ShellExecutionResult {
        val arch = apkManager.currentArch()
        val kernel = System.getProperty("os.version") ?: "6.1.75-android"
        val host = "alpine-android"
        val flags = args.joinToString("")
        return when {
            flags.contains("a") -> ok("Linux $host $kernel #1 SMP PREEMPT $arch Linux (Alpine ${AlpineApkManager.ALPINE_VERSION})")
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
                "/dev/alpine_rootfs   ${totalStr.padStart(9)} ${usedStr.padStart(9)} ${availStr.padStart(9)} ${pct.padStart(4)} /"
        )
    }

    private fun handlePsTop(metrics: SystemMetricsSnapshot): ShellExecutionResult {
        val pid = android.os.Process.myPid()
        val cpu = String.format(Locale.US, "%.1f", metrics.cpuPercent)
        val mem = String.format(Locale.US, "%.1f", metrics.memPercent)
        val shell = envVars["SHELL"] ?: "/bin/ash"
        return ok(
            "  PID USER       VSZ STAT CPU% MEM% COMMAND\n" +
                "    1 root      1420 S    0.0  0.1 /sbin/init alpine-${AlpineApkManager.ALPINE_VERSION}\n" +
                "  ${(pid - 1).coerceAtLeast(2).toString().padStart(3)} alpine    4096 S    0.2  0.4 /usr/sbin/telemetry-agent\n" +
                "  ${pid.toString().padStart(3)} alpine   ${(metrics.jvmHeapUsedMb * 1024).toString().padStart(5)} R   $cpu  $mem $shell (active, ${metrics.processCount} threads)"
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
            "$typeChar$perms 1 alpine alpine $size $mtime $displayName"
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
        val rawScript = scriptFile.readText()
        if (isCompoundPosixConstruct(rawScript) && posixBridge.supportsNativePosix()) {
            return posixBridge.executePosixCommand(
                commandLine = rawScript,
                currentHostDir = resolveHostFile(currentVirtualDir),
                currentDisplayDir = currentDisplayDir(),
                envVars = envVars,
            )
        }

        val lines = rawScript.lines()
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
        val normalizedInput = when {
            path == "/home/alpaca" -> homeVirtualPath
            path.startsWith("/home/alpaca/") -> homeVirtualPath + path.removePrefix("/home/alpaca")
            else -> path
        }
        val expanded = when {
            normalizedInput == "~" -> homeVirtualPath
            normalizedInput.startsWith("~/") -> homeVirtualPath + normalizedInput.removePrefix("~")
            normalizedInput.startsWith("/") -> normalizedInput
            else -> "$currentVirtualDir/$normalizedInput"
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

    private fun isCompoundPosixConstruct(script: String): Boolean {
        return Regex("\\b(for|while|until|if|case)\\b|\\$\\(|`").containsMatchIn(script)
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
        val alpineRelease = File(rootDir, "etc/alpine-release")
        if (alpineRelease.exists()) return

        listOf(
            "bin", "etc/apk", "home/alpine/scripts", "home/alpine/projects",
            "lib/apk/db", "usr/bin", "usr/local/bin", "var/cache/apk", "var/log", "tmp"
        ).forEach { File(rootDir, it).mkdirs() }

        // Migrate any existing scripts saved in prior /home/alpaca workspace
        val legacyScriptsDir = File(legacyRootDir, "home/alpaca/scripts")
        if (legacyScriptsDir.exists() && legacyScriptsDir.isDirectory) {
            legacyScriptsDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val dest = File(rootDir, "home/alpine/scripts/${file.name}")
                    if (!dest.exists()) file.copyTo(dest, overwrite = false)
                }
            }
        }

        val arch = AlpineApkManager.detectAlpineArch()
        File(rootDir, "etc/os-release").writeText(
            """
            NAME="Alpine Linux"
            ID=alpine
            VERSION_ID=${AlpineApkManager.ALPINE_VERSION}
            PRETTY_NAME="Alpine Linux v3.21"
            HOME_URL="https://alpinelinux.org/"
            BUG_REPORT_URL="https://gitlab.alpinelinux.org/alpine/aports/-/issues"
            """.trimIndent() + "\n"
        )
        File(rootDir, "etc/alpine-release").writeText("${AlpineApkManager.ALPINE_VERSION}\n")
        File(rootDir, "etc/apk/arch").writeText("$arch\n")
        File(rootDir, "etc/apk/repositories").writeText(
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/v3.21/community\n"
        )
        val basePackages = listOf(
            "alpine-baselayout",
            "alpine-release",
            "apk-tools",
            "busybox",
            "busybox-binsh",
            "musl",
            "ca-certificates",
            "curl",
        )
        File(rootDir, "etc/apk/world").writeText(basePackages.joinToString("\n") + "\n")
        apkManager.seedInitialInstalledDb(basePackages)
        File(rootDir, "etc/passwd").writeText(
            "root:x:0:0:root:/root:/bin/ash\n" +
                "alpine:x:1000:1000:Alpine User:/home/alpine:/bin/ash\n"
        )
        File(rootDir, "etc/group").writeText(
            "root:x:0:root\nwheel:x:10:alpine\nalpine:x:1000:alpine\n"
        )
        File(rootDir, "etc/hosts").writeText(
            "127.0.0.1\tlocalhost localhost.localdomain\n::1\t\tlocalhost localhost.localdomain\n"
        )
        File(rootDir, "etc/motd").writeText(
            "Welcome to Alpine Linux ${AlpineApkManager.ALPINE_VERSION} ($arch)!\n" +
                "Run 'apk update' to sync live v3.21 APKINDEX repositories from dl-cdn.alpinelinux.org.\n"
        )
        File(rootDir, "home/alpine/README.md").writeText(
            "# Alpine Linux v3.21 Workspace\n" +
                "- Sync live Alpine APKINDEX: `apk update` then `apk search <pkg>` or `apk info -a <pkg>`\n" +
                "- Run `sh ~/sysinfo.sh` to inspect live device & Alpine rootfs telemetry.\n" +
                "- Native POSIX utilities (`awk`, `sed`, `sort`, `tar`, `sha256sum`, `curl`) execute in `alpine_rootfs`.\n"
        )
        File(rootDir, "home/alpine/sysinfo.sh").writeText(
            "#!/bin/ash\n" +
                "echo \"=== Alpine Linux ${AlpineApkManager.ALPINE_VERSION} ($arch) ===\"\n" +
                "cat /etc/alpine-release\n" +
                "uname -a\n" +
                "uptime\n" +
                "free -h\n" +
                "df -h\n"
        )
        File(rootDir, "home/alpine/scripts/disk_audit.sh").writeText(
            "#!/bin/ash\n" +
                "echo \"Checking /home/alpine workspace files...\"\n" +
                "find /home/alpine\n" +
                "df -h\n"
        )
        File(rootDir, "var/log/alpine-boot.log").writeText(
            "[0.000] alpine-init: mounted alpine_rootfs ($arch) at /home/alpine\n" +
                "[0.120] apk-tools: configured v3.21 main & community repositories\n" +
                "[0.240] posix-bridge: /system/bin/sh + toybox execution armed\n"
        )
    }

    private fun ok(stdout: String) =
        ShellExecutionResult(stdout = stdout, stderr = "", exitCode = 0, newPromptPath = currentDisplayDir())

    private fun err(stderr: String, code: Int) =
        ShellExecutionResult(stdout = "", stderr = stderr, exitCode = code, newPromptPath = currentDisplayDir())

    private fun helpText(): String =
        """
        Alpine Linux ${AlpineApkManager.ALPINE_VERSION} (${apkManager.currentArch()}) Shell:
          Files & Nav : ls [-la], cd, pwd, cat, head, tail, wc, grep [-inr], find, mkdir [-p], touch, rm [-rf], cp, mv, chmod
          POSIX Tools : awk, sed, sort, uniq, cut, tr, seq, expr, sha256sum, md5sum, base64, gzip, tar, curl
          System & IO : uname [-a], uptime, free [-h], df [-h], ps, top, env, export, which, whoami, id, date, history, clear
          Packages    : apk [update | search <pkg> | add <pkg> | del <pkg> | info [-a] <pkg> | list]
          Scripting   : sh <script.sh> | ash | loops/conditionals | pipes (|) | redirect (> / >>) | chains (&& / ;)
          Native Sys  : getprop, vmstat, ip, netstat, ping -c 1 <host>
        """.trimIndent()

    companion object {
        private val NATIVE_POSIX_TOOLS = setOf(
            "awk", "sed", "sort", "uniq", "cut", "tr", "seq", "expr",
            "basename", "dirname", "readlink", "stat", "sha256sum", "md5sum",
            "base64", "gzip", "gunzip", "tar", "sleep", "test", "[", "true", "false",
            "yes", "nl", "od", "xxd", "diff", "cmp"
        )
    }
}
