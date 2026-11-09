// M8 isolated Android NDK dedicated-looper-thread compile prototype.
// NOT shipped, NOT used by renderer, and does not register X11 callbacks.
// ALooper_removeFd cannot prove that callbacks have stopped. A dedicated
// owned poll thread can establish its own completion only after join.
// All future FD registrations must be confined to this thread or routed
// through an explicitly synchronized command queue before reuse.
#include <android/looper.h>
#include <atomic>
#include <condition_variable>
#include <exception>
#include <mutex>
#include <stdexcept>
#include <thread>

#if !defined(__ANDROID__)
#error "Compile with the Android NDK; this is not a host simulator."
#endif

namespace anvildesk_m8 {

class DedicatedLooperThread final {
public:
    DedicatedLooperThread()
        : worker_([this] { pollLoop(); }), workerId_(worker_.get_id()) {
        std::unique_lock<std::mutex> lock(mutex_);
        started_.wait(lock, [this] { return ready_; });
        // No callback or userdata is registered by this prototype.
    }

    DedicatedLooperThread(const DedicatedLooperThread&) = delete;
    DedicatedLooperThread& operator=(const DedicatedLooperThread&) = delete;

    ~DedicatedLooperThread() noexcept {
        // Caller must explicitly join before destruction. A destructor
        // cannot promise quiescence while other threads access this object.
        if (worker_.joinable()) std::terminate();
    }

    // Competing external callers join exactly once. Do not call this from
    // callbacks on the owned worker; a self-join would deadlock.
    void shutdownAndJoin() {
        if (std::this_thread::get_id() == workerId_)
            throw std::logic_error("dedicated looper cannot join itself");
        std::lock_guard<std::mutex> joinLock(joinMutex_);
        if (joined_) return;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stopping_ = true;
            if (looper_) ALooper_wake(looper_);
        }
        worker_.join();
        joined_ = true;
        // The thread can no longer poll or invoke its callbacks. Any future
        // callbacks would have to come from a different, unmodeled looper.
        if (looper_) ALooper_release(looper_);
        looper_ = nullptr;
    }

    bool initialized() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return prepared_;
    }

private:
    void pollLoop() {
        ALooper* local = ALooper_prepare(0);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            looper_ = local;
            prepared_ = local != nullptr;
            // Hold an independent reference while the other thread may
            // invoke ALooper_wake(). Released only after worker joins.
            if (local) ALooper_acquire(local);
            ready_ = true;
        }
        started_.notify_all();
        if (!local) return;
        for (;;) {
            {
                std::lock_guard<std::mutex> lock(mutex_);
                if (stopping_) break;
            }
            // pollOnce invokes callback-mode FD handlers on this worker.
            // For the prototype no FD is registered. Wake breaks the wait.
            ALooper_pollOnce(-1, nullptr, nullptr, nullptr);
        }
    }

    mutable std::mutex mutex_;
    std::condition_variable started_;
    ALooper* looper_ = nullptr; // protected by mutex until joined
    bool ready_ = false;
    bool prepared_ = false;
    bool stopping_ = false;
    std::thread worker_;
    const std::thread::id workerId_; // immutable, safe across concurrent joins
    std::mutex joinMutex_;
    bool joined_ = false; // protected by joinMutex_
};

} // namespace anvildesk_m8

// No instantiation or runtime use; CI only compiles this boundary.
