package io.github.unknownprocess.wristle.config

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// @keep(pacodelavega): Single unified configuration for Wear OS 40mm round UI, timings, haptics, and network protocol

object WristleConfig {

    object Colors {
        val Background = Color.Black
        val CardBackground = Color(0xFF1C1D22)
        val CardBorder = Color(0x22FFFFFF)
        val ChipActive = Color(0xFF14B8A6)
        val ChipInactive = Color(0xFF2A2B32)

        val ButtonRecording = Color(0xFFE5383B) // Coral Red
        val ButtonReady = Color(0xFF14B8A6)     // Vibrant Samsung Teal
        val ButtonNeutral = Color(0xFF3A3A3C)   // Neutral Dark
        val ButtonActionBlue = Color(0xFF2563EB) // Samsung Action Blue
        val ButtonActionDark = Color(0xFF1E293B) // Slate Dark
        val CancelRed = Color(0xFFFF5252)

        val TextPrimary = Color.White
        val TextSecondary = Color(0xFFCCCCCC)
        val TextMuted = Color(0xFFAAAAAA)
        val TextSubtle = Color(0xFF777777)

        val AccentTeal = Color(0xFF14B8A6)
        val AccentBlue = Color(0xFF38BDF8)

        val ArcIndicatorActive = Color(0xFF14B8A6)
        val ArcIndicatorInactive = Color(0x44FFFFFF)
    }

    object Dimensions {
        val CenterButtonSize: Dp = 88.dp
        val CancelButtonSize: Dp = 36.dp
        val WifiRetryButtonWidth: Dp = 80.dp
        val WifiRetryButtonHeight: Dp = 34.dp

        val ScreenHorizontalPadding: Dp = 14.dp
        val ScreenVerticalPadding: Dp = 16.dp
        val CardPadding: Dp = 10.dp

        val CardCornerRadius: Dp = 14.dp
        val ChipCornerRadius: Dp = 10.dp
        val ActionButtonCornerRadius: Dp = 10.dp

        val ArcStrokeWidth: Dp = 3.5.dp
        val RoundDisplayInset: Dp = 14.dp
        val ContentTopPadding: Dp = 28.dp
        val ContentBottomPadding: Dp = 48.dp
    }

    object Typography {
        val TitleFontSize: TextUnit = 15.sp
        val CardTitleFontSize: TextUnit = 12.sp
        val BodyFontSize: TextUnit = 12.sp
        val ActionButtonFontSize: TextUnit = 11.sp
        val CaptionFontSize: TextUnit = 10.sp
        val HintFontSize: TextUnit = 10.sp

        val MicGlyphSize: TextUnit = 32.sp
        val CancelGlyphSize: TextUnit = 16.sp
    }

    object Timing {
        const val LockModeThresholdMs: Long = 350L
        const val QuickModeLaunchThresholdMs: Long = 300L
        const val QuickModeFinishDelayMs: Long = 800L
        const val QuickModeCancelDelayMs: Long = 500L
        const val SentStateResetDelayMs: Long = 1500L
        const val OfflineFlushDelayMs: Long = 80L
        const val FinalTextWaitTimeoutMs: Long = 800L
        const val ReconnectIntervalSeconds: Int = 10
        const val ReconnectCountdownStepMs: Long = 1000L
        const val DefaultServiceTimeoutSec: Int = 10
        const val DefaultWifiTimeoutSec: Int = 30
        const val TimeoutStepSec: Int = 5
        const val MinTimeoutSec: Int = 5
        const val MaxTimeoutSec: Int = 120
        const val MaxWakeLockDurationMs: Long = 5 * 60 * 1000L // 5 minutes
    }

    object Haptics {
        const val PressDownDurationMs: Long = 40L
        const val PressUpDurationMs: Long = 25L
        const val CancelDurationMs: Long = 80L
        const val QuickLaunchDurationMs: Long = 50L
    }

    object Network {
        const val TcpPort: Int = 41083
        const val UdpPort: Int = 47821
        const val ProtocolVersion: Int = 2
        const val SocketTimeoutMs: Int = 4000
        const val DiscoveryTimeoutMs: Int = 1200
        const val DiscoveryAttempts: Int = 3
        const val BufferSize: Int = 8192
    }

    object Preferences {
        const val PrefsName: String = "wristle_prefs"
        const val KeyOfflineQueue: String = "offline_text_queue"
        const val KeyAutoRetryService: String = "pref_auto_retry_service"
        const val KeyServiceTimeoutSec: String = "pref_service_timeout_sec"
        const val KeyAutoRetryWifi: String = "pref_auto_retry_wifi"
        const val KeyWifiTimeoutSec: String = "pref_wifi_timeout_sec"
        const val KeyRecognitionLanguage: String = "pref_recognition_language"
        const val DefaultLanguage: String = "en-US"
        const val KeySttEngine: String = "pref_stt_engine"
        const val KeyLiveTyping: String = "pref_live_typing"
        const val KeyHostPrefix: String = "key:"
        const val EngineWatchNpu: String = "watch_npu"
    }

    object Icons {
        const val Mic: String = "🎤"
        const val RecordingDot: String = "●"
        const val Cancel: String = "✕"
        const val Settings: String = "⚙"
        const val Reconnect: String = "🔄"
        const val Check: String = "✓"
        const val BadgeNpu: String = "⚡"
        const val Package: String = "📦"
        const val ArrowBack: String = "←"
        const val WatchUltra: String = "🟠"
        const val Watch7: String = "🔵"
        const val Wifi: String = "ᯤ"
        const val WifiRetry: String = "ᯤ ↻"
        const val WifiOff: String = "🚫"
        const val Pause: String = "⏸"
        const val Play: String = "▶"
        const val GesturePinch: String = "🤏"
        const val GestureKnock: String = "✊"
        const val GestureButton: String = "🔘"
    }
}
