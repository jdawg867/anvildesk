# AnvilDesk

**Secure, reproducible ARM64 Linux desktop runtime for Android.**

AnvilDesk is an open-source project to run a capable Linux userspace and, later, a full desktop environment on modern Android devices without sacrificing basic platform security or software provenance.

## Project status

AnvilDesk is in **Milestone 1: trusted runtime bootstrap**.

The first application build will:

- identify the device and Android API level;
- verify ARM64 capability;
- detect whether a root manager exposes an `su` binary;
- request root only after the user presses a button;
- verify that approved root access actually returns uid 0.

It deliberately does **not** download a Linux rootfs or execute arbitrary privileged commands yet.

## Principles

- Current stable Android SDK baseline; no deliberate legacy-target security bypass.
- Rootless operation is the preferred default path.
- Root support is explicit and narrowly scoped.
- HTTPS-only artifact acquisition.
- Mandatory SHA-256 verification before rootfs extraction.
- No opaque native binaries in release builds.
- No remote service exposed by default.
- Releases tied to exact source commits with published artifact hashes.

## Android baseline

- ARM64 first (`arm64-v8a` / `aarch64`).
- Minimum Android 9 / API 28 for the early development baseline.
- Compile/target Android 16 / API 36, the current stable SDK platform available through the standard SDK channel.
- Application ID: `io.github.jdawg867.anvildesk`.

AGP 9.4 can support API 37, but AnvilDesk will not move its compile/target baseline to API 37 until the corresponding stable SDK platform is generally available through standard `sdkmanager` installations and CI.

## Repository layout

```text
app/                   Android UI and application entry point
core/runtime/          Device and privilege/runtime boundary
rootfs/manifests/      Integrity-pinned rootfs metadata
docs/architecture/     Architecture decisions and roadmap
docs/security/         Threat model and security design
.github/workflows/     CI and build verification
```

See [the architecture overview](docs/architecture/overview.md), [threat model](docs/security/threat-model.md), and [build guide](docs/development/build.md).

## License

AnvilDesk is licensed under the GNU General Public License v3.0. Third-party components retain their own licenses and must have documented provenance before they are distributed with AnvilDesk.
