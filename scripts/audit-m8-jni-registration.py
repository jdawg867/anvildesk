#!/usr/bin/env python3
"""Validate the pinned Termux:X11 JNI registration ABI before adapting it to AnvilDesk.

This is an intentionally strict source contract, not evidence of a working native bridge.
"""
from pathlib import Path
import sys

PINNED_CLASSES = (
    "com/termux/x11/CmdEntryPoint",
    "com/termux/x11/LorieView",
    "com/termux/x11/MainActivity",
)

def verify(cmd: str, activity: str) -> None:
    for name, source in ((PINNED_CLASSES[0], cmd),
                         (PINNED_CLASSES[1], activity),
                         (PINNED_CLASSES[2], activity)):
        if source.count(f'FindClass("{name}")') != 1:
            raise ValueError(f"JNI class registration changed: {name}")
    if "registerCmdEntryPointNatives(env);" not in activity:
        raise ValueError("CmdEntryPoint registration no longer chained")
    if "JNI_OnLoad" not in activity:
        raise ValueError("Missing JNI_OnLoad")
    if "RegisterNatives" not in cmd or activity.count("RegisterNatives") < 2:
        raise ValueError("Registration table changed")
    print("PASS: pinned JNI registration classes and call path accounted for")
    print("BLOCKED: AnvilDesk native bridge not yet implemented; do not load libXlorie.so")

def main() -> int:
    if len(sys.argv) != 3:
        print("usage: audit-m8-jni-registration.py CMDENTRYPOINT_CPP ACTIVITY_CPP", file=sys.stderr)
        return 2
    try:
        verify(Path(sys.argv[1]).read_text(), Path(sys.argv[2]).read_text())
    except (OSError, ValueError) as error:
        print(f"STOP: {error}", file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
