# Rootfs provenance

AnvilDesk does not install an unverified Linux filesystem image.

For the first supported image, AnvilDesk pins Ubuntu Base 24.04.5 ARM64 from Canonical's official `cdimage.ubuntu.com` release directory.

Pinned artifact:

- File: `ubuntu-base-24.04.5-base-arm64.tar.gz`
- Architecture: ARM64 / aarch64
- SHA-256: `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2`
- Checksum manifest: `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS`
- Detached signature: `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS.gpg`

The runtime must enforce HTTPS for the artifact and every redirect, stream the download into an app-private temporary file, verify the pinned SHA-256 before extraction, and delete the temporary file on any failure.

A manifest update that changes an artifact URL, digest, architecture, or provenance source is a security-sensitive code change and requires review.
