// Standalone M8 Android ARM64 runtime probe. Build in CI, run ONLY in an
// explicitly controlled Android test environment; NEVER include in the APK.
// Uses an anonymous pipe and no X11, renderer, PRoot, network or root access.
#include "m8-android-owned-looper-thread.cpp"
#include <android/looper.h>
#include <atomic>
#include <chrono>
#include <future>
#include <iostream>
#include <thread>
#include <unistd.h>

namespace {
using namespace std::chrono_literals;
struct CallbackData {
    std::promise<void> entered;
    std::shared_future<void> release;
    std::atomic<int> finished{0};
};

int pipeCallback(int fd, int, void* raw) {
    auto* data = static_cast<CallbackData*>(raw);
    char byte = 0;
    if (read(fd, &byte, 1) != 1) return 0;
    data->entered.set_value();
    data->release.wait();
    ++data->finished;
    return 1;
}

int runProbe() {
    int fds[2] = {-1, -1};
    if (pipe(fds) != 0) return 10;

    std::promise<void> allowExit;
    CallbackData callbackData;
    callbackData.release = allowExit.get_future().share();
    int result = 0;
    {
        anvildesk_m8::DedicatedLooperThread looper;
        if (!looper.initialized() ||
            !looper.registerCallback(fds[0], ALOOPER_EVENT_INPUT,
                                     pipeCallback, &callbackData)) {
            result = 11;
            // No callback was registered if registration failed; shutdown
            // must still join the worker before stack-owned userdata dies.
            if (!looper.shutdownAndJoin()) result = 12;
        } else {
            const char byte = 'x';
            if (write(fds[1], &byte, 1) != 1) {
                result = 13;
                if (!looper.shutdownAndJoin()) result = 14;
            } else {
                auto entered = callbackData.entered.get_future();
                const bool called =
                    entered.wait_for(10s) == std::future_status::ready;
                if (!called) {
                    result = 15;
                    allowExit.set_value();
                    if (!looper.shutdownAndJoin()) result = 16;
                } else {
                    // The actual join request is performed on a separate
                    // thread while the callback remains blocked.
                    std::atomic<bool> joined{false};
                    std::thread joiner([&] {
                        joined.store(looper.shutdownAndJoin());
                    });
                    // The worker cannot exit until callbackData.release is
                    // satisfied; no timing-dependent claim of quiescence.
                    const bool stillRunning =
                        callbackData.finished.load() == 0;
                    allowExit.set_value();
                    joiner.join();
                    if (!stillRunning || !joined.load() ||
                        callbackData.finished.load() != 1)
                        result = 17;
                }
            }
        }
        // The worker has joined on the successful path. No subsequent
        // registration may re-open callback admission or retain userdata
        // after its owner is eligible for destruction.
        if (result == 0 &&
            looper.registerCallback(fds[0], ALOOPER_EVENT_INPUT,
                                    pipeCallback, &callbackData))
            result = 18;
    }
    close(fds[0]);
    close(fds[1]);
    return result;
}
} // namespace

int main() {
    const int result = runProbe();
    if (result != 0) {
        std::cerr << "M8_ANDROID_OWNED_LOOPER_DEVICE_TEST=FAIL:"
                  << result << '\n';
        return result;
    }
    std::cout << "M8_ANDROID_OWNED_LOOPER_DEVICE_TEST=PASS\n"
              << "M8_ANDROID_LOOPER_POST_JOIN_REGISTRATION_REJECTED=PASS\n"
              << "M8_X11_RENDERER_INTEGRATION_VERIFIED=false\n"
              << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
}
