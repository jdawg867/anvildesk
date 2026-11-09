#!/usr/bin/env bash
# Explicit opt-in device-only smoke test for M8's isolated native looper.
# Downloads the CI-built test ELF. No APK install, rootfs, root or PRoot use.
set -euo pipefail

REPO="jdawg867/anvildesk"
RUN_ID="42707334270"
EXPECTED_SHA="fcdcddba50e559585c1d95f2cc24dd3cf7bcf7a1"
ARTIFACT="m8-owned-looper-device-probe-arm64"
BINARY="m8-android-owned-looper-device-probe"

for tool in gh adb readelf mktemp timeout; do
  command -v "$tool" >/dev/null || { echo "STOP: missing $tool" >&2; exit 1; }
done

[[ "${1:-}" == "--run-on-device" && "$#" -eq 1 ]] || {
  echo "This test must be explicitly requested: $0 --run-on-device" >&2
  exit 2
}

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

tmp="$(mktemp -d)"
remote=""
cleanup() {
  if [[ -n "$remote" ]]; then
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
# Local non-privileged, fixed-duration native process. Unique path and
# cleanup ensure no changes to the installed app or Ubuntu rootfs.
remote="/data/local/tmp/anvildesk-m8-looper-probe-${RUN_ID}-$$"
adb -s "$serial" push "$candidate" "$remote" >/dev/null
adb -s "$serial" shell "chmod 700 '$remote'"
echo "Running isolated ARM64 probe on $serial (timeout: 20 seconds)."
# Bound both device-side child runtime and the host-side ADB wait.
timeout 30s adb -s "$serial" shell "timeout 20s '$remote'" | tee "$tmp/output.txt"
grep -Fx 'M8_ANDROID_OWNED_LOOPER_DEVICE_TEST=PASS' "$tmp/output.txt"
grep -Fx 'M8_X11_RENDERER_INTEGRATION_VERIFIED=false' "$tmp/output.txt"
grep -Fx 'M8_NATIVE_LOAD_AUTHORIZED=false' "$tmp/output.txt"
echo "M8_DEVICE_PROBE_SMOKE_TEST=PASS"
