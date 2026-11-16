#include "m8-renderer-diagnostic-registration.h"
#include <atomic>
#include <cassert>
#include <stdexcept>
#include <thread>
using namespace anvildesk_m8;

struct FakeRegistration {
    bool signaturesValid = true;
    bool registerSuccess = true;
    bool verified = true;
    bool unregisterSuccess = true;
    bool throwOnRegister = false;
    bool installed = false;
    int validations = 0;
    int registers = 0;
    int verifies = 0;
    int unregisters = 0;
    bool validateSignatures() { ++validations; return signaturesValid; }
    bool registerMethods() {
        ++registers;
        installed = true; // Simulate partial installation before failure.
        if (throwOnRegister) throw std::runtime_error("registration error");
        return registerSuccess;
    }
    bool verifyRegistered() { ++verifies; return verified; }
    bool unregisterMethods() {
        ++unregisters;
        if (!unregisterSuccess) return false;
        installed = false;
        return true;
    }
};
int main() {
    // Invalid signatures must not touch the registry.
    FakeRegistration invalid;
    invalid.signaturesValid = false;
    RendererDiagnosticRegistration<FakeRegistration> invalidCoordinator(invalid);
    assert(!invalidCoordinator.registerAndVerify());
    assert(invalid.registers == 0 && invalid.unregisters == 0 && !invalid.installed);

    // A register failure can leave partial state; rollback must run.
    FakeRegistration partial;
    partial.registerSuccess = false;
    RendererDiagnosticRegistration<FakeRegistration> partialCoordinator(partial);
    assert(!partialCoordinator.registerAndVerify());
    assert(!partial.installed && partial.unregisters == 1);
    assert(!partialCoordinator.registered() && !partialCoordinator.poisoned());
    partial.registerSuccess = true;
    assert(partialCoordinator.registerAndVerify());
    assert(partialCoordinator.registered() && partial.installed);
    assert(!partialCoordinator.unregisterAfterDrain(false));
    assert(partial.installed);
    assert(partialCoordinator.unregisterAfterDrain(true));
    assert(!partial.installed);

    // Failure after successful register also rolls back.
    FakeRegistration verifyFailure;
    verifyFailure.verified = false;
    RendererDiagnosticRegistration<FakeRegistration> verifyCoordinator(verifyFailure);
    assert(!verifyCoordinator.registerAndVerify());
    assert(!verifyFailure.installed && verifyFailure.unregisters == 1);

    FakeRegistration exceptionFailure;
    exceptionFailure.throwOnRegister = true;
    RendererDiagnosticRegistration<FakeRegistration> exceptionCoordinator(exceptionFailure);
    assert(!exceptionCoordinator.registerAndVerify());
    assert(!exceptionFailure.installed && exceptionFailure.unregisters == 1);

    // Unregistration failure must never allow another registration attempt.
    FakeRegistration leaked;
    leaked.verified = false;
    leaked.unregisterSuccess = false;
    RendererDiagnosticRegistration<FakeRegistration> leakedCoordinator(leaked);
    assert(!leakedCoordinator.registerAndVerify());
    assert(leakedCoordinator.poisoned() && !leakedCoordinator.registered());
    assert(leaked.installed);
    const int registeredOnce = leaked.registers;
    assert(!leakedCoordinator.registerAndVerify());
    assert(leaked.registers == registeredOnce);
    assert(!leakedCoordinator.unregisterAfterDrain(true));

    // Successful registration is serialized and idempotent across callers.
    FakeRegistration concurrent;
    RendererDiagnosticRegistration<FakeRegistration> coordinator(concurrent);
    std::atomic<int> successes{0};
    std::thread a([&] { if (coordinator.registerAndVerify()) ++successes; });
    std::thread b([&] { if (coordinator.registerAndVerify()) ++successes; });
    a.join(); b.join();
    assert(successes == 2 && concurrent.registers == 1 && concurrent.installed);
    assert(coordinator.unregisterAfterDrain(true));
    assert(coordinator.unregisterAfterDrain(true));
    assert(concurrent.unregisters == 1 && !concurrent.installed);
}
