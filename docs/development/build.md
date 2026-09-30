# Building AnvilDesk

## Toolchain baseline

- JDK 17
- Android SDK Platform 36 (Android 16)
- Android SDK Build Tools 36.0.0
- Gradle 9.6.0
- Android Gradle Plugin 9.4.0

AGP 9 uses built-in Kotlin support; the project intentionally does not apply the legacy `org.jetbrains.kotlin.android` plugin.

AGP 9.4 supports API levels through 37, but API 36 is the project compile/target baseline because it is the current stable SDK platform published through the standard Android SDK channel. API 37 will be adopted when its stable platform package is generally resolvable by `sdkmanager` and CI.

## Initial build

Until the Gradle wrapper binary is generated and committed, use a local Gradle 9.6 installation:

```bash
gradle --no-daemon :core:runtime:testDebugUnitTest :app:assembleDebug
```

To generate the project wrapper locally:

```bash
gradle wrapper --gradle-version 9.6.0 --distribution-type bin
```

The resulting wrapper files should be reviewed and committed so subsequent builds use `./gradlew`.

## Branch model

- `main`: release-ready history.
- `development`: integration branch.
- `feature/*`: focused implementation branches merged through pull requests into `development`.
