# Build-ready validation notes

Static checks performed on this package before delivery:

- All Android XML resource/manifest files parse successfully.
- English and Arabic string resources are in 1:1 parity.
- Every `R.string.*` reference in Kotlin resolves to a declared string resource.
- No duplicate string resource keys were found.
- Gradle Wrapper scripts and `gradle-wrapper.jar` are included.
- Kotlin source and tests were parsed with the local Kotlin compiler; no parser/redeclaration diagnostics were found.
- UX regression tests were added for Object Removal mask mode and Perspective mode cleanup, and the asynchronous duplicate-layer regression test now waits for completion.

The sandbox used for this review does not contain an Android SDK and blocks Gradle's direct download from `services.gradle.org`, so a full `assembleDebug`/`lint` Android build could not be executed here. Run the README build command once in Android Studio or a machine with SDK 36 installed.
