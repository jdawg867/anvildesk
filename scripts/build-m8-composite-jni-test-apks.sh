#!/usr/bin/env bash
set -euo pipefail
trap 'code=$?; echo "STOP: composite JNI APK packaging failed at line $LINENO (exit $code): $BASH_COMMAND" >&2' ERR
# Opt-in CI-only composite JNI test APK pair: NEVER install, deploy, or publish.
[[ "${ANVILDESK_M8_COMPOSITE_JNI_APK_BUILD:-}" == "true" ]] || {
  echo "STOP: explicit isolated diagnostic APK opt-in required" >&2
  exit 1
}
[[ -n "${ANDROID_HOME:-}" ]] || { echo "STOP: ANDROID_HOME missing" >&2; exit 1; }
[[ ! -e app/src/main/jniLibs/arm64-v8a/libm8-composite-jni-diagnostic.so ]] || {
  echo "STOP: diagnostic native library present in main source set" >&2
  exit 1
}
NDK="$ANDROID_HOME/ndk/27.2.12479018"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
OUT="app/build/generated/m8-composite-jni-diagnostic/androidTest/jniLibs/arm64-v8a"
mkdir -p "$OUT"
"$BIN/clang++" --target=aarch64-linux-android26 \
  -std=c++17 -fPIC -shared -pthread -static-libstdc++ \
  -O1 -Wall -Wextra -Werror -Wl,--no-undefined \
  scripts/m8-android-composite-jni-diagnostic.cpp \
  -landroid -o "$OUT/libm8-composite-jni-diagnostic.so"
"$BIN/llvm-readelf" -h "$OUT/libm8-composite-jni-diagnostic.so" \
  | grep -Eq 'Machine:.*AArch64'
"$BIN/llvm-readelf" -Ws "$OUT/libm8-composite-jni-diagnostic.so" \
  > /tmp/m8-composite-jni-exported-symbols.txt
grep -Fq 'Java_io_github_jdawg867_anvildesk_m8_M8CompositeJniDiagnostic_runProbe' \
  /tmp/m8-composite-jni-exported-symbols.txt
echo "M8_COMPOSITE_JNI_NATIVE_PREBUILD=PASS"
gradle --no-daemon \
  -PanvildeskM8CompositeJniDiagnostic=true \
  :app:assembleDebug :app:assembleDebugAndroidTest
TARGET="app/build/outputs/apk/debug/app-debug.apk"
TEST="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
test -s "$TARGET"
test -s "$TEST"
if unzip -Z1 "$TARGET" | grep -Fq 'libm8-composite-jni-diagnostic.so'; then
  echo "STOP: diagnostic library leaked into target APK" >&2
  exit 1
fi
unzip -Z1 "$TEST" \
  | grep -Fx 'lib/arm64-v8a/libm8-composite-jni-diagnostic.so'
AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt"
test -x "$AAPT"
"$AAPT" dump badging "$TARGET" \
  | grep -F "package: name='io.github.jdawg867.anvildesk.m8jnidiag'"
"$AAPT" dump badging "$TEST" \
  | grep -F "package: name='io.github.jdawg867.anvildesk.m8jnidiag.test'"
"$AAPT" dump badging "$TEST" \
  | grep -E "instrumentation: name='androidx.test.runner.AndroidJUnitRunner'.*targetPackage='io.github.jdawg867.anvildesk.m8jnidiag'"
echo 'M8_COMPOSITE_JNI_TEST_ONLY_PACKAGE_ISOLATION=PASS'
echo 'M8_COMPOSITE_JNI_ANDROID_EXECUTED=false'
echo 'M8_NATIVE_LOAD_AUTHORIZED=false'
