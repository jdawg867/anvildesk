#pragma once
// Isolated host-only Android looper shim; never packaged into APK.
struct ALooper;
using ALooper_callbackFunc = int (*)(int, int, void*);
constexpr int ALOOPER_POLL_CALLBACK = -2;
constexpr int ALOOPER_EVENT_INPUT = 1;
ALooper* ALooper_prepare(int);
void ALooper_acquire(ALooper*);
void ALooper_release(ALooper*);
void ALooper_wake(ALooper*);
int ALooper_pollOnce(int, int*, int*, void**);
int ALooper_addFd(ALooper*, int, int, int, ALooper_callbackFunc, void*);
int ALooper_removeFd(ALooper*, int);
