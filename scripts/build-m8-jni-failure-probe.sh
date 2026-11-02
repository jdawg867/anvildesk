#!/usr/bin/env bash
set -euo pipefail
# CI emulator only: build a deliberately failing JNI_OnLoad probe for androidTest.
# Never add the output to app/src/main/jniLibs or a production APK.
test "${ANVILDESK_M8_EMULATOR_PROBE:-}" = "true" || {
  echo "STOP: emulator JNI failure probe requires explicit CI opt-in" >&2; exit 1;
}
: "${ANDROID_HOME:?ANDROID_HOME required}"
NDK="${ANDROID_HOME}/ndk/27.2.12479018"
test -d "$NDK" || { echo "STOP: pinned NDK missing" >&2; exit 1; }
HOST="$(find "$NDK/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -n1)"
test -n "$HOST" || exit 1
COMPILER="$HOST/bin/x86_64-linux-android28-clang++"
test -x "$COMPILER" || exit 1
OUT="app/src/androidTest/jniLibs/x86_64"
mkdir -p "$OUT"
"$COMPILER" -shared -fPIC -std=c++17 -static-libstdc++ -Wall -Wextra -Werror \
  -I"$HOST/sysroot/usr/include" \
  app/src/androidTest/cpp/m8_jni_failure_probe.cpp \
  -o "$OUT/libm8jnifailureprobe.so"
test -s "$OUT/libm8jnifailureprobe.so"
echo "PASS: built test-only x86_64 JNI_OnLoad rejection probe"
