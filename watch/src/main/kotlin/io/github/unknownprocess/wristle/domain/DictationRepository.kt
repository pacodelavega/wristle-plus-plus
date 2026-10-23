package io.github.unknownprocess.wristle.domain

import io.github.unknownprocess.wristle.net.ConnectionState
import kotlinx.coroutines.flow.StateFlow

// @keep(pacodelavega): Clean Architecture domain layer repository interface decoupling UI and ViewModel from Service internals

interface DictationRepository {
    /**
     * Observable PC connection state (Idle, Searching, Connecting, Pairing, Ready, Listening, Sending, Sent, Error).
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Real-time audio RMS level (0.0f..1.0f) for dynamic mic button pulsing without polling loops.
     */
    val audioLevel: StateFlow<Float>

    /**
     * Number of dictations currently buffered offline pending delivery to PC.
     */
    val offlineQueueCount: StateFlow<Int>

    /**
     * Whether dictation is currently recording audio / speech recognition.
     */
    val isRecording: StateFlow<Boolean>

    /**
     * Live and final recognized text to display on watch screen.
     */
    val recognizedText: StateFlow<String>

    val recognitionLanguage: StateFlow<String>

    val autoRetryService: StateFlow<Boolean>
    val serviceTimeoutSec: StateFlow<Int>
    val autoRetryWifi: StateFlow<Boolean>
    val wifiTimeoutSec: StateFlow<Int>
    val isRetryPaused: StateFlow<Boolean>

    /**
     * Starts on-device speech recognition in Burst Mode.
     */
    fun startDictation(): Boolean

    /**
     * Finishes speech recognition and delivers the completed phrase to PC as text.
     */
    fun finishDictation()

    /**
     * Cancels current dictation without sending text.
     */
    fun cancelDictation()

    /**
     * Initiates Wi-Fi rediscovery and reconnection to PC server.
     */
    fun reconnect()

    /**
     * Refreshes offline queue counter from storage.
     */
    fun refreshOfflineQueueCount()

    fun setAutoRetryService(enabled: Boolean)
    fun setServiceTimeoutSec(sec: Int)
    fun setAutoRetryWifi(enabled: Boolean)
    fun setWifiTimeoutSec(sec: Int)
    fun toggleRetry()
    fun pauseRetry()
    fun resumeRetry()
    fun setRecognitionLanguage(langTag: String)
}
