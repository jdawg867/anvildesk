// Disposable HotSpot JNI failure semantics probe. Intentionally supplies a
// nonexistent method descriptor, checks pending exception, then explicitly
// clears that TEST-ONLY exception before controlled recovery.
// Not an Android ART test, production JNI export or Lorie load.
#include "m8-renderer-diagnostic-jni-registration-backend.h"
#include "m8-renderer-diagnostic-registration.h"
#include <jni.h>
#include <cstdint>
#include <string>
using namespace anvildesk_m8;

static jint JNICALL diagnostic(JNIEnv*, jclass, jint, jlong, jlong,
                               jint, jint, jint) { return 0; }

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::string classpath = std::string("-Djava.class.path=") + argv[1];
    JavaVMOption option{};
    option.optionString = classpath.data();
    JavaVMInitArgs args{};
    args.version = JNI_VERSION_1_6;
    args.nOptions = 1;
    args.options = &option;
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    if (JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&env), &args) != JNI_OK ||
        !vm || !env) return 3;
    int status = 0;
    jclass clazz = env->FindClass(
        "io/github/jdawg867/anvildesk/m8/diagnostic/M8HostRegisteredCommand");
    if (!clazz || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        status = 4;
    } else {
        // Correct function pointer but wrong descriptor: real JVM should reject.
        JNINativeMethod invalid[] = {{
            const_cast<char*>("dispatch"),
            const_cast<char*>("(JJIII)I"),
            reinterpret_cast<void*>(&diagnostic)
        }};
        const jint badReturn = env->RegisterNatives(clazz, invalid, 1);
        const bool hadException = env->ExceptionCheck() == JNI_TRUE;
        if (badReturn == JNI_OK || !hadException) status = 5;
        // Caller-owned test exception recovery. The fail-closed production
        // backend deliberately does NOT clear exceptions behind Java's back.
        if (hadException) env->ExceptionClear();
        if (env->ExceptionCheck()) status = status ? status : 6;

        // Registration state after failure is not inferred; call explicit
        // cleanup before allowing a new diagnostic transaction.
        if (env->UnregisterNatives(clazz) != JNI_OK ||
            env->ExceptionCheck()) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            status = status ? status : 7;
        }

        JNINativeMethod valid[] = {{
            const_cast<char*>("dispatch"),
            const_cast<char*>("(IJJIII)I"),
            reinterpret_cast<void*>(&diagnostic)
        }};
        RendererDiagnosticJniRegistrationBackend backend(env, clazz, valid, 1);
        RendererDiagnosticRegistration<RendererDiagnosticJniRegistrationBackend>
            transaction(backend);
        if (!transaction.registerAndVerify() || !transaction.registered())
            status = status ? status : 8;
        if (transaction.registered() &&
            (!transaction.unregisterAfterDrain(true) || transaction.registered()))
            status = status ? status : 9;
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            status = status ? status : 10;
        }
        env->DeleteLocalRef(clazz);
    }
    if (vm->DestroyJavaVM() != JNI_OK) return 11;
    return status;
}
