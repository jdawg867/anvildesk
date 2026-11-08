// Host-only callback token tests. No Android looper or libXlorie loading.
#include "m8-callback-token-registry.h"
#include <atomic>
#include <chrono>
#include <future>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <thread>

using anvildesk_m8::CallbackTokenRegistry;
using namespace std::chrono_literals;

static void check(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}

static void testStaleTokenAndReconnect() {
    CallbackTokenRegistry registry;
    auto oldContext = std::make_shared<CallbackTokenRegistry::Context>();
    std::weak_ptr<CallbackTokenRegistry::Context> oldWeak = oldContext;
    const auto oldToken = registry.registerContext(oldContext);
    oldContext.reset();
    { auto callback = registry.enter(oldToken); check(bool(callback), "first token rejected"); }
    registry.unregisterAndDrain(oldToken);
    check(!registry.enter(oldToken), "stale token admitted");
    check(oldWeak.expired(), "old context leaked");

    auto nextContext = std::make_shared<CallbackTokenRegistry::Context>();
    const auto nextToken = registry.registerContext(nextContext);
    check(nextToken != oldToken, "token reused after reconnect");
    check(!registry.enter(oldToken), "old token matched new context");
    { auto callback = registry.enter(nextToken); check(bool(callback), "new context rejected"); }
    registry.unregisterAndDrain(nextToken);
    check(!registry.enter(nextToken), "replacement token admitted after unregister");
}

static void testUnregisterWaitsForActiveLease() {
    CallbackTokenRegistry registry;
    auto context = std::make_shared<CallbackTokenRegistry::Context>();
    const auto token = registry.registerContext(context);
    auto callback = registry.enter(token);
    check(bool(callback), "initial callback rejected");
    std::promise<void> finished;
    auto future = finished.get_future();
    std::thread closer([&] {
        registry.unregisterAndDrain(token);
        finished.set_value();
    });
    // Wait for token removal, not a timed guess about scheduling.
    while (registry.enter(token)) std::this_thread::yield();
    const bool blocked = future.wait_for(20ms) == std::future_status::timeout;
    callback = {};
    closer.join();
    check(blocked, "unregister completed with an active callback");
    check(!registry.enter(token), "closed token was admitted again");
}

static void testDuplicateUnregisterWaitsForDrain() {
    CallbackTokenRegistry registry;
    auto context = std::make_shared<CallbackTokenRegistry::Context>();
    const auto token = registry.registerContext(context);
    auto active = registry.enter(token);
    check(bool(active), "initial lease unavailable");
    std::promise<void> firstFinished;
    std::promise<void> secondFinished;
    auto firstResult = firstFinished.get_future();
    auto secondResult = secondFinished.get_future();
    std::thread first([&] {
        registry.unregisterAndDrain(token);
        firstFinished.set_value();
    });
    // The first unregistration has marked the entry as closing.
    while (registry.enter(token)) std::this_thread::yield();
    std::promise<void> secondStarted;
    auto secondStart = secondStarted.get_future();
    std::thread second([&] {
        secondStarted.set_value();
        registry.unregisterAndDrain(token);
        secondFinished.set_value();
    });
    secondStart.wait();
    const bool firstBlocked =
        firstResult.wait_for(20ms) == std::future_status::timeout;
    const bool secondBlocked =
        secondResult.wait_for(20ms) == std::future_status::timeout;
    active = {};
    first.join();
    second.join();
    check(firstBlocked, "first stop completed with a live lease");
    check(secondBlocked, "duplicate stop returned before drain");
    check(!registry.enter(token), "retired token accepted after stops");
}

static void testConcurrentAdmissionAndCancellation() {
    CallbackTokenRegistry registry;
    for (int round = 0; round < 64; ++round) {
        auto context = std::make_shared<CallbackTokenRegistry::Context>();
        auto token = registry.registerContext(context);
        std::atomic<bool> keepRunning{true};
        std::thread worker([&] {
            while (keepRunning.load()) {
                auto callback = registry.enter(token);
                if (!callback) break;
            }
        });
        registry.unregisterAndDrain(token);
        keepRunning.store(false);
        worker.join();
        check(!registry.enter(token), "token survived cancellation");
    }
}

int main() {
    try {
        testStaleTokenAndReconnect();
        testUnregisterWaitsForActiveLease();
        testDuplicateUnregisterWaitsForDrain();
        testConcurrentAdmissionAndCancellation();
        std::cout << "M8_CALLBACK_TOKEN_REGISTRY_HOST_MODEL=PASS\n"
                  << "M8_ANDROID_USERDATA_TOKEN_ENCODING_VERIFIED=false\n"
                  << "M8_ANDROID_LOOPER_QUIESCENCE_VERIFIED=false\n"
                  << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
    } catch (const std::exception& e) {
        std::cerr << "FAIL: " << e.what() << '\n';
        return 1;
    }
}
