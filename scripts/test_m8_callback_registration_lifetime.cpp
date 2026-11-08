// Host-only model of stable callback userdata ownership.
// This does NOT model Android ALooper deregistration or authorize native loading.
#include "m8-native-callback-drain.h"

#include <atomic>
#include <chrono>
#include <functional>
#include <future>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <thread>

using anvildesk_m8::CallbackDrain;
using namespace std::chrono_literals;

static void check(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}

struct RegistrationContext {
    CallbackDrain drain;
    std::atomic<int> accepted{0};
};

// Queued callback copies own the context independently of a registration table.
// The real Android callback ABI supplies void* userdata and does not offer
// this ownership guarantee: integration MUST solve that gap explicitly.
using Callback = std::function<bool()>;
static Callback makeCallback(std::shared_ptr<RegistrationContext> context) {
    return [context = std::move(context)] {
        auto lease = context->drain.acquire();
        if (!lease) return false;
        ++context->accepted;
        return true;
    };
}

static void testQueuedCallbackAfterUnregister() {
    auto context = std::make_shared<RegistrationContext>();
    std::weak_ptr<RegistrationContext> weak = context;
    Callback registered = makeCallback(context);
    Callback queued = registered;  // simulated looper queue owns a copy
    registered = nullptr;         // simulated deregistration
    context->drain.shutdownAndDrain();
    context.reset();               // owner relinquishes registration
    check(!weak.expired(), "queued callback lost its stable context");
    check(!queued(), "queued callback admitted after shutdown");
    queued = nullptr;
    check(weak.expired(), "context retained after final queued callback");
}

static void testShutdownWaitsForRunningCallback() {
    auto context = std::make_shared<RegistrationContext>();
    std::promise<void> entered;
    std::promise<void> release;
    auto releaseFuture = release.get_future().share();
    std::thread callback([context, &entered, releaseFuture] {
        auto lease = context->drain.acquire();
        if (!lease) {
            entered.set_value();
            return;
        }
        entered.set_value();
        releaseFuture.wait();
        ++context->accepted;
    });
    entered.get_future().wait();
    std::promise<void> drained;
    auto finished = drained.get_future();
    std::thread shutdown([context, &drained] {
        context->drain.shutdownAndDrain();
        drained.set_value();
    });
    while (!context->drain.stopping()) std::this_thread::yield();
    const bool blocked = finished.wait_for(20ms) == std::future_status::timeout;
    release.set_value();
    callback.join();
    shutdown.join();
    check(blocked, "shutdown crossed an active callback lease");
    check(context->accepted == 1, "active callback did not finish");
    check(!makeCallback(context)(), "callback admitted after drain");
}

static void testReconnectUsesIndependentGeneration() {
    auto oldContext = std::make_shared<RegistrationContext>();
    Callback stale = makeCallback(oldContext);
    oldContext->drain.shutdownAndDrain();
    auto newContext = std::make_shared<RegistrationContext>();
    Callback current = makeCallback(newContext);
    check(!stale(), "stale registration accessed retired generation");
    check(current(), "replacement registration rejected");
    check(oldContext->accepted == 0, "old generation received an event");
    check(newContext->accepted == 1, "new generation lost its event");
    newContext->drain.shutdownAndDrain();
    check(!current(), "replacement callback reopened after stop");
}

int main() {
    try {
        testQueuedCallbackAfterUnregister();
        testShutdownWaitsForRunningCallback();
        testReconnectUsesIndependentGeneration();
        std::cout << "M8_CALLBACK_REGISTRATION_HOST_MODEL=PASS\\n"
                  << "M8_ANDROID_RAW_USERDATA_LIFETIME_VERIFIED=false\\n"
                  << "M8_ANDROID_LOOPER_QUIESCENCE_VERIFIED=false\\n"
                  << "M8_NATIVE_LOAD_AUTHORIZED=false\\n";
    } catch (const std::exception& e) {
        std::cerr << "FAIL: " << e.what() << '\\n';
        return 1;
    }
}
