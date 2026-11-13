// Host execution of the ACTUAL isolated dedicated Android looper class.
// Fake NDK functions model poll/wake/callbacks; this is NOT an Android test.
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <future>
#include <iostream>
#include <mutex>
#include <memory>
#include <stdexcept>
#include <thread>
#include "m8-owned-looper-test-shim/android/looper.h"
#include "m8-renderer-callback-ownership-model.h"

struct ALooper {
    std::mutex mutex;
    std::condition_variable ready;
    bool wake = false;
    bool pending = false;
    bool registered = false;
    std::atomic<bool> failRemove{false};
    std::atomic<bool> failAdd{false};
    std::atomic<int> references{0};
    std::atomic<int> acquireCalls{0};
    std::atomic<int> releaseCalls{0};
    std::atomic<int> alreadyRemovedReturns{0};
    std::atomic<int> removeAttempts{0};
    std::thread::id preparingThread; // set by the worker in ALooper_prepare
    std::promise<void> removalSucceeded; // signals completed deregistration
    std::promise<void> callbackAutoRemoved; // callback returned zero
    int fd = -1;
    ALooper_callbackFunc callback = nullptr;
    void* userdata = nullptr;
};
static ALooper platform;
static thread_local ALooper* currentLooper = nullptr;
static std::atomic<bool> failPrepare{false};

ALooper* ALooper_prepare(int) {
    if (failPrepare.load()) return nullptr;
    currentLooper = &platform;
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        platform.preparingThread = std::this_thread::get_id();
    }
    return currentLooper;
}
void ALooper_acquire(ALooper* ptr) { ++ptr->acquireCalls; ++ptr->references; }
void ALooper_release(ALooper* ptr) { ++ptr->releaseCalls; --ptr->references; }
void ALooper_wake(ALooper* ptr) {
    std::lock_guard<std::mutex> lock(ptr->mutex);
    ptr->wake = true;
    ptr->ready.notify_all();
}
int ALooper_addFd(ALooper* ptr, int fd, int, int, ALooper_callbackFunc cb, void* data) {
    std::lock_guard<std::mutex> lock(ptr->mutex);
    if (ptr->registered || ptr->failAdd) return -1;
    ptr->fd = fd;
    ptr->callback = cb;
    ptr->userdata = data;
    ptr->registered = true;
    return 1;
}
int ALooper_removeFd(ALooper* ptr, int fd) {
    std::lock_guard<std::mutex> lock(ptr->mutex);
    ++ptr->removeAttempts;
    if (ptr->failRemove) return -1;
    if (!ptr->registered || ptr->fd != fd) {
        ++ptr->alreadyRemovedReturns;
        return 0;
    }
    ptr->registered = false;
    ptr->pending = false;
    ptr->callback = nullptr;
    ptr->userdata = nullptr;
    ptr->removalSucceeded.set_value();
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
        const int callbackResult = cb(fd, ALOOPER_EVENT_INPUT, data);
        if (callbackResult == 0) {
            std::lock_guard<std::mutex> afterCallback(ptr->mutex);
            if (ptr->registered && ptr->fd == fd && ptr->callback == cb &&
                ptr->userdata == data) {
                ptr->registered = false;
                ptr->pending = false;
                ptr->callback = nullptr;
                ptr->userdata = nullptr;
                ptr->callbackAutoRemoved.set_value();
            }
        }
        return 1;
    }
    return 0;
}
#include "m8-android-owned-looper-thread.cpp"
#include "m8-renderer-owned-looper-adapter.h"
#include "m8-renderer-session-owned-looper.h"
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
    std::promise<std::thread::id>* callbackThread;
    std::shared_future<void>* release;
    anvildesk_m8::DedicatedLooperThread* owner;
    std::atomic<bool>* selfJoinRejected;
};
static int heldCallback(int, int, void* raw) {
    auto* userdata = static_cast<Userdata*>(raw);
    ++*userdata->calls;
    userdata->callbackThread->set_value(std::this_thread::get_id());
    // Joining from inside the owned worker must be rejected, without
    // closing registration admission or poisoning later external teardown.
    try {
        userdata->owner->shutdownAndJoin();
    } catch (const std::logic_error&) {
        userdata->selfJoinRejected->store(true);
    }
    userdata->entered->set_value();
    userdata->release->wait();
    return 1;
}
static void testWakeJoinAndRemoveRetry() {
    std::promise<void> entered, releaseSignal;
    std::promise<std::thread::id> callbackThread;
    auto callbackThreadFuture = callbackThread.get_future();
    const auto callerThread = std::this_thread::get_id();
    auto released = releaseSignal.get_future().share();
    std::atomic<int> calls{0};
    std::atomic<bool> selfJoinRejected{false};
    anvildesk_m8::RendererCallbackOwnershipModel ownership;
    require(ownership.registerSession(50), "failed-retry model registration failed");
    require(ownership.callbackMayEnter(50), "failed-retry model callback admission closed");
    const int acquiredBefore = platform.acquireCalls.load();
    const int releasedBefore = platform.releaseCalls.load();
    anvildesk_m8::DedicatedLooperThread owner;
    Userdata userdata{&calls, &entered, &callbackThread, &released,
                      &owner, &selfJoinRejected};
    require(owner.initialized(), "looper prepare failed");
    require(platform.references == 1, "looper ref not held");
    // Invalid registrations must leave admission open and must never install
    // a descriptor in the fake NDK looper before a later valid request.
    require(!owner.registerCallback(-1, ALOOPER_EVENT_INPUT, heldCallback, &userdata),
            "negative FD registration accepted");
    require(!owner.registerCallback(50, 0, heldCallback, &userdata),
            "zero-event registration accepted");
    require(!owner.registerCallback(50, ALOOPER_EVENT_INPUT, nullptr, &userdata),
            "null callback registration accepted");
    require(!owner.registerCallback(50, ALOOPER_EVENT_INPUT, heldCallback, nullptr),
            "null userdata registration accepted");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered, "invalid registration mutated looper state");
    }
    // Valid input can still fail inside ALooper_addFd. A rejected add must
    // leave activeFd unset and permit a later successful registration.
    platform.failAdd.store(true);
    require(!owner.registerCallback(50, ALOOPER_EVENT_INPUT, heldCallback, &userdata),
            "NDK addFd failure was reported as successful");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered, "failed NDK addFd changed registered state");
    }
    platform.failAdd.store(false);
    require(owner.registerCallback(50, ALOOPER_EVENT_INPUT, heldCallback, &userdata),
            "first FD registration failed");
    require(!owner.registerCallback(51, ALOOPER_EVENT_INPUT, heldCallback, &userdata),
            "duplicate FD registration admitted");
    triggerEvent();
    entered.get_future().wait();
    const auto actualCallbackThread = callbackThreadFuture.get();
    std::thread::id preparedWorkerThread;
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        preparedWorkerThread = platform.preparingThread;
    }
    const bool dispatchedOnWorker =
        preparedWorkerThread != std::thread::id{} &&
        actualCallbackThread == preparedWorkerThread &&
        actualCallbackThread != callerThread;
    const int removeAttemptsBeforeFailures = platform.removeAttempts.load();
    require(ownership.beginShutdown(), "failed-retry model shutdown admission failed");
    require(!ownership.callbackMayEnter(50),
            "failed-retry model admitted callback after stop request");
    platform.failRemove.store(true);
    require(!owner.shutdownAndJoin(), "first failed removal unexpectedly completed shutdown");
    require(!owner.shutdownAndJoin(), "second failed removal unexpectedly completed shutdown");
    require(platform.removeAttempts.load() == removeAttemptsBeforeFailures + 2,
            "failed removals did not each invoke the NDK removal path");
    require(ownership.recordRemoveFailure(), "failed-retry model did not record removal error");
    require(!ownership.mayFreeUserdata() && !ownership.releaseUserdata(),
            "failed removal permitted premature userdata release");
    require(!ownership.acknowledgeWorkerJoined(),
            "failed removal permitted an unverified join transition");
    require(ownership.retryRemoval(), "failed-retry model could not retry removal");
    // A failed remove must not join or release the worker: the callback
    // still owns stack-backed userdata and remains blocked until released.
    require(platform.references.load() == 1 &&
            platform.acquireCalls.load() == acquiredBefore + 1 &&
            platform.releaseCalls.load() == releasedBefore,
            "failed removal prematurely released the looper reference");
    require(!owner.registerCallback(51, ALOOPER_EVENT_INPUT, heldCallback, &userdata),
            "failed removal reopened registration");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(platform.registered && platform.fd == 50,
                "repeated failed removal lost the original callback registration");
    }
    platform.failRemove.store(false);
    auto deregistered = platform.removalSucceeded.get_future();
    std::atomic<bool> completed{false};
    std::thread shutdown([&] { completed = owner.shutdownAndJoin(); });
    // Wait for REAL proof that the retry deregistered the FD, not merely
    // for the shutdown thread to be scheduled. The callback is still blocked.
    deregistered.wait();
    require(ownership.recordRemoveSuccessOrAbsent(),
            "failed-retry model did not record successful removal");
    require(!ownership.mayFreeUserdata() && !ownership.releaseUserdata(),
            "successful removal without join permitted userdata release");
    // Registration from a different external thread, after confirmed FD
    // removal but before callback release, must not reopen admission.
    std::atomic<bool> lateRegistrationAccepted{false};
    std::thread lateRegistrar([&] {
        lateRegistrationAccepted.store(
            owner.registerCallback(53, ALOOPER_EVENT_INPUT,
                                   heldCallback, &userdata));
    });
    lateRegistrar.join();
    // Queue a fresh readiness notification after ALooper_removeFd succeeded
    // while the first callback is still blocked. The owned worker must never
    // deliver it after the current callback returns and shutdown joins.
    triggerEvent();
    const bool waited = !completed.load();
    const bool stillHeld =
        platform.references.load() == 1 &&
        platform.releaseCalls.load() == releasedBefore;
    // A second external shutdown caller must serialize behind the first,
    // not race the worker join or release the looper while userdata is live.
    std::atomic<bool> competingCompleted{false};
    std::thread competingShutdown([&] {
        competingCompleted = owner.shutdownAndJoin();
    });
    const bool competingWaited = !competingCompleted.load();
    releaseSignal.set_value();
    shutdown.join();
    competingShutdown.join();
    require(completed && competingCompleted,
            "failed-retry worker join did not complete");
    require(ownership.acknowledgeWorkerJoined(),
            "failed-retry model rejected completed worker join");
    require(ownership.mayFreeUserdata() && ownership.releaseUserdata(),
            "failed-retry model did not release after worker join");
    require(!ownership.releaseUserdata(), "failed-retry model allowed double release");
    require(dispatchedOnWorker, "callback did not run on the looper preparing thread");
    require(selfJoinRejected, "owned worker failed to reject self-join");
    require(stillHeld, "looper reference released before callback completed");
    require(waited, "join completed while active callback still blocked");
    require(competingWaited, "competing shutdown completed before callback release");
    require(completed, "retry did not join worker");
    require(competingCompleted, "competing shutdown did not observe joined state");
    require(!lateRegistrationAccepted, "registration reopened after successful FD removal");
    require(calls == 1, "callback dispatched again after descriptor removal");
    require(platform.references == 0, "retained looper ref leaked");
    require(platform.acquireCalls.load() == acquiredBefore + 1 &&
            platform.releaseCalls.load() == releasedBefore + 1,
            "callback shutdown did not balance exactly one acquire/release");
    require(!owner.registerCallback(52, ALOOPER_EVENT_INPUT, heldCallback, &userdata),
            "registration admitted after joined shutdown");
    require(owner.shutdownAndJoin(), "repeat shutdown not idempotent");
}
static void testShutdownWithoutRegisteredCallback() {
    // Exercise the no-FD lifecycle separately. The previous scenario has
    // completed and no callback or external user retains the looper.
    require(platform.references == 0, "prior test retained a looper reference");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered, "prior test retained a callback");
        platform.pending = false;
        platform.wake = false;
    }
    const int acquiredBefore = platform.acquireCalls.load();
    const int releasedBefore = platform.releaseCalls.load();
    anvildesk_m8::DedicatedLooperThread owner;
    require(owner.initialized(), "idle looper preparation failed");
    require(platform.references == 1, "idle looper reference was not acquired");
    require(owner.shutdownAndJoin(), "idle looper did not shutdown and join");
    require(platform.references == 0, "idle looper reference leaked");
    require(platform.acquireCalls.load() == acquiredBefore + 1 &&
            platform.releaseCalls.load() == releasedBefore + 1,
            "idle shutdown did not balance exactly one acquire/release");
    require(!owner.registerCallback(70, ALOOPER_EVENT_INPUT,
                                    heldCallback, &owner),
            "idle looper admitted callback registration after join");
    require(owner.shutdownAndJoin(), "idle looper shutdown not idempotent");
}

static void testPrepareFailureAndShutdown() {
    require(platform.references == 0, "prior looper reference was not released");
    const int acquiredBefore = platform.acquireCalls.load();
    const int releasedBefore = platform.releaseCalls.load();
    failPrepare.store(true);
    {
        anvildesk_m8::DedicatedLooperThread owner;
        require(!owner.initialized(), "failed looper preparation was accepted");
        require(!owner.registerCallback(80, ALOOPER_EVENT_INPUT,
                                        heldCallback, &owner),
                "registration admitted without a prepared looper");
        require(owner.shutdownAndJoin(), "failed-prepare worker could not join");
        require(owner.shutdownAndJoin(), "failed-prepare shutdown not idempotent");
        require(platform.references == 0, "failed preparation leaked a reference");
        require(platform.acquireCalls.load() == acquiredBefore &&
                platform.releaseCalls.load() == releasedBefore,
                "failed prepare unexpectedly acquired or released a looper ref");
    }
    failPrepare.store(false);
    // A failed worker must not poison a fresh, independent owner.
    anvildesk_m8::DedicatedLooperThread recovered;
    require(recovered.initialized(), "looper did not recover after prepare failure");
    require(recovered.shutdownAndJoin(), "recovered looper could not join");
    require(platform.references == 0, "recovered looper leaked a reference");
    require(platform.acquireCalls.load() == acquiredBefore + 1 &&
            platform.releaseCalls.load() == releasedBefore + 1,
            "recovered worker did not balance exactly one acquire/release");
}

static int autoRemovingCallback(int, int, void* raw) {
    auto* invocations = static_cast<std::atomic<int>*>(raw);
    ++*invocations;
    return 0; // NDK callback convention: unregister this descriptor
}
static void testCallbackReturnZeroDeregistration() {
    require(platform.references == 0, "prior looper leaked a reference");
    const int baselineAlreadyRemoved = platform.alreadyRemovedReturns.load();
    const int acquiredBefore = platform.acquireCalls.load();
    const int releasedBefore = platform.releaseCalls.load();
    std::atomic<int> invocations{0};
    anvildesk_m8::RendererCallbackOwnershipModel ownership;
    require(ownership.registerSession(90), "ownership registration failed");
    require(ownership.callbackMayEnter(90), "ownership callback admission failed");
    auto removed = platform.callbackAutoRemoved.get_future();
    anvildesk_m8::DedicatedLooperThread owner;
    require(owner.initialized(), "auto-removal looper preparation failed");
    require(owner.registerCallback(90, ALOOPER_EVENT_INPUT,
                                   autoRemovingCallback, &invocations),
            "auto-removing callback registration failed");
    triggerEvent();
    require(removed.wait_for(std::chrono::seconds(5)) == std::future_status::ready,
            "callback did not auto-remove its descriptor");
    require(ownership.beginShutdown(), "ownership stop admission failed");
    require(!ownership.callbackMayEnter(90), "ownership admitted callback during shutdown");
    require(ownership.recordRemoveSuccessOrAbsent(),
            "ownership failed to record callback auto-removal");
    require(!ownership.mayFreeUserdata(),
            "ownership allowed userdata free before real worker join");
    require(!ownership.releaseUserdata(),
            "ownership released userdata before real worker join");
    // A callback that returned zero has removed its own descriptor. It must
    // not be replaced on the same owner, even before shutdown begins.
    require(!owner.registerCallback(91, ALOOPER_EVENT_INPUT,
                                    autoRemovingCallback, &invocations),
            "replacement registration admitted after callback auto-removal");
    // Queue another readiness notification after confirmed removal. Joining
    // the owned poll worker establishes that this event was not dispatched.
    triggerEvent();
    require(owner.shutdownAndJoin(), "shutdown failed after callback auto-removal");
    // A real worker join (albeit under a fake NDK host platform) is the only
    // point at which this test acknowledges the ownership join transition.
    require(ownership.acknowledgeWorkerJoined(),
            "ownership did not accept verified worker join");
    require(ownership.mayFreeUserdata(),
            "ownership failed to allow userdata cleanup after worker join");
    require(ownership.releaseUserdata(), "ownership cleanup transition failed");
    require(!ownership.releaseUserdata(), "ownership permitted double release");
    require(platform.alreadyRemovedReturns.load() == baselineAlreadyRemoved + 1,
            "shutdown did not accept the already-removed FD return code");
    const int attemptsAfterJoin = platform.removeAttempts.load();
    require(owner.shutdownAndJoin(), "repeated shutdown failed after auto-removal");
    require(platform.removeAttempts.load() == attemptsAfterJoin,
            "repeated shutdown attempted to remove descriptor again");
    require(platform.alreadyRemovedReturns.load() == baselineAlreadyRemoved + 1,
            "repeated shutdown requested removal of already-removed FD");
    require(platform.references == 0,
            "repeated shutdown double-released or reacquired looper reference");
    require(platform.acquireCalls.load() == acquiredBefore + 1 &&
            platform.releaseCalls.load() == releasedBefore + 1,
            "auto-removal repeated shutdown did not balance exactly one ref");
    require(invocations == 1, "auto-removing callback invoked unexpectedly");
    require(platform.references == 0, "callback auto-removal leaked looper ref");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered, "auto-removed descriptor remained registered");
    }
    require(!owner.registerCallback(91, ALOOPER_EVENT_INPUT,
                                    autoRemovingCallback, &invocations),
            "post-join registration admitted after callback auto-removal");
}
static int adapterCallback(int, int, void* raw) {
    ++*static_cast<std::atomic<int>*>(raw);
    return 1;
}
static void testUnifiedOwnershipAdapterFailureAndRetry() {
    require(platform.references == 0, "previous looper owner leaked reference");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered, "previous looper left FD registered");
        // The fake NDK looper is reused across scenarios. Reset its one-shot
        // deregistration notification only after the preceding owner joined.
        platform.removalSucceeded = std::promise<void>{};
        platform.pending = false;
        platform.wake = false;
    }
    std::atomic<int> calls{0};
    anvildesk_m8::RendererOwnedLooperAdapter adapter;
    require(!adapter.userdataReleaseEligible(), "new adapter freed absent userdata");
    require(!adapter.registerCallback(0, 100, ALOOPER_EVENT_INPUT,
                                      adapterCallback, &calls),
            "adapter admitted invalid generation");
    const int attemptsBeforeFailure = platform.removeAttempts.load();
    platform.failAdd.store(true);
    require(!adapter.registerCallback(100, 100, ALOOPER_EVENT_INPUT,
                                      adapterCallback, &calls),
            "adapter accepted NDK addFd failure");
    require(!adapter.userdataReleaseEligible() && !adapter.releaseAuthorization(),
            "failed addFd created a releasable ownership record");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered, "failed addFd registered a platform callback");
    }
    require(platform.removeAttempts.load() == attemptsBeforeFailure,
            "failed addFd unexpectedly attempted FD removal");
    platform.failAdd.store(false);
    require(adapter.registerCallback(100, 100, ALOOPER_EVENT_INPUT,
                                     adapterCallback, &calls),
            "adapter refused valid callback after NDK addFd failure");
    require(!adapter.registerCallback(101, 101, ALOOPER_EVENT_INPUT,
                                      adapterCallback, &calls),
            "adapter accepted replacement registration");
    triggerEvent();
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (calls.load() == 0 && std::chrono::steady_clock::now() < deadline)
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    require(calls == 1, "adapter callback did not execute on worker");
    platform.failRemove.store(true);
    require(!adapter.shutdownAndJoin(), "adapter accepted failed FD removal");
    require(!adapter.userdataReleaseEligible() && !adapter.releaseAuthorization(),
            "failed adapter shutdown authorized userdata release");
    require(platform.references == 1, "failed adapter shutdown released looper");
    require(!adapter.registerCallback(102, 102, ALOOPER_EVENT_INPUT,
                                      adapterCallback, &calls),
            "failed adapter shutdown reopened registration");
    platform.failRemove.store(false);
    require(adapter.shutdownAndJoin(), "adapter removal retry failed to join");
    require(platform.references == 0, "adapter retry leaked looper reference");
    require(adapter.userdataReleaseEligible(), "adapter join did not authorize cleanup");
    require(adapter.releaseAuthorization(), "adapter failed cleanup authorization");
    require(!adapter.releaseAuthorization(), "adapter authorized repeated cleanup");
    require(adapter.shutdownAndJoin(), "adapter repeated shutdown not idempotent");
    require(!adapter.registerCallback(103, 103, ALOOPER_EVENT_INPUT,
                                      adapterCallback, &calls),
            "adapter admitted registration after join");
}
struct AdapterHeldCallbackContext {
    std::promise<void>* entered;
    std::shared_future<void>* release;
    std::atomic<int>* calls;
    anvildesk_m8::RendererOwnedLooperAdapter* adapter;
    std::atomic<bool>* rejectedSelfJoin;
    std::atomic<bool>* rejectedWorkerOperations;
};
static int heldAdapterCallback(int, int, void* raw) {
    auto* context = static_cast<AdapterHeldCallbackContext*>(raw);
    ++*context->calls;
    context->entered->set_value();
    context->release->wait();
    // An external thread can hold the adapter shutdown mutex while joining
    // this worker. Reject without waiting on that mutex or corrupting model.
    try {
        context->adapter->shutdownAndJoin();
    } catch (const std::logic_error&) {
        context->rejectedSelfJoin->store(true);
    }
    // Every adapter API that would acquire its mutex must decline
    // from a worker callback before blocking on external join.
    const bool rejected = !context->adapter->releaseAuthorization() &&
                          !context->adapter->userdataReleaseEligible() &&
                          !context->adapter->registerCallback(
                              201, 201, ALOOPER_EVENT_INPUT,
                              heldAdapterCallback, raw);
    context->rejectedWorkerOperations->store(rejected);
    return 1;
}
static void testUnifiedAdapterConcurrentShutdownAndActiveCallback() {
    require(platform.references == 0, "prior adapter retained a looper reference");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered, "prior adapter left a callback registered");
        platform.removalSucceeded = std::promise<void>{};
        platform.pending = false;
        platform.wake = false;
    }
    std::promise<void> entered, release;
    auto callbackEntered = entered.get_future();
    auto releaseSignal = release.get_future().share();
    auto removed = platform.removalSucceeded.get_future();
    std::atomic<int> calls{0};
    std::atomic<bool> rejectedSelfJoin{false};
    std::atomic<bool> rejectedWorkerOperations{false};
    anvildesk_m8::RendererOwnedLooperAdapter adapter;
    AdapterHeldCallbackContext context{&entered, &releaseSignal, &calls,
                                       &adapter, &rejectedSelfJoin,
                                       &rejectedWorkerOperations};
    require(adapter.registerCallback(200, 200, ALOOPER_EVENT_INPUT,
                                     heldAdapterCallback, &context),
            "concurrent adapter registration failed");
    triggerEvent();
    require(callbackEntered.wait_for(std::chrono::seconds(5)) ==
                std::future_status::ready, "concurrent adapter callback never entered");

    std::atomic<bool> firstDone{false}, secondDone{false};
    std::atomic<bool> firstResult{false}, secondResult{false};
    std::thread first([&] {
        firstResult = adapter.shutdownAndJoin();
        firstDone = true;
    });
    // The real fake-NDK remove signals before the worker can join, because
    // the active callback is held by the release promise.
    removed.wait();
    std::thread second([&] {
        secondResult = adapter.shutdownAndJoin();
        secondDone = true;
    });
    // Cleanup authorization is serialized with shutdown; it must not
    // return early while the worker is still executing callback userdata.
    std::promise<void> releaseAttempt;
    auto attemptStarted = releaseAttempt.get_future();
    std::atomic<bool> authorized{false}, authorizationFinished{false};
    std::thread authorization([&] {
        releaseAttempt.set_value();
        authorized = adapter.releaseAuthorization();
        authorizationFinished = true;
    });
    attemptStarted.wait();
    const bool noEarlyCompletion =
        !firstDone.load() && !secondDone.load() &&
        !authorizationFinished.load() && platform.references.load() == 1;
    release.set_value();
    first.join();
    second.join();
    authorization.join();
    require(noEarlyCompletion, "adapter completed teardown while callback was active");
    require(rejectedSelfJoin, "adapter did not reject self-join from active callback");
    require(rejectedWorkerOperations,
            "adapter allowed worker operations or blocked behind external join");
    require(firstResult && secondResult, "competing adapter shutdown failed");
    require(authorized && authorizationFinished,
            "adapter authorization did not serialize after worker join");
    require(calls == 1, "adapter callback dispatched unexpectedly again");
    require(platform.references == 0, "competing adapter shutdown leaked looper");
    require(!adapter.releaseAuthorization(), "adapter authorized duplicate release");
    require(adapter.shutdownAndJoin(), "competing adapter repeat shutdown failed");
}
static void testUnifiedAdapterPrepareFailureRecovery() {
    require(platform.references == 0, "prior adapter leaked looper reference");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered, "prior adapter left callback registered");
        // The recovered adapter will remove a new FD. Retire the prior
        // scenario's completed one-shot removal signal after its worker joined.
        platform.removalSucceeded = std::promise<void>{};
        platform.pending = false;
        platform.wake = false;
    }
    const int acquisitions = platform.acquireCalls.load();
    const int releases = platform.releaseCalls.load();
    std::atomic<int> calls{0};
    failPrepare.store(true);
    {
        anvildesk_m8::RendererOwnedLooperAdapter failed;
        require(!failed.registerCallback(300, 300, ALOOPER_EVENT_INPUT,
                                         adapterCallback, &calls),
                "adapter accepted registration without prepared looper");
        require(!failed.userdataReleaseEligible() &&
                !failed.releaseAuthorization(),
                "uninitialized adapter authorized userdata release");
        require(failed.shutdownAndJoin(),
                "uninitialized adapter failed to join worker");
        require(failed.shutdownAndJoin(),
                "uninitialized adapter repeat shutdown was not idempotent");
        require(!failed.registerCallback(301, 301, ALOOPER_EVENT_INPUT,
                                         adapterCallback, &calls),
                "uninitialized adapter admitted registration after shutdown");
        require(platform.references == 0 &&
                platform.acquireCalls.load() == acquisitions &&
                platform.releaseCalls.load() == releases,
                "failed adapter initialization changed looper reference ownership");
    }
    failPrepare.store(false);
    {
        anvildesk_m8::RendererOwnedLooperAdapter recovered;
        require(recovered.registerCallback(302, 302, ALOOPER_EVENT_INPUT,
                                           adapterCallback, &calls),
                "fresh adapter could not register after failed preparation");
        require(recovered.shutdownAndJoin(), "recovered adapter could not join");
        require(recovered.userdataReleaseEligible() &&
                recovered.releaseAuthorization(),
                "recovered adapter did not permit cleanup after join");
        require(platform.references == 0 &&
                platform.acquireCalls.load() == acquisitions + 1 &&
                platform.releaseCalls.load() == releases + 1,
                "recovered adapter did not balance its looper reference");
    }
}
static void testUnifiedAdapterCallbackAutoRemoval() {
    require(platform.references == 0, "prior adapter leaked looper ref");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered, "prior adapter left FD registered");
        platform.callbackAutoRemoved = std::promise<void>{};
        platform.removalSucceeded = std::promise<void>{};
        platform.pending = false;
        platform.wake = false;
    }
    const int alreadyRemoved = platform.alreadyRemovedReturns.load();
    const int acquired = platform.acquireCalls.load();
    const int released = platform.releaseCalls.load();
    std::atomic<int> calls{0};
    auto autoRemoved = platform.callbackAutoRemoved.get_future();
    anvildesk_m8::RendererOwnedLooperAdapter adapter;
    require(adapter.registerCallback(400, 400, ALOOPER_EVENT_INPUT,
                                     autoRemovingCallback, &calls),
            "adapter refused auto-remove callback registration");
    triggerEvent();
    require(autoRemoved.wait_for(std::chrono::seconds(5)) ==
                std::future_status::ready,
            "adapter callback did not auto-remove FD");
    require(calls == 1, "adapter callback auto-removal invoked incorrect count");
    require(!adapter.registerCallback(401, 401, ALOOPER_EVENT_INPUT,
                                      autoRemovingCallback, &calls),
            "adapter replaced callback after auto-removal");
    require(!adapter.userdataReleaseEligible() && !adapter.releaseAuthorization(),
            "adapter released userdata before auto-removal worker join");
    triggerEvent();
    require(adapter.shutdownAndJoin(),
            "adapter failed to join after callback auto-removal");
    require(platform.alreadyRemovedReturns.load() == alreadyRemoved + 1,
            "adapter shutdown did not accept already-removed descriptor");
    require(platform.references == 0 &&
            platform.acquireCalls.load() == acquired + 1 &&
            platform.releaseCalls.load() == released + 1,
            "auto-removal adapter leaked or double-released looper reference");
    require(calls == 1, "adapter dispatched pending event after auto-removal");
    require(adapter.userdataReleaseEligible() && adapter.releaseAuthorization(),
            "adapter failed to authorize cleanup after auto-removal join");
    require(!adapter.releaseAuthorization(), "adapter allowed double cleanup");
    require(adapter.shutdownAndJoin(), "adapter repeated auto-removal shutdown failed");
    require(!adapter.registerCallback(402, 402, ALOOPER_EVENT_INPUT,
                                      autoRemovingCallback, &calls),
            "adapter registered callback after auto-removal join");
}
static void testUnifiedAdapterShutdownBeforeRegistration() {
    require(platform.references == 0, "prior adapter leaked looper ref");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered, "prior adapter left FD registered");
        platform.pending = false;
        platform.wake = false;
    }
    const int acquired = platform.acquireCalls.load();
    const int released = platform.releaseCalls.load();
    const int removes = platform.removeAttempts.load();
    std::atomic<int> calls{0};
    anvildesk_m8::RendererOwnedLooperAdapter adapter;
    require(!adapter.userdataReleaseEligible() && !adapter.releaseAuthorization(),
            "unused adapter authorized cleanup before shutdown");
    require(adapter.shutdownAndJoin(), "unused adapter shutdown failed");
    require(adapter.shutdownAndJoin(), "unused adapter repeat shutdown failed");
    require(!adapter.registerCallback(500, 500, ALOOPER_EVENT_INPUT,
                                      adapterCallback, &calls),
            "unused adapter admitted callback after shutdown");
    require(!adapter.userdataReleaseEligible() && !adapter.releaseAuthorization(),
            "unused adapter authorized cleanup without ever owning userdata");
    require(platform.references == 0 &&
            platform.acquireCalls.load() == acquired + 1 &&
            platform.releaseCalls.load() == released + 1,
            "unused adapter shutdown did not balance one looper reference");
    require(platform.removeAttempts.load() == removes,
            "unused adapter attempted to remove an unregistered descriptor");
}
struct HeapAdapterCallbackContext {
    std::atomic<int>* calls;
    std::promise<void>* entered;
    std::shared_future<void>* release;
};
static int heapAdapterCallback(int, int, void* raw) {
    auto* context = static_cast<HeapAdapterCallbackContext*>(raw);
    ++*context->calls;
    context->entered->set_value();
    context->release->wait();
    return 1;
}
static void testUnifiedAdapterHeapUserdataLifetime() {
    require(platform.references == 0, "prior adapter leaked a looper ref");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered, "prior adapter left a callback registered");
        platform.removalSucceeded = std::promise<void>{};
        platform.pending = false;
        platform.wake = false;
        require(platform.callback == nullptr && platform.userdata == nullptr,
                "fake platform retained stale callback userdata after removal");
    }
    std::atomic<int> calls{0};
    std::promise<void> entered, release;
    auto enteredFuture = entered.get_future();
    auto callbackRelease = release.get_future().share();
    auto context = std::make_unique<HeapAdapterCallbackContext>(
        HeapAdapterCallbackContext{&calls, &entered, &callbackRelease});
    anvildesk_m8::RendererOwnedLooperAdapter adapter;
    require(adapter.registerCallback(600, 600, ALOOPER_EVENT_INPUT,
                                     heapAdapterCallback, context.get()),
            "heap callback registration failed");
    triggerEvent();
    require(enteredFuture.wait_for(std::chrono::seconds(5)) ==
                std::future_status::ready,
            "heap callback never started");
    std::atomic<bool> joined{false};
    std::thread teardown([&] { joined = adapter.shutdownAndJoin(); });
    auto removed = platform.removalSucceeded.get_future();
    require(removed.wait_for(std::chrono::seconds(5)) == std::future_status::ready,
            "heap callback was not removed");
    // Shutdown holds the adapter mutex until join. Querying eligibility
    // here would block this test while its callback waits for release.
    require(!joined, "heap owner joined while callback remained active");
    // Keep the heap allocation valid until the callback is unblocked and
    // the owned worker has completed its join.
    release.set_value();
    teardown.join();
    require(joined, "heap callback owner did not join");
    require(adapter.userdataReleaseEligible() && adapter.releaseAuthorization(),
            "heap userdata cleanup was not authorized after join");
    context.reset();
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered && platform.callback == nullptr &&
                platform.userdata == nullptr,
                "fake NDK retained callback userdata after shutdown");
    }
    triggerEvent(); // no worker exists to dereference the freed heap object
    require(calls == 1, "callback invoked after userdata was freed");
    require(!adapter.releaseAuthorization(), "duplicate heap userdata cleanup accepted");
    require(adapter.shutdownAndJoin(), "heap owner repeated shutdown failed");
    require(platform.references == 0, "heap owner leaked looper reference");
}
struct CompositeHeldCallbackContext {
    std::promise<void>* entered;
    std::shared_future<void>* release;
    std::atomic<int>* calls;
};
static int compositeHeldCallback(int, int, void* raw) {
    auto* context = static_cast<CompositeHeldCallbackContext*>(raw);
    ++*context->calls;
    context->entered->set_value();
    context->release->wait();
    return 1;
}
static void testRendererSessionCompositeRetainsStateThroughFailedRemoval() {
    require(platform.references == 0, "previous owner leaked looper reference");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered, "previous owner left callback registered");
        platform.removalSucceeded = std::promise<void>{};
        platform.pending = false;
        platform.wake = false;
    }
    std::promise<void> entered, release;
    auto enteredFuture = entered.get_future();
    auto releaseSignal = release.get_future().share();
    auto removed = platform.removalSucceeded.get_future();
    std::atomic<int> calls{0};
    CompositeHeldCallbackContext userdata{&entered, &releaseSignal, &calls};
    anvildesk_m8::RendererSessionOwnedLooper owner;
    require(!owner.registerCallback(0, 720, ALOOPER_EVENT_INPUT,
                                    compositeHeldCallback, &userdata),
            "composite accepted generation zero");
    require(owner.registerCallback(720, 720, ALOOPER_EVENT_INPUT,
                                   compositeHeldCallback, &userdata),
            "composite callback registration failed");
    require(owner.holdsCallbackContext(), "composite lost registered context");
    require(!owner.registerCallback(721, 721, ALOOPER_EVENT_INPUT,
                                    compositeHeldCallback, &userdata),
            "composite admitted replacement registration");
    triggerEvent();
    require(enteredFuture.wait_for(std::chrono::seconds(5)) ==
            std::future_status::ready, "composite callback never entered");
    platform.failRemove.store(true);
    require(!owner.shutdownAndJoin(), "composite ignored failed descriptor removal");
    require(owner.holdsCallbackContext(),
            "composite freed platform callback userdata after failed removal");
    require(!owner.registerCallback(722, 722, ALOOPER_EVENT_INPUT,
                                    compositeHeldCallback, &userdata),
            "composite reopened registration after failed shutdown");
    platform.failRemove.store(false);
    std::atomic<bool> joined{false};
    std::thread shutdown([&] { joined = owner.shutdownAndJoin(); });
    require(removed.wait_for(std::chrono::seconds(5)) == std::future_status::ready,
            "composite retry did not remove FD");
    require(!joined, "composite joined while callback still active");
    release.set_value();
    shutdown.join();
    require(joined && calls == 1, "composite failed to drain active callback");
    require(!owner.holdsCallbackContext(),
            "composite retained callback context after verified join");
    require(owner.shutdownAndJoin(), "composite repeated shutdown failed");
    require(!owner.registerCallback(723, 723, ALOOPER_EVENT_INPUT,
                                    compositeHeldCallback, &userdata),
            "composite admitted callback after final shutdown");
    require(platform.references == 0, "composite shutdown leaked looper reference");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered && platform.userdata == nullptr,
                "composite teardown left stale fake platform userdata");
    }
}
static void testRendererSessionCompositeAutoRemovalAndFreshGeneration() {
    require(platform.references == 0, "prior composite leaked looper reference");
    const int baselineAlreadyRemoved = platform.alreadyRemovedReturns.load();
    const int baselineAcquires = platform.acquireCalls.load();
    const int baselineReleases = platform.releaseCalls.load();
    std::atomic<int> firstCalls{0}, secondCalls{0};
    for (int iteration = 0; iteration < 2; ++iteration) {
        {
            std::lock_guard<std::mutex> lock(platform.mutex);
            require(!platform.registered, "previous session left an FD registered");
            platform.callbackAutoRemoved = std::promise<void>{};
            platform.removalSucceeded = std::promise<void>{};
            platform.pending = false;
            platform.wake = false;
            require(platform.userdata == nullptr && platform.callback == nullptr,
                    "fake looper retained stale callback pointer");
        }
        auto removed = platform.callbackAutoRemoved.get_future();
        auto* calls = iteration == 0 ? &firstCalls : &secondCalls;
        anvildesk_m8::RendererSessionOwnedLooper owner;
        const std::uint64_t generation = iteration == 0 ? 801 : 802;
        require(owner.registerCallback(generation, 801, ALOOPER_EVENT_INPUT,
                                       autoRemovingCallback, calls),
                "composite failed to register fresh session");
        {
            std::lock_guard<std::mutex> lock(platform.mutex);
            require(platform.userdata != static_cast<void*>(calls),
                    "composite passed raw client userdata to Android");
            require(platform.userdata != nullptr &&
                    platform.callback != autoRemovingCallback,
                    "composite bypassed stable trampoline callback context");
        }
        triggerEvent();
        require(removed.wait_for(std::chrono::seconds(5)) ==
                std::future_status::ready, "composite did not auto-remove callback");
        require(calls->load() == 1, "composite auto-removal callback count incorrect");
        require(owner.holdsCallbackContext(),
                "composite released context before joining auto-removed callback");
        require(!owner.registerCallback(generation + 100, 801, ALOOPER_EVENT_INPUT,
                                        autoRemovingCallback, calls),
                "composite re-registered after callback auto-removal");
        triggerEvent(); // fake pending notification must never invoke removed callback
        require(owner.shutdownAndJoin(), "composite auto-removal shutdown failed");
        require(owner.shutdownAndJoin(), "composite repeat shutdown failed");
        require(!owner.holdsCallbackContext(), "composite leaked auto-removed context");
        require(calls->load() == 1, "composite redelivered auto-removed callback");
        require(platform.references == 0, "composite leaked looper reference");
    }
    require(firstCalls == 1 && secondCalls == 1,
            "new generation reused prior callback client state");
    require(platform.alreadyRemovedReturns.load() == baselineAlreadyRemoved + 2,
            "composite did not handle already-removed descriptor twice");
    require(platform.acquireCalls.load() == baselineAcquires + 2 &&
            platform.releaseCalls.load() == baselineReleases + 2,
            "composite reused FD with unbalanced looper references");
}
struct CompositeSelfJoinContext {
    anvildesk_m8::RendererSessionOwnedLooper* owner;
    std::promise<void>* entered;
    std::shared_future<void>* release;
    std::atomic<bool>* selfJoinRejected;
    std::atomic<bool>* workerContextInspectionRejected;
    std::atomic<int>* invocations;
};
static int compositeSelfJoinCallback(int, int, void* raw) {
    auto* data = static_cast<CompositeSelfJoinContext*>(raw);
    ++*data->invocations;
    try {
        data->owner->shutdownAndJoin();
    } catch (const std::logic_error&) {
        data->selfJoinRejected->store(true);
    }
    data->workerContextInspectionRejected->store(
        !data->owner->holdsCallbackContext());
    data->entered->set_value();
    data->release->wait();
    return 1;
}
static void testRendererSessionCompositeCompetingShutdownSelfJoin() {
    require(platform.references == 0, "prior composite leaked looper ref");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered, "prior composite left registered callback");
        platform.removalSucceeded = std::promise<void>{};
        platform.pending = false;
        platform.wake = false;
    }
    auto removed = platform.removalSucceeded.get_future();
    std::promise<void> entered, release;
    auto enteredFuture = entered.get_future();
    auto allowRelease = release.get_future().share();
    std::atomic<bool> selfJoinRejected{false}, inspectionRejected{false};
    std::atomic<int> calls{0};
    const int beforeAcquire = platform.acquireCalls.load();
    const int beforeRelease = platform.releaseCalls.load();
    anvildesk_m8::RendererSessionOwnedLooper owner;
    CompositeSelfJoinContext userdata{&owner, &entered, &allowRelease,
                                       &selfJoinRejected, &inspectionRejected,
                                       &calls};
    require(owner.registerCallback(920, 920, ALOOPER_EVENT_INPUT,
                                   compositeSelfJoinCallback, &userdata),
            "competing shutdown composite registration failed");
    triggerEvent();
    require(enteredFuture.wait_for(std::chrono::seconds(5)) ==
                std::future_status::ready, "composite self-join callback never entered");
    require(selfJoinRejected && inspectionRejected,
            "composite worker operation blocked or admitted its own join");
    std::atomic<bool> firstDone{false}, secondDone{false};
    std::atomic<bool> firstJoined{false}, secondJoined{false};
    std::thread first([&] {
        firstJoined = owner.shutdownAndJoin();
        firstDone = true;
    });
    std::thread second([&] {
        secondJoined = owner.shutdownAndJoin();
        secondDone = true;
    });
    require(removed.wait_for(std::chrono::seconds(5)) ==
                std::future_status::ready,
            "composite competing shutdown never removed descriptor");
    require(!firstDone && !secondDone,
            "composite competing shutdown finished while callback held lease");
    release.set_value();
    first.join();
    second.join();
    require(firstDone && secondDone && firstJoined && secondJoined,
            "composite competing shutdown failed to join both callers");
    require(calls == 1, "composite delivered callback more than once");
    require(!owner.holdsCallbackContext(),
            "composite kept callback userdata after concurrent join");
    require(owner.shutdownAndJoin(), "composite lost terminal joined state");
    require(!owner.registerCallback(921, 920, ALOOPER_EVENT_INPUT,
                                    compositeSelfJoinCallback, &userdata),
            "composite admitted new generation after terminal join");
    require(platform.references == 0 &&
            platform.acquireCalls.load() == beforeAcquire + 1 &&
            platform.releaseCalls.load() == beforeRelease + 1,
            "composite concurrent shutdown broke reference balance");
}
static void testRendererSessionCompositeFailedRemovalClosesCallbackAdmission() {
    require(platform.references == 0, "prior owner left looper ref active");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered, "prior owner left descriptor registered");
        platform.callbackAutoRemoved = std::promise<void>{};
        platform.removalSucceeded = std::promise<void>{};
        platform.pending = false;
        platform.wake = false;
    }
    const int alreadyAbsent = platform.alreadyRemovedReturns.load();
    const int acquires = platform.acquireCalls.load();
    const int releases = platform.releaseCalls.load();
    auto autoRemoved = platform.callbackAutoRemoved.get_future();
    std::atomic<int> calls{0};
    anvildesk_m8::RendererSessionOwnedLooper owner;
    require(owner.registerCallback(1100, 1100, ALOOPER_EVENT_INPUT,
                                   autoRemovingCallback, &calls),
            "failed-removal admission test did not register callback");
    platform.failRemove = true;
    require(!owner.shutdownAndJoin(),
            "composite ignored removal failure before callback dispatch");
    require(owner.holdsCallbackContext(),
            "composite freed callback context on removal failure");
    // With platform deregistration failed, an event may still reach the
    // trampoline. The closed generation gate must reject it WITHOUT
    // entering or dereferencing the caller's callback userdata.
    triggerEvent();
    require(autoRemoved.wait_for(std::chrono::seconds(5)) ==
                std::future_status::ready,
            "closed gate did not auto-remove late callback");
    require(calls == 0,
            "client callback executed after removal failed and admission closed");
    require(owner.holdsCallbackContext(),
            "platform auto-removal released userdata before worker join");
    platform.failRemove = false;
    require(owner.shutdownAndJoin(),
            "composite failed retry after closed-gate callback auto-removal");
    require(!owner.holdsCallbackContext(),
            "composite retained callback context after worker joined");
    require(owner.shutdownAndJoin(), "composite duplicate join failed");
    triggerEvent();
    require(calls == 0, "late event reached client after callback context cleanup");
    require(platform.alreadyRemovedReturns.load() == alreadyAbsent + 1,
            "composite did not accept already-removed descriptor after retry");
    require(platform.references == 0 &&
            platform.acquireCalls.load() == acquires + 1 &&
            platform.releaseCalls.load() == releases + 1,
            "composite removal retry unbalanced looper references");
    require(!owner.registerCallback(1101, 1100, ALOOPER_EVENT_INPUT,
                                    autoRemovingCallback, &calls),
            "composite reopened callback admission following failed removal");
}
static void testRendererSessionCompositeAddFailureAndIdleClose() {
    require(platform.references == 0, "prior composite leaked reference");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered, "prior composite left FD registered");
        platform.removalSucceeded = std::promise<void>{};
        platform.pending = false;
        platform.wake = false;
        require(platform.userdata == nullptr, "prior composite left platform userdata");
    }
    const int acquired = platform.acquireCalls.load();
    const int released = platform.releaseCalls.load();
    std::atomic<int> calls{0};
    {
        anvildesk_m8::RendererSessionOwnedLooper owner;
        require(!owner.registerCallback(1200, -1, ALOOPER_EVENT_INPUT,
                                        autoRemovingCallback, &calls),
                "composite accepted invalid descriptor");
        require(!owner.registerCallback(1200, 1200, ALOOPER_EVENT_INPUT,
                                        nullptr, &calls),
                "composite accepted null callback");
        require(!owner.registerCallback(1200, 1200, ALOOPER_EVENT_INPUT,
                                        autoRemovingCallback, nullptr),
                "composite accepted null client userdata");
        platform.failAdd = true;
        require(!owner.registerCallback(1200, 1200, ALOOPER_EVENT_INPUT,
                                        autoRemovingCallback, &calls),
                "composite accepted failed platform addFd");
        require(!owner.holdsCallbackContext(),
                "composite retained context after failed addFd");
        platform.failAdd = false;
        require(owner.registerCallback(1201, 1200, ALOOPER_EVENT_INPUT,
                                       autoRemovingCallback, &calls),
                "composite failed to register after transient addFd failure");
        require(owner.holdsCallbackContext(),
                "composite failed to retain successful callback context");
        require(owner.shutdownAndJoin(), "composite failed normal shutdown");
        require(!owner.holdsCallbackContext(),
                "composite retained callback context after worker joined");
        require(owner.shutdownAndJoin(), "composite repeated stop failed");
        require(!owner.registerCallback(1202, 1200, ALOOPER_EVENT_INPUT,
                                        autoRemovingCallback, &calls),
                "composite admitted registration after terminal stop");
    }
    require(calls == 0, "unexpected callback dispatched without readiness");
    require(platform.references == 0 &&
            platform.acquireCalls.load() == acquired + 1 &&
            platform.releaseCalls.load() == released + 1,
            "composite failed add recovery leaked a looper reference");
    {
        std::lock_guard<std::mutex> lock(platform.mutex);
        require(!platform.registered && platform.userdata == nullptr,
                "composite failed add recovery retained platform userdata");
        platform.pending = false;
        platform.wake = false;
    }
    const int removalAttempts = platform.removeAttempts.load();
    const int idleAcquired = platform.acquireCalls.load();
    const int idleReleased = platform.releaseCalls.load();
    {
        anvildesk_m8::RendererSessionOwnedLooper unused;
        require(!unused.holdsCallbackContext(),
                "idle composite unexpectedly owns callback context");
        require(unused.shutdownAndJoin(), "idle composite shutdown failed");
        require(unused.shutdownAndJoin(), "idle composite repeat shutdown failed");
        require(!unused.holdsCallbackContext(),
                "idle composite manufactured callback context during shutdown");
        require(!unused.registerCallback(1203, 1203, ALOOPER_EVENT_INPUT,
                                         autoRemovingCallback, &calls),
                "idle composite admitted registration after stop");
    }
    require(platform.removeAttempts.load() == removalAttempts,
            "idle composite attempted removing an unregistered descriptor");
    require(platform.references == 0 &&
            platform.acquireCalls.load() == idleAcquired + 1 &&
            platform.releaseCalls.load() == idleReleased + 1,
            "idle composite failed exact looper reference balance");
}
int main() {
    testWakeJoinAndRemoveRetry();
    testShutdownWithoutRegisteredCallback();
    testPrepareFailureAndShutdown();
    testCallbackReturnZeroDeregistration();
    testUnifiedOwnershipAdapterFailureAndRetry();
    testUnifiedAdapterConcurrentShutdownAndActiveCallback();
    testUnifiedAdapterPrepareFailureRecovery();
    testUnifiedAdapterCallbackAutoRemoval();
    testUnifiedAdapterShutdownBeforeRegistration();
    testUnifiedAdapterHeapUserdataLifetime();
    testRendererSessionCompositeRetainsStateThroughFailedRemoval();
    testRendererSessionCompositeAutoRemovalAndFreshGeneration();
    testRendererSessionCompositeCompetingShutdownSelfJoin();
    testRendererSessionCompositeFailedRemovalClosesCallbackAdmission();
    testRendererSessionCompositeAddFailureAndIdleClose();
    std::cout << "M8_OWNED_LOOPER_HOST_EXECUTION=PASS\n"
              << "M8_OWNED_LOOPER_COMPETING_SHUTDOWN=PASS\n"
              << "M8_OWNED_LOOPER_CALLBACK_WORKER_THREAD=PASS\n"
              << "M8_OWNED_LOOPER_CALLBACK_PREPARE_THREAD_MATCH=PASS\n"
              << "M8_OWNED_LOOPER_POST_REMOVE_EVENT_SUPPRESSED=PASS\n"
              << "M8_OWNED_LOOPER_POST_REMOVE_REGISTRATION_REJECTED=PASS\n"
              << "M8_OWNED_LOOPER_IDLE_SHUTDOWN=PASS\n"
              << "M8_OWNED_LOOPER_INVALID_REGISTRATION_REJECTED=PASS\n"
              << "M8_OWNED_LOOPER_ADD_FD_FAILURE_RECOVERY=PASS\n"
              << "M8_OWNED_LOOPER_PREPARE_FAILURE_RECOVERY=PASS\n"
              << "M8_OWNED_LOOPER_CALLBACK_AUTO_REMOVE=PASS\n"
              << "M8_OWNED_LOOPER_AUTO_REMOVE_REDELIVERY_REJECTED=PASS\n"
              << "M8_OWNED_LOOPER_REMOVE_FD_ZERO_ACCEPTED=PASS\n"
              << "M8_OWNED_LOOPER_AUTO_REMOVE_REJOIN_IDEMPOTENT=PASS\n"
              << "M8_OWNED_LOOPER_EXACT_REFERENCE_BALANCE=PASS\n"
              << "M8_OWNED_LOOPER_REMOVE_FAILURE_RETAINS_REFERENCE=PASS\n"
              << "M8_OWNED_LOOPER_REPEATED_REMOVE_FAILURE_RECOVERY=PASS\n"
              << "M8_RENDERER_OWNERSHIP_FAKE_LOOPER_JOIN_BOUNDARY=PASS\n"
              << "M8_RENDERER_OWNERSHIP_FAILED_REMOVE_RETRY_JOIN=PASS\n"
              << "M8_RENDERER_UNIFIED_OWNER_FAILED_REMOVE_RETRY=PASS\n"
              << "M8_RENDERER_UNIFIED_OWNER_CONCURRENT_CALLBACK_DRAIN=PASS\n"
              << "M8_RENDERER_UNIFIED_OWNER_SELF_JOIN_REJECTED=PASS\n"
              << "M8_RENDERER_WORKER_MUTEX_OPERATIONS_REJECTED=PASS\n"
              << "M8_RENDERER_UNIFIED_OWNER_ADD_FAILURE_RECOVERY=PASS\n"
              << "M8_RENDERER_UNIFIED_OWNER_PREPARE_FAILURE_RECOVERY=PASS\n"
              << "M8_RENDERER_UNIFIED_OWNER_CALLBACK_AUTO_REMOVAL=PASS\n"
              << "M8_RENDERER_UNIFIED_OWNER_IDLE_FINAL_SHUTDOWN=PASS\n"
              << "M8_RENDERER_UNIFIED_OWNER_HEAP_USERDATA_POST_JOIN_FREE=PASS\n"
              << "M8_RENDERER_SESSION_COMPOSITE_FAILED_REMOVE_JOIN=PASS\n"
              << "M8_RENDERER_SESSION_COMPOSITE_AUTO_REMOVE_FRESH_GENERATION=PASS\n"
              << "M8_RENDERER_SESSION_COMPOSITE_CONCURRENT_SELF_JOIN_GUARD=PASS\n"
              << "M8_RENDERER_SESSION_COMPOSITE_FAILED_REMOVE_LATE_CALLBACK_REJECTED=PASS\n"
              << "M8_RENDERER_SESSION_COMPOSITE_ADD_FAILURE_IDLE_CLOSE=PASS\n"
              << "M8_OWNED_LOOPER_SELF_JOIN_REJECTED=PASS\n"
              << "M8_ANDROID_LOOPER_RUNTIME_VERIFIED=false\n"
              << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
}
