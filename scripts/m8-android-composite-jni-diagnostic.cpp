// Isolated Android JNI/looper integration diagnostic. Packaged ONLY in the
// explicitly opted-in instrumentation APK; never in the target or production APK.
// A test-only Java harness explicitly loads this .so and calls its JNI probes.
// No JNI_OnLoad, Lorie renderer, Xorg server, rootfs or app runtime.
#include "m8-android-owned-looper-thread.cpp"
#include "m8-renderer-session-owned-looper.h"
#include "m8-renderer-jni-thread-env.h"
#include "m8-renderer-jni-global-ref-android.h"
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
// Real JVM-backed global-reference probe. The global reference is created on
// the JNI caller and accessed only through a fresh worker-local JNIEnv. It is
// retained while a callback is active, then retired after owned-worker join.
struct GlobalContext {
    JavaVM* vm;
    jobject global; // Owned by DeferredGlobalRefRetirement, not by callback.
    std::promise<void> entered;
    std::shared_future<void> release;
    std::atomic<int> valid{0};
};
int globalRefCallback(int fd, int, void* raw) {
    auto* context = static_cast<GlobalContext*>(raw);
    char byte = 0;
    if (read(fd, &byte, 1) != 1) return 0;
    anvildesk_m8::AndroidJniThreadBackend thread(context->vm);
    {
        anvildesk_m8::ScopedAndroidCallbackEnv scope(thread);
        if (scope) {
            JNIEnv* env = scope.get();
            // This proves the worker sees a usable JNI global reference.
            jclass clazz = env->GetObjectClass(context->global);
            if (clazz && !env->ExceptionCheck()) {
                ++context->valid;
            }
            if (clazz) env->DeleteLocalRef(clazz);
        }
        context->entered.set_value();
        context->release.wait();
    }
    return 1;
}
jint runGlobalRefProbe(JNIEnv* caller, jobject javaObject) {
    if (!caller || !javaObject) return 30;
    JavaVM* vm = nullptr;
    if (caller->GetJavaVM(&vm) != JNI_OK || !vm) return 31;
    jobject global = caller->NewGlobalRef(javaObject);
    if (!global || caller->ExceptionCheck()) return 32;
    // The test must retain the reference on all failed/partial shutdown paths.
    anvildesk_m8::AndroidGlobalRefRetirementBackend backend(vm);
    anvildesk_m8::DeferredGlobalRefRetirement<
        anvildesk_m8::AndroidGlobalRefRetirementBackend> refs(backend, global);
    int pipes[2] = {-1, -1};
    if (pipe(pipes) != 0) {
        if (!refs.retireAfterVerifiedJoin(true)) return 33; // No worker started.
        return 34;
    }
    jint result = 0;
    std::promise<void> unblock;
    GlobalContext context{vm, global, {}, unblock.get_future().share(), 0};
    {
        anvildesk_m8::RendererSessionOwnedLooper owner;
        if (!owner.registerCallback(2500, pipes[0], ALOOPER_EVENT_INPUT,
                                    globalRefCallback, &context)) {
            result = 35;
        } else {
            const char byte = 'g';
            if (write(pipes[1], &byte, 1) != 1) {
                result = 36;
            } else {
                auto entered = context.entered.get_future();
                if (entered.wait_for(10s) != std::future_status::ready) {
                    result = 37;
                } else if (context.valid != 1 || !refs.holdsReference()) {
                    result = 38;
                } else if (refs.retireAfterVerifiedJoin(false)) {
                    result = 39; // Must not delete the reference before join.
                }
            }
        }
        unblock.set_value();
        const bool joined = owner.shutdownAndJoin();
        if (!joined && !result) result = 40;
        if (joined && owner.holdsCallbackContext() && !result) result = 41;
        if (joined) {
            // Exercise exception preservation: the adapter must not clear
            // or delete through a pending exception. We clear ONLY the
            // intentionally generated diagnostic exception afterwards.
            jclass exceptionClass = caller->FindClass("java/lang/IllegalStateException");
            if (!exceptionClass || caller->ExceptionCheck()) {
                if (caller->ExceptionCheck()) caller->ExceptionClear();
                if (!result) result = 42;
            } else {
                if (caller->ThrowNew(exceptionClass, "M8 diagnostic only") != JNI_OK) {
                    if (!result) result = 43;
                } else if (!caller->ExceptionCheck() ||
                           refs.retireAfterVerifiedJoin(true) ||
                           !refs.holdsReference()) {
                    if (!result) result = 44;
                }
                // Never clear a pre-existing caller exception: this method
                // created the sole pending exception for the test.
                if (caller->ExceptionCheck()) caller->ExceptionClear();
                caller->DeleteLocalRef(exceptionClass);
            }
            if (!refs.retireAfterVerifiedJoin(true) && !result) result = 45;
            if ((refs.holdsReference() || !refs.retired()) && !result) result = 46;
            if (!refs.retireAfterVerifiedJoin(true) && !result) result = 47;
        }
    }
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

extern "C" JNIEXPORT jint JNICALL
Java_io_github_jdawg867_anvildesk_m8_M8CompositeJniDiagnostic_runGlobalRefProbe(
    JNIEnv* env, jclass, jobject object) {
    return runGlobalRefProbe(env, object);
}
