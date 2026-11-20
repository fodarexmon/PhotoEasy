# Build notes

## Continuous integration

Every push and pull request is built by GitHub Actions (`.github/workflows/android.yml`):

1. JDK 21 (Robolectric with SDK 36 needs Java 21) and the runner's Android SDK.
2. `./gradlew testDebugUnitTest` — Robolectric unit tests.
3. `./gradlew assembleDebug` — the APK is uploaded as the `PhotoEasy-apk` artifact.

A failing test fails the build, so a green run means the project compiles and all unit tests pass.

## Releases

A push to `main` publishes GitHub Release `v<versionName>` with the APK attached when that
version has no Release yet. To publish a new version, raise `versionName` (and `versionCode`)
in `app/build.gradle.kts`. A pushed `v*` tag or a Release created on GitHub also gets the APK.

The APK is debug-signed. Set the repository secret `DEBUG_KEYSTORE_BASE64` (a base64-encoded
keystore, alias `androiddebugkey`, passwords `android`) so every build uses the same key and a
newer APK installs over an older one; without it each build generates a new key.

## Local build

```bash
./gradlew clean test assembleDebug
```

Needs the Android SDK (compileSdk 36) and JDK 17+ (JDK 21 to run the unit tests).

## Still to check on a device

Gestures (transform / perspective / text dragging, one-finger mask painting, rapid selection
strokes), refine edge on a transformed layer, reopening older projects with masks, export → Share,
and Select Subject (needs Google Play services; falls back to the built-in method otherwise).
