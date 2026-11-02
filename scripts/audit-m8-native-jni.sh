#!/usr/bin/env bash
# Audit, not integration: record native entry points and reject a falsely "ready" M8.
set -euo pipefail
: "${M8_LIB:?Set M8_LIB to the pinned patched arm64 libXlorie.so}"
test -s "$M8_LIB" || { echo "STOP: missing native X11 library"; exit 1; }
command -v readelf >/dev/null || { echo "STOP: readelf missing"; exit 1; }
command -v nm >/dev/null || { echo "STOP: nm missing"; exit 1; }
file "$M8_LIB"
readelf -h "$M8_LIB" | grep -Eq 'Machine:.*AArch64' || {
  echo "STOP: native X11 library is not ARM64"; exit 1;
}
echo "===== NATIVE JNI EXPORTS ====="
nm -D --defined-only "$M8_LIB" | grep -E 'Java_com_termux_x11_|JNI_OnLoad' | tee m8-native-jni-exports.txt || true
echo "===== TERMUX-SPECIFIC CLASS REFERENCES ====="
# ELF strings only: never interpret this as exhaustive source inspection.
strings "$M8_LIB" | grep -E 'com/termux/x11|com\.termux\.x11' | sort -u | tee m8-native-termux-references.txt || true
test -s m8-native-jni-exports.txt || {
  echo "STOP: no observable native JNI entry points; integration assumptions invalid"; exit 1;
}
echo "AUDIT: native artifacts inspected. No claim of functional AnvilDesk JNI bridge."
