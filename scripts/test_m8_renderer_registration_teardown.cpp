// Host-only composition. Fake JNI registration does not prove ART behavior.
#include "m8-renderer-registration-teardown.h"
#include "m8-renderer-diagnostic-registration.h"
#include "m8-renderer-handle-session-retirement.h"
#include "m8-renderer-command-boundary.h"
#include <atomic>
#include <cassert>
#include <chrono>
#include <future>
#include <memory>
#include <thread>
using namespace anvildesk_m8;
using namespace std::chrono_literals;

struct Resource {
    explicit Resource(std::uint64_t g) : g(g) {}
    std::uint64_t generation() const { return g; }
    const std::uint64_t g;
};
struct Backend {
    std::atomic<int> calls{0};
    bool apply(Resource&, RendererCommand, std::uint32_t, std::uint32_t) {
        ++calls;
        return true;
    }
};
struct Retirement {
    std::atomic<int> calls{0};
    std::atomic<bool> allowed{true};
    bool shutdownAndRetire() { ++calls; return allowed; }
};
struct RegistrationBackend {
    std::atomic<int> registers{0}, unregisters{0};
    bool failUnregister = false;
    bool validateSignatures() { return true; }
    bool registerMethods() { ++registers; return true; }
    bool verifyRegistered() { return true; }
    bool unregisterMethods() { ++unregisters; return !failUnregister; }
};
int main() {
    RendererHandleRegistry<Resource> registry;
    Backend backend;
    RendererCommandBoundary<Resource, Backend> commands(registry, backend);
    auto handle = registry.publish(std::make_shared<Resource>(15));
    RegistrationBackend registrationBackend;
    RendererDiagnosticRegistration<RegistrationBackend> registration(registrationBackend);
    assert(registration.registerAndVerify());
    Retirement sessionBackend;
    RendererHandleSessionRetirement<Resource, Retirement>
        retirement(registry, handle, sessionBackend);
    RendererRegistrationTeardown<decltype(retirement), decltype(registration)>
        teardown(retirement, registration);

    // Active command holds lease while native teardown starts.
    auto active = registry.acquire(handle);
    assert(active);
    std::promise<bool> finished;
    auto result = finished.get_future();
    std::thread closer([&] { finished.set_value(teardown.shutdownAndUnregister()); });
    const auto deadline = std::chrono::steady_clock::now() + 5s;
    while (registry.acquire(handle) && std::chrono::steady_clock::now() < deadline)
        std::this_thread::yield();
    assert(!registry.acquire(handle));
    assert(commands.dispatch(handle, 15, RendererCommand::Refresh) ==
           RendererCommandResult::StaleHandle);
    assert(result.wait_for(20ms) == std::future_status::timeout);
    assert(registrationBackend.unregisters == 0);
    assert(registration.registered());
    assert(sessionBackend.calls == 0);
    active.release();
    assert(result.wait_for(5s) == std::future_status::ready);
    assert(result.get());
    closer.join();
    assert(teardown.complete());
    assert(retirement.retired());
    assert(!registration.registered());
    assert(sessionBackend.calls == 1 && registrationBackend.unregisters == 1);
    assert(teardown.shutdownAndUnregister());
    assert(registrationBackend.unregisters == 1);

    // Retirement failure must never advance to UnregisterNatives.
    auto handle2 = registry.publish(std::make_shared<Resource>(16));
    RegistrationBackend registration2Backend;
    RendererDiagnosticRegistration<RegistrationBackend> registration2(registration2Backend);
    assert(registration2.registerAndVerify());
    Retirement retireFailure;
    retireFailure.allowed = false;
    RendererHandleSessionRetirement<Resource, Retirement>
        retirement2(registry, handle2, retireFailure);
    RendererRegistrationTeardown<decltype(retirement2), decltype(registration2)>
        teardown2(retirement2, registration2);
    assert(!teardown2.shutdownAndUnregister());
    assert(registration2Backend.unregisters == 0 && registration2.registered());
    retireFailure.allowed = true;
    assert(teardown2.shutdownAndUnregister());
    assert(registration2Backend.unregisters == 1);
    assert(!registration2.registered());
    assert(!registry.acquire(handle2));

    // An unregistration failure poisons registration and cannot be claimed
    // successful or retried blindly after its state becomes uncertain.
    auto handle3 = registry.publish(std::make_shared<Resource>(17));
    RegistrationBackend badBackend;
    badBackend.failUnregister = true;
    RendererDiagnosticRegistration<RegistrationBackend> badRegistration(badBackend);
    assert(badRegistration.registerAndVerify());
    Retirement retire3;
    RendererHandleSessionRetirement<Resource, Retirement>
        retirement3(registry, handle3, retire3);
    RendererRegistrationTeardown<decltype(retirement3), decltype(badRegistration)>
        teardown3(retirement3, badRegistration);
    assert(!teardown3.shutdownAndUnregister());
    assert(retirement3.retired());
    assert(badRegistration.poisoned() && !teardown3.complete());
    assert(!teardown3.shutdownAndUnregister());
    assert(badBackend.unregisters == 1);
}
