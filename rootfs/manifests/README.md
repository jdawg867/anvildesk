# Root filesystem manifests

AnvilDesk never installs a root filesystem from a bare URL.

Every supported image must have a reviewed manifest conforming to `schema-v1.json`. The manifest records the upstream source page, HTTPS artifact URL, architecture, and exact SHA-256 digest. If upstream provides a detached signature, the signature metadata should also be recorded and verified.

A digest mismatch is a hard failure. The app must delete the untrusted download and must not offer a bypass button.

No production manifest may contain placeholder hashes or point at an unofficial mirror without an explicit security review.
