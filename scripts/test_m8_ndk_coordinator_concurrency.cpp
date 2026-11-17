// Host-only test of the actual NDK compile-only teardown coordinator.
// Uses a deliberately fake android/looper.h, NOT an Android runtime test.
#include <atomic>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <unordered_set>
#include <vector>

struct ALooper {
    std::mutex mutex;
    std::unordered_set<int> registered;
    std::atomic<bool> removalFailure{false};
    std::atomic<int> refs{0};
};
#include "m8-android-looper-ndk-compile.cpp"

ALooper* ALooper_forThread() { return nullptr; }
void ALooper_acquire(ALooper* looper) { ++looper->refs; }
void ALooper_release(ALooper* looper) { --looper->refs; }
int ALooper_addFd(ALooper* looper, int fd, int, int, ALooper_callbackFunc, void*) {
    std::lock_guard<std::mutex> lock(looper->mutex);
    return looper->registered.insert(fd).second ? 1 : -1;
}
int ALooper_removeFd(ALooper* looper, int fd) {
    std::lock_guard<std::mutex> lock(looper->mutex);
    if (looper->removalFailure.load()) return -1;
    return looper->registered.erase(fd) ? 1 : 0;
}

using namespace anvildesk_m8;
static int callback(int, int, void*) { return 1; }
static void check(bool ok, const char* why) {
    if (!ok) throw std::runtime_error(why);
}

template<typename Action>
static int competingCalls(int count, Action action) {
    std::atomic<int> passed{0};
    std::vector<std::thread> threads;
    threads.reserve(count);
    for (int i = 0; i < count; ++i)
        threads.emplace_back([&] { if (action()) ++passed; });
    for (auto& thread : threads) thread.join();
    return passed.load();
}

static void testConcurrentShutdownAndDrain() {
    ALooper platform;
    int userdata = 7;  // valid only while test's callback cannot be dispatched
    {
        RetainedLooper looper(&platform);
        check(platform.refs == 1, "looper reference not acquired");
        check(looper.add(11, ALOOPER_EVENT_INPUT, callback, &userdata)
                  == AddStatus::Added, "FD registration failed");
        SingleFdShutdownCoordinator coordinator(looper, 11);
        check(competingCalls(16, [&] { return coordinator.beginShutdown(); }) == 1,
              "multiple shutdown callers admitted");
        check(looper.add(12, ALOOPER_EVENT_INPUT, callback, &userdata)
                  == AddStatus::ShuttingDown, "registration reopened during teardown");
        check(competingCalls(16, [&] { return coordinator.removeRegisteredFd(); }) == 1,
              "duplicate removal advanced teardown twice");
        check(coordinator.state() == LooperTeardownGate::State::HostDrainPending,
              "removal did not enter host-drain phase");
        check(competingCalls(16, [&] {
                  return coordinator.reportExternallyVerifiedHostDrain();
              }) == 1, "duplicate drain reports advanced state");
        check(!coordinator.canReleaseNativeResources(),
              "host drain authorized native resource release");
        check(platform.registered.empty(), "FD still registered after removal");
    }
    check(platform.refs == 0, "looper reference not released");
}

static void testRemovalErrorRetryWithoutReopening() {
    ALooper platform;
    int userdata = 9;
    {
        RetainedLooper looper(&platform);
        check(looper.add(13, ALOOPER_EVENT_INPUT, callback, &userdata)
                  == AddStatus::Added, "FD registration failed");
        SingleFdShutdownCoordinator coordinator(looper, 13);
        check(coordinator.beginShutdown(), "shutdown not started");
        platform.removalFailure.store(true);
        check(coordinator.removeRegisteredFd(), "failed removal not recorded");
        check(coordinator.state() == LooperTeardownGate::State::RemovalFailed,
              "removal failure lost");
        check(!coordinator.reportExternallyVerifiedHostDrain(),
              "host drain accepted after failed removal");
        check(looper.add(14, ALOOPER_EVENT_INPUT, callback, &userdata)
                  == AddStatus::ShuttingDown, "failed removal reopened admission");
        check(coordinator.retryRemoval(), "removal retry denied");
        platform.removalFailure.store(false);
        check(coordinator.removeRegisteredFd(), "retry not recorded");
        check(coordinator.reportExternallyVerifiedHostDrain(),
              "host drain not recorded after retry");
        check(!coordinator.canReleaseNativeResources(), "retry authorized release");
    }
    check(platform.refs == 0, "looper leaked after removal retry");
}

int main() {
    testConcurrentShutdownAndDrain();
    testRemovalErrorRetryWithoutReopening();
    // This uses a host stub with NO real looper callback delivery.
    std::cout << "M8_NDK_COORDINATOR_HOST_CONCURRENCY=PASS\n"
              << "M8_ANDROID_LOOPER_QUIESCENCE_VERIFIED=false\n"
              << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
    return 0;
}
