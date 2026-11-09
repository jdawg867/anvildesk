// Host execution of the ACTUAL isolated dedicated Android looper class.
// Fake NDK functions model poll/wake/callbacks; this is NOT an Android test.
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <future>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <thread>
#include "m8-owned-looper-test-shim/android/looper.h"

struct ALooper {
    std::mutex mutex;
    std::condition_variable ready;
    bool wake = false;
    bool pending = false;
    bool registered = false;
    std::atomic<bool> failRemove{false};
    std::atomic<int> references{0};
    int fd = -1;
    ALooper_callbackFunc callback = nullptr;
    void* userdata = nullptr;
};
static ALooper platform;
static thread_local ALooper* currentLooper = nullptr;

ALooper* ALooper_prepare(int) {
    currentLooper = &platform;
    return currentLooper;
}
void ALooper_acquire(ALooper* ptr) { ++ptr->references; }
void ALooper_release(ALooper* ptr) { --ptr->references; }
void ALooper_wake(ALooper* ptr) {
    std::lock_guard<std::mutex> lock(ptr->mutex);
    ptr->wake = true;
    ptr->ready.notify_all();
}
int ALooper_addFd(ALooper* ptr, int fd, int, int, ALooper_callbackFunc cb, void* data) {
    std::lock_guard<std::mutex> lock(ptr->mutex);
    if (ptr->registered) return -1;
    ptr->fd = fd;
    ptr->callback = cb;
    ptr->userdata = data;
    ptr->registered = true;
    return 1;
}
int ALooper_removeFd(ALooper* ptr, int fd) {
    std::lock_guard<std::mutex> lock(ptr->mutex);
    if (ptr->failRemove) return -1;
    if (!ptr->registered || ptr->fd != fd) return 0;
    ptr->registered = false;
    ptr->pending = false;
    return 1;
}
int ALooper_pollOnce(int, int*, int*, void**) {
    ALooper* ptr = currentLooper;
    std::unique_lock<std::mutex> lock(ptr->mutex);
    ptr->ready.wait(lock, [&] { return ptr->wake || ptr->pending; });
    if (ptr->wake) {
        ptr->wake = false;
        return 0;
    }
    if (ptr->pending && ptr->registered) {
        ptr->pending = false;
        const int fd = ptr->fd;
        const auto cb = ptr->callback;
        void* data = ptr->userdata;
        lock.unlock();
        cb(fd, ALOOPER_EVENT_INPUT, data);
        return 1;
    }
    return 0;
}
#include "m8-android-owned-looper-thread.cpp"
static void require(bool ok, const char* why) {
    if (!ok) throw std::runtime_error(why);
}
static void triggerEvent() {
    std::lock_guard<std::mutex> lock(platform.mutex);
    platform.pending = true;
    platform.ready.notify_one();
}
struct Userdata {
    std::atomic<int>* calls;
    std::promise<void>* entered;
    std::shared_future<void>* release;
};
static int heldCallback(int, int, void* raw) {
    auto* userdata = static_cast<Userdata*>(raw);
    ++*userdata->calls;
    userdata->entered->set_value();
    userdata->release->wait();
    return 1;
}
static void testWakeJoinAndRemoveRetry() {
    std::promise<void> entered, releaseSignal;
    auto released = releaseSignal.get_future().share();
    std::atomic<int> calls{0};
    Userdata userdata{&calls, &entered, &released};
    anvildesk_m8::DedicatedLooperThread owner;
    require(owner.initialized(), "looper prepare failed");
    require(platform.references == 1, "looper ref not held");
    require(owner.registerCallback(50, ALOOPER_EVENT_INPUT, heldCallback, &userdata),
            "first FD registration failed");
    require(!owner.registerCallback(51, ALOOPER_EVENT_INPUT, heldCallback, &userdata),
            "duplicate FD registration admitted");
    triggerEvent();
    entered.get_future().wait();
    platform.failRemove.store(true);
    require(!owner.shutdownAndJoin(), "failed removal unexpectedly completed shutdown");
    require(!owner.registerCallback(51, ALOOPER_EVENT_INPUT, heldCallback, &userdata),
            "failed removal reopened registration");
    platform.failRemove.store(false);
    std::atomic<bool> completed{false};
    std::thread shutdown([&] { completed = owner.shutdownAndJoin(); });
    // The running callback remains blocked, so joining cannot finish yet.
    const bool waited = !completed.load();
    releaseSignal.set_value();
    shutdown.join();
    require(waited, "join completed while active callback still blocked");
    require(completed, "retry did not join worker");
    require(calls == 1, "callback delivery count incorrect");
    require(platform.references == 0, "retained looper ref leaked");
    require(!owner.registerCallback(52, ALOOPER_EVENT_INPUT, heldCallback, &userdata),
            "registration admitted after joined shutdown");
    require(owner.shutdownAndJoin(), "repeat shutdown not idempotent");
}
int main() {
    testWakeJoinAndRemoveRetry();
    std::cout << "M8_OWNED_LOOPER_HOST_EXECUTION=PASS\n"
              << "M8_ANDROID_LOOPER_RUNTIME_VERIFIED=false\n"
              << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
}
