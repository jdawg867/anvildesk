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
echo "===== ARM64 ELF LINKAGE ====="
readelf -d "$M8_LIB" | tee m8-native-elf-dynamic.txt
readelf -lW "$M8_LIB" | tee m8-native-elf-program-headers.txt
readelf -d "$M8_LIB" | grep -F '(NEEDED)' | tee m8-native-needed.txt || true
# JNI_OnLoad must be a dynamic export, not merely present in local symbols.
readelf -Ws "$M8_LIB" | grep -E 'GLOBAL +DEFAULT +[0-9]+ +JNI_OnLoad
nm -D --defined-only "$M8_LIB" | grep -E 'Java_com_termux_x11_|JNI_OnLoad' | tee m8-native-jni-exports.txt || true
echo "===== TERMUX-SPECIFIC CLASS REFERENCES ====="
# ELF strings only: never interpret this as exhaustive source inspection.
strings "$M8_LIB" | grep -E 'com/termux/x11|com\.termux\.x11' | sort -u | tee m8-native-termux-references.txt || true
test -s m8-native-jni-exports.txt || {
  echo "STOP: no observable native JNI entry points; integration assumptions invalid"; exit 1;
}
echo "AUDIT: native artifacts inspected. No claim of functional AnvilDesk JNI bridge."
 \
  | tee m8-native-jni-onload-export.txt
test -s m8-native-jni-onload-export.txt || {
  echo "STOP: JNI_OnLoad is not a visible dynamic symbol"; exit 1;
}
if grep -E '\\(TEXTREL\\)|\\(FLAGS\\).*TEXTREL' m8-native-elf-dynamic.txt; then
  echo "STOP: native X11 ELF requires text relocations"; exit 1;
fi
if grep -E '^  GNU_STACK.*RWE' m8-native-elf-program-headers.txt; then
  echo "STOP: native X11 ELF requests executable stack"; exit 1;
fi
echo "M8_ELF_LINKAGE_REVIEW_REQUIRED=true"
echo "M8_NATIVE_LOAD_AUTHORIZED=false"
echo "===== NATIVE JNI EXPORTS ====="
nm -D --defined-only "$M8_LIB" | grep -E 'Java_com_termux_x11_|JNI_OnLoad' | tee m8-native-jni-exports.txt || true
echo "===== TERMUX-SPECIFIC CLASS REFERENCES ====="
# ELF strings only: never interpret this as exhaustive source inspection.
strings "$M8_LIB" | grep -E 'com/termux/x11|com\.termux\.x11' | sort -u | tee m8-native-termux-references.txt || true
test -s m8-native-jni-exports.txt || {
  echo "STOP: no observable native JNI entry points; integration assumptions invalid"; exit 1;
}
echo "AUDIT: native artifacts inspected. No claim of functional AnvilDesk JNI bridge."
