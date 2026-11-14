#pragma once
// Experimental global-reference retirement boundary. Isolated from Lorie.
// A join-confirmation argument is a caller assertion, NOT independent proof
// of looper quiescence. Never free JNI refs from callback entry, from a
// worker still dispatching callbacks, or from an arbitrary stored JNIEnv*.
#include <mutex>
namespace anvildesk_m8 {
template <class Backend>
class DeferredGlobalRefRetirement final {
public:
    using Ref = typename Backend::Ref;
    DeferredGlobalRefRetirement(Backend& backend, Ref reference)
        : backend_(backend), reference_(reference) {}
    DeferredGlobalRefRetirement(const DeferredGlobalRefRetirement&) = delete;
    DeferredGlobalRefRetirement& operator=(const DeferredGlobalRefRetirement&) = delete;

    // Does nothing without a confirmed external worker join. If current
    // thread cannot use JNI, or has pending JNI exception, retain reference
    // for explicit retry on a safe attached thread after join.
    bool retireAfterVerifiedJoin(bool workerJoined) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!workerJoined) return false;
        if (retired_) return true;
        if (reference_ && !backend_.deleteGlobalRef(reference_)) return false;
        reference_ = nullptr;
        retired_ = true;
        return true;
    }
    bool retired() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return retired_;
    }
    bool holdsReference() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return reference_ != nullptr;
    }
private:
    Backend& backend_;
    mutable std::mutex mutex_;
    Ref reference_;
    bool retired_ = false;
};
} // namespace anvildesk_m8
