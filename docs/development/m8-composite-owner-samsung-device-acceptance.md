# M8 composite session-owner Samsung device acceptance

**Status: PASS — isolated physical-device smoke test, user-provided terminal output, 2026-11-13.**

This document records the standalone composite session-owner diagnostic only.
The executable does **not** contain or load the Lorie X11 renderer, JNI
integration, native Xorg server, rootfs, or the production AnvilDesk APK.

## Pinned build provenance

- Branch: `feature/android-x11-display-input`
- Device diagnostic source: `scripts/m8-android-composite-owner-device-probe.cpp`
- Source/build commit: `c26fc7d05d1b9be19c04bc1e6cb8517e53715698`
- GitHub Actions push build: `43537453762` (success)
- GitHub Actions PR build: `43537460896` (success)
- Artifact name: `m8-composite-owner-device-probe-arm64`
- Artifact ID: `14090591718`
- GitHub artifact metadata digest:
  `sha256:3708eef7fe09d81a54ceac495bd449fa6d21a58a986b847c0d03fd6f97df6c40`
- Artifact expiration: `2026-11-21T00:57:33Z`
- Gated runner: `scripts/run-m8-composite-owner-device-probe.sh`
- Runner/CI pin-check commit: `76d117447882b518ceb194c19ece7a2a68945b14`
- CI read-only preflight: pull-request workflow `43544960334`, success
- Physical target: Samsung SM-G766U, ARM64 (`arm64-v8a` checked by runner)

The published digest is GitHub's artifact metadata; the terminal output does
not demonstrate independent ZIP digest recomputation.

## Physical-device test result

The user fetched the exact feature branch runner, executed it with
`--run-on-device`, and supplied terminal output reporting a single ELF
transferred and the following markers:

```text
M8_ARTIFACT_ONLY_PREFLIGHT=PASS
M8_ANDROID_COMPOSITE_OWNER_DEVICE_TEST=PASS
M8_ANDROID_COMPOSITE_CONTEXT_RELEASE_AFTER_JOIN=PASS
M8_X11_RENDERER_INTEGRATION_VERIFIED=false
M8_NATIVE_LOAD_AUTHORIZED=false
M8_COMPOSITE_OWNER_DEVICE_PROBE_SMOKE_TEST=PASS
```

The first test markers appeared twice because the runner displays the
executable's stdout and then repeats matching assertions via `grep`; this is
**one** observed device run. No timeout, error, or failed test marker was
visible. The final runner marker shows the shell script reached its acceptance
check. The runner checks the phone model and ABI and executes the probe with
host and Android timeouts.

**Independent evidence limits:** Android API level, explicit final host exit
status and success of temporary remote-file cleanup were not independently
printed. Do not infer they were separately verified. No ADB device identifier,
personal data, secrets, or app contents are stored in this record.

## Acceptance scope and promotion blockers

**PASS:** isolated `RendererSessionOwnedLooper` + session callback
trampoline/context + real Android looper, using one anonymous pipe,
held callback, external shutdown, joined worker, then context cleanup.
This establishes behavior for the tested execution sequence and environment,
not a universal proof of Android callback quiescence for all races.

**NOT VERIFIED:** upstream Lorie `activity.cpp` integration; JNI thread
attachment/exception/global-reference lifetime; Java handle invalidation and
Surface concurrency; native server stop/join/restart; live renderer callback
safety. Do not interpret this result as authorization to load `libXlorie.so`,
modify the production APK, or merge draft PR #21.

Next integration gate: source-locked replacement of Lorie's thread-local
`ALooper_forThread()` + raw `this` userdata path, scoped callback leases,
and `JavaVM*`-based thread-affine JNI access. Independently test and audit
real nativeDestroy ordering and native server lifetime.
