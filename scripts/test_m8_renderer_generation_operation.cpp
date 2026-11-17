// Host-only JNI/Surface-generation admission gate test.
#include "m8-renderer-generation-operation.h"
#include "m8-renderer-handle-session-retirement.h"
#include <atomic>
#include <cassert>
#include <chrono>
#include <future>
#include <memory>
#include <thread>
using namespace std::chrono_literals;
using namespace anvildesk_m8;

struct SurfaceResource {
    explicit SurfaceResource(std::uint64_t g) : token(g) {}
    std::uint64_t generation() const noexcept { return token; }
    const std::uint64_t token;
    std::atomic<int> calls{0};
};
struct Retire {
    std::atomic<int> calls{0};
    bool shutdownAndRetire() { ++calls; return true; }
};
int main() {
    RendererHandleRegistry<SurfaceResource> registry;
    RendererGenerationOperation<SurfaceResource> ops(registry);
    const auto old = registry.publish(std::make_shared<SurfaceResource>(41));
    std::atomic<int> calls{0};
    assert(!ops.withCurrent(0, 41, [&](SurfaceResource&) { ++calls; }));
    assert(!ops.withCurrent(old, 0, [&](SurfaceResource&) { ++calls; }));
    assert(!ops.withCurrent(old, 42, [&](SurfaceResource&) { ++calls; }));
    assert(calls == 0);
    assert(ops.withCurrent(old, 41, [&](SurfaceResource& resource) {
        assert(resource.generation() == 41);
        ++resource.calls;
        ++calls;
    }));
    assert(calls == 1);
    std::promise<void> entered;
    auto enteredFuture = entered.get_future();
    std::promise<void> release;
    auto releaseFuture = release.get_future();
    std::thread active([&] {
        assert(ops.withCurrent(old, 41, [&](SurfaceResource& resource) {
            entered.set_value();
            releaseFuture.wait();
            ++resource.calls;
        }));
    });
    assert(enteredFuture.wait_for(5s) == std::future_status::ready);
    Retire backend;
    RendererHandleSessionRetirement<SurfaceResource, Retire>
        coordinator(registry, old, backend);
    std::promise<bool> done;
    auto finished = done.get_future();
    std::thread shutdown([&] { done.set_value(coordinator.shutdownAndRetire()); });
    auto deadline = std::chrono::steady_clock::now() + 5s;
    // No stale Java Surface generation can reenter once invalidation begins.
    while (registry.acquire(old) && std::chrono::steady_clock::now() < deadline)
        std::this_thread::yield();
    assert(!ops.withCurrent(old, 41, [&](SurfaceResource&) { ++calls; }));
    assert(finished.wait_for(20ms) == std::future_status::timeout);
    assert(backend.calls == 0);
    const auto replacement = registry.publish(std::make_shared<SurfaceResource>(42));
    assert(replacement != old);
    assert(!ops.withCurrent(replacement, 41, [&](SurfaceResource&) { ++calls; }));
    assert(ops.withCurrent(replacement, 42, [&](SurfaceResource& resource) {
        ++resource.calls;
    }));
    release.set_value();
    active.join();
    assert(finished.wait_for(5s) == std::future_status::ready);
    assert(finished.get());
    shutdown.join();
    assert(backend.calls == 1);
    assert(!ops.withCurrent(old, 41, [&](SurfaceResource&) { ++calls; }));
    assert(calls == 1);
    assert(registry.invalidateAndDrain(replacement));
    assert(!ops.withCurrent(replacement, 42, [&](SurfaceResource&) { ++calls; }));
}
