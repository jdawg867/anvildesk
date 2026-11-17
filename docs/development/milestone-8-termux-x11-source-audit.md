# M8 Termux:X11 source and native-build audit

Tracking: issue #20 / draft PR #21. This is a source inspection only;
**no binary has been compiled, installed, or run on Samsung**.

## Immutable upstream target

- Repository: https://github.com/termux/termux-x11
- Inspected upstream `master` commit:
  `fa3a8b430e2896a19f44c99a9cb056254615ae06`
- Git tree API returned this exact commit as the recursive tree revision.
- The 16 native Git submodule gitlink pins under
  `lorie/src/main/cpp/` are listed below.

| Path suffix | Submodule gitlink commit |
|---|---|
| bzip2 | `6a8690fc8d26c815e798c588f796eabe9d684cf0` |
| libepoxy | `c84bc9459357a40e46e2fec0408d04fbdde2c973` |
| libfontenc | `780d1f6f192a331de7b298a96e23ebc44d2a884b` |
| libtirpc | `5ca4ca92f629d9d83e83544b9239abaaacf0a527` |
| libx11 | `6c75545a1deb51f5903992c52af6bc35cc9bc103` |
| libxau | `a9c65683e68b3a4349afee5d7673b393fb924d2e` |
| libxcvt | `dd8631c61465cc0de5e476c7a98e56528d62b163` |
| libxdmcp | `1192d3bc407348ff316bd3bffc791b3ac73f591b` |
| libxfont | `975cc6526e892c6fad8fa49d0fef58565fbaa003` |
| libxkbfile | `42e5dedd7fd3c7c73f3870a8751893c03c1afc69` |
| libxshmfence | `89f064748554e11832a3ec783945e1f4c7fe846e` |
| libxtrans | `cf05ba4a10c90da2c63805a5375e983b174e28b0` |
| pixman | `9cc163c9da0fb4da430641715313d95a6ec466d9` |
| xkbcomp | `2c7789785981ad1fce3858c726615b49293f7de0` |
| xorgproto | `6d3907b6d5dc4aa7d7ee6ee5d7d8714e7466630b` |
| xserver | `65d790bd208ec380b196eb98f144abb0b32e334d` |

These are *gitlink identifiers* seen in the parent tree, not proof
that all submodule commits remain accessible or compile together.
Several submodules use gitlab.freedesktop.org and gitlab.com URLs, so
GitHub alone cannot establish complete source availability.

## Entry points and toolchain

Inspected pinned upstream files:
`settings.gradle`, `build.gradle`, `lorie/version.gradle`,
`lorie/build.gradle`, `lorie-app/build.gradle`,
`shell-loader/build.gradle`, `lorie/src/main/cpp/CMakeLists.txt`,
`lorie/src/main/AndroidManifest.xml`,
`lorie-app/src/main/AndroidManifest.xml`, and `.gitmodules`.

- Upstream Gradle modules: `:lorie` (Android library), `:lorie-app`
  (standalone/sharedUid host), `:shell-loader`, `:shell-loader:stub`.
  The `:lorie` module discovers the *single actual host project* from
  its Android application dependency graph to generate an APPLICATION_ID.
  **Potential adapter:** make AnvilDesk the single host project, but
  the module cannot simply be dropped into the existing project
  without reviewing dependencies, manifests, and generated assets.
- Upstream Android Gradle Plugin `9.3.1`; `compileSdk 34`,
  `minSdk 24`, `targetSdk 34` in `:lorie`.
  Pinned native NDK `29.0.14206865`; CMake minimum 3.22,
  C11/C++17; Python3 and Bison required at CMake configure time.
  This is **not** a verified match to AnvilDesk's existing Android
  Gradle Plugin/Gradle/JDK/NDK versions; check without an unrelated
  build-system upgrade.
- CMake includes native recipes for
  xorgproto, fontenc, md, pixman, tirpc, xshmfence, Xdmcp, Xau,
  Xfont2, xkbcomp, and xserver. This is a substantial X.Org build,
  not a small stand-alone JNI library.
- `:lorie-app` standalone and sharedUid variants exist.
  Upstream sharedUid variant lowers target SDK to 28 and its Gradle
  verification requires components to use the `com.termux` process.
  **Do not** use sharedUid mode in AnvilDesk.
- The `:lorie` library manifest declares INTERNET,
  FOREGROUND_SERVICE, POST_NOTIFICATIONS and WRITE_SECURE_SETTINGS,
  and contains activities, exported receivers and an accessibility
  service. Blind manifest merging would import behavior and permission
  surface outside M8 scope. AnvilDesk needs a reviewed minimal component
  manifest and explicit compatibility decisions before library inclusion.
- Upstream shell-loader expects a separately signed
  `com.termux.x11` package and embeds the `com.termux` application
  ID. This is **not** a viable AnvilDesk no-Termux integration unchanged.

## Current verdict

**Feasibility is plausible but not proven.** Treat the upstream
`:lorie` Android library and pinned native sources as the candidate
integration basis. The unmodified standalone APK, shared-UID flavor,
and shell-loader are not accepted AnvilDesk dependencies. Do not
bring source/binaries into AnvilDesk before the license audit,
manifest minimization, toolchain compatibility check, and socket
lifecycle design.

## Next gated engineering actions

1. Compare AnvilDesk's exact AGP, Gradle, Android min/target SDK,
   NDK, ABI filters and permission manifest against upstream.
2. Inspect `:lorie` native/JNI entry points plus Android
   `LorieView`, input bridge and socket launch path. Map which
   components can operate without other installed apps.
3. Audit GPLv3 compatibility and notices for each required upstream
   project; decide distribution strategy before bundling source.
4. Define a standalone app-private Unix-domain socket and Xauthority
   handshake without relying on hard-coded Termux socket directories.
5. Only then run an **isolated CI source-build spike** pinned to the
   specified commit and submodule refs. Its success must not change
   the existing signed-device APK or any Samsung runtime.


## JNI/socket bridge findings and mandatory security blocker

Pinned upstream source:
- `lorie/src/main/cpp/lorie/cmdentrypoint.cpp`
- `lorie/src/main/java/com/termux/x11/CmdEntryPoint.java`
- `lorie/src/main/java/com/termux/x11/LorieView.java`

The native `start()` launches `dix_main` on a pthread, with
`TMPDIR` driving `xtrans_unix_path_x11` / `xtrans_unix_dir_x11`
(`<TMPDIR>/.X11-unix/X`). This supports a candidate private
filesystem socket mapping but does **not** establish Android/PRoot
connectivity or Xauthority protection.

`LorieView` extends Android `SurfaceView`, forwards
`surfaceChanged` and `surfaceDestroyed` to JNI, and provides
native input/viewport methods. Its `connect(int fd)` path is
separate from the guest X11 socket and needs careful lifecycle mapping.

**Hard blocker at pinned upstream `cmdentrypoint.cpp`:
`lorieListenForKnocks()` creates `socket(AF_INET, SOCK_STREAM)`,
binds `INADDR_ANY`, and calls `listen(fd, 5)`. This is a TCP
control/notification port, not necessarily an X11 protocol listener,
but it still violates AnvilDesk's strict no-network-listener policy.
The upstream `CmdEntryPoint.java` uses broadcast-based service
discovery as part of this design, and its command-entry context probes
Android internal APIs. The unmodified server must never ship in M8.

**Integration decision:** replace the TCP knock/broadcast mechanism
with an app-owned in-process lifecycle notification (or a strictly
app-private Unix-domain control socket if isolation requires it), and
remove any optional network listening paths. Implement guarded
socket + Xauthority creation and a narrow PRoot bind. Only then
can the pinned native build move from evidence to an AnvilDesk
integration build. Do not silently weaken the no-TCP gate to
"loopback only".

The CI audit guard in `scripts/verify-m8-upstream-no-tcp.py` is a
negative control: it **must reject the unmodified pinned source**.
It is not proof that a subsequently patched server has no possible
network surface. That will require source review and runtime socket
inspection on the device.
