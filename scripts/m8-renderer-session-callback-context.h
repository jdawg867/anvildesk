#pragma once
// Host-only M8 renderer session callback admission model.
// Does NOT wrap Android ALooper or protect its own userdata from late entry.
// Caller MUST retain this object until the owned poll worker has joined.
// Does not own renderer pointers, JNI refs, descriptors or Java handles.
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace anvildesk_m8 {
class RendererSessionCallbackContext final {
public:
    class Lease final {
    public:
        Lease() = default;
        Lease(const Lease&) = delete;
        Lease& operator=(const Lease&) = delete;
        Lease(Lease&& other) noexcept : owner_(other.owner_) {
            other.owner_ = nullptr;
        }
        Lease& operator=(Lease&&) = delete;
        ~Lease() {
            if (owner_) owner_->leave();
        }
        explicit operator bool() const noexcept { return owner_ != nullptr; }
    private:
        friend class RendererSessionCallbackContext;
        explicit Lease(RendererSessionCallbackContext* owner) : owner_(owner) {}
        RendererSessionCallbackContext* owner_ = nullptr;
    };

    RendererSessionCallbackContext(std::uint64_t generation, int fd)
        : generation_(generation), fd_(fd) {}
    RendererSessionCallbackContext(const RendererSessionCallbackContext&) = delete;
    RendererSessionCallbackContext& operator=(const RendererSessionCallbackContext&) = delete;

    // A generation is unique to a renderer session, never derived from FD.
    bool valid() const noexcept { return generation_ != 0 && fd_ >= 0; }
    Lease enter(std::uint64_t generation, int fd) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!valid() || stopping_ || generation != generation_ || fd != fd_)
            return {};
        ++active_;
        return Lease(this);
    }
    // Close admission without waiting, so an external looper owner can first
    // remove the FD and join its worker without holding a callback lock.
    void closeAdmission() {
        std::lock_guard<std::mutex> lock(mutex_);
        stopping_ = true;
        if (active_ == 0) drained_.notify_all();
    }
    // Call only outside callbacks. Leases alone do not establish platform
    // callback entry quiescence; the owned looper must be joined separately.
    void waitForLeases() {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!stopping_) return; // No implicit shutdown.
        drained_.wait(lock, [this] { return active_ == 0; });
    }
    bool admissionClosed() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return stopping_;
    }
    std::size_t activeLeases() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return active_;
    }
private:
    void leave() {
        std::lock_guard<std::mutex> lock(mutex_);
        --active_;
        if (stopping_ && active_ == 0) drained_.notify_all();
    }
    const std::uint64_t generation_;
    const int fd_;
    mutable std::mutex mutex_;
    std::condition_variable drained_;
    bool stopping_ = false;
    std::size_t active_ = 0;
};
}  // namespace anvildesk_m8
