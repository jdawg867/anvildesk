# M8 unified-owner Samsung device probe — preparation

**Status: ARM64 CI BUILD PASS; DEVICE EXECUTION NOT VERIFIED.**

This isolated probe is intentionally separate from the AnvilDesk APK,
Lorie X11 renderer, JNI, and native server. It uses an anonymous pipe,
a dedicated Android looper worker, and heap callback userdata. The
callback remains blocked until the caller allows it to finish; after
`shutdownAndJoin()` succeeds the adapter authorizes userdata cleanup.
There is no root, network, rootfs, renderer, or APK installation.

## Pinned provenance (2026-11-12)

- Source branch: `feature/android-x11-display-input`
- Source commit: `8a9767971fd21689bd16e70b4c8a4510f737779a`
- GitHub Actions push run: `43319679610` (success)
- GitHub Actions PR run: `43319689835` (success)
- Artifact name: `m8-unified-owner-device-probe-arm64`
- Artifact ID: `13989353189`
- GitHub-provided artifact digest:
  `sha256:bbac900b551b143add444fb0904fb37af2a5af2254415a4695a90ab6273288c7`
- Artifact expires: `2026-11-19T22:25:59Z`
- Test executable: `m8-android-unified-owner-device-probe`
- Runner: `scripts/run-m8-unified-owner-device-probe.sh`
- Device target: Samsung SM-G766U, `arm64-v8a`

The artifact metadata is GitHub's published metadata, not an
independently recomputed ZIP digest.

## Read-only artifact preflight

On the authorized development workstation, before connecting a device:

```bash
cd "$HOME/work/projects/anvildesk"
git fetch origin feature/android-x11-display-input
git show origin/feature/android-x11-display-input:scripts/run-m8-unified-owner-device-probe.sh \
  > /tmp/run-m8-unified-owner-device-probe.sh
bash /tmp/run-m8-unified-owner-device-probe.sh --verify-artifact
```

This validates the pinned GitHub workflow SHA, successful run, artifact
name/ID/digest/expiration and ELF header/linker/dependencies. The runner
rejects a mismatch rather than replacing an expired artifact silently.

## Explicit Android test (not performed yet)

Once authorized for physical-device testing, with exactly one online ADB
device (Samsung SM-G766U):

```bash
bash /tmp/run-m8-unified-owner-device-probe.sh --run-on-device
```

The runner bounds execution with host and device timeouts, uses only
`/data/local/tmp`, and attempts to remove the transferred ELF afterward.
Success requires all of:

```text
M8_ARTIFACT_ONLY_PREFLIGHT=PASS
M8_ANDROID_UNIFIED_OWNER_DEVICE_TEST=PASS
M8_ANDROID_UNIFIED_OWNER_POST_JOIN_HEAP_FREE=PASS
M8_X11_RENDERER_INTEGRATION_VERIFIED=false
M8_NATIVE_LOAD_AUTHORIZED=false
M8_UNIFIED_OWNER_DEVICE_PROBE_SMOKE_TEST=PASS
```

Record: device model, Android API level, ABI, source commit, artifact ID,
exit status, exact output, and successful cleanup, excluding serial numbers.

## Limits and blocking gates

- CI proves cross-compilation and host-test behavior, **not** real
  Android callback quiescence.
- The standalone executable still requires physical Samsung execution.
- Even a passing device probe does **not** validate upstream Lorie
  renderer callback ownership, JNI thread attachment, or native Xorg
  process shutdown/restart.
- Do not enable `libXlorie.so`, promote M8, or merge draft PR #21
  solely on the strength of this probe.
