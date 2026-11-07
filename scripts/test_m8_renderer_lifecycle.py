import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "m8_lifecycle_audit", Path(__file__).with_name("audit-m8-renderer-lifecycle.py"))
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

SOURCE = '''
thiz = env->NewGlobalRef(view);
activity = env->NewGlobalRef(a);
env->DeleteGlobalRef(thiz);
env->DeleteGlobalRef(activity);
ALooper_addFd(looper, fd, flags, cb, data);
ALooper_removeFd(looper, fd);
ALOOPER_EVENT_HANGUP;
MainActivity.clientConnectedStateChanged;
renderer.destroy();
{"nativeInit", "()J", (void *)&nativeInit},
{"nativeDestroy", "(J)V", (void *) +[](JNIEnv *env, jobject thiz, jlong ptr) {
    auto* r = (LorieViewResources*) ptr;
    r->~LorieViewResources();
    free(r);
}},
{"surfaceChanged", "(JLandroid/view/Surface;)V", (void *) nullptr},
'''


class RendererLifecycleAuditTests(unittest.TestCase):
    def test_pinned_lifetime_hazards_reported_without_approval(self):
        report = mod.audit(SOURCE)
        self.assertIn("M8_RENDERER_DOUBLE_DESTROY_PROTECTION_VERIFIED=false", report)
        self.assertIn("M8_RENDERER_CALLBACK_TEARDOWN_SYNCHRONIZATION_VERIFIED=false", report)
        self.assertIn("M8_NATIVE_LOAD_AUTHORIZED=false", report)

    def test_missing_global_reference_release_rejected(self):
        with self.assertRaises(ValueError):
            mod.audit(SOURCE.replace("env->DeleteGlobalRef(activity);", ""))

    def test_native_destroy_drift_rejected(self):
        with self.assertRaises(ValueError):
            mod.audit(SOURCE.replace("free(r);", "delete r;"))

    def test_double_destroy_entry_point_rejected(self):
        with self.assertRaises(ValueError):
            mod.audit(SOURCE + '{"nativeDestroy", "(J)V"')


if __name__ == "__main__":
    unittest.main()
