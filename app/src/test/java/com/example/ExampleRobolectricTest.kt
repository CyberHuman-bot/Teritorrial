package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.SystemMetricsSnapshot
import com.example.data.repository.AlpacaBootstrapEngine
import com.example.data.repository.SystemMonitorRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    fun `alpaca bootstrap shell executes filesystem pipes and apk commands`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val monitor = SystemMonitorRepository(context)
        val engine = AlpacaBootstrapEngine(context, monitor)
        val metrics = SystemMetricsSnapshot(cpuPercent = 22.5f, memUsedMb = 512, memTotalMb = 2048)

        val osRelease = engine.execute("cat /etc/os-release", metrics, emptyList())
        assertEquals(0, osRelease.exitCode)
        assertTrue(osRelease.stdout.contains("Alpaca Linux"))

        val pipeTest = engine.execute("echo \"alpha\nbeta\ngamma\" | grep beta", metrics, emptyList())
        assertEquals(0, pipeTest.exitCode)
        assertEquals("beta", pipeTest.stdout)

        val redirectAndRead = engine.execute("mkdir -p ~/testdir && echo \"ok\" > ~/testdir/flag.txt && cat ~/testdir/flag.txt", metrics, emptyList())
        assertEquals(0, redirectAndRead.exitCode)
        assertEquals("ok", redirectAndRead.stdout)

        val apkAdd = engine.execute("apk add jq && apk info", metrics, emptyList())
        assertEquals(0, apkAdd.exitCode)
        assertTrue(apkAdd.stdout.contains("jq - installed"))
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
