#!/usr/bin/env python3
"""Fail-closed inspection of the disabled M8 process boundary scaffold."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
SERVICE = ".m8.M8X11ProcessService"

def verify(manifest: str, source: str) -> None:
    root = ET.fromstring(manifest)
    app = root.find("application")
    if app is None:
        raise ValueError("missing application")
    matches = [s for s in app.findall("service") if s.get(ANDROID + "name") == SERVICE]
    if len(matches) != 1:
        raise ValueError("expected exactly one M8 service")
    service = matches[0]
    expected = {"process": ":m8x11", "exported": "false", "enabled": "false"}
    for attr, value in expected.items():
        if service.get(ANDROID + attr) != value:
            raise ValueError(f"unsafe M8 service {attr}")
    for forbidden in ("System.loadLibrary(", "Runtime.getRuntime(", "ProcessBuilder(", "startForeground("):
        if forbidden in source:
            raise ValueError("unreviewed process/native call " + forbidden)
    for expected_code in ("extends Service", "return null;", "stopSelf(startId);", "START_NOT_STICKY"):
        if expected_code not in source:
            raise ValueError("service behavior drift: " + expected_code)

def main() -> int:
    if len(sys.argv) != 3:
        print("usage: verify-m8-secondary-process.py MANIFEST SERVICE_JAVA", file=sys.stderr)
        return 2
    try:
        verify(Path(sys.argv[1]).read_text(), Path(sys.argv[2]).read_text())
    except (OSError, ValueError, ET.ParseError) as error:
        print("STOP: " + str(error), file=sys.stderr)
        return 1
    print("PASS: inert separate-process M8 service disabled and non-exported")
    print("M8_NATIVE_LOAD_AUTHORIZED=false")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
