#!/usr/bin/env python3
"""Fail closed if the upstream M8 X11 C++ bridge retains a TCP knock listener.

This is a targeted pre-integration guard, not a comprehensive network audit.
"""
import pathlib
import re
import sys

def inspect(source: str) -> list[str]:
    issues = []
    for pattern, label in (
        (r"\bsocket\s*\(\s*AF_INET\b", "AF_INET socket"),
        (r"\bsocket\s*\(\s*AF_INET6\b", "AF_INET6 socket"),
        (r"\bINADDR_ANY\b", "wildcard network binding"),
        (r"\bstruct\s+sockaddr_in6?\b", "IP socket address"),
        (r"\blorieListenForKnocks\s*\(", "TCP knock/discovery listener"),
    ):
        if re.search(pattern, source):
            issues.append(label)
    return issues

def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify-m8-upstream-no-tcp.py <cmdentrypoint.cpp>", file=sys.stderr)
        return 2
    path = pathlib.Path(sys.argv[1])
    try:
        content = path.read_text(encoding="utf-8")
    except OSError as exc:
        print(f"FAIL: cannot inspect source: {exc}", file=sys.stderr)
        return 2
    issues = inspect(content)
    if issues:
        print("REJECT: upstream TCP-discovery surface found: " + ", ".join(issues))
        return 1
    print("PASS: targeted upstream cmdentrypoint.cpp TCP patterns absent; further audit still required")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
