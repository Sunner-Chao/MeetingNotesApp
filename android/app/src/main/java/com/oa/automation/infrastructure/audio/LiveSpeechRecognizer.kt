package com.oa.automation.infrastructure.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Uses the platform speech recognizer for immediate on-device/live preview text.
 * Final transcript still comes from the app's configured STT backend.
 */
class LiveSpeechRecognizer(context: Context) {

    private val appContext = context.applicationContext
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var restartRequested = false
    private var onText: ((String, Boolean) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun start(
        onText: (String, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        this.onText = onText
        this.onError = onError
        restartRequested = true

        if (!isAvailable()) {
            onError("设备未提供系统实时语音识别服务")
            return
        }

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                setRecognitionListener(listener)
            }
        }

        startListeningInternal()
    }

    fun stop() {
        restartRequested = false
        isListening = false
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
    }

    fun release() {
        stop()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun startListeningInternal() {
        val recognizer = speechRecognizer ?: return
        if (isListening) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
        }

        isListening = true
        recognizer.startListening(intent)
    }

    private fun maybeRestart() {
        isListening = false
        if (restartRequested) {
            startListeningInternal()
        }
    }

    private fun Bundle.bestText(): String {
        return getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
            .trim()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            isListening = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_CLIENT -> maybeRestart()
                else -> {
                    onError?.invoke("系统实时识别暂不可用（错误码: $error）")
                    maybeRestart()
                }
            }
        }

        override fun onResults(results: Bundle) {
            val text = results.bestText()
            if (text.isNotBlank()) {
                onText?.invoke(text, true)
            }
            maybeRestart()
        }

        override fun onPartialResults(partialResults: Bundle) {
            val text = partialResults.bestText()
            if (text.isNotBlank()) {
                onText?.invoke(text, false)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
