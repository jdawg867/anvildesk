# Roadmap

## Milestone 1 — Trusted runtime bootstrap
Device inspection, ARM64 validation, explicit root capability verification, unit tests, and CI.

## Milestone 2 — Verified rootfs provisioning
Download only from reviewed HTTPS sources, verify pinned SHA-256 digests, validate archive paths, and extract into app-private storage.

## Milestone 3 — Linux command runtime
Launch a minimal shell inside the provisioned userspace, capture output safely, and define the command-execution API without exposing arbitrary privileged shell interpolation.

## Milestone 4 — Runtime lifecycle
Start/stop state, persistence, recovery after interrupted installs, health checks, and cleanup.

## Milestone 5 — Display stack
Integrate an auditable X11/display path and connect the Linux userspace without weakening Android security policy.

## Milestone 6 — Desktop integration
XFCE or another lightweight desktop, keyboard/mouse handling, audio, clipboard, storage sharing, and optional GPU acceleration.

## Milestone 7 — Production release pipeline
Release signing, provenance records, dependency/native-source manifests, published artifact hashes, reproducibility checks, and stable update policy.
