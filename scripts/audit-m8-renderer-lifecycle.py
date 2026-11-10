#!/usr/bin/env python3
"""Record pinned renderer native lifetime hazards. No native code is executed.

A successful audit confirms known blockers remain visible; it does not prove
safe teardown or authorize loading libXlorie.so.
"""
from pathlib import Path
import sys

INVARIANTS = {
    "raw_pointer_destroy": ('auto* r = (LorieViewResources*) ptr;', 'r->~LorieViewResources();', 'free(r);'),
    "native_global_view_ref": ('thiz = env->NewGlobalRef(view);', 'env->DeleteGlobalRef(thiz);'),
    "native_global_activity_ref": ('activity = env->NewGlobalRef(a);', 'env->DeleteGlobalRef(activity);'),
    "looper_fd_lifecycle": ('ALooper_addFd(', 'ALooper_removeFd('),
    "disconnect_callback": ('ALOOPER_EVENT_HANGUP', 'MainActivity.clientConnectedStateChanged'),
    "raw_looper_callback_userdata": ('return ((LorieViewResources*) data)->xcallback(fd, events);',),
    "thread_local_looper_teardown": ('ALooper_removeFd(ALooper_forThread(), connFd);',),
}
# These are SOURCE OBSERVATIONS only. Their order cannot prove Android's
# queued callbacks have become quiescent or that JNIEnv is thread-safe.
DESTRUCTOR_START = "LorieViewResources::~LorieViewResources() {"
DESTRUCTOR_END = "int LorieViewResources::xcallback("
DESTRUCTOR_ORDER = (
    "destroyed = true;",
    "ALooper_removeFd(ALooper_forThread(), connFd);",
    "close(connFd);",
    "connFd = -1;",
    "renderer.destroy();",
    "env->DeleteGlobalRef(thiz);",
    "env->DeleteGlobalRef(activity);",
)

# Pin the callback body, not merely unrelated JNI symbols elsewhere in
# activity.cpp. The callback uses the retained JNIEnv* to reach Java.
CALLBACK_START = "int LorieViewResources::xcallback(int fd, int events) {"
CALLBACK_END = "void LorieViewResources::connect(jint fd) {"
CALLBACK_JNI_OPERATIONS = (
    "env->CallVoidMethod(",
    "env->GetMethodID(",
)

METHOD_DESTROY = '{"nativeDestroy", "(J)V"'
METHOD_INIT = '{"nativeInit", "()J"'


def audit(source: str) -> list[str]:
    for label, tokens in INVARIANTS.items():
        if any(token not in source for token in tokens):
            raise ValueError("Pinned renderer lifecycle changed: " + label)
    for token in (METHOD_INIT, METHOD_DESTROY, 'renderer.destroy();'):
        if source.count(token) != 1:
            raise ValueError("Pinned native renderer entry point drift: " + token)
    if source.count(DESTRUCTOR_START) != 1 or source.count(DESTRUCTOR_END) != 1:
        raise ValueError("Pinned LorieViewResources destructor boundary drift")
    destructor = source.split(DESTRUCTOR_START, 1)[1].split(DESTRUCTOR_END, 1)[0]
    positions = [destructor.find(token) for token in DESTRUCTOR_ORDER]
    if any(pos < 0 for pos in positions):
        raise ValueError("Pinned destructor lost looper/renderer/JNI cleanup")
    if positions != sorted(positions) or len(positions) != len(set(positions)):
        raise ValueError("Pinned destructor cleanup ordering drift")
    if "JNIEnv* env = nullptr;" not in source or "vm->AttachCurrentThread(&env, nullptr);" not in source:
        raise ValueError("Pinned thread-bound JNI environment ownership drift")
    if "ALooper_addFd(ALooper_forThread(), fd," not in source:
        raise ValueError("Pinned thread-local callback registration drift")
    if "this);" not in source:
        raise ValueError("Pinned callback raw userdata registration drift")
    if source.count(CALLBACK_START) != 1 or source.count(CALLBACK_END) != 1:
        raise ValueError("Pinned xcallback body boundary drift")
    callback = source.split(CALLBACK_START, 1)[1].split(CALLBACK_END, 1)[0]
    if any(operation not in callback for operation in CALLBACK_JNI_OPERATIONS):
        raise ValueError("Pinned xcallback JNI usage drift")
    # The stored env is acquired by AttachCurrentThread, not by a
    # thread-independent JavaVM lookup at callback entry. This audit
    # records that hazard; it does not label the source as safe.
    if "env->GetJavaVM(&vm)" in callback or "vm->AttachCurrentThread" in callback:
        raise ValueError("Pinned callback JNI acquisition changed; re-audit needed")
    destroyed = source.index(METHOD_DESTROY)
    destroy_end = source.index('{"surfaceChanged"', destroyed)
    destroy_section = source[destroyed:destroy_end]
    destructor_call = destroy_section.find('r->~LorieViewResources();')
    raw_free = destroy_section.find('free(r);')
    if destructor_call < 0 or raw_free < 0:
        raise ValueError("Pinned nativeDestroy implementation drift")
    # Preserve the expected destruction ordering even in this unsafe pinned
    # implementation. Moving free() earlier would cause an immediate UAF,
    # and broad token checks alone would incorrectly accept that regression.
    if raw_free < destructor_call:
        raise ValueError("Pinned nativeDestroy frees memory before destructor")
    return [
        "M8_RENDERER_RAW_POINTER_DESTROY=observed",
        "M8_RENDERER_DESTRUCTOR_BEFORE_FREE_ORDER=observed",
        "M8_RENDERER_DESTRUCTOR_CLEANUP_ORDER=observed",
        "M8_RENDERER_THREAD_BOUND_JNI_ENV=observed",
        "M8_RENDERER_CALLBACK_USES_STORED_JNI_ENV=observed",
        "M8_RENDERER_CALLBACK_JNI_THREAD_AFFINITY_VERIFIED=false",
        "M8_RENDERER_LOOPER_REGISTRATION_THREAD_OWNERSHIP_VERIFIED=false",
        "M8_RENDERER_JAVA_POINTER_OWNERSHIP_VERIFIED=false",
        "M8_RENDERER_DOUBLE_DESTROY_PROTECTION_VERIFIED=false",
        "M8_RENDERER_CALLBACK_TEARDOWN_SYNCHRONIZATION_VERIFIED=false",
        "M8_RENDERER_GLOBAL_JNI_REFERENCE_LIFETIME_VERIFIED=false",
        "M8_RENDERER_LOOPER_FD_OWNERSHIP_VERIFIED=false",
        "M8_RENDERER_RAW_LOOPER_USERDATA=observed",
        "M8_RENDERER_CALLBACK_ENTRY_QUIESCENCE_VERIFIED=false",
        "M8_RENDERER_REGISTERED_LOOPER_TEARDOWN_VERIFIED=false",
        "M8_RENDERER_RUNTIME_VERIFIED=false",
        "M8_NATIVE_LOAD_AUTHORIZED=false",
    ]


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: audit-m8-renderer-lifecycle.py PATCHED_ACTIVITY_CPP", file=sys.stderr)
        return 2
    try:
        report = audit(Path(sys.argv[1]).read_text())
    except (OSError, ValueError) as exc:
        print("STOP: " + str(exc), file=sys.stderr)
        return 1
    print("\n".join(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
