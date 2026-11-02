#include <jni.h>

// Emulator-only test library: intentionally refuses JNI initialization.
// This file must never be packaged into the production app.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env)
        return JNI_ERR;
    jclass target = env->FindClass("io/github/jdawg867/anvildesk/m8/M8JniFailureProbeTarget");
    if (!target)
        return JNI_ERR;
    // This intentionally nonexistent native declaration forces RegisterNatives
    // to fail with NoSuchMethodError on Android.
    JNINativeMethod invalid[] = {
        {const_cast<char*>("methodThatMustNotExist"),
         const_cast<char*>("()V"), reinterpret_cast<void*>(+[]() {})}
    };
    if (env->RegisterNatives(target, invalid, 1) != JNI_OK)
        return JNI_ERR;
    // Unexpected acceptance is still a failure: never load successfully.
    return JNI_ERR;
}
