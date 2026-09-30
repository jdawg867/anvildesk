# PRoot runtime source provenance

AnvilDesk does not accept opaque prebuilt PRoot binaries.

## Integration source

- Project: `oonid/pr`
- URL: `https://github.com/oonid/pr.git`
- Pinned commit: `fcf25cb2396361f0be2edfc96fdd61a6e738c9d9`
- Source used: `src/proot/`
- Declared license for `src/proot/`: GPL-2.0-or-later
- License map at the pinned commit: `LICENSE`
- PRoot GPL text at the pinned commit: `src/proot/COPYING`

The source snapshot is used because it contains modern Android app-process
compatibility work including an APK-native PRoot loader path. AnvilDesk builds
the runtime itself and does not consume the repository's checked-in native
binaries.

## talloc build dependency

The reference build compiles Samba's talloc source into the host PRoot binary.

- Project: `samba-team/samba`
- URL: `https://github.com/samba-team/samba.git`
- Pinned commit: `2f8dfde1210395175e726455bdb63a7b97245a72`
- Source used: `lib/talloc/`
- `talloc.c` declares LGPL-3.0-or-later.

## Reference upstream gitlinks

The pinned `oonid/pr` snapshot records:

| Component | Commit |
| --- | --- |
| `proot-me/proot` | `5f780cba57ce7ce557a389e1572e0d30026fcbca` |
| `termux/proot` | `ab2e3464d04483b98a0614b470f3f8950d5a6468` |
| `samba-team/samba` | `2f8dfde1210395175e726455bdb63a7b97245a72` |

## Reproduction rule

`scripts/fetch-rootless-runtime-source.sh` fetches only HTTPS repositories,
checks out detached exact commits, and fails unless `git rev-parse HEAD`
matches the pins byte-for-byte.

The fetched source lives under ignored `build/third_party/` state and is not a
trusted build artifact by itself. Native build outputs must be produced by CI
and their hashes/provenance recorded before device testing.
