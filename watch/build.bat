@echo off
setlocal enabledelayedexpansion

rem ==============================================================================
rem Wristle Wear OS Build Script (Kotlin + Jetpack Compose Material 3 + NDK C++)
rem ==============================================================================

cd /d "%~dp0.."

if exist "gradlew.bat" (
    echo Building Wristle Wear OS app with Gradle and NDK...
    call gradlew.bat :watch:assembleDebug
    if errorlevel 1 (
        echo [ERROR] Build failed.
        exit /b 1
    )
    echo.
    echo [SUCCESS] APK built successfully:
    echo watch\build\outputs\apk\debug\watch-debug.apk
) else (
    echo [ERROR] gradlew.bat not found in project root.
    exit /b 1
)
