#include "m8-renderer-handle-session-retirement.h"
#include <atomic>
#include <cassert>
#include <chrono>
#include <future>
#include <memory>
#include <thread>
using namespace std::chrono_literals;
using anvildesk_m8::RendererHandleRegistry;
using anvildesk_m8::RendererHandleSessionRetirement;

struct SessionRetirement {
    std::atomic<int> calls{0};
    std::atomic<bool> permit{false};
    bool shutdownAndRetire() {
        ++calls;
        return permit.load();
    }
};
int main() {
    RendererHandleRegistry<int> registry;
    SessionRetirement backend;
    auto resource = std::make_shared<int>(41);
    const auto handle = registry.publish(resource);
    std::weak_ptr<int> weak = resource;
    resource.reset();
    auto held = registry.acquire(handle);
    assert(held && *held.get() == 41);
    RendererHandleSessionRetirement<int,SessionRetirement> coordinator(
        registry, handle, backend);
    std::promise<bool> finished;
    auto result = finished.get_future();
    std::thread closer([&] {
        finished.set_value(coordinator.shutdownAndRetire());
    });
    auto deadline = std::chrono::steady_clock::now() + 5s;
    while (registry.acquire(handle) && std::chrono::steady_clock::now() < deadline)
        std::this_thread::yield();
    assert(!registry.acquire(handle));
    assert(result.wait_for(20ms) == std::future_status::timeout);
    assert(backend.calls == 0); // no worker shutdown while JNI lease held
    held.release();
    assert(result.wait_for(5s) == std::future_status::ready);
    assert(!result.get()); // injected failure, resource kept for retry
    closer.join();
    assert(coordinator.revoked() && !coordinator.retired());
    assert(!registry.acquire(handle));
    assert(!weak.expired());
    assert(backend.calls == 1);
    backend.permit = true;
    // Concurrent retries serialized, terminal retirement is exactly once.
    std::thread retry1([&] { assert(coordinator.shutdownAndRetire()); });
    std::thread retry2([&] { assert(coordinator.shutdownAndRetire()); });
    retry1.join();
    retry2.join();
    assert(coordinator.retired());
    assert(backend.calls == 2);
    assert(weak.expired());
    assert(coordinator.shutdownAndRetire());
    assert(backend.calls == 2);
    assert(!registry.acquire(handle));
    // A new generation cannot reuse the invalidated token.
    const auto replacement = registry.publish(std::make_shared<int>(82));
    assert(replacement != handle);
    auto next = registry.acquire(replacement);
    assert(next && *next.get() == 82);
    next.release();
    assert(registry.invalidateAndDrain(replacement));
    assert(!registry.acquire(replacement));
}
