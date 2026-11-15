#pragma once
// M8 isolated JNI-style admission boundary; no Android/JNI/Lorie execution.
// The Java Surface generation and native handle are checked under an actual
// registry lease. Java-only ownsResource() checks cannot substitute for this.
#include "m8-renderer-handle-registry.h"
#include <cstdint>
#include <utility>

namespace anvildesk_m8 {
template <class Resource>
class RendererGenerationOperation final {
public:
    using Registry = RendererHandleRegistry<Resource>;
    using Handle = typename Registry::Handle;
    explicit RendererGenerationOperation(Registry& registry) : registry_(registry) {}

    // Resource must expose a stable, immutable generation() value.
    // Never retain a raw Resource* beyond this call. Lambdas must not stash it.
    template <class Operation>
    bool withCurrent(Handle handle, std::uint64_t generation, Operation&& operation) {
        if (handle == 0 || generation == 0) return false;
        auto lease = registry_.acquire(handle);
        if (!lease || lease.get()->generation() != generation) return false;
        std::forward<Operation>(operation)(*lease.get());
        return true;
    }

private:
    Registry& registry_;
};
} // namespace anvildesk_m8
