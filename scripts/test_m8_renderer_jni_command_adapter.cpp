#include "m8-renderer-jni-command-adapter.h"
#include <atomic>
#include <cassert>
#include <cstdint>
#include <memory>
#include <stdexcept>
using namespace anvildesk_m8;

struct Resource {
    explicit Resource(std::uint64_t g) : token(g) {}
    std::uint64_t generation() const { return token; }
    const std::uint64_t token;
    std::atomic<int> successful{0};
};
struct Backend {
    int calls = 0;
    bool reject = false;
    bool throwing = false;
    bool apply(Resource& resource, RendererCommand, std::uint32_t,
               std::uint32_t) {
        ++calls;
        if (throwing) throw std::runtime_error("test backend throw");
        if (reject) return false;
        ++resource.successful;
        return true;
    }
};

int main() {
    RendererHandleRegistry<Resource> registry;
    Backend backend;
    RendererCommandBoundary<Resource, Backend> boundary(registry, backend);
    RendererJniCommandAdapter<Resource, Backend> adapter(boundary);
    const auto h = registry.publish(std::make_shared<Resource>(33));
    const auto dispatch = [&](std::int32_t version, std::int64_t handle,
                              std::int64_t generation, std::int32_t opcode,
                              std::int32_t width, std::int32_t height) {
        return adapter.dispatch(version, handle, generation, opcode, width, height);
    };
    assert(dispatch(2, h, 33, 2, 0, 0) == 2);
    assert(dispatch(1, -1, 33, 2, 0, 0) == 2);
    assert(dispatch(1, 0, 33, 2, 0, 0) == 2);
    assert(dispatch(1, h, -1, 2, 0, 0) == 2);
    assert(dispatch(1, h, 33, 0, 0, 0) == 2);
    assert(dispatch(1, h, 33, 3, 0, 0) == 2);
    assert(dispatch(1, h, 33, 1, -1, 10) == 2);
    assert(dispatch(1, h, 33, 1, 1, -1) == 2);
    assert(dispatch(1, h, 33, 1, 0, 10) == 2);
    assert(dispatch(1, h, 33, 1, 16385, 10) == 2);
    assert(dispatch(1, h, 33, 2, 1, 0) == 2);
    assert(backend.calls == 0);
    assert(dispatch(1, h, 34, 2, 0, 0) == 1);
    backend.reject = true;
    assert(dispatch(1, h, 33, 2, 0, 0) == 3);
    backend.reject = false;
    backend.throwing = true;
    assert(dispatch(1, h, 33, 2, 0, 0) == 3);
    backend.throwing = false;
    assert(dispatch(1, h, 33, 1, 1280, 720) == 0);
    assert(dispatch(1, h, 33, 2, 0, 0) == 0);
    assert(backend.calls == 4);
    assert(registry.invalidateAndDrain(h));
    assert(dispatch(1, h, 33, 2, 0, 0) == 1);
    const auto replacement = registry.publish(std::make_shared<Resource>(34));
    assert(replacement != h);
    assert(dispatch(1, replacement, 33, 2, 0, 0) == 1);
    assert(dispatch(1, replacement, 34, 2, 0, 0) == 0);
    assert(registry.invalidateAndDrain(replacement));
}
