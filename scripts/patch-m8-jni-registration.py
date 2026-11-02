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
    # Check lookups and registrations without changing the exported helper
    # signature declared in upstream lorie.h.
    old_cmd = '''    jclass cls = env->FindClass("io/github/jdawg867/anvildesk/m8/M8CmdEntryPoint");
    env->RegisterNatives(cls, methods, sizeof(methods)/sizeof(methods[0]));'''
    new_cmd = '''    jclass cls = env->FindClass("io/github/jdawg867/anvildesk/m8/M8CmdEntryPoint");
    if (!cls) return; // Preserve pending ClassNotFoundException.
    if (env->RegisterNatives(cls, methods, sizeof(methods)/sizeof(methods[0])) != JNI_OK) {
        if (!env->ExceptionCheck())
            env->ThrowNew(env->FindClass("java/lang/UnsatisfiedLinkError"),
                          "M8 command JNI registration failed");
        return;
    }'''
    if cmd.count(old_cmd) != 1:
        raise ValueError("Pinned command JNI registration structure changed")
    cmd = cmd.replace(old_cmd, new_cmd)
    old_view = '''    jclass cls = env->FindClass("io/github/jdawg867/anvildesk/m8/M8LorieView");
    env->RegisterNatives(cls, methods, sizeof(methods)/sizeof(methods[0]));'''
    new_view = '''    jclass cls = env->FindClass("io/github/jdawg867/anvildesk/m8/M8LorieView");
    if (!cls) return JNI_ERR;
    if (env->RegisterNatives(cls, methods, sizeof(methods)/sizeof(methods[0])) != JNI_OK)
        return JNI_ERR;'''
    if activity.count(old_view) != 1:
        raise ValueError("Pinned view JNI registration structure changed")
    activity = activity.replace(old_view, new_view)
    old_activity = '''    env->RegisterNatives(env->FindClass("io/github/jdawg867/anvildesk/m8/M8NativeActivityBridge"), mainActivityMethods, sizeof(mainActivityMethods)/sizeof(mainActivityMethods[0]));

    registerCmdEntryPointNatives(env);

    return JNI_VERSION_1_6;'''
    new_activity = '''    jclass activityClass = env->FindClass("io/github/jdawg867/anvildesk/m8/M8NativeActivityBridge");
    if (!activityClass) return JNI_ERR;
    if (env->RegisterNatives(activityClass, mainActivityMethods, sizeof(mainActivityMethods)/sizeof(mainActivityMethods[0])) != JNI_OK) {
        jthrowable registrationError = env->ExceptionOccurred();
        env->ExceptionClear();
        env->UnregisterNatives(cls);
        if (registrationError) {
            env->Throw(registrationError);
            env->DeleteLocalRef(registrationError);
        }
        return JNI_ERR;
    }

    registerCmdEntryPointNatives(env);
    if (env->ExceptionCheck()) {
        // Best-effort rollback of registrations performed earlier in OnLoad.
        // Preserve the original Java exception across UnregisterNatives.
        jthrowable registrationError = env->ExceptionOccurred();
        env->ExceptionClear();
        env->UnregisterNatives(activityClass);
        env->UnregisterNatives(cls);
        if (registrationError) {
            env->Throw(registrationError);
            env->DeleteLocalRef(registrationError);
        }
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;'''
    if activity.count(old_activity) != 1:
        raise ValueError("Pinned activity JNI registration structure changed")
    activity = activity.replace(old_activity, new_activity)
    # Resolve all application-owned JNI classes *before* registering the first
    # native method. This avoids leaving partially registered classes when a
    # later class lookup fails. A failure during registration still needs
    # rollback and is deliberately reported as a remaining blocker.
    preflight_anchor = '    vm->AttachCurrentThread(&env, nullptr);'
    onload_index = activity.find("JNIEXPORT jint JNI_OnLoad")
    if onload_index < 0 or activity[onload_index:].count(preflight_anchor) != 1:
        raise ValueError("Pinned JNI_OnLoad thread attachment changed")
    preflight = '''    if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env)
        return JNI_ERR;
    jclass preflightView = env->FindClass("io/github/jdawg867/anvildesk/m8/M8LorieView");
    if (!preflightView) return JNI_ERR;
    jclass preflightActivity = env->FindClass("io/github/jdawg867/anvildesk/m8/M8NativeActivityBridge");
    if (!preflightActivity) return JNI_ERR;
    jclass preflightCmd = env->FindClass("io/github/jdawg867/anvildesk/m8/M8CmdEntryPoint");
    if (!preflightCmd) return JNI_ERR;
    env->DeleteLocalRef(preflightView);
    env->DeleteLocalRef(preflightActivity);
    env->DeleteLocalRef(preflightCmd);'''
    activity = activity[:onload_index] + activity[onload_index:].replace(preflight_anchor, preflight, 1)
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
