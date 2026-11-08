#!/usr/bin/env bash
# TurboDL - compilacion offline (Linux/macOS)
# Equivalente a build_offline.bat. Requiere haber compilado una vez
# con internet (prepare) para poblar el cache de Gradle.
set -e
cd "$(dirname "$0")/.."
[ -n "$ANDROID_HOME" ] || { echo "Define ANDROID_HOME"; exit 1; }
./gradlew --offline assembleRelease --no-daemon
mkdir -p release
cp app/build/outputs/apk/release/TurboDL-*.apk release/
echo "APK generado en release/"
