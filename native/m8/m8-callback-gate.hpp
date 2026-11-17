// M8 host-side callback admission gate. Not an Android/ALooper integration.
// Copyright (c) AnvilDesk contributors. SPDX-License-Identifier: MIT
#pragma once

#include <condition_variable>
#include <cstddef>
#include <mutex>
#include <utility>

namespace anvildesk::m8 {

// A gate does NOT own an Android callback userdata pointer. The owner must
// unregister on the SAME ALooper, establish callback dispatch quiescence,
// and keep this gate alive until no further calls to enter() are possible.
// close_and_wait() must not be called by a holder of its own Lease.
class CallbackGate final {
public:
    class Lease final {
    public:
        Lease() noexcept = default;
        Lease(const Lease&) = delete;
        Lease& operator=(const Lease&) = delete;
        Lease(Lease&& other) noexcept : gate_(std::exchange(other.gate_, nullptr)) {}
        Lease& operator=(Lease&& other) noexcept {
            if (this != &other) {
                release();
                gate_ = std::exchange(other.gate_, nullptr);
            }
            return *this;
        }
        ~Lease() { release(); }
        explicit operator bool() const noexcept { return gate_ != nullptr; }
        void release() noexcept {
            CallbackGate* gate = std::exchange(gate_, nullptr);
            if (gate) gate->leave();
        }
    private:
        friend class CallbackGate;
        explicit Lease(CallbackGate* gate) noexcept : gate_(gate) {}
        CallbackGate* gate_ = nullptr;
    };

    CallbackGate() = default;
    CallbackGate(const CallbackGate&) = delete;
    CallbackGate& operator=(const CallbackGate&) = delete;

    // Admission and close share one mutex, so admission cannot race past close.
    [[nodiscard]] Lease enter() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (closing_) return {};
        ++active_;
        return Lease(this);
    }

    // Idempotent. Blocks until all admitted callbacks have left. Does NOT
    // unregister a platform callback or prove its dispatcher is quiescent.
    void close_and_wait() {
        std::unique_lock<std::mutex> lock(mutex_);
        closing_ = true;
        drained_.wait(lock, [this] { return active_ == 0; });
    }

    [[nodiscard]] bool is_closing() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return closing_;
    }

private:
    void leave() noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        if (--active_ == 0) drained_.notify_all();
    }

    mutable std::mutex mutex_;
    std::condition_variable drained_;
    bool closing_ = false;
    std::size_t active_ = 0;
};

} // namespace anvildesk::m8
