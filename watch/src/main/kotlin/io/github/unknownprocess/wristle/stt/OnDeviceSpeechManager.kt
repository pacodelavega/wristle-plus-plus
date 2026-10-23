package io.github.unknownprocess.wristle.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

// @keep(pacodelavega): On-Device Speech Recognizer utilizing Galaxy Watch 7 / Wear OS Exynos NPU hardware

class OnDeviceSpeechManager(private val context: Context) {

    private val _textFlow = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 64)
    val textFlow: SharedFlow<SpeechEvent> = _textFlow.asSharedFlow()

    private val _audioLevel = MutableStateFlow(0.0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var currentAudioLevel = 0.0f
    var languageTag: String = "en-US"

    fun getAudioLevel(): Float = _audioLevel.value

    sealed class SpeechEvent {
        data class Partial(val text: String) : SpeechEvent()
        data class Final(val text: String) : SpeechEvent()
        data class Error(val errorMsg: String) : SpeechEvent()
    }

    fun initRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        val level = ((rmsdB + 2.0f) / 10.0f).coerceIn(0.0f, 1.0f)
                        currentAudioLevel = level
                        _audioLevel.value = level
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        currentAudioLevel = 0.0f
                        _audioLevel.value = 0.0f
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        currentAudioLevel = 0.0f
                        _audioLevel.value = 0.0f
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout"
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            else -> "Recognition error ($error)"
                        }
                        _textFlow.tryEmit(SpeechEvent.Error(msg))
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        currentAudioLevel = 0.0f
                        _audioLevel.value = 0.0f
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        _textFlow.tryEmit(SpeechEvent.Final(text))
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull() ?: ""
                        if (partial.isNotBlank()) {
                            _textFlow.tryEmit(SpeechEvent.Partial(partial))
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    fun startListening() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { startListening() }
            return
        }

        if (isListening) return

        if (speechRecognizer == null) {
            initRecognizer()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Force on-device offline recognition supported on Wear OS 5 / Galaxy Watch 7
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            val lang = if (languageTag == "system") Locale.getDefault().toLanguageTag() else languageTag
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            Log.e("OnDeviceSpeechManager", "Failed to start on-device speech recognition", e)
            _textFlow.tryEmit(SpeechEvent.Error("Speech recognizer unavailable"))
        }
    }

    fun stopListening() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { stopListening() }
            return
        }

        currentAudioLevel = 0.0f
        _audioLevel.value = 0.0f
        if (isListening) {
            try {
                speechRecognizer?.stopListening()
            } catch (ignored: Exception) {}
            isListening = false
        }
    }

    fun destroy() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { destroy() }
            return
        }

        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
