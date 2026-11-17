#include "m8-renderer-handle-registry.h"
#include <atomic>
#include <cassert>
#include <chrono>
#include <future>
#include <memory>
#include <thread>
using namespace std::chrono_literals;
using anvildesk_m8::RendererHandleRegistry;

int main() {
    RendererHandleRegistry<int> registry;
    assert(!registry.acquire(0));
    assert(registry.publish({}) == 0);
    auto value = std::make_shared<int>(73);
    auto old = registry.publish(value);
    assert(old != 0);
    auto lease = registry.acquire(old);
    assert(lease && *lease.get() == 73);
    auto moved = std::move(lease);
    assert(!lease && moved);
    std::promise<std::shared_ptr<int>> finished;
    auto future = finished.get_future();
    std::thread retire([&] {
        finished.set_value(registry.invalidateAndDrain(old));
    });
    // Observe invalidation through acquisition rather than sleeping;
    // invalidation must reject all new leases before retirement completes.
    auto deadline = std::chrono::steady_clock::now() + 5s;
    while (registry.acquire(old) && std::chrono::steady_clock::now() < deadline)
        std::this_thread::yield();
    assert(!registry.acquire(old));
    assert(future.wait_for(20ms) == std::future_status::timeout);
    moved.release();
    moved.release();
    assert(future.wait_for(5s) == std::future_status::ready);
    assert(future.get() == value);
    retire.join();
    assert(!registry.invalidateAndDrain(old));
    assert(!registry.acquire(old));
    auto newer = registry.publish(std::make_shared<int>(92));
    assert(newer != old);
    auto fresh = registry.acquire(newer);
    assert(fresh && *fresh.get() == 92);
    fresh.release();
    assert(registry.invalidateAndDrain(newer));
    assert(!registry.acquire(newer));
    // Under racing acquire/retire calls, no new handle can reuse an old token.
    auto third = registry.publish(std::make_shared<int>(1));
    std::atomic<bool> running{true};
    std::thread reader([&] {
        while (running) {
            auto current = registry.acquire(third);
            if (current) assert(*current.get() == 1);
        }
    });
    assert(registry.invalidateAndDrain(third));
    running = false;
    reader.join();
    assert(!registry.acquire(third));
}
