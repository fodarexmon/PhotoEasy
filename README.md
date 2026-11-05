# PhotoEasy

Professional Android photo editor built with Kotlin + Jetpack Compose.

## Build

Requirements:
- Android Studio with Android SDK / Build Tools 36 installed
- JDK 17 or newer (JDK 17 is the baseline required by AGP 9.1.1)
- Internet access on the first Gradle sync (dependencies are resolved from Google/Maven Central)

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

For signed release builds, either place `my-upload-key.jks` in the project root or set `KEYSTORE_PATH`, and provide `STORE_PASSWORD` and `KEY_PASSWORD` as environment variables. The key alias is fixed to `upload` in `app/build.gradle.kts`.

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
- Content-aware removal has dedicated Paint/Erase mask modes, mask Undo/Clear, radius/edge controls, and two pure-Kotlin engines: multi-scale PatchMatch image completion (content-aware fill, default) and Telea fast marching. The hole is grown a few pixels to remove halos and cross-faded with the original across the feather ring.
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
- Content-aware removal is implemented in Kotlin (`InpaintEngine`); there is no OpenCV dependency.
- Project persistence uses schema versioning (currently 5) and safer commit checks. Schema 5 also stores curves, HSL, colour replacement, filter intensity, mask density/feather and text glow/shadow offsets.
- Layer masks and selections live in document space (canvas-sized ALPHA_8). Masks are saved as lossless grayscale PNG and reloaded as editable bitmaps; unchanged masks are not re-encoded on autosave.
- Layer bitmaps are saved lossless (WebP lossless on Android 11+, PNG below). Unreferenced `source_*` assets are cleaned up, except those still needed by undo/redo.
- Selection edits are serialised and copy-on-write, so the canvas never reads a half-edited mask.
- Adjustment layers honour their mask, which is how adjustments are limited to an area (non-destructive adjustments always apply to the whole layer).
- The app works offline and does not request the INTERNET permission. "Select Subject" uses ML Kit on-device subject segmentation (model delivered by Google Play services) and falls back to a built-in saliency method.
- Refine Edge (`RefineEdgeEngine`): trimap band in all directions (O(N) morphology, Smart Radius by local texture), colour-sampling alpha estimation (best F/B pair along 8 rays), colour guided filter for hair, decontamination that preserves layer transparency; processed in tiles around the edge band to keep memory low.
- Selection tools: live strokes, magnifier loupe, live magnetic snapping, editable shapes, Move selection, magic-wand drag-to-tolerance, live modifier sliders, Refine Edge live preview, Quick Mask view, hold-to-subtract, compressed multi-level selection undo (`SelectionHistory`), and the selection is saved with the project (`selection.png`).
