#pragma once
// JNI-backed ref retirement adapter: only use after external callback worker
// join; never integrate into Lorie before its ownership graph is reviewed.
#include "m8-renderer-jni-thread-env.h"
#include "m8-renderer-jni-global-ref-retirement.h"
namespace anvildesk_m8 {
class AndroidGlobalRefRetirementBackend final {
public:
    using Ref = jobject;
    explicit AndroidGlobalRefRetirementBackend(JavaVM* vm) : thread_(vm) {}
    bool deleteGlobalRef(jobject global) {
        if (!global) return true;
        ScopedAndroidCallbackEnv scope(thread_);
        if (!scope) return false;
        JNIEnv* env = scope.get();
        // Never clear or swallow another subsystem's pending JNI exception.
        // In that case the reference remains owned and must be retried.
        if (env->ExceptionCheck()) return false;
        env->DeleteGlobalRef(global); // JNI void operation; no success code.
        return true;
    }
private:
    AndroidJniThreadBackend thread_;
};
} // namespace anvildesk_m8
