# M8 composite JNI Samsung instrumentation acceptance

**Status: PASS — isolated Android instrumentation; user-supplied terminal output, 2026-11-14.**

This records the first test-only `JavaVM*` and Android looper callback integration on
a physical Samsung device. **It is not a Lorie renderer or production Xorg test.**
No claim is made that the production application loaded `libXlorie.so`.

## Pinned provenance

- Feature branch: `feature/android-x11-display-input`
- Source: `scripts/m8-android-composite-jni-diagnostic.cpp`
- JNI helper: `scripts/m8-renderer-jni-thread-env.h` and
  `scripts/m8-renderer-callback-jni-scope.h`
- Android test: `app/src/androidTest/java/io/github/jdawg867/anvildesk/m8/M8CompositeJniDiagnosticInstrumentedTest.java`
- Test target class: `M8CompositeJniDiagnostic`
- Diagnostic APK source commit: `dd8009d7b5a1bc714c11feeeaedcee5bdc59c003`
- Successful pinned PR workflow: `43561562356` (push `43561558516`)
- Archive: `m8-composite-jni-diagnostic-test-apks-arm64`, artifact ID `14100975634`
- GitHub artifact metadata digest:
  `sha256:fe3aaac5d1ab25fa350f9321732d5e30022d06d256cde39ae5835bf663af8fc1`
- GitHub artifact expiry: `2026-11-21T05:30:40Z`
- Gated runner: `scripts/run-m8-composite-jni-samsung-instrumentation.sh`
- Runner preparation and CI review: `6ab35e670cfa1f278ed717156e218278efbf5b42`
- SDK discovery fix commits: `2d231e1e1fbc4ab4c7a6d6c3856e8251de2929ef`,
  `3001dc2e1dfee865ea74f3dba7a88406ea64c2ac`
- Real device target: Samsung SM-G766U, `arm64-v8a` (checked by runner)
- Original app package: `io.github.jdawg867.anvildesk`
- Separate target and instrumentation packages:
  `io.github.jdawg867.anvildesk.m8jnidiag` and
  `io.github.jdawg867.anvildesk.m8jnidiag.test`

## User-provided physical-device output

The user ran the runner with `--run-on-device` after a successful
`--verify-artifact` preflight. The supplied output included:

```text
M8_ANDROID_AAPT_FOUND=PASS
M8_COMPOSITE_JNI_APK_ARTIFACT_PREFLIGHT=PASS
M8_COMPOSITE_JNI_TEST_ONLY_PACKAGE_ISOLATION=PASS
M8_COMPOSITE_JNI_DEVICE_EXECUTED=false
M8_NATIVE_LOAD_AUTHORIZED=false
Installing separate M8 diagnostic target package (NOT AnvilDesk)...
Performing Streamed Install
Success
Installing separate M8 diagnostic instrumentation APK...
Performing Streamed Install
Success
M8_COMPOSITE_JNI_SEPARATE_PACKAGES_INSTALLED=PASS
INSTRUMENTATION_STATUS: class=io.github.jdawg867.anvildesk.m8.M8CompositeJniDiagnosticInstrumentedTest
INSTRUMENTATION_STATUS: test=ownedLooperCallbackAttachesToRealJVMAndJoins
INSTRUMENTATION_STATUS_CODE: 0
Time: 0.026
OK (1 test)
INSTRUMENTATION_CODE: -1
M8_COMPOSITE_JNI_ANDROID_INSTRUMENTATION=PASS
M8_COMPOSITE_JNI_ORIGINAL_APP_PATH_UNCHANGED=PASS
M8_LORIE_CALLBACK_JNI_INTEGRATED=false
M8_NATIVE_LOAD_AUTHORIZED=false
```

The earlier preflight's `M8_COMPOSITE_JNI_DEVICE_EXECUTED=false` marker
describes that *preflight phase*, not the subsequent instrumentation.
The later `M8_COMPOSITE_JNI_ANDROID_INSTRUMENTATION=PASS` reports actual
instrumentation success.

## What this establishes

**PASS in this observed run:** the isolated JNI diagnostic library was loaded
by an explicitly opted-in `androidTest` harness; the diagnostic entered a
real Android looper callback worker and reported success through its JNI
function; the helper obtained a current-worker `JNIEnv*` from the real
`JavaVM*`, and the diagnostic coordinated callback completion, detachment,
and owner shutdown before releasing its test-only context.

The runner's original-app path check passed. It attempts to uninstall both
diagnostic packages on exit; the supplied output does **not** independently
show their final uninstall state. No app data, identifiers, secrets, or
private device logs are stored here.

**NOT VERIFIED:** concurrent Java Surface/renderer mutation; JNI global
reference deletion or pending JNI exception handling; Lorie's upstream
`LorieViewResources::xcallback`, `connect`, `nativeDestroy` or raw
`this` userdata; real Xorg native server stop/join/restart; production
AnvilDesk loading or upgrading. The diagnostic does not call any Java
methods from the callback thread.

The physical device success **does not change** the native-load gate:
`M8_LORIE_CALLBACK_JNI_INTEGRATED=false` and
`M8_NATIVE_LOAD_AUTHORIZED=false`.

## Next engineering gate

Audit and source-lock a real replacement for the pinned Lorie callback's
cross-thread stored `JNIEnv*` usage, resource/handle leases and destruction
order. Prove global-reference lifetime and JNI exception handling first.
Then review separately gated integration tests before any renderer or native
Xorg process is enabled. Draft PR #21 remains unmerged.
