// Standalone native concurrency tests; no Android SDK or X11 JNI loading.
#include "m8-native-callback-drain.h"

#include <atomic>
#include <chrono>
#include <future>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <vector>

using anvildesk_m8::CallbackDrain;
using namespace std::chrono_literals;

static void check(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

static void verifyOneShotShutdown() {
    CallbackDrain gate;
    auto first = gate.acquire();
    auto second = gate.acquire();
    check(first != nullptr && second != nullptr, "initial callbacks rejected");

    std::promise<void> started;
    std::promise<void> completed;
    auto finished = completed.get_future();
    std::thread closer([&] {
        started.set_value();
        gate.shutdownAndDrain();
        completed.set_value();
    });
    started.get_future().wait();
    // Wait for the gate to transition to stopping without sleeping.
    while (!gate.stopping()) std::this_thread::yield();
    check(gate.acquire() == nullptr, "callback admitted after shutdown began");
    check(finished.wait_for(20ms) == std::future_status::timeout,
          "shutdown returned while leases were active");
    first.reset();
    check(finished.wait_for(20ms) == std::future_status::timeout,
          "shutdown returned before final callback completed");
    second.reset();
    const bool drained = finished.wait_for(2s) == std::future_status::ready;
    closer.join();
    check(drained, "shutdown did not drain existing callbacks");
    check(gate.acquire() == nullptr, "callback reopened after shutdown");
    gate.shutdownAndDrain(); // idempotent, with no active callbacks
}

static void verifyConcurrentCallbackAdmission() {
    CallbackDrain gate;
    constexpr int count = 24;
    std::atomic<int> entered{0};
    std::promise<void> release;
    auto releaseFuture = release.get_future().share();
    std::vector<std::thread> callbacks;
    callbacks.reserve(count);
    for (int i = 0; i < count; ++i) {
        callbacks.emplace_back([&] {
            auto lease = gate.acquire();
            if (lease) {
                ++entered;
                releaseFuture.wait();
            }
        });
    }
    while (entered.load() != count) std::this_thread::yield();
    std::promise<void> done;
    auto doneFuture = done.get_future();
    std::thread closer([&] {
        gate.shutdownAndDrain();
        done.set_value();
    });
    while (!gate.stopping()) std::this_thread::yield();
    check(gate.acquire() == nullptr, "late callback was admitted");
    check(doneFuture.wait_for(20ms) == std::future_status::timeout,
          "shutdown passed active callback barrier");
    release.set_value();
    for (auto& thread : callbacks) thread.join();
    const bool drained = doneFuture.wait_for(2s) == std::future_status::ready;
    closer.join();
    check(drained, "concurrent callbacks did not drain");
}

// Shutdown must remain closed when it starts with no active callbacks.
static void verifyEmptyGateShutdown() {
    CallbackDrain gate;
    gate.shutdownAndDrain();
    check(gate.stopping(), "empty gate did not enter stopping");
    check(gate.acquire() == nullptr, "empty gate reopened after shutdown");
    gate.shutdownAndDrain();
}

// Multiple shutdown callers must all wait for the last callback lease.
static void verifyConcurrentShutdownCallers() {
    CallbackDrain gate;
    auto lease = gate.acquire();
    check(lease != nullptr, "initial lease rejected");
    constexpr int count = 8;
    std::atomic<int> finished{0};
    std::vector<std::thread> closers;
    closers.reserve(count);
    for (int i = 0; i < count; ++i) {
        closers.emplace_back([&] {
            gate.shutdownAndDrain();
            ++finished;
        });
    }
    while (!gate.stopping()) std::this_thread::yield();
    check(gate.acquire() == nullptr, "new lease acquired during concurrent stop");
    check(finished.load() == 0, "shutdown completed with a live lease");
    lease.reset();
    for (auto& closer : closers) closer.join();
    check(finished.load() == count, "not every shutdown caller completed");
    check(gate.acquire() == nullptr, "gate reopened after concurrent stop");
}

// A new gate must not inherit the stopped state of a prior, drained gate.
static void verifyIndependentGateLifecycles() {
    for (int i = 0; i < 64; ++i) {
        CallbackDrain gate;
        auto lease = gate.acquire();
        check(lease != nullptr, "fresh gate unexpectedly closed");
        lease.reset();
        gate.shutdownAndDrain();
        check(gate.acquire() == nullptr, "drained gate unexpectedly reopened");
    }
}

int main() {
    try {
        verifyOneShotShutdown();
        verifyConcurrentCallbackAdmission();
        verifyEmptyGateShutdown();
        verifyConcurrentShutdownCallers();
        verifyIndependentGateLifecycles();
        std::cout << "M8_NATIVE_CALLBACK_DRAIN_SYNTHETIC_TEST=PASS\n";
        std::cout << "M8_ANDROID_LOOPER_QUIESCENCE_VERIFIED=false\n";
        std::cout << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
