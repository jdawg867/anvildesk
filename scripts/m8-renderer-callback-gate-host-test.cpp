// Host-only proof of a two-phase M8 callback-admission gate.
// This DOES NOT make a raw Android ALooper userdata pointer safe: a real
// implementation must own the callback context until the looper thread has
// been stopped and joined, including callbacks queued before removeFd.
// No JNI, renderer, APK packaging or production native loading.
#include <atomic>
#include <condition_variable>
#include <future>
#include <iostream>
#include <mutex>
#include <thread>

class CallbackGate {
    std::mutex mu_;
    std::condition_variable cv_;
    bool stopping_ = false;
    unsigned active_ = 0;

public:
    class Lease {
        CallbackGate* owner_ = nullptr;
    public:
        explicit Lease(CallbackGate* owner = nullptr) : owner_(owner) {}
        Lease(const Lease&) = delete;
        Lease& operator=(const Lease&) = delete;
        Lease(Lease&& other) noexcept : owner_(other.owner_) { other.owner_ = nullptr; }
        ~Lease() {
            if (!owner_) return;
            std::lock_guard<std::mutex> lock(owner_->mu_);
            --owner_->active_;
            owner_->cv_.notify_all();
        }
        explicit operator bool() const { return owner_ != nullptr; }
    };

    Lease tryEnter() {
        std::lock_guard<std::mutex> lock(mu_);
        if (stopping_) return {};
        ++active_;
        return Lease(this);
    }

    void stopAdmissionAndDrain() {
        std::unique_lock<std::mutex> lock(mu_);
        stopping_ = true;
        cv_.wait(lock, [this] { return active_ == 0; });
    }
};

int main() {
    CallbackGate gate;
    std::promise<void> entered, release, stopped;
    auto enteredFuture = entered.get_future();
    auto releaseSignal = release.get_future().share();
    auto stoppedFuture = stopped.get_future();
    std::atomic<bool> callbackFinished{false};
    std::thread callback([&] {
        auto lease = gate.tryEnter();
        if (!lease) return;
        entered.set_value();
        releaseSignal.wait();
        callbackFinished.store(true);
    });
    enteredFuture.wait();
    std::thread shutdown([&] {
        gate.stopAdmissionAndDrain();
        stopped.set_value();
    });
    // Shutdown must not be allowed to return until the callback exits.
    // No sleep / scheduler assumptions: use a second gate arrival only
    // after the shutdown completion fence below.
    if (stoppedFuture.wait_for(std::chrono::milliseconds(0)) ==
        std::future_status::ready) {
        release.set_value();
        callback.join();
        shutdown.join();
        return 1;
    }
    release.set_value();
    callback.join();
    shutdown.join();
    if (!callbackFinished.load() || gate.tryEnter()) return 2;
    if (stoppedFuture.wait_for(std::chrono::seconds(0)) !=
        std::future_status::ready) return 3;
    std::cout << "M8_CALLBACK_GATE_HOST_DRAIN=PASS\n"
                 "M8_CALLBACK_GATE_POST_STOP_ENTRY_REJECTED=PASS\n"
                 "M8_ANDROID_LOOPER_CALLBACK_QUIESCENCE_VERIFIED=false\n"
                 "M8_RENDERER_JNI_LIFECYCLE_VERIFIED=false\n"
                 "M8_NATIVE_LOAD_AUTHORIZED=false\n";
}
