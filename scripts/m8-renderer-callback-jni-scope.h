#pragma once
// Experimental per-callback JNI attachment policy. No renderer dependency.
// Backend is injectable for deterministic host tests. The JNI-backed adapter
// lives in m8-renderer-jni-thread-env.h and is NOT wired into libXlorie.so.
namespace anvildesk_m8 {
template <class Backend>
class ScopedCallbackThreadEnv final {
public:
    explicit ScopedCallbackThreadEnv(Backend& backend) : backend_(backend) {
        // Query the CURRENT thread. Never reuse another thread's JNIEnv.
        switch (backend_.query(&env_)) {
        case Backend::Status::Attached:
            return;  // Borrow existing attachment; do not detach.
        case Backend::Status::Detached:
            env_ = nullptr;
            if (backend_.attach(&env_) && env_) attachedHere_ = true;
            else env_ = nullptr;
            return;
        case Backend::Status::Error:
            env_ = nullptr;
            return;  // Fail closed; do not attempt attach on query error.
        }
        env_ = nullptr;
    }
    ~ScopedCallbackThreadEnv() {
        // Detach only the current thread if this exact scope attached it.
        if (attachedHere_) backend_.detach();
    }
    ScopedCallbackThreadEnv(const ScopedCallbackThreadEnv&) = delete;
    ScopedCallbackThreadEnv& operator=(const ScopedCallbackThreadEnv&) = delete;
    ScopedCallbackThreadEnv(ScopedCallbackThreadEnv&&) = delete;
    ScopedCallbackThreadEnv& operator=(ScopedCallbackThreadEnv&&) = delete;
    using Env = typename Backend::Env;
    Env* get() const noexcept { return env_; }
    explicit operator bool() const noexcept { return env_ != nullptr; }
    bool attachedHere() const noexcept { return attachedHere_; }
private:
    Backend& backend_;
    Env* env_ = nullptr;
    bool attachedHere_ = false;
};
} // namespace anvildesk_m8
