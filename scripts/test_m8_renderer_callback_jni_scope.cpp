// Host-only fake backend exercising per-callback JNI thread-affinity rules.
// No JVM, APK, Lorie renderer or native library loading.
#include "m8-renderer-callback-jni-scope.h"
#include <iostream>
#include <stdexcept>
#include <thread>
#include <atomic>
using anvildesk_m8::ScopedCallbackThreadEnv;
static void check(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}
struct FakeJvm {
    struct Env { std::thread::id boundThread; };
    enum class Status { Attached, Detached, Error };
    Status status = Status::Detached;
    Env env{std::this_thread::get_id()};
    int queries = 0, attaches = 0, detaches = 0;
    bool attachmentSucceeds = true;
    Status query(Env** out) {
        ++queries;
        *out = nullptr;
        if (status == Status::Attached) *out = &env;
        return status;
    }
    bool attach(Env** out) {
        ++attaches;
        *out = nullptr;
        if (!attachmentSucceeds) return false;
        env.boundThread = std::this_thread::get_id();
        *out = &env;
        return true;
    }
    void detach() { ++detaches; }
};
int main() {
    FakeJvm previouslyAttached;
    previouslyAttached.status = FakeJvm::Status::Attached;
    {
        ScopedCallbackThreadEnv<FakeJvm> scope(previouslyAttached);
        check(scope && !scope.attachedHere(), "borrowed attachment mistaken for owned");
        check(scope.get()->boundThread == std::this_thread::get_id(),
              "borrowed environment not on calling thread");
    }
    check(previouslyAttached.queries == 1 && previouslyAttached.attaches == 0 &&
          previouslyAttached.detaches == 0, "borrowed JVM attachment was modified");

    FakeJvm detached;
    {
        ScopedCallbackThreadEnv<FakeJvm> scope(detached);
        check(scope && scope.attachedHere(), "detached thread did not attach");
        check(scope.get()->boundThread == std::this_thread::get_id(),
              "new env belongs to wrong thread");
    }
    check(detached.queries == 1 && detached.attaches == 1 &&
          detached.detaches == 1, "owned attachment not balanced");

    FakeJvm failed;
    failed.attachmentSucceeds = false;
    {
        ScopedCallbackThreadEnv<FakeJvm> scope(failed);
        check(!scope && !scope.attachedHere(),
              "failed JNI attach produced apparently valid environment");
    }
    check(failed.queries == 1 && failed.attaches == 1 &&
          failed.detaches == 0, "failed attach incorrectly detached");

    FakeJvm queryError;
    queryError.status = FakeJvm::Status::Error;
    {
        ScopedCallbackThreadEnv<FakeJvm> scope(queryError);
        check(!scope, "query error permitted JNI use");
    }
    check(queryError.queries == 1 && queryError.attaches == 0 &&
          queryError.detaches == 0, "query error triggered attachment mutation");

    std::atomic<bool> workerPassed{false};
    std::thread worker([&] {
        FakeJvm owned;
        {
            ScopedCallbackThreadEnv<FakeJvm> scope(owned);
            workerPassed = scope && scope.attachedHere() &&
                scope.get()->boundThread == std::this_thread::get_id();
        }
        workerPassed = workerPassed && owned.detaches == 1;
    });
    worker.join();
    check(workerPassed, "worker-thread JNI scope failed");
    std::cout << "M8_JNI_SCOPE_BORROWED_ENV_NO_DETACH=PASS\n"
              << "M8_JNI_SCOPE_ATTACH_DETACH_BALANCED=PASS\n"
              << "M8_JNI_SCOPE_FAILURE_FAIL_CLOSED=PASS\n"
              << "M8_JNI_SCOPE_WORKER_THREAD_LOCAL=PASS\n"
              << "M8_LORIE_CALLBACK_JNI_INTEGRATED=false\n"
              << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
}
