import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("m8_registry_patch", Path(__file__).with_name("patch-m8-jni-registration.py"))
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

CMD = 'jclass cls = env->FindClass("com/termux/x11/CmdEntryPoint");'
ACT = 'env->FindClass("com/termux/x11/LorieView"); env->FindClass("com/termux/x11/MainActivity");'

class PatchTests(unittest.TestCase):
    def test_exact_pinned_registrations(self):
        cmd, act = mod.patch(CMD, ACT)
        self.assertIn("io/github/jdawg867/anvildesk/m8/M8CmdEntryPoint", cmd)
        self.assertIn("io/github/jdawg867/anvildesk/m8/M8LorieView", act)
        self.assertIn("io/github/jdawg867/anvildesk/m8/M8NativeActivityBridge", act)
        self.assertNotIn('FindClass("com/termux/x11/', cmd + act)
    def test_missing_registration_rejected(self):
        with self.assertRaises(ValueError):
            mod.patch(CMD, ACT.replace("com/termux/x11/LorieView", "unexpected"))
    def test_duplicate_registration_rejected(self):
        with self.assertRaises(ValueError):
            mod.patch(CMD + CMD, ACT)
    def test_repeated_patch_rejected(self):
        cmd, act = mod.patch(CMD, ACT)
        with self.assertRaises(ValueError):
            mod.patch(cmd, act)
if __name__ == "__main__":
    unittest.main()
