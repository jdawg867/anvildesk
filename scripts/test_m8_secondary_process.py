import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location(
    "m8_process_guard", ROOT / "scripts/verify-m8-secondary-process.py"
)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
SERVICE = (ROOT / "app/src/main/java/io/github/jdawg867/anvildesk/m8/M8X11ProcessService.java").read_text()

class SecondaryProcessTests(unittest.TestCase):
    def test_actual_disabled_binder_contract(self):
        mod.verify(MANIFEST, SERVICE)

    def test_exported_service_rejected(self):
        with self.assertRaises(ValueError):
            mod.verify(MANIFEST.replace('android:exported="false"\n            android:enabled="false"',
                                        'android:exported="true"\n            android:enabled="false"'), SERVICE)

    def test_native_loading_rejected(self):
        with self.assertRaises(ValueError):
            mod.verify(MANIFEST, SERVICE + '\nSystem.loadLibrary("Xlorie");')

    def test_missing_uid_check_rejected(self):
        with self.assertRaises(ValueError):
            mod.verify(MANIFEST, SERVICE.replace("Binder.getCallingUid()", "0"))

if __name__ == "__main__":
    unittest.main()
