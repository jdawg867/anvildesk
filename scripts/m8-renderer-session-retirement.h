#pragma once
// Isolated lifecycle coordinator. Does not load or patch Lorie or Xorg.
// Owner must provide shutdownAndJoin() with actual worker-join semantics;
// renderer / Java callers must prevent use of client resources after joining.
// A failure retains resources and reference ownership for an explicit retry.
#include <mutex>
namespace anvildesk_m8 {
template <class Owner, class References, class ResourceRetirer>
class RendererSessionRetirement final {
public:
    RendererSessionRetirement(Owner& owner, References& references,
                              ResourceRetirer& resources)
        : owner_(owner), refs_(references), resources_(resources) {}
    RendererSessionRetirement(const RendererSessionRetirement&) = delete;
    RendererSessionRetirement& operator=(const RendererSessionRetirement&) = delete;

    // NEVER called on the owned callback worker: shutdownAndJoin may reject
    // self-join. Does not swallow exceptions or claim join from an input flag.
    bool shutdownAndRetire() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (retired_) return true;
        if (!joined_) {
            if (!owner_.shutdownAndJoin()) return false;
            joined_ = true;
        }
        // The owner now supplies actual worker-join confirmation rather than
        // trusting a boolean provided by a client/callback.
        if (!refs_.retireAfterVerifiedJoin(joined_)) return false;
        if (!resourcesRetired_) {
            if (!resources_.retire()) return false;
            resourcesRetired_ = true;
        }
        retired_ = true;
        return true;
    }
    bool workerJoined() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return joined_;
    }
    bool retired() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return retired_;
    }
private:
    Owner& owner_;
    References& refs_;
    ResourceRetirer& resources_;
    mutable std::mutex mutex_;
    bool joined_ = false;
    bool resourcesRetired_ = false;
    bool retired_ = false;
};
} // namespace anvildesk_m8
