#!/usr/bin/env bash
# TurboDL - preparacion para compilacion offline (Linux/macOS)
# Ejecutar UNA VEZ con internet para poblar el cache de Gradle.
set -e
cd "$(dirname "$0")/.."
[ -n "$ANDROID_HOME" ] || { echo "Define ANDROID_HOME"; exit 1; }
./gradlew assembleRelease --no-daemon
echo "Listo: a partir de ahora usa scripts_compilacion/build_offline.sh sin conexion."
