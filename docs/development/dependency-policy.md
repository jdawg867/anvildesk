# Dependency policy

AnvilDesk keeps the dependency surface intentionally small.

- Prefer Android/JDK platform APIs when they are sufficient.
- Pin build plugins and runtime dependencies to exact versions; do not use `+` or other dynamic versions.
- Do not add opaque binary dependencies without documented source provenance.
- Review dependencies that execute during the build with the same care as runtime code.
- Rootfs artifacts are data inputs governed by the rootfs manifest policy, not ordinary unverified downloads.
- Remove dependencies that are no longer used.

Security-sensitive dependency upgrades should note relevant upstream release/security information in the pull request.
