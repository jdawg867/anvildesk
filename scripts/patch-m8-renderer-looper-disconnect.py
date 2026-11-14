#!/usr/bin/env python3
"""Patch the pinned X11 renderer looper disconnect callback only.

Build-time transformation for isolated ARM64 CI. This is not a general
callback-thread lifetime solution and does not authorize JNI loading.
"""
from pathlib import Path
import sys

OLD = '''int LorieViewResources::xcallback(int fd, int events) {
    if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
        ALooper_removeFd(ALooper_forThread(), fd);
        close(connFd);
        connFd = -1;
        renderer.setSharedState(nullptr);
        renderer.removeAllBuffers();
        log(DEBUG, "disconnected");
        if (activity)
            env->CallVoidMethod(activity, MainActivity.clientConnectedStateChanged);
        return 1;
    }'''

NEW = '''int LorieViewResources::xcallback(int fd, int events) {
    // A removed descriptor may have queued one final callback. Do not close
    // a replacement connection or touch its renderer state.
    if (fd != connFd)
        return 0;
    if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
        ALooper_removeFd(ALooper_forThread(), fd);
        close(connFd);
        connFd = -1;
        renderer.setSharedState(nullptr);
        renderer.removeAllBuffers();
        log(DEBUG, "disconnected");
        if (activity)
            env->CallVoidMethod(activity, MainActivity.clientConnectedStateChanged);
        // Android looper callback contract: zero unregisters this callback.
        return 0;
    }'''


def patch(source: str) -> str:
    if source.count(OLD) != 1:
        raise ValueError("Pinned renderer disconnect handler drift or already patched")
    return source.replace(OLD, NEW, 1)


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: patch-m8-renderer-looper-disconnect.py ACTIVITY_CPP", file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    try:
        source = path.read_text()
        updated = patch(source)
        path.write_text(updated)
    except (OSError, ValueError) as exc:
        print("STOP: " + str(exc), file=sys.stderr)
        return 1
    print("M8_RENDERER_STALE_FD_CALLBACK_REJECTED=true")
    print("M8_RENDERER_DISCONNECT_CALLBACK_UNREGISTER_RETURN=true")
    print("M8_RENDERER_CALLBACK_TEARDOWN_SYNCHRONIZATION_VERIFIED=false")
    print("M8_NATIVE_LOAD_AUTHORIZED=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
