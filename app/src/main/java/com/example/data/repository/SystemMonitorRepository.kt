package com.example.data.repository

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import com.example.data.model.SystemMetricsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class SystemMonitorRepository(context: Context) {
    private val appContext = context.applicationContext
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val localIoBytesCounter = AtomicLong(0L)

    fun recordWorkspaceIoBytes(bytes: Long) {
        if (bytes > 0L) {
            localIoBytesCounter.addAndGet(bytes)
        }
    }

    fun observeMetrics(intervalMs: Long = 1500L): Flow<SystemMetricsSnapshot> = flow {
        val cpuHistory = ArrayDeque<Float>(MAX_HISTORY)
        val memHistory = ArrayDeque<Float>(MAX_HISTORY)
        val diskIoHistory = ArrayDeque<Float>(MAX_HISTORY)
        val netHistory = ArrayDeque<Float>(MAX_HISTORY)

        var prevWallMs = SystemClock.elapsedRealtime()
        var prevProcCpuMs = Process.getElapsedCpuTime()
        var prevProcStat = readProcStatTotals()

        var prevNetRx = safeTrafficBytes { TrafficStats.getTotalRxBytes() }
        var prevNetTx = safeTrafficBytes { TrafficStats.getTotalTxBytes() }
        if (prevNetRx < 0L) prevNetRx = safeTrafficBytes { TrafficStats.getUidRxBytes(Process.myUid()) }
        if (prevNetTx < 0L) prevNetTx = safeTrafficBytes { TrafficStats.getUidTxBytes(Process.myUid()) }

        var prevProcIoBytes = readProcIoBytes() ?: localIoBytesCounter.get()
        val sessionStartNet = (prevNetRx.coerceAtLeast(0L) + prevNetTx.coerceAtLeast(0L))

        while (currentCoroutineContext().isActive) {
            val nowWallMs = SystemClock.elapsedRealtime()
            val dtSec = ((nowWallMs - prevWallMs).coerceAtLeast(100L)) / 1000f

            // 1. Real CPU Utilization
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val currentProcStat = readProcStatTotals()
            val cpuPercent = if (currentProcStat != null && prevProcStat != null) {
                val totalDelta = (currentProcStat.first - prevProcStat.first).coerceAtLeast(1L)
                val idleDelta = (currentProcStat.second - prevProcStat.second).coerceAtLeast(0L)
                (((totalDelta - idleDelta).toFloat() / totalDelta.toFloat()) * 100f).coerceIn(0f, 100f)
            } else {
                val nowProcCpuMs = Process.getElapsedCpuTime()
                val cpuDeltaMs = (nowProcCpuMs - prevProcCpuMs).coerceAtLeast(0L)
                prevProcCpuMs = nowProcCpuMs
                val wallDeltaMs = (nowWallMs - prevWallMs).coerceAtLeast(1L)
                // Scale process CPU share + thread activity baseline for legible live feedback
                ((cpuDeltaMs.toFloat() / (wallDeltaMs * cores).toFloat()) * 100f)
                    .coerceIn(0f, 100f)
            }
            prevProcStat = currentProcStat
            val cpuFreqMhz = readCpuFreqMhz()

            // 2. Real Memory Consumption
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val totalMemMb = (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(1L)
            val availMemMb = (memInfo.availMem / (1024L * 1024L)).coerceAtLeast(0L)
            val usedMemMb = (totalMemMb - availMemMb).coerceAtLeast(0L)
            val memPercent = ((usedMemMb.toFloat() / totalMemMb.toFloat()) * 100f).coerceIn(0f, 100f)

            val runtime = Runtime.getRuntime()
            val jvmHeapMb = ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)).coerceAtLeast(1L)

            // 3. Real Disk & I/O Telemetry
            val statFs = StatFs(appContext.filesDir.absolutePath)
            val diskTotalBytes = statFs.totalBytes.coerceAtLeast(1L)
            val diskAvailBytes = statFs.availableBytes.coerceAtLeast(0L)
            val diskUsedBytes = (diskTotalBytes - diskAvailBytes).coerceAtLeast(0L)
            val diskTotalGb = diskTotalBytes / (1024f * 1024f * 1024f)
            val diskUsedGb = diskUsedBytes / (1024f * 1024f * 1024f)
            val diskPercent = ((diskUsedBytes.toFloat() / diskTotalBytes.toFloat()) * 100f).coerceIn(0f, 100f)

            val currentProcIo = readProcIoBytes() ?: localIoBytesCounter.get()
            val ioDeltaBytes = (currentProcIo - prevProcIoBytes).coerceAtLeast(0L)
            prevProcIoBytes = currentProcIo
            val diskIoKbps = (ioDeltaBytes / 1024f) / dtSec

            // 4. Real Network Traffic
            var curNetRx = safeTrafficBytes { TrafficStats.getTotalRxBytes() }
            var curNetTx = safeTrafficBytes { TrafficStats.getTotalTxBytes() }
            if (curNetRx < 0L) curNetRx = safeTrafficBytes { TrafficStats.getUidRxBytes(Process.myUid()) }
            if (curNetTx < 0L) curNetTx = safeTrafficBytes { TrafficStats.getUidTxBytes(Process.myUid()) }

            val rxDelta = if (curNetRx >= 0L && prevNetRx >= 0L) (curNetRx - prevNetRx).coerceAtLeast(0L) else 0L
            val txDelta = if (curNetTx >= 0L && prevNetTx >= 0L) (curNetTx - prevNetTx).coerceAtLeast(0L) else 0L
            prevNetRx = curNetRx
            prevNetTx = curNetTx

            val netRxKbps = (rxDelta / 1024f) / dtSec
            val netTxKbps = (txDelta / 1024f) / dtSec
            val totalSessionNetBytes = ((curNetRx.coerceAtLeast(0L) + curNetTx.coerceAtLeast(0L)) - sessionStartNet).coerceAtLeast(0L)
            val totalNetMb = totalSessionNetBytes / (1024f * 1024f)

            // 5. Thread / process activity
            val activeThreads = Thread.activeCount().coerceAtLeast(1)

            prevWallMs = nowWallMs

            appendHistory(cpuHistory, cpuPercent)
            appendHistory(memHistory, memPercent)
            appendHistory(diskIoHistory, diskIoKbps)
            appendHistory(netHistory, netRxKbps + netTxKbps)

            emit(
                SystemMetricsSnapshot(
                    cpuPercent = cpuPercent,
                    cpuFreqMhz = cpuFreqMhz,
                    cpuCores = cores,
                    memUsedMb = usedMemMb,
                    memTotalMb = totalMemMb,
                    memPercent = memPercent,
                    jvmHeapUsedMb = jvmHeapMb,
                    diskUsedGb = diskUsedGb,
                    diskTotalGb = diskTotalGb,
                    diskPercent = diskPercent,
                    diskIoKbps = diskIoKbps,
                    netRxKbps = netRxKbps,
                    netTxKbps = netTxKbps,
                    totalNetMb = totalNetMb,
                    processCount = activeThreads,
                    uptimeSeconds = SystemClock.elapsedRealtime() / 1000L,
                    cpuHistory = cpuHistory.toList(),
                    memHistory = memHistory.toList(),
                    diskIoHistory = diskIoHistory.toList(),
                    netHistory = netHistory.toList(),
                )
            )
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    private fun appendHistory(queue: ArrayDeque<Float>, value: Float) {
        if (queue.size >= MAX_HISTORY) {
            queue.removeFirst()
        }
        queue.addLast(value)
    }

    private fun readProcStatTotals(): Pair<Long, Long>? {
        return try {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return null
            if (!line.startsWith("cpu ")) return null
            val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (parts.size < 4) return null
            val total = parts.sum()
            val idle = parts[3] + (parts.getOrNull(4) ?: 0L)
            total to idle
        } catch (_: Exception) {
            null
        }
    }

    private fun readCpuFreqMhz(): Int {
        return try {
            val raw = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
                .readText().trim().toLongOrNull() ?: return 0
            (raw / 1000L).toInt()
        } catch (_: Exception) {
            0
        }
    }

    private fun readProcIoBytes(): Long? {
        return try {
            val lines = File("/proc/self/io").readLines()
            var readBytes = 0L
            var writeBytes = 0L
            for (line in lines) {
                when {
                    line.startsWith("read_bytes:") -> readBytes = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                    line.startsWith("write_bytes:") -> writeBytes = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                }
            }
            readBytes + writeBytes
        } catch (_: Exception) {
            null
        }
    }

    private inline fun safeTrafficBytes(block: () -> Long): Long {
        return try {
            val value = block()
            if (value == TrafficStats.UNSUPPORTED.toLong()) -1L else value
        } catch (_: Exception) {
            -1L
        }
    }

    companion object {
        private const val MAX_HISTORY = 28
    }
}
