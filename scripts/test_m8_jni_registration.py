import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).with_name("audit-m8-jni-registration.py")
spec = importlib.util.spec_from_file_location("m8_jni_audit", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

CMD = '''jclass cls = env->FindClass("com/termux/x11/CmdEntryPoint");
env->RegisterNatives(cls, methods, 5);'''
ACTIVITY = '''JNI_OnLoad
jclass cls = env->FindClass("com/termux/x11/LorieView");
env->RegisterNatives(cls, methods, 10);
env->RegisterNatives(env->FindClass("com/termux/x11/MainActivity"), methods, 1);
registerCmdEntryPointNatives(env);'''

class ContractTests(unittest.TestCase):
    def test_expected_registration(self):
        module.verify(CMD, ACTIVITY)

    def test_missing_activity_throws(self):
        with self.assertRaises(ValueError):
            module.verify(CMD, ACTIVITY.replace("com/termux/x11/MainActivity", "wrong"))

    def test_missing_chain_throws(self):
        with self.assertRaises(ValueError):
            module.verify(CMD, ACTIVITY.replace("registerCmdEntryPointNatives(env);", ""))

    def test_duplicate_registration_throws(self):
        with self.assertRaises(ValueError):
            module.verify(CMD + CMD, ACTIVITY)

if __name__ == "__main__":
    unittest.main()
