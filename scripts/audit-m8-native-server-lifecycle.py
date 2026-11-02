#!/usr/bin/env python3
"""Inspect pinned M8 X11 server lifecycle; never infer safety from compilation.

This audit deliberately reports BLOCKED until native startup, stop and
thread ownership have a real implementation. It must not authorize JNI loads.
"""
from pathlib import Path
import re
import sys

def audit(source: str) -> list[str]:
    checks = {
        "server_thread_created": r"pthread_create\s*\(\s*&t",
        "xorg_main_executed": r"dix_main\s*\(",
        "server_jvm_thread_attach": r"AttachCurrentThread\s*\(",
    }
    missing = [name for name, pattern in checks.items() if not re.search(pattern, source)]
    if missing:
        raise ValueError("Pinned lifecycle changed: " + ", ".join(missing))
    # This audit recognizes no valid stop primitive in the current integration.
    # The no-TCP patch replaces process exit with thread return, NOT a stop API.
    return [
        "BLOCKED: no explicit request-stop and thread-join lifecycle contract",
        "BLOCKED: JNI start uses process-global argv, thiz and serverEnv",
        "BLOCKED: native thread cannot be proven stopped before host teardown",
        "BLOCKED: JNI_OnLoad and callback behavior not validated on Android",
    ]

def main() -> int:
    if len(sys.argv) != 2:
        print("usage: audit-m8-native-server-lifecycle.py CMD_CPP", file=sys.stderr)
        return 2
    try:
        findings = audit(Path(sys.argv[1]).read_text())
    except (ValueError, OSError) as exc:
        print("STOP: " + str(exc), file=sys.stderr)
        return 1
    for finding in findings:
        print(finding)
    print("M8_NATIVE_LOAD_AUTHORIZED=false")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
