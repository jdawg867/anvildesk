#include "m8-renderer-command-boundary.h"
#include "m8-renderer-handle-session-retirement.h"
#include <atomic>
#include <cassert>
#include <chrono>
#include <cstdint>
#include <future>
#include <memory>
#include <stdexcept>
#include <thread>
using namespace anvildesk_m8;
using namespace std::chrono_literals;

struct Resource {
    explicit Resource(std::uint64_t g) : id(g) {}
    std::uint64_t generation() const { return id; }
    const std::uint64_t id;
    std::atomic<int> applied{0};
};
struct Backend {
    std::atomic<bool> fail{false};
    std::atomic<bool> throws{false};
    std::atomic<int> calls{0};
    bool apply(Resource& resource, RendererCommand command,
               std::uint32_t width, std::uint32_t height) {
        ++calls;
        if (throws) throw std::runtime_error("injected exception");
        if (fail) return false;
        if (command == RendererCommand::Viewport) assert(width > 0 && height > 0);
        ++resource.applied;
        return true;
    }
};
struct Retirement {
    std::atomic<int> calls{0};
    bool shutdownAndRetire() { ++calls; return true; }
};
int main() {
    RendererHandleRegistry<Resource> registry;
    Backend backend;
    RendererCommandBoundary<Resource, Backend> commands(registry, backend);
    const auto handle = registry.publish(std::make_shared<Resource>(15));
    using R = RendererCommandResult;
    assert(commands.dispatch(0, 15, RendererCommand::Refresh) == R::InvalidRequest);
    assert(commands.dispatch(handle, 0, RendererCommand::Refresh) == R::InvalidRequest);
    assert(commands.dispatch(handle, 15, RendererCommand::Viewport) == R::InvalidRequest);
    assert(commands.dispatch(handle, 15, RendererCommand::Viewport, 20000, 1) == R::InvalidRequest);
    assert(commands.dispatch(handle, 15, RendererCommand::Refresh, 1, 0) == R::InvalidRequest);
    assert(commands.dispatch(handle, 15, static_cast<RendererCommand>(999)) == R::InvalidRequest);
    assert(commands.dispatch(handle, 16, RendererCommand::Refresh) == R::StaleHandle);
    assert(backend.calls == 0);
    backend.fail = true;
    assert(commands.dispatch(handle, 15, RendererCommand::Refresh) == R::BackendRejected);
    backend.fail = false;
    backend.throws = true;
    assert(commands.dispatch(handle, 15, RendererCommand::Refresh) == R::BackendRejected);
    backend.throws = false;
    assert(commands.dispatch(handle, 15, RendererCommand::Viewport, 800, 600) == R::Applied);

    // A separately retained lease simulates JNI work already admitted.
    // Invalidation denies new commands and blocks retirement until release.
    auto admitted = registry.acquire(handle);
    assert(admitted);
    Retirement backendRetire;
    RendererHandleSessionRetirement<Resource, Retirement>
        retirement(registry, handle, backendRetire);
    std::promise<bool> done;
    auto completed = done.get_future();
    std::thread destroy([&] { done.set_value(retirement.shutdownAndRetire()); });
    const auto deadline = std::chrono::steady_clock::now() + 5s;
    while (registry.acquire(handle) && std::chrono::steady_clock::now() < deadline)
        std::this_thread::yield();
    assert(commands.dispatch(handle, 15, RendererCommand::Refresh) == R::StaleHandle);
    assert(completed.wait_for(20ms) == std::future_status::timeout);
    assert(backendRetire.calls == 0);
    auto replacement = registry.publish(std::make_shared<Resource>(16));
    assert(replacement != handle);
    assert(commands.dispatch(replacement, 15, RendererCommand::Refresh) == R::StaleHandle);
    assert(commands.dispatch(replacement, 16, RendererCommand::Refresh) == R::Applied);
    admitted.release();
    assert(completed.wait_for(5s) == std::future_status::ready);
    assert(completed.get());
    destroy.join();
    assert(backendRetire.calls == 1);
    assert(commands.dispatch(handle, 15, RendererCommand::Refresh) == R::StaleHandle);
    assert(registry.invalidateAndDrain(replacement));
}
