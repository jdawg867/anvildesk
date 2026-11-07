import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "m8_registry_patch", Path(__file__).with_name("patch-m8-jni-registration.py"))
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

CMD = '''void registerCmdEntryPointNatives(JNIEnv *env) {
    jclass cls = env->FindClass("com/termux/x11/CmdEntryPoint");
    env->RegisterNatives(cls, methods, sizeof(methods)/sizeof(methods[0]));
}'''
ACT = '''JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    vm->AttachCurrentThread(&env, nullptr);
    MainActivity.self = FindClassOrDie(env,  "com/termux/x11/MainActivity");
    jfieldID activityField = env->GetFieldID(env->GetObjectClass(view), "activity", "Lcom/termux/x11/MainActivity;");
    jclass cls = env->FindClass("com/termux/x11/LorieView");
    env->RegisterNatives(cls, methods, sizeof(methods)/sizeof(methods[0]));
    env->RegisterNatives(env->FindClass("com/termux/x11/MainActivity"), mainActivityMethods, sizeof(mainActivityMethods)/sizeof(mainActivityMethods[0]));

    registerCmdEntryPointNatives(env);

    return JNI_VERSION_1_6;
}'''

class PatchTests(unittest.TestCase):
    def test_exact_pinned_registrations(self):
        cmd, act = mod.patch(CMD, ACT)
        self.assertIn("io/github/jdawg867/anvildesk/m8/M8CmdEntryPoint", cmd)
        self.assertIn("io/github/jdawg867/anvildesk/m8/M8LorieView", act)
        self.assertIn("io/github/jdawg867/anvildesk/m8/M8NativeActivityBridge", act)
        self.assertNotIn('FindClass("com/termux/x11/', cmd + act)
        self.assertNotIn("Lcom/termux/x11/MainActivity;", act)
        self.assertIn('FindClassOrDie(env,  "io/github/jdawg867/anvildesk/m8/M8NativeActivityBridge")', act)
        self.assertIn("Lio/github/jdawg867/anvildesk/m8/M8NativeActivityBridge;", act)
        self.assertIn("if (!cls) return;", cmd)
        self.assertIn('if (!env->ExceptionCheck())', cmd)
        self.assertIn('env->ThrowNew(env->FindClass("java/lang/UnsatisfiedLinkError")', cmd)
        self.assertIn("!= JNI_OK", cmd)
        self.assertIn("if (!cls) return m8RejectOnLoad(env);", act)
        self.assertIn("if (!activityClass) {", act)
        self.assertIn("if (env->ExceptionCheck()) {", act)
        self.assertIn("jthrowable registrationError = env->ExceptionOccurred();", act)
        self.assertIn("if (!preflightCmd) return m8RejectOnLoad(env);", act)
        self.assertIn("env->UnregisterNatives(activityClass);", act)
        self.assertIn("env->ExceptionClear();", act)
        self.assertIn("env->ExceptionDescribe();", act)
        self.assertEqual(act.count("return m8RejectOnLoad(env);"), 9)
        self.assertGreaterEqual(act.count("env->UnregisterNatives(cls);"), 2)
        self.assertIn("env->Throw(registrationError);", act)
        self.assertLess(act.index("jclass preflightCmd"), act.index("env->RegisterNatives(cls"))

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

    def test_unexpected_native_registration_structure_rejected(self):
        with self.assertRaises(ValueError):
            mod.patch(CMD.replace("sizeof(methods)/sizeof(methods[0])", "1"), ACT)

if __name__ == "__main__":
    unittest.main()
