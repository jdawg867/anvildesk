#pragma once
// Host-only M8 renderer replacement publication barrier.
// Does not patch or load Lorie. The previous session's retirement coordinator
// MUST own platform worker join, JNI reference deletion, and renderer cleanup.
// A failed or in-flight shutdown never authorizes next-generation admission.
#include <atomic>
#include <cstdint>
#include <mutex>

namespace anvildesk_m8 {
template <class PreviousRetirement>
class RendererReplacementHandoff final {
public:
    explicit RendererReplacementHandoff(PreviousRetirement& previous)
        : previous_(previous) {}
    RendererReplacementHandoff(const RendererReplacementHandoff&) = delete;
    RendererReplacementHandoff& operator=(const RendererReplacementHandoff&) = delete;

    bool retireAndPublish() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (published_.load(std::memory_order_acquire)) return true;
        if (!previous_.shutdownAndRetire()) return false;
        published_.store(true, std::memory_order_release);
        return true;
    }
    bool published() const noexcept {
        return published_.load(std::memory_order_acquire);
    }
    template <class NextOwner, class Callback>
    bool registerReplacement(NextOwner& next, std::uint64_t generation,
                             int fd, int events, Callback callback,
                             void* clientData) const {
        // The barrier is monotonic: false while retireAndPublish() is
        // blocked or failed, true only after the entire prior cleanup.
        if (!published()) return false;
        return next.registerCallback(generation, fd, events,
                                     callback, clientData);
    }
private:
    PreviousRetirement& previous_;
    std::mutex mutex_;
    std::atomic<bool> published_{false};
};
} // namespace anvildesk_m8
