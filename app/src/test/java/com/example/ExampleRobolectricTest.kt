package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.SystemMetricsSnapshot
import com.example.data.repository.AlpacaBootstrapEngine
import com.example.data.repository.SshContainerSession
import com.example.data.repository.SystemMonitorRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `real persistent linux shell executes kernel commands pipes state and exit codes`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val monitor = SystemMonitorRepository(context)
        val engine = AlpacaBootstrapEngine(context, monitor)
        val metrics = SystemMetricsSnapshot(cpuPercent = 22.5f, memUsedMb = 512, memTotalMb = 2048)

        val uname = engine.execute("uname -s", metrics, emptyList())
        assertEquals(0, uname.exitCode)
        assertTrue(uname.stdout.contains("Linux"))

        val pipeTest = engine.execute("printf 'alpha\\nbeta\\ngamma\\n' | grep beta", metrics, emptyList())
        assertEquals(0, pipeTest.exitCode)
        assertEquals("beta", pipeTest.stdout)

        // Verify persistent state across commands (export + cd)
        val setVar = engine.execute("export MY_REAL_VAR=container_ok && mkdir -p ~/testdir && cd ~/testdir", metrics, emptyList())
        assertEquals(0, setVar.exitCode)
        assertEquals("~/testdir", setVar.newPromptPath)

        val readVarAndPwd = engine.execute("printf '%s' \"\$MY_REAL_VAR\" > flag.txt && cat flag.txt", metrics, emptyList())
        assertEquals(0, readVarAndPwd.exitCode)
        assertEquals("container_ok", readVarAndPwd.stdout)

        // Verify real non-simulated 127 error when invoking an absent binary
        val missingCmd = engine.execute("nonexistent_binary_xyz_123", metrics, emptyList())
        assertNotEquals(0, missingCmd.exitCode)
    }

    @Test
    fun `ssh container session strips ansi escape and osc terminal sequences`() {
        val raw = "\u001B]0;root@alpine:~\u0007\u001B[01;32mroot@alpine\u001B[00m:\u001B[01;34m~\u001B[00m# apk info\r\n"
        val clean = SshContainerSession.stripAnsiSequences(raw)
        assertEquals("root@alpine:~# apk info\n", clean)
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
