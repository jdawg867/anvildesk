#!/usr/bin/env python3
"""Fail-closed Java JNI declaration check against pinned native registration tables.

This is a static ABI audit, not proof the server can be safely loaded or started.
"""
from pathlib import Path
import re
import sys

JAVA_TYPES = {
    "void": "V", "boolean": "Z", "byte": "B", "int": "I",
    "long": "J", "float": "F", "double": "D",
    "String": "Ljava/lang/String;",
    "Surface": "Landroid/view/Surface;",
    "ParcelFileDescriptor": "Landroid/os/ParcelFileDescriptor;",
    "Object": "Ljava/lang/Object;",
    "byte[]": "[B", "String[]": "[Ljava/lang/String;",
}
CLASSES = {
    "M8CmdEntryPoint": "cmdentrypoint.cpp",
    "M8LorieView": "activity.cpp",
    "M8NativeActivityBridge": "activity.cpp",
}
def declarations(source):
    found = {}
    for match in re.finditer(
        r'public\s+(static\s+)?native\s+(\w+(?:\[\])?)\s+(\w+)\s*\(([^)]*)\)\s*;',
        source,
    ):
        is_static, ret, name, args = match.groups()
        params = []
        for arg in args.split(","):
            arg = arg.strip()
            if arg:
                typ = arg.rsplit(" ", 1)[0].strip()
                if typ not in JAVA_TYPES:
                    raise ValueError(f"Unexpected Java argument type {typ}")
                params.append(JAVA_TYPES[typ])
        if ret not in JAVA_TYPES:
            raise ValueError(f"Unexpected Java return type {ret}")
        key = (name, "(" + "".join(params) + ")" + JAVA_TYPES[ret])
        if key in found:
            raise ValueError(f"Duplicate Java declaration: {key}")
        found[key] = bool(is_static)
    return found

def registration_table(text, declaration):
    at = text.find(declaration)
    if at < 0:
        raise ValueError("Missing native table " + declaration)
    start = text.find("{", at)
    end = text.find("};", start)
    if start < 0 or end < 0:
        raise ValueError("Malformed native table")
    matches = re.findall(r'\{\s*"([^"]+)"\s*,\s*"([^"]+)"', text[start:end])
    if len(matches) != len(set(matches)):
        raise ValueError("Duplicate native methods")
    return set(matches)

def verify(cmd, activity, java):
    tables = {
        "M8CmdEntryPoint": registration_table(cmd, "static JNINativeMethod methods[]"),
        "M8LorieView": registration_table(activity, "static JNINativeMethod methods[]"),
        "M8NativeActivityBridge": registration_table(activity, "static JNINativeMethod mainActivityMethods[]"),
    }
    for cls, native in tables.items():
        declared = declarations(java[cls])
        if set(declared) != native:
            raise ValueError(f"{cls} mismatch: missing={sorted(native - set(declared))}; extra={sorted(set(declared) - native)}")
        print(f"PASS: {cls}: {len(native)} native signatures match")
    if not declarations(java["M8CmdEntryPoint"])[("connected", "()Z")]:
        raise ValueError("CmdEntryPoint connected must be static")
    if not declarations(java["M8LorieView"])[("connected", "(J)Z")]:
        raise ValueError("LorieView connected must be static")
    if not declarations(java["M8NativeActivityBridge"])[("nativeSetLaunchWindowingModeFreeform", "(Ljava/lang/Object;)V")]:
        raise ValueError("Activity bridge method must be static")
    print("PASS: Java declarations match pinned native registration tables; native loading remains BLOCKED")

def main():
    if len(sys.argv) != 6:
        raise SystemExit("usage: verify-m8-java-jni-declarations.py CMD_CPP ACTIVITY_CPP CMD_JAVA VIEW_JAVA BRIDGE_JAVA")
    try:
        contents = [Path(path).read_text() for path in sys.argv[1:]]
        verify(contents[0], contents[1], dict(zip(CLASSES, contents[2:])))
    except (OSError, ValueError, KeyError) as error:
        raise SystemExit(f"STOP: {error}")

if __name__ == "__main__":
    main()
