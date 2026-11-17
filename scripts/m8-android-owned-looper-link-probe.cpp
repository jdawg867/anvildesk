// Link-only Android ARM64 probe for the isolated owned-looper prototype.
// Never load, execute, or package this probe in AnvilDesk.
#include "m8-android-owned-looper-thread.cpp"

// Export a non-inlined entry point so Android looper symbols are retained
// through the linker. Deliberately do not call from production.
extern "C" __attribute__((visibility("default")))
int anvildesk_m8_owned_looper_link_probe() {
    anvildesk_m8::DedicatedLooperThread thread;
    const bool prepared = thread.initialized();
    if (!thread.shutdownAndJoin()) return -1;
    return prepared ? 0 : -2;
}
