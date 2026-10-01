# Stable development APK signing

AnvilDesk uses a dedicated **development-only** Android signing certificate for CI device-test APKs. It must never be reused as a production/release key.

## Established development certificate

Public SHA-256 certificate fingerprint:

```text
67F139BCA36B618BD470D567ECE87C477C23788A7EDAC4D493DCBB9A3AB78065
```

This fingerprint is public metadata and is the value CI must match before publishing a device-upgrade APK.

## Why this exists

Android only allows an in-place package update when the installed and incoming APKs are signed by the same certificate. GitHub-hosted runners otherwise create ephemeral debug keystores, which makes consecutive CI APKs incompatible with `adb install -r`.

## CI behavior

Trusted push builds look for the following GitHub Actions repository secrets:

- `ANVILDESK_DEV_KEYSTORE_B64`
- `ANVILDESK_DEV_STORE_PASSWORD`
- `ANVILDESK_DEV_KEY_ALIAS`
- `ANVILDESK_DEV_KEY_PASSWORD`
- `ANVILDESK_DEV_CERT_SHA256`

When all are configured, CI:

1. decodes the keystore only into the runner temporary directory;
2. builds the debug APK with that dedicated development key;
3. verifies the APK signature with Android `apksigner`;
4. compares the APK certificate SHA-256 fingerprint with `ANVILDESK_DEV_CERT_SHA256`;
5. publishes the `anvildesk-debug` artifact only after that verification succeeds.

If the stable signing secrets are absent, CI still compiles and runs tests, but it does **not** publish a device-upgrade APK. Pull-request builds do not require signing secrets.

## One-time key bootstrap

Generate the key on a trusted local workstation. Keep the private keystore out of Git and out of chat/log output.

```bash
mkdir -p ~/.config/anvildesk/signing
chmod 700 ~/.config/anvildesk/signing

keytool -genkeypair \
  -keystore ~/.config/anvildesk/signing/anvildesk-development.jks \
  -storetype PKCS12 \
  -alias anvildesk-development \
  -keyalg RSA \
  -keysize 3072 \
  -validity 10000 \
  -dname "CN=AnvilDesk Development,O=AnvilDesk Development,C=US"
```

Use a unique development-key password when prompted. The same value may be used for the PKCS12 store and key password if desired.

Print the public certificate fingerprint:

```bash
keytool -list -v \
  -keystore ~/.config/anvildesk/signing/anvildesk-development.jks \
  -alias anvildesk-development \
  | grep 'SHA256:'
```

The SHA-256 certificate fingerprint is public metadata. Never record the keystore password or private key.

## Add repository secrets with GitHub CLI

From the AnvilDesk repository on the trusted workstation:

```bash
cd ~/work/projects/anvildesk

base64 -w 0 ~/.config/anvildesk/signing/anvildesk-development.jks \
  | gh secret set ANVILDESK_DEV_KEYSTORE_B64

gh secret set ANVILDESK_DEV_STORE_PASSWORD
gh secret set ANVILDESK_DEV_KEY_ALIAS --body 'anvildesk-development'
gh secret set ANVILDESK_DEV_KEY_PASSWORD
gh secret set ANVILDESK_DEV_CERT_SHA256
```

For password and fingerprint secrets, `gh` will prompt for the value when `--body` is omitted. This avoids placing sensitive passwords in shell history.

## Local builds

Normal local `assembleDebug` builds continue using the developer machine's ordinary Android debug key unless all four `ANVILDESK_DEV_*` signing environment variables are explicitly supplied. This prevents CI signing material from becoming a local development requirement.

## Device validation

After two independent signed CI builds exist, verify that the second can replace the first without clearing app data:

```bash
adb install -r first-ci.apk
adb install -r second-ci.apk
```

Both commands must succeed, and AnvilDesk's verified Ubuntu rootfs must remain `Ready` after the second install.
