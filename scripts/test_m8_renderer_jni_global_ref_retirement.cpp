// Host-only failure model for explicit global-ref retirement. The join flag
// is a synthetic stand-in, not proof of real Android looper quiescence.
#include "m8-renderer-jni-global-ref-retirement.h"
#include <atomic>
#include <iostream>
#include <stdexcept>
#include <thread>
using anvildesk_m8::DeferredGlobalRefRetirement;
static void check(bool b, const char* why) {
    if (!b) throw std::runtime_error(why);
}
struct FakeRefs {
    using Ref = void*;
    std::atomic<int> deleted{0}, attempts{0};
    std::atomic<bool> attached{true}, pendingException{false};
    bool deleteGlobalRef(Ref ref) {
        ++attempts;
        if (!ref || !attached || pendingException) return false;
        ++deleted;
        return true;
    }
};
int main() {
    int token = 1;
    FakeRefs backend;
    DeferredGlobalRefRetirement<FakeRefs> ref(backend, &token);
    check(!ref.retireAfterVerifiedJoin(false), "premature retirement accepted");
    check(ref.holdsReference() && backend.deleted == 0, "premature free");
    backend.attached = false;
    check(!ref.retireAfterVerifiedJoin(true), "unattached retirement accepted");
    check(ref.holdsReference() && backend.deleted == 0, "lost reference on JNI failure");
    backend.attached = true;
    backend.pendingException = true;
    check(!ref.retireAfterVerifiedJoin(true), "pending exception retirement accepted");
    check(ref.holdsReference() && backend.deleted == 0, "lost reference with exception");
    backend.pendingException = false;
    std::thread first([&] { check(ref.retireAfterVerifiedJoin(true), "first retirement failed"); });
    std::thread second([&] { check(ref.retireAfterVerifiedJoin(true), "second retirement failed"); });
    first.join();
    second.join();
    check(ref.retired() && !ref.holdsReference() && backend.deleted == 1,
          "concurrent retirement did not delete exactly once");
    check(ref.retireAfterVerifiedJoin(true) && backend.deleted == 1,
          "repeated retirement caused double deletion");
    FakeRefs nullBackend;
    DeferredGlobalRefRetirement<FakeRefs> empty(nullBackend, nullptr);
    check(empty.retireAfterVerifiedJoin(true) && nullBackend.deleted == 0,
          "empty ref retirement failed");
    check(!DeferredGlobalRefRetirement<FakeRefs>(nullBackend, nullptr)
               .retireAfterVerifiedJoin(false),
          "empty ref bypassed join gate");
    std::cout << "M8_JNI_GLOBAL_REF_PREJOIN_RETIRE_REJECTED=PASS\n"
              << "M8_JNI_GLOBAL_REF_UNATTACHED_RETRY=PASS\n"
              << "M8_JNI_GLOBAL_REF_PENDING_EXCEPTION_RETRY=PASS\n"
              << "M8_JNI_GLOBAL_REF_CONCURRENT_SINGLE_DELETE=PASS\n"
              << "M8_JNI_GLOBAL_REF_EMPTY_JOIN_GUARD=PASS\n"
              << "M8_LORIE_JNI_GLOBAL_REF_LIFETIME_VERIFIED=false\n"
              << "M8_NATIVE_LOAD_AUTHORIZED=false\n";
}
