#!/usr/bin/env python3
"""Fail-closed source parity check for the inert M8 Java/native command shape.

This is NOT proof that JNI registration, Lorie, or Android runtime works.
"""
from pathlib import Path
import re
import sys

JAVA = {
    "COMMAND_VIEWPORT": 1,
    "COMMAND_REFRESH": 2,
    "RESULT_APPLIED": 0,
    "RESULT_STALE_HANDLE": 1,
    "RESULT_INVALID_REQUEST": 2,
    "RESULT_BACKEND_REJECTED": 3,
    "MAX_VIEWPORT_DIMENSION": 16384,
    "VERSION": 1,
}
CPP_COMMANDS = {"Viewport": 1, "Refresh": 2}
CPP_RESULTS = {"Applied": 0, "StaleHandle": 1, "InvalidRequest": 2, "BackendRejected": 3}


def enum_values(source: str, enum: str) -> dict[str, int]:
    match = re.search(r"enum\s+class\s+" + re.escape(enum)
                      + r"\s*:\s*std::uint32_t\s*\{([^}]*)\}", source)
    if not match:
        raise ValueError("native enum missing: " + enum)
    result = {}
    for name, value in re.findall(r"\b([A-Za-z_]+)\s*=\s*(\d+)\b", match.group(1)):
        result[name] = int(value)
    return result


def verify(java: str, native: str) -> None:
    # Exact anchored integer assignments; no aliases/overrides.
    actual = dict((name, int(value)) for name, value in
                  re.findall(r"static\s+final\s+int\s+(\w+)\s*=\s*(\d+)\s*;", java))
    if actual != JAVA:
        raise ValueError("Java command constants drifted")
    if enum_values(native, "RendererCommand") != CPP_COMMANDS:
        raise ValueError("native command codes drifted")
    if enum_values(native, "RendererCommandResult") != CPP_RESULTS:
        raise ValueError("native result codes drifted")
    if not re.search(r"width\s*>\s*16384\s*\|\|\s*height\s*>\s*16384", native):
        raise ValueError("native viewport bound drifted")
    if not re.search(r"width\s*<=\s*MAX_VIEWPORT_DIMENSION", java) or not re.search(
            r"height\s*<=\s*MAX_VIEWPORT_DIMENSION", java):
        raise ValueError("Java viewport bound drifted")
    if not re.search(r"version\s*!=\s*VERSION\s*\|\|\s*handle\s*<=\s*0"
                     r"\s*\|\|\s*generation\s*<=\s*0", java):
        raise ValueError("Java version/handle validation drifted")
    # No executable JNI path may be slipped into this contract.
    for forbidden in ("System.loadLibrary(", "Runtime.getRuntime()",
                      "JNI_OnLoad", "libXlorie.so"):
        if forbidden in java:
            raise ValueError("executable native path in inert contract: " + forbidden)
    if re.search(r"\bnative\s+(?:int|void|long|boolean|byte|short|float|double|String)\s+\w+\s*\(", java):
        raise ValueError("JNI native method declared in inert contract")
    if "registry_.acquire(handle)" not in native:
        raise ValueError("native lease admission removed")
    if "lease.get()->generation() != generation" not in native:
        raise ValueError("native generation check removed")


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: verify-m8-renderer-command-parity.py JAVA_CONTRACT CPP_BOUNDARY", file=sys.stderr)
        return 2
    try:
        verify(Path(sys.argv[1]).read_text(), Path(sys.argv[2]).read_text())
    except (OSError, ValueError) as error:
        print("STOP: " + str(error), file=sys.stderr)
        return 1
    print("M8_JAVA_NATIVE_COMMAND_SOURCE_PARITY=PASS")
    print("M8_REAL_JNI_COMMAND_DISPATCH_VERIFIED=false")
    print("M8_NATIVE_LOAD_AUTHORIZED=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
