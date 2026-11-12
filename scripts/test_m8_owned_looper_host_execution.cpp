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
                ptr->callbackAutoRemoved.set_value();
            }
        }
        return 1;
    }
    return 0;
}
#include "m8-android-owned-looper-thread.cpp"
#include "m8-renderer-owned-looper-adapter.h"
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
        platform.pending = false;
        platform.wake = false;
    }
    std::atomic<int> calls{0};
    anvildesk_m8::RendererOwnedLooperAdapter adapter;
    require(!adapter.userdataReleaseEligible(), "new adapter freed absent userdata");
    require(!adapter.registerCallback(0, 100, ALOOPER_EVENT_INPUT,
                                      adapterCallback, &calls),
            "adapter admitted invalid generation");
    require(adapter.registerCallback(100, 100, ALOOPER_EVENT_INPUT,
                                     adapterCallback, &calls),
            "adapter refused valid callback");
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
int main() {
    testWakeJoinAndRemoveRetry();
    testShutdownWithoutRegisteredCallback();
    testPrepareFailureAndShutdown();
    testCallbackReturnZeroDeregistration();
    testUnifiedOwnershipAdapterFailureAndRetry();
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
              << "M8_OWNED_LOOPER_SELF_JOIN_REJECTED=PASS\n"
              << "M8_ANDROID_LOOPER_RUNTIME_VERIFIED=false\n"
              << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
}
