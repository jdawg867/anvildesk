# Contributing

AnvilDesk favors small, reviewable changes and traceable dependencies.

## Workflow

1. Branch from `development` using `feature/<topic>`, `fix/<topic>`, or `docs/<topic>`.
2. Keep each branch focused on one logical change.
3. Add or update tests for behavior changes.
4. Open a pull request into `development`.
5. Security-sensitive changes should describe privilege, network, filesystem, and supply-chain impact in the PR.

`main` is reserved for release-ready integration and should not be used as the normal feature target.

## Dependency and binary policy

Do not add opaque binaries. Native libraries must identify their upstream source, exact revision, license, build process, and artifact digest. Download URLs used by the app must be HTTPS and integrity-pinned.
