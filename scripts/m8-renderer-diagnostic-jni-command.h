#pragma once
// M8 diagnostic-only JNI ABI facade; no JNI_OnLoad, registration, exports or Lorie.
// This translates JNI primitive types to the independently lease-guarded host
// adapter. It must remain unregistered until dedicated Android acceptance.
#include "m8-renderer-jni-command-adapter.h"
#include <jni.h>
#include <cstdint>
#include <type_traits>

namespace anvildesk_m8 {
template<class Resource, class Backend>
class RendererDiagnosticJniCommand final {
public:
    using Adapter = RendererJniCommandAdapter<Resource, Backend>;
    explicit RendererDiagnosticJniCommand(Adapter& adapter) : adapter_(adapter) {}

    // JNI descriptor: (IJJIII)I
    // Static Java native method convention: JNIEnv*, jclass, then six args.
    jint dispatch(JNIEnv* env, jclass, jint version, jlong handle,
                  jlong generation, jint command, jint width, jint height) noexcept {
        if (!env) return static_cast<jint>(RendererCommandResult::InvalidRequest);
        // Never clear or replace a caller's pending exception. JNI must not
        // access the backend while its environment is in exceptional state.
        if (env->ExceptionCheck())
            return static_cast<jint>(RendererCommandResult::BackendRejected);
        try {
            return static_cast<jint>(adapter_.dispatch(
                static_cast<std::int32_t>(version),
                static_cast<std::int64_t>(handle),
                static_cast<std::int64_t>(generation),
                static_cast<std::int32_t>(command),
                static_cast<std::int32_t>(width),
                static_cast<std::int32_t>(height)));
        } catch (...) {
            return static_cast<jint>(RendererCommandResult::BackendRejected);
        }
    }
private:
    Adapter& adapter_;
};
static_assert(sizeof(jlong) == sizeof(std::int64_t), "JNI jlong width changed");
static_assert(sizeof(jint) == sizeof(std::int32_t), "JNI jint width changed");
} // namespace anvildesk_m8
