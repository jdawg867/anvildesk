#!/usr/bin/env python3
"""Fail-closed regression tests for the pinned M8 renderer lifecycle audit.

This synthetic fixture tests audit behavior only. It does not execute or certify
libXlorie.so, guarantee Android callback quiescence, or permit native loading.
"""
import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).with_name("audit-m8-renderer-lifecycle.py")
spec = importlib.util.spec_from_file_location("m8_renderer_lifecycle_audit", SCRIPT)
audit_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit_module)


def fixture():
    # Purposefully minimal source with the pinned unsafe upstream structures.
    # No Android runtime code is executed; observed hazards must be reported.
    return """
struct LorieViewResources {
    JNIEnv* env = nullptr;
};
thiz = env->NewGlobalRef(view);
activity = env->NewGlobalRef(a);
vm->AttachCurrentThread(&env, nullptr);
LorieViewResources::~LorieViewResources() {
    destroyed = true;
    ALooper_removeFd(ALooper_forThread(), connFd);
    close(connFd);
    connFd = -1;
    renderer.destroy();
    env->DeleteGlobalRef(thiz);
    env->DeleteGlobalRef(activity);
}
int LorieViewResources::xcallback(int fd, int events) {
    env->CallVoidMethod(thiz, callback);
    env->GetMethodID(clazz, "onEvent", "()V");
    if (events & ALOOPER_EVENT_HANGUP)
        MainActivity.clientConnectedStateChanged;
    return 1;
}
void LorieViewResources::connect(jint fd) {
    ALooper_addFd(ALooper_forThread(), fd,
        ALOOPER_POLL_CALLBACK, ALOOPER_EVENT_INPUT,
        +[](int fd, int events, void* data) {
            return ((LorieViewResources*) data)->xcallback(fd, events);
        }, this);
}
{\"nativeInit\", \"()J\", (void*)init},
{\"nativeDestroy\", \"(J)V\", (void*)destroy},
auto* r = (LorieViewResources*) ptr;
r->~LorieViewResources();
free(r);
{\"surfaceChanged\", \"(JII)V\", (void*)surfaceChanged},
"""


class RendererLifecycleAuditTests(unittest.TestCase):
    def test_known_unsafe_fixture_reports_blockers_not_authorization(self):
        findings = audit_module.audit(fixture())
        self.assertIn("M8_RENDERER_RAW_LOOPER_USERDATA=observed", findings)
        self.assertIn("M8_RENDERER_CALLBACK_ENTRY_QUIESCENCE_VERIFIED=false", findings)
        self.assertIn("M8_RENDERER_CALLBACK_JNI_THREAD_AFFINITY_VERIFIED=false", findings)
        self.assertIn("M8_NATIVE_LOAD_AUTHORIZED=false", findings)
        self.assertFalse(any(line == "M8_NATIVE_LOAD_AUTHORIZED=true" for line in findings))

    def assert_drift(self, text):
        with self.assertRaises(ValueError):
            audit_module.audit(text)

    def test_missing_callback_entry_fails_closed(self):
        self.assert_drift(fixture().replace(
            "return ((LorieViewResources*) data)->xcallback(fd, events);",
            "return 0;",
        ))

    def test_raw_userdata_registration_drift_fails_closed(self):
        self.assert_drift(fixture().replace("}, this);", "}, other);"))

    def test_callback_moved_outside_connect_fails_closed(self):
        self.assert_drift(fixture().replace(
            "return ((LorieViewResources*) data)->xcallback(fd, events);",
            "return 0;",
        ) + "\nreturn ((LorieViewResources*) data)->xcallback(fd, events);\n")

    def test_destructor_order_reversal_fails_closed(self):
        self.assert_drift(fixture().replace(
            "destroyed = true;\n    ALooper_removeFd(ALooper_forThread(), connFd);",
            "ALooper_removeFd(ALooper_forThread(), connFd);\n    destroyed = true;",
        ))

    def test_free_before_destructor_fails_closed(self):
        self.assert_drift(fixture().replace(
            "r->~LorieViewResources();\nfree(r);",
            "free(r);\nr->~LorieViewResources();",
        ))

    def test_changed_callback_jni_acquisition_requires_reaudit(self):
        self.assert_drift(fixture().replace(
            "env->CallVoidMethod(thiz, callback);",
            "vm->AttachCurrentThread(&env, nullptr);\n"
            "    env->CallVoidMethod(thiz, callback);",
        ))

    def test_removed_jni_cleanup_fails_closed(self):
        self.assert_drift(fixture().replace(
            "env->DeleteGlobalRef(activity);", "/* removed */",
        ))


if __name__ == "__main__":
    unittest.main(verbosity=2)
