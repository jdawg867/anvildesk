#pragma once
// M8 renderer ownership *protocol model* (host-only).
// No Android looper, renderer, JNI, pointer ownership, or runtime integration.
// The caller must establish actual worker-join evidence externally; merely
// calling acknowledgeWorkerJoined() is NOT Android callback quiescence proof.
#include <cstdint>

namespace anvildesk_m8 {

class RendererCallbackOwnershipModel final {
public:
    enum class State {
        Empty, Registered, RemovalPending, RemovalFailed,
        AwaitingJoin, Joined, Released
    };
    State state() const { return state_; }
    std::uint64_t generation() const { return generation_; }
    bool admissionOpen() const { return state_ == State::Registered; }
    bool mayFreeUserdata() const { return state_ == State::Joined; }

    bool registerSession(std::uint64_t generation) {
        if (state_ != State::Empty || generation == 0) return false;
        generation_ = generation;
        state_ = State::Registered;
        return true;
    }
    bool callbackMayEnter(std::uint64_t generation) const {
        return state_ == State::Registered && generation == generation_;
    }
    bool beginShutdown() {
        if (state_ != State::Registered) return false;
        state_ = State::RemovalPending;
        return true;
    }
    bool recordRemoveFailure() {
        if (state_ != State::RemovalPending) return false;
        state_ = State::RemovalFailed;
        return true;
    }
    bool retryRemoval() {
        if (state_ != State::RemovalFailed) return false;
        state_ = State::RemovalPending;
        return true;
    }
    // removeFd returning 0 or 1 permits moving to join, but NOT free.
    bool recordRemoveSuccessOrAbsent() {
        if (state_ != State::RemovalPending) return false;
        state_ = State::AwaitingJoin;
        return true;
    }
    // Must be invoked only after real callback-worker join succeeds.
    // No self-join and no release on timeout/error.
    bool acknowledgeWorkerJoined() {
        if (state_ != State::AwaitingJoin) return false;
        state_ = State::Joined;
        return true;
    }
    bool releaseUserdata() {
        if (!mayFreeUserdata()) return false;
        state_ = State::Released;
        return true;
    }
private:
    State state_ = State::Empty;
    std::uint64_t generation_ = 0;
};
}  // namespace anvildesk_m8
