# Milestone 1 — Trusted runtime bootstrap

## Goal

Prove that AnvilDesk can identify a supported Android device and, when the user explicitly requests it, verify root capability without silently performing privileged work.

## Exit criteria

- App builds for API 37 and installs on an ARM64 Android device.
- Device model, API level, primary ABI, and ARM64 capability are displayed.
- Root presence detection does not itself invoke `su`.
- Root verification only runs after a user action.
- Successful verification requires `id -u` to return exactly `0`.
- No Linux rootfs is downloaded in this milestone.
- No arbitrary root shell command interface exists.
- Unit tests cover ABI and root uid parsing.
- CI produces a debug APK from source.

## Next milestone

Milestone 2 will add integrity-pinned rootfs acquisition, SHA-256 verification, and safe extraction into app-private storage. Rootfs installation must fail closed on any digest or path-validation error.
