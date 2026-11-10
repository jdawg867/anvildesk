# M8 Samsung owned-looper device acceptance gate

**Status: NOT PASSED.** The owned-looper executable is built and validated in
GitHub Actions, but no physical Android device execution has been recorded.
Neither a host looper shim nor an Android ARM64 link test is a substitute.

## Provenance

- Draft PR: #21, branch `feature/android-x11-display-input`
- Verified artifact build: GitHub Actions #808, commit
  `871264cc1872d980b5e59f5ab71ac1a70bf7b9a5`
- Artifact: `m8-owned-looper-device-probe-arm64`
- Artifact ID: `13716750305`
- Artifact retention end: 2026-11-16T22:25:10Z
- Artifact-only CI acceptance: #822,
  `M8_ARTIFACT_ONLY_PREFLIGHT=PASS`,
  `M8_ANDROID_DEVICE_PROBE_EXECUTED=false`
- Device target: Samsung SM-G766U, Android ARM64
- Runner: `scripts/run-m8-owned-looper-device-probe.sh`

## Execution boundary

**Artifact-only verification** (no phone required):

```bash
bash scripts/run-m8-owned-looper-device-probe.sh --verify-artifact
```

**Explicit physical-device execution** (only on the authorized host with
exactly one online ADB device, the Samsung SM-G766U):

```bash
bash scripts/run-m8-owned-looper-device-probe.sh --run-on-device
```

The runner refuses an incorrect/expired artifact, unknown device model, wrong
architecture, missing Android `timeout`, and unacceptable ELF dependencies.
It transfers only a standalone test ELF to `/data/local/tmp`, executes it with
a bounded timeout, and attempts to remove it afterward. It does not install
an APK, alter AnvilDesk's mutable Ubuntu rootfs, change the production app,
or load the X11 renderer.

**Required output from actual device execution**:

```text
M8_ANDROID_OWNED_LOOPER_DEVICE_TEST=PASS
M8_X11_RENDERER_INTEGRATION_VERIFIED=false
M8_NATIVE_LOAD_AUTHORIZED=false
M8_DEVICE_PROBE_SMOKE_TEST=PASS
```

Record: device model, API level, ABI, exact artifact ID/source SHA, test output,
ADB exit status, and whether temporary-file cleanup succeeded. Do not record
device serials, authentication material, user app data, or Linux rootfs contents.

## Failure policy and promotion gate

- Any timeout, crash, missing marker, mismatch, or uncertain cleanup = **FAIL**.
- An expired artifact requires a new verified build and updated pinned runner,
  never a fallback to a different unverified executable.
- Passing this probe proves only a minimal single-FD owned-looper callback and
  shutdown sequence on the tested device.
- It does **not** prove the upstream Lorie renderer/JNI lifecycle, callback
  userdata behavior for other loopers, production process isolation, or
  Samsung end-to-end X11 functionality.
- Keep `libXlorie.so` loading disabled and PR #21 in draft until those
  separate acceptance gates have evidence and explicit approval.
