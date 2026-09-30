# Architecture overview

AnvilDesk is an ARM64-first Linux runtime for Android with a security model built around traceable source, explicit privilege boundaries, and reproducible releases.

## Modules

### `app`
The Android application and user-facing control plane. It should contain as little privileged logic as possible.

### `core/runtime`
Device capability detection and runtime control. Root access is never requested automatically; a user action must initiate privilege verification.

### `rootfs/manifests`
Versioned metadata for supported Linux root filesystems. Downloads are accepted only after cryptographic digest verification and, when available, upstream signature verification.

## Planned runtime modes

1. **Rootless mode** — app-private userspace without requiring device root.
2. **Root/chroot mode** — optional higher-capability runtime using a user-approved root manager. Privileged commands will be narrowly scoped and visible in code.

Rootless support is the preferred default. Root support is an explicit capability, not a requirement for launching the app.

## Milestone sequence

1. Device inspection and explicit root capability verification.
2. Verified rootfs acquisition and safe extraction.
3. Minimal command execution inside the installed Linux userspace.
4. Persistent runtime lifecycle and recovery.
5. X11/display integration.
6. Desktop environment and GPU/audio integration.
7. Signed, reproducible release pipeline.

## Non-goals

AnvilDesk will not hide privilege escalation, download executable code from untracked locations, expose remote shell/VNC services by default, or lower the Android target SDK to bypass modern platform security behavior.
