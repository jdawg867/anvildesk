// Uses JNI-defined function signatures with a synthetic JNIEnv function table.
// Does not load a JVM or interact with Android ART.
#include "m8-renderer-diagnostic-jni-registration-backend.h"
#include "m8-renderer-diagnostic-registration.h"
#include <cassert>
#include <cstdint>
using namespace anvildesk_m8;

namespace {
struct State {
    int registrations = 0;
    int removals = 0;
    bool installed = false;
    bool registerFails = false;
    bool unregisterFails = false;
    bool pendingException = false;
} state;
jint JNICALL reg(JNIEnv*, jclass clazz, const JNINativeMethod* methods, jint count) {
    assert(clazz && methods && count == 1);
    ++state.registrations;
    state.installed = true; // Inject partial registration before failure.
    return state.registerFails ? JNI_ERR : JNI_OK;
}
jint JNICALL unreg(JNIEnv*, jclass clazz) {
    assert(clazz);
    ++state.removals;
    if (state.unregisterFails) return JNI_ERR;
    state.installed = false;
    return JNI_OK;
}
jboolean JNICALL exception(JNIEnv*) {
    return state.pendingException ? JNI_TRUE : JNI_FALSE;
}
void dummy() {}
void reset() { state = State{}; }
}

int main() {
    JNINativeInterface_ api{};
    api.RegisterNatives = reg;
    api.UnregisterNatives = unreg;
    api.ExceptionCheck = exception;
    JNIEnv_ fake{&api};
    auto* env = &fake;
    // Fake non-null opaque jclass; it is never dereferenced by this mock.
    auto clazz = reinterpret_cast<jclass>(static_cast<std::uintptr_t>(1));
    JNINativeMethod methods[] = {
        {const_cast<char*>("dispatch"), const_cast<char*>("(IJJIII)I"),
         reinterpret_cast<void*>(&dummy)}
    };

    RendererDiagnosticJniRegistrationBackend backend(env, clazz, methods, 1);
    RendererDiagnosticRegistration<RendererDiagnosticJniRegistrationBackend> tx(backend);
    assert(tx.registerAndVerify());
    assert(state.registrations == 1 && state.installed && tx.registered());
    assert(tx.registerAndVerify() && state.registrations == 1);
    assert(!tx.unregisterAfterDrain(false));
    assert(state.installed && state.removals == 0);
    assert(tx.unregisterAfterDrain(true));
    assert(!state.installed && state.removals == 1);
    assert(tx.unregisterAfterDrain(true) && state.removals == 1);

    reset();
    state.registerFails = true;
    RendererDiagnosticJniRegistrationBackend failedBackend(env, clazz, methods, 1);
    RendererDiagnosticRegistration<RendererDiagnosticJniRegistrationBackend> failed(failedBackend);
    assert(!failed.registerAndVerify());
    assert(state.registrations == 1 && state.removals == 1 && !state.installed);
    assert(!failed.poisoned());

    reset();
    state.registerFails = true;
    state.unregisterFails = true;
    RendererDiagnosticJniRegistrationBackend unsafeBackend(env, clazz, methods, 1);
    RendererDiagnosticRegistration<RendererDiagnosticJniRegistrationBackend> unsafe(unsafeBackend);
    assert(!unsafe.registerAndVerify());
    assert(unsafe.poisoned() && state.installed);
    assert(!unsafe.registerAndVerify() && state.registrations == 1);

    reset();
    state.pendingException = true;
    RendererDiagnosticJniRegistrationBackend pendingBackend(env, clazz, methods, 1);
    RendererDiagnosticRegistration<RendererDiagnosticJniRegistrationBackend> pending(pendingBackend);
    assert(!pending.registerAndVerify());
    assert(state.registrations == 0 && state.removals == 0);

    reset();
    JNINativeMethod wrong[] = {
        {const_cast<char*>("dispatch"), const_cast<char*>("(JJIII)I"),
         reinterpret_cast<void*>(&dummy)}
    };
    RendererDiagnosticJniRegistrationBackend wrongBackend(env, clazz, wrong, 1);
    RendererDiagnosticRegistration<RendererDiagnosticJniRegistrationBackend> wrongTx(wrongBackend);
    assert(!wrongTx.registerAndVerify() && state.registrations == 0);

    RendererDiagnosticJniRegistrationBackend zeroCount(env, clazz, methods, 0);
    assert(!zeroCount.validateSignatures());
    RendererDiagnosticJniRegistrationBackend noEnv(nullptr, clazz, methods, 1);
    assert(!noEnv.validateSignatures());
}
