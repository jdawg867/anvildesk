#pragma once
// Host-only M8 JNI-shaped command boundary. No JNI exports, Lorie or APK.
// Clients pass numeric IDs only; never return or expose native Resource*.
#include "m8-renderer-handle-registry.h"
#include <cstdint>

namespace anvildesk_m8 {
enum class RendererCommand : std::uint32_t { Viewport = 1, Refresh = 2 };
enum class RendererCommandResult : std::uint32_t {
    Applied = 0, StaleHandle = 1, InvalidRequest = 2, BackendRejected = 3
};

// Backend is a trusted native-side component; not an arbitrary Java callback.
// Resource::generation() is immutable for the lifetime of the registration.
// The returned result contains no resource ownership or pointer.
template<class Resource, class Backend>
class RendererCommandBoundary final {
public:
    explicit RendererCommandBoundary(RendererHandleRegistry<Resource>& registry,
                                     Backend& backend)
        : registry_(registry), backend_(backend) {}
    RendererCommandBoundary(const RendererCommandBoundary&) = delete;
    RendererCommandBoundary& operator=(const RendererCommandBoundary&) = delete;

    RendererCommandResult dispatch(
        std::uint64_t handle, std::uint64_t generation,
        RendererCommand command, std::uint32_t width = 0,
        std::uint32_t height = 0) {
        if (handle == 0 || generation == 0) return RendererCommandResult::InvalidRequest;
        if (command != RendererCommand::Viewport && command != RendererCommand::Refresh)
            return RendererCommandResult::InvalidRequest;
        if (command == RendererCommand::Viewport &&
            (width == 0 || height == 0 || width > 16384 || height > 16384))
            return RendererCommandResult::InvalidRequest;
        if (command == RendererCommand::Refresh && (width != 0 || height != 0))
            return RendererCommandResult::InvalidRequest;
        auto lease = registry_.acquire(handle);
        if (!lease || lease.get()->generation() != generation)
            return RendererCommandResult::StaleHandle;
        // Backend must not retain Resource& or call invalidation recursively.
        // Keep any exception inside native ownership boundary; JNI must
        // separately translate errors without leaking C++ exceptions.
        try {
            if (!backend_.apply(*lease.get(), command, width, height))
                return RendererCommandResult::BackendRejected;
        } catch (...) {
            return RendererCommandResult::BackendRejected;
        }
        return RendererCommandResult::Applied;
    }
private:
    RendererHandleRegistry<Resource>& registry_;
    Backend& backend_;
};
} // namespace anvildesk_m8
