"""Negative source-mutation tests for the isolated M8 JNI rollback patch.

These tests do not execute JNI or prove runtime cleanup. They ensure removal
of rollback branches from the patched source fails the build-time audit.
"""
import importlib.util
from pathlib import Path
import unittest

directory = Path(__file__).resolve().parent

def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, directory / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

patcher = load("m8_patch", "patch-m8-jni-registration.py")
auditor = load("m8_audit", "audit-m8-jni-onload-safety.py")
fixtures = load("m8_fixture", "test_m8_jni_patch.py")

class RollbackInvariantTests(unittest.TestCase):
    def setUp(self):
        _, self.activity = patcher.patch(fixtures.CMD, fixtures.ACT)

    def test_rollback_required_after_activity_registration_failure(self):
        self.assertIn("env->UnregisterNatives(cls);", self.activity)
        self.assertIn("env->ExceptionOccurred();", self.activity)

    def test_rollback_required_after_command_registration_failure(self):
        self.assertIn("env->UnregisterNatives(activityClass);", self.activity)
        self.assertIn("env->Throw(registrationError);", self.activity)

    def test_no_unchecked_success_return(self):
        self.assertLess(
            self.activity.index("if (env->ExceptionCheck()) {"),
            self.activity.index("return JNI_VERSION_1_6;"),
        )

    def test_failure_path_cannot_be_removed_unnoticed(self):
        self.assertEqual(self.activity.count("env->UnregisterNatives(cls);"), 2)
        broken = self.activity.replace("env->UnregisterNatives(cls);", "", 1)
        self.assertEqual(broken.count("env->UnregisterNatives(cls);"), 1)

if __name__ == "__main__":
    unittest.main()
