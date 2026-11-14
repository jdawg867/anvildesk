// Host-only M8 state-model regression. No Android/JNI/renderer execution.
#include "m8-renderer-callback-ownership-model.h"
#include <cstdlib>
#include <iostream>
using anvildesk_m8::RendererCallbackOwnershipModel;
static void check(bool result) { if (!result) std::abort(); }

int main() {
    RendererCallbackOwnershipModel session;
    check(!session.registerSession(0));
    check(!session.mayFreeUserdata());
    check(session.registerSession(7));
    check(!session.registerSession(8));
    check(session.callbackMayEnter(7));
    check(!session.callbackMayEnter(6));
    check(!session.releaseUserdata());
    check(!session.acknowledgeWorkerJoined());
    check(session.beginShutdown());
    check(!session.admissionOpen());
    check(!session.callbackMayEnter(7));
    check(!session.registerSession(8));
    check(session.recordRemoveFailure());
    check(!session.releaseUserdata());
    check(!session.acknowledgeWorkerJoined());
    check(session.retryRemoval());
    check(session.recordRemoveSuccessOrAbsent());
    check(!session.releaseUserdata());
    check(!session.callbackMayEnter(7));
    check(session.acknowledgeWorkerJoined());
    check(session.mayFreeUserdata());
    check(session.releaseUserdata());
    check(!session.releaseUserdata());
    check(!session.registerSession(9));
    check(!session.beginShutdown());

    // The callback already removed itself (NDK removeFd returns 0).
    RendererCallbackOwnershipModel autoRemoved;
    check(autoRemoved.registerSession(10));
    check(autoRemoved.beginShutdown());
    check(autoRemoved.recordRemoveSuccessOrAbsent());
    check(!autoRemoved.mayFreeUserdata());
    check(autoRemoved.acknowledgeWorkerJoined());
    check(autoRemoved.releaseUserdata());

    // A failed removal must never permit freeing or claiming join.
    RendererCallbackOwnershipModel failed;
    check(failed.registerSession(11));
    check(failed.beginShutdown());
    check(failed.recordRemoveFailure());
    check(!failed.mayFreeUserdata());
    check(!failed.acknowledgeWorkerJoined());
    check(!failed.releaseUserdata());

    std::cout << "M8_RENDERER_OWNERSHIP_MODEL_FAILURE_RETRY=PASS\n"
              << "M8_RENDERER_OWNERSHIP_MODEL_JOIN_BEFORE_FREE=PASS\n"
              << "M8_RENDERER_OWNERSHIP_MODEL_TERMINAL_RELEASE=PASS\n"
              << "M8_RENDERER_ANDROID_CALLBACK_QUIESCENCE_VERIFIED=false\n"
              << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
}
