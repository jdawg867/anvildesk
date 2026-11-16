#!/usr/bin/env bash
set -euo pipefail
# READ-ONLY GitHub artifact preflight. Never calls adb or installs APKs.
# Usage: bash scripts/verify-m8-art-registration-test-apks.sh RUN_ID EXPECTED_HEAD_SHA
REPO='jdawg867/anvildesk'
ARTIFACT='m8-art-registration-test-apks-arm64'
TARGET_PACKAGE='io.github.jdawg867.anvildesk.m8artdiag'
TEST_PACKAGE="$TARGET_PACKAGE.test"
LIB='lib/arm64-v8a/libm8-art-registration-diagnostic.so'
[[ "$#" -eq 2 && "$1" =~ ^[0-9]+$ && "$2" =~ ^[0-9a-f]{40}$ ]] || {
  echo 'Usage: verify-m8-art-registration-test-apks.sh RUN_ID EXPECTED_HEAD_SHA' >&2
  exit 2
}
RUN_ID="$1"
EXPECTED_SHA="$2"
for cmd in gh unzip readelf mktemp find grep; do
  command -v "$cmd" >/dev/null || { echo "STOP: missing $cmd" >&2; exit 1; }
done
: "${ANDROID_HOME:?Android SDK required for aapt}"
AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt"
[[ -x "$AAPT" ]] || { echo 'STOP: pinned aapt missing' >&2; exit 1; }
meta="$(gh api "repos/$REPO/actions/runs/$RUN_ID" \
  --jq '[.head_sha,.conclusion,.head_branch,.event,.workflow_id] | @tsv')"
[[ "$meta" == "$(printf '%s\tsuccess\tfeature/android-x11-display-input\tpull_request\t420367785' "$EXPECTED_SHA")" ]] || {
  echo 'STOP: CI run SHA/branch/conclusion/event mismatch' >&2
  exit 1
}
artifact_info="$(gh api "repos/$REPO/actions/runs/$RUN_ID/artifacts" \
  --jq ".artifacts[] | select(.name == \"$ARTIFACT\") | [.id,.digest,.expired,.workflow_run.head_sha] | @tsv")"
[[ -n "$artifact_info" ]] || { echo 'STOP: diagnostic artifact not found' >&2; exit 1; }
IFS=$'\t' read -r artifact_id artifact_digest artifact_expired artifact_sha <<< "$artifact_info"
[[ "$artifact_id" =~ ^[0-9]+$ && "$artifact_digest" =~ ^sha256:[0-9a-f]{64}$ && \
   "$artifact_expired" == false && "$artifact_sha" == "$EXPECTED_SHA" ]] || {
  echo 'STOP: diagnostic artifact provenance invalid' >&2
  exit 1
}
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
gh run download "$RUN_ID" --repo "$REPO" --name "$ARTIFACT" --dir "$tmp"
mapfile -d '' -t targets < <(find "$tmp" -type f -name 'app-debug.apk' -print0)
mapfile -d '' -t tests < <(find "$tmp" -type f -name 'app-debug-androidTest.apk' -print0)
[[ "${#targets[@]}" -eq 1 && "${#tests[@]}" -eq 1 ]] || {
  echo 'STOP: expected exactly one target/test APK pair' >&2
  exit 1
}
TARGET="${targets[0]}"
TEST="${tests[0]}"
[[ ! -L "$TARGET" && ! -L "$TEST" ]] || exit 1
unzip -tqq "$TARGET"
unzip -tqq "$TEST"
[[ "$("$AAPT" dump badging "$TARGET" | grep -F "package: name='$TARGET_PACKAGE'" | wc -l)" -eq 1 ]] || exit 1
[[ "$("$AAPT" dump badging "$TEST" | grep -F "package: name='$TEST_PACKAGE'" | wc -l)" -eq 1 ]] || exit 1
if unzip -Z1 "$TARGET" | grep -Fq 'libm8-art-registration-diagnostic.so'; then
  echo 'STOP: diagnostic library leaked into target APK' >&2
  exit 1
fi
unzip -Z1 "$TEST" | grep -Fxq "$LIB" || {
  echo 'STOP: isolated ART library missing from instrumentation APK' >&2
  exit 1
}
"$AAPT" dump xmltree "$TEST" AndroidManifest.xml > "$tmp/manifest.txt"
grep -Fq 'E: instrumentation' "$tmp/manifest.txt"
grep -Fq 'android:name(0x01010003)="androidx.test.runner.AndroidJUnitRunner"' "$tmp/manifest.txt"
grep -Fq "android:targetPackage(0x01010021)=\"$TARGET_PACKAGE\"" "$tmp/manifest.txt"
unzip -p "$TEST" "$LIB" > "$tmp/probe.so"
readelf -h "$tmp/probe.so" | grep -Eq 'Machine:.*AArch64'
readelf -Ws "$tmp/probe.so" > "$tmp/symbols.txt"
grep -Fq 'Java_io_github_jdawg867_anvildesk_m8_M8ArtRegistrationDiagnostic_runProbe' "$tmp/symbols.txt"
if grep -Eq '[[:space:]]JNI_OnLoad([[:space:]]|$)' "$tmp/symbols.txt"; then
  echo 'STOP: diagnostic unexpectedly exposes JNI_OnLoad' >&2
  exit 1
fi
echo "M8_ART_DIAGNOSTIC_RUN_ID=$RUN_ID"
echo "M8_ART_DIAGNOSTIC_ARTIFACT_ID=$artifact_id"
echo "M8_ART_DIAGNOSTIC_ARTIFACT_DIGEST=$artifact_digest"
echo 'M8_ART_DIAGNOSTIC_ARTIFACT_PREFLIGHT=PASS'
echo 'M8_ART_DIAGNOSTIC_TEST_ONLY_PACKAGE_ISOLATION=PASS'
echo 'M8_ART_DIAGNOSTIC_DEVICE_EXECUTED=false'
echo 'M8_NATIVE_LOAD_AUTHORIZED=false'
