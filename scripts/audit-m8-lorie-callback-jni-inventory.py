#!/usr/bin/env python3
"""Source-locked inventory of pinned Lorie xcallback JNI/resource hazards.

This checks the observed pinned upstream (including the isolated disconnect
patch) and intentionally does NOT attest thread safety or native-load approval.
"""
from collections import Counter
from pathlib import Path
import re
import sys

START = "int LorieViewResources::xcallback(int fd, int events) {"
END = "void LorieViewResources::connect(jint fd) {"
EXPECTED_JNI = Counter({
    "CallVoidMethod": 5,
    "GetMethodID": 3,
    "GetObjectClass": 3,
    "NewDirectByteBuffer": 1,
    "CallStaticObjectMethod": 1,
    "NewStringUTF": 1,
    "CallObjectMethod": 2,
    "DeleteLocalRef": 1,
})
EXPECTED_EVENTS = {
    "EVENT_CLIPBOARD_SEND", "EVENT_CLIPBOARD_REQUEST",
    "EVENT_SHARED_SERVER_STATE", "EVENT_ADD_BUFFER",
    "EVENT_REMOVE_BUFFER", "EVENT_WINDOW_FOCUS_CHANGED",
    "EVENT_SYNC_REPLY",
}
EXPECTED_GLOBALS = (
    "thiz", "activity", "Charset.self", "Charset.forName",
    "Charset.decode", "CharBuffer.toString",
    "MainActivity.clientConnectedStateChanged", "MainActivity.resetIme",
)
EXPECTED_RENDERER = (
    "renderer.setSharedState(", "renderer.removeAllBuffers(",
    "renderer.addBuffer(", "renderer.removeBuffer(",
)
EXPECTED_LOCAL_REF_CALLS = {
    "GetObjectClass": 3,
    "NewDirectByteBuffer": 1,
    "NewStringUTF": 1,
    "CallStaticObjectMethod": 1,
    "CallObjectMethod": 2,
}

def audit(source: str) -> list[str]:
    if source.count(START) != 1 or source.count(END) != 1:
        raise ValueError("pinned xcallback method boundaries drifted")
    body = source.split(START, 1)[1].split(END, 1)[0]
    events = set(re.findall(r"\bcase\s+(EVENT_[A-Z_]+)\s*:", body))
    if events != EXPECTED_EVENTS:
        raise ValueError("pinned callback event routing drifted")
    calls = Counter(re.findall(r"\benv->([A-Za-z_][A-Za-z_0-9]*)\s*\(", body))
    if calls != EXPECTED_JNI:
        raise ValueError(f"pinned callback JNI calls drifted: {dict(calls)}")
    for token in EXPECTED_GLOBALS:
        if token not in body:
            raise ValueError(f"pinned callback Java reference drifted: {token}")
    for token in EXPECTED_RENDERER:
        if token not in body:
            raise ValueError(f"pinned callback renderer mutation drifted: {token}")
    if "return xcallback(fd, ALOOPER_EVENT_HANGUP);" not in body:
        raise ValueError("pinned callback recursive disconnect drifted")
    # Scoped negative inventory: the pinned callback has NO safe, local
    # GetEnv/AttachCurrentThread entry; also no exception check/clear path.
    for forbidden in ("GetJavaVM(", "GetEnv(", "AttachCurrentThread(",
                      "DetachCurrentThread(", "ExceptionCheck(",
                      "ExceptionClear(", "ExceptionOccurred("):
        if forbidden in body:
            raise ValueError(f"pinned callback JNI ownership changed: {forbidden}")
    # Event loop uses Java objects without documented local-ref balancing.
    # This audit records counts; DeleteLocalRef only targets one buffer, not
    # all returned refs. Do not promote this pattern.
    if calls["DeleteLocalRef"] != 1:
        raise ValueError("pinned callback local-reference cleanup drifted")
    return [
        "M8_LORIE_CALLBACK_EVENT_PATHS=7",
        "M8_LORIE_CALLBACK_JNI_CALLS=17",
        "M8_LORIE_CALLBACK_LOCAL_REF_DELETE_CALLS=1",
        "M8_LORIE_CALLBACK_STORED_JNI_ENV=observed",
        "M8_LORIE_CALLBACK_JNI_EXCEPTION_GATE_VERIFIED=false",
        "M8_LORIE_CALLBACK_JNI_LOCAL_REFS_BALANCED_VERIFIED=false",
        "M8_LORIE_CALLBACK_JAVA_GLOBAL_REF_LEASE_VERIFIED=false",
        "M8_LORIE_CALLBACK_RENDERER_RESOURCE_LEASE_VERIFIED=false",
        "M8_LORIE_CALLBACK_SOURCE_LOCKED_INVENTORY=PASS",
        "M8_NATIVE_LOAD_AUTHORIZED=false",
    ]

def main() -> int:
    if len(sys.argv) != 2:
        print("usage: audit-m8-lorie-callback-jni-inventory.py ACTIVITY_CPP", file=sys.stderr)
        return 2
    try:
        print("\n".join(audit(Path(sys.argv[1]).read_text())))
        return 0
    except (OSError, ValueError) as exc:
        print("STOP: " + str(exc), file=sys.stderr)
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
