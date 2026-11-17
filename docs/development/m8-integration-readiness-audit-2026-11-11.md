# M8 integration-readiness audit — 2026-11-11

**Status: BLOCKED for renderer/JNI integration and production native loading.**

Repository: `jdawg867/anvildesk`  
Branch: `feature/android-x11-display-input`  
Review baseline: `56c6ae232368fe3097a809e0a1651bc39fc03b25`  
Draft pull request: #21 (target: `development`).

## Proven evidence

- At the review baseline, GitHub Actions push run **43109348159** and PR run
  **43109359217** both completed successfully.
- The isolated `DedicatedLooperThread` host test executes the real prototype
  class against a fake Android NDK looper shim, with ASan/UBSan. It checks
  callback thread identity, registration admission, callback auto-removal,
  shutdown concurrency, failure/retry paths and reference accounting.
- CI also compiles the standalone Android ARM64 looper/device probe. A compile
  or link result is **not** evidence that its newest behavior ran on Android.
- The Samsung SM-G766U standalone ARM64 device probe was reported passing on
  2026-11-09 for its **pinned older** artifact; see
  `docs/development/m8-samsung-looper-device-acceptance.md`. It does not
  validate the later host regressions or any renderer/JNI behavior.

## What remains unproved / blocking

1. **Renderer integration:** `scripts/m8-android-owned-looper-thread.cpp` is an
   isolated prototype, not wired into the upstream Lorie renderer. No
   demonstrated callback userdata ownership from registration through
   deregistration and worker join for real renderer callbacks.
2. **Native X11 server shutdown:** see `docs/m8-native-server-shutdown-contract.md`.
   No audited Xorg-supported stop mechanism, pthread join contract, or proven
   safe handling of process-global Xorg state/restart.
3. **JNI load and rollback:** see `docs/m8-native-jni-onload-review.md`.
   Compiling or statically auditing JNI registration does not verify actual
   `JNI_OnLoad` execution, constructor dependencies, error rollback or global
   reference cleanup on the target ARM64 device.
4. **End-to-end process and display lifecycle:** no device acceptance for
   isolated `:m8x11` execution with renderer startup/teardown, app process
   survival, rotation, disconnect, retries, or guest desktop I/O.
5. **Current device coverage:** physical-device evidence covers the narrow
   pinned standalone callback/join smoke scenario, not the newest prototype
   behavior and not production integration.

## Recommended ordered work

1. Keep PR #21 **draft** and `M8NativeLoadingPolicy` denying load. Do not
   enable the disabled `:m8x11` service, package an executable renderer
   into a production path, or alter the existing installed Samsung app/rootfs.
2. Audit real upstream renderer callback registration/deregistration paths and
   JNI global state; record concrete ownership entry/exit points and
   non-executing rollback checks using GitHub CI.
3. Design and review a real Xorg-supported stop + verified join contract in
   the isolated process. If Xorg cannot reliably stop/reinitialize, specify a
   safe process-lifetime policy rather than assuming in-process restart.
4. Only after independent native-load and server-lifecycle safety review,
   prepare a separately identified diagnostic ARM64 package for explicit
   user-approved ADB testing; never silently upgrade the production package.
5. Acquire device evidence for callback userdata lifetime, renderer
   connection/disconnection and server shutdown, with artifact provenance and
   failure records, before considering production promotion.

## Interpretation of CI

Passing host/sanitizer tests confirms modeled invariants in the isolated
prototype only. It does **not** grant Android runtime, renderer, JNI, native
server shutdown, or production load authorization.

**M8_X11_RENDERER_INTEGRATION_VERIFIED=false**  
**M8_NATIVE_LOAD_AUTHORIZED=false**
