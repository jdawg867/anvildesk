#pragma once
// Isolated M8 host composition: stop JNI-style command admission and wait for
// renderer retirement BEFORE attempting JNI method deregistration.
// This is not an Android ART/production JNI integration. All external callers
// must use the same registry and registration coordinator; no competing owners.
// The underlying retirement must invalidate and drain EVERY invocation lease.
#include <mutex>

namespace anvildesk_m8 {
template<class Retirement, class Registration>
class RendererRegistrationTeardown final {
public:
    RendererRegistrationTeardown(Retirement& retirement, Registration& registration)
        : retirement_(retirement), registration_(registration) {}
    RendererRegistrationTeardown(const RendererRegistrationTeardown&) = delete;
    RendererRegistrationTeardown& operator=(const RendererRegistrationTeardown&) = delete;

    bool shutdownAndUnregister() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (complete_) return true;
        if (!retirement_.retired()) {
            if (!retirement_.shutdownAndRetire()) return false;
        }
        // Never use a client-supplied drain flag. Derive it from the
        // coordinator that just invalidated and drained native commands.
        if (!retirement_.retired()) return false;
        if (!registration_.unregisterAfterDrain(true)) return false;
        complete_ = true;
        return true;
    }

    bool complete() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return complete_;
    }
private:
    Retirement& retirement_;
    Registration& registration_;
    mutable std::mutex mutex_;
    bool complete_ = false;
};
} // namespace anvildesk_m8
