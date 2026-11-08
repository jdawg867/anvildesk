// Deterministic host simulation of Android ALooper removal semantics.
// No Android looper calls, JNI, production X11 library loading or device access.
#include "m8-callback-token-registry.h"
#include <cstdint>
#include <functional>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <unordered_map>
#include <utility>

using anvildesk_m8::CallbackTokenRegistry;
static void require(bool ok, const char* why) { if (!ok) throw std::runtime_error(why); }

// The queue owns a callable (not Android's raw void* userdata). This explicitly
// DOES NOT prove the memory lifetime of raw Android userdata after removeFd.
class FakeLooper {
public:
    using Callback = std::function<bool()>;
    enum class RemoveResult { Removed, NotRegistered, Error };
    void add(int fd, Callback cb) {
        if (!entries_.emplace(fd, std::move(cb)).second)
            throw std::runtime_error("duplicate descriptor");
    }
    Callback snapshot(int fd) const {
        auto it = entries_.find(fd);
        return it == entries_.end() ? Callback{} : it->second;
    }
    RemoveResult remove(int fd) {
        if (failRemove_) return RemoveResult::Error;
        return entries_.erase(fd) ? RemoveResult::Removed : RemoveResult::NotRegistered;
    }
    void failRemove(bool fail) { failRemove_ = fail; }
    bool hasFd(int fd) const { return entries_.count(fd) != 0; }
private:
    bool failRemove_ = false;
    std::unordered_map<int, Callback> entries_;
};

struct Registration {
    std::shared_ptr<CallbackTokenRegistry::Context> context;
    CallbackTokenRegistry::Token token;
    int fd;
    bool retired = false;
};

// The test captures a registry reference only while the registry is still
// alive. A production adapter must provide its own independent registry lifetime.
static FakeLooper::Callback callbackFor(CallbackTokenRegistry& registry,
                                        CallbackTokenRegistry::Token token) {
    return [&registry, token] {
        auto lease = registry.enter(token);
        return bool(lease);
    };
}

static void testCallbackAfterRemoval() {
    CallbackTokenRegistry registry;
    FakeLooper looper;
    auto ctx = std::make_shared<CallbackTokenRegistry::Context>();
    auto token = registry.registerContext(ctx);
    looper.add(41, callbackFor(registry, token));
    auto queued = looper.snapshot(41); // delivery already queued
    require(bool(queued), "missing queued callback");
    require(looper.remove(41) == FakeLooper::RemoveResult::Removed, "remove failed");
    registry.unregisterAndDrain(token);
    require(!queued(), "late callback admitted to retired registration");
}

static void testDescriptorReuseAndGenerations() {
    CallbackTokenRegistry registry;
    FakeLooper looper;
    auto old = registry.registerContext(std::make_shared<CallbackTokenRegistry::Context>());
    looper.add(42, callbackFor(registry, old));
    auto stale = looper.snapshot(42);
    require(looper.remove(42) == FakeLooper::RemoveResult::Removed, "old fd not removed");
    registry.unregisterAndDrain(old);
    auto fresh = registry.registerContext(std::make_shared<CallbackTokenRegistry::Context>());
    require(fresh != old, "token reused");
    looper.add(42, callbackFor(registry, fresh)); // same OS descriptor number
    require(!stale(), "old queued callback entered replacement context");
    require(looper.snapshot(42)(), "replacement callback rejected");
    require(looper.remove(42) == FakeLooper::RemoveResult::Removed, "fresh remove failed");
    registry.unregisterAndDrain(fresh);
}

static void testRemovalErrorRetainsOwnershipUntilRetry() {
    CallbackTokenRegistry registry;
    FakeLooper looper;
    auto ctx = std::make_shared<CallbackTokenRegistry::Context>();
    std::weak_ptr<CallbackTokenRegistry::Context> weak = ctx;
    auto token = registry.registerContext(ctx);
    looper.add(43, callbackFor(registry, token));
    ctx.reset();
    looper.failRemove(true);
    require(looper.remove(43) == FakeLooper::RemoveResult::Error, "expected remove failure");
    require(looper.hasFd(43), "failed remove unexpectedly deregistered fd");
    require(!weak.expired(), "failed removal dropped live context");
    // Do not destroy registry/context on remove error. Retry with healthy looper.
    looper.failRemove(false);
    require(looper.remove(43) == FakeLooper::RemoveResult::Removed, "retry failed");
    registry.unregisterAndDrain(token);
    require(weak.expired(), "context survived completed drain");
}

static void testUnknownDescriptorRemoval() {
    FakeLooper looper;
    require(looper.remove(100) == FakeLooper::RemoveResult::NotRegistered,
            "missing fd incorrectly reported removed");
}

int main() {
    try {
        testCallbackAfterRemoval();
        testDescriptorReuseAndGenerations();
        testRemovalErrorRetainsOwnershipUntilRetry();
        testUnknownDescriptorRemoval();
        std::cout << "M8_FAKE_LOOPER_REMOVAL_MODEL=PASS\n"
                  << "M8_ANDROID_RAW_USERDATA_LIFETIME_VERIFIED=false\n"
                  << "M8_ANDROID_LOOPER_QUIESCENCE_VERIFIED=false\n"
                  << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
    } catch (const std::exception& e) {
        std::cerr << "FAIL: " << e.what() << '\n';
        return 1;
    }
}
