import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "m8_lorie_jni_inventory",
    Path(__file__).with_name("audit-m8-lorie-callback-jni-inventory.py"))
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

# Synthetic source fixture: exactly the pinned JNI calls, event cases, Java
# references and renderer mutations, not a substitute for the real upstream
# source (which is audited separately in the pinned upstream workflow).
BODY = """
int LorieViewResources::xcallback(int fd, int events) {
    if (activity) env->CallVoidMethod(activity, MainActivity.clientConnectedStateChanged);
    switch(e.type) {
        case EVENT_CLIPBOARD_SEND: {
            env->GetMethodID(env->GetObjectClass(thiz), "setClipboardText", "(Ljava/lang/String;)V");
            env->NewDirectByteBuffer(data, 1);
            env->CallStaticObjectMethod(Charset.self, Charset.forName, env->NewStringUTF("UTF-8"));
            env->CallObjectMethod(Charset.decode, buffer);
            env->DeleteLocalRef(buffer);
            env->CallObjectMethod(buffer, CharBuffer.toString);
            env->CallVoidMethod(thiz, method, buffer);
            break;
        }
        case EVENT_CLIPBOARD_REQUEST:
            env->CallVoidMethod(thiz, env->GetMethodID(env->GetObjectClass(thiz), "requestClipboard", "()V"));
            break;
        case EVENT_SHARED_SERVER_STATE: renderer.setSharedState(state); break;
        case EVENT_ADD_BUFFER: renderer.addBuffer(buffer); break;
        case EVENT_REMOVE_BUFFER: renderer.removeBuffer(id); break;
        case EVENT_WINDOW_FOCUS_CHANGED:
            env->CallVoidMethod(thiz, MainActivity.resetIme); break;
        case EVENT_SYNC_REPLY:
            env->GetMethodID(env->GetObjectClass(thiz), "onSyncReply", "(I)V");
            env->CallVoidMethod(thiz, method, serial); break;
    }
    renderer.removeAllBuffers();
    return xcallback(fd, ALOOPER_EVENT_HANGUP);
}
void LorieViewResources::connect(jint fd) {
}
"""

class SourceLockedJniInventoryTests(unittest.TestCase):
    def test_pinned_inventory_only_records_blockers(self):
        result = mod.audit(BODY)
        self.assertIn("M8_LORIE_CALLBACK_SOURCE_LOCKED_INVENTORY=PASS", result)
        self.assertIn("M8_LORIE_CALLBACK_JNI_EXCEPTION_GATE_VERIFIED=false", result)
        self.assertIn("M8_LORIE_CALLBACK_JAVA_GLOBAL_REF_LEASE_VERIFIED=false", result)
        self.assertIn("M8_NATIVE_LOAD_AUTHORIZED=false", result)

    def test_missing_event_is_source_drift(self):
        with self.assertRaisesRegex(ValueError, "event routing drifted"):
            mod.audit(BODY.replace("case EVENT_SYNC_REPLY:", "case EVENT_UNKNOWN:"))

    def test_changed_jni_call_is_source_drift(self):
        with self.assertRaisesRegex(ValueError, "JNI calls drifted"):
            mod.audit(BODY.replace("env->CallVoidMethod(thiz, method, serial);", ""))

    def test_missing_java_reference_is_source_drift(self):
        with self.assertRaisesRegex(ValueError, "Java reference drifted"):
            mod.audit(BODY.replace("Charset.decode", "Different.decode"))

    def test_renderer_resource_mutation_is_source_drift(self):
        with self.assertRaisesRegex(ValueError, "renderer mutation drifted"):
            mod.audit(BODY.replace("renderer.removeAllBuffers();", ""))

    def test_scoped_callback_boundary_must_be_unique(self):
        with self.assertRaisesRegex(ValueError, "method boundaries drifted"):
            mod.audit(BODY + BODY)

    def test_new_jni_ownership_path_requires_manual_reaudit(self):
        with self.assertRaisesRegex(ValueError, "(?:JNI calls drifted|JNI ownership changed)"):
            mod.audit(BODY.replace("renderer.removeAllBuffers();",
                                   "env->ExceptionCheck(); renderer.removeAllBuffers();"))

    def test_local_ref_deletion_count_change_rejected(self):
        with self.assertRaisesRegex(ValueError, "JNI calls drifted"):
            mod.audit(BODY.replace("env->DeleteLocalRef(buffer);", ""))

if __name__ == "__main__":
    unittest.main()
