#pragma once

// Experimental callback-drain protocol for isolated M8 host-side tests.
// NOT wired into libXlorie.so and NOT proof that Android's looper has drained.
// The caller must deregister the callback source and guarantee this gate stays
// alive until no new callback can access it. Shutdown must run outside callbacks.
#include <condition_variable>
#include <cstddef>
#include <memory>
#include <mutex>

namespace anvildesk_m8 {

class CallbackDrain final {
public:
    class Lease final {
    public:
        Lease(const Lease&) = delete;
        Lease& operator=(const Lease&) = delete;
        ~Lease() { owner_->leave(); }

    private:
        friend class CallbackDrain;
        explicit Lease(CallbackDrain* owner) : owner_(owner) {}
        CallbackDrain* const owner_;
    };

    CallbackDrain() = default;
    CallbackDrain(const CallbackDrain&) = delete;
    CallbackDrain& operator=(const CallbackDrain&) = delete;

    // A lease protects resources only after a callback has reached acquire().
    // An external looper must independently prevent access after gate deletion.
    std::unique_ptr<Lease> acquire() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (stopping_) return nullptr;
        auto lease = std::unique_ptr<Lease>(new Lease(this));
        ++active_;
        return lease;
    }

    // Cannot be invoked from a callback holding a Lease.
    // The gate must not be destroyed before this returns and all callback
    // entry points are externally quiesced.
    void shutdownAndDrain() {
        std::unique_lock<std::mutex> lock(mutex_);
        stopping_ = true;
        drained_.wait(lock, [this] { return active_ == 0; });
    }

    bool stopping() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return stopping_;
    }

private:
    void leave() {
        std::lock_guard<std::mutex> lock(mutex_);
        // Every Lease is created only after an active_ increment.
        --active_;
        if (stopping_ && active_ == 0) drained_.notify_all();
    }

    mutable std::mutex mutex_;
    std::condition_variable drained_;
    std::size_t active_ = 0;
    bool stopping_ = false;
};

}  // namespace anvildesk_m8
