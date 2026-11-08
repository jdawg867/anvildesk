import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "m8_looper_patch", Path(__file__).with_name("patch-m8-renderer-looper-disconnect.py"))
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class RendererLooperDisconnectPatchTests(unittest.TestCase):
    def test_pinned_callback_is_patched_once(self):
        source = "prefix\n" + mod.OLD + "\nother native methods"
        updated = mod.patch(source)
        self.assertIn("if (fd != connFd)\n        return 0;", updated)
        self.assertIn("env->CallVoidMethod(activity, MainActivity.clientConnectedStateChanged);\n        // Android looper callback contract: zero unregisters this callback.\n        return 0;", updated)
        self.assertEqual(updated.count("int LorieViewResources::xcallback("), 1)
        self.assertIn("other native methods", updated)

    def test_double_patch_rejected(self):
        with self.assertRaises(ValueError):
            mod.patch(mod.patch(mod.OLD))

    def test_source_drift_rejected(self):
        with self.assertRaises(ValueError):
            mod.patch(mod.OLD.replace("return 1;", "return 0;"))

    def test_duplicate_source_rejected(self):
        with self.assertRaises(ValueError):
            mod.patch(mod.OLD + "\n" + mod.OLD)

    def test_unrelated_source_rejected(self):
        with self.assertRaises(ValueError):
            mod.patch("int other_callback() { return 1; }")


if __name__ == "__main__":
    unittest.main()
