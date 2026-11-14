#pragma once
// Android JNI backend for isolated callback-thread scoping. Not integrated
// into Lorie, the production APK, or the native Xorg server.
#include "m8-renderer-callback-jni-scope.h"
#include <jni.h>

namespace anvildesk_m8 {
class AndroidJniThreadBackend final {
public:
    using Env = JNIEnv;
    enum class Status { Attached, Detached, Error };
    explicit AndroidJniThreadBackend(JavaVM* vm) noexcept : vm_(vm) {}
    Status query(JNIEnv** out) noexcept {
        if (!out) return Status::Error;
        *out = nullptr;
        if (!vm_) return Status::Error;
        void* raw = nullptr;
        const jint result = vm_->GetEnv(&raw, JNI_VERSION_1_6);
        if (result == JNI_OK && raw) {
            *out = static_cast<JNIEnv*>(raw);
            return Status::Attached;
        }
        if (result == JNI_EDETACHED) return Status::Detached;
        return Status::Error;
    }
    bool attach(JNIEnv** out) noexcept {
        if (!out) return false;
        *out = nullptr;
        return vm_ && vm_->AttachCurrentThread(out, nullptr) == JNI_OK &&
               *out != nullptr;
    }
    void detach() noexcept {
        if (vm_) (void)vm_->DetachCurrentThread();
    }
private:
    JavaVM* const vm_;
};
using ScopedAndroidCallbackEnv = ScopedCallbackThreadEnv<AndroidJniThreadBackend>;
} // namespace anvildesk_m8
