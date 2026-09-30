# Rootless runtime architecture

Milestone 3 executes the verified Ubuntu ARM64 rootfs on non-root Android while
preserving AnvilDesk's current trust boundary.

## Android execution boundary

AnvilDesk targets modern Android. Android 10+ removes execute permission for
code stored in an app's writable home directory. The verified rootfs under
`files/rootfs/installed/...` therefore remains **data** and is never used as a
host executable location.

Executable host-side runtime components must be packaged in the APK and
installed by Android into the app native-library directory. The initial design
packages a PRoot host executable and PRoot loader as native library payloads
under `lib/arm64-v8a/`, then launches the host runtime from
`ApplicationInfo.nativeLibraryDir`.

Guest binaries remain inside the verified Ubuntu rootfs. PRoot translates the
guest's filesystem/syscall view and uses its packaged loader for guest exec
transitions.

## First backend

The first non-root backend is a source-built PRoot-compatible runtime derived
from the Android compatibility work in `oonid/pr`.

Pinned integration reference:

- Repository: `https://github.com/oonid/pr.git`
- Commit: `fcf25cb2396361f0be2edfc96fdd61a6e738c9d9`
- Relevant source: `src/proot/`
- Reference Android compatibility document:
  `docs/targetsdk35-compatibility.md`

The reference snapshot records these upstream gitlinks:

- `proot-me/proot`: `5f780cba57ce7ce557a389e1572e0d30026fcbca`
- `termux/proot`: `ab2e3464d04483b98a0614b470f3f8950d5a6468`
- `samba-team/samba`: `2f8dfde1210395175e726455bdb63a7b97245a72`

AnvilDesk does not import the reference application's UI, distro manager,
terminal, BusyBox payloads, prebuilt `.so` files, or release artifacts.

## Runtime security rules

1. No downloaded executable is launched from writable app storage.
2. Runtime/loader code is built from pinned source in CI.
3. The guest root must already have a verified AnvilDesk install record.
4. Initial commands are fixed smoke tests, not arbitrary user shell input.
5. Guest binds are explicit and minimal.
6. Root is never requested by the rootless backend.
7. Every launch has a timeout and process cleanup.
8. stdout, stderr, and exit status are captured as data.
9. No network listener is started by Milestone 3.

## Planned smoke test

The first device proof is a fixed Ubuntu command such as:

```text
/usr/bin/uname -a
```

The app should report the command, stdout, stderr, exit code, and elapsed state.
A successful smoke test is not a claim of kernel/container isolation: PRoot is a
userspace path/syscall translation layer using the Android host kernel.
