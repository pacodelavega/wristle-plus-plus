package io.github.unknownprocess.wristle.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.unknownprocess.wristle.config.WristleConfig
import io.github.unknownprocess.wristle.domain.DictationRepository
import io.github.unknownprocess.wristle.net.ConnectionState
import io.github.unknownprocess.wristle.net.WristleClient
import io.github.unknownprocess.wristle.stt.OnDeviceSpeechManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// @keep(pacodelavega): Foreground Service with PARTIAL_WAKE_LOCK and WIFI_MODE_FULL_HIGH_PERF for uninterrupted recording

class DictationService : Service(), DictationRepository {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private lateinit var client: WristleClient
    private lateinit var speechManager: OnDeviceSpeechManager
    private var speechJob: Job? = null
    private var finishJob: Job? = null
    private var finalDeferred = CompletableDeferred<String?>()

    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    override val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _recognitionLanguage = MutableStateFlow(WristleConfig.Preferences.DefaultLanguage)
    override val recognitionLanguage: StateFlow<String> = _recognitionLanguage.asStateFlow()

    override val connectionState: StateFlow<ConnectionState>
        get() = client.state

    override val audioLevel: StateFlow<Float>
        get() = speechManager.audioLevel

    override val offlineQueueCount: StateFlow<Int>
        get() = client.offlineQueueCount

    override val autoRetryService: StateFlow<Boolean>
        get() = client.autoRetryService

    override val serviceTimeoutSec: StateFlow<Int>
        get() = client.serviceTimeoutSec

    override val autoRetryWifi: StateFlow<Boolean>
        get() = client.autoRetryWifi

    override val wifiTimeoutSec: StateFlow<Int>
        get() = client.wifiTimeoutSec

    override val isRetryPaused: StateFlow<Boolean>
        get() = client.isRetryPaused

    private var latestRecognizedText = ""
    private var textSent = false

    inner class LocalBinder : Binder() {
        fun getService(): DictationService = this@DictationService
        fun getRepository(): DictationRepository = this@DictationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "wear_watch"
        client = WristleClient(applicationContext, deviceId)
        speechManager = OnDeviceSpeechManager(applicationContext)
        val savedLang = getSharedPreferences(WristleConfig.Preferences.PrefsName, Context.MODE_PRIVATE)
            .getString(WristleConfig.Preferences.KeyRecognitionLanguage, WristleConfig.Preferences.DefaultLanguage)
            ?: WristleConfig.Preferences.DefaultLanguage
        _recognitionLanguage.value = savedLang
        speechManager.languageTag = savedLang
        speechManager.initRecognizer()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Wristle::AudioWakeLock")

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Wristle::WifiLock")

        client.connect()
    }

    override fun reconnect() {
        client.disconnect()
        client.connect()
    }

    override fun refreshOfflineQueueCount() {
        client.refreshOfflineQueueCount()
    }

    override fun setAutoRetryService(enabled: Boolean) = client.setAutoRetryService(enabled)
    override fun setServiceTimeoutSec(sec: Int) = client.setServiceTimeoutSec(sec)
    override fun setAutoRetryWifi(enabled: Boolean) = client.setAutoRetryWifi(enabled)
    override fun setWifiTimeoutSec(sec: Int) = client.setWifiTimeoutSec(sec)
    override fun toggleRetry() = client.toggleRetry()
    override fun pauseRetry() = client.pauseRetry()
    override fun resumeRetry() = client.resumeRetry()

    override fun setRecognitionLanguage(langTag: String) {
        _recognitionLanguage.value = langTag
        speechManager.languageTag = langTag
        getSharedPreferences(WristleConfig.Preferences.PrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(WristleConfig.Preferences.KeyRecognitionLanguage, langTag)
            .apply()
    }

    override fun startDictation(): Boolean {
        if (_isRecording.value) return true

        finishJob?.cancel()
        finishJob = null

        acquireLocks()
        _isRecording.value = true
        _recognizedText.value = ""
        latestRecognizedText = ""
        textSent = false
        finalDeferred = CompletableDeferred()

        startForegroundServiceNotification("Listening...", "On-Device Dictation")
        client.startDictation()

        speechJob?.cancel()
        speechJob = scope.launch {
            speechManager.textFlow.collect { event ->
                when (event) {
                    is OnDeviceSpeechManager.SpeechEvent.Partial -> {
                        latestRecognizedText = event.text
                        _recognizedText.value = event.text
                    }
                    is OnDeviceSpeechManager.SpeechEvent.Final -> {
                        latestRecognizedText = event.text
                        _recognizedText.value = event.text
                        if (!finalDeferred.isCompleted) {
                            finalDeferred.complete(event.text)
                        }
                    }
                    is OnDeviceSpeechManager.SpeechEvent.Error -> {
                        Log.w("DictationService", "Speech recognizer notice: ${event.errorMsg}")
                        if (!finalDeferred.isCompleted) {
                            finalDeferred.complete(null)
                        }
                    }
                }
            }
        }
        speechManager.startListening()
        return true
    }

    override fun finishDictation() {
        if (!_isRecording.value) return
        _isRecording.value = false

        speechManager.stopListening()

        finishJob?.cancel()
        finishJob = scope.launch {
            try {
                val awaitedText = withTimeoutOrNull(WristleConfig.Timing.FinalTextWaitTimeoutMs) {
                    finalDeferred.await()
                }
                val textToSend = if (!awaitedText.isNullOrBlank()) {
                    awaitedText
                } else if (latestRecognizedText.isNotBlank()) {
                    latestRecognizedText
                } else {
                    null
                }

                if (!textSent && !textToSend.isNullOrBlank()) {
                    textSent = true
                    client.sendText(textToSend, isPartial = false)
                } else {
                    client.finishDictation()
                }
            } catch (e: Exception) {
                Log.e("DictationService", "Error completing dictation", e)
                client.finishDictation()
            } finally {
                speechJob?.cancel()
                speechJob = null
                if (!_isRecording.value) {
                    releaseLocks()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
    }

    override fun cancelDictation() {
        if (!_isRecording.value) return
        _isRecording.value = false
        finishJob?.cancel()
        finishJob = null
        speechJob?.cancel()
        speechJob = null
        latestRecognizedText = ""
        _recognizedText.value = ""
        textSent = true // prevent sending on cancel
        if (!finalDeferred.isCompleted) {
            finalDeferred.complete(null)
        }

        speechManager.stopListening()
        client.cancelDictation()

        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun acquireLocks() {
        wakeLock?.let {
            if (!it.isHeld) it.acquire(WristleConfig.Timing.MaxWakeLockDurationMs)
        }
        wifiLock?.let {
            if (!it.isHeld) it.acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wifiLock?.let {
            if (it.isHeld) it.release()
        }
    }

    private fun startForegroundServiceNotification(
        title: String = "Listening...",
        text: String = "On-Device Dictation (Burst Mode)"
    ) {
        val notification = createNotification(title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(title: String, text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wristle Dictation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active dictation recording state"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        finishJob?.cancel()
        speechJob?.cancel()
        speechManager.destroy()
        client.disconnect()
        scope.cancel()
    }

    companion object {
        private const val CHANNEL_ID = "wristle_recording_channel"
        private const val NOTIFICATION_ID = 47820
    }
}
