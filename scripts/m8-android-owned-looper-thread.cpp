// M8 isolated Android NDK dedicated-looper-thread compile prototype.
// NOT shipped or used by renderer. No X11 callbacks are registered in CI.
// ALooper_removeFd cannot prove that callbacks have stopped. A dedicated
// owned poll thread can establish its own completion only after join.
// This prototype registers at most one FD under a mutex, routes callbacks
// exclusively to the owned polling thread, and joins it before userdata free.
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
        // No callback or userdata is registered by the compile-only CI step.
    }

    DedicatedLooperThread(const DedicatedLooperThread&) = delete;
    DedicatedLooperThread& operator=(const DedicatedLooperThread&) = delete;

    ~DedicatedLooperThread() noexcept {
        // Caller must explicitly join before destruction. A destructor
        // cannot promise quiescence while other threads access this object.
        if (worker_.joinable()) std::terminate();
    }

    // Register one callback FD for dispatch exclusively on the owned poll
    // thread. External caller retains userdata through shutdownAndJoin().
    // The mutex closes admission atomically with shutdown, preventing a
    // late registration from replacing a deregistered descriptor.
    bool registerCallback(int fd, int events, ALooper_callbackFunc callback,
                          void* stableUserdata) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!prepared_ || admissionClosed_ || activeFd_ >= 0 ||
            fd < 0 || events == 0 || !callback || !stableUserdata)
            return false;
        const int result = ALooper_addFd(
            looper_, fd, ALOOPER_POLL_CALLBACK, events, callback, stableUserdata);
        if (result != 1) return false;
        activeFd_ = fd;
        return true;
    }

    // Competing external callers join exactly once. Do not call this from
    // callbacks on the owned worker; a self-join would deadlock.
    // Return false on failed deregistration. Admission stays closed and
    // userdata MUST remain alive; a later external caller may retry.
    // Caller must also ensure all other users of this object have stopped.
    bool shutdownAndJoin() {
        if (std::this_thread::get_id() == workerId_)
            throw std::logic_error("dedicated looper cannot join itself");
        std::lock_guard<std::mutex> joinLock(joinMutex_);
        if (joined_) return true;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            admissionClosed_ = true;
            if (activeFd_ >= 0) {
                // A result of zero means the callback already deregistered
                // itself. Negative results are errors and must not free data.
                if (ALooper_removeFd(looper_, activeFd_) < 0) return false;
                activeFd_ = -1;
            }
            stopping_ = true;
            if (looper_) ALooper_wake(looper_);
        }
        worker_.join(); // waits for any in-flight owned-thread callback
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (looper_) ALooper_release(looper_);
            looper_ = nullptr;
        }
        joined_ = true;
        return true;
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
            // Wake breaks the wait. Removal precedes wake during shutdown.
            ALooper_pollOnce(-1, nullptr, nullptr, nullptr);
        }
    }

    mutable std::mutex mutex_;
    std::condition_variable started_;
    ALooper* looper_ = nullptr; // protected by mutex until joined
    bool ready_ = false;
    bool prepared_ = false;
    bool admissionClosed_ = false;
    int activeFd_ = -1;
    bool stopping_ = false;
    std::thread worker_;
    const std::thread::id workerId_; // immutable, safe across concurrent joins
    std::mutex joinMutex_;
    bool joined_ = false; // protected by joinMutex_
};

} // namespace anvildesk_m8

// No instantiation or runtime use; CI only compiles this boundary.
