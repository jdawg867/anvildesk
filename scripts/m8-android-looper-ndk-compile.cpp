// Compile-only Android NDK boundary for M8 looper ownership.
// No runtime registration, JNI loading or production integration.
// ALooper_removeFd does not promise queued callback quiescence.
#include <android/looper.h>
#include "m8-looper-teardown-gate.h"
#include <exception>
#include <mutex>
#include <type_traits>
#include <unordered_set>

#if !defined(__ANDROID__)
#error "This translation unit must be compiled with an Android NDK target."
#endif

namespace anvildesk_m8 {

enum class RemoveStatus { Removed, NotRegistered, NotTracked, Error };
enum class AddStatus { Added, AlreadyTracked, ShuttingDown, Error };

class RetainedLooper final {
public:
    explicit RetainedLooper(ALooper* registeringLooper) : looper_(registeringLooper) {
        if (looper_) ALooper_acquire(looper_);
    }
    // Fail closed rather than silently orphan active registrations.
    // Owner must stop registration attempts and join all adapter callers
    // BEFORE destruction; this is not an Android callback-drain barrier.
    ~RetainedLooper() noexcept {
        if (!registered_.empty()) std::terminate();
        if (looper_) ALooper_release(looper_);
    }
    RetainedLooper(const RetainedLooper&) = delete;
    RetainedLooper& operator=(const RetainedLooper&) = delete;
    RetainedLooper(RetainedLooper&&) = delete;
    RetainedLooper& operator=(RetainedLooper&&) = delete;

    // Once shutdown starts, no later registration may reopen the adapter.
    // Call this before deregistration and callback-drain coordination.
    // This only blocks add() calls; it does NOT drain callbacks.
    void beginShutdown() {
        std::lock_guard<std::mutex> lock(mutex_);
        shuttingDown_ = true;
    }

    // Non-owning userdata: the caller MUST ensure the pointed-to object
    // survives a possible final callback after removeFd. Never pass a
    // LorieViewResources* as data without an independent lifetime scheme.
    // NDK addFd would replace a prior registration for the same fd.
    // Serialize registration and reject descriptors already owned by this
    // adapter. Other code using the same looper must coordinate separately.
    AddStatus add(int fd, int events, ALooper_callbackFunc callback,
                  void* stableUserdata) {
        if (!looper_ || fd < 0 || events == 0 || !callback || !stableUserdata)
            return AddStatus::Error;
        std::lock_guard<std::mutex> lock(mutex_);
        if (shuttingDown_) return AddStatus::ShuttingDown;
        if (registered_.count(fd)) return AddStatus::AlreadyTracked;
        const int result = ALooper_addFd(
            looper_, fd, ALOOPER_POLL_CALLBACK, events,
            callback, stableUserdata);
        if (result != 1) return AddStatus::Error;
        registered_.insert(fd);
        return AddStatus::Added;
    }

    RemoveStatus remove(int fd) {
        if (!looper_ || fd < 0) return RemoveStatus::Error;
        std::lock_guard<std::mutex> lock(mutex_);
        if (!registered_.count(fd)) return RemoveStatus::NotTracked;
        const int result = ALooper_removeFd(looper_, fd);
        if (result < 0) return RemoveStatus::Error; // retain tracking for retry
        registered_.erase(fd);
        return result == 1 ? RemoveStatus::Removed
                           : RemoveStatus::NotRegistered;
    }

private:
    ALooper* const looper_;
    std::mutex mutex_;
    std::unordered_set<int> registered_;
    bool shuttingDown_ = false;
};

// Single-registration compile-only composition of actual NDK removal and the
// host teardown gate. Owner serializes calls; lifetime is NOT Android-safe.
class SingleFdShutdownCoordinator final {
public:
    SingleFdShutdownCoordinator(RetainedLooper& looper, int fd)
        : looper_(looper), fd_(fd) {}
    SingleFdShutdownCoordinator(const SingleFdShutdownCoordinator&) = delete;
    SingleFdShutdownCoordinator& operator=(const SingleFdShutdownCoordinator&) = delete;

    // Close admission before attempting to remove the registered FD.
    bool beginShutdown() {
        if (fd_ < 0 || gate_.state() != LooperTeardownGate::State::Active)
            return false;
        looper_.beginShutdown();
        return gate_.beginShutdown();
    }

    // Explicitly retry after a removal error; it must not reopen admission.
    bool retryRemoval() { return gate_.retryRemoval(); }

    // A descriptor absent from this adapter's tracking is not an NDK
    // removal success. Treat it as error rather than granting drain phase.
    bool removeRegisteredFd() {
        if (gate_.state() != LooperTeardownGate::State::RemovalPending)
            return false;
        const auto status = looper_.remove(fd_);
        LooperTeardownGate::RemoveOutcome outcome;
        switch (status) {
            case RemoveStatus::Removed:
                outcome = LooperTeardownGate::RemoveOutcome::Removed;
                break;
            case RemoveStatus::NotRegistered:
                outcome = LooperTeardownGate::RemoveOutcome::NotRegistered;
                break;
            case RemoveStatus::NotTracked:
            case RemoveStatus::Error:
            default:
                outcome = LooperTeardownGate::RemoveOutcome::Error;
                break;
        }
        return gate_.reportRemoval(outcome);
    }

    // This can be called ONLY after the separate callback-drain implementation
    // confirms completion. This wrapper does not perform that drain.
    bool reportExternallyVerifiedHostDrain() { return gate_.reportHostDrain(); }

    LooperTeardownGate::State state() const { return gate_.state(); }
    bool canReleaseNativeResources() const {
        return gate_.canReleaseNativeResources(); // always false
    }

private:
    RetainedLooper& looper_;
    const int fd_;
    LooperTeardownGate gate_;
};

// Signature checks ensure we're compiling against actual Android NDK APIs.
static_assert(std::is_same_v<decltype(ALooper_forThread()), ALooper*>);
static_assert(std::is_same_v<decltype(ALooper_addFd(
    static_cast<ALooper*>(nullptr), 1, ALOOPER_POLL_CALLBACK,
    ALOOPER_EVENT_INPUT, static_cast<ALooper_callbackFunc>(nullptr),
    static_cast<void*>(nullptr))), int>);
static_assert(std::is_same_v<decltype(ALooper_removeFd(
    static_cast<ALooper*>(nullptr), 1)), int>);
static_assert(!std::is_copy_constructible_v<RetainedLooper>);
static_assert(!std::is_move_constructible_v<RetainedLooper>);
static_assert(!std::is_copy_constructible_v<SingleFdShutdownCoordinator>);
static_assert(std::is_same_v<decltype(
    std::declval<SingleFdShutdownCoordinator&>().removeRegisteredFd()), bool>);
static_assert(std::is_same_v<decltype(
    std::declval<const SingleFdShutdownCoordinator&>().canReleaseNativeResources()), bool>);

} // namespace anvildesk_m8

// No 'main': this translation unit is only compiled with -fsyntax-only.
// It does NOT solve void* callback userdata lifetime, external looper users,
// callbacks arriving after removal, or coordination with other looper users.
// Outstanding tracked FDs are a fatal programming error at destruction;
// even zero tracked FDs does NOT prove callbacks are quiescent.
// The coordinator is intentionally single-FD, externally serialized and
// requires caller-provided host-drain evidence; it cannot free native state.
