import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "m8_renderer_audit", Path(__file__).with_name("audit-m8-renderer-contract.py"))
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

NATIVE = '''
GetFieldID(cls, "activity", "Lio/github/jdawg867/anvildesk/m8/M8NativeActivityBridge;");
FindMethodOrDie(env, cls, "resetIme", "()V", JNI_FALSE);
FindMethodOrDie(env, cls, "clientConnectedStateChanged", "()V", JNI_FALSE);
'''
VIEW = 'public final class M8LorieView { }'
ACTIVITY = 'public final class M8NativeActivityBridge { }'


class RendererContractTests(unittest.TestCase):
    def test_missing_callbacks_are_reported_not_accepted_as_ready(self):
        result = mod.audit(NATIVE, VIEW, ACTIVITY)
        self.assertEqual(set(result), set(mod.NATIVE_REQUIREMENTS))
        self.assertFalse(any(result.values()))

    def test_java_declarations_do_not_prove_runtime_safety(self):
        view = 'M8NativeActivityBridge activity; void resetIme() {}'
        activity = 'void clientConnectedStateChanged() {}'
        self.assertTrue(all(mod.audit(NATIVE, view, activity).values()))

    def test_native_contract_drift_is_fail_closed(self):
        with self.assertRaises(ValueError):
            mod.audit(NATIVE.replace('"resetIme"', '"resetIMEMissing"'), VIEW, ACTIVITY)

    def test_duplicate_native_callback_requirement_is_rejected(self):
        with self.assertRaises(ValueError):
            mod.audit(NATIVE + 'FindMethodOrDie(env, cls, "resetIme", "()V", JNI_FALSE);', VIEW, ACTIVITY)


if __name__ == "__main__":
    unittest.main()
