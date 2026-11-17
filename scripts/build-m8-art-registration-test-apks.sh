#!/usr/bin/env bash
set -euo pipefail
# Opt-in, build-only Android ART diagnostic package pair. NEVER installs APKs.
[[ "${ANVILDESK_M8_ART_DIAGNOSTIC_APK_BUILD:-}" == "true" ]] || {
  echo "STOP: ART diagnostic package build needs explicit opt-in" >&2
  exit 1
}
: "${ANDROID_HOME:?Android SDK required}"
test ! -e app/src/main/jniLibs/arm64-v8a/libm8-art-registration-diagnostic.so
NDK="$ANDROID_HOME/ndk/27.2.12479018"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
OUT="app/build/generated/m8-art-registration-diagnostic/androidTest/jniLibs/arm64-v8a"
mkdir -p "$OUT"
"$BIN/aarch64-linux-android28-clang++" \
  -std=c++17 -shared -fPIC -O1 -Wall -Wextra -Werror \
  -Wl,--no-undefined \
  scripts/m8-android-art-registration-probe.cpp \
  -o "$OUT/libm8-art-registration-diagnostic.so"
"$BIN/llvm-readelf" -h "$OUT/libm8-art-registration-diagnostic.so" \
  | grep -Eq 'Machine:.*AArch64'
"$BIN/llvm-readelf" -Ws "$OUT/libm8-art-registration-diagnostic.so" \
  > /tmp/m8-art-registration-exported-symbols.txt
grep -Fq 'Java_io_github_jdawg867_anvildesk_m8_M8ArtRegistrationDiagnostic_runProbe' \
  /tmp/m8-art-registration-exported-symbols.txt
if grep -Eq '[[:space:]]JNI_OnLoad([[:space:]]|$)' /tmp/m8-art-registration-exported-symbols.txt; then
  echo 'STOP: ART diagnostic unexpectedly exports JNI_OnLoad' >&2
  exit 1
fi
echo 'M8_ART_DIAGNOSTIC_NATIVE_PREBUILD=PASS'
gradle --no-daemon -PanvildeskM8ArtRegistrationDiagnostic=true \
  :app:assembleDebug :app:assembleDebugAndroidTest
TARGET="app/build/outputs/apk/debug/app-debug.apk"
TEST="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
test -s "$TARGET"
test -s "$TEST"
unzip -tqq "$TARGET"
unzip -tqq "$TEST"
if unzip -Z1 "$TARGET" | grep -Fq 'libm8-art-registration-diagnostic.so'; then
  echo 'STOP: ART diagnostic native library leaked into target APK' >&2
  exit 1
fi
unzip -Z1 "$TEST" \
  | grep -Fx 'lib/arm64-v8a/libm8-art-registration-diagnostic.so'
AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt"
test -x "$AAPT"
"$AAPT" dump badging "$TARGET" \
  | grep -F "package: name='io.github.jdawg867.anvildesk.m8artdiag'"
"$AAPT" dump badging "$TEST" \
  | grep -F "package: name='io.github.jdawg867.anvildesk.m8artdiag.test'"
"$AAPT" dump xmltree "$TEST" AndroidManifest.xml \
  > /tmp/m8-art-registration-test-manifest.txt
grep -Fq 'E: instrumentation' /tmp/m8-art-registration-test-manifest.txt
grep -Fq 'android:name(0x01010003)="androidx.test.runner.AndroidJUnitRunner"' \
  /tmp/m8-art-registration-test-manifest.txt
grep -Fq 'android:targetPackage(0x01010021)="io.github.jdawg867.anvildesk.m8artdiag"' \
  /tmp/m8-art-registration-test-manifest.txt
echo 'M8_ART_DIAGNOSTIC_TEST_ONLY_PACKAGE_ISOLATION=PASS'
echo 'M8_ART_DIAGNOSTIC_DEVICE_EXECUTED=false'
echo 'M8_NATIVE_LOAD_AUTHORIZED=false'
