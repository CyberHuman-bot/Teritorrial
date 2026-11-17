package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.SystemMetricsSnapshot
import com.example.data.repository.AlpacaBootstrapEngine
import com.example.data.repository.AlpineApkManager
import com.example.data.repository.SystemMonitorRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Alpaca Shell", appName)
    }

    @Test
    fun `alpine rootfs executes filesystem pipes apk and legacy path alias`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val monitor = SystemMonitorRepository(context)
        val engine = AlpacaBootstrapEngine(context, monitor)
        val metrics = SystemMetricsSnapshot(cpuPercent = 22.5f, memUsedMb = 512, memTotalMb = 2048)

        val osRelease = engine.execute("cat /etc/os-release", metrics, emptyList())
        assertEquals(0, osRelease.exitCode)
        assertTrue(osRelease.stdout.contains("Alpine Linux"))
        assertTrue(osRelease.stdout.contains("ID=alpine"))

        val alpineRelease = engine.execute("cat /etc/alpine-release", metrics, emptyList())
        assertEquals(0, alpineRelease.exitCode)
        assertEquals(AlpineApkManager.ALPINE_VERSION, alpineRelease.stdout.trim())

        val pipeTest = engine.execute("echo \"alpha\nbeta\ngamma\" | grep beta", metrics, emptyList())
        assertEquals(0, pipeTest.exitCode)
        assertEquals("beta", pipeTest.stdout)

        val redirectAndRead = engine.execute(
            "mkdir -p ~/testdir && echo \"ok\" > ~/testdir/flag.txt && cat /home/alpaca/testdir/flag.txt",
            metrics,
            emptyList(),
        )
        assertEquals(0, redirectAndRead.exitCode)
        assertEquals("ok", redirectAndRead.stdout)

        val apkAdd = engine.execute("apk add jq && apk info", metrics, emptyList())
        assertEquals(0, apkAdd.exitCode)
        assertTrue(apkAdd.stdout.contains("jq-"))
        assertTrue(apkAdd.stdout.contains("installed"))

        val apkInfoDetail = engine.execute("apk info -a jq", metrics, emptyList())
        assertEquals(0, apkInfoDetail.exitCode)
        assertTrue(apkInfoDetail.stdout.contains("status: installed"))
    }

    @Test
    fun `alpine apkindex tar gz extractor and parser decodes real stanza format`() {
        val sampleIndex = """
            P:ripgrep
            V:14.1.1-r0
            A:aarch64
            T:Fast line-oriented regex search tool
            U:https://github.com/BurntSushi/ripgrep
            L:MIT
            S:1450000
            I:4200000
            D:so:libc.musl-aarch64.so.1 pcre2
        """.trimIndent() + "\n\n"

        val payload = sampleIndex.toByteArray(Charsets.UTF_8)
        val header = ByteArray(512)
        "APKINDEX".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        val octalSize = payload.size.toString(8).padStart(11, '0')
        octalSize.toByteArray(Charsets.US_ASCII).copyInto(header, 124)

        val tarGzBytes = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { gz ->
                gz.write(header)
                gz.write(payload)
                val pad = (512 - (payload.size % 512)) % 512
                if (pad > 0) gz.write(ByteArray(pad))
                gz.write(ByteArray(1024))
            }
            baos.toByteArray()
        }

        val extracted = AlpineApkManager.extractTarGzEntry(tarGzBytes.inputStream(), "APKINDEX")
        assertNotNull(extracted)
        val parsed = AlpineApkManager.parseApkIndex(extracted!!, "v3.21/community")
        val rg = parsed["ripgrep"]
        assertNotNull(rg)
        assertEquals("14.1.1-r0", rg!!.version)
        assertEquals("aarch64", rg.arch)
        assertEquals("pcre2", AlpineApkManager.normalizeDependencyToken(rg.dependencies.last()))
    }

    @Test
    fun `speech to text manager initializes idle and handles missing recognizer service cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = com.example.voice.SpeechToTextManager(context)
        assertEquals(com.example.voice.SpeechToTextState.Idle, manager.state.value)
        if (!manager.isAvailable()) {
            manager.startListening()
            assertTrue(manager.state.value is com.example.voice.SpeechToTextState.Error)
            manager.resetState()
            assertEquals(com.example.voice.SpeechToTextState.Idle, manager.state.value)
        }
        manager.destroy()
    }
}
