#pragma once
// Experimental M8 session owner. Include after the dedicated-looper prototype.
// NOT part of Lorie, JNI, or the production AnvilDesk APK.
// The client callback must not hold an unprotected renderer/JNI pointer.
// This owner releases only its OWN callback context, never client resources.
#include "m8-renderer-owned-looper-adapter.h"
#include "m8-renderer-session-callback-context.h"
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>

namespace anvildesk_m8 {
class RendererSessionOwnedLooper final {
public:
    RendererSessionOwnedLooper() = default;
    RendererSessionOwnedLooper(const RendererSessionOwnedLooper&) = delete;
    RendererSessionOwnedLooper& operator=(const RendererSessionOwnedLooper&) = delete;

    bool registerCallback(std::uint64_t generation, int fd, int events,
                          ALooper_callbackFunc callback, void* clientData) {
        if (adapter_.onWorkerThread()) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        if (closed_ || session_ || !callback || !clientData || generation == 0 ||
            fd < 0 || events == 0)
            return false;
        auto candidate = std::make_unique<CallbackState>(generation, fd,
                                                          callback, clientData);
        if (!adapter_.registerCallback(generation, fd, events,
                                      &RendererSessionOwnedLooper::trampoline,
                                      candidate.get()))
            return false;  // No platform callback was registered.
        session_ = std::move(candidate);
        return true;
    }

    // External callers only. On failed removal the live context is retained
    // and registration stays closed. Concurrent calls serialize through mutex.
    bool shutdownAndJoin() {
        if (adapter_.onWorkerThread())
            throw std::logic_error("renderer session owner cannot self-join");
        std::lock_guard<std::mutex> lock(mutex_);
        if (joined_) return true;
        closed_ = true;
        if (session_) session_->gate.closeAdmission();
        if (!adapter_.shutdownAndJoin()) return false;
        if (session_) {
            // The platform poll worker has actually joined; only now may
            // its raw userdata be destroyed. Lease drain is extra defense.
            session_->gate.waitForLeases();
            if (!adapter_.releaseAuthorization()) return false;
            session_.reset();
        }
        joined_ = true;
        return true;
    }
    bool holdsCallbackContext() const {
        if (adapter_.onWorkerThread()) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        return static_cast<bool>(session_);
    }

private:
    struct CallbackState {
        CallbackState(std::uint64_t g, int descriptor,
                      ALooper_callbackFunc fn, void* client)
            : gate(g, descriptor), generation(g), callback(fn), userdata(client) {}
        RendererSessionCallbackContext gate;
        const std::uint64_t generation;
        ALooper_callbackFunc const callback;
        void* const userdata;
    };
    static int trampoline(int fd, int events, void* raw) {
        auto* state = static_cast<CallbackState*>(raw);
        auto lease = state->gate.enter(state->generation, fd);
        if (!lease) return 0;  // Close or stale descriptor: auto-deregister.
        return state->callback(fd, events, state->userdata);
    }
    mutable std::mutex mutex_;
    RendererOwnedLooperAdapter adapter_;
    std::unique_ptr<CallbackState> session_;
    bool closed_ = false;
    bool joined_ = false;
};
}  // namespace anvildesk_m8
