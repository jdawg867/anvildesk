# Release policy

AnvilDesk release artifacts must be traceable to exact source.

## Required release properties

1. A release is cut from `main` at an annotated version tag.
2. CI builds the release artifact from that tag; hand-built APKs are not published as official releases.
3. Release APKs use the project release key, never the Android debug key.
4. SHA-256 digests for every published artifact are included with the release.
5. Bundled native binaries must have documented source revision, license, build instructions, and digest.
6. Rootfs images are not silently replaced behind an existing manifest. A changed artifact requires a new reviewed manifest and digest.
7. Reproducibility differences must be investigated before a release is called reproducible.

Milestone builds may be unsigned debug artifacts for testing, but they must never be presented as production releases.
