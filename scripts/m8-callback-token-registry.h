#pragma once
// Host-only registration token model. This does not integrate ALooper or JNI.
// Dispatch resolves an ID under a mutex, never dereferencing a stale userdata pointer.
#include "m8-native-callback-drain.h"
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <unordered_map>
#include <utility>

namespace anvildesk_m8 {

class CallbackTokenRegistry final {
public:
    using Token = std::uint64_t;
    struct Context final {
        CallbackDrain drain;
    };
    struct Handle final {
        std::shared_ptr<Context> context;
        std::unique_ptr<CallbackDrain::Lease> lease;
        explicit operator bool() const { return lease != nullptr; }
    };

    Token registerContext(std::shared_ptr<Context> context) {
        if (!context) throw std::invalid_argument("null callback context");
        std::lock_guard<std::mutex> lock(mutex_);
        // Never recycle tokens, even after unregister. Fail shut on exhaustion.
        if (next_ == std::numeric_limits<Token>::max())
            throw std::overflow_error("callback token exhausted");
        Token id = ++next_;
        registered_.emplace(id, std::move(context));
        return id;
    }

    Handle enter(Token id) {
        // Synchronize resolution with unregister. A live shared_ptr protects
        // Context while the lease protects its callback-dependent resources.
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = registered_.find(id);
        if (it == registered_.end()) return {};
        auto context = it->second;
        auto lease = context->drain.acquire();
        return {std::move(context), std::move(lease)};
    }

    // Remove token first, then drain outside the registry mutex.
    // Caller must also deregister the platform callback and ensure no
    // native callback is still touching unowned userdata on teardown.
    void unregisterAndDrain(Token id) {
        std::shared_ptr<Context> context;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            auto it = registered_.find(id);
            if (it == registered_.end()) return;
            context = std::move(it->second);
            registered_.erase(it);
        }
        context->drain.shutdownAndDrain();
    }

private:
    std::mutex mutex_;
    Token next_ = 0;
    std::unordered_map<Token, std::shared_ptr<Context>> registered_;
};
} // namespace anvildesk_m8
