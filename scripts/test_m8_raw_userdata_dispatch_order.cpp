// Host-only proof-of-order simulation for Android's raw void* callback ABI.
// This test executes NO real Android callbacks and is NOT release authorization.
#include "m8-callback-token-registry.h"
#include <atomic>
#include <chrono>
#include <exception>
#include <condition_variable>
#include <future>
#include <functional>
#include <iostream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <thread>

using anvildesk_m8::CallbackTokenRegistry;

static void check(bool ok, const char* why) {
    if (!ok) throw std::runtime_error(why);
}

struct StableUserdata {
    CallbackTokenRegistry* registry; // registry outlives dispatcher thread
    CallbackTokenRegistry::Token token;
    std::atomic<int>* denied;
    std::atomic<int>* admitted;
    ~StableUserdata() = default;
};

static int rawCallback(int, int, void* raw) {
    auto* userdata = static_cast<StableUserdata*>(raw);
    auto lease = userdata->registry->enter(userdata->token);
    if (lease) {
        ++*userdata->admitted;
    } else {
        ++*userdata->denied;
    }
    return 0; // no re-registration
}

// Models a queued platform callback. The raw userdata is borrowed;
// ownership stays with the test until the dispatcher thread has joined.
static void testQueuedCallbackSurvivesRemovalAndDrain() {
    CallbackTokenRegistry registry;
    auto context = std::make_shared<CallbackTokenRegistry::Context>();
    const auto token = registry.registerContext(context);
    std::atomic<int> denied{0}, admitted{0};
    auto userdata = std::make_unique<StableUserdata>(
        StableUserdata{&registry, token, &denied, &admitted});
    void* queuedRaw = userdata.get(); // simulated ALooper queue snapshot

    // Removal does NOT cancel a callback already queued for dispatch.
    registry.unregisterAndDrain(token);
    std::thread dispatcher([queuedRaw] { rawCallback(51, 1, queuedRaw); });
    dispatcher.join(); // independently established dispatcher quiescence
    check(denied == 1, "late queued callback not rejected");
    check(admitted == 0, "late queued callback entered retired context");
    userdata.reset(); // permitted in host model ONLY after thread join
}

static void testRunningCallbackKeepsResourceAliveDuringShutdown() {
    CallbackTokenRegistry registry;
    auto context = std::make_shared<CallbackTokenRegistry::Context>();
    std::weak_ptr<CallbackTokenRegistry::Context> weak = context;
    auto token = registry.registerContext(context);
    std::atomic<bool> entered{false}, release{false}, drained{false};

    std::thread dispatcher([&] {
        auto lease = registry.enter(token);
        if (!lease) return;
        entered.store(true);
        while (!release.load()) std::this_thread::yield();
    });
    while (!entered.load()) std::this_thread::yield();
    context.reset();
    std::thread shutdown([&] {
        registry.unregisterAndDrain(token);
        drained.store(true);
    });
    while (registry.enter(token)) std::this_thread::yield();
    check(!weak.expired(), "context freed during active callback");
    check(!drained.load(), "drain finished before callback exit");
    release.store(true);
    dispatcher.join();
    shutdown.join();
    check(drained.load(), "drain never completed");
    check(weak.expired(), "context retained after drain and thread join");
}

// This worker owns a host callback-dispatch thread. The raw userdata pointer
// belongs to its caller and must remain alive until shutdownAndJoin returns.
// This is deliberately NOT an Android ALooper implementation.
class HostDispatchOwner final {
public:
    using Callback = std::function<void(void*)>;
    HostDispatchOwner(void* userdata, Callback callback)
        : userdata_(userdata), callback_(std::move(callback)),
          worker_([this] { run(); }) {}
    HostDispatchOwner(const HostDispatchOwner&) = delete;
    HostDispatchOwner& operator=(const HostDispatchOwner&) = delete;

    ~HostDispatchOwner() {
        // Forbid implicit thread detach / userdata lifetime assumptions.
        if (worker_.joinable()) std::terminate();
    }

    bool queueCallback() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (stopping_) return false;
        ++pending_;
        ready_.notify_one();
        return true;
    }

    // Single external shutdown caller; never invoked from worker callback.
    void shutdownAndJoin() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stopping_ = true;
        }
        ready_.notify_one();
        worker_.join();
    }

private:
    void run() {
        for (;;) {
            std::unique_lock<std::mutex> lock(mutex_);
            ready_.wait(lock, [&] { return stopping_ || pending_ != 0; });
            if (pending_ == 0 && stopping_) return;
            --pending_;
            lock.unlock();
            callback_(userdata_);
        }
    }

    void* userdata_;
    Callback callback_;
    std::mutex mutex_;
    std::condition_variable ready_;
    bool stopping_ = false;
    unsigned pending_ = 0;
    std::thread worker_;
};

static void testHostDispatcherJoinPrecedesRawUserdataRelease() {
    struct Userdata {
        std::shared_ptr<int> alive;
    };
    std::promise<void> callbackEntered;
    std::promise<void> callbackRelease;
    const auto release = callbackRelease.get_future().share();
    std::atomic<int> calls{0};
    auto userdata = std::make_unique<Userdata>();
    userdata->alive = std::make_shared<int>(42);
    std::weak_ptr<int> weak = userdata->alive;
    HostDispatchOwner owner(userdata.get(), [&](void* raw) {
        auto* borrowed = static_cast<Userdata*>(raw);
        callbackEntered.set_value();
        release.wait();
        if (*borrowed->alive == 42) ++calls;
    });
    check(owner.queueCallback(), "dispatcher rejected initial callback");
    callbackEntered.get_future().wait();
    std::promise<void> shutdownFinished;
    auto finished = shutdownFinished.get_future();
    std::thread shutdown([&] {
        owner.shutdownAndJoin();
        shutdownFinished.set_value();
    });
    const bool joinWaited = finished.wait_for(std::chrono::milliseconds(20))
                            == std::future_status::timeout;
    const bool retained = !weak.expired();
    // Once shutdown starts, posting must fail. The host model exposes no
    // thread-safe query; shutdown already started but may not be scheduled.
    callbackRelease.set_value();
    shutdown.join();
    check(joinWaited, "dispatcher joined before running callback finished");
    check(retained, "raw userdata was released during callback");
    check(calls == 1, "running callback did not finish");
    check(!owner.queueCallback(), "dispatcher accepted callback after join");
    userdata.reset();
    check(weak.expired(), "userdata retained beyond completed join");
}

int main() {
    testQueuedCallbackSurvivesRemovalAndDrain();
    testRunningCallbackKeepsResourceAliveDuringShutdown();
    testHostDispatcherJoinPrecedesRawUserdataRelease();
    std::cout << "M8_RAW_USERDATA_HOST_DISPATCH_ORDER=PASS\n"
              << "M8_ANDROID_DISPATCH_THREAD_JOIN_VERIFIED=false\n"
              << "M8_ANDROID_LOOPER_QUIESCENCE_VERIFIED=false\n"
              << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
}
