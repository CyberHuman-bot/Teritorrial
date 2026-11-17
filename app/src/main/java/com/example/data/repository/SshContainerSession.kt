package com.example.data.repository

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.Properties

data class SshContainerConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String = "",
    val privateKey: String = "",
    val label: String = "",
) {
    fun displayTarget(): String = "${username.ifBlank { "root" }}@$host:$port"
}

sealed interface SshConnectionState {
    data object Disconnected : SshConnectionState
    data class Connecting(val target: String) : SshConnectionState
    data class Connected(val config: SshContainerConfig) : SshConnectionState
    data class Error(val message: String) : SshConnectionState
}

class SshContainerSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<SshConnectionState>(SshConnectionState.Disconnected)
    val state: StateFlow<SshConnectionState> = _state.asStateFlow()

    private val _outputStream = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val outputStream: SharedFlow<String> = _outputStream.asSharedFlow()

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var ptyOut: OutputStream? = null
    private var readerJob: Job? = null

    fun isConnected(): Boolean = _state.value is SshConnectionState.Connected && channel?.isConnected == true

    suspend fun connect(config: SshContainerConfig): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanHost = config.host.trim()
        val cleanUser = config.username.trim()
        if (cleanHost.isEmpty() || cleanUser.isEmpty()) {
            val err = IllegalArgumentException("Host and username are required.")
            _state.value = SshConnectionState.Error(err.message!!)
            return@withContext Result.failure(err)
        }

        disconnectInternal(emitBanner = false)
        val normalized = config.copy(host = cleanHost, username = cleanUser)
        _state.value = SshConnectionState.Connecting(normalized.displayTarget())

        try {
            val jsch = JSch()
            if (normalized.privateKey.isNotBlank()) {
                val keyBytes = normalized.privateKey.trim().toByteArray(Charsets.UTF_8)
                jsch.addIdentity("container_identity", keyBytes, null, null)
            }

            val sshSession = jsch.getSession(cleanUser, cleanHost, normalized.port.coerceIn(1, 65535))
            if (normalized.password.isNotBlank()) {
                sshSession.setPassword(normalized.password)
            }
            val props = Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications", "publickey,password,keyboard-interactive")
            }
            sshSession.setConfig(props)
            sshSession.timeout = 15_000
            sshSession.connect(12_000)

            val shellChannel = sshSession.openChannel("shell") as ChannelShell
            shellChannel.setPty(true)
            shellChannel.setPtyType("xterm-256color", 120, 40, 960, 640)
            val input = shellChannel.inputStream
            val out = shellChannel.outputStream
            shellChannel.connect(8_000)

            session = sshSession
            channel = shellChannel
            ptyOut = out
            _state.value = SshConnectionState.Connected(normalized)

            readerJob = scope.launch {
                val buf = ByteArray(4096)
                try {
                    while (isActive && shellChannel.isConnected) {
                        val read = input.read(buf)
                        if (read < 0) break
                        if (read > 0) {
                            val rawChunk = String(buf, 0, read, Charsets.UTF_8)
                            val cleaned = stripAnsiSequences(rawChunk)
                            if (cleaned.isNotBlank()) {
                                _outputStream.emit(cleaned.trimEnd('\r', '\n'))
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    if (_state.value is SshConnectionState.Connected) {
                        _outputStream.emit("[Container SSH session closed]")
                        disconnectInternal(emitBanner = false)
                    }
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            disconnectInternal(emitBanner = false)
            val msg = e.localizedMessage ?: "SSH connection failed"
            _state.value = SshConnectionState.Error(msg)
            Result.failure(e)
        }
    }

    suspend fun sendCommand(command: String): Result<Unit> = withContext(Dispatchers.IO) {
        val out = ptyOut ?: return@withContext Result.failure(IllegalStateException("Container is not connected."))
        return@withContext try {
            out.write((command + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            disconnectInternal(emitBanner = true)
            Result.failure(e)
        }
    }

    suspend fun sendInterrupt() = withContext(Dispatchers.IO) {
        try {
            ptyOut?.write(byteArrayOf(0x03))
            ptyOut?.flush()
        } catch (_: Exception) {
        }
    }

    fun disconnect() {
        disconnectInternal(emitBanner = false)
    }

    private fun disconnectInternal(emitBanner: Boolean) {
        readerJob?.cancel()
        readerJob = null
        try {
            ptyOut?.close()
        } catch (_: Exception) {
        }
        ptyOut = null
        try {
            channel?.disconnect()
        } catch (_: Exception) {
        }
        channel = null
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        session = null
        _state.value = SshConnectionState.Disconnected
        if (emitBanner) {
            _outputStream.tryEmit("[Disconnected from SSH container]")
        }
    }

    companion object {
        private val ANSI_OSC_REGEX = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
        private val ANSI_CSI_REGEX = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
        private val ANSI_OTHER_REGEX = Regex("\u001B[@-Z\\\\-_]")

        fun stripAnsiSequences(input: String): String {
            return input
                .replace(ANSI_OSC_REGEX, "")
                .replace(ANSI_CSI_REGEX, "")
                .replace(ANSI_OTHER_REGEX, "")
                .replace("\r\n", "\n")
                .replace("\r", "")
        }
    }
}
