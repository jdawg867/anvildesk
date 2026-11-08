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

    def test_stale_fd_guard_precedes_all_disconnect_side_effects(self):
        # Structural regression: a queued callback for an old descriptor
        # must return before touching the new connection or renderer.
        callback = mod.patch(mod.OLD)
        guard = callback.index("if (fd != connFd)\n        return 0;")
        side_effects = (
            "ALooper_removeFd(",
            "close(connFd);",
            "connFd = -1;",
            "renderer.setSharedState(nullptr);",
            "renderer.removeAllBuffers();",
            "env->CallVoidMethod(",
        )
        for token in side_effects:
            with self.subTest(token=token):
                self.assertGreater(callback.index(token), guard)

    def test_disconnect_unregister_and_close_order(self):
        callback = mod.patch(mod.OLD)
        self.assertLess(callback.index("ALooper_removeFd("),
                        callback.index("close(connFd);"))
        self.assertLess(callback.index("close(connFd);"),
                        callback.index("connFd = -1;"))
        self.assertIn("return 0;", callback)
        self.assertNotIn("return 1;", callback)
        # This only checks the pinned code transformation, not that the
        # Android looper has quiesced before native object destruction.

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
