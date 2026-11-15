#!/usr/bin/env python3
"""Read-only M8 production load boundary guard.

Only verifies the *current inert implementation*. It never grants permission
to load libXlorie, and must be manually re-audited before any promotion.
"""
from pathlib import Path
import re
import sys

from importlib.util import module_from_spec, spec_from_file_location


def method_body(source: str, signature: str) -> str:
    """Extract one no-nested-braces Java method by an exact declaration."""
    if source.count(signature) != 1:
        raise ValueError("M8 policy method declaration drift: " + signature)
    tail = source.split(signature, 1)[1]
    open_brace = tail.find("{")
    if open_brace < 0:
        raise ValueError("M8 policy method lacks body")
    depth = 0
    for index in range(open_brace, len(tail)):
        if tail[index] == "{":
            depth += 1
        elif tail[index] == "}":
            depth -= 1
            if depth == 0:
                return tail[open_brace + 1:index]
    raise ValueError("M8 policy method unbalanced")


def verify_policy(source: str) -> None:
    if source.count("public final class M8NativeLoadingPolicy") != 1:
        raise ValueError("production native policy class drift")
    if not re.search(r"enum\s+Status\s*\{\s*NOT_AUTHORIZED\s*\}", source):
        raise ValueError("production loading status changed")
    methods = (
        ("public static Status evaluate(M8NativeLoadGate.Evidence evidence)",
         "return Status.NOT_AUTHORIZED;"),
        ("public static boolean mayLoadLibrary(M8NativeLoadGate.Evidence evidence)",
         "return false;"),
    )
    for signature, expected in methods:
        body = method_body(source, signature)
        # Current implementation must be strictly unconditional, with no
        # alternate branches, invocation or configurable permission path.
        statements = re.sub(r"/\*.*?\*/|//[^\n]*", "", body, flags=re.S).strip()
        if statements != expected:
            raise ValueError("production native loading policy not sealed: " + signature)
    if "System.loadLibrary(" in source or "Runtime.getRuntime().load(" in source:
        raise ValueError("production native policy now loads a library")


def verify(manifest: str, service: str, policy: str, verifier: str) -> None:
    verify_policy(policy)
    spec = spec_from_file_location("m8_secondary_process_guard", verifier)
    if spec is None or spec.loader is None:
        raise ValueError("M8 service verifier unavailable")
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    module.verify(manifest, service)


def main() -> int:
    if len(sys.argv) != 5:
        print("usage: verify-m8-native-promotion-block.py MANIFEST SERVICE_JAVA POLICY_JAVA SERVICE_VERIFIER", file=sys.stderr)
        return 2
    try:
        verify(*(Path(argument).read_text() if index != 4 else argument
                 for index, argument in enumerate(sys.argv[1:], 1)))
    except (OSError, ValueError, SyntaxError) as exc:
        print("STOP: " + str(exc), file=sys.stderr)
        return 1
    print("M8_PRODUCTION_NATIVE_POLICY_SEALED=PASS")
    print("M8_SECONDARY_SERVICE_DISABLED=PASS")
    print("M8_NATIVE_LOAD_AUTHORIZED=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
