#!/usr/bin/env python3
"""Fail-closed audit of pinned Termux:X11's in-process server lifetime.

An in-process X11 server cannot be approved merely because the upstream
thread's exit() was removed. This audit is deliberately a BLOCKER report.
"""
from pathlib import Path
import re
import sys

THREAD = re.compile(r"pthread_create\s*\(\s*&t\s*,")
MAIN = re.compile(r"\bdix_main\s*\(")
SAFE_RETURN = 'const int serverStatus = dix_main(argc, (char**) argv, (char*[]) { nullptr });'
GLOBAL = ("static JNIEnv* serverEnv", "static jobject thiz", "static int argc", "static char** argv")
UNSAFE = ("pthread_cancel(", "kill(getpid()", "raise(SIGTERM)", "exit(dix_main(")

def verify(source: str) -> list[str]:
    if not THREAD.search(source) or not MAIN.search(source):
        raise ValueError("Pinned server thread entrypoint changed")
    if source.count(SAFE_RETURN) != 1:
        raise ValueError("Expected patched thread return missing or duplicated")
    for token in GLOBAL:
        if source.count(token) != 1:
            raise ValueError("Expected global server state changed: " + token)
    for token in UNSAFE:
        if token in source:
            raise ValueError("Unreviewed process termination or cancellation: " + token)
    return [
        "SERVER_MODEL=in-process pthread with process-global state",
        "THREAD_EXIT_PATCH=present (not a stop mechanism)",
        "STOP_AND_JOIN_VERIFIED=false",
        "IN_PROCESS_RESTART_VERIFIED=false",
        "M8_NATIVE_LOAD_AUTHORIZED=false",
    ]

def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify-m8-server-process-boundary.py CMD_CPP", file=sys.stderr)
        return 2
    try:
        result = verify(Path(sys.argv[1]).read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        print("STOP: " + str(error), file=sys.stderr)
        return 1
    print("\n".join(result))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
