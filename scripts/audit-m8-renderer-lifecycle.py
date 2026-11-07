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
}
METHOD_DESTROY = '{"nativeDestroy", "(J)V"'
METHOD_INIT = '{"nativeInit", "()J"'


def audit(source: str) -> list[str]:
    for label, tokens in INVARIANTS.items():
        if any(token not in source for token in tokens):
            raise ValueError("Pinned renderer lifecycle changed: " + label)
    for token in (METHOD_INIT, METHOD_DESTROY, 'renderer.destroy();'):
        if source.count(token) != 1:
            raise ValueError("Pinned native renderer entry point drift: " + token)
    destroyed = source.index(METHOD_DESTROY)
    destroy_end = source.index('{"surfaceChanged"', destroyed)
    destroy_section = source[destroyed:destroy_end]
    if 'r->~LorieViewResources();' not in destroy_section or 'free(r);' not in destroy_section:
        raise ValueError("Pinned nativeDestroy implementation drift")
    return [
        "M8_RENDERER_RAW_POINTER_DESTROY=observed",
        "M8_RENDERER_JAVA_POINTER_OWNERSHIP_VERIFIED=false",
        "M8_RENDERER_DOUBLE_DESTROY_PROTECTION_VERIFIED=false",
        "M8_RENDERER_CALLBACK_TEARDOWN_SYNCHRONIZATION_VERIFIED=false",
        "M8_RENDERER_GLOBAL_JNI_REFERENCE_LIFETIME_VERIFIED=false",
        "M8_RENDERER_LOOPER_FD_OWNERSHIP_VERIFIED=false",
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
