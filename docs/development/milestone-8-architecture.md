# Milestone 8 — Android X11 display and input architecture

Status: **initial architecture checkpoint; no server implementation selected**.
Tracking: [issue #20](https://github.com/jdawg867/anvildesk/issues/20).
Base: `development` commit `f45b32c7ecd0cc010e63acab87e10cbd30b01f91`.

## Evidence inherited from M7

Samsung SM-G766U (Android API 36; ARM64; no root) has a verified
Ubuntu Base and working mutable XFCE desktop package set. M7's managed session
preview constructs `dbus-launch --exit-with-session startxfce4` with
`DISPLAY=:0`, but **does not execute the command or supply an X server**.
The X11 bootstrap utilities (`xauth`, `xdpyinfo`, `xset`) are clients,
not the host display server.

Do not disturb the original mutable rootfs or its isolated M7 clean-install
canary while building M8.

## Proposed trust boundaries

```text
Android AnvilDesk Activity
    ├─ explicit user start/stop, lifecycle and diagnostics
    ├─ Android drawing Surface + input event adapter
    └─ app-owned X11 server backend [selection pending]
           └─ private Unix-domain socket + authorization
                   ↕ restricted, fixed PRoot bind (not /tmp wholesale)
                 Ubuntu mutable rootfs
                   └─ dbus-launch --exit-with-session startxfce4
```

There must be no public TCP X11 listener, no generic shell prompt, no
unrestricted host directory bind, and no dependency on Android root.

## Decisions to resolve before executable integration

1. Select an actively maintained, ARM64 Android compatible **X server backend**
   with verifiable build source, licensing and pinned reproducible dependencies.
   Evaluate process integration, native libraries, surfaces, software
   rendering, clipboard/input exposure, X11 protocol compatibility, and
   deployment size. Existing utilities alone are insufficient.
2. Specify the host socket model (filesystem pathname versus abstract local
   socket). Android-local filesystem pathname with an app-private directory
   is preferred *provisionally* because it admits permissions checks and a
   narrowly scoped PRoot bind; confirm actual Android/PRoot compatibility.
3. Define authorization for X11 clients. Filesystem permissions alone are not
   treated as a substitute for an explicit X11 connection/authentication
   policy. Never use `xhost +` or TCP port 6000.
4. Define rendering transport from X server to Android `Surface`, including
   resize/orientation/viewport mapping, and input mapping for touch, pointer,
   scroll and keyboard. Do not assume a regular X11 display exposes rendered
   pixels without a backend-specific renderer.
5. Establish startup sequencing and crash cleanup. XFCE is launched only
   after the display is demonstrably ready, with bounded timeouts and explicit
   cancellation. No automatic session launch on app startup.

## Lifecycle state contract (initial)

`Idle → Preparing → StartingServer → WaitingForReady → Running → Stopping → Idle`

`Preparing`, `StartingServer` and `WaitingForReady` may fail to `Failed`;
`Running` may fail to `Failed` on server/guest exit. `Failed → Idle`
requires cleanup before a subsequent explicit start. A stop request from
any non-idle state cancels startup and closes the private transport.

Readiness requires checking the live server, a connecting guest X11
client, and the correct private display; file/socket existence alone is
insufficient. Unexpected termination must invalidate readiness.

## Security acceptance criteria

- Reject socket paths outside the dedicated app-private namespace or using
  unexpected symlinks; do not bind a user-supplied host path.
- Session directory creation and permissions are checked before server
  startup; shutdown does not recursively delete unknown paths.
- Display number and guest arguments are fixed/planned; no untrusted
  free-form command, display variable, or bind values.
- Apply authentication and deny external network transport by construction,
  tested rather than asserted.
- Use only explicitly enumerated small host binds for display socket and
  normal guest session home/temp; no broad /dev, /proc, /sys, /storage,
  /sdcard, /system, or /vendor mounts.
- Maintain verified base and mutable runtime provenance and preserve
  package manager state.
- Keep install, start, stop and recovery user-visible, opt-in, and distinct.

## Device acceptance

Run on SM-G766U without root. Prove one real X11 client connects; launch
`startxfce4`, render its visible desktop through the Android surface, and
check touch/pointer/key/scroll. Test rotation, disconnect, app background,
server crash, and clean stop. Verify socket is private, no TCP listener
exists, and production rootfs provenance remains unchanged. Capture
reproducible logs, signed APK SHA, CI, and on-device result. Only then merge.
