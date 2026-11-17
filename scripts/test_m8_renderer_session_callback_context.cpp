// Host-only renderer session callback context contract test.
// No NDK looper, renderer, JNI or production native loading.
#include "m8-renderer-session-callback-context.h"
#include <atomic>
#include <chrono>
#include <future>
#include <iostream>
#include <stdexcept>
#include <thread>

using anvildesk_m8::RendererSessionCallbackContext;
static void require(bool condition, const char* reason) {
    if (!condition) throw std::runtime_error(reason);
}
int main() {
    RendererSessionCallbackContext invalid(0, -1);
    require(!invalid.valid() && !invalid.enter(0, -1),
            "invalid session accepted callback");
    RendererSessionCallbackContext oldSession(1001, 42);
    RendererSessionCallbackContext newSession(1002, 42);
    require(oldSession.valid() && newSession.valid(), "valid session rejected");
    require(!oldSession.enter(1002, 42), "cross-generation callback admitted");
    require(!oldSession.enter(1001, 43), "stale FD callback admitted");

    std::promise<void> entered, release;
    auto enteredFuture = entered.get_future();
    auto releaseFuture = release.get_future().share();
    std::atomic<bool> completed{false};
    std::thread callback([&] {
        auto lease = oldSession.enter(1001, 42);
        require(static_cast<bool>(lease), "legitimate callback rejected");
        entered.set_value();
        releaseFuture.wait();
        completed = true;
    });
    require(enteredFuture.wait_for(std::chrono::seconds(5)) ==
            std::future_status::ready, "callback did not enter");
    require(oldSession.activeLeases() == 1, "callback lease absent");
    oldSession.closeAdmission();
    oldSession.closeAdmission(); // terminal and idempotent
    require(oldSession.admissionClosed(), "callback admission not closed");
    require(!oldSession.enter(1001, 42), "closing owner accepted late entry");

    std::promise<void> drained;
    auto drainedFuture = drained.get_future();
    std::thread waiter([&] {
        oldSession.waitForLeases();
        drained.set_value();
    });
    require(drainedFuture.wait_for(std::chrono::milliseconds(0)) !=
            std::future_status::ready, "drain completed with active callback");
    release.set_value();
    callback.join();
    require(drainedFuture.wait_for(std::chrono::seconds(5)) ==
            std::future_status::ready, "callback leases failed to drain");
    waiter.join();
    require(completed && oldSession.activeLeases() == 0,
            "callback completion not observed");
    require(!oldSession.enter(1001, 42),
            "old session resurrected after drain");
    {
        auto lease = newSession.enter(1002, 42);
        require(static_cast<bool>(lease),
                "new generation refused same descriptor after old session closed");
    }
    newSession.closeAdmission();
    newSession.waitForLeases();
    require(!newSession.enter(1002, 42), "new session reopened after teardown");
    std::cout << "M8_RENDERER_CONTEXT_GENERATION_FD_GUARD=PASS\n"
              << "M8_RENDERER_CONTEXT_ACTIVE_LEASE_DRAIN=PASS\n"
              << "M8_RENDERER_CONTEXT_REUSED_FD_NEW_GENERATION=PASS\n"
              << "M8_RENDERER_ANDROID_CALLBACK_QUIESCENCE_VERIFIED=false\n"
              << "M8_RENDERER_JNI_LIFECYCLE_VERIFIED=false\n"
              << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
}
