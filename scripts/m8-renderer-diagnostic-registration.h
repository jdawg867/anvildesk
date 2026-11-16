#pragma once
// Host-only, single-class JNI registration transaction MODEL. No JNI exports,
// JNI_OnLoad, actual RegisterNatives, Lorie, Android app, or renderer activation.
// Backend registerMethods may have partially mutated state even if it returns
// false, so ALWAYS attempt unregisterMethods on failed registration.
// A failed rollback poisons this coordinator; never retry unsafe registration.
#include <mutex>

namespace anvildesk_m8 {
template<class Backend>
class RendererDiagnosticRegistration final {
public:
    explicit RendererDiagnosticRegistration(Backend& backend) : backend_(backend) {}
    RendererDiagnosticRegistration(const RendererDiagnosticRegistration&) = delete;
    RendererDiagnosticRegistration& operator=(const RendererDiagnosticRegistration&) = delete;

    bool registerAndVerify() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (poisoned_) return false;
        if (registered_) return true;
        // Validation MUST happen before registration; all backend methods are
        // synchronous and must not reenter this coordinator.
        bool valid = false;
        try { valid = backend_.validateSignatures(); } catch (...) { poisoned_ = true; return false; }
        if (!valid) return false;
        bool registered = false;
        try { registered = backend_.registerMethods(); } catch (...) {
            rollbackAfterFailure();
            return false;
        }
        if (!registered) {
            rollbackAfterFailure();
            return false;
        }
        bool verified = false;
        try { verified = backend_.verifyRegistered(); } catch (...) {
            rollbackAfterFailure();
            return false;
        }
        if (!verified) {
            rollbackAfterFailure();
            return false;
        }
        registered_ = true;
        return true;
    }

    // Deregistration should happen only after active invocation leases drain.
    // This model itself provides no invocation tracking.
    bool unregisterAfterDrain(bool invocationDrained) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!invocationDrained || poisoned_) return false;
        if (!registered_) return true;
        try {
            if (!backend_.unregisterMethods()) { poisoned_ = true; return false; }
        } catch (...) {
            poisoned_ = true;
            return false;
        }
        registered_ = false;
        return true;
    }
    bool registered() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return registered_;
    }
    bool poisoned() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return poisoned_;
    }

private:
    void rollbackAfterFailure() {
        bool cleaned = false;
        try { cleaned = backend_.unregisterMethods(); } catch (...) {}
        if (!cleaned) poisoned_ = true;
        registered_ = false;
    }
    Backend& backend_;
    mutable std::mutex mutex_;
    bool registered_ = false;
    bool poisoned_ = false;
};
} // namespace anvildesk_m8
