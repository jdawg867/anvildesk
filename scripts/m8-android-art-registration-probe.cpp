// Opt-in Android ART registration probe. Instrumentation APK ONLY.
// No JNI_OnLoad, no renderer, no Lorie, no production native library.
// Failure exception clearing is allowed only in this disposable diagnostic.
#include <jni.h>
#include <cstdint>

namespace {
static jint JNICALL command(JNIEnv*, jclass, jint version, jlong handle,
                            jlong generation, jint opcode, jint width, jint height) {
    if (version != 1 || handle <= 0 || generation <= 0) return 2;
    if (opcode == 2 && width == 0 && height == 0) return 0;
    if (opcode == 1 && width > 0 && height > 0 && width <= 16384 && height <= 16384)
        return 0;
    return 2;
}
}
extern "C" JNIEXPORT jint JNICALL
Java_io_github_jdawg867_anvildesk_m8_M8ArtRegistrationDiagnostic_runProbe(
    JNIEnv* env, jclass, jclass target) {
    if (!env || !target || env->ExceptionCheck()) return 10;
    // Deliberately nonexistent method. ART must reject this call; the
    // diagnostic owns and clears its deliberately induced exception.
    JNINativeMethod wrong[] = {{
        const_cast<char*>("methodThatMustNotExist"),
        const_cast<char*>("(IJJIII)I"),
        reinterpret_cast<void*>(&command)
    }};
    const jint invalid = env->RegisterNatives(target, wrong, 1);
    const bool exceptionSeen = env->ExceptionCheck() == JNI_TRUE;
    if (invalid == JNI_OK || !exceptionSeen) {
        if (exceptionSeen) env->ExceptionClear();
        return 11;
    }
    env->ExceptionClear();
    if (env->ExceptionCheck()) return 12;
    // Explicit recovery before any second registration attempt.
    if (env->UnregisterNatives(target) != JNI_OK || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return 13;
    }
    JNINativeMethod valid[] = {{
        const_cast<char*>("dispatch"),
        const_cast<char*>("(IJJIII)I"),
        reinterpret_cast<void*>(&command)
    }};
    if (env->RegisterNatives(target, valid, 1) != JNI_OK || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return 14;
    }
    jmethodID method = env->GetStaticMethodID(target, "dispatch", "(IJJIII)I");
    if (!method || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->UnregisterNatives(target);
        return 15;
    }
    const jint result = env->CallStaticIntMethod(
        target, method, static_cast<jint>(1), static_cast<jlong>(77),
        static_cast<jlong>(9), static_cast<jint>(1),
        static_cast<jint>(640), static_cast<jint>(480));
    const bool callFailed = env->ExceptionCheck() == JNI_TRUE;
    if (callFailed) env->ExceptionClear();
    // All calls are complete before deregistration. Do not invoke the
    // method again after UnregisterNatives.
    const jint removed = env->UnregisterNatives(target);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 16;
    }
    if (removed != JNI_OK || callFailed || result != 0) return 17;
    return 0;
}
