#!/usr/bin/env bash
set -euo pipefail

if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle 9.5.0 未安装。请用 Android Studio 打开工程，或安装 Gradle 9.5.0。" >&2
  exit 1
fi

python3 scripts/verify_source.py
gradle --no-daemon :app:assembleDebug

echo "APK: app/build/outputs/apk/debug/app-debug.apk"
