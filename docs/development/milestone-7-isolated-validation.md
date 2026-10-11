# Milestone 7: isolated Android clean-install acceptance

## Purpose

Validate that the current `xfce-desktop-core-v1` installer works against a **new mutable clone** of the pinned verified Ubuntu Base on the target ARM64 Android device, without relying on manual `dpkg --configure` recovery.

GitHub CI compiles and tests the implementation; CI passing alone is **not** physical-device acceptance.

## Isolation

The test creates its own app-private namespace under
`files/m7-isolated-validation-v1/` with a separate `MutableRuntimeStore`.
The normal working runtime stays at `files/runtime/mutable/ubuntu-24.04.5-arm64/`.
The test also uses separate home/temp/cache, and does not clear Android app data,
remove the working `.l2s` tree, or write to the verified Ubuntu Base.

A prior test namespace is not reused. Do not manually delete the retained
namespace until its evidence is reviewed. The test enforces a 2 GiB free-space
preflight, but actual space usage and duration depend on packages and device.

## Steps

1. Build the branch in GitHub Actions with stable development signing; verify
   CI and signing and perform a guarded in-place `adb install -r` upgrade.
2. Verify the working runtime's `runtime.properties` creation timestamp and
   presence of `.l2s` and `startxfce4` before and after upgrade.
3. On Samsung, select **Run isolated XFCE install test** and explicitly approve
   the operation. It may download significant amounts of Ubuntu package data.
4. Read the test result on screen and retain
   `files/m7-isolated-validation-v1/validation-report.txt` for analysis.
5. PASS requires `apt-get update` and `apt-get install` exit 0, eight
   exactly allowlisted XFCE package rows with installed status, the nine required
   binaries, fixed managed-session preview, and no incomplete packages in the
   known recovery chain. No X server or display transport is started.
6. Recheck production runtime provenance and installed XFCE binaries afterward.

## Failure handling

Any failed stage is a failed acceptance test. Preserve the canary and log;
diagnose and fix the managed installer in a new build rather than manually
repairing the canary and claiming clean-install success. Keep PR #19 draft.

This test does not claim to cover all Ubuntu packages, every Android device,
future repository package revisions, or an actively running graphical session.


## Samsung acceptance evidence — 2026-10-10 (America/New_York)

Test device: Samsung SM-G766U, Android API 36, ARM64, rootless.
Trusted stable-development-signed push CI run: 38095206331 on commit
`092774a3b6573b9788af1dded2123c68edac03e1`; associated PR
workflow run 38095209619 also passed. The distributed APK's SHA-256 was
`9f8c7cb8f85080ac75b3b33053c4ea0fc3b9843ed55b608e0f315a0a7f5e645f`.

An in-place `adb install -r` returned `Success`. The production mutable
runtime retained its prior `createdAtEpochMillis=1791166903698`,
`.l2s` directory, and `/usr/bin/startxfce4` before and after upgrade.

The user executed **Run isolated XFCE install test** and supplied the
retained `validation-report.txt`. Observed stages:

- `apt-get update exitCode=0 timedOut=false`; the test fetched 37.3 MB
  of Ubuntu package metadata.
- `apt-get install exitCode=0 timedOut=false`, without manual dpkg repair.
  Reported nonfatal messages included debconf delaying configuration because
  apt-utils was absent, unavailable init runlevel, and missing alternative
  manpages.
- `dpkg-query verification exitCode=0 timedOut=false`; the eight required
  packages were reported installed (`ii`): dbus-x11 1.14.10-4ubuntu4.1,
  thunar 4.18.8-1build3, xfce4-appfinder 4.18.0-1build2, xfce4-panel
  4.18.4-1ubuntu0.1, xfce4-session 4.18.3-1build2, xfce4-settings
  4.18.4-0ubuntu3, xfdesktop4 4.18.1-1build3, xfwm4 4.18.0-1build3.
- The test reported the required guest binaries and managed session preview
  passed, finishing with the exact line `RESULT=PASS`.
- A separate ADB post-test check confirmed `PRODUCTION_RUNTIME=PASS`,
  production timestamp unchanged, and `ISOLATED_RUNTIME=PASS`;
  both `.l2s` directories and `startxfce4` binaries were present.

Conclusion: clean M7 package installation succeeds on this device with a fresh
isolated mutable clone, no manual repair, and preservation of the production
runtime. This does **not** validate a running X11 desktop (reserved for M8),
nor broad reproducibility across other devices/Ubuntu mirrors.
