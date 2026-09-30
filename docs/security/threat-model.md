# Threat model

AnvilDesk can eventually manage a full Linux userspace and, on rooted devices, may execute narrowly scoped commands as uid 0. That makes supply-chain integrity and privilege boundaries primary design requirements.

## Assets we protect

- Android application data and user files.
- Root manager authorization.
- Linux root filesystem integrity.
- Release signing identity.
- Network-facing services and credentials.

## Primary threats

### Malicious or replaced rootfs archive
Mitigation: HTTPS is required, a pinned SHA-256 digest is mandatory, upstream signatures are verified when available, and extraction never begins after a failed verification.

### Archive path traversal
Mitigation: extraction code must canonicalize every output path and reject absolute paths, `..` traversal, device nodes, or any entry escaping the designated runtime directory unless a reviewed format explicitly requires it.

### Excessive root authority
Mitigation: root is requested only after an explicit user action. Privileged operations live behind a small audited interface. Arbitrary UI strings, downloaded content, and network input must never be interpolated into a root shell command.

### Unintended network exposure
Mitigation: remote desktop, SSH, audio, debugging, and similar listeners must bind to loopback by default. Remote exposure requires an explicit opt-in with authentication.

### Release/source mismatch
Mitigation: releases must identify an exact Git tag and commit, publish artifact digests, and use a project release key rather than Android debug signing.

## Android baseline

- Current target SDK rather than a deliberately old target SDK.
- Cleartext traffic disabled.
- No broad storage permission by default.
- No SMS, contacts, microphone, camera, call-log, or location access unless a future feature has a documented need and security review.
- No automatic root prompt at application launch.
