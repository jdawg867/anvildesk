#!/usr/bin/env bash
# Audit only: inspect the pinned patched ARM64 X11 library without loading it.
set -euo pipefail
: "${M8_LIB:?Set M8_LIB to the pinned patched arm64 libXlorie.so}"
test -s "$M8_LIB" || { echo "STOP: missing native X11 library" >&2; exit 1; }
for tool in readelf nm file strings; do
  command -v "$tool" >/dev/null || { echo "STOP: missing $tool" >&2; exit 1; }
done
file "$M8_LIB"
readelf -h "$M8_LIB" | grep -Eq 'Machine:.*AArch64' || {
  echo "STOP: native X11 library is not ARM64" >&2; exit 1;
}

echo "===== ARM64 ELF LINKAGE ====="
readelf -d "$M8_LIB" | tee m8-native-elf-dynamic.txt
readelf -lW "$M8_LIB" | tee m8-native-elf-program-headers.txt
readelf -d "$M8_LIB" | grep -F '(NEEDED)' | tee m8-native-needed.txt || true

echo "===== JNI_OnLoad DYNAMIC SYMBOL ====="
readelf --dyn-syms -W "$M8_LIB" |
  awk '$4 == "FUNC" && $5 == "GLOBAL" && $7 != "UND" && $8 == "JNI_OnLoad" {print}' |
  tee m8-native-jni-onload-export.txt
test -s m8-native-jni-onload-export.txt || {
  echo "STOP: JNI_OnLoad is not an exported dynamic function" >&2; exit 1;
}
if grep -Eq '[(]TEXTREL[)]|[(]FLAGS[)].*TEXTREL' m8-native-elf-dynamic.txt; then
  echo "STOP: native X11 ELF requires text relocations" >&2; exit 1;
fi
if grep -Eq '^[[:space:]]*GNU_STACK.*RWE' m8-native-elf-program-headers.txt; then
  echo "STOP: native X11 ELF requests executable stack" >&2; exit 1;
fi

echo "===== NATIVE JNI EXPORTS ====="
nm -D --defined-only "$M8_LIB" |
  grep -E 'Java_com_termux_x11_|JNI_OnLoad' |
  tee m8-native-jni-exports.txt || true
test -s m8-native-jni-exports.txt || {
  echo "STOP: no native JNI exports" >&2; exit 1;
}

echo "===== TERMUX-SPECIFIC CLASS REFERENCES ====="
# ELF strings are only supporting evidence, never exhaustive source inspection.
strings "$M8_LIB" |
  grep -E 'com/termux/x11|com[.]termux[.]x11' |
  sort -u | tee m8-native-termux-references.txt || true

echo "M8_ELF_LINKAGE_REVIEW_REQUIRED=true"
echo "M8_NATIVE_LOAD_AUTHORIZED=false"
echo "AUDIT: native ELF inspected; no claim of functional AnvilDesk JNI bridge."
