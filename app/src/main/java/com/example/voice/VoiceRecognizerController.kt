package com.example.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.log10

enum class VoiceEngineMode(val label: String) {
    AUTO("Auto"),
    DEVICE_SPEECH("Live Speech"),
    GEMINI_AUDIO("Gemini Audio"),
}

sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(
        val partialText: String = "",
        val rmsDb: Float = 0f,
        val isAudioCapture: Boolean = false,
        val elapsedSeconds: Int = 0,
    ) : VoiceState
    data class Result(val transcript: String) : VoiceState
    data class AudioReady(val audioBytes: ByteArray, val mimeType: String = "audio/mp4") : VoiceState
    data class Error(val message: String) : VoiceState
}

class VoiceRecognizerController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val speechToTextManager = SpeechToTextManager(appContext)
    private var mediaRecorder: MediaRecorder? = null
    private var tempAudioFile: File? = null
    private var meterJob: Job? = null
    private var recordingStartMillis: Long = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _engineMode = MutableStateFlow(VoiceEngineMode.AUTO)
    val engineMode: StateFlow<VoiceEngineMode> = _engineMode.asStateFlow()

    init {
        scope.launch {
            speechToTextManager.state.collect { sttState ->
                // Only mirror SpeechToTextManager when MediaRecorder audio capture is not active
                if (mediaRecorder != null) return@collect
                when (sttState) {
                    is SpeechToTextState.Idle -> {
                        if (_voiceState.value is VoiceState.Listening &&
                            !(_voiceState.value as VoiceState.Listening).isAudioCapture
                        ) {
                            _voiceState.value = VoiceState.Idle
                        }
                    }
                    is SpeechToTextState.Listening -> {
                        _voiceState.value = VoiceState.Listening(
                            partialText = sttState.partialText,
                            rmsDb = sttState.rmsDb,
                            isAudioCapture = false,
                            elapsedSeconds = sttState.elapsedSeconds,
                        )
                    }
                    is SpeechToTextState.Recognized -> {
                        speechToTextManager.resetState()
                        _voiceState.value = VoiceState.Result(sttState.transcript)
                    }
                    is SpeechToTextState.Error -> {
                        speechToTextManager.resetState()
                        if (_engineMode.value == VoiceEngineMode.AUTO && sttState.isServiceFailure) {
                            Log.w(TAG, "SpeechToTextManager service failure (${sttState.errorCode}); falling back to Gemini Audio.")
                            startMediaRecorderCapture()
                        } else {
                            _voiceState.value = VoiceState.Error(sttState.message)
                        }
                    }
                }
            }
        }
    }

    fun isSpeechServiceAvailable(): Boolean = speechToTextManager.isAvailable()

    fun cycleEngineMode() {
        _engineMode.value = when (_engineMode.value) {
            VoiceEngineMode.AUTO -> VoiceEngineMode.GEMINI_AUDIO
            VoiceEngineMode.GEMINI_AUDIO -> VoiceEngineMode.DEVICE_SPEECH
            VoiceEngineMode.DEVICE_SPEECH -> VoiceEngineMode.AUTO
        }
    }

    fun setEngineMode(mode: VoiceEngineMode) {
        _engineMode.value = mode
    }

    fun startListening() {
        val useAudioFallback = when (_engineMode.value) {
            VoiceEngineMode.GEMINI_AUDIO -> true
            VoiceEngineMode.DEVICE_SPEECH -> false
            VoiceEngineMode.AUTO -> !speechToTextManager.isAvailable()
        }

        if (useAudioFallback) {
            startMediaRecorderCapture()
        } else {
            stopActiveMediaCapture(discard = true)
            if (!speechToTextManager.isAvailable()) {
                _voiceState.value = VoiceState.Error(
                    "System speech service unavailable. Switch Voice mode to Gemini Audio or Auto."
                )
            } else {
                speechToTextManager.startListening()
            }
        }
    }

    private fun startMediaRecorderCapture() {
        try {
            stopActiveMediaCapture(discard = true)
            speechToTextManager.cancelListening()
            val audioFile = File.createTempFile("alpaca_voice_", ".m4a", appContext.cacheDir)
            tempAudioFile = audioFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(16_000)
                setMaxDuration(MAX_AUDIO_DURATION_MS)
                setOutputFile(audioFile.absolutePath)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stopListening()
                    }
                }
                prepare()
                start()
            }

            mediaRecorder = recorder
            recordingStartMillis = System.currentTimeMillis()
            _voiceState.value = VoiceState.Listening(isAudioCapture = true, rmsDb = 1.5f)
            startAudioAmplitudeMeter()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            cleanupTempFile()
            mediaRecorder = null
            _voiceState.value = VoiceState.Error(
                e.localizedMessage ?: "Unable to access microphone for voice capture."
            )
        }
    }

    private fun startAudioAmplitudeMeter() {
        meterJob?.cancel()
        meterJob = scope.launch {
            while (isActive && mediaRecorder != null && _voiceState.value is VoiceState.Listening) {
                delay(90L)
                val recorder = mediaRecorder ?: break
                val amp = try {
                    recorder.maxAmplitude
                } catch (_: Exception) {
                    0
                }
                val normalizedDb = if (amp > 0) {
                    ((log10(amp.toDouble()) / 4.5) * 12.0).toFloat().coerceIn(0.8f, 12f)
                } else {
                    1.0f
                }
                val elapsed = ((System.currentTimeMillis() - recordingStartMillis) / 1000L).toInt()
                val current = _voiceState.value as? VoiceState.Listening ?: break
                _voiceState.value = current.copy(
                    rmsDb = normalizedDb,
                    elapsedSeconds = elapsed,
                )
            }
        }
    }

    fun stopListening() {
        meterJob?.cancel()
        val recorder = mediaRecorder
        if (recorder != null) {
            mediaRecorder = null
            val durationMs = System.currentTimeMillis() - recordingStartMillis
            try {
                recorder.stop()
            } catch (e: RuntimeException) {
                Log.w(TAG, "MediaRecorder stop error", e)
                runCatching { recorder.release() }
                cleanupTempFile()
                _voiceState.value = VoiceState.Error("Recording too short. Speak for at least 1 second.")
                return
            }
            runCatching { recorder.release() }

            val file = tempAudioFile
            if (file != null && file.exists() && file.length() > 0L && durationMs >= 350L) {
                val bytes = runCatching { file.readBytes() }.getOrNull()
                cleanupTempFile()
                if (bytes != null && bytes.isNotEmpty()) {
                    _voiceState.value = VoiceState.AudioReady(bytes, "audio/mp4")
                } else {
                    _voiceState.value = VoiceState.Error("Failed to read recorded voice clip.")
                }
            } else {
                cleanupTempFile()
                _voiceState.value = VoiceState.Error("Recording too short. Speak clearly and try again.")
            }
            return
        }

        speechToTextManager.stopListening()
    }

    fun cancelListening() {
        stopActiveMediaCapture(discard = true)
        speechToTextManager.cancelListening()
        _voiceState.value = VoiceState.Idle
    }

    fun resetState() {
        speechToTextManager.resetState()
        _voiceState.value = VoiceState.Idle
    }

    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (tts == null) {
            pendingSpeech = clean
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.language = Locale.getDefault()
                    pendingSpeech?.let { queued ->
                        pendingSpeech = null
                        tts?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "alpaca_voice_${System.currentTimeMillis()}")
                    }
                }
            }
        } else if (ttsReady) {
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "alpaca_voice_${System.currentTimeMillis()}")
        } else {
            pendingSpeech = clean
        }
    }

    fun stopSpeaking() {
        runCatching { tts?.stop() }
    }

    private fun stopActiveMediaCapture(discard: Boolean) {
        meterJob?.cancel()
        meterJob = null
        mediaRecorder?.let { rec ->
            mediaRecorder = null
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        if (discard) {
            cleanupTempFile()
        }
    }

    private fun cleanupTempFile() {
        runCatching { tempAudioFile?.delete() }
        tempAudioFile = null
    }

    fun destroy() {
        stopActiveMediaCapture(discard = true)
        speechToTextManager.destroy()
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ttsReady = false
        scope.cancel()
    }

    companion object {
        private const val TAG = "VoiceController"
        private const val MAX_AUDIO_DURATION_MS = 30_000
    }
}
