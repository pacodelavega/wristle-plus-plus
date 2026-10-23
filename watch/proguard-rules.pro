# Wristle Wear OS ProGuard Rules
-keep class io.github.unknownprocess.wristle.nativebridge.** { *; }
-keepclassmembers class io.github.unknownprocess.wristle.nativebridge.** {
    native <methods>;
}
-keep class androidx.wear.compose.material3.** { *; }
