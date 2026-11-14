// M8 standalone Android ARM64 ownership-adapter probe. NOT in production APK.
// Build under Android NDK; execute only via a future explicitly pinned and
// device-gated runner. No X11, JNI, root, network, or app runtime access.
#include "m8-android-owned-looper-thread.cpp"
#include "m8-renderer-owned-looper-adapter.h"
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
struct CallbackContext {
    std::promise<void> entered;
    std::shared_future<void> release;
    std::atomic<int> completed{0};
};

int callback(int fd, int, void* userdata) {
    auto* context = static_cast<CallbackContext*>(userdata);
    char byte = 0;
    if (read(fd, &byte, 1) != 1) return 0;
    context->entered.set_value();
    context->release.wait();
    ++context->completed;
    return 1;
}
int runProbe() {
    int fds[2] = {-1, -1};
    if (pipe(fds) != 0) return 10;
    int result = 0;
    std::promise<void> allowCallbackExit;
    auto context = std::make_unique<CallbackContext>();
    context->release = allowCallbackExit.get_future().share();
    {
        anvildesk_m8::RendererOwnedLooperAdapter adapter;
        if (!adapter.registerCallback(700, fds[0], ALOOPER_EVENT_INPUT,
                                      callback, context.get())) {
            result = 11;
            allowCallbackExit.set_value();
            if (!adapter.shutdownAndJoin()) result = 12;
        } else {
            const char byte = 'x';
            if (write(fds[1], &byte, 1) != 1) {
                result = 13;
                allowCallbackExit.set_value();
                if (!adapter.shutdownAndJoin()) result = 14;
            } else {
                auto entered = context->entered.get_future();
                if (entered.wait_for(10s) != std::future_status::ready) {
                    result = 15;
                    allowCallbackExit.set_value();
                    if (!adapter.shutdownAndJoin()) result = 16;
                } else {
                    // Callback is held open; external shutdown must join it
                    // before userdata release is authorized.
                    std::atomic<bool> joined{false};
                    std::thread joiner([&] {
                        joined = adapter.shutdownAndJoin();
                    });
                    const bool callbackStillRunning = context->completed == 0;
                    allowCallbackExit.set_value();
                    joiner.join();
                    if (!callbackStillRunning || !joined ||
                        context->completed != 1 ||
                        !adapter.userdataReleaseEligible() ||
                        !adapter.releaseAuthorization() ||
                        adapter.releaseAuthorization())
                        result = 17;
                }
            }
        }
        if (result == 0 &&
            adapter.registerCallback(701, fds[0], ALOOPER_EVENT_INPUT,
                                     callback, context.get()))
            result = 18;
        // This ownership transfer occurs only on the successful join path.
        // Other test failure paths join before leaving this scope as well.
    }
    context.reset();
    close(fds[0]);
    close(fds[1]);
    return result;
}
} // namespace

int main() {
    const int result = runProbe();
    if (result != 0) {
        std::cerr << "M8_ANDROID_UNIFIED_OWNER_DEVICE_TEST=FAIL:" << result << '\n';
        return result;
    }
    std::cout << "M8_ANDROID_UNIFIED_OWNER_DEVICE_TEST=PASS\n"
              << "M8_ANDROID_UNIFIED_OWNER_POST_JOIN_HEAP_FREE=PASS\n"
              << "M8_X11_RENDERER_INTEGRATION_VERIFIED=false\n"
              << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
}
