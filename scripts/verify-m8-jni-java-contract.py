#!/usr/bin/env python3
"""Static, fail-closed ABI declaration audit for the M8 native JNI registration.

Parses the pinned C++ registration tables; all Java adapter signatures must be
reviewed before the native library can ever be loaded. No APK is enabled.
"""
import re
import sys
from pathlib import Path

CLASSES = {
    "M8CmdEntryPoint": "cmdentrypoint.cpp",
    "M8LorieView": "activity.cpp",
    "M8NativeActivityBridge": "activity.cpp",
}
EXPECTED = {
    "M8CmdEntryPoint": {
        ("start", "([Ljava/lang/String;)Z"),
        ("getXConnection", "()Landroid/os/ParcelFileDescriptor;"),
        ("getLogcatOutput", "()Landroid/os/ParcelFileDescriptor;"),
        ("reportFatalError", "(Ljava/lang/String;)V"),
        ("connected", "()Z"),
    },
    "M8NativeActivityBridge": {("nativeSetLaunchWindowingModeFreeform", "(Ljava/lang/Object;)V")},
}
def extract(text, marker):
    start = text.find(marker)
    if start < 0:
        raise ValueError("Missing native registration table " + marker)
    opening = text.find("{", start)
    closing = text.find("};", opening)
    if opening < 0 or closing < 0:
        raise ValueError("Malformed registration table " + marker)
    return set(re.findall(r'\{"([^"]+)",\s*"([^"]+)"', text[opening:closing]))

def verify(cmd, activity):
    registrations = {
        "M8CmdEntryPoint": extract(cmd, "static JNINativeMethod methods[]"),
        "M8LorieView": extract(activity, "static JNINativeMethod methods[]"),
        "M8NativeActivityBridge": extract(activity, "static JNINativeMethod mainActivityMethods[]"),
    }
    for name, expected in EXPECTED.items():
        if registrations[name] != expected:
            raise ValueError(f"Unexpected native ABI for {name}: {registrations[name] ^ expected}")
    for name, methods in registrations.items():
        if not methods:
            raise ValueError("Empty native table: " + name)
        print(f"{name}: {len(methods)} methods")
        for method, signature in sorted(methods):
            print(f"  {method} {signature}")
    print("PASS: Native ABI inventory generated (Java binding NOT yet implemented)")

def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify-m8-jni-java-contract.py CMD_CPP ACTIVITY_CPP")
    try:
        verify(Path(sys.argv[1]).read_text(), Path(sys.argv[2]).read_text())
    except ValueError as error:
        raise SystemExit("STOP: " + str(error))

if __name__ == "__main__":
    main()
