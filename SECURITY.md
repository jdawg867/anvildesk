# Security policy

AnvilDesk treats root execution, rootfs acquisition, native code, and release provenance as security-sensitive surfaces.

## Reporting a vulnerability

Please use GitHub's private security advisory feature for vulnerabilities that could expose user data, obtain unintended root execution, bypass rootfs verification, or compromise released artifacts. Do not publish exploit details in a public issue before a fix is available.

## Security invariants

- Root access is never requested automatically at app startup.
- Rootfs artifacts require a pinned SHA-256 digest before extraction.
- Cleartext network traffic is disabled by default.
- Remote services must default to loopback-only.
- Release APKs must not use the Android debug key.
- Bundled native binaries must have documented source provenance and reproducible build instructions before release.
