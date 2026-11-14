#!/usr/bin/env bash
set -euo pipefail
# Explicit Samsung-only instrumentation experiment, never the production app.
# --verify-artifact is fully read-only. --run-on-device installs ONLY the
# separate .m8jnidiag target and .test APKs, then uninstalls those two.
REPO="jdawg867/anvildesk"
RUN_ID="43561562356"
ARTIFACT="m8-composite-jni-diagnostic-test-apks-arm64"
TARGET="io.github.jdawg867.anvildesk.m8jnidiag"
TEST="$TARGET.test"
ORIGINAL="io.github.jdawg867.anvildesk"
TEST_CLASS="io.github.jdawg867.anvildesk.m8.M8CompositeJniDiagnosticInstrumentedTest"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
[[ "$#" -eq 1 && ( "$1" == "--verify-artifact" || "$1" == "--run-on-device" ) ]] || {
  echo "Usage: $0 --verify-artifact | --run-on-device" >&2
  exit 2
}
mode="$1"
for command in gh mktemp find grep; do
  command -v "$command" >/dev/null || { echo "STOP: missing $command" >&2; exit 1; }
done
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
verifier="$script_dir/verify-m8-composite-jni-test-apks.sh"
[[ -f "$verifier" ]] || { echo "STOP: pinned read-only verifier missing" >&2; exit 1; }
bash "$verifier" --verify-artifact
if [[ "$mode" == "--verify-artifact" ]]; then
  echo 'M8_COMPOSITE_JNI_SAMSUNG_RUNNER_PREFLIGHT=PASS'
  echo 'M8_COMPOSITE_JNI_DEVICE_EXECUTED=false'
  exit 0
fi

for command in adb timeout; do
  command -v "$command" >/dev/null || { echo "STOP: missing $command" >&2; exit 1; }
done
mapfile -t devices < <(adb devices | awk '$2=="device" {print $1}')
[[ "${#devices[@]}" == 1 ]] || { echo "STOP: exactly one online ADB device required" >&2; exit 1; }
serial="${devices[0]}"
model="$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"
abi="$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$model" == "SM-G766U" && "$abi" == "arm64-v8a" ]] || {
  echo "STOP: refusing non-Samsung SM-G766U ARM64 target" >&2
  exit 1
}
# Fail closed if either diagnostic package already exists; never overwrite
# diagnostic packages owned by a different test run or the installed main app.
package_path() {
  adb -s "$serial" shell pm path "$1" | tr -d '\r'
}
[[ -z "$(package_path "$TARGET")" && -z "$(package_path "$TEST")" ]] || {
  echo "STOP: diagnostic packages already present; refusing overwrite/uninstall" >&2
  exit 1
}
original_before="$(package_path "$ORIGINAL")"
workspace="$(mktemp -d)"
installed_target=false
installed_test=false
cleanup() {
  result=$?
  trap - EXIT
  # Uninstall only the two diagnostic identities installed by THIS run.
  if [[ "$installed_test" == true ]]; then
    adb -s "$serial" uninstall "$TEST" >/dev/null || echo "WARNING: manual diagnostic test package cleanup needed" >&2
  fi
  if [[ "$installed_target" == true ]]; then
    adb -s "$serial" uninstall "$TARGET" >/dev/null || echo "WARNING: manual diagnostic target cleanup needed" >&2
  fi
  original_after="$(package_path "$ORIGINAL" 2>/dev/null || true)"
  if [[ "$original_after" != "$original_before" ]]; then
    echo "STOP: original AnvilDesk package path changed; inspect device state" >&2
    result=1
  fi
  rm -rf -- "$workspace"
  exit "$result"
}
trap cleanup EXIT
gh run download "$RUN_ID" --repo "$REPO" --name "$ARTIFACT" --dir "$workspace"
mapfile -d '' -t target_apks < <(find "$workspace" -type f -name app-debug.apk -print0)
mapfile -d '' -t test_apks < <(find "$workspace" -type f -name app-debug-androidTest.apk -print0)
[[ "${#target_apks[@]}" == 1 && "${#test_apks[@]}" == 1 ]] || {
  echo "STOP: expected one test target APK and one instrumentation APK" >&2
  exit 1
}
# Repeat the package identity/isolation checks on the exact files to install.
: "${ANDROID_HOME:?Android SDK required for local package verification}"
aapt="$ANDROID_HOME/build-tools/36.0.0/aapt"
[[ -x "$aapt" ]] || { echo "STOP: aapt missing" >&2; exit 1; }
"$aapt" dump badging "${target_apks[0]}" | grep -Fq "package: name='$TARGET'"
"$aapt" dump badging "${test_apks[0]}" | grep -Fq "package: name='$TEST'"
if unzip -Z1 "${target_apks[0]}" | grep -Fq libm8-composite-jni-diagnostic.so; then
  echo "STOP: diagnostic library leaked into target APK" >&2
  exit 1
fi
unzip -Z1 "${test_apks[0]}" > "$workspace/test-list.txt"
grep -Fxq 'lib/arm64-v8a/libm8-composite-jni-diagnostic.so' "$workspace/test-list.txt"
"$aapt" dump xmltree "${test_apks[0]}" AndroidManifest.xml > "$workspace/manifest.txt"
grep -Fq 'android:name(0x01010003)="androidx.test.runner.AndroidJUnitRunner"' "$workspace/manifest.txt"
grep -Fq "android:targetPackage(0x01010021)=\"$TARGET\"" "$workspace/manifest.txt"
[[ "$(package_path "$ORIGINAL")" == "$original_before" ]] || {
  echo "STOP: original app changed before diagnostic installation" >&2
  exit 1
}

echo "Installing separate M8 diagnostic target package (NOT AnvilDesk)..."
timeout 60s adb -s "$serial" install -t "${target_apks[0]}"
installed_target=true
echo "Installing separate M8 diagnostic instrumentation APK..."
timeout 60s adb -s "$serial" install -t "${test_apks[0]}"
installed_test=true
[[ -n "$(package_path "$TARGET")" && -n "$(package_path "$TEST")" ]] || {
  echo "STOP: diagnostic packages missing after installation" >&2
  exit 1
}
[[ "$(package_path "$ORIGINAL")" == "$original_before" ]] || {
  echo "STOP: original AnvilDesk package changed during installation" >&2
  exit 1
}
echo 'M8_COMPOSITE_JNI_SEPARATE_PACKAGES_INSTALLED=PASS'
# No global tests: use one explicitly named test class and opt-in arg.
# Android's instrumentation exit status alone may not represent JUnit
# failures, so also require the canonical successful instrumentation markers.
timeout 100s adb -s "$serial" shell am instrument -w -r \
  -e m8CompositeJniDiagnostic true \
  -e class "$TEST_CLASS" \
  "$TEST/$RUNNER" | tee "$workspace/result.txt"
grep -Eq '^OK \(1 test\)|^OK \(1 tests\)' "$workspace/result.txt"
grep -Fxq 'INSTRUMENTATION_CODE: -1' "$workspace/result.txt"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_RESULT: shortMsg=' "$workspace/result.txt"; then
  echo "STOP: Android instrumentation reports a failure" >&2
  exit 1
fi
[[ "$(package_path "$ORIGINAL")" == "$original_before" ]] || {
  echo "STOP: original AnvilDesk installation changed after diagnostic test" >&2
  exit 1
}
echo 'M8_COMPOSITE_JNI_ANDROID_INSTRUMENTATION=PASS'
echo 'M8_COMPOSITE_JNI_ORIGINAL_APP_PATH_UNCHANGED=PASS'
echo 'M8_LORIE_CALLBACK_JNI_INTEGRATED=false'
echo 'M8_NATIVE_LOAD_AUTHORIZED=false'
