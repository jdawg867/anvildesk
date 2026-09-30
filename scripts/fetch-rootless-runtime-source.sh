#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_ROOT="${ANVILDESK_THIRD_PARTY_DIR:-${ROOT_DIR}/build/third_party/rootless-runtime}"

PR_REPO="https://github.com/oonid/pr.git"
PR_COMMIT="fcf25cb2396361f0be2edfc96fdd61a6e738c9d9"

SAMBA_REPO="https://github.com/samba-team/samba.git"
SAMBA_COMMIT="2f8dfde1210395175e726455bdb63a7b97245a72"

fetch_exact_commit() {
    local repo="$1"
    local commit="$2"
    local destination="$3"
    shift 3
    local sparse_paths=("$@")

    rm -rf "$destination"
    mkdir -p "$(dirname "$destination")"

    git init -q "$destination"
    git -C "$destination" remote add origin "$repo"

    if ((${#sparse_paths[@]} > 0)); then
        git -C "$destination" sparse-checkout init --cone
        git -C "$destination" sparse-checkout set "${sparse_paths[@]}"
    fi

    git -C "$destination" fetch \
        --quiet \
        --no-tags \
        --depth=1 \
        --filter=blob:none \
        origin "$commit"

    git -C "$destination" checkout --quiet --detach FETCH_HEAD

    local actual
    actual="$(git -C "$destination" rev-parse HEAD)"
    if [[ "$actual" != "$commit" ]]; then
        echo "Pinned source mismatch for $repo: expected $commit, got $actual" >&2
        exit 1
    fi
}

echo "Fetching pinned Android PRoot source..."
fetch_exact_commit \
    "$PR_REPO" \
    "$PR_COMMIT" \
    "${DEST_ROOT}/pr" \
    src/proot docs scripts

echo "Fetching pinned talloc source..."
fetch_exact_commit \
    "$SAMBA_REPO" \
    "$SAMBA_COMMIT" \
    "${DEST_ROOT}/samba" \
    lib/talloc

test -f "${DEST_ROOT}/pr/src/proot/src/GNUmakefile"
test -f "${DEST_ROOT}/pr/src/proot/src/loader/loader.c"
test -f "${DEST_ROOT}/pr/LICENSE"
test -f "${DEST_ROOT}/samba/lib/talloc/talloc.c"
test -f "${DEST_ROOT}/samba/lib/talloc/talloc.h"

printf 'PRoot integration source: %s\n' \
    "$(git -C "${DEST_ROOT}/pr" rev-parse HEAD)"
printf 'Samba/talloc source:      %s\n' \
    "$(git -C "${DEST_ROOT}/samba" rev-parse HEAD)"
