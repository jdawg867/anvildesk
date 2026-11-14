#pragma once
// Host-only lifecycle decision model. NOT a production callback adapter.
// In particular, no transition to "safe to free" exists: Android platform
// callback quiescence has not been established by NDK removeFd.
namespace anvildesk_m8 {

class LooperTeardownGate final {
public:
    enum class State {
        Active,
        RemovalPending,
        RemovalFailed,
        HostDrainPending,
        AwaitingAndroidQuiescence
    };
    enum class RemoveOutcome { Removed, NotRegistered, Error };

    State state() const { return state_; }
    bool canRegister() const { return state_ == State::Active; }
    bool canReleaseNativeResources() const { return false; }

    bool beginShutdown() {
        if (state_ != State::Active) return false;
        state_ = State::RemovalPending;
        return true;
    }

    bool retryRemoval() {
        if (state_ != State::RemovalFailed) return false;
        state_ = State::RemovalPending;
        return true;
    }

    bool reportRemoval(RemoveOutcome outcome) {
        if (state_ != State::RemovalPending) return false;
        if (outcome == RemoveOutcome::Error) {
            state_ = State::RemovalFailed;
        } else {
            // NotRegistered is not Android quiescence either.
            state_ = State::HostDrainPending;
        }
        return true;
    }

    bool reportHostDrain() {
        if (state_ != State::HostDrainPending) return false;
        state_ = State::AwaitingAndroidQuiescence;
        return true;
    }

private:
    State state_ = State::Active;
};
} // namespace anvildesk_m8
