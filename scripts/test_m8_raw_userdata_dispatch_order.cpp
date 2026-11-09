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
#include <vector>

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
          worker_([this] { run(); }), workerId_(worker_.get_id()) {}
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

    bool admissionClosed() {
        std::lock_guard<std::mutex> lock(mutex_);
        return stopping_;
    }

    // Multiple external callers may race to stop the worker. Serialize
    // joining so every caller returns only after the same worker has exited.
    // Never invoke this from a worker callback (self-join is forbidden).
    void shutdownAndJoin() {
        if (std::this_thread::get_id() == workerId_)
            throw std::logic_error("dispatcher callback cannot join itself");
        std::lock_guard<std::mutex> joinLock(joinMutex_);
        if (joined_) return;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stopping_ = true;
            pending_ = 0; // cancel callbacks not yet dispatched
        }
        ready_.notify_one();
        worker_.join();
        joined_ = true;
    }

private:
    void run() {
        for (;;) {
            std::unique_lock<std::mutex> lock(mutex_);
            ready_.wait(lock, [&] { return stopping_ || pending_ != 0; });
            if (stopping_) return; // no queued dispatch after shutdown
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
    const std::thread::id workerId_; // immutable identity for self-join guard
    std::mutex joinMutex_; // serializes competing joins, never held by worker
    bool joined_ = false; // guarded by joinMutex_
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

static void testConcurrentShutdownJoinersWaitForActiveCallback() {
    std::promise<void> callbackEntered;
    std::promise<void> releaseCallback;
    const auto release = releaseCallback.get_future().share();
    std::atomic<int> callbacks{0};
    std::atomic<int> finishedJoiners{0};
    int userdata = 19;
    HostDispatchOwner owner(&userdata, [&](void* raw) {
        callbackEntered.set_value();
        release.wait();
        if (*static_cast<int*>(raw) == 19) ++callbacks;
    });
    check(owner.queueCallback(), "initial callback rejected");
    callbackEntered.get_future().wait();

    std::vector<std::thread> joiners;
    for (int i = 0; i < 8; ++i) {
        joiners.emplace_back([&] {
            owner.shutdownAndJoin();
            ++finishedJoiners;
        });
    }
    // Wait for the first shutdown to close admission. It cannot join until
    // the blocked callback finishes, and the other callers must not return.
    while (!owner.admissionClosed()) std::this_thread::yield();
    const bool allWaiting = finishedJoiners.load() == 0;
    releaseCallback.set_value();
    for (auto& thread : joiners) thread.join();
    check(allWaiting, "shutdown caller returned before worker exited");
    check(finishedJoiners == 8, "not all competing shutdowns joined");
    check(callbacks == 1, "running callback count incorrect");
    owner.shutdownAndJoin(); // completed shutdown is idempotent
    check(!owner.queueCallback(), "post-shutdown callback admitted");
}

static void testShutdownDropsQueuedCallbacksButJoinsActive() {
    std::promise<void> entered;
    std::promise<void> unblock;
    const auto release = unblock.get_future().share();
    std::atomic<int> calls{0};
    int userdata = 23;
    HostDispatchOwner owner(&userdata, [&](void* raw) {
        const int sequence = ++calls;
        if (sequence == 1) {
            entered.set_value();
            release.wait();
        }
        check(*static_cast<int*>(raw) == 23, "callback used invalid userdata");
    });
    check(owner.queueCallback(), "active callback not queued");
    entered.get_future().wait();
    for (int i = 0; i < 8; ++i)
        check(owner.queueCallback(), "pending callback not queued");
    std::atomic<bool> joined{false};
    std::thread shutdown([&] {
        owner.shutdownAndJoin();
        joined.store(true);
    });
    while (!owner.admissionClosed()) std::this_thread::yield();
    const bool waited = !joined.load();
    check(!owner.queueCallback(), "shutdown admitted fresh callback");
    unblock.set_value();
    shutdown.join();
    check(waited, "joined while callback still running");
    check(joined.load(), "shutdown join did not finish");
    check(calls == 1, "queued callbacks executed during teardown");
    owner.shutdownAndJoin(); // idempotent
}

static void testWorkerCallbackCannotShutdownAndJoinItself() {
    int userdata = 29;
    std::atomic<bool> rejected{false};
    std::atomic<int> callbacks{0};
    HostDispatchOwner* dispatcher = nullptr;
    HostDispatchOwner owner(&userdata, [&](void* raw) {
        check(*static_cast<int*>(raw) == 29, "invalid callback userdata");
        try {
            dispatcher->shutdownAndJoin();
        } catch (const std::logic_error&) {
            rejected.store(true);
        }
        ++callbacks;
    });
    dispatcher = &owner;
    check(owner.queueCallback(), "self-join callback not queued");
    // A safe outside caller must remain able to perform the real join.
    owner.shutdownAndJoin();
    check(callbacks == 1, "callback did not complete after self-join rejection");
    check(rejected.load(), "callback was permitted to join itself");
    check(!owner.queueCallback(), "post-join callback admitted");
}

int main() {
    testQueuedCallbackSurvivesRemovalAndDrain();
    testRunningCallbackKeepsResourceAliveDuringShutdown();
    testHostDispatcherJoinPrecedesRawUserdataRelease();
    testConcurrentShutdownJoinersWaitForActiveCallback();
    testShutdownDropsQueuedCallbacksButJoinsActive();
    testWorkerCallbackCannotShutdownAndJoinItself();
    std::cout << "M8_RAW_USERDATA_HOST_DISPATCH_ORDER=PASS\n"
              << "M8_ANDROID_DISPATCH_THREAD_JOIN_VERIFIED=false\n"
              << "M8_ANDROID_LOOPER_QUIESCENCE_VERIFIED=false\n"
              << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
}
