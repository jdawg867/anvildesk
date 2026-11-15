// M8 host-only composition of the REAL session retirement templates with
// the renderer handle registry. Fakes are NOT Android worker/JNI evidence.
#include "m8-renderer-handle-session-retirement.h"
#include "m8-renderer-session-retirement.h"
#include "m8-renderer-jni-global-ref-retirement.h"
#include <atomic>
#include <cassert>
#include <chrono>
#include <future>
#include <memory>
#include <thread>
using namespace std::chrono_literals;
using namespace anvildesk_m8;

struct FakeWorker {
    std::atomic<int> attempts{0};
    std::atomic<bool> allowJoin{false};
    bool shutdownAndJoin() {
        ++attempts;
        return allowJoin.load();
    }
};
struct FakeJni {
    using Ref = int*;
    std::atomic<int> attempts{0};
    std::atomic<bool> allowDelete{false};
    bool deleteGlobalRef(Ref ref) {
        assert(ref);
        ++attempts;
        return allowDelete.load();
    }
};
struct FakeRendererRetirer {
    std::atomic<int> attempts{0};
    std::atomic<bool> allowRetire{false};
    bool retire() {
        ++attempts;
        return allowRetire.load();
    }
};

int main() {
    RendererHandleRegistry<int> registry;
    auto resource = std::make_shared<int>(19);
    std::weak_ptr<int> resourceWeak = resource;
    const auto handle = registry.publish(resource);
    resource.reset();
    auto lease = registry.acquire(handle);
    assert(lease && *lease.get() == 19);

    FakeWorker worker;
    FakeJni jni;
    int ref = 7;
    DeferredGlobalRefRetirement<FakeJni> refs(jni, &ref);
    FakeRendererRetirer renderer;
    RendererSessionRetirement<FakeWorker,
        DeferredGlobalRefRetirement<FakeJni>, FakeRendererRetirer>
        retirement(worker, refs, renderer);
    RendererHandleSessionRetirement<int, decltype(retirement)>
        coordinator(registry, handle, retirement);

    std::promise<bool> completion;
    auto done = completion.get_future();
    std::thread closer([&] { completion.set_value(coordinator.shutdownAndRetire()); });
    const auto deadline = std::chrono::steady_clock::now() + 5s;
    while (registry.acquire(handle) && std::chrono::steady_clock::now() < deadline)
        std::this_thread::yield();
    assert(!registry.acquire(handle));
    assert(done.wait_for(20ms) == std::future_status::timeout);
    assert(worker.attempts == 0 && jni.attempts == 0 && renderer.attempts == 0);
    lease.release();
    assert(done.wait_for(5s) == std::future_status::ready);
    assert(!done.get());
    closer.join();
    assert(coordinator.revoked() && !coordinator.retired());
    assert(worker.attempts == 1);
    assert(jni.attempts == 0 && renderer.attempts == 0);
    assert(!resourceWeak.expired());

    // Worker shutdown now succeeds, JNI cleanup refuses; retain all state.
    worker.allowJoin = true;
    assert(!coordinator.shutdownAndRetire());
    assert(retirement.workerJoined());
    assert(jni.attempts == 1 && renderer.attempts == 0);
    assert(refs.holdsReference());
    assert(!resourceWeak.expired());

    // JNI cleanup succeeds; renderer retirement fails. Do not repeat JNI delete.
    jni.allowDelete = true;
    assert(!coordinator.shutdownAndRetire());
    assert(jni.attempts == 2 && renderer.attempts == 1);
    assert(!refs.holdsReference() && refs.retired());
    assert(!resourceWeak.expired());

    // Terminal success (including two concurrent callers) is exactly once.
    renderer.allowRetire = true;
    std::thread retryA([&] { assert(coordinator.shutdownAndRetire()); });
    std::thread retryB([&] { assert(coordinator.shutdownAndRetire()); });
    retryA.join();
    retryB.join();
    assert(coordinator.retired() && retirement.retired());
    assert(worker.attempts == 2 && jni.attempts == 2);
    assert(renderer.attempts == 2);
    assert(resourceWeak.expired());
    assert(!registry.acquire(handle));
    assert(!registry.invalidateAndDrain(handle));
    assert(coordinator.shutdownAndRetire());
    assert(renderer.attempts == 2);
}
