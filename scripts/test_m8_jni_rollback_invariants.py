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

    def assert_rollback_structure(self, source):
        activity_failure = source.split(
            'if (env->RegisterNatives(activityClass,', 1)[1].split(
            'registerCmdEntryPointNatives(env);', 1)[0]
        command_failure = source.split(
            'if (env->ExceptionCheck()) {', 1)[1].split(
            'return JNI_VERSION_1_6;', 1)[0]
        self.assertIn("env->UnregisterNatives(cls);", activity_failure)
        self.assertIn("env->UnregisterNatives(cls);", command_failure)
        self.assertIn("env->UnregisterNatives(activityClass);", command_failure)
        self.assertIn("env->ExceptionOccurred();", activity_failure)
        self.assertIn("env->ExceptionOccurred();", command_failure)
        self.assertIn("env->Throw(registrationError);", command_failure)

    def test_expected_failure_paths_satisfy_structure(self):
        self.assert_rollback_structure(self.activity)

    def test_activity_rollback_deletion_is_detected(self):
        broken = self.activity.replace("env->UnregisterNatives(cls);", "", 1)
        with self.assertRaises(AssertionError):
            self.assert_rollback_structure(broken)

    def test_command_rollback_deletion_is_detected(self):
        broken = self.activity.replace(
            "env->UnregisterNatives(activityClass);", "", 1)
        with self.assertRaises(AssertionError):
            self.assert_rollback_structure(broken)

    def test_command_view_rollback_deletion_is_detected(self):
        first = self.activity.index("env->UnregisterNatives(cls);")
        broken = self.activity[:first + 1] + self.activity[first + 1:].replace(
            "env->UnregisterNatives(cls);", "", 1)
        with self.assertRaises(AssertionError):
            self.assert_rollback_structure(broken)

    def test_command_failure_does_not_throw_null(self):
        command_failure = self.activity.split(
            'if (env->ExceptionCheck()) {', 1)[1].split(
            'return JNI_VERSION_1_6;', 1)[0]
        self.assertIn('if (registrationError) {', command_failure)
        self.assertLess(
            command_failure.index('if (registrationError) {'),
            command_failure.index('env->Throw(registrationError);'),
        )

    def test_exception_capture_deletion_is_detected(self):
        broken = self.activity.replace(
            "jthrowable registrationError = env->ExceptionOccurred();", "", 1)
        with self.assertRaises(AssertionError):
            self.assert_rollback_structure(broken)

if __name__ == "__main__":
    unittest.main()
