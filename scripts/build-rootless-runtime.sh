#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
THIRD_PARTY_ROOT="${ANVILDESK_THIRD_PARTY_DIR:-${ROOT_DIR}/build/third_party/rootless-runtime}"
PR_ROOT="${THIRD_PARTY_ROOT}/pr"
PROOT_SRC="${PR_ROOT}/src/proot"
TALLOC_SRC="${THIRD_PARTY_ROOT}/samba/lib/talloc"
TALLOC_STUB="${PROOT_SRC}/lib/talloc"

NDK_VERSION="27.2.12479018"
NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-}/ndk/${NDK_VERSION}}"
API_LEVEL=28
TRIPLE="aarch64-linux-android"

WORK_DIR="${ROOT_DIR}/build/rootless-runtime/arm64-v8a"
JNI_ROOT="${ROOT_DIR}/app/build/generated/rootless-runtime/jniLibs"
JNI_DIR="${JNI_ROOT}/arm64-v8a"
PROVENANCE_DIR="${ROOT_DIR}/build/rootless-runtime/provenance"

if [[ ! -f "${PROOT_SRC}/src/GNUmakefile" || ! -f "${TALLOC_SRC}/talloc.c" ]]; then
    "${ROOT_DIR}/scripts/fetch-rootless-runtime-source.sh"
fi

if [[ ! -d "$NDK_ROOT" ]]; then
    echo "Android NDK ${NDK_VERSION} not found at: ${NDK_ROOT}" >&2
    exit 1
fi

TOOLCHAIN="${NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64"
BIN="${TOOLCHAIN}/bin"
SYSROOT="${TOOLCHAIN}/sysroot"
CC="${BIN}/${TRIPLE}${API_LEVEL}-clang"
AR="${BIN}/llvm-ar"
RANLIB="${BIN}/llvm-ranlib"
STRIP="${BIN}/llvm-strip"
OBJCOPY="${BIN}/llvm-objcopy"
OBJDUMP="${BIN}/llvm-objdump"
READELF="${BIN}/llvm-readelf"

for tool in "$CC" "$AR" "$RANLIB" "$STRIP" "$OBJCOPY" "$OBJDUMP" "$READELF"; do
    [[ -x "$tool" ]] || { echo "Required NDK tool missing: $tool" >&2; exit 1; }
done

rm -rf "$WORK_DIR" "$JNI_ROOT" "$PROVENANCE_DIR"
mkdir -p "$WORK_DIR" "$JNI_DIR" "$PROVENANCE_DIR"

TALLOC_BUILD="${WORK_DIR}/talloc"
mkdir -p "$TALLOC_BUILD"

"$CC" \
    --sysroot="$SYSROOT" \
    -I"$TALLOC_STUB" \
    -I"$TALLOC_SRC" \
    -DNO_CONFIG_H=1 \
    -D__STDC_WANT_LIB_EXT1__=1 \
    -O2 -Wall -Wextra \
    -c "${TALLOC_SRC}/talloc.c" \
    -o "${TALLOC_BUILD}/talloc.o"

"$AR" rcs "${TALLOC_BUILD}/libtalloc.a" "${TALLOC_BUILD}/talloc.o"
"$RANLIB" "${TALLOC_BUILD}/libtalloc.a"

pushd "${PROOT_SRC}/src" >/dev/null
make -f GNUmakefile clean >/dev/null 2>&1 || true
make -f GNUmakefile \
    CC="$CC" \
    STRIP="$STRIP" \
    OBJCOPY="$OBJCOPY" \
    OBJDUMP="$OBJDUMP" \
    CFLAGS="-Wall -Wextra -O2 --sysroot=${SYSROOT} -I${TALLOC_STUB} -I${TALLOC_SRC}" \
    LDFLAGS="--sysroot=${SYSROOT} -L${TALLOC_BUILD} -ltalloc -static -Wl,-z,noexecstack,-z,max-page-size=16384" \
    GIT=true \
    proot loader/loader
popd >/dev/null

PROOT_BINARY="${PROOT_SRC}/src/proot"
LOADER_BINARY="${PROOT_SRC}/src/loader/loader"
[[ -f "$PROOT_BINARY" ]] || { echo "PRoot build output missing" >&2; exit 1; }
[[ -f "$LOADER_BINARY" ]] || { echo "PRoot loader build output missing" >&2; exit 1; }

fix_tls_alignment() {
    local binary="$1"
    local align_hex
    align_hex="$($READELF -W -l "$binary" | awk '/^[[:space:]]*TLS/{print $NF; exit}' | sed 's/^0x//')"
    [[ -n "$align_hex" ]] || return 0

    local align=$((16#$align_hex))
    if (( align >= 64 )); then
        return 0
    fi

    python3 - "$binary" <<'PY'
import struct
import sys

path = sys.argv[1]
with open(path, "rb") as handle:
    data = bytearray(handle.read())

if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
    raise SystemExit("expected little-endian ELF64")

e_phoff = struct.unpack_from("<Q", data, 32)[0]
e_phentsize = struct.unpack_from("<H", data, 54)[0]
e_phnum = struct.unpack_from("<H", data, 56)[0]

PT_TLS = 7
for index in range(e_phnum):
    offset = e_phoff + index * e_phentsize
    if struct.unpack_from("<I", data, offset)[0] == PT_TLS:
        struct.pack_into("<Q", data, offset + 48, 64)
        break
else:
    raise SystemExit("TLS program header disappeared")

with open(path, "wb") as handle:
    handle.write(data)
PY
}

fix_tls_alignment "$PROOT_BINARY"

install -m 0755 "$PROOT_BINARY" "${JNI_DIR}/libanvildesk-proot.so"
install -m 0755 "$LOADER_BINARY" "${JNI_DIR}/libanvildesk-proot-loader.so"

verify_elf() {
    local file="$1"
    local header
    header="$(file "$file")"
    echo "$header"
    grep -q "ELF 64-bit" <<<"$header"
    grep -Eq "ARM aarch64|aarch64" <<<"$header"

    "$READELF" -h "$file" | grep -q "Machine:.*AArch64"
    if "$READELF" -d "$file" 2>/dev/null | grep -q "NEEDED"; then
        echo "Unexpected dynamic dependency in $file" >&2
        "$READELF" -d "$file" >&2
        exit 1
    fi

    while read -r align; do
        [[ -z "$align" ]] && continue
        local value=$((align))
        if (( value < 16384 )); then
            echo "LOAD segment alignment below 16 KiB in $file: $align" >&2
            exit 1
        fi
    done < <("$READELF" -W -l "$file" | awk '/^[[:space:]]*LOAD/{print $NF}')
}

verify_elf "${JNI_DIR}/libanvildesk-proot.so"
verify_elf "${JNI_DIR}/libanvildesk-proot-loader.so"

(
    cd "$ROOT_DIR"
    sha256sum \
        "app/build/generated/rootless-runtime/jniLibs/arm64-v8a/libanvildesk-proot.so" \
        "app/build/generated/rootless-runtime/jniLibs/arm64-v8a/libanvildesk-proot-loader.so" \
        > "build/rootless-runtime/provenance/SHA256SUMS"
)

cat > "${PROVENANCE_DIR}/SOURCE.txt" <<EOF
AnvilDesk rootless runtime
PRoot integration source: https://github.com/oonid/pr.git
PRoot integration commit: fcf25cb2396361f0be2edfc96fdd61a6e738c9d9
Samba/talloc source: https://github.com/samba-team/samba.git
Samba/talloc commit: 2f8dfde1210395175e726455bdb63a7b97245a72
Android NDK: ${NDK_VERSION}
Android API used for native compilation: ${API_LEVEL}
EOF

cat "${PROVENANCE_DIR}/SOURCE.txt"
cat "${PROVENANCE_DIR}/SHA256SUMS"
