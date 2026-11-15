// M8 host-only Surface/JNI-style handle race regression. No Android or Lorie.
#include "m8-renderer-handle-registry.h"
#include "m8-renderer-handle-session-retirement.h"
#include "m8-renderer-session-retirement.h"
#include "m8-renderer-jni-global-ref-retirement.h"
#include <atomic>
#include <cassert>
#include <chrono>
#include <future>
#include <memory>
#include <thread>
using namespace anvildesk_m8;
using namespace std::chrono_literals;

struct SurfaceState {
    std::atomic<int> updates{0};
    explicit SurfaceState(int id) : generation(id) {}
    const int generation;
};
struct Worker {
    std::atomic<int> joins{0};
    bool shutdownAndJoin() { ++joins; return true; }
};
struct JavaRefs {
    using Ref = int*;
    std::atomic<int> deletes{0};
    bool deleteGlobalRef(Ref p) {
        assert(p);
        ++deletes;
        return true;
    }
};
struct RendererCleanup {
    std::atomic<int> calls{0};
    bool retire() { ++calls; return true; }
};

int main() {
    RendererHandleRegistry<SurfaceState> registry;
    auto resource = std::make_shared<SurfaceState>(1);
    const auto old = registry.publish(resource);
    std::weak_ptr<SurfaceState> oldWeak = resource;
    resource.reset();
    auto inFlight = registry.acquire(old);
    assert(inFlight);
    auto ignored = registry.acquire(old);
    assert(ignored);
    ignored.release();

    Worker worker;
    JavaRefs backend;
    int globalRef = 3;
    DeferredGlobalRefRetirement<JavaRefs> refs(backend, &globalRef);
    RendererCleanup cleanup;
    RendererSessionRetirement<Worker, decltype(refs), RendererCleanup>
        retirement(worker, refs, cleanup);
    RendererHandleSessionRetirement<SurfaceState, decltype(retirement)>
        coordinator(registry, old, retirement);

    std::promise<bool> donePromise;
    auto done = donePromise.get_future();
    std::thread destroy([&] { donePromise.set_value(coordinator.shutdownAndRetire()); });
    auto deadline = std::chrono::steady_clock::now() + 5s;
    while (registry.acquire(old) && std::chrono::steady_clock::now() < deadline)
        std::this_thread::yield();
    assert(!registry.acquire(old)); // terminal invalidation rejects stale Surface work
    assert(done.wait_for(20ms) == std::future_status::timeout);
    assert(worker.joins == 0 && backend.deletes == 0 && cleanup.calls == 0);

    // New Surface generation gets a different token. Stale events may not
    // acquire it using the retired generation, even if Surface/FD is reused.
    const auto next = registry.publish(std::make_shared<SurfaceState>(2));
    assert(next != old);
    auto fresh = registry.acquire(next);
    assert(fresh && fresh.get()->generation == 2);
    assert(!registry.acquire(old));
    fresh.get()->updates++;
    assert(fresh.get()->updates == 1);

    inFlight.get()->updates++; // already admitted call can finish
    assert(inFlight.get()->updates == 1);
    inFlight.release();
    assert(done.wait_for(5s) == std::future_status::ready);
    assert(done.get());
    destroy.join();
    assert(worker.joins == 1 && backend.deletes == 1 && cleanup.calls == 1);
    assert(oldWeak.expired() && !registry.acquire(old));
    assert(coordinator.shutdownAndRetire()); // idempotent
    assert(worker.joins == 1 && backend.deletes == 1 && cleanup.calls == 1);
    fresh.release();
    assert(registry.invalidateAndDrain(next));
    assert(!registry.acquire(next));
}
