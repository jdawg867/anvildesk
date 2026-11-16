#pragma once
// M8 diagnostic host JNI-table backend. Uses real JNI header signatures,
// but never loads a JVM, registers app methods, exports JNI_OnLoad or Lorie.
// A host test injects a fake JNIEnv function table. Real ART semantics untested.
#include <jni.h>
#include <cstring>

namespace anvildesk_m8 {
class RendererDiagnosticJniRegistrationBackend final {
public:
    RendererDiagnosticJniRegistrationBackend(JNIEnv* env, jclass target,
                                              const JNINativeMethod* methods, jint count)
        : env_(env), target_(target), methods_(methods), count_(count) {}

    bool validateSignatures() {
        if (!env_ || !target_ || !methods_ || count_ != 1) return false;
        if (env_->ExceptionCheck()) return false;
        const JNINativeMethod& method = methods_[0];
        // Diagnostic fixed contract only. Never pass untrusted method arrays.
        return method.name && method.signature && method.fnPtr &&
            std::strcmp(method.name, "dispatch") == 0 &&
            std::strcmp(method.signature, "(IJJIII)I") == 0;
    }
    bool registerMethods() {
        if (!validateSignatures()) return false;
        // JNI RegisterNatives return value may not prove the backend is clean
        // on failure. The transaction coordinator attempts rollback regardless.
        const jint result = env_->RegisterNatives(target_, methods_, count_);
        return result == JNI_OK && !env_->ExceptionCheck();
    }
    bool verifyRegistered() {
        // JNI has no portable registration-introspection API. This checks
        // only that no pending exception remains; it is NOT proof of binding.
        return env_ && !env_->ExceptionCheck();
    }
    bool unregisterMethods() {
        if (!env_ || !target_) return false;
        // On RegisterNatives failure, a pending exception is possible.
        // Never clear it here: caller must arrange safe recovery.
        if (env_->ExceptionCheck()) return false;
        return env_->UnregisterNatives(target_) == JNI_OK &&
               !env_->ExceptionCheck();
    }
private:
    JNIEnv* env_;
    jclass target_;
    const JNINativeMethod* methods_;
    jint count_;
};
} // namespace anvildesk_m8
