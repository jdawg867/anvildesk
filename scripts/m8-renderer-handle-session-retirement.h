#pragma once
// Isolated M8 handle-to-session retirement coordination. No Lorie, JNI, or APK.
// Contract: the Registry and Retirement objects must outlive this coordinator.
// Only this coordinator may invalidate its handle. No caller may invoke
// shutdownAndRetire while holding a lease for the same handle (self-deadlock).
// The returned shared resource keeps state alive over a failed retirement;
// the retirement backend must implement idempotent, retry-safe cleanup.
#include "m8-renderer-handle-registry.h"
#include <cstdint>
#include <memory>
#include <mutex>

namespace anvildesk_m8 {
template<class Resource, class Retirement>
class RendererHandleSessionRetirement final {
public:
    using Registry = RendererHandleRegistry<Resource>;
    using Handle = typename Registry::Handle;

    RendererHandleSessionRetirement(Registry& registry, Handle handle,
                                     Retirement& retirement)
        : registry_(registry), handle_(handle), retirement_(retirement) {}
    RendererHandleSessionRetirement(const RendererHandleSessionRetirement&) = delete;
    RendererHandleSessionRetirement& operator=(const RendererHandleSessionRetirement&) = delete;

    // Step 1: revoke handle and drain all leases.
    // Step 2: ask existing owner/retirement coordinator to stop and join the
    // looper, clean global refs, and retire renderer state.
    // If step 2 fails, hold resource ownership for retry. Never republish.
    bool shutdownAndRetire() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (retired_) return true;
        if (!revoked_) {
            if (handle_ == 0) return false;
            resource_ = registry_.invalidateAndDrain(handle_);
            if (!resource_) return false;
            revoked_ = true;
        }
        if (!retirement_.shutdownAndRetire()) return false;
        retired_ = true;
        resource_.reset();
        return true;
    }
    bool retired() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return retired_;
    }
    bool revoked() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return revoked_;
    }
private:
    Registry& registry_;
    const Handle handle_;
    Retirement& retirement_;
    mutable std::mutex mutex_;
    std::shared_ptr<Resource> resource_;
    bool revoked_ = false;
    bool retired_ = false;
};
} // namespace anvildesk_m8
