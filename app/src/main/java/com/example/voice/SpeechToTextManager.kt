package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import java.util.Locale

sealed interface SpeechToTextState {
    data object Idle : SpeechToTextState
    data class Listening(
        val partialText: String = "",
        val rmsDb: Float = 0f,
        val elapsedSeconds: Int = 0,
    ) : SpeechToTextState
    data class Recognized(val transcript: String) : SpeechToTextState
    data class Error(
        val errorCode: Int,
        val message: String,
        val isServiceFailure: Boolean = false,
    ) : SpeechToTextState
}

/**
 * Service wrapper around Android's [SpeechRecognizer] API that captures spoken
 * commands, streams partial transcripts and audio dB levels, and delivers final
 * command text for injection into the terminal input field.
 */
class SpeechToTextManager(
    context: Context,
    private val onTranscriptCaptured: ((String) -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var speechRecognizer: SpeechRecognizer? = null
    private var tickerJob: Job? = null
    private var listeningStartMillis: Long = 0L

    private val _state = MutableStateFlow<SpeechToTextState>(SpeechToTextState.Idle)
    val state: StateFlow<SpeechToTextState> = _state.asStateFlow()

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun startListening(locale: Locale = Locale.getDefault()) {
        if (!isAvailable()) {
            _state.value = SpeechToTextState.Error(
                errorCode = SpeechRecognizer.ERROR_CLIENT,
                message = "Speech recognition service unavailable on this device.",
                isServiceFailure = true,
            )
            return
        }

        try {
            releaseRecognizer()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            speechRecognizer = recognizer
            listeningStartMillis = System.currentTimeMillis()

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _state.value = SpeechToTextState.Listening()
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    val current = _state.value
                    if (current is SpeechToTextState.Listening) {
                        _state.value = current.copy(
                            rmsDb = rmsdB.coerceIn(0f, 12f),
                            elapsedSeconds = elapsedSecondsNow(),
                        )
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    tickerJob?.cancel()
                    val isServiceFailure = error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_SERVER ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Network error during speech recognition."
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Try speaking closer to the mic."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy. Please try again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out. Tap mic to retry."
                        else -> "Speech recognition error ($error)."
                    }
                    _state.value = SpeechToTextState.Error(
                        errorCode = error,
                        message = msg,
                        isServiceFailure = isServiceFailure,
                    )
                }

                override fun onResults(results: Bundle?) {
                    tickerJob?.cancel()
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val transcript = matches?.firstOrNull()?.trim().orEmpty()
                    if (transcript.isNotEmpty()) {
                        _state.value = SpeechToTextState.Recognized(transcript)
                        onTranscriptCaptured?.invoke(transcript)
                    } else {
                        _state.value = SpeechToTextState.Error(
                            errorCode = SpeechRecognizer.ERROR_NO_MATCH,
                            message = "No transcript captured.",
                        )
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull().orEmpty()
                    val current = _state.value
                    if (current is SpeechToTextState.Listening) {
                        _state.value = current.copy(
                            partialText = partial,
                            elapsedSeconds = elapsedSecondsNow(),
                        )
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            _state.value = SpeechToTextState.Listening()
            startElapsedTicker()
            recognizer.startListening(intent)
        } catch (e: Exception) {
            tickerJob?.cancel()
            _state.value = SpeechToTextState.Error(
                errorCode = SpeechRecognizer.ERROR_CLIENT,
                message = e.localizedMessage ?: "Failed to start voice input.",
                isServiceFailure = true,
            )
        }
    }

    fun stopListening() {
        tickerJob?.cancel()
        runCatching { speechRecognizer?.stopListening() }
    }

    fun cancelListening() {
        tickerJob?.cancel()
        releaseRecognizer()
        _state.value = SpeechToTextState.Idle
    }

    fun resetState() {
        _state.value = SpeechToTextState.Idle
    }

    fun destroy() {
        tickerJob?.cancel()
        releaseRecognizer()
        scope.cancel()
    }

    private fun startElapsedTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && _state.value is SpeechToTextState.Listening) {
                delay(500L)
                val current = _state.value as? SpeechToTextState.Listening ?: break
                _state.value = current.copy(elapsedSeconds = elapsedSecondsNow())
            }
        }
    }

    private fun elapsedSecondsNow(): Int =
        ((System.currentTimeMillis() - listeningStartMillis) / 1000L).toInt().coerceAtLeast(0)

    private fun releaseRecognizer() {
        speechRecognizer?.let { sr ->
            speechRecognizer = null
            runCatching { sr.cancel() }
            runCatching { sr.destroy() }
        }
    }
}
