#pragma once
// TEST ONLY: minimal host shim for compiling the actual M8 NDK adapter.
// Never include this directory in Android APK or production builds.
struct ALooper;
using ALooper_callbackFunc = int (*)(int, int, void*);
constexpr int ALOOPER_POLL_CALLBACK = -2;
constexpr int ALOOPER_EVENT_INPUT = 1;
ALooper* ALooper_forThread();
void ALooper_acquire(ALooper*);
void ALooper_release(ALooper*);
int ALooper_addFd(ALooper*, int, int, int, ALooper_callbackFunc, void*);
int ALooper_removeFd(ALooper*, int);
