#pragma once
// M8 JNI-shaped input adapter, host-only. No JNI registration or native exports.
// Java long/int representation is modeled explicitly to prevent negative values
// from wrapping into positive unsigned native resource identifiers.
// The actual JNI entry point will require independent registration/lifecycle review.
#include "m8-renderer-command-boundary.h"
#include <cstdint>

namespace anvildesk_m8 {
template<class Resource, class Backend>
class RendererJniCommandAdapter final {
public:
    explicit RendererJniCommandAdapter(
        RendererCommandBoundary<Resource, Backend>& boundary)
        : boundary_(boundary) {}

    // Mirrors Java: (int version, long handle, long generation,
    //                int command, int width, int height) -> int status.
    // Never accepts, returns, or reconstructs a raw renderer pointer.
    std::int32_t dispatch(std::int32_t version, std::int64_t handle,
                          std::int64_t generation, std::int32_t command,
                          std::int32_t width, std::int32_t height) {
        if (version != 1 || handle <= 0 || generation <= 0 ||
            width < 0 || height < 0 ||
            (command != 1 && command != 2)) {
            return status(RendererCommandResult::InvalidRequest);
        }
        // Signed positive values are safe to convert to uint64_t.
        const auto result = boundary_.dispatch(
            static_cast<std::uint64_t>(handle),
            static_cast<std::uint64_t>(generation),
            static_cast<RendererCommand>(command),
            static_cast<std::uint32_t>(width),
            static_cast<std::uint32_t>(height));
        return status(result);
    }

private:
    static constexpr std::int32_t status(RendererCommandResult result) noexcept {
        return static_cast<std::int32_t>(result);
    }
    RendererCommandBoundary<Resource, Backend>& boundary_;
};
} // namespace anvildesk_m8
