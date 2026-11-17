#!/usr/bin/env bash
set -euo pipefail
# Read-only by default; explicit --run-on-device AND --serial required.
REPO='jdawg867/anvildesk'
TARGET='io.github.jdawg867.anvildesk.m8artdiag'
TEST='io.github.jdawg867.anvildesk.m8artdiag.test'
LIB='lib/arm64-v8a/libm8-art-registration-diagnostic.so'
CLASS='io.github.jdawg867.anvildesk.m8.M8ArtRegistrationInstrumentedTest'
RUNNER='androidx.test.runner.AndroidJUnitRunner'
RUN_ID='' SHA='' SERIAL='' EXECUTE=false CHECK_DEVICE=false
usage() { echo "Usage: bash $0 --run-id RUN_ID --sha FULL_SHA [--check-device --serial SERIAL | --run-on-device --serial SERIAL]"; }
while (( $# )); do
  case "$1" in
    --run-id) RUN_ID="$2"; shift 2 ;;
    --sha) SHA="$2"; shift 2 ;;
    --serial) SERIAL="$2"; shift 2 ;;
    --run-on-device) EXECUTE=true; shift ;;
    --check-device) CHECK_DEVICE=true; shift ;;
    *) usage >&2; exit 2 ;;
  esac
done
[[ "$RUN_ID" =~ ^[0-9]+$ && "$SHA" =~ ^[0-9a-f]{40}$ ]] || { usage >&2; exit 2; }
if [[ "$EXECUTE" == true && "$CHECK_DEVICE" == true ]]; then
  echo "STOP: choose read-only --check-device or explicit --run-on-device, not both" >&2
  exit 2
fi
if [[ "$EXECUTE" == true || "$CHECK_DEVICE" == true ]]; then
  [[ -n "$SERIAL" && "$SERIAL" != *[[:space:]]* ]] || {
    echo 'STOP: explicit ADB serial required' >&2; exit 1;
  }
else
  [[ -z "$SERIAL" ]] || { echo 'STOP: serial requires --check-device or --run-on-device' >&2; exit 1; }
fi
for tool in gh unzip readelf grep mktemp find base64; do
  command -v "$tool" >/dev/null || { echo "STOP: missing $tool" >&2; exit 1; }
done
: "${ANDROID_HOME:?Android SDK required}"
AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt"
[[ -x "$AAPT" ]] || { echo 'STOP: missing aapt 36' >&2; exit 1; }
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
gh api "repos/$REPO/contents/scripts/verify-m8-art-registration-test-apks.sh?ref=$SHA" \
  --jq .content | base64 -d > "$tmp/verify.sh"
bash "$tmp/verify.sh" "$RUN_ID" "$SHA" | tee "$tmp/preflight.log"
grep -Fxq 'M8_ART_DIAGNOSTIC_ARTIFACT_PREFLIGHT=PASS' "$tmp/preflight.log"
grep -Fxq 'M8_ART_DIAGNOSTIC_TEST_ONLY_PACKAGE_ISOLATION=PASS' "$tmp/preflight.log"
if [[ "$EXECUTE" != true && "$CHECK_DEVICE" != true ]]; then
  echo 'M8_ART_DIAGNOSTIC_DEVICE_EXECUTED=false'
  echo 'M8_NATIVE_LOAD_AUTHORIZED=false'
  exit 0
fi
command -v adb >/dev/null || { echo 'STOP: adb not found' >&2; exit 1; }
[[ "$(adb -s "$SERIAL" get-state 2>/dev/null)" == device ]] || {
  echo 'STOP: device unavailable' >&2; exit 1;
}
MODEL="$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
API="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
ABIS="$(adb -s "$SERIAL" shell getprop ro.product.cpu.abilist | tr -d '\r')"
[[ "$MODEL" == SM-G766U ]] || { echo "STOP: incorrect model $MODEL" >&2; exit 1; }
[[ "$API" =~ ^[0-9]+$ ]] && (( API >= 28 )) || {
  echo 'STOP: unsupported Android SDK' >&2; exit 1;
}
[[ ",$ABIS," == *',arm64-v8a,'* ]] || { echo 'STOP: ARM64 unavailable' >&2; exit 1; }
if adb -s "$SERIAL" shell pm list packages | tr -d '\r' \
    | grep -Eq "^package:($TARGET|$TEST)$"; then
  echo 'STOP: isolated diagnostic package already installed' >&2; exit 1
fi
if [[ "$CHECK_DEVICE" == true ]]; then
  echo 'M8_ART_DIAGNOSTIC_DEVICE_READ_ONLY_PREFLIGHT=PASS'
  echo 'M8_ART_DIAGNOSTIC_DEVICE_EXECUTED=false'
  echo 'M8_NATIVE_LOAD_AUTHORIZED=false'
  exit 0
fi
gh run download "$RUN_ID" --repo "$REPO" \
  --name m8-art-registration-test-apks-arm64 --dir "$tmp/apks"
TARGET_APK="$(find "$tmp/apks" -type f -name app-debug.apk -print -quit)"
TEST_APK="$(find "$tmp/apks" -type f -name app-debug-androidTest.apk -print -quit)"
[[ -f "$TARGET_APK" && -f "$TEST_APK" ]] || { echo 'STOP: missing APK pair' >&2; exit 1; }
unzip -Z1 "$TEST_APK" | grep -Fxq "$LIB"
"$AAPT" dump badging "$TARGET_APK" | grep -Fq "package: name='$TARGET'"
"$AAPT" dump badging "$TEST_APK" | grep -Fq "package: name='$TEST'"
installed_target=false installed_test=false
cleanup() {
  if [[ "$installed_test" == true ]]; then
    adb -s "$SERIAL" uninstall "$TEST" >/dev/null || echo 'WARN: test cleanup failed' >&2
  fi
  if [[ "$installed_target" == true ]]; then
    adb -s "$SERIAL" uninstall "$TARGET" >/dev/null || echo 'WARN: target cleanup failed' >&2
  fi
}
trap 'cleanup; rm -rf -- "$tmp"' EXIT
adb -s "$SERIAL" install "$TARGET_APK" >/dev/null
installed_target=true
adb -s "$SERIAL" install "$TEST_APK" >/dev/null
installed_test=true
adb -s "$SERIAL" shell am instrument -w \
  -e class "$CLASS" \
  -e m8ArtRegistrationDiagnostic true \
  "$TEST/$RUNNER" | tee "$tmp/instrumentation.log"
grep -Fq 'OK (1 test)' "$tmp/instrumentation.log" || {
  echo 'STOP: expected one successful ART diagnostic test' >&2; exit 1;
}
if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$tmp/instrumentation.log"; then
  echo 'STOP: instrumentation failure' >&2; exit 1
fi
echo 'M8_ART_DIAGNOSTIC_DEVICE_EXECUTED=true'
echo 'M8_ART_DIAGNOSTIC_INSTRUMENTATION=PASS'
echo 'M8_NATIVE_LOAD_AUTHORIZED=false'
