# PhotoEasy

Professional Android photo editor built with Kotlin + Jetpack Compose.

## Build

Requirements:
- Android Studio with Android SDK / Build Tools 36 installed
- JDK 17 or newer (JDK 17 is the baseline required by AGP 9.1.1)
- Internet access on the first Gradle sync (dependencies and OpenCV are resolved from Google/Maven Central)

Debug build from the project root:

```bash
./gradlew clean test lint assembleDebug
```

Windows:

```powershell
.\gradlew.bat clean test lint assembleDebug
```

Release bundle:

```bash
./gradlew bundleRelease
```

For signed release builds, either place `my-upload-key.jks` in the project root or set `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` environment variables.

## UX / editor improvements in this revision

- Responsive editor layout: compact/medium screens keep the canvas central; the 3-pane studio layout is used only on expanded-width, non-compact-height windows.
- One horizontal, scrollable mobile tool rail; no toolbar overlays the image.
- Single zoom control; Fit mode automatically re-fits when contextual panels resize the canvas.
- Two-finger pan/zoom around the touch centroid while one-finger gestures remain reserved for the active editing tool.
- Professional crop overlay with handles, rule-of-thirds grid, aspect-ratio selection state, live crop dimensions, and perspective isolated to the crop tool.
- Transform handles appear only when Transform is explicitly selected.
- Live filter thumbnails generated from the current image, transient preview, Apply/Cancel, and press-to-compare with the original.
- Adjustments include Reset All and non-destructive press-to-compare.
- Selection workspace reorganized into tool/mode/refine groups with Rectangle/Ellipse/Lasso/Polygon/Wand, independent selection Undo/Redo, Add/Subtract/Intersect, and lower-memory ALPHA_8 operations.
- Content-aware removal has dedicated Paint/Erase mask modes, mask Undo/Clear, radius/edge controls, and OpenCV Telea + Navier–Stokes hybrid inpainting with a Kotlin fallback.
- Copy Selection to New Layer now copies from the authoritative image source, keeps antialiased alpha, and preserves document-space geometry.
- Layer panel now uses thumbnails, flexible height, and an overflow action menu instead of a cramped row of buttons.
- Top bar is reduced to core actions with Save/Resize/Smart Auto moved to overflow, plus visible Saved/Unsaved/Working state.
- Project deletion requires confirmation; the sample project is no longer auto-created and is de-emphasized after first use.
- Dialogs and contextual panels adapt to short landscape windows and support scrolling / numeric keyboards where appropriate.
- English/Arabic resources are kept in parity, runtime operation messages are localized, and custom controls use at least 48dp touch targets where interactive.
- Canvas preview rendering uses adaptive proxy resolution and background double buffering to reduce jank during editing.

## Image engine notes

- Source/proxy geometry is separated so preview and export share the same document-space transforms.
- Destructive operations persist a high-quality authoritative source version while the editor keeps a lighter preview proxy.
- Content-aware removal uses the official `org.opencv:opencv:4.14.0` Maven artifact.
- Project persistence uses schema versioning and safer commit checks.
