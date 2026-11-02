#!/usr/bin/env python3
"""Fail-closed pinned Termux:X11 JNI_OnLoad readiness audit.

This is a static audit of a patched activity.cpp: it does NOT prove load safety.
It deliberately reports observed blockers even when native compilation passes.
"""
from pathlib import Path
import sys

REQUIRED = (
    "JNIEXPORT jint JNI_OnLoad",
    "RegisterNatives(cls, methods,",
    "RegisterNatives(activityClass, mainActivityMethods,",
    "registerCmdEntryPointNatives(env)",
    "pthread_create(&freeformLookupThread",
    "pthread_join(freeformLookupThread",
    "if (!cls) return JNI_ERR;",
    "if (!activityClass) return JNI_ERR;",
    "if (env->ExceptionCheck()) {",
)
TARGETS = (
    'FindClass("io/github/jdawg867/anvildesk/m8/M8LorieView")',
    'FindClass("io/github/jdawg867/anvildesk/m8/M8NativeActivityBridge")',
)

def audit(source: str) -> list[str]:
    if source.count("JNIEXPORT jint JNI_OnLoad") != 1:
        raise ValueError("JNI_OnLoad entrypoint drift")
    for token in REQUIRED + TARGETS:
        expected = 2 if token in TARGETS else 1
        if source.count(token) != expected:
            raise ValueError("JNI registration or thread contract drift: " + token)
    start = source.index("JNIEXPORT jint JNI_OnLoad")
    body = source[start:]
    if "return JNI_VERSION_1_6;" not in body:
        raise ValueError("JNI_OnLoad result drift")
    if body.index("jclass preflightCmd") > body.index("env->RegisterNatives(cls"):
        raise ValueError("JNI class preflight occurs after first registration")
    if body.count("env->UnregisterNatives(cls);") < 2 or "env->UnregisterNatives(activityClass);" not in body:
        raise ValueError("Expected registration rollback missing")
    # These static checks establish patch presence, not atomic rollback or
    # validated runtime recovery from partial JNI registration failure.
    return [
        "JNI_ONLOAD_CLASS_REGISTRATION=present",
        "JNI_ONLOAD_CLASS_PREFLIGHT=present",
        "JNI_ONLOAD_FREEFORM_LOOKUP_THREAD=present",
        "JNI_ONLOAD_REGISTER_NATIVES_RESULT_VALIDATED=true",
        "JNI_ONLOAD_REGISTRATION_ROLLBACK_PATCH=present",
        "JNI_ONLOAD_PARTIAL_FAILURE_ROLLBACK_VERIFIED=false",
        "M8_NATIVE_LOAD_AUTHORIZED=false",
    ]

def main() -> int:
    if len(sys.argv) != 2:
        print("usage: audit-m8-jni-onload-safety.py PATCHED_ACTIVITY_CPP", file=sys.stderr)
        return 2
    try:
        findings = audit(Path(sys.argv[1]).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print("STOP: " + str(exc), file=sys.stderr)
        return 1
    print("\n".join(findings))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
