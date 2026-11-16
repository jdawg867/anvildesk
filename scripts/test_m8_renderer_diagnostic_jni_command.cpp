// Host JNI-header ABI exercise with synthetic JNIEnv table, no JVM loaded.
// The method is NOT registered; a real Android test is still required.
#include "m8-renderer-diagnostic-jni-command.h"
#include <atomic>
#include <cassert>
#include <cstdint>
#include <memory>
#include <type_traits>
using namespace anvildesk_m8;

struct TestResource {
    explicit TestResource(std::uint64_t generation) : generation_(generation) {}
    std::uint64_t generation() const noexcept { return generation_; }
    const std::uint64_t generation_;
    int applied = 0;
};
struct TestBackend {
    int calls = 0;
    bool apply(TestResource& resource, RendererCommand, std::uint32_t, std::uint32_t) {
        ++calls;
        ++resource.applied;
        return true;
    }
};
static std::atomic<bool> pending{false};
static jboolean JNICALL hasException(JNIEnv*) { return pending ? JNI_TRUE : JNI_FALSE; }

int main() {
    JNINativeInterface_ functions{};
    functions.ExceptionCheck = &hasException;
    JNIEnv_ fakeEnvironment{&functions};
    auto* env = &fakeEnvironment;
    RendererHandleRegistry<TestResource> registry;
    TestBackend backend;
    RendererCommandBoundary<TestResource, TestBackend> boundary(registry, backend);
    RendererJniCommandAdapter<TestResource, TestBackend> signedAdapter(boundary);
    RendererDiagnosticJniCommand<TestResource, TestBackend> jni(signedAdapter);
    const auto handle = registry.publish(std::make_shared<TestResource>(56));
    const auto call = [&](JNIEnv* given, jint version, jlong resource, jlong generation,
                          jint command, jint width, jint height) {
        return jni.dispatch(given, nullptr, version, resource, generation,
                            command, width, height);
    };
    assert(call(nullptr, 1, handle, 56, 2, 0, 0) == 2);
    pending = true;
    assert(call(env, 1, handle, 56, 2, 0, 0) == 3);
    assert(pending && backend.calls == 0); // No exception clear.
    pending = false;
    assert(call(env, 2, handle, 56, 2, 0, 0) == 2);
    assert(call(env, 1, -1, 56, 2, 0, 0) == 2);
    assert(call(env, 1, handle, 56, 1, -1, 100) == 2);
    assert(call(env, 1, handle, 57, 2, 0, 0) == 1);
    assert(call(env, 1, handle, 56, 1, 640, 480) == 0);
    assert(backend.calls == 1);
    assert(registry.invalidateAndDrain(handle));
    assert(call(env, 1, handle, 56, 2, 0, 0) == 1);
    assert(backend.calls == 1);
}
