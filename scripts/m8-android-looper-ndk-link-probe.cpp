// Build/link verification only. NEVER execute or ship this probe.
// The goal is to force real Android NDK ALooper symbol resolution.
#include "m8-android-looper-ndk-compile.cpp"

// A real callback signature, with no renderer access or JNI operations.
static int probeCallback(int, int, void*) { return 0; }

extern "C" __attribute__((visibility("default")))
int anvildesk_m8_ndk_link_probe(ALooper* borrowedLooper, int fd,
                                void* borrowedUserdata) {
    // Keep all Android calls link-visible, but DO NOT invoke this function.
    // The compile-only API has no real callback userdata lifetime guarantee.
    if (!borrowedLooper || fd < 0 || !borrowedUserdata) return -1;
    anvildesk_m8::RetainedLooper looper(borrowedLooper);
    const auto add = looper.add(
        fd, ALOOPER_EVENT_INPUT, probeCallback, borrowedUserdata);
    if (add != anvildesk_m8::AddStatus::Added) return -2;
    looper.beginShutdown();
    const auto removed = looper.remove(fd);
    // Failed removal triggers the adapter's deliberate fail-closed
    // destructor: this function is NOT a usable runtime adapter.
    return removed == anvildesk_m8::RemoveStatus::Removed ? 0 : -3;
}
