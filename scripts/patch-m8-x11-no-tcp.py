#!/usr/bin/env python3
"""Apply a fail-closed M8 no-TCP source patch to the pinned upstream checkout."""
from pathlib import Path
import sys

START = '// Called from OsVendorInit() once the display number and "-tag" argument (if any) are resolved,'
END = '\nvoid registerCmdEntryPointNatives(JNIEnv *env) {'
REPLACEMENT = '''// AnvilDesk M8 integration experiment: disable upstream TCP knock discovery.
// The in-process Android host must manage readiness/lifecycle separately.
// This does not establish X11 authentication or guest connectivity.
void lorieListenForKnocks(int port, const char *tag) {
    (void) port;
    serverEnv->CallVoidMethod(thiz, setDocumentTag,
                              tag ? serverEnv->NewStringUTF(tag) : nullptr);
}
'''
def patch(source: str) -> str:
    if source.count(START) != 1 or source.count(END) != 1:
        raise ValueError("Unexpected upstream native entrypoint structure")
    left, after_start = source.split(START, 1)
    removed, right = after_start.split(END, 1)
    for evidence in ("socket(AF_INET", "INADDR_ANY", "SetNotifyFd(", "sendBroadcastDelayed"):
        if evidence not in removed:
            raise ValueError("Pinned upstream TCP implementation does not match")
    return left + REPLACEMENT + END + right

def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: patch-m8-x11-no-tcp.py <upstream cmdentrypoint.cpp>", file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    try:
        old = path.read_text(encoding="utf-8")
        new = patch(old)
        path.write_text(new, encoding="utf-8")
    except (OSError, ValueError) as exc:
        print(f"PATCH REFUSED: {exc}", file=sys.stderr)
        return 1
    print("M8 TCP knock listener disabled in disposable upstream source checkout")
    return 0
if __name__ == "__main__":
    raise SystemExit(main())
