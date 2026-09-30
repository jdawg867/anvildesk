# Security review checklist

Use this checklist for changes that touch root, downloads, filesystem extraction, native code, networking, or releases.

## Privilege
- Is root access necessary for this change?
- Is the root request triggered by an explicit user action?
- Are privileged commands fixed or structured rather than built from untrusted strings?

## Network
- Are all remote artifact URLs HTTPS?
- Does any new listener bind beyond loopback?
- Is authentication required before remote exposure?

## Filesystem
- Can downloaded or archive-controlled paths escape the intended runtime directory?
- Are symlinks, device nodes, and traversal entries handled deliberately?

## Supply chain
- Is every downloaded or bundled artifact integrity-pinned?
- Is upstream source/revision/license documented for native binaries?
- Does the release artifact map to an exact commit/tag?

## Android permissions
- Is any new permission strictly required?
- Could the feature work with a narrower scoped-storage or platform API instead?
