// Standalone desktop JVM integration probe. Uses real HotSpot JNI
// RegisterNatives/UnregisterNatives. No Android ART, production app, Lorie,
// JNI_OnLoad, native renderer library, or Samsung device involvement.
#include "m8-renderer-diagnostic-jni-registration-backend.h"
#include "m8-renderer-diagnostic-registration.h"
#include "m8-renderer-registration-teardown.h"
#include "m8-renderer-diagnostic-jni-command.h"
#include "m8-renderer-handle-session-retirement.h"
#include <jni.h>
#include <cassert>
#include <cstdint>
#include <memory>
#include <string>
using namespace anvildesk_m8;

struct Resource {
    explicit Resource(std::uint64_t g) : id(g) {}
    std::uint64_t generation() const noexcept { return id; }
    const std::uint64_t id;
    int applied = 0;
};
struct Commands {
    int calls = 0;
    bool apply(Resource& resource, RendererCommand, std::uint32_t, std::uint32_t) {
        ++calls;
        ++resource.applied;
        return true;
    }
};
struct Retire {
    int calls = 0;
    bool shutdownAndRetire() { ++calls; return true; }
};
using Facade = RendererDiagnosticJniCommand<Resource, Commands>;
static Facade* activeFacade = nullptr;

static jint JNICALL dispatchEntry(JNIEnv* env, jclass cls, jint version,
                                  jlong handle, jlong generation, jint command,
                                  jint width, jint height) {
    if (!activeFacade) return static_cast<jint>(RendererCommandResult::BackendRejected);
    return activeFacade->dispatch(env, cls, version, handle, generation, command, width, height);
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::string classpath = std::string("-Djava.class.path=") + argv[1];
    JavaVMOption option{};
    option.optionString = classpath.data();
    JavaVMInitArgs init{};
    init.version = JNI_VERSION_1_6;
    init.nOptions = 1;
    init.options = &option;
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    if (JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&env), &init) != JNI_OK ||
        !vm || !env) return 3;

    int status = 0;
    {
        jclass target = env->FindClass(
            "io/github/jdawg867/anvildesk/m8/diagnostic/M8HostRegisteredCommand");
        if (!target || env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            status = 4;
        } else {
            RendererHandleRegistry<Resource> registry;
            Commands backend;
            RendererCommandBoundary<Resource, Commands> boundary(registry, backend);
            RendererJniCommandAdapter<Resource, Commands> adapter(boundary);
            Facade facade(adapter);
            activeFacade = &facade;
            const auto handle = registry.publish(std::make_shared<Resource>(55));
            JNINativeMethod methods[] = {
                {const_cast<char*>("dispatch"), const_cast<char*>("(IJJIII)I"),
                 reinterpret_cast<void*>(&dispatchEntry)}
            };
            RendererDiagnosticJniRegistrationBackend registrationBackend(
                env, target, methods, 1);
            RendererDiagnosticRegistration<RendererDiagnosticJniRegistrationBackend>
                registration(registrationBackend);
            if (!registration.registerAndVerify() || !registration.registered()) {
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                }
                status = 5;
            } else {
                jmethodID method = env->GetStaticMethodID(
                    target, "dispatch", "(IJJIII)I");
                if (!method || env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    status = 6;
                } else {
                    jint accepted = env->CallStaticIntMethod(
                        target, method, static_cast<jint>(1),
                        static_cast<jlong>(handle), static_cast<jlong>(55),
                        static_cast<jint>(1), static_cast<jint>(640), static_cast<jint>(480));
                    jint stale = env->CallStaticIntMethod(
                        target, method, static_cast<jint>(1),
                        static_cast<jlong>(handle), static_cast<jlong>(56),
                        static_cast<jint>(2), static_cast<jint>(0), static_cast<jint>(0));
                    jint bad = env->CallStaticIntMethod(
                        target, method, static_cast<jint>(1),
                        static_cast<jlong>(-1), static_cast<jlong>(55),
                        static_cast<jint>(2), static_cast<jint>(0), static_cast<jint>(0));
                    if (env->ExceptionCheck() || accepted != 0 || stale != 1 ||
                        bad != 2 || backend.calls != 1) {
                        if (env->ExceptionCheck()) {
                            env->ExceptionDescribe();
                            env->ExceptionClear();
                        }
                        status = 7;
                    }
                }
            }

            Retire retireBackend;
            RendererHandleSessionRetirement<Resource, Retire> retirement(
                registry, handle, retireBackend);
            RendererRegistrationTeardown<decltype(retirement), decltype(registration)>
                teardown(retirement, registration);
            // Teardown happens only from the JVM caller, after all command calls
            // have returned. Command leases and registration state are joined.
            if (registration.registered()) {
                if (!teardown.shutdownAndUnregister() || !teardown.complete() ||
                    registration.registered() || retireBackend.calls != 1)
                    status = status ? status : 8;
            } else {
                // A failed registration is never treated as success.
                if (!retirement.shutdownAndRetire()) status = status ? status : 9;
            }
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                status = status ? status : 10;
            }
            activeFacade = nullptr;
            env->DeleteLocalRef(target);
        }
    }
    if (vm->DestroyJavaVM() != JNI_OK) return 11;
    return status;
}
