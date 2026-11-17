package com.example.data.repository

import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream

data class ApkPackageInfo(
    val name: String,
    val version: String,
    val arch: String = "aarch64",
    val description: String = "",
    val url: String = "https://alpinelinux.org/",
    val license: String = "MIT",
    val packageSize: Long = 0L,
    val installedSize: Long = 0L,
    val dependencies: List<String> = emptyList(),
    val repository: String = "v3.21/main",
)

data class ApkCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
)

class AlpineApkManager(
    private val rootDir: File,
    private val archProvider: () -> String = { detectAlpineArch() },
) {
    private val indexCache = linkedMapOf<String, ApkPackageInfo>()

    fun currentArch(): String {
        val archFile = File(rootDir, "etc/apk/arch")
        val stored = if (archFile.exists()) archFile.readText().trim() else ""
        return stored.ifEmpty { archProvider() }
    }

    fun execute(args: List<String>): ApkCommandResult {
        if (args.isEmpty()) {
            return ApkCommandResult(
                stdout = "apk-tools $APK_TOOLS_VERSION, compiled for ${currentArch()} (Alpine Linux $ALPINE_VERSION)\n" +
                    "usage: apk [update | search [-v] <pkg> | add <pkg...> | del <pkg...> | info [-a] [pkg] | list [-I]]",
                stderr = "",
                exitCode = 0,
            )
        }

        ensureIndexLoaded()
        val worldFile = File(rootDir, "etc/apk/world")
        val installedDbFile = File(rootDir, "lib/apk/db/installed")
        val world = readWorldSet(worldFile)
        val installedDb = readInstalledDb(installedDbFile)

        return when (val sub = args[0]) {
            "update" -> handleUpdate()
            "search" -> handleSearch(args.drop(1))
            "info" -> handleInfo(args.drop(1), world, installedDb)
            "list" -> handleList(args.drop(1), world, installedDb)
            "add" -> handleAdd(args.drop(1), world, installedDb, worldFile, installedDbFile)
            "del" -> handleDel(args.drop(1), world, installedDb, worldFile, installedDbFile)
            else -> ApkCommandResult(
                stdout = "",
                stderr = "ERROR: '$sub' is not an apk command. See 'apk' for usage.",
                exitCode = 1,
            )
        }
    }

    private fun handleUpdate(): ApkCommandResult {
        val arch = currentArch()
        val reposFile = File(rootDir, "etc/apk/repositories")
        val repos = if (reposFile.exists()) {
            reposFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        } else {
            listOf(
                "https://dl-cdn.alpinelinux.org/alpine/v3.21/main",
                "https://dl-cdn.alpinelinux.org/alpine/v3.21/community",
            )
        }

        val cacheDir = File(rootDir, "var/cache/apk").apply { mkdirs() }
        val out = StringBuilder()
        val err = StringBuilder()
        var anyNetworkSuccess = false

        for (repoUrl in repos) {
            val cleanRepo = repoUrl.trimEnd('/')
            val indexUrl = "$cleanRepo/$arch/APKINDEX.tar.gz"
            val repoTag = cleanRepo.substringAfter("/alpine/", cleanRepo).replace('/', '_')
            out.append("fetch ").append(indexUrl).append('\n')

            try {
                val conn = (URL(indexUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 20_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "apk-tools/$APK_TOOLS_VERSION (Alpine Linux $ALPINE_VERSION)")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    err.append("WARNING: updating and opening ").append(cleanRepo)
                        .append(": HTTP ").append(code).append('\n')
                    conn.disconnect()
                    continue
                }
                val rawGz = conn.inputStream.use { it.readBytes() }
                conn.disconnect()

                val indexText = extractTarGzEntry(rawGz.inputStream(), "APKINDEX")
                if (indexText.isNullOrBlank()) {
                    err.append("WARNING: APKINDEX missing in archive from ").append(cleanRepo).append('\n')
                    continue
                }

                File(cacheDir, "APKINDEX.$repoTag.tar.gz").writeBytes(rawGz)
                File(cacheDir, "APKINDEX.$repoTag.idx").writeText(indexText)
                val parsed = parseApkIndex(indexText, repoTag.replace('_', '/'))
                indexCache.putAll(parsed)
                anyNetworkSuccess = true
            } catch (e: Exception) {
                err.append("WARNING: updating and opening ").append(cleanRepo)
                    .append(": ").append(e.localizedMessage ?: "network error").append('\n')
            }
        }

        if (!anyNetworkSuccess) {
            loadCachedOrFallbackIndex()
            out.append("Offline fallback index active (${indexCache.size} cached Alpine $ALPINE_VERSION packages)")
            return ApkCommandResult(
                stdout = out.toString().trimEnd(),
                stderr = err.toString().trimEnd(),
                exitCode = if (err.isNotEmpty()) 1 else 0,
            )
        }

        out.append("v$ALPINE_VERSION [$arch] [${repos.firstOrNull().orEmpty()}]\n")
        out.append("OK: ${indexCache.size} distinct packages available")
        return ApkCommandResult(
            stdout = out.toString().trimEnd(),
            stderr = err.toString().trimEnd(),
            exitCode = 0,
        )
    }

    private fun handleSearch(args: List<String>): ApkCommandResult {
        val verbose = args.any { it == "-v" || it == "-d" }
        val query = args.firstOrNull { !it.startsWith("-") }?.lowercase().orEmpty()
        val matches = indexCache.values
            .filter { pkg ->
                query.isEmpty() ||
                    pkg.name.lowercase().contains(query) ||
                    pkg.description.lowercase().contains(query)
            }
            .sortedBy { it.name }

        if (matches.isEmpty()) {
            return ApkCommandResult("", "ERROR: no packages matched '$query'", 1)
        }

        val capped = matches.take(80)
        val lines = capped.map { pkg ->
            if (verbose || query.isNotEmpty()) {
                "${pkg.name}-${pkg.version} - ${pkg.description}"
            } else {
                "${pkg.name}-${pkg.version}"
            }
        }
        val suffix = if (matches.size > capped.size) {
            "\n... (${matches.size - capped.size} more; refine query)"
        } else {
            ""
        }
        return ApkCommandResult(lines.joinToString("\n") + suffix, "", 0)
    }

    private fun handleInfo(
        args: List<String>,
        world: Set<String>,
        installedDb: Map<String, ApkPackageInfo>,
    ): ApkCommandResult {
        val showAllDetails = args.any { it == "-a" }
        val targets = args.filter { !it.startsWith("-") }
        if (targets.isEmpty()) {
            val allInstalled = (installedDb.keys + world).toSortedSet()
            return ApkCommandResult(
                stdout = allInstalled.joinToString("\n") { pkgName ->
                    val ver = installedDb[pkgName]?.version ?: indexCache[pkgName]?.version
                    if (ver != null) "$pkgName-$ver - installed" else "$pkgName - installed"
                },
                stderr = "",
                exitCode = 0,
            )
        }

        val sb = StringBuilder()
        for (target in targets) {
            val info = installedDb[target] ?: indexCache[target]
                ?: return ApkCommandResult("", "ERROR: package '$target' not found", 1)
            val isInstalled = target in world || target in installedDb
            if (showAllDetails) {
                sb.append("${info.name}-${info.version} description:\n${info.description}\n\n")
                sb.append("${info.name}-${info.version} webpage:\n${info.url}\n\n")
                sb.append("${info.name}-${info.version} installed size:\n${formatBytes(info.installedSize)}\n\n")
                sb.append("${info.name}-${info.version} license:\n${info.license}\n\n")
                sb.append("${info.name}-${info.version} depends on:\n")
                sb.append(if (info.dependencies.isEmpty()) "(none)" else info.dependencies.joinToString(" "))
                sb.append("\n\nstatus: ${if (isInstalled) "installed" else "available (${info.repository})"}\n")
            } else {
                val statusTag = if (isInstalled) "installed" else "available"
                sb.append("${info.name}-${info.version} - ${info.description} [$statusTag]\n")
            }
        }
        return ApkCommandResult(sb.toString().trimEnd(), "", 0)
    }

    private fun handleList(
        args: List<String>,
        world: Set<String>,
        installedDb: Map<String, ApkPackageInfo>,
    ): ApkCommandResult {
        val installedOnly = args.any { it == "-I" || it == "--installed" }
        val query = args.firstOrNull { !it.startsWith("-") }?.lowercase().orEmpty()
        val allInstalled = world + installedDb.keys

        val source = if (installedOnly || query.isEmpty()) {
            allInstalled.sorted().map { name ->
                installedDb[name] ?: indexCache[name] ?: ApkPackageInfo(name, "installed", currentArch())
            }
        } else {
            indexCache.values.filter {
                it.name.lowercase().contains(query) || it.description.lowercase().contains(query)
            }.sortedBy { it.name }
        }

        val lines = source.take(80).map { pkg ->
            val mark = if (pkg.name in allInstalled) "[installed]" else "[${pkg.repository}]"
            "${pkg.name}-${pkg.version} ${pkg.arch} {${pkg.name}} (${pkg.license}) $mark"
        }
        return ApkCommandResult(lines.joinToString("\n"), "", 0)
    }

    private fun handleAdd(
        args: List<String>,
        world: MutableSet<String>,
        installedDb: MutableMap<String, ApkPackageInfo>,
        worldFile: File,
        installedDbFile: File,
    ): ApkCommandResult {
        val pkgs = args.filter { !it.startsWith("-") }
        if (pkgs.isEmpty()) {
            return ApkCommandResult("", "apk add: specify package name(s)", 1)
        }

        val resolvedQueue = ArrayDeque<String>()
        val toInstall = linkedMapOf<String, ApkPackageInfo>()
        val visited = mutableSetOf<String>()

        for (pkg in pkgs) {
            if (pkg !in indexCache && !pkg.matches(Regex("[a-z0-9._+-]+"))) {
                return ApkCommandResult("", "ERROR: unable to select package '$pkg'", 1)
            }
            resolvedQueue.addLast(pkg)
        }

        while (resolvedQueue.isNotEmpty()) {
            val current = resolvedQueue.removeFirst()
            if (!visited.add(current)) continue
            val info = indexCache[current] ?: ApkPackageInfo(
                name = current,
                version = "1.0.0-r0",
                arch = currentArch(),
                description = "Alpine Linux package",
            )
            if (current !in world && current !in installedDb) {
                toInstall[current] = info
            }
            for (rawDep in info.dependencies) {
                val depName = normalizeDependencyToken(rawDep) ?: continue
                if (depName in indexCache && depName !in visited && depName !in installedDb) {
                    resolvedQueue.addLast(depName)
                }
            }
        }

        val log = StringBuilder()
        var step = 0
        val total = toInstall.size.coerceAtLeast(pkgs.size)
        for (pkg in pkgs) {
            if (pkg in world || pkg in installedDb) {
                log.append("($pkg already in /etc/apk/world)\n")
            }
            world.add(pkg)
        }
        for ((name, info) in toInstall) {
            step++
            installedDb[name] = info
            val sizeLabel = if (info.installedSize > 0) " (${formatBytes(info.installedSize)})" else ""
            log.append("($step/$total) Installing $name (${info.version})$sizeLabel\n")
        }

        writeWorldFile(worldFile, world)
        writeInstalledDb(installedDbFile, installedDb)

        val totalKiB = (installedDb.values.sumOf { it.installedSize } / 1024L).coerceAtLeast(world.size * 128L)
        log.append("OK: $totalKiB KiB in ${installedDb.size.coerceAtLeast(world.size)} packages (/lib/apk/db/installed)")
        return ApkCommandResult(log.toString().trimEnd(), "", 0)
    }

    private fun handleDel(
        args: List<String>,
        world: MutableSet<String>,
        installedDb: MutableMap<String, ApkPackageInfo>,
        worldFile: File,
        installedDbFile: File,
    ): ApkCommandResult {
        val pkgs = args.filter { !it.startsWith("-") }
        if (pkgs.isEmpty()) {
            return ApkCommandResult("", "apk del: specify package name(s)", 1)
        }
        val log = StringBuilder()
        for (pkg in pkgs) {
            if (pkg in ESSENTIAL_BASE_PACKAGES) {
                return ApkCommandResult("", "ERROR: cannot remove essential world dependency '$pkg'", 1)
            }
            val removedWorld = world.remove(pkg)
            val removedDb = installedDb.remove(pkg) != null
            if (removedWorld || removedDb) {
                log.append("Purging $pkg\n")
            } else {
                log.append("Package $pkg is not installed\n")
            }
        }
        writeWorldFile(worldFile, world)
        writeInstalledDb(installedDbFile, installedDb)
        log.append("OK: ${installedDb.size.coerceAtLeast(world.size)} packages remaining")
        return ApkCommandResult(log.toString().trimEnd(), "", 0)
    }

    fun seedInitialInstalledDb(basePackages: Collection<String>) {
        val dbFile = File(rootDir, "lib/apk/db/installed")
        if (dbFile.exists() && dbFile.length() > 0L) return
        ensureIndexLoaded()
        val initialDb = linkedMapOf<String, ApkPackageInfo>()
        for (name in basePackages) {
            val info = indexCache[name] ?: ApkPackageInfo(
                name = name,
                version = "1.0.0-r0",
                arch = currentArch(),
                description = "Alpine Linux base package",
            )
            initialDb[name] = info
        }
        writeInstalledDb(dbFile, initialDb)
    }

    private fun ensureIndexLoaded() {
        if (indexCache.isEmpty()) {
            loadCachedOrFallbackIndex()
        }
    }

    private fun loadCachedOrFallbackIndex() {
        indexCache.clear()
        val fallback = parseApkIndex(OFFLINE_FALLBACK_INDEX, "v3.21/main")
        indexCache.putAll(fallback)

        val cacheDir = File(rootDir, "var/cache/apk")
        if (cacheDir.exists() && cacheDir.isDirectory) {
            cacheDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("APKINDEX.") && it.name.endsWith(".idx") }
                ?.sortedBy { it.name }
                ?.forEach { idxFile ->
                    val repoTag = idxFile.name.removePrefix("APKINDEX.").removeSuffix(".idx").replace('_', '/')
                    indexCache.putAll(parseApkIndex(idxFile.readText(), repoTag))
                }
        }
    }

    private fun readWorldSet(worldFile: File): MutableSet<String> {
        if (!worldFile.exists()) return linkedSetOf()
        return worldFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
    }

    private fun writeWorldFile(worldFile: File, world: Set<String>) {
        worldFile.parentFile?.mkdirs()
        worldFile.writeText(world.sorted().joinToString("\n") + if (world.isNotEmpty()) "\n" else "")
    }

    private fun readInstalledDb(dbFile: File): MutableMap<String, ApkPackageInfo> {
        if (!dbFile.exists()) return linkedMapOf()
        return parseApkIndex(dbFile.readText(), "installed").toMutableMap()
    }

    private fun writeInstalledDb(dbFile: File, db: Map<String, ApkPackageInfo>) {
        dbFile.parentFile?.mkdirs()
        val content = db.values.sortedBy { it.name }.joinToString("\n\n") { pkg ->
            buildString {
                appendLine("P:${pkg.name}")
                appendLine("V:${pkg.version}")
                appendLine("A:${pkg.arch}")
                appendLine("T:${pkg.description}")
                appendLine("U:${pkg.url}")
                appendLine("L:${pkg.license}")
                appendLine("S:${pkg.packageSize}")
                appendLine("I:${pkg.installedSize}")
                if (pkg.dependencies.isNotEmpty()) {
                    append("D:${pkg.dependencies.joinToString(" ")}")
                } else {
                    append("D:")
                }
            }.trimEnd()
        }
        dbFile.writeText(content + if (content.isNotEmpty()) "\n\n" else "")
    }

    companion object {
        const val ALPINE_VERSION = "3.21.3"
        const val APK_TOOLS_VERSION = "2.14.6-r3"

        private val ESSENTIAL_BASE_PACKAGES = setOf(
            "alpine-baselayout",
            "alpine-release",
            "apk-tools",
            "busybox",
            "musl",
        )

        fun detectAlpineArch(): String {
            val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull()?.lowercase(Locale.US).orEmpty()
            return when {
                primaryAbi.contains("arm64") || primaryAbi.contains("aarch64") -> "aarch64"
                primaryAbi.contains("armeabi") || primaryAbi.contains("armv7") -> "armv7"
                primaryAbi.contains("x86_64") -> "x86_64"
                primaryAbi.contains("x86") -> "x86"
                else -> "aarch64"
            }
        }

        fun normalizeDependencyToken(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("!")) return null
            if (trimmed.startsWith("so:") || trimmed.startsWith("cmd:") || trimmed.startsWith("pc:")) return null
            val stripped = trimmed.split(Regex("[><=~]")).firstOrNull()?.trim().orEmpty()
            return stripped.ifEmpty { null }
        }

        fun formatBytes(bytes: Long): String {
            return when {
                bytes <= 0L -> "0 B"
                bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
                bytes >= 1024L -> "${bytes / 1024L} KiB"
                else -> "$bytes B"
            }
        }

        fun extractTarGzEntry(inputStream: InputStream, targetName: String): String? {
            GZIPInputStream(inputStream).use { gz ->
                val header = ByteArray(512)
                while (true) {
                    val read = readFully(gz, header)
                    if (read < 512) return null
                    if (header.all { it == 0.toByte() }) return null

                    val name = extractNullTerminatedAscii(header, 0, 100)
                    val prefix = extractNullTerminatedAscii(header, 345, 155)
                    val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
                    val sizeStr = extractNullTerminatedAscii(header, 124, 12).trim()
                    val size = sizeStr.toLongOrNull(8) ?: 0L

                    if (fullName == targetName || fullName.endsWith("/$targetName")) {
                        val out = ByteArrayOutputStream(size.toInt().coerceIn(0, 64 * 1024 * 1024))
                        copyExact(gz, out, size)
                        return out.toString(Charsets.UTF_8.name())
                    } else {
                        skipExact(gz, size)
                    }

                    val remainder = (size % 512L).toInt()
                    if (remainder != 0) {
                        skipExact(gz, (512 - remainder).toLong())
                    }
                }
            }
        }

        fun parseApkIndex(rawIndex: String, repository: String): Map<String, ApkPackageInfo> {
            val result = linkedMapOf<String, ApkPackageInfo>()
            val stanzas = rawIndex.split(Regex("\\r?\\n\\r?\\n"))
            for (stanza in stanzas) {
                if (stanza.isBlank()) continue
                var name = ""
                var version = ""
                var arch = "aarch64"
                var desc = ""
                var url = "https://alpinelinux.org/"
                var license = "MIT"
                var pkgSize = 0L
                var instSize = 0L
                var deps = emptyList<String>()

                for (line in stanza.lineSequence()) {
                    if (line.length < 2 || line[1] != ':') continue
                    val value = line.substring(2).trim()
                    when (line[0]) {
                        'P' -> name = value
                        'V' -> version = value
                        'A' -> if (value.isNotEmpty()) arch = value
                        'T' -> desc = value
                        'U' -> if (value.isNotEmpty()) url = value
                        'L' -> if (value.isNotEmpty()) license = value
                        'S' -> pkgSize = value.toLongOrNull() ?: 0L
                        'I' -> instSize = value.toLongOrNull() ?: 0L
                        'D' -> deps = if (value.isEmpty()) emptyList() else value.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    }
                }
                if (name.isNotEmpty() && version.isNotEmpty()) {
                    result[name] = ApkPackageInfo(
                        name = name,
                        version = version,
                        arch = arch,
                        description = desc,
                        url = url,
                        license = license,
                        packageSize = pkgSize,
                        installedSize = instSize,
                        dependencies = deps,
                        repository = repository,
                    )
                }
            }
            return result
        }

        private fun readFully(stream: InputStream, buffer: ByteArray): Int {
            var offset = 0
            while (offset < buffer.size) {
                val n = stream.read(buffer, offset, buffer.size - offset)
                if (n <= 0) break
                offset += n
            }
            return offset
        }

        private fun copyExact(stream: InputStream, out: ByteArrayOutputStream, count: Long) {
            val buf = ByteArray(8192)
            var remaining = count
            while (remaining > 0L) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = stream.read(buf, 0, toRead)
                if (n <= 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
        }

        private fun skipExact(stream: InputStream, count: Long) {
            val buf = ByteArray(8192)
            var remaining = count
            while (remaining > 0L) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = stream.read(buf, 0, toRead)
                if (n <= 0) break
                remaining -= n
            }
        }

        private fun extractNullTerminatedAscii(bytes: ByteArray, offset: Int, length: Int): String {
            var end = offset
            val limit = (offset + length).coerceAtMost(bytes.size)
            while (end < limit && bytes[end] != 0.toByte()) {
                end++
            }
            return String(bytes, offset, end - offset, Charsets.US_ASCII)
        }

        val OFFLINE_FALLBACK_INDEX: String = """
P:alpine-baselayout
V:3.6.8-r1
A:aarch64
T:Alpine base dir structure and init scripts
U:https://alpinelinux.org/
L:GPL-2.0-only
S:24576
I:409600
D:alpine-baselayout-data busybox

P:alpine-release
V:3.21.3-r0
A:noarch
T:Alpine release metadata files
U:https://alpinelinux.org/
L:MIT
S:2048
I:16384
D:

P:apk-tools
V:2.14.6-r3
A:aarch64
T:Alpine Package Keeper - package manager for alpine
U:https://gitlab.alpinelinux.org/alpine/apk-tools
L:GPL-2.0-only
S:148000
I:327680
D:musl zlib openssl

P:busybox
V:1.37.0-r12
A:aarch64
T:Size optimized toolbox of many common UNIX utilities
U:https://busybox.net/
L:GPL-2.0-only
S:540672
I:983040
D:musl

P:busybox-binsh
V:1.37.0-r12
A:aarch64
T:BusyBox ash /bin/sh symlink
U:https://busybox.net/
L:GPL-2.0-only
S:2048
I:16384
D:busybox

P:musl
V:1.2.5-r9
A:aarch64
T:the musl c library (libc) implementation
U:https://musl.libc.org/
L:MIT
S:412000
I:655360
D:

P:musl-dev
V:1.2.5-r9
A:aarch64
T:the musl c library (libc) implementation (development files)
U:https://musl.libc.org/
L:MIT
S:2940000
I:6553600
D:musl

P:ca-certificates
V:20241121-r1
A:noarch
T:Common CA certificates PEM files from Mozilla
U:https://www.mozilla.org/en-US/about/governance/policies/security-group/certs/
L:MPL-2.0 GPL-2.0-or-later
S:131072
I:327680
D:ca-certificates-bundle

P:curl
V:8.12.1-r0
A:aarch64
T:URL retrieval utility and library
U:https://curl.se/
L:curl
S:163840
I:311296
D:ca-certificates libcurl

P:libcurl
V:8.12.1-r0
A:aarch64
T:The multiprotocol file transfer library
U:https://curl.se/
L:curl
S:311296
I:655360
D:openssl zlib

P:git
V:2.47.2-r0
A:aarch64
T:Distributed version control system
U:https://git-scm.com/
L:GPL-2.0-or-later
S:4194304
I:16777216
D:curl expat openssl pcre2 zlib

P:htop
V:3.3.0-r0
A:aarch64
T:Interactive process viewer
U:https://htop.dev/
L:GPL-2.0-or-later
S:147456
I:327680
D:ncurses-libs

P:jq
V:1.7.1-r0
A:aarch64
T:Lightweight and flexible command-line JSON processor
U:https://jqlang.github.io/jq/
L:MIT
S:196608
I:458752
D:oniguruma

P:python3
V:3.12.9-r0
A:aarch64
T:A high-level scripting language
U:https://www.python.org/
L:PSF-2.0
S:16777216
I:54525952
D:musl openssl readline sqlite-libs zlib

P:vim
V:9.1.1043-r0
A:aarch64
T:Improved vi text editor
U:https://www.vim.org/
L:Vim
S:1703936
I:4194304
D:ncurses-libs

P:bash
V:5.2.37-r0
A:aarch64
T:The GNU Bourne Again shell
U:https://www.gnu.org/software/bash/bash.html
L:GPL-3.0-or-later
S:491520
I:983040
D:readline

P:zsh
V:5.9-r4
A:aarch64
T:Very advanced and programmable command interpreter
U:https://www.zsh.org/
L:MIT-Modern-Variant
S:1835008
I:5242880
D:ncurses-libs pcre2

P:gawk
V:5.3.1-r0
A:aarch64
T:GNU awk pattern-matching language
U:https://www.gnu.org/software/gawk/
L:GPL-3.0-or-later
S:589824
I:1310720
D:musl

P:sqlite
V:3.48.0-r0
A:aarch64
T:C library that implements an SQL database engine
U:https://www.sqlite.org/
L:blessing
S:1179648
I:2359296
D:sqlite-libs readline

P:openssh-client
V:9.9_p2-r0
A:aarch64
T:OpenBSD SSH client applications
U:https://www.openssh.com/portable.html
L:BSD-2-Clause
S:851968
I:2097152
D:musl openssl zlib

P:iperf3
V:3.18-r0
A:aarch64
T:Tool to measure IP bandwidth using UDP or TCP
U:https://github.com/esnet/iperf
L:BSD-3-Clause
S:98304
I:245760
D:openssl

P:tar
V:1.35-r2
A:aarch64
T:Utility used to store, backup, and transport files
U:https://www.gnu.org/software/tar/
L:GPL-3.0-or-later
S:262144
I:655360
D:musl

P:tmux
V:3.5a-r0
A:aarch64
T:Tool to control multiple terminals from a single screen
U:https://tmux.github.io/
L:ISC
S:393216
I:786432
D:libevent ncurses-libs

P:nano
V:8.3-r0
A:aarch64
T:Enhanced clone of the Pico text editor
U:https://www.nano-editor.org/
L:GPL-3.0-or-later
S:245760
I:589824
D:ncurses-libs

P:openssl
V:3.3.3-r0
A:aarch64
T:Toolkit for Transport Layer Security (TLS)
U:https://www.openssl.org/
L:Apache-2.0
S:393216
I:851968
D:libcrypto3 libssl3

P:zlib
V:1.3.1-r2
A:aarch64
T:A compression/decompression library
U:https://zlib.net/
L:Zlib
S:65536
I:131072
D:musl
        """.trimIndent()
    }
}
