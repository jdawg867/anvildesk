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
