#!/usr/bin/env bash
set -euo pipefail
# Read-only provenance and APK isolation check. NO adb, install, or code execution.
REPO="jdawg867/anvildesk"
RUN_ID="43561562356"
EXPECTED_SHA="dd8009d7b5a1bc714c11feeeaedcee5bdc59c003"
ARTIFACT="m8-composite-jni-diagnostic-test-apks-arm64"
ARTIFACT_ID="14100975634"
ARTIFACT_DIGEST="sha256:fe3aaac5d1ab25fa350f9321732d5e30022d06d256cde39ae5835bf663af8fc1"
TARGET_PACKAGE="io.github.jdawg867.anvildesk.m8jnidiag"
TEST_PACKAGE="$TARGET_PACKAGE.test"
LIB="lib/arm64-v8a/libm8-composite-jni-diagnostic.so"

[[ "$#" -eq 1 && "$1" == "--verify-artifact" ]] || {
  echo "Usage: $0 --verify-artifact (read-only; no device actions)" >&2
  exit 2
}
for command in gh unzip readelf mktemp find grep; do
  command -v "$command" >/dev/null || {
    echo "STOP: missing dependency: $command" >&2
    exit 1
  }
done
: "${ANDROID_HOME:?ANDROID_HOME with Android build-tools required}"
AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt"
[[ -x "$AAPT" ]] || { echo "STOP: pinned Android aapt unavailable" >&2; exit 1; }

metadata="$(gh api "repos/$REPO/actions/runs/$RUN_ID" \
  --jq '[.head_sha, .conclusion, .event] | @tsv')"
[[ "$metadata" == "$(printf '%s\tsuccess\tpull_request' "$EXPECTED_SHA")" ]] || {
  echo "STOP: workflow provenance/conclusion/event mismatch" >&2
  exit 1
}
artifact_metadata="$(gh api "repos/$REPO/actions/runs/$RUN_ID/artifacts" \
  --jq ".artifacts[] | select(.name == \"$ARTIFACT\") | [.id, .digest, .expired, .workflow_run.head_sha] | @tsv")"
[[ "$artifact_metadata" == "$(printf '%s\t%s\tfalse\t%s' "$ARTIFACT_ID" "$ARTIFACT_DIGEST" "$EXPECTED_SHA")" ]] || {
  echo "STOP: artifact ID/digest/source mismatch or artifact expired" >&2
  exit 1
}
workspace="$(mktemp -d)"
trap 'rm -rf -- "$workspace"' EXIT
gh run download "$RUN_ID" --repo "$REPO" --name "$ARTIFACT" --dir "$workspace"
mapfile -d '' -t targets < <(find "$workspace" -type f -name 'app-debug.apk' -print0)
mapfile -d '' -t tests < <(find "$workspace" -type f -name 'app-debug-androidTest.apk' -print0)
[[ "${#targets[@]}" -eq 1 && "${#tests[@]}" -eq 1 ]] || {
  echo "STOP: expected exactly one target and one instrumentation APK" >&2
  exit 1
}
target="${targets[0]}"
testapk="${tests[0]}"
[[ ! -L "$target" && ! -L "$testapk" ]] || {
  echo "STOP: refusing symlinked APK artifact" >&2
  exit 1
}
unzip -tqq "$target"
unzip -tqq "$testapk"
target_badging="$("$AAPT" dump badging "$target")"
test_badging="$("$AAPT" dump badging "$testapk")"
grep -Fq "package: name='$TARGET_PACKAGE'" <<< "$target_badging" || {
  echo "STOP: target diagnostic package mismatch" >&2
  exit 1
}
grep -Fq "package: name='$TEST_PACKAGE'" <<< "$test_badging" || {
  echo "STOP: instrumentation package mismatch" >&2
  exit 1
}
if unzip -Z1 "$target" | grep -Fq 'libm8-composite-jni-diagnostic.so'; then
  echo "STOP: diagnostic library found in target APK" >&2
  exit 1
fi
test_entries="$(unzip -Z1 "$testapk")"
grep -Fxq "$LIB" <<< "$test_entries" || {
  echo "STOP: diagnostic library missing from instrumentation APK" >&2
  exit 1
}
"$AAPT" dump xmltree "$testapk" AndroidManifest.xml > "$workspace/test-manifest.txt"
grep -Fq 'E: instrumentation' "$workspace/test-manifest.txt" || {
  echo "STOP: instrumentation declaration missing" >&2
  exit 1
}
grep -Fq 'android:name(0x01010003)="androidx.test.runner.AndroidJUnitRunner"' \
  "$workspace/test-manifest.txt" || {
  echo "STOP: unexpected instrumentation runner" >&2
  exit 1
}
grep -Fq "android:targetPackage(0x01010021)=\"$TARGET_PACKAGE\"" \
  "$workspace/test-manifest.txt" || {
  echo "STOP: instrumentation targets unexpected package" >&2
  exit 1
}
unzip -p "$testapk" "$LIB" > "$workspace/isolated-diagnostic.so"
readelf -h "$workspace/isolated-diagnostic.so" | grep -Eq 'Machine:.*AArch64' || {
  echo "STOP: diagnostic shared library is not ARM64" >&2
  exit 1
}
readelf -Ws "$workspace/isolated-diagnostic.so" > "$workspace/symbols.txt"
grep -Fq 'Java_io_github_jdawg867_anvildesk_m8_M8CompositeJniDiagnostic_runProbe' \
  "$workspace/symbols.txt" || {
  echo "STOP: JNI entry symbol missing" >&2
  exit 1
}
if grep -Fq 'JNI_OnLoad' "$workspace/symbols.txt"; then
  echo "STOP: diagnostic exports JNI_OnLoad unexpectedly" >&2
  exit 1
fi
echo 'M8_COMPOSITE_JNI_APK_ARTIFACT_PREFLIGHT=PASS'
echo 'M8_COMPOSITE_JNI_TEST_ONLY_PACKAGE_ISOLATION=PASS'
echo 'M8_COMPOSITE_JNI_DEVICE_EXECUTED=false'
echo 'M8_NATIVE_LOAD_AUTHORIZED=false'
