// Isolated Android JNI/looper integration diagnostic. NOT packaged in APK.
// A test-only Java harness must explicitly load this .so and call runProbe().
// No JNI_OnLoad, Lorie renderer, Xorg server, rootfs or app runtime.
#include "m8-android-owned-looper-thread.cpp"
#include "m8-renderer-session-owned-looper.h"
#include "m8-renderer-jni-thread-env.h"
#include <jni.h>
#include <android/looper.h>
#include <atomic>
#include <chrono>
#include <future>
#include <memory>
#include <thread>
#include <unistd.h>

namespace {
using namespace std::chrono_literals;
struct Context {
    JavaVM* vm = nullptr;  // Supplied by the calling Java thread; not JNIEnv*.
    std::promise<void> entered;
    std::shared_future<void> release;
    std::atomic<int> valid{0};
    std::atomic<int> completed{0};
};

int callback(int fd, int, void* raw) {
    auto* context = static_cast<Context*>(raw);
    char byte = 0;
    if (read(fd, &byte, 1) != 1) return 0;
    anvildesk_m8::AndroidJniThreadBackend backend(context->vm);
    {
        anvildesk_m8::ScopedAndroidCallbackEnv scope(backend);
        // A valid JNIEnv is obtained on THIS worker, never borrowed from
        // the entering JNI caller. No Java methods or refs are invoked.
        if (scope && scope.attachedHere()) ++context->valid;
        context->entered.set_value();
        context->release.wait();
    } // AttachCurrentThread-owned env detached before callback completes.
    ++context->completed;
    return 1;
}

jint runProbe(JNIEnv* callingEnv) {
    if (!callingEnv) return 10;
    JavaVM* vm = nullptr;
    if (callingEnv->GetJavaVM(&vm) != JNI_OK || !vm) return 11;
    int pipes[2] = {-1, -1};
    if (pipe(pipes) != 0) return 12;
    jint result = 0;
    auto context = std::make_unique<Context>();
    context->vm = vm;
    std::promise<void> unblock;
    context->release = unblock.get_future().share();
    {
        anvildesk_m8::RendererSessionOwnedLooper owner;
        if (!owner.registerCallback(2400, pipes[0], ALOOPER_EVENT_INPUT,
                                    callback, context.get())) {
            result = 13;
            unblock.set_value();
            if (!owner.shutdownAndJoin()) result = 14;
        } else {
            const char byte = 'j';
            if (write(pipes[1], &byte, 1) != 1) {
                result = 15;
                unblock.set_value();
                if (!owner.shutdownAndJoin()) result = 16;
            } else {
                auto entered = context->entered.get_future();
                if (entered.wait_for(10s) != std::future_status::ready) {
                    result = 17;
                    unblock.set_value();
                    if (!owner.shutdownAndJoin()) result = 18;
                } else {
                    std::atomic<bool> joined{false};
                    std::thread joiner([&] { joined = owner.shutdownAndJoin(); });
                    const bool blocked = context->completed == 0;
                    unblock.set_value();
                    joiner.join();
                    if (!blocked || !joined || context->valid != 1 ||
                        context->completed != 1 || owner.holdsCallbackContext())
                        result = 19;
                }
            }
        }
        if (result == 0 && owner.registerCallback(
                2401, pipes[0], ALOOPER_EVENT_INPUT, callback, context.get()))
            result = 20;
        if (!owner.shutdownAndJoin() && result == 0) result = 21;
    }
    context.reset(); // Only after owner scope and worker join.
    close(pipes[0]);
    close(pipes[1]);
    return result;
}
} // namespace

// No JNI_OnLoad: the test harness must use the explicit class and library
// name; dynamic symbol registration cannot touch the production app.
extern "C" JNIEXPORT jint JNICALL
Java_io_github_jdawg867_anvildesk_m8_M8CompositeJniDiagnostic_runProbe(
    JNIEnv* env, jclass) {
    return runProbe(env); // 0 PASS; nonzero diagnostic stage FAIL.
}
