# Milestone 2 — verified rootfs provisioning

Milestone 2 establishes a secure acquisition and installation path for an ARM64 Linux root filesystem without requiring root.

## Trust chain

1. Rootfs metadata is pinned in the repository.
2. Artifact, provenance, checksum, and signature URLs must use HTTPS.
3. Every redirect is inspected and must remain HTTPS.
4. The archive is streamed into an app-private temporary file.
5. SHA-256 is computed while downloading and compared to the pinned digest.
6. An unverified archive is deleted and is never extracted.
7. Extraction occurs only into a fresh app-private staging directory.
8. Archive paths and link targets are constrained to the staging root.
9. Entry count and uncompressed byte limits reduce archive-bomb risk.
10. An install record is written and fsynced inside staging.
11. The staging root and its metadata are promoted together with an atomic same-filesystem rename.
12. Failed installs delete their staging tree without following symbolic links and cannot replace an existing installed rootfs.

## Initial rootfs

Ubuntu Base 24.04.5 ARM64 is pinned from Canonical's official cdimage release directory. The expected SHA-256 is recorded in `rootfs/manifests/ubuntu-24.04.5-arm64.json` and matches Canonical's published `SHA256SUMS` manifest.

## Deliberate exclusions

This milestone does not execute downloaded files, expose a shell, request root, create network listeners, or start a desktop environment.
