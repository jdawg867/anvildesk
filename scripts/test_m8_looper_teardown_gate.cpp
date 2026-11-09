// Regression checks for fail-closed host-only M8 teardown transitions.
#include "m8-looper-teardown-gate.h"
#include <iostream>
#include <stdexcept>

using Gate = anvildesk_m8::LooperTeardownGate;
static void check(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}

static void testSuccessfulRemovalDoesNotAuthorizeRelease() {
    Gate gate;
    check(gate.canRegister(), "fresh gate cannot register");
    check(!gate.reportHostDrain(), "drain accepted without shutdown");
    check(gate.beginShutdown(), "shutdown not admitted");
    check(!gate.canRegister(), "registration admitted after shutdown");
    check(!gate.beginShutdown(), "duplicate shutdown reopened gate");
    check(gate.reportRemoval(Gate::RemoveOutcome::Removed), "removal rejected");
    check(gate.state() == Gate::State::HostDrainPending, "drain phase skipped");
    check(!gate.canReleaseNativeResources(), "removal authorized release");
    check(gate.reportHostDrain(), "host drain rejected");
    check(gate.state() == Gate::State::AwaitingAndroidQuiescence,
          "Android quiescence barrier omitted");
    check(!gate.canReleaseNativeResources(), "host drain authorized release");
    check(!gate.beginShutdown(), "shutdown restarted after drain");
}

static void testRemovalFailureAndRetry() {
    Gate gate;
    check(gate.beginShutdown(), "shutdown not admitted");
    check(gate.reportRemoval(Gate::RemoveOutcome::Error), "removal error rejected");
    check(gate.state() == Gate::State::RemovalFailed, "failed removal not retained");
    check(!gate.canRegister(), "failed removal reopened admission");
    check(!gate.reportHostDrain(), "host drain accepted before successful removal");
    check(!gate.canReleaseNativeResources(), "failed removal allowed native free");
    check(gate.retryRemoval(), "removal retry rejected");
    check(gate.reportRemoval(Gate::RemoveOutcome::Removed), "retry result rejected");
    check(gate.reportHostDrain(), "post-retry drain rejected");
    check(!gate.canReleaseNativeResources(), "post-retry drain authorized free");
}

static void testAbsentRegistrationStillNeedsPlatformBarrier() {
    Gate gate;
    check(gate.beginShutdown(), "shutdown not admitted");
    check(gate.reportRemoval(Gate::RemoveOutcome::NotRegistered),
          "missing registration result rejected");
    check(gate.reportHostDrain(), "host drain rejected");
    check(gate.state() == Gate::State::AwaitingAndroidQuiescence,
          "missing registration bypassed Android barrier");
    check(!gate.canReleaseNativeResources(), "missing registration authorized free");
    check(!gate.retryRemoval(), "completed removal unexpectedly retried");
}

int main() {
    try {
        testSuccessfulRemovalDoesNotAuthorizeRelease();
        testRemovalFailureAndRetry();
        testAbsentRegistrationStillNeedsPlatformBarrier();
        std::cout << "M8_LOOPER_TEARDOWN_GATE_HOST_MODEL=PASS\n"
                  << "M8_ANDROID_LOOPER_QUIESCENCE_VERIFIED=false\n"
                  << "M8_NATIVE_RESOURCE_RELEASE_AUTHORIZED=false\n"
                  << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
    } catch (const std::exception& e) {
        std::cerr << "FAIL: " << e.what() << '\n';
        return 1;
    }
}
