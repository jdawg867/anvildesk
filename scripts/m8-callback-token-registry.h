#pragma once
// Host-only registration token model. This does not integrate ALooper or JNI.
// Dispatch resolves an ID under a mutex, never dereferencing a stale userdata pointer.
#include "m8-native-callback-drain.h"
#include <condition_variable>
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
        // A context that has already begun draining cannot be resurrected
        // by handing it a fresh registration token.
        if (context->drain.stopping())
            throw std::invalid_argument("stopped callback context");
        // One context must have exactly one live registration. Otherwise
        // retiring one token would shut down another token's callbacks.
        for (const auto& record : registered_) {
            if (record.second->context.get() == context.get())
                throw std::invalid_argument("context already registered");
        }
        // Never recycle tokens, even after unregister. Fail shut on exhaustion.
        if (next_ == std::numeric_limits<Token>::max())
            throw std::overflow_error("callback token exhausted");
        Token id = ++next_;
        registered_.emplace(id, std::make_shared<Entry>(std::move(context)));
        return id;
    }

    Handle enter(Token id) {
        // Synchronize resolution with unregister. A live shared_ptr protects
        // Context while the lease protects its callback-dependent resources.
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = registered_.find(id);
        if (it == registered_.end()) return {};
        auto entry = it->second;
        if (entry->closing) return {};
        auto context = entry->context;
        auto lease = context->drain.acquire();
        return {std::move(context), std::move(lease)};
    }

    // A closing registration stays in the table until draining completes.
    // Duplicate stop callers wait on the same completion barrier.
    // Caller must separately deregister its platform callback and guarantee
    // raw platform userdata cannot be accessed after it is freed.
    void unregisterAndDrain(Token id) {
        std::shared_ptr<Entry> entry;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            auto it = registered_.find(id);
            if (it == registered_.end()) return; // already fully drained
            entry = it->second;
            if (entry->closing) {
                entry->finished.wait(lock, [&] { return entry->drained; });
                return;
            }
            entry->closing = true; // blocks new admission before drain
        }
        // Must not hold registry mutex while waiting for outstanding leases.
        entry->context->drain.shutdownAndDrain();
        {
            std::lock_guard<std::mutex> lock(mutex_);
            entry->drained = true;
            registered_.erase(id);
        }
        entry->finished.notify_all();
    }

private:
    struct Entry final {
        explicit Entry(std::shared_ptr<Context> value)
            : context(std::move(value)) {}
        std::shared_ptr<Context> context;
        bool closing = false;
        bool drained = false;
        std::condition_variable finished;
    };
    std::mutex mutex_;
    Token next_ = 0;
    std::unordered_map<Token, std::shared_ptr<Entry>> registered_;
};
} // namespace anvildesk_m8
