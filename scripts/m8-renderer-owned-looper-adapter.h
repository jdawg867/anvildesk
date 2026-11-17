#pragma once
// Isolated M8 integration experiment. NOT a production renderer adapter.
// Include after the DedicatedLooperThread prototype is defined.
// The caller keeps userdata alive until releaseAuthorization() succeeds.
// This class does not own/free userdata, JNI refs, renderer handles or X11 FDs.
#include "m8-renderer-callback-ownership-model.h"
#include <cstdint>
#include <mutex>
#include <stdexcept>

namespace anvildesk_m8 {

class RendererOwnedLooperAdapter final {
public:
    RendererOwnedLooperAdapter() = default;
    RendererOwnedLooperAdapter(const RendererOwnedLooperAdapter&) = delete;
    RendererOwnedLooperAdapter& operator=(const RendererOwnedLooperAdapter&) = delete;

    // Immutable worker identity; safe before acquiring any shutdown mutex.
    bool onWorkerThread() const noexcept { return looper_.onWorkerThread(); }

    bool registerCallback(std::uint64_t generation, int fd, int events,
                          ALooper_callbackFunc callback, void* userdata) {
        // A callback must never block behind an external join of itself.
        if (looper_.onWorkerThread()) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        if (!looper_.initialized() || model_.state() !=
            RendererCallbackOwnershipModel::State::Empty || generation == 0)
            return false;
        if (!looper_.registerCallback(fd, events, callback, userdata))
            return false;
        // No user callback can access the model; caller owns userdata.
        return model_.registerSession(generation);
    }

    // External shutdown only; never call from an owned-thread callback.
    // A removal failure retains the FD, worker and userdata for retry.
    bool shutdownAndJoin() {
        // Check BEFORE taking mutex_: an external caller may already hold
        // that mutex while joining this callback's worker. Otherwise a
        // self-join attempt deadlocks and can poison the ownership state.
        if (looper_.onWorkerThread())
            throw std::logic_error("renderer callback owner cannot join itself");
        std::lock_guard<std::mutex> lock(mutex_);
        using State = RendererCallbackOwnershipModel::State;
        const auto state = model_.state();
        if (state == State::Registered) {
            if (!model_.beginShutdown()) return false;
        } else if (state == State::RemovalFailed) {
            if (!model_.retryRemoval()) return false;
        } else if (state == State::Joined || state == State::Released) {
            return true;
        } else if (state != State::Empty) {
            return false;
        }

        // A successful call establishes both removal and worker completion.
        // Never advance to Joined before the real worker join returns.
        if (!looper_.shutdownAndJoin()) {
            if (model_.state() == State::RemovalPending)
                model_.recordRemoveFailure();
            return false;
        }
        if (model_.state() == State::Empty) return true; // no userdata exists
        if (!model_.recordRemoveSuccessOrAbsent()) return false;
        return model_.acknowledgeWorkerJoined();
    }

    // An authorization token only. Freeing caller-owned userdata and JNI
    // objects remains an external responsibility after callback quiescence.
    bool releaseAuthorization() {
        // A callback cannot authorize freeing its own userdata, nor wait
        // behind an external shutdown that is joining that callback.
        if (looper_.onWorkerThread()) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        return model_.releaseUserdata();
    }

    bool userdataReleaseEligible() const {
        if (looper_.onWorkerThread()) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        return model_.mayFreeUserdata();
    }
private:
    mutable std::mutex mutex_;
    DedicatedLooperThread looper_;
    RendererCallbackOwnershipModel model_;
};
}  // namespace anvildesk_m8
