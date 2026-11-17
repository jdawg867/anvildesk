// Standalone experimental composite M8 callback owner for Android ARM64.
// Built by CI only; NEVER packaged into AnvilDesk or loaded by production.
// No Lorie renderer, JNI, root, rootfs, or network calls.
#include "m8-android-owned-looper-thread.cpp"
#include "m8-renderer-session-owned-looper.h"
#include <android/looper.h>
#include <atomic>
#include <chrono>
#include <future>
#include <iostream>
#include <memory>
#include <thread>
#include <unistd.h>

namespace {
using namespace std::chrono_literals;
struct ClientContext {
    std::promise<void> entered;
    std::shared_future<void> release;
    std::atomic<int> completed{0};
};
int heldPipeCallback(int fd, int, void* raw) {
    auto* state = static_cast<ClientContext*>(raw);
    char byte = 0;
    if (read(fd, &byte, 1) != 1) return 0;
    state->entered.set_value();
    state->release.wait();
    ++state->completed;
    return 1;
}
int runProbe() {
    int fds[2] = {-1, -1};
    if (pipe(fds) != 0) return 10;
    int result = 0;
    auto client = std::make_unique<ClientContext>();
    std::promise<void> unblock;
    client->release = unblock.get_future().share();
    {
        anvildesk_m8::RendererSessionOwnedLooper owner;
        if (!owner.registerCallback(2010, fds[0], ALOOPER_EVENT_INPUT,
                                    heldPipeCallback, client.get())) {
            result = 11;
            unblock.set_value();
            if (!owner.shutdownAndJoin()) result = 12;
        } else {
            char byte = 'x';
            if (write(fds[1], &byte, 1) != 1) {
                result = 13;
                unblock.set_value();
                if (!owner.shutdownAndJoin()) result = 14;
            } else {
                auto entered = client->entered.get_future();
                if (entered.wait_for(10s) != std::future_status::ready) {
                    result = 15;
                    unblock.set_value();
                    if (!owner.shutdownAndJoin()) result = 16;
                } else {
                    std::atomic<bool> joined{false};
                    std::thread joiner([&] { joined = owner.shutdownAndJoin(); });
                    const bool active = client->completed.load() == 0;
                    unblock.set_value();
                    joiner.join();
                    if (!active || !joined || client->completed != 1 ||
                        owner.holdsCallbackContext()) result = 17;
                }
            }
        }
        if (result == 0 &&
            owner.registerCallback(2011, fds[0], ALOOPER_EVENT_INPUT,
                                   heldPipeCallback, client.get())) result = 18;
        if (result == 0 && !owner.shutdownAndJoin()) result = 19;
    }
    // Client data is released only after the composite's looper has joined.
    client.reset();
    close(fds[0]);
    close(fds[1]);
    return result;
}
} // namespace

int main() {
    int result = runProbe();
    if (result != 0) {
        std::cerr << "M8_ANDROID_COMPOSITE_OWNER_DEVICE_TEST=FAIL:" << result << '\n';
        return result;
    }
    std::cout << "M8_ANDROID_COMPOSITE_OWNER_DEVICE_TEST=PASS\n"
              << "M8_ANDROID_COMPOSITE_CONTEXT_RELEASE_AFTER_JOIN=PASS\n"
              << "M8_X11_RENDERER_INTEGRATION_VERIFIED=false\n"
              << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
}
