# Building AnvilDesk

## Toolchain baseline

- JDK 17
- Android SDK Platform 37
- Android SDK Build Tools 36.0.0 or newer compatible version
- Gradle 9.6.0
- Android Gradle Plugin 9.4.0

AGP 9 uses built-in Kotlin support; the project intentionally does not apply the legacy `org.jetbrains.kotlin.android` plugin.

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
