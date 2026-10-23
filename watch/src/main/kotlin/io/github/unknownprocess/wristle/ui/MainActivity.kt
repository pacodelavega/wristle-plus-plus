package io.github.unknownprocess.wristle.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.SwipeToDismissValue
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material3.*
import io.github.unknownprocess.wristle.config.WristleConfig
import io.github.unknownprocess.wristle.service.DictationService
import io.github.unknownprocess.wristle.viewmodel.DictationUiState
import io.github.unknownprocess.wristle.viewmodel.DictationViewModel
import kotlinx.coroutines.launch

// @keep(pacodelavega): Jetpack Compose for Wear OS (Material 3) Main UI with dynamic audio feedback, settings carousel, and bottom arc indicator

open class MainActivity : ComponentActivity() {

    private val viewModel: DictationViewModel by viewModels()
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? DictationService.LocalBinder ?: return
            val repo = binder.getRepository()
            isBound = true
            viewModel.bindRepository(repo)
            if (isQuickMode()) {
                startQuickDictation()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            bindDictationService()
        }
    }

    private var activityStartTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityStartTime = System.currentTimeMillis()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            bindDictationService()
        }

        setContent {
            MaterialTheme {
                val state by viewModel.uiState.collectAsState()
                val pagerState = rememberPagerState(pageCount = { 2 })
                val swipeDismissState = rememberSwipeToDismissBoxState()
                val coroutineScope = rememberCoroutineScope()

                LaunchedEffect(swipeDismissState.currentValue) {
                    if (swipeDismissState.currentValue == SwipeToDismissValue.Dismissed) {
                        finish()
                    }
                }

                SwipeToDismissBox(
                    state = swipeDismissState,
                    userSwipeEnabled = true
                ) { isBackground ->
                    if (isBackground) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(WristleConfig.Colors.Background)
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            HorizontalPager(
                                state = pagerState,
                                userScrollEnabled = (pagerState.currentPage == 0),
                                modifier = Modifier.fillMaxSize()
                            ) { page ->
                                when (page) {
                                    0 -> DictationScreen(
                                        state = state,
                                        onPressDown = {
                                            vibrate(WristleConfig.Haptics.PressDownDurationMs)
                                            viewModel.startDictation()
                                        },
                                        onPressUp = { durationMs ->
                                            if (state.isRecording) {
                                                if (durationMs < WristleConfig.Timing.LockModeThresholdMs) {
                                                    // Tap: lock mode
                                                    vibrate(WristleConfig.Haptics.PressUpDurationMs)
                                                    viewModel.lockRecording()
                                                } else {
                                                    // Release: finish
                                                    vibrate(WristleConfig.Haptics.PressUpDurationMs)
                                                    viewModel.finishDictation()
                                                    if (isQuickMode()) finishAfterDelay(WristleConfig.Timing.QuickModeFinishDelayMs)
                                                }
                                            }
                                        },
                                        onTapWhileRecording = {
                                            vibrate(WristleConfig.Haptics.PressUpDurationMs)
                                            viewModel.finishDictation()
                                            if (isQuickMode()) finishAfterDelay(WristleConfig.Timing.QuickModeFinishDelayMs)
                                        },
                                        onCancel = {
                                            vibrate(WristleConfig.Haptics.CancelDurationMs)
                                            viewModel.cancelDictation()
                                            if (isQuickMode()) finishAfterDelay(WristleConfig.Timing.QuickModeCancelDelayMs)
                                        },
                                        onRetryWifi = {
                                            vibrate(WristleConfig.Haptics.PressDownDurationMs)
                                            viewModel.retryWifi()
                                        },
                                        onToggleRetry = {
                                            vibrate(WristleConfig.Haptics.PressDownDurationMs)
                                            viewModel.toggleRetry()
                                        }
                                    )
                                    1 -> SettingsScreen(
                                        state = state,
                                        onReconnect = { viewModel.reconnect() },
                                        onAutoRetryServiceChange = { viewModel.setAutoRetryService(it) },
                                        onServiceTimeoutChange = { viewModel.setServiceTimeoutSec(it) },
                                        onAutoRetryWifiChange = { viewModel.setAutoRetryWifi(it) },
                                        onWifiTimeoutChange = { viewModel.setWifiTimeoutSec(it) },
                                        onRecognitionLanguageChange = { viewModel.setRecognitionLanguage(it) },
                                        onNavigateToMain = {
                                            coroutineScope.launch { pagerState.animateScrollToPage(0) }
                                        }
                                    )
                                }
                            }

                            // Curved bottom arc page indicator along circular bezel
                            CurvedBottomPageIndicator(
                                pageCount = 2,
                                currentPage = pagerState.currentPage,
                                activeColor = WristleConfig.Colors.ArcIndicatorActive,
                                inactiveColor = WristleConfig.Colors.ArcIndicatorInactive,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }

    open fun isQuickMode(): Boolean = false

    private fun startQuickDictation() {
        vibrate(WristleConfig.Haptics.QuickLaunchDurationMs)
        viewModel.startDictation()
        viewModel.lockRecording()
    }

    // Hardware Button (Walkie-Talkie Push-To-Talk) Support
    private var isHardwareKeyPressed = false
    private var keyPressStartTime = 0L

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0 && isPttKey(keyCode)) {
            val state = viewModel.uiState.value
            if (!state.isRecording) {
                isHardwareKeyPressed = true
                keyPressStartTime = System.currentTimeMillis()
                vibrate(WristleConfig.Haptics.PressDownDurationMs)
                viewModel.startDictation()
                return true
            } else if (state.isLocked) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isHardwareKeyPressed && isPttKey(keyCode)) {
            isHardwareKeyPressed = false
            val duration = System.currentTimeMillis() - keyPressStartTime
            vibrate(WristleConfig.Haptics.PressUpDurationMs)
            if (duration < WristleConfig.Timing.LockModeThresholdMs) {
                // Short press: lock recording mode (keep talking without holding)
                viewModel.lockRecording()
            } else {
                // Held & released: Walkie-Talkie PTT finish
                viewModel.finishDictation()
                if (isQuickMode()) finishAfterDelay(WristleConfig.Timing.QuickModeFinishDelayMs)
            }
            return true
        } else if (viewModel.uiState.value.isRecording && isPttKey(keyCode)) {
            val sinceLaunch = System.currentTimeMillis() - activityStartTime
            if (isQuickMode() && sinceLaunch < WristleConfig.Timing.QuickModeLaunchThresholdMs) {
                // Short tap launched the app: keep recording in lock mode
                viewModel.lockRecording()
            } else {
                // Held button while opening and talking: Walkie-Talkie release to send!
                vibrate(WristleConfig.Haptics.PressUpDurationMs)
                viewModel.finishDictation()
                if (isQuickMode()) finishAfterDelay(WristleConfig.Timing.QuickModeFinishDelayMs)
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isPttKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_STEM_1 ||
               keyCode == KeyEvent.KEYCODE_STEM_2 ||
               keyCode == KeyEvent.KEYCODE_STEM_3 ||
               keyCode == KeyEvent.KEYCODE_STEM_PRIMARY ||
               keyCode == KeyEvent.KEYCODE_BUTTON_1 ||
               keyCode == KeyEvent.KEYCODE_BUTTON_2 ||
               keyCode == KeyEvent.KEYCODE_BUTTON_3 ||
               keyCode == KeyEvent.KEYCODE_FUNCTION ||
               keyCode == KeyEvent.KEYCODE_MEDIA_RECORD ||
               keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
               keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
               (isQuickMode() && keyCode == KeyEvent.KEYCODE_BACK)
    }

    private fun finishAfterDelay(ms: Long) {
        window.decorView.postDelayed({ finish() }, ms)
    }

    private fun bindDictationService() {
        val intent = Intent(this, DictationService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun vibrate(durationMs: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(durationMs)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}

@Composable
fun DictationScreen(
    state: DictationUiState,
    onPressDown: () -> Unit,
    onPressUp: (durationMs: Long) -> Unit,
    onTapWhileRecording: () -> Unit,
    onCancel: () -> Unit,
    onRetryWifi: () -> Unit = {},
    onToggleRetry: () -> Unit = {}
) {
    val buttonColor by animateColorAsState(
        targetValue = when {
            state.isRecording -> WristleConfig.Colors.ButtonRecording
            state.isReady -> WristleConfig.Colors.ButtonReady
            else -> WristleConfig.Colors.ButtonNeutral
        },
        label = "buttonColor"
    )

    val scale by animateFloatAsState(
        targetValue = if (state.isRecording) 1.0f + (state.audioLevel * 0.20f) else 1.0f,
        label = "scale"
    )

    val currentIsRecording by rememberUpdatedState(state.isRecording)
    val currentIsLocked by rememberUpdatedState(state.isLocked)
    val currentOnPressDown by rememberUpdatedState(onPressDown)
    val currentOnPressUp by rememberUpdatedState(onPressUp)
    val currentOnTapWhileRecording by rememberUpdatedState(onTapWhileRecording)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WristleConfig.Colors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = WristleConfig.Dimensions.ScreenHorizontalPadding, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Status Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 10.dp)
            ) {
                Text(
                    text = state.statusText,
                    fontSize = WristleConfig.Typography.TitleFontSize,
                    fontWeight = FontWeight.Bold,
                    color = WristleConfig.Colors.TextPrimary,
                    textAlign = TextAlign.Center
                )
                if (state.subText != null) {
                    Text(
                        text = state.subText,
                        fontSize = WristleConfig.Typography.BodyFontSize,
                        color = WristleConfig.Colors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Big Center Recording Button (88.dp prevents clipping on 40mm round screen)
            Box(
                modifier = Modifier
                    .size(WristleConfig.Dimensions.CenterButtonSize)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(buttonColor)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                if (currentIsRecording && currentIsLocked) {
                                    currentOnTapWhileRecording()
                                } else if (!currentIsRecording) {
                                    val startTime = System.currentTimeMillis()
                                    currentOnPressDown()
                                    val released = tryAwaitRelease()
                                    val duration = System.currentTimeMillis() - startTime
                                    if (released) {
                                        currentOnPressUp(duration)
                                    } else {
                                        currentOnPressUp(duration)
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.isRecording) WristleConfig.Icons.RecordingDot else WristleConfig.Icons.Mic,
                    fontSize = WristleConfig.Typography.MicGlyphSize,
                    color = WristleConfig.Colors.TextPrimary
                )
            }

            // Bottom Contextual Slot between Center Button and Curved Arc Indicator
            val textScrollState = rememberScrollState()
            val textFocusRequester = remember { FocusRequester() }

            // Reset scroll position on new recording
            LaunchedEffect(state.isRecording) {
                if (state.isRecording) {
                    textScrollState.scrollTo(0)
                }
            }

            if (state.recognizedText.isNotBlank()) {
                LaunchedEffect(state.recognizedText) {
                    textFocusRequester.requestFocus()
                }
                // Display recognized live or final text without overlapping button or arc
                Box(
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .fillMaxWidth(0.92f)
                        .heightIn(min = 32.dp, max = 52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E2028))
                        .focusRequester(textFocusRequester)
                        .focusable()
                        .onRotaryScrollEvent { event ->
                            textScrollState.dispatchRawDelta(event.verticalScrollPixels)
                            true
                        }
                        .verticalScroll(textScrollState)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(
                        text = state.recognizedText,
                        fontSize = 11.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        lineHeight = 13.sp
                    )
                }
            } else if (state.isRecording) {
                // Cancel Button: compact 36.dp circular button with Unicode '✕'
                Box(
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .size(WristleConfig.Dimensions.CancelButtonSize)
                        .clip(CircleShape)
                        .background(WristleConfig.Colors.ChipInactive)
                        .clickable { onCancel() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = WristleConfig.Icons.Cancel,
                        fontSize = WristleConfig.Typography.CancelGlyphSize,
                        fontWeight = FontWeight.Bold,
                        color = WristleConfig.Colors.CancelRed
                    )
                }
            } else if (state.isNoWifi) {
                // Retry Wi-Fi button: compact pill, minimal letters, prominent Material Icon
                Box(
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .height(WristleConfig.Dimensions.WifiRetryButtonHeight)
                        .clip(RoundedCornerShape(WristleConfig.Dimensions.ActionButtonCornerRadius))
                        .background(WristleConfig.Colors.ButtonActionBlue)
                        .clickable { onRetryWifi() }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        WifiRetryIcon(
                            modifier = Modifier.size(18.dp),
                            tint = WristleConfig.Colors.TextPrimary
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Wi-Fi",
                            fontSize = WristleConfig.Typography.ActionButtonFontSize,
                            fontWeight = FontWeight.Bold,
                            color = WristleConfig.Colors.TextPrimary
                        )
                    }
                }
            } else if (!state.isReady) {
                if (state.isRetryPaused) {
                    // Resume retrying button: prominent MD3 Play icon + short English label
                    Box(
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .height(WristleConfig.Dimensions.WifiRetryButtonHeight)
                            .clip(RoundedCornerShape(WristleConfig.Dimensions.ActionButtonCornerRadius))
                            .background(WristleConfig.Colors.ButtonActionBlue)
                            .clickable { onToggleRetry() }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Md3PlayIcon(
                                modifier = Modifier.size(14.dp),
                                tint = WristleConfig.Colors.TextPrimary
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Search",
                                fontSize = WristleConfig.Typography.ActionButtonFontSize,
                                fontWeight = FontWeight.Bold,
                                color = WristleConfig.Colors.TextPrimary
                            )
                        }
                    }
                } else {
                    // Pause retrying button: prominent MD3 Pause icon + seconds countdown
                    val label = if (state.retrySecondsLeft != null) "${state.retrySecondsLeft}s" else "Pause"
                    Box(
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .height(WristleConfig.Dimensions.WifiRetryButtonHeight)
                            .clip(RoundedCornerShape(WristleConfig.Dimensions.ActionButtonCornerRadius))
                            .background(WristleConfig.Colors.ChipInactive)
                            .clickable { onToggleRetry() }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Md3PauseIcon(
                                modifier = Modifier.size(14.dp),
                                tint = WristleConfig.Colors.TextPrimary
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = label,
                                fontSize = WristleConfig.Typography.ActionButtonFontSize,
                                fontWeight = FontWeight.Bold,
                                color = WristleConfig.Colors.TextPrimary
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(WristleConfig.Dimensions.CancelButtonSize + 6.dp))
            }
        }
    }
}

@Composable
fun WifiRetryIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val stroke = 2.dp.toPx()
        val w = size.width
        val h = size.height

        // Wi-Fi dot
        drawCircle(
            color = tint,
            radius = w * 0.08f,
            center = Offset(w * 0.38f, h * 0.82f)
        )

        // Wi-Fi inner arc
        drawArc(
            color = tint,
            startAngle = 225f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w * 0.16f, h * 0.52f),
            size = Size(w * 0.44f, h * 0.44f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        // Wi-Fi outer arc
        drawArc(
            color = tint,
            startAngle = 225f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w * -0.02f, h * 0.28f),
            size = Size(w * 0.80f, h * 0.80f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        // Curved refresh arrow on top-right
        val arcCenter = Offset(w * 0.74f, h * 0.36f)
        val arcRadius = w * 0.22f
        drawArc(
            color = tint,
            startAngle = 60f,
            sweepAngle = 260f,
            useCenter = false,
            topLeft = Offset(arcCenter.x - arcRadius, arcCenter.y - arcRadius),
            size = Size(arcRadius * 2, arcRadius * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        // Arrow head for refresh
        val arrowHead = Path().apply {
            moveTo(w * 0.86f, h * 0.20f)
            lineTo(w * 0.98f, h * 0.34f)
            lineTo(w * 0.76f, h * 0.36f)
            close()
        }
        drawPath(arrowHead, color = tint)
    }
}

@Composable
fun Md3PauseIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val barW = w * 0.28f
        val barH = h * 0.70f
        val corner = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        val top = (h - barH) / 2f

        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.14f, top),
            size = Size(barW, barH),
            cornerRadius = corner
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.58f, top),
            size = Size(barW, barH),
            cornerRadius = corner
        )
    }
}

@Composable
fun Md3PlayIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.15f)
            lineTo(w * 0.86f, h * 0.50f)
            lineTo(w * 0.22f, h * 0.85f)
            close()
        }
        drawPath(path, color = tint)
    }
}
