// Host-only proof-of-order simulation for Android's raw void* callback ABI.
// This test executes NO real Android callbacks and is NOT release authorization.
#include "m8-callback-token-registry.h"
#include <atomic>
#include <condition_variable>
#include <iostream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <thread>

using anvildesk_m8::CallbackTokenRegistry;

static void check(bool ok, const char* why) {
    if (!ok) throw std::runtime_error(why);
}

struct StableUserdata {
    CallbackTokenRegistry* registry; // registry outlives dispatcher thread
    CallbackTokenRegistry::Token token;
    std::atomic<int>* denied;
    std::atomic<int>* admitted;
    ~StableUserdata() = default;
};

static int rawCallback(int, int, void* raw) {
    auto* userdata = static_cast<StableUserdata*>(raw);
    auto lease = userdata->registry->enter(userdata->token);
    if (lease) {
        ++*userdata->admitted;
    } else {
        ++*userdata->denied;
    }
    return 0; // no re-registration
}

// Models a queued platform callback. The raw userdata is borrowed;
// ownership stays with the test until the dispatcher thread has joined.
static void testQueuedCallbackSurvivesRemovalAndDrain() {
    CallbackTokenRegistry registry;
    auto context = std::make_shared<CallbackTokenRegistry::Context>();
    const auto token = registry.registerContext(context);
    std::atomic<int> denied{0}, admitted{0};
    auto userdata = std::make_unique<StableUserdata>(
        StableUserdata{&registry, token, &denied, &admitted});
    void* queuedRaw = userdata.get(); // simulated ALooper queue snapshot

    // Removal does NOT cancel a callback already queued for dispatch.
    registry.unregisterAndDrain(token);
    std::thread dispatcher([queuedRaw] { rawCallback(51, 1, queuedRaw); });
    dispatcher.join(); // independently established dispatcher quiescence
    check(denied == 1, "late queued callback not rejected");
    check(admitted == 0, "late queued callback entered retired context");
    userdata.reset(); // permitted in host model ONLY after thread join
}

static void testRunningCallbackKeepsResourceAliveDuringShutdown() {
    CallbackTokenRegistry registry;
    auto context = std::make_shared<CallbackTokenRegistry::Context>();
    std::weak_ptr<CallbackTokenRegistry::Context> weak = context;
    auto token = registry.registerContext(context);
    std::atomic<bool> entered{false}, release{false}, drained{false};

    std::thread dispatcher([&] {
        auto lease = registry.enter(token);
        if (!lease) return;
        entered.store(true);
        while (!release.load()) std::this_thread::yield();
    });
    while (!entered.load()) std::this_thread::yield();
    context.reset();
    std::thread shutdown([&] {
        registry.unregisterAndDrain(token);
        drained.store(true);
    });
    while (registry.enter(token)) std::this_thread::yield();
    check(!weak.expired(), "context freed during active callback");
    check(!drained.load(), "drain finished before callback exit");
    release.store(true);
    dispatcher.join();
    shutdown.join();
    check(drained.load(), "drain never completed");
    check(weak.expired(), "context retained after drain and thread join");
}

int main() {
    testQueuedCallbackSurvivesRemovalAndDrain();
    testRunningCallbackKeepsResourceAliveDuringShutdown();
    std::cout << "M8_RAW_USERDATA_HOST_DISPATCH_ORDER=PASS\n"
              << "M8_ANDROID_DISPATCH_THREAD_JOIN_VERIFIED=false\n"
              << "M8_ANDROID_LOOPER_QUIESCENCE_VERIFIED=false\n"
              << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
}
