#!/usr/bin/env python3
"""Mutation regressions for the M8 production no-load policy."""
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import unittest

root = Path(__file__).resolve().parents[1]
script = root / "scripts/verify-m8-native-promotion-block.py"
spec = spec_from_file_location("m8_promotion_block", script)
module = module_from_spec(spec)
spec.loader.exec_module(module)

policy_path = root / "app/src/main/java/io/github/jdawg867/anvildesk/m8/M8NativeLoadingPolicy.java"


class NativePromotionBlockTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.policy = policy_path.read_text()

    def test_current_policy_is_sealed(self):
        module.verify_policy(self.policy)

    def test_return_true_is_rejected(self):
        with self.assertRaises(ValueError):
            module.verify_policy(self.policy.replace("return false;", "return true;"))

    def test_configurable_loading_is_rejected(self):
        with self.assertRaises(ValueError):
            module.verify_policy(self.policy.replace(
                "return false;", "if (evidence != null) return true; return false;"))

    def test_conditional_evaluate_is_rejected(self):
        with self.assertRaises(ValueError):
            module.verify_policy(self.policy.replace(
                "return Status.NOT_AUTHORIZED;",
                "if (evidence != null) return Status.NOT_AUTHORIZED; return Status.NOT_AUTHORIZED;"))

    def test_missing_method_is_rejected(self):
        with self.assertRaises(ValueError):
            module.verify_policy(self.policy.replace("mayLoadLibrary", "canLoadLibrary"))

    def test_unexpected_library_load_is_rejected(self):
        with self.assertRaises(ValueError):
            module.verify_policy(self.policy.replace(
                "return false;", 'System.loadLibrary("Xlorie"); return false;'))


if __name__ == "__main__":
    unittest.main(verbosity=2)
