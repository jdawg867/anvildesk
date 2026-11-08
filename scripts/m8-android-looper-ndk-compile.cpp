// Compile-only Android NDK boundary for M8 looper ownership.
// No callback registration, JNI loading or production integration.
// ALooper_removeFd does not promise queued callback quiescence.
#include <android/looper.h>
#include <type_traits>

#if !defined(__ANDROID__)
#error "This translation unit must be compiled with an Android NDK target."
#endif

namespace anvildesk_m8 {

enum class RemoveStatus { Removed, NotRegistered, Error };

class RetainedLooper final {
public:
    explicit RetainedLooper(ALooper* registeringLooper) : looper_(registeringLooper) {
        if (looper_) ALooper_acquire(looper_);
    }
    ~RetainedLooper() {
        if (looper_) ALooper_release(looper_);
    }
    RetainedLooper(const RetainedLooper&) = delete;
    RetainedLooper& operator=(const RetainedLooper&) = delete;
    RetainedLooper(RetainedLooper&&) = delete;
    RetainedLooper& operator=(RetainedLooper&&) = delete;

    RemoveStatus remove(int fd) const {
        if (!looper_ || fd < 0) return RemoveStatus::Error;
        const int result = ALooper_removeFd(looper_, fd);
        switch (result) {
            case 1: return RemoveStatus::Removed;
            case 0: return RemoveStatus::NotRegistered;
            default: return RemoveStatus::Error;
        }
    }

private:
    ALooper* const looper_;
};

// Signature checks ensure we're compiling against actual Android NDK APIs.
static_assert(std::is_same_v<decltype(ALooper_forThread()), ALooper*>);
static_assert(std::is_same_v<decltype(ALooper_removeFd(
    static_cast<ALooper*>(nullptr), 1)), int>);
static_assert(!std::is_copy_constructible_v<RetainedLooper>);
static_assert(!std::is_move_constructible_v<RetainedLooper>);

} // namespace anvildesk_m8

// No 'main': this translation unit is only compiled with -fsyntax-only.
// In particular, it does NOT solve void* callback userdata lifetime.
