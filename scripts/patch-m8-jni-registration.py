#!/usr/bin/env python3
"""Patch the pinned native JNI registry to resolve AnvilDesk-owned classes.

This is a build-time source transformation only; it does NOT enable native
startup, replace upstream Java implementations, or make an installable desktop.
"""
from pathlib import Path
import sys

REPLACEMENTS = {
    "com/termux/x11/CmdEntryPoint": "io/github/jdawg867/anvildesk/m8/M8CmdEntryPoint",
    "com/termux/x11/LorieView": "io/github/jdawg867/anvildesk/m8/M8LorieView",
    "com/termux/x11/MainActivity": "io/github/jdawg867/anvildesk/m8/M8NativeActivityBridge",
}

def patch(cmd: str, activity: str) -> tuple[str, str]:
    for old, new in REPLACEMENTS.items():
        target = cmd if old.endswith("CmdEntryPoint") else activity
        marker = f'FindClass("{old}")'
        if target.count(marker) != 1:
            raise ValueError(f"Expected exactly one pinned JNI registration: {old}")
        if new in cmd or new in activity:
            raise ValueError(f"Already patched or unexpected class name: {new}")
    cmd = cmd.replace('FindClass("com/termux/x11/CmdEntryPoint")',
                      'FindClass("' + REPLACEMENTS["com/termux/x11/CmdEntryPoint"] + '")')
    for old in ("com/termux/x11/LorieView", "com/termux/x11/MainActivity"):
        activity = activity.replace(f'FindClass("{old}")', f'FindClass("{REPLACEMENTS[old]}")')
    return cmd, activity

def main() -> int:
    if len(sys.argv) != 3:
        print("usage: patch-m8-jni-registration.py CMDENTRYPOINT_CPP ACTIVITY_CPP", file=sys.stderr)
        return 2
    files = [Path(p) for p in sys.argv[1:]]
    try:
        old = [p.read_text() for p in files]
        updated = patch(*old)
        for file, content in zip(files, updated):
            file.write_text(content)
    except (OSError, ValueError) as e:
        print(f"STOP: {e}", file=sys.stderr)
        return 1
    print("PASS: pinned JNI registry class names adapted in isolated upstream source")
    print("NOT FUNCTIONAL: no AnvilDesk Java/native runtime implementation is enabled")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
