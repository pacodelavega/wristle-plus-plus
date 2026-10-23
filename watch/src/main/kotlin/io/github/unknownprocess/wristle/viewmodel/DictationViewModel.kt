package io.github.unknownprocess.wristle.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.unknownprocess.wristle.config.WristleConfig
import io.github.unknownprocess.wristle.domain.DictationRepository
import io.github.unknownprocess.wristle.net.ConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// @keep(pacodelavega): Reactive StateFlow ViewModel coordinating UI states and audio metering

data class DictationUiState(
    val statusText: String = "Connecting...",
    val subText: String? = null,
    val isRecording: Boolean = false,
    val audioLevel: Float = 0.0f,
    val pairingCode: String? = null,
    val isLocked: Boolean = false,
    val isReady: Boolean = false,
    val isNoWifi: Boolean = false,
    val isRetryPaused: Boolean = false,
    val retrySecondsLeft: Int? = null,
    val pcName: String? = null,
    val offlineQueueCount: Int = 0,
    val autoRetryService: Boolean = true,
    val serviceTimeoutSec: Int = 10,
    val autoRetryWifi: Boolean = false,
    val wifiTimeoutSec: Int = 30,
    val recognizedText: String = "",
    val recognitionLanguage: String = "en-US"
)

class DictationViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DictationUiState())
    val uiState: StateFlow<DictationUiState> = _uiState.asStateFlow()

    private var repository: DictationRepository? = null
    private var repositoryJobs: Job? = null

    fun bindRepository(repo: DictationRepository) {
        repository = repo
        repositoryJobs?.cancel()
        repositoryJobs = viewModelScope.launch {
            launch {
                repo.connectionState.collect { connState ->
                    val qCount = repo.offlineQueueCount.value
                    when (connState) {
                        is ConnectionState.Idle -> {
                            _uiState.update { it.copy(statusText = "Idle", subText = null, isReady = false, isNoWifi = false, retrySecondsLeft = null, offlineQueueCount = qCount) }
                        }
                        is ConnectionState.NoWifi -> {
                            _uiState.update { it.copy(statusText = "No Wi-Fi", subText = null, isReady = false, isNoWifi = true, retrySecondsLeft = null, offlineQueueCount = qCount) }
                        }
                        is ConnectionState.Searching -> {
                            _uiState.update { it.copy(statusText = "Searching PC...", subText = "via Wi-Fi", isReady = false, isNoWifi = false, retrySecondsLeft = null, offlineQueueCount = qCount) }
                        }
                        is ConnectionState.Retrying -> {
                            _uiState.update { it.copy(statusText = "Searching PC...", subText = "Retry in ${connState.secondsLeft}s", isReady = false, isNoWifi = false, isRetryPaused = false, retrySecondsLeft = connState.secondsLeft, offlineQueueCount = qCount) }
                        }
                        is ConnectionState.Paused -> {
                            _uiState.update { it.copy(statusText = "Searching PC", subText = "Paused", isReady = false, isNoWifi = false, isRetryPaused = true, retrySecondsLeft = null, offlineQueueCount = qCount) }
                        }
                        is ConnectionState.Connecting -> {
                            _uiState.update { it.copy(statusText = "Connecting...", subText = null, isReady = false, isNoWifi = false, retrySecondsLeft = null, offlineQueueCount = qCount) }
                        }
                        is ConnectionState.Pairing -> {
                            _uiState.update { it.copy(statusText = "Approve on PC", subText = "Code: ${connState.code}", pairingCode = connState.code, isReady = false, isNoWifi = false, retrySecondsLeft = null, offlineQueueCount = qCount) }
                        }
                        is ConnectionState.Ready -> {
                            _uiState.update { it.copy(statusText = "Ready", subText = connState.pcName, isReady = true, isNoWifi = false, isRetryPaused = false, retrySecondsLeft = null, pcName = connState.pcName, pairingCode = null, offlineQueueCount = qCount) }
                        }
                        is ConnectionState.Listening -> {
                            _uiState.update { it.copy(statusText = "Listening...", subText = if (it.isLocked) "Tap to finish" else "Hold to talk", offlineQueueCount = qCount) }
                        }
                        is ConnectionState.Sending -> {
                            _uiState.update { it.copy(statusText = "Sending...", subText = null, offlineQueueCount = qCount) }
                        }
                        is ConnectionState.Sent -> {
                            _uiState.update { it.copy(statusText = "Sent ${WristleConfig.Icons.Check}", subText = null, offlineQueueCount = qCount) }
                        }
                        is ConnectionState.Error -> {
                            _uiState.update { it.copy(statusText = "Error", subText = connState.message, isReady = false, isNoWifi = false, retrySecondsLeft = null, offlineQueueCount = qCount) }
                        }
                    }
                }
            }
            launch {
                repo.audioLevel.collect { level ->
                    _uiState.update { it.copy(audioLevel = level) }
                }
            }
            launch {
                repo.offlineQueueCount.collect { count ->
                    _uiState.update { it.copy(offlineQueueCount = count) }
                }
            }
            launch {
                repo.isRecording.collect { recording ->
                    _uiState.update { it.copy(isRecording = recording) }
                }
            }
            launch {
                repo.autoRetryService.collect { enabled ->
                    _uiState.update { it.copy(autoRetryService = enabled) }
                }
            }
            launch {
                repo.serviceTimeoutSec.collect { sec ->
                    _uiState.update { it.copy(serviceTimeoutSec = sec) }
                }
            }
            launch {
                repo.autoRetryWifi.collect { enabled ->
                    _uiState.update { it.copy(autoRetryWifi = enabled) }
                }
            }
            launch {
                repo.wifiTimeoutSec.collect { sec ->
                    _uiState.update { it.copy(wifiTimeoutSec = sec) }
                }
            }
            launch {
                repo.isRetryPaused.collect { paused ->
                    _uiState.update { it.copy(isRetryPaused = paused) }
                }
            }
            launch {
                repo.recognizedText.collect { text ->
                    _uiState.update { it.copy(recognizedText = text) }
                }
            }
            launch {
                repo.recognitionLanguage.collect { lang ->
                    _uiState.update { it.copy(recognitionLanguage = lang) }
                }
            }
        }
    }

    fun refreshOfflineQueueCount() {
        repository?.refreshOfflineQueueCount()
    }

    fun startDictation() {
        val repo = repository ?: return
        if (repo.startDictation()) {
            _uiState.update { it.copy(isRecording = true, recognizedText = "") }
        }
    }

    fun finishDictation() {
        repository?.finishDictation()
        _uiState.update { it.copy(isRecording = false, isLocked = false, audioLevel = 0.0f) }
    }

    fun cancelDictation() {
        repository?.cancelDictation()
        _uiState.update { it.copy(isRecording = false, isLocked = false, audioLevel = 0.0f, recognizedText = "") }
    }

    fun lockRecording() {
        _uiState.update { it.copy(isLocked = true, subText = "Locked · Tap to finish") }
    }

    fun reconnect() {
        repository?.reconnect()
    }

    fun retryWifi() {
        repository?.reconnect()
    }

    fun toggleRetry() {
        repository?.toggleRetry()
    }

    fun pauseRetry() {
        repository?.pauseRetry()
    }

    fun resumeRetry() {
        repository?.resumeRetry()
    }

    fun setAutoRetryService(enabled: Boolean) {
        repository?.setAutoRetryService(enabled)
    }

    fun setServiceTimeoutSec(sec: Int) {
        repository?.setServiceTimeoutSec(sec)
    }

    fun setAutoRetryWifi(enabled: Boolean) {
        repository?.setAutoRetryWifi(enabled)
    }

    fun setWifiTimeoutSec(sec: Int) {
        repository?.setWifiTimeoutSec(sec)
    }

    fun setRecognitionLanguage(langTag: String) {
        repository?.setRecognitionLanguage(langTag)
    }

    override fun onCleared() {
        super.onCleared()
        repositoryJobs?.cancel()
        repository = null
    }
}
