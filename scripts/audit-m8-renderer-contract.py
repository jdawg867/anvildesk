#!/usr/bin/env python3
"""Audit pinned M8 renderer Java fields/callbacks; never authorize native loading.

A green audit means the missing runtime contract is *recorded*, not implemented.
No JNI, Android APIs, or shared libraries are executed here.
"""
from pathlib import Path
import re
import sys

NATIVE_REQUIREMENTS = {
    "view_activity_field": (
        '"activity", "Lio/github/jdawg867/anvildesk/m8/M8NativeActivityBridge;"',
        r"\\bM8NativeActivityBridge\\s+activity\\s*;",
    ),
    "view_reset_ime": (
        '"resetIme", "()V"',
        r"\\bvoid\\s+resetIme\\s*\\(\\s*\\)",
    ),
    "activity_connection_callback": (
        '"clientConnectedStateChanged", "()V"',
        r"\\bvoid\\s+clientConnectedStateChanged\\s*\\(\\s*\\)",
    ),
}


def audit(native: str, view: str, activity: str) -> dict[str, bool]:
    result = {}
    for label, (native_marker, java_pattern) in NATIVE_REQUIREMENTS.items():
        if native.count(native_marker) != 1:
            raise ValueError("Pinned native renderer contract changed: " + label)
        java = activity if label == "activity_connection_callback" else view
        result[label] = re.search(java_pattern, java) is not None
    return result


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: audit-m8-renderer-contract.py PATCHED_ACTIVITY_CPP M8_LORIE_VIEW_JAVA M8_ACTIVITY_BRIDGE_JAVA", file=sys.stderr)
        return 2
    try:
        native, view, activity = (Path(p).read_text() for p in sys.argv[1:])
        result = audit(native, view, activity)
    except (OSError, ValueError) as exc:
        print("STOP: " + str(exc), file=sys.stderr)
        return 1
    for name, present in result.items():
        print("M8_RENDERER_" + name.upper() + "=" + ("present_unverified" if present else "missing"))
    print("M8_RENDERER_CONTRACT_COMPLETE=" + str(all(result.values())).lower())
    print("M8_RENDERER_RUNTIME_VERIFIED=false")
    print("M8_NATIVE_LOAD_AUTHORIZED=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
