# Build-ready validation notes

Static checks performed on this package before delivery:

- All Android XML resource/manifest files parse successfully.
- English and Arabic string resources are in 1:1 parity (502 keys each).
- Every `R.string.*` reference in Kotlin resolves to a declared string resource.
- No duplicate string resource keys were found.
- Gradle Wrapper scripts and `gradle-wrapper.jar` are included.
- UX regression tests were added for Object Removal mask mode and Perspective mode cleanup, and the asynchronous duplicate-layer regression test now waits for completion.
- A persistence round-trip test covers curves/HSL/filter intensity/text effects and verifies that a reloaded layer mask is a mutable ALPHA_8 bitmap with its pixels intact (runs with Robolectric `GraphicsMode.NATIVE`).

## Not yet verified by a build

The latest round of fixes (persistence schema 5, document-space masks, refine edge / select subject,
serialised selection edits, gesture handling in `CanvasView`, editor/home UX changes, rename & share)
was made without an Android SDK, so it has **not** been compiled, linted or run. Before release:

```bash
./gradlew clean test lint assembleDebug
```

Then check on a device: transform/perspective/text dragging, one-finger mask painting, rapid selection
strokes, refine edge on a transformed layer, reopening an older project that contains a mask, export → Share,
and rename from both the project list and the editor menu.

### Selection tools (latest round) — also unverified on a device

- New dependency: `com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1`
  (Select Subject). Needs Google Play services; falls back to the built-in method otherwise.
- Check: magnifier loupe, live brush / quick selection / mask strokes, live magnetic lasso & polygon,
  editable rectangle/ellipse (corners + move, from-centre, square/circle, W×H label), polygon
  double-tap close and point dragging, Move-selection tool, magic-wand drag-to-tolerance,
  feather/grow/shrink/smooth live sliders, Refine Edge live preview + view modes, Quick Mask view,
  hold-to-subtract button, multi-level selection undo, selection restored after reopening a project.

## Known leftovers

- 66 string resources are currently unused (kept for now; `shrinkResources` removes them from release builds).
