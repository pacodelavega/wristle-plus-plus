package io.github.unknownprocess.wristle.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Text
import io.github.unknownprocess.wristle.config.WristleConfig
import io.github.unknownprocess.wristle.viewmodel.DictationUiState

// @keep(pacodelavega): Minimalist, uniform Wear OS Settings Screen with English localization and language switcher

@Composable
fun SettingsScreen(
    state: DictationUiState,
    onReconnect: () -> Unit,
    onAutoRetryServiceChange: (Boolean) -> Unit,
    onServiceTimeoutChange: (Int) -> Unit,
    onAutoRetryWifiChange: (Boolean) -> Unit,
    onWifiTimeoutChange: (Int) -> Unit,
    onRecognitionLanguageChange: (String) -> Unit,
    onNavigateToMain: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    var dragAccumulatorX by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WristleConfig.Colors.Background)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragAccumulatorX = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        // Only consume leftward swipes (return to main screen)
                        if (dragAmount < 0f || dragAccumulatorX < -8f) {
                            change.consume()
                            dragAccumulatorX += dragAmount
                        }
                    },
                    onDragEnd = {
                        if (dragAccumulatorX < -32.dp.toPx()) {
                            vibrate(context, WristleConfig.Haptics.PressUpDurationMs)
                            onNavigateToMain()
                        }
                        dragAccumulatorX = 0f
                    },
                    onDragCancel = {
                        dragAccumulatorX = 0f
                    }
                )
            }
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(
                start = WristleConfig.Dimensions.ScreenHorizontalPadding,
                top = WristleConfig.Dimensions.ContentTopPadding,
                end = WristleConfig.Dimensions.ScreenHorizontalPadding,
                bottom = WristleConfig.Dimensions.ContentBottomPadding
            )
        ) {
            // Header
            item {
                Text(
                    text = "Settings",
                    fontSize = WristleConfig.Typography.TitleFontSize,
                    fontWeight = FontWeight.Bold,
                    color = WristleConfig.Colors.TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // Section 1: Activation Methods
            item {
                SettingsCard(title = "Activation Methods") {
                    Spacer(modifier = Modifier.height(6.dp))
                    ActivationRow(
                        icon = WristleConfig.Icons.GestureButton,
                        title = "Button (PTT)",
                        onClick = { openGestureSettings(context, "button") }
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    ActivationRow(
                        icon = WristleConfig.Icons.GesturePinch,
                        title = "Double Pinch",
                        onClick = { openGestureSettings(context, "pinch") }
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    ActivationRow(
                        icon = WristleConfig.Icons.GestureKnock,
                        title = "Knock Gesture",
                        onClick = { openGestureSettings(context, "knock") }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Section 2: Recognition Language
            item {
                SettingsCard(title = "Speech Language") {
                    Spacer(modifier = Modifier.height(6.dp))
                    val isEnglish = state.recognitionLanguage.startsWith("en", ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF252730))
                            .clickable {
                                vibrate(context, WristleConfig.Haptics.PressDownDurationMs)
                                onRecognitionLanguageChange(if (isEnglish) "system" else "en-US")
                            }
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isEnglish) "English (US)" else "System Default",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = WristleConfig.Colors.AccentTeal
                        )
                        Text(
                            text = "Toggle",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = WristleConfig.Colors.AccentBlue
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Section 3: Auto-Retry Service
            item {
                AutoRetryCard(
                    title = "PC Auto-Retry",
                    enabled = state.autoRetryService,
                    onEnabledChange = {
                        vibrate(context, WristleConfig.Haptics.PressDownDurationMs)
                        onAutoRetryServiceChange(it)
                    },
                    seconds = state.serviceTimeoutSec,
                    onSecondsChange = onServiceTimeoutChange,
                    context = context
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Section 4: Auto-Retry Wi-Fi
            item {
                AutoRetryCard(
                    title = "Wi-Fi Auto-Retry",
                    enabled = state.autoRetryWifi,
                    onEnabledChange = {
                        vibrate(context, WristleConfig.Haptics.PressDownDurationMs)
                        onAutoRetryWifiChange(it)
                    },
                    seconds = state.wifiTimeoutSec,
                    onSecondsChange = onWifiTimeoutChange,
                    context = context
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Section 5: Offline Queue (if any)
            if (state.offlineQueueCount > 0) {
                item {
                    SettingsCard(
                        title = "${WristleConfig.Icons.Package} Offline Queue (${state.offlineQueueCount})"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            // Section 6: PC Reconnect
            item {
                SettingsCard(
                    title = if (state.isReady) (state.pcName ?: "PC") else state.statusText
                ) {
                    Spacer(modifier = Modifier.height(6.dp))
                    ActionButton(
                        text = if (state.isNoWifi) "${WristleConfig.Icons.WifiRetry} Connect Wi-Fi" else "${WristleConfig.Icons.Reconnect} Reconnect",
                        containerColor = if (state.isNoWifi) WristleConfig.Colors.ButtonActionBlue else WristleConfig.Colors.ButtonActionDark
                    ) {
                        vibrate(context, WristleConfig.Haptics.PressDownDurationMs)
                        onReconnect()
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Section 7: Return Button
            item {
                ActionButton(
                    text = "${WristleConfig.Icons.ArrowBack} Main Screen",
                    containerColor = WristleConfig.Colors.ChipInactive
                ) {
                    vibrate(context, WristleConfig.Haptics.PressUpDurationMs)
                    onNavigateToMain()
                }
            }
        }
    }
}

@Composable
private fun ActivationRow(
    icon: String,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF252730))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = WristleConfig.Colors.TextPrimary
            )
        }
        Text(
            text = "›",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = WristleConfig.Colors.TextSubtle
        )
    }
}

@Composable
private fun AutoRetryCard(
    title: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
    context: Context
) {
    SettingsCard(
        title = "",
        headerContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    fontSize = WristleConfig.Typography.CardTitleFontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = WristleConfig.Colors.AccentBlue
                )
                Md3Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
        }
    ) {
        if (enabled) {
            Spacer(modifier = Modifier.height(6.dp))
            TimeoutStepper(
                seconds = seconds,
                onSecondsChange = onSecondsChange,
                context = context
            )
        }
    }
}

@Composable
private fun TimeoutStepper(
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
    context: Context
) {
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    var rotaryAccumulator by remember { mutableFloatStateOf(0f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF252730))
            .border(
                width = if (isFocused) 1.dp else 0.dp,
                color = if (isFocused) WristleConfig.Colors.AccentTeal else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onRotaryScrollEvent { event ->
                rotaryAccumulator += event.verticalScrollPixels
                val threshold = 36f
                if (rotaryAccumulator >= threshold) {
                    val next = (seconds + WristleConfig.Timing.TimeoutStepSec)
                        .coerceAtMost(WristleConfig.Timing.MaxTimeoutSec)
                    if (next != seconds) {
                        vibrate(context, WristleConfig.Haptics.PressUpDurationMs)
                        onSecondsChange(next)
                    }
                    rotaryAccumulator = 0f
                    true
                } else if (rotaryAccumulator <= -threshold) {
                    val prev = (seconds - WristleConfig.Timing.TimeoutStepSec)
                        .coerceAtLeast(WristleConfig.Timing.MinTimeoutSec)
                    if (prev != seconds) {
                        vibrate(context, WristleConfig.Haptics.PressUpDurationMs)
                        onSecondsChange(prev)
                    }
                    rotaryAccumulator = 0f
                    true
                } else {
                    true
                }
            }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Minus button (-5s)
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(WristleConfig.Colors.ButtonActionDark)
                .clickable {
                    focusRequester.requestFocus()
                    val prev = (seconds - WristleConfig.Timing.TimeoutStepSec)
                        .coerceAtLeast(WristleConfig.Timing.MinTimeoutSec)
                    if (prev != seconds) {
                        vibrate(context, WristleConfig.Haptics.PressUpDurationMs)
                        onSecondsChange(prev)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "−",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = WristleConfig.Colors.TextPrimary
            )
        }

        // Seconds value
        Text(
            text = "${seconds}s",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = WristleConfig.Colors.TextPrimary,
            modifier = Modifier.clickable { focusRequester.requestFocus() }
        )

        // Plus button (+5s)
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(WristleConfig.Colors.ButtonActionDark)
                .clickable {
                    focusRequester.requestFocus()
                    val next = (seconds + WristleConfig.Timing.TimeoutStepSec)
                        .coerceAtMost(WristleConfig.Timing.MaxTimeoutSec)
                    if (next != seconds) {
                        vibrate(context, WristleConfig.Haptics.PressUpDurationMs)
                        onSecondsChange(next)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = WristleConfig.Colors.TextPrimary
            )
        }
    }
}

@Composable
private fun Md3Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 18.dp else 2.dp,
        label = "thumbOffset"
    )
    val trackColor = if (checked) WristleConfig.Colors.AccentTeal else Color(0xFF3E4048)

    Box(
        modifier = Modifier
            .size(width = 38.dp, height = 22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(trackColor)
            .clickable { onCheckedChange(!checked) }
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    headerContent: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WristleConfig.Dimensions.CardCornerRadius))
            .background(WristleConfig.Colors.CardBackground)
            .padding(WristleConfig.Dimensions.CardPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (headerContent != null) {
            headerContent()
        } else if (title.isNotBlank()) {
            Text(
                text = title,
                fontSize = WristleConfig.Typography.CardTitleFontSize,
                fontWeight = FontWeight.SemiBold,
                color = WristleConfig.Colors.AccentBlue,
                textAlign = TextAlign.Center
            )
        }
        content()
    }
}

@Composable
private fun ActionButton(
    text: String,
    containerColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WristleConfig.Dimensions.ActionButtonCornerRadius))
            .background(containerColor)
            .clickable { onClick() }
            .padding(vertical = 7.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = WristleConfig.Typography.ActionButtonFontSize,
            fontWeight = FontWeight.Medium,
            color = WristleConfig.Colors.TextPrimary,
            textAlign = TextAlign.Center
        )
    }
}

private fun vibrate(context: Context, durationMs: Long) {
    val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        v.vibrate(durationMs)
    }
}

private fun openGestureSettings(context: Context, gestureType: String) {
    val intents = when (gestureType) {
        "button" -> listOf(
            Intent("com.samsung.android.settings.KEY_CUSTOMIZE"),
            Intent("com.samsung.android.app.telephonyui.action.OPEN_KEY_CUSTOMIZE"),
            Intent("android.settings.ACCESSIBILITY_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        "pinch" -> listOf(
            Intent("com.samsung.android.settings.ACCESSIBILITY_UNIVERSAL_SWITCH"),
            Intent("com.samsung.android.settings.UNIVERSAL_SWITCH"),
            Intent("android.settings.ACCESSIBILITY_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        "knock" -> listOf(
            Intent("com.samsung.android.settings.QUICK_LAUNCH"),
            Intent("com.samsung.android.settings.GESTURE_SETTINGS"),
            Intent("android.settings.SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        else -> listOf(Intent(Settings.ACTION_SETTINGS))
    }

    for (intent in intents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // Try next fallback intent
        }
    }
}
