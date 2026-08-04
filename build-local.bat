@echo off
where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle 9.5.0 is required. Open the project in Android Studio or install Gradle 9.5.0.
  exit /b 1
)
python scripts\verify_source.py || exit /b 1
gradle --no-daemon :app:assembleDebug || exit /b 1
echo APK: app\build\outputs\apk\debug\app-debug.apk
