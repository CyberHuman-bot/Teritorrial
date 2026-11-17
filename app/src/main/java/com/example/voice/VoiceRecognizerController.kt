package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val partialText: String = "", val rmsDb: Float = 0f) : VoiceState
    data class Result(val transcript: String) : VoiceState
    data class Error(val message: String) : VoiceState
}

class VoiceRecognizerController(context: Context) {
    private val appContext = context.applicationContext
    private var speechRecognizer: SpeechRecognizer? = null

    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun startListening() {
        if (!isAvailable()) {
            _voiceState.value = VoiceState.Error("Speech recognition service unavailable on this device.")
            return
        }
        try {
            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _voiceState.value = VoiceState.Listening()
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    val current = _voiceState.value
                    if (current is VoiceState.Listening) {
                        _voiceState.value = current.copy(rmsDb = rmsdB.coerceIn(0f, 12f))
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
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
                    _voiceState.value = VoiceState.Error(msg)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val transcript = matches?.firstOrNull()?.trim().orEmpty()
                    if (transcript.isNotEmpty()) {
                        _voiceState.value = VoiceState.Result(transcript)
                    } else {
                        _voiceState.value = VoiceState.Error("No transcript captured.")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull().orEmpty()
                    val current = _voiceState.value
                    if (current is VoiceState.Listening) {
                        _voiceState.value = current.copy(partialText = partial)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            _voiceState.value = VoiceState.Listening()
            recognizer.startListening(intent)
        } catch (e: Exception) {
            _voiceState.value = VoiceState.Error(e.localizedMessage ?: "Failed to start voice input.")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }
    }

    fun resetState() {
        _voiceState.value = VoiceState.Idle
    }

    fun destroy() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }
}
