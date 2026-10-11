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


## Initial backend comparison — research checkpoint

**Candidate A: Termux:X11 architecture (leading reference, not yet selected).**
Upstream: https://github.com/termux/termux-x11 . Upstream describes
a complete Android NDK-based X server, Android 8+ support, a dedicated
Android app plus companion Termux package, a Git-submodule-based source
tree, and GPLv3 licensing. This is evidence of a mature integration
reference, **not** evidence that an unmodified upstream binary can be
embedded within AnvilDesk's process/package namespace. A direct port or
source-derived integration must establish license compatibility,
reproducible ARM64 native compilation, JNI/Surface lifecycle, guest socket
ownership, and isolation without Termux's package/shared UID assumptions.

**Candidate B: XServer XSDL (alternative reference).**
Upstream: https://github.com/pelya/xserver-xsdl . The project ports an
X.Org server using SDL, but its README reports lack of XRANDR support
and problematic text input, and its Android build uses a patched SDL
tree. Those are significant risks to M8 rotation and input criteria.
Do not select it solely because it displays X11 content.

**Candidate C: a managed, localhost-only VNC display backend.**
A VNC server rendering an X11 desktop plus an embedded Android viewer
could simplify framebuffer/input integration and retain XFCE in a private
guest runtime. However it introduces a distinct VNC protocol and display
server, explicit loopback transport, authentication and lifecycle design,
and may violate the no-TCP-listener gate if implemented naively.
Treat only as a contingency after evaluating Unix socket capability.

### Decision

Proceed with a **Termux:X11 upstream source/design audit** before
selecting or shipping any binary. Determine whether X server protocol
and framebuffer components can run in-process under AnvilDesk, using
only app-owned sockets and no additional Termux app/package. Do not
copy nightly APKs or promise embedding without a reproducible build and
GPLv3 obligations review. Keep the app's M7 session command unchanged
until the server path is demonstrably ready.

### Validation questions for next checkpoint

- Can the upstream native server/JNI components compile for our pinned
  NDK and the app's minSdk/targetSdk without another app UID?
- Can we direct the display socket into AnvilDesk's app-private
  filesystem with secure ownership and narrow PRoot guest mapping?
- Does it provide usable direct Android Surface rendering and actual
  touch/keyboard/scroll mapping without custom invasive patches?
- What GPLv3 source-distribution and dependency license obligations
  would apply to an AnvilDesk binary distribution?
- Can automated tests verify there is no AF_INET/AF_INET6 X11 listener,
  and can the Samsung prove real X11 client connection and cleanup?

External-source findings above reflect the upstream project READMEs;
they do not assert that the AnvilDesk integration already works.


## Termux:X11 upstream README audit — integration constraints

Source reviewed: https://github.com/termux/termux-x11 (upstream README,
2026-10-10). This is a **source-level compatibility assessment**, not a
successful AnvilDesk build.

Confirmed from upstream documentation:

- It is an Android NDK-built X server with an Android app and a separate
  Termux-side companion package in the standard deployment.
- The repo requires recursive Git submodules; a top-level checkout alone is
  insufficient for a reproducible native build.
- Proot deployments use a **shared /tmp** (or mapped TMPDIR), directly
  relevant to AnvilDesk's restricted session temp bind. The socket mapping
  must be explicit and checked, not implemented by exposing Android /tmp.
- A distinct shared-UID APK variant is offered for Termux integration, with
  signature-matching restrictions; AnvilDesk cannot assume it can share
  Termux's UID.
- Upstream documents launch from third-party apps, but that does not prove
  support for in-process embedding.
- Upstream supports tap/scroll gestures and DPI customization, which can
  inform our Android input UX, though actual integration remains unverified.
- Published license is GPLv3. Reuse/copying is gated on satisfying license
  and source distribution requirements.

**Gate result:** research is sufficient to justify examining the upstream
modules and build system, but insufficient to approve an X server binary for
AnvilDesk. Next audited deliverable must pin a specific upstream commit and
submodule SHAs; map Android app/service/JNI/native ownership; specify how the
server is started and stopped with private socket, no independent Termux
installation or UID, and no TCP X11 listener; then validate ARM64 compilation
with the pinned toolchain.


## Private socket and Xauthority preflight (M8 development checkpoint)

The Android runtime module now contains non-launching safety components:
- `M8PrivateDisplayContract` checks a fixed app-private `m8-display` namespace and readiness/lifecycle transitions.
- `M8PrivateSocketWorkspace` prepares `m8-display/tmp/.X11-unix` with owner-only directory permissions and refuses existing session/socket paths.
- `M8X11LaunchGate` writes a fresh 16-byte MIT-MAGIC-COOKIE-1 credential in an Xauthority binary record, without shelling out, and plans `:0 -nolisten tcp -auth <app-private authority>`.

**Unfinished integration boundary:** The rootless PRoot guest and the native X server must see the same socket directory. The host's `m8-display/tmp` must be selectively visible to the guest as `/tmp`, with no overwrite of the existing M7 mutable rootfs. The guest must receive the same authentication record via an intentionally scoped read-only bind and matching `XAUTHORITY` environment variable. Validate that the chosen PRoot bind syntax, guest identity and Xauthority path actually work; current planning helpers do not establish that.

A successful future acceptance test must confirm all of the following on a physical Android device:
1. Modified native server is running inside AnvilDesk, with no listening TCP sockets.
2. The filesystem Unix-domain socket exists at the expected private path and is usable by the guest.
3. An unauthenticated X11 request is rejected.
4. A guest `xdpyinfo -display :0` using the scoped authority succeeds.
5. Native server teardown and a second session start work without stale sockets.
6. Existing M7 production and isolated runtime provenance records remain unchanged.

No M8 runtime test or Android APK installation has yet proven these conditions. Do not advertise the XFCE session as ready.


## M8 merge gate — explicit status after authentication and guest probe planning

Implemented on the M8 feature branch, but not yet wired to a running Android-native X server:

- Disposable pinned-upstream compile patch removes its `INADDR_ANY` TCP notification listener; the isolated upstream compilation workflow has passed.
- `M8PrivateSocketWorkspace` validates owner-only app-private Unix socket directories without deleting existing state.
- `M8X11LaunchGate` creates a new owner-private MIT-MAGIC-COOKIE-1 Xauthority file and plans a `-nolisten tcp -auth` native launch.
- `M8GuestDisplayBridge` composes PRoot guest home/temp mounts **before** narrower socket-directory and authority-file mounts, and refuses missing guest bind targets.
- `RootlessRuntimePlanner.m8AuthenticatedDisplayProbe` plans a shell-free `/usr/bin/xdpyinfo -display :0` process with the restricted environment.

**Not demonstrated and required before merge:**
1. Embed/build the patched upstream native X11 implementation in the AnvilDesk APK; the current upstream compile workflow only builds an isolated upstream app and stores diagnostic artifacts.
2. Replace its broadcast/Termux-specific entry point with an AnvilDesk-owned native server lifecycle and Android Surface/input channel.
3. Ensure `xdpyinfo` and validated bind targets exist in a disposable/isolated guest without modifying M7 production or isolated validation environments.
4. Demonstrate on a physical rootless Android device that an authenticated guest handshake succeeds, an unauthenticated handshake fails, the X11 socket is private, no network listeners exist, and teardown/restart is clean.
5. Review upstream GPLv3 and all transitive licensing/source-distribution obligations, as well as merged Android manifest components and permissions.
6. Confirm all required GitHub Actions checks green for final head and record device results in PR #21.

**Merge decision:** keep PR #21 draft and unmerged until the above acceptance evidence exists. Source compilation, planner tests, and static audit alone are not a substitute for live device validation.
