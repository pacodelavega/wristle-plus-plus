package io.github.unknownprocess.wristle

import io.github.unknownprocess.wristle.config.WristleConfig
import io.github.unknownprocess.wristle.domain.DictationRepository
import io.github.unknownprocess.wristle.net.ConnectionState
import io.github.unknownprocess.wristle.viewmodel.DictationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeDictationRepository : DictationRepository {
    val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val _audioLevel = MutableStateFlow(0.0f)
    override val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    val _offlineQueueCount = MutableStateFlow(0)
    override val offlineQueueCount: StateFlow<Int> = _offlineQueueCount.asStateFlow()

    val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    val _autoRetryService = MutableStateFlow(true)
    override val autoRetryService: StateFlow<Boolean> = _autoRetryService.asStateFlow()

    val _serviceTimeoutSec = MutableStateFlow(10)
    override val serviceTimeoutSec: StateFlow<Int> = _serviceTimeoutSec.asStateFlow()

    val _autoRetryWifi = MutableStateFlow(false)
    override val autoRetryWifi: StateFlow<Boolean> = _autoRetryWifi.asStateFlow()

    val _wifiTimeoutSec = MutableStateFlow(30)
    override val wifiTimeoutSec: StateFlow<Int> = _wifiTimeoutSec.asStateFlow()

    val _isRetryPaused = MutableStateFlow(false)
    override val isRetryPaused: StateFlow<Boolean> = _isRetryPaused.asStateFlow()

    val _recognizedText = MutableStateFlow("")
    override val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    val _recognitionLanguage = MutableStateFlow("en-US")
    override val recognitionLanguage: StateFlow<String> = _recognitionLanguage.asStateFlow()

    var startCalled = false
    var finishCalled = false
    var cancelCalled = false
    var reconnectCalled = false
    var refreshCalled = false
    var toggleRetryCalled = false
    var pauseRetryCalled = false
    var resumeRetryCalled = false

    override fun startDictation(): Boolean {
        startCalled = true
        _isRecording.value = true
        return true
    }

    override fun finishDictation() {
        finishCalled = true
        _isRecording.value = false
    }

    override fun cancelDictation() {
        cancelCalled = true
        _isRecording.value = false
    }

    override fun reconnect() {
        reconnectCalled = true
    }

    override fun refreshOfflineQueueCount() {
        refreshCalled = true
    }

    override fun toggleRetry() {
        toggleRetryCalled = true
        _isRetryPaused.value = !_isRetryPaused.value
    }

    override fun pauseRetry() {
        pauseRetryCalled = true
        _isRetryPaused.value = true
    }

    override fun resumeRetry() {
        resumeRetryCalled = true
        _isRetryPaused.value = false
    }

    override fun setAutoRetryService(enabled: Boolean) {
        _autoRetryService.value = enabled
    }

    override fun setServiceTimeoutSec(sec: Int) {
        _serviceTimeoutSec.value = sec
    }

    override fun setAutoRetryWifi(enabled: Boolean) {
        _autoRetryWifi.value = enabled
    }

    override fun setWifiTimeoutSec(sec: Int) {
        _wifiTimeoutSec.value = sec
    }

    override fun setRecognitionLanguage(langTag: String) {
        _recognitionLanguage.value = langTag
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DictationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepo: FakeDictationRepository
    private lateinit var viewModel: DictationViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeDictationRepository()
        viewModel = DictationViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialUiState() {
        val state = viewModel.uiState.value
        assertEquals("Connecting...", state.statusText)
        assertFalse(state.isRecording)
        assertEquals(0.0f, state.audioLevel, 0.001f)
    }

    @Test
    fun testBindRepositoryUpdatesConnectionState() = runTest {
        viewModel.bindRepository(fakeRepo)
        advanceUntilIdle()

        fakeRepo._connectionState.value = ConnectionState.Ready("DesktopPC")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Ready", state.statusText)
        assertEquals("DesktopPC", state.pcName)
        assertTrue(state.isReady)
    }

    @Test
    fun testAudioLevelUpdatesReactivelyWithoutPolling() = runTest {
        viewModel.bindRepository(fakeRepo)
        advanceUntilIdle()

        fakeRepo._audioLevel.value = 0.75f
        advanceUntilIdle()

        assertEquals(0.75f, viewModel.uiState.value.audioLevel, 0.001f)
    }

    @Test
    fun testOfflineQueueCountUpdatesReactively() = runTest {
        viewModel.bindRepository(fakeRepo)
        advanceUntilIdle()

        fakeRepo._offlineQueueCount.value = 4
        advanceUntilIdle()

        assertEquals(4, viewModel.uiState.value.offlineQueueCount)
    }

    @Test
    fun testStartAndFinishDictation() = runTest {
        viewModel.bindRepository(fakeRepo)
        advanceUntilIdle()

        viewModel.startDictation()
        assertTrue(fakeRepo.startCalled)

        viewModel.finishDictation()
        assertTrue(fakeRepo.finishCalled)
        assertFalse(viewModel.uiState.value.isRecording)
    }

    @Test
    fun testCancelDictation() = runTest {
        viewModel.bindRepository(fakeRepo)
        advanceUntilIdle()

        viewModel.startDictation()
        viewModel.cancelDictation()
        assertTrue(fakeRepo.cancelCalled)
        assertFalse(viewModel.uiState.value.isRecording)
        assertEquals(0.0f, viewModel.uiState.value.audioLevel, 0.001f)
    }

    @Test
    fun testLockRecording() = runTest {
        viewModel.bindRepository(fakeRepo)
        advanceUntilIdle()

        viewModel.startDictation()
        viewModel.lockRecording()
        assertTrue(viewModel.uiState.value.isLocked)
        assertTrue(viewModel.uiState.value.subText?.contains("Locked") == true)
    }

    @Test
    fun testReconnectAndRefreshOfflineQueue() = runTest {
        viewModel.bindRepository(fakeRepo)
        advanceUntilIdle()

        viewModel.reconnect()
        assertTrue(fakeRepo.reconnectCalled)

        viewModel.refreshOfflineQueueCount()
        assertTrue(fakeRepo.refreshCalled)
    }

    @Test
    fun testAllConnectionStatesMapping() = runTest {
        viewModel.bindRepository(fakeRepo)
        advanceUntilIdle()

        fakeRepo._connectionState.value = ConnectionState.Searching
        advanceUntilIdle()
        assertEquals("Searching PC...", viewModel.uiState.value.statusText)
        assertFalse(viewModel.uiState.value.isNoWifi)

        fakeRepo._connectionState.value = ConnectionState.NoWifi
        advanceUntilIdle()
        assertEquals("No Wi-Fi", viewModel.uiState.value.statusText)
        assertTrue(viewModel.uiState.value.isNoWifi)

        fakeRepo._connectionState.value = ConnectionState.Retrying(8)
        advanceUntilIdle()
        assertEquals("Searching PC...", viewModel.uiState.value.statusText)
        assertEquals("Retry in 8s", viewModel.uiState.value.subText)
        assertEquals(8, viewModel.uiState.value.retrySecondsLeft)
        assertFalse(viewModel.uiState.value.isNoWifi)

        fakeRepo._connectionState.value = ConnectionState.Connecting
        advanceUntilIdle()
        assertEquals("Connecting...", viewModel.uiState.value.statusText)

        fakeRepo._connectionState.value = ConnectionState.Pairing("4321")
        advanceUntilIdle()
        assertEquals("Approve on PC", viewModel.uiState.value.statusText)
        assertEquals("4321", viewModel.uiState.value.pairingCode)

        fakeRepo._connectionState.value = ConnectionState.Listening("OfficePC")
        advanceUntilIdle()
        assertEquals("Listening...", viewModel.uiState.value.statusText)

        fakeRepo._connectionState.value = ConnectionState.Sending
        advanceUntilIdle()
        assertEquals("Sending...", viewModel.uiState.value.statusText)

        fakeRepo._connectionState.value = ConnectionState.Sent
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.statusText.contains("Sent"))

        fakeRepo._connectionState.value = ConnectionState.Paused
        advanceUntilIdle()
        assertEquals("Searching PC", viewModel.uiState.value.statusText)
        assertEquals("Paused", viewModel.uiState.value.subText)
        assertTrue(viewModel.uiState.value.isRetryPaused)

        fakeRepo._connectionState.value = ConnectionState.Error("Network unreachable")
        advanceUntilIdle()
        assertEquals("Error", viewModel.uiState.value.statusText)
        assertEquals("Network unreachable", viewModel.uiState.value.subText)
    }

    @Test
    fun testSettingsAndRetryControls() = runTest {
        viewModel.bindRepository(fakeRepo)
        advanceUntilIdle()

        viewModel.setAutoRetryService(false)
        assertFalse(fakeRepo._autoRetryService.value)

        viewModel.setServiceTimeoutSec(15)
        assertEquals(15, fakeRepo._serviceTimeoutSec.value)

        viewModel.setAutoRetryWifi(true)
        assertTrue(fakeRepo._autoRetryWifi.value)

        viewModel.setWifiTimeoutSec(45)
        assertEquals(45, fakeRepo._wifiTimeoutSec.value)

        viewModel.toggleRetry()
        assertTrue(fakeRepo.toggleRetryCalled)

        viewModel.pauseRetry()
        assertTrue(fakeRepo.pauseRetryCalled)

        viewModel.resumeRetry()
        assertTrue(fakeRepo.resumeRetryCalled)

        viewModel.setRecognitionLanguage("system")
        assertEquals("system", fakeRepo._recognitionLanguage.value)
    }

    @Test
    fun testRecognizedTextUpdatesAndResetsOnRecording() = runTest {
        viewModel.bindRepository(fakeRepo)
        advanceUntilIdle()

        fakeRepo._recognizedText.value = "Hello world"
        advanceUntilIdle()
        assertEquals("Hello world", viewModel.uiState.value.recognizedText)

        viewModel.startDictation()
        assertEquals("", viewModel.uiState.value.recognizedText)
    }

    @Test
    fun testWristleConfigConstants() {
        assertEquals(88, WristleConfig.Dimensions.CenterButtonSize.value.toInt())
        assertEquals(36, WristleConfig.Dimensions.CancelButtonSize.value.toInt())
        assertEquals(800L, WristleConfig.Timing.FinalTextWaitTimeoutMs)
        assertEquals("🎤", WristleConfig.Icons.Mic)
        assertEquals("●", WristleConfig.Icons.RecordingDot)
        assertEquals("✕", WristleConfig.Icons.Cancel)
        assertEquals("⚙", WristleConfig.Icons.Settings)
        assertEquals("🔄", WristleConfig.Icons.Reconnect)
    }
}
