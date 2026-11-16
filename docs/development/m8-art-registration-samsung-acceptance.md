# M8 ART registration diagnostic — Samsung acceptance gate

Status: **packaging/preflight only**, NOT authorized for production native loading.
Repository: `jdawg867/anvildesk`, feature branch `feature/android-x11-display-input`,
draft PR #21. Real ART execution is **not yet confirmed**.

## Separation

The dedicated Gradle flag `-PanvildeskM8ArtRegistrationDiagnostic=true`
changes the test target application ID to
`io.github.jdawg867.anvildesk.m8artdiag`, and the androidTest ID to
`io.github.jdawg867.anvildesk.m8artdiag.test`. Only the instrumentation
APK contains `libm8-art-registration-diagnostic.so`; the target APK must
not contain it. This is distinct from the installed production package
`io.github.jdawg867.anvildesk`. The diagnostic has no `JNI_OnLoad`
and is not the Lorie library.

## GitHub gate (no local checkout)

Wait for successful **M8 opt-in ART registration APK isolation** on the
exact intended commit in GitHub Actions. The workflow retains the APK pair
for three days, without deploying to a device. Record the run ID, GitHub
head SHA, and artifact digest. Do not reuse older unverified artifacts.

For an ADB-capable workstation, fetch the read-only verification script from
GitHub at the exact reviewed SHA. Use it to verify artifact provenance,
manifest, package identity, ARM64 architecture, JNI symbol, and absence of
the test library from the target APK:

```bash
# Fill values from the successful run; do not guess.
RUN_ID=<verified_run_id>
SHA=<full_40_character_head_sha>
gh api "repos/jdawg867/anvildesk/contents/scripts/verify-m8-art-registration-test-apks.sh?ref=$SHA" \
  --jq .content | base64 -d > /tmp/verify-m8-art-registration-test-apks.sh
bash /tmp/verify-m8-art-registration-test-apks.sh "$RUN_ID" "$SHA"
```

This command is **read-only**: it does not invoke ADB, install APKs, execute
JNI, or modify the phone. If provenance verification fails, stop.

## Future device gate (not yet authorized)

Before a separate opt-in `--run-on-device` procedure is introduced,
validate a single ADB serial, the Samsung `SM-G766U` model, supported
`arm64-v8a` ABI, API level, isolated signed APK pair, and absence of
conflicting diagnostic package installations. Preserve the existing phone
package and user data. Never `adb uninstall` the production package,
grant root privileges, install `libXlorie`, or invoke a native diagnostic
automatically during routine app startup.

The ART instrumentation test
`M8ArtRegistrationInstrumentedTest.rejectedRegistrationRecoversAndUnregisters`
has an explicit opt-in argument `m8ArtRegistrationDiagnostic=true`;
without it, the test skips. Even if it passes, that is only a diagnostic
result for ART registration behavior, not authorization to promote M8
native loading or merge PR #21.
