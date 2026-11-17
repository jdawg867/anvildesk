// Host-only model for ordered callback join, JNI ref cleanup, and renderer
// cleanup. Failure injections prove that no resource retires before join.
#include <atomic>
#include <iostream>
#include <stdexcept>
#include <thread>
#include "m8-renderer-jni-global-ref-retirement.h"
#include "m8-renderer-session-retirement.h"

static void require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}
struct Worker {
    std::atomic<int> attempts{0};
    std::atomic<bool> allowJoin{false};
    bool shutdownAndJoin() {
        ++attempts;
        return allowJoin.load();
    }
};
struct JniRefs {
    using Ref = void*;
    std::atomic<int> deletes{0};
    std::atomic<bool> allowDelete{false};
    bool deleteGlobalRef(Ref reference) {
        if (!reference || !allowDelete) return false;
        ++deletes;
        return true;
    }
};
struct Renderer {
    std::atomic<int> attempts{0};
    std::atomic<int> releases{0};
    std::atomic<bool> allowRelease{false};
    bool retire() {
        ++attempts;
        if (!allowRelease) return false;
        ++releases;
        return true;
    }
};
int main() {
    Worker worker;
    JniRefs backend;
    Renderer renderer;
    int token = 5;
    anvildesk_m8::DeferredGlobalRefRetirement<JniRefs> refs(backend, &token);
    anvildesk_m8::RendererSessionRetirement<
        Worker, decltype(refs), Renderer> session(worker, refs, renderer);
    require(!session.shutdownAndRetire(), "failed join unexpectedly retired");
    require(!session.workerJoined() && !session.retired() && refs.holdsReference() &&
            backend.deletes == 0 && renderer.attempts == 0,
            "join-failure path released owned resources");
    worker.allowJoin = true;
    require(!session.shutdownAndRetire() && session.workerJoined() &&
            !session.retired() && refs.holdsReference() &&
            renderer.attempts == 0 && backend.deletes == 0,
            "JNI cleanup refusal released resources");
    backend.allowDelete = true;
    require(!session.shutdownAndRetire() && refs.retired() &&
            backend.deletes == 1 && renderer.attempts == 1 &&
            renderer.releases == 0 && !session.retired(),
            "renderer cleanup refusal incorrectly marked retirement complete");
    renderer.allowRelease = true;
    constexpr int threads = 5;
    std::thread workers[threads];
    std::atomic<int> successful{0};
    for (auto& t : workers) {
        t = std::thread([&] { if (session.shutdownAndRetire()) ++successful; });
    }
    for (auto& t : workers) t.join();
    require(successful == threads && session.retired() &&
            worker.attempts == 2 && backend.deletes == 1 &&
            renderer.releases == 1 && renderer.attempts == 2 &&
            !refs.holdsReference(),
            "concurrent retirement was not idempotent");
    require(session.shutdownAndRetire() && renderer.releases == 1 &&
            backend.deletes == 1, "repeated terminal shutdown changed resources");
    std::cout
        << "M8_SESSION_RETIREMENT_JOIN_FAILURE_RETAINS_RESOURCES=PASS\n"
        << "M8_SESSION_RETIREMENT_JNI_REF_FAILURE_RETAINS_RENDERER=PASS\n"
        << "M8_SESSION_RETIREMENT_RENDERER_RETRY=PASS\n"
        << "M8_SESSION_RETIREMENT_CONCURRENT_EXACTLY_ONCE=PASS\n"
        << "M8_LORIE_SESSION_RETIREMENT_INTEGRATED=false\n"
        << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
}
