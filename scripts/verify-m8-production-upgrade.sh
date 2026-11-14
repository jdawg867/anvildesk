#!/usr/bin/env bash
# Read-only candidate-to-installed APK signature gate. NEVER installs/uninstalls/clears apps.
set -euo pipefail

: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the exact ADB serial}"
: "${CANDIDATE_APK:?Set CANDIDATE_APK to a signed production-ID candidate APK}"
PKG="io.github.jdawg867.anvildesk"

[[ -s "$CANDIDATE_APK" ]] || { echo "STOP: candidate APK missing"; exit 1; }
command -v adb >/dev/null || { echo "STOP: adb missing"; exit 1; }
[[ "$(adb -s "$ANDROID_SERIAL" get-state)" == "device" ]] || { echo "STOP: device unavailable"; exit 1; }
MODEL="$(adb -s "$ANDROID_SERIAL" shell getprop ro.product.model | tr -d '\r')"
[[ "$MODEL" == "SM-G766U" ]] || { echo "STOP: unexpected model $MODEL"; exit 1; }

APKSIGNER="$(command -v apksigner || true)"
if [[ -z "$APKSIGNER" ]]; then
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" /usr/local/lib/android/sdk /usr/lib/android-sdk; do
        [[ -n "$sdk" && -d "$sdk/build-tools" ]] || continue
        APKSIGNER="$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner | sort -V | tail -1)"
        [[ -n "$APKSIGNER" ]] && break
    done
fi
[[ -n "$APKSIGNER" && -x "$APKSIGNER" ]] || {
    echo "STOP: apksigner unavailable; no installation attempted"
    exit 1
}

TMP="$(mktemp -d)"
trap 'rm -rf -- "$TMP"' EXIT
mapfile -t paths < <(adb -s "$ANDROID_SERIAL" shell pm path "$PKG" | tr -d '\r' | sed -n 's/^package://p')
[[ "${#paths[@]}" -eq 1 && "${paths[0]}" == */base.apk ]] || {
    echo "STOP: expected one production base.apk, got ${#paths[@]}"
    exit 1
}
adb -s "$ANDROID_SERIAL" pull "${paths[0]}" "$TMP/installed.apk" >/dev/null
"$APKSIGNER" verify --print-certs "$TMP/installed.apk" > "$TMP/installed-cert.txt"
"$APKSIGNER" verify --print-certs "$CANDIDATE_APK" > "$TMP/candidate-cert.txt"

extract_sha() {
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$1" |
        tr '[:lower:]' '[:upper:]' | tr -d ':[:space:]'
}
INSTALLED="$(extract_sha "$TMP/installed-cert.txt")"
CANDIDATE="$(extract_sha "$TMP/candidate-cert.txt")"
[[ -n "$INSTALLED" && -n "$CANDIDATE" ]] || {
    echo "STOP: cannot determine both signing certificates"
    exit 1
}
echo "Installed signer SHA256: $INSTALLED"
echo "Candidate signer SHA256: $CANDIDATE"
[[ "$INSTALLED" == "$CANDIDATE" ]] || { echo "STOP: signature mismatch; DO NOT upgrade"; exit 1; }

# Validate candidate package identity independently if aapt is available.
AAPT="${APKSIGNER%/apksigner}/aapt"
[[ -x "$AAPT" ]] || { echo "STOP: aapt unavailable; package identity unverified"; exit 1; }
NAME="$("$AAPT" dump badging "$CANDIDATE_APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
[[ "$NAME" == "$PKG" ]] || { echo "STOP: candidate package is $NAME"; exit 1; }

echo "PASS: correct package, matching APK signers; no device modifications made"
