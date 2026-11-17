#!/usr/bin/env bash
# Explicit opt-in device-only smoke test for M8's isolated native looper.
# Downloads the CI-built test ELF. No APK install, rootfs, root or PRoot use.
set -euo pipefail

REPO="jdawg867/anvildesk"
RUN_ID="43319679610"
EXPECTED_SHA="8a9767971fd21689bd16e70b4c8a4510f737779a"
ARTIFACT="m8-unified-owner-device-probe-arm64"
BINARY="m8-android-unified-owner-device-probe"
EXPECTED_ARTIFACT_ID="13989353189"
EXPECTED_ARTIFACT_DIGEST="sha256:bbac900b551b143add444fb0904fb37af2a5af2254415a4695a90ab6273288c7"

# Artifact verification is read-only and needs no connected Android device.
# Running code on a phone is a separate explicit opt-in operation.
[[ "$#" -eq 1 ]] && [[ "$1" == "--verify-artifact" || "$1" == "--run-on-device" ]] || {
  echo "Usage: $0 --verify-artifact | --run-on-device" >&2
  exit 2
}
mode="$1"
for tool in gh readelf mktemp; do
  command -v "$tool" >/dev/null || { echo "STOP: missing $tool" >&2; exit 1; }
done
if [[ "$mode" == "--run-on-device" ]]; then
  for tool in adb timeout; do
    command -v "$tool" >/dev/null || { echo "STOP: missing $tool" >&2; exit 1; }
  done
fi

run_sha="$(gh api "repos/$REPO/actions/runs/$RUN_ID" --jq '.head_sha')"
[[ "$run_sha" == "$EXPECTED_SHA" ]] || {
  echo "STOP: GitHub workflow commit does not match pinned probe source" >&2
  exit 1
}
run_status="$(gh api "repos/$REPO/actions/runs/$RUN_ID" --jq '.conclusion')"
[[ "$run_status" == "success" ]] || {
  echo "STOP: pinned workflow did not pass" >&2
  exit 1
}

# Require the exact non-expired GitHub Actions artifact. This checks the
# provenance metadata; gh downloads over authenticated HTTPS. It does not
# independently recompute the artifact ZIP digest after extraction.
artifact_json="$(gh api "repos/$REPO/actions/runs/$RUN_ID/artifacts" \
  --jq ".artifacts[] | select(.name == \"$ARTIFACT\") | [.id, .digest, .expired, .workflow_run.head_sha] | @tsv")"
expected_metadata="$(printf '%s\t%s\tfalse\t%s' \
  "$EXPECTED_ARTIFACT_ID" "$EXPECTED_ARTIFACT_DIGEST" "$EXPECTED_SHA")"
[[ "$artifact_json" == "$expected_metadata" ]] || {
  echo "STOP: expected artifact missing, expired, or provenance mismatch" >&2
  exit 1
}

tmp="$(mktemp -d)"
remote=""
cleanup() {
  if [[ -n "$remote" && -n "${serial:-}" ]]; then
    adb -s "$serial" shell "rm -f '$remote'" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$tmp"
}
trap cleanup EXIT

gh run download "$RUN_ID" --repo "$REPO" --name "$ARTIFACT" --dir "$tmp"
candidate="$tmp/$BINARY"
[[ -f "$candidate" && ! -L "$candidate" ]] || {
  echo "STOP: expected isolated ELF missing from CI artifact" >&2
  exit 1
}
readelf -h "$candidate" | grep -Eq 'Machine:.*AArch64' || {
  echo "STOP: downloaded executable is not AArch64" >&2
  exit 1
}
# The standalone NDK executable must use Android's 64-bit linker and
# system libraries; a non-bundled C++ runtime must not be required.
readelf -l "$candidate" | grep -Fq '/system/bin/linker64' || {
  echo "STOP: executable does not use Android's 64-bit dynamic linker" >&2
  exit 1
}
dependencies="$(readelf -d "$candidate")"
grep -Fq 'libandroid.so' <<< "$dependencies" || {
  echo "STOP: Android native looper dependency missing" >&2
  exit 1
}
grep -Fq 'libc.so' <<< "$dependencies" || {
  echo "STOP: Android libc dependency missing" >&2
  exit 1
}
if grep -Eq 'libc\\+\\+_shared\\.so|libstdc\\+\\+\\.so|GLIBC_' \
     <<< "$dependencies"; then
  echo "STOP: executable depends on unbundled/non-Android C++ runtime" >&2
  exit 1
fi
echo "M8_ARTIFACT_ONLY_PREFLIGHT=PASS"
if [[ "$mode" == "--verify-artifact" ]]; then
  echo "M8_ANDROID_DEVICE_PROBE_EXECUTED=false"
  exit 0
fi

mapfile -t devices < <(adb devices | awk '$2=="device" {print $1}')
[[ "${#devices[@]}" -eq 1 ]] || {
  echo "STOP: exactly one authorized online ADB device is required" >&2
  exit 1
}
serial="${devices[0]}"
model="$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"
[[ "$model" == "SM-G766U" ]] || {
  echo "STOP: refusing to execute on unexpected device model: $model" >&2
  exit 1
}
abi="$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$abi" == "arm64-v8a" ]] || {
  echo "STOP: expected arm64-v8a, got $abi" >&2
  exit 1
}
adb -s "$serial" shell 'command -v timeout >/dev/null' || {
  echo "STOP: Android device lacks timeout; cannot ensure bounded execution" >&2
  exit 1
}

# Local non-privileged, fixed-duration native process. Unique path and
# cleanup ensure no changes to the installed app or Ubuntu rootfs.
remote="/data/local/tmp/anvildesk-m8-unified-owner-probe-${RUN_ID}-$$"
adb -s "$serial" push "$candidate" "$remote" >/dev/null
adb -s "$serial" shell "chmod 700 '$remote'"
echo "Running isolated ARM64 probe on $serial (timeout: 20 seconds)."
# Bound both device-side child runtime and the host-side ADB wait.
timeout 30s adb -s "$serial" shell "timeout 20s '$remote'" | tee "$tmp/output.txt"
grep -Fx 'M8_ANDROID_UNIFIED_OWNER_DEVICE_TEST=PASS' "$tmp/output.txt"
grep -Fx 'M8_ANDROID_UNIFIED_OWNER_POST_JOIN_HEAP_FREE=PASS' "$tmp/output.txt"
grep -Fx 'M8_X11_RENDERER_INTEGRATION_VERIFIED=false' "$tmp/output.txt"
grep -Fx 'M8_NATIVE_LOAD_AUTHORIZED=false' "$tmp/output.txt"
echo "M8_UNIFIED_OWNER_DEVICE_PROBE_SMOKE_TEST=PASS"
