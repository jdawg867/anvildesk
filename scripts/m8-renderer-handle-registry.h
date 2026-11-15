// Isolated M8 native handle registry. Does not own Android/JNI/Lorie resources.
// An actual renderer integration must hold a Lease for every handle-using JNI
// operation and must retire resources only AFTER invalidateAndDrain succeeds.
#pragma once
#include <condition_variable>
#include <cstdint>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <utility>

namespace anvildesk_m8 {
template <class Resource>
class RendererHandleRegistry final {
    struct Entry {
        explicit Entry(std::shared_ptr<Resource> ptr) : resource(std::move(ptr)) {}
        std::mutex mutex;
        std::condition_variable drained;
        std::shared_ptr<Resource> resource;
        std::size_t active = 0;
        bool closing = false;
    };
public:
    using Handle = std::uint64_t;
    class Lease final {
    public:
        Lease() = default;
        Lease(const Lease&) = delete;
        Lease& operator=(const Lease&) = delete;
        Lease(Lease&& other) noexcept
            : entry_(std::move(other.entry_)), resource_(std::move(other.resource_)) {}
        Lease& operator=(Lease&& other) noexcept {
            if (this != &other) {
                release();
                entry_ = std::move(other.entry_);
                resource_ = std::move(other.resource_);
            }
            return *this;
        }
        ~Lease() { release(); }
        Resource* get() const noexcept { return resource_.get(); }
        explicit operator bool() const noexcept { return !!resource_; }
        void release() noexcept {
            if (!entry_) return;
            auto entry = std::move(entry_);
            resource_.reset();
            std::lock_guard<std::mutex> lock(entry->mutex);
            if (--entry->active == 0) entry->drained.notify_all();
        }
    private:
        friend class RendererHandleRegistry;
        Lease(std::shared_ptr<Entry> entry, std::shared_ptr<Resource> resource)
            : entry_(std::move(entry)), resource_(std::move(resource)) {}
        std::shared_ptr<Entry> entry_;
        std::shared_ptr<Resource> resource_;
    };
    RendererHandleRegistry() = default;
    RendererHandleRegistry(const RendererHandleRegistry&) = delete;
    RendererHandleRegistry& operator=(const RendererHandleRegistry&) = delete;

    // Never recycle a handle, including after resource teardown. Zero is invalid.
    Handle publish(std::shared_ptr<Resource> resource) {
        if (!resource) return 0;
        std::lock_guard<std::mutex> lock(mutex_);
        if (next_ == std::numeric_limits<Handle>::max()) return 0;
        const Handle handle = ++next_;
        entries_.emplace(handle, std::make_shared<Entry>(std::move(resource)));
        return handle;
    }
    Lease acquire(Handle handle) {
        std::shared_ptr<Entry> entry;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const auto it = entries_.find(handle);
            if (it == entries_.end()) return {};
            entry = it->second;
        }
        std::lock_guard<std::mutex> lock(entry->mutex);
        if (entry->closing) return {};
        ++entry->active;
        return Lease(entry, entry->resource);
    }
    // Must be called externally, never by a holder of the same handle Lease.
    // Invalidates the token atomically before waiting for accepted operations.
    // Returns the resource only after all prior leases have exited; caller
    // then performs owned-looper shutdown, JNI cleanup, renderer retirement.
    std::shared_ptr<Resource> invalidateAndDrain(Handle handle) {
        std::shared_ptr<Entry> entry;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const auto it = entries_.find(handle);
            if (it == entries_.end()) return {};
            entry = it->second;
            {
                std::lock_guard<std::mutex> entryLock(entry->mutex);
                entry->closing = true;
            }
            entries_.erase(it);
        }
        std::unique_lock<std::mutex> lock(entry->mutex);
        entry->drained.wait(lock, [&] { return entry->active == 0; });
        return std::move(entry->resource);
    }
private:
    std::mutex mutex_;
    std::map<Handle, std::shared_ptr<Entry>> entries_;
    Handle next_ = 0;
};
} // namespace anvildesk_m8
