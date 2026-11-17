#!/usr/bin/env bash
set -euo pipefail
ROOT="$(mktemp -d)"
trap 'rm -rf -- "$ROOT"' EXIT
mkdir -p "$ROOT/bin" "$ROOT/sdk/build-tools/36.0.0"
touch "$ROOT/sdk/build-tools/36.0.0/aapt"
chmod +x "$ROOT/sdk/build-tools/36.0.0/aapt"
cat > "$ROOT/bin/gh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == api ]] || exit 90
printf '%s\n' "#!/bin/bash" \
 "echo M8_ART_DIAGNOSTIC_ARTIFACT_PREFLIGHT=PASS" \
 "echo M8_ART_DIAGNOSTIC_TEST_ONLY_PACKAGE_ISOLATION=PASS" | base64 -w0
MOCK
cat > "$ROOT/bin/adb" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
echo "$*" >> "$M8_MOCK_ADB_LOG"
[[ "$1" == -s && "$2" == mock-serial ]] || exit 90
shift 2
case "$*" in
  get-state) echo device ;;
  'shell getprop ro.product.model') if [[ "$M8_MOCK_BAD_MODEL" == true ]]; then echo Other; else echo SM-G766U; fi ;;
  'shell getprop ro.build.version.sdk') echo 36 ;;
  'shell getprop ro.product.cpu.abilist') echo arm64-v8a ;;
  'shell pm list packages') if [[ "$M8_MOCK_EXISTING" == true ]]; then echo package:io.github.jdawg867.anvildesk.m8artdiag; fi ;;
  *) echo "STOP: unexpected device command $*" >&2; exit 90 ;;
esac
MOCK
chmod +x "$ROOT/bin/gh" "$ROOT/bin/adb"
export PATH="$ROOT/bin:$PATH" ANDROID_HOME="$ROOT/sdk"
export M8_MOCK_ADB_LOG="$ROOT/adb-log" M8_MOCK_BAD_MODEL=false M8_MOCK_EXISTING=false
SHA=d4e98310819847295f7046121c9deaef2e46e449
RUN=scripts/run-m8-art-registration-samsung-instrumentation.sh
bash -n "$RUN"
bash "$RUN" --run-id 43915706427 --sha "$SHA" > "$ROOT/default"
grep -Fxq M8_ART_DIAGNOSTIC_DEVICE_EXECUTED=false "$ROOT/default"
test ! -s "$M8_MOCK_ADB_LOG"
if bash "$RUN" --run-id 43915706427 --sha "$SHA" --serial mock-serial >/dev/null 2>&1; then
  echo 'STOP: serial alone accepted' >&2; exit 1
fi
test ! -s "$M8_MOCK_ADB_LOG"
bash "$RUN" --run-id 43915706427 --sha "$SHA" --check-device --serial mock-serial > "$ROOT/check"
grep -Fxq M8_ART_DIAGNOSTIC_DEVICE_READ_ONLY_PREFLIGHT=PASS "$ROOT/check"
grep -Fxq M8_ART_DIAGNOSTIC_DEVICE_EXECUTED=false "$ROOT/check"
if grep -Eq ' install | uninstall | instrument ' "$M8_MOCK_ADB_LOG"; then
  echo 'STOP: read-only mode mutated simulated device' >&2; exit 1
fi
: > "$M8_MOCK_ADB_LOG"
if bash "$RUN" --run-id 43915706427 --sha "$SHA" --check-device --run-on-device --serial mock-serial >/dev/null 2>&1; then
  echo 'STOP: mixed modes accepted' >&2; exit 1
fi
test ! -s "$M8_MOCK_ADB_LOG"
if M8_MOCK_BAD_MODEL=true bash "$RUN" --run-id 43915706427 --sha "$SHA" --check-device --serial mock-serial >/dev/null 2>&1; then
  echo 'STOP: wrong model accepted' >&2; exit 1
fi
if M8_MOCK_EXISTING=true bash "$RUN" --run-id 43915706427 --sha "$SHA" --check-device --serial mock-serial >/dev/null 2>&1; then
  echo 'STOP: existing diagnostic accepted' >&2; exit 1
fi
if grep -Eq ' install | uninstall | instrument ' "$M8_MOCK_ADB_LOG"; then
  echo 'STOP: mutation in simulated device path' >&2; exit 1
fi
echo M8_ART_SAMSUNG_SIMULATED_READ_ONLY=PASS
echo M8_ART_SAMSUNG_ACTUAL_DEVICE_EXECUTED=false
echo M8_NATIVE_LOAD_AUTHORIZED=false
