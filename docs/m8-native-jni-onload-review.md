# M8 JNI_OnLoad safety review — experimental ARM64 X11 process

Status: **NOT AUTHORIZED TO LOAD**.

## Verified build evidence

The pinned Termux:X11 library compiles with AnvilDesk's class-registration patch, the TCP knock listener removed, and the native server-thread process-exit behavior replaced. The ARM64 packaging spike includes `libXlorie.so` and an instrumentation test APK. None of these checks runs `JNI_OnLoad` on ARM64 Android.

## Required native-loader proof

- Validate every registered `FindClass` target and every `RegisterNatives` method against the classes present in the *actual installed ARM64 diagnostic APK*.
- Audit `JNI_OnLoad` initialization, global references, other class lookups, and failure rollback. A Java signature inventory alone is insufficient.
- Identify all dependencies and any native constructors invoked by dynamic loading, before Xorg startup.
- Confirm the load occurs exclusively inside the `:m8x11` process and cannot be triggered by opening the production Activity.
- Establish deterministic process shutdown and detection of unexpected child-process death; process termination is not an acceptable shutdown mechanism for the main Android application.
- Run the first actual load only in the isolated, separately identified ARM64 diagnostic package; preserve the existing production package, its signature and Ubuntu rootfs.
- Record Android logcat, isolated-process PID, JNI registration outcome, linker errors, and process-death behavior.

## Explicit blockers

The current `M8NativeLoadingPolicy` denies all loads. The `M8X11ProcessService` is disabled by manifest. No experiment should bypass those gates merely because an APK builds or a native library is present.

Next implementation should add a *non-executing* JNI_OnLoad/source-registration audit to CI, then review its results before creating a separately gated ARM64 runtime experiment.
