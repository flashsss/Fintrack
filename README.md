# FinTrack

Native Android personal finance tracker (Kotlin, Jetpack Compose, Room).

- Repository rules: [`CLAUDE.md`](CLAUDE.md)
- Architecture, database conventions and business rules: [`docs/ENGINEERING_SPEC.md`](docs/ENGINEERING_SPEC.md)

## Build requirements

| Requirement | Version |
|---|---|
| JDK | 21 (the version the build is verified with; AGP 9 requires at least 17) |
| Gradle | Provided by the wrapper (`./gradlew`), 9.9.2 |
| Android SDK Platform | `platforms;android-37.2` (compileSdk 37, minor API level 2) |
| Android SDK Build-Tools | Installed automatically by the Android Gradle Plugin when licenses are accepted |

The Android SDK is not part of this repository. Point the build to your own SDK with
either the `ANDROID_HOME` environment variable or a `local.properties` file containing
`sdk.dir=/path/to/Android/sdk`. `local.properties` is machine-specific and git-ignored;
never commit it.

Installing the required platform with the command-line tools:

```bash
sdkmanager --licenses
sdkmanager "platforms;android-37.2" "platform-tools"
```

## Common commands

```bash
./gradlew :app:assembleDebug          # build the debug APK
./gradlew :app:testDebugUnitTest      # JVM + Robolectric tests (no emulator required)
./gradlew :app:lintDebug              # Android lint
```

Room schemas are exported to `app/schemas/` and must be committed with every schema change.
