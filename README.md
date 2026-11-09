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
- Crop: live straighten (±45°, the frame always stays inside the rotated image), whole-image rotate 90° / flip, ratios that hold at the image edges (edges work with a fixed ratio too), portrait/landscape swap, exact output sizes (presets + custom), thirds / golden / diagonal grids, and canvas expansion.
- Perspective crop: output keeps the rectangle's real proportions (estimated from the quad geometry), corner loupe, keystone sliders (vertical / horizontal) for buildings, automatic document detection, and a single high-quality resampling per layer without full-canvas intermediates.
- Professional crop overlay with handles, rule-of-thirds grid, aspect-ratio selection state, live crop dimensions, and perspective isolated to the crop tool.
- Transform: corners scale from the opposite corner (or from the centre), edges along the layer's own axes, relative rotation around a selectable reference point with 15°/90° snapping, smart guides to canvas and other layers' edges/centres, two-finger scale/rotate on the layer, live value bubble, exact numeric values, log-scale sliders covering the whole image, and non-destructive Skew and free Distort (corner pinning).
- Live filter thumbnails generated from the current image, transient preview, Apply/Cancel, and press-to-compare with the original.
- Pro effects (blur, sharpen, oil paint, noise reduction...) preview live on the main image, scaled to match the full-resolution result and limited to the active selection; nothing changes until Apply.
- Pro effects engine (`ProEffects`): premultiplied processing (no dark halos at transparent edges), true Gaussian with fractional radius, edge-preserving surface blur (guided filter, any radius), Kuwahara oil paint with bristle texture, luminance-only unsharp mask with threshold, luminance + colour noise reduction with detail recovery, smooth motion / radial blur (bilinear, recursive doubling) with Zoom and Spin and a centre placed by tapping, lens blur (bokeh disc in linear light with highlight bloom), tilt-shift (focus size, transition, angle), high pass; every effect has an opacity slider and Reset, heavy effects run in strips with progress and Cancel, and non-image layers explain why effects are unavailable.
- Transform vs Crop: each panel says what it changes (Transform = the selected layer, Crop & Perspective = the whole image), rotate / flip use the same words in both, hints point to Straighten for tilted photos and to Perspective crop for documents, and rotating a one-layer image by 90° turns the whole image with its canvas.
- Adjustments: grouped Light / Color / Detail / Effects sliders (exposure, brightness as a mid-tone curve, contrast S-curve, local highlights/shadows, whites, blacks, natural white balance, vibrance with skin protection, saturation, clarity, dehaze, luminance unsharp mask scaled to the image, elliptical vignette), processed in one float pass without 8-bit clipping between steps; independent HSL per colour range with smooth range blending; double-tap a slider to reset it; press-to-compare; "Adjust selection only" adds an adjustment layer masked by the selection.
- Curves: monotone cubic spline (no overshoot or inverted tones), smooth drag that never swaps points, new points added on the curve, movable end points (input black/white), drag off the graph or double-tap to delete, histogram behind the graph, other channels shown faintly, modified-channel dots, ±1 nudges, on-image point picking and black/grey/white point eyedroppers, more presets, Reset All Curves.
- HSL: eight Lightroom-style colour ranges (reds, oranges, yellows, greens, aquas, blues, purples, magentas) with smooth blending and adjustable range width, perceptual lightness that keeps hue and colourfulness, limited per-range hue shifts (no tearing), targeted adjustment by dragging on the image, colour strips on the sliders, double-tap reset, and Colorize.
- Replace Color: up to three replacements per layer, eyedroppers for the source and target colours, perceptual (CIE Lab) matching with tolerance + edge softness, greys protected, colour transfer that keeps the original shading, hold-to-see affected area, and a shortcut to limit it to the selection. Fixes the old tolerance unit bug (older projects are repaired on load).
- Smart Auto analyses the histogram (black/white points, median, spread, clipping), neutral-pixel white balance, colourfulness and noise, adds only the corrections the image needs on top of your settings, and has a strength slider (0-150%).
- Selection workspace reorganized into tool/mode/refine groups with Rectangle/Ellipse/Lasso/Polygon/Wand, independent selection Undo/Redo, Add/Subtract/Intersect, and lower-memory ALPHA_8 operations.
- Smart Removal (default): the hole is split into parts; thin parts (scratches, wires, dust) get seamless fast-marching, straight edges crossing larger parts are continued, and the rest gets PatchMatch texture synthesis started from the fast-marching fill. A protected area (painted in green) is never used as a texture source, and "Another result" re-runs the last removal with different texture choices. The individual techniques remain under Advanced.
- Content-aware removal has dedicated Paint/Erase mask modes, mask Undo/Clear, radius/edge controls, and two pure-Kotlin engines: multi-scale PatchMatch image completion (content-aware fill, default) and Telea fast marching. The hole is grown a few pixels to remove halos and cross-faded with the original across the feather ring.
- Copy Selection to New Layer now copies from the authoritative image source, keeps antialiased alpha, and preserves document-space geometry.
- Layers panel: drag-to-reorder, rename, per-row visibility/lock, live thumbnails (layer as placed, plus a black/white mask thumbnail), clipping masks, select layer pixels, and 17 blend modes (Color Dodge/Burn, Exclusion, Hue, Saturation, Color, Luminosity on Android 10+).
- Text tool: multi-line typing (Enter = new line), correct left/right alignment for Arabic, a text box that hugs the text (max width adjustable), bold/italic/underline, gradient fill, background box, real blurred drop shadow and outer glow with their own opacity, effects that scale with the image, tap to select / double-tap to edit text on the canvas, drag the text only when the drag starts on it, two-finger scale/rotate on the text, automatic layer names, and no empty undo steps.
- Layer masks: linked to the layer (they move/scale/rotate with it, chain-link toggle), soft brush with hardness and per-stroke opacity, gradients (linear/radial, optionally inside the selection), painting limited to the selection and blocked on locked layers; feather/density results are cached at render size.
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
- Filters: presets are built from tone curves, white balance, skin-protected saturation/vibrance, split toning, matte fade, film grain, linear-light glow and light leaks (`FilterRecipes`); special effects scale with the image; intensity preserves transparency. Users can import 3D LUTs (`.cube`, `CubeLut`), save their own looks, mark favourites and browse by category (`FilterLibrary`).
- Quick Selection (`QuickSelectEngine`): foreground and background colour models in CIE Lab (k-means), colour (not only brightness) edge map, edge-aware geodesic growth (bucket-queue Dijkstra) that reaches the object outline from a short stroke while internal edges between object colours are free, the same intelligence when subtracting, hole filling and a guided-filter anti-aliased edge; own tolerance and edge sensitivity; live strokes are coalesced on a fast 1024 px source and the whole stroke is recomputed at full detail (up to 3072 px) when the finger lifts.
- One refine workspace, Refine Edge & Hair (Select and Mask): edge detection with smart radius and hair matting (radius 0 = off), global refinements in pixels (Euclidean shift edge with round corners applied before matting so strands survive, smooth without shrinking, Gaussian feather, edge contrast), remove specks & holes, border band and colour decontamination; instant preview (fast pass then detailed pass) with pinch zoom, hold-to-compare Before, and overlay / black / white / mask views. The separate Refine Selection group was removed from the Selection tab.
- Export (`ExportEngine`): JPG, PNG, WebP and lossless WebP; quality presets; output size (original, percent, long edge, Instagram / Full HD / 4K) with high-quality downscaling and output sharpening; estimated file size and a 1:1 compression preview; transparent background (hides the background fill, on by default for the old dark import fill) and a JPG background colour; whole image, selection only or each layer separately; EXIF kept, kept without location, or removed (orientation reset); editable file name; gallery or Save to Files (a folder for layers); share after saving; progress with Cancel; automatic smaller export with a message when memory is short; settings remembered. Imported photos no longer get a dark background layer, so transparent PNGs export transparent as in Photoshop.
- Selection tools: live strokes, magnifier loupe, live magnetic snapping, editable shapes, Move selection, magic wand (`MagicWandEngine`: samples the image as shown in CIE Lab, 1/3×3/5×5 sample size, graded soft edges, smart-gradient region growing, noise cleanup, all-layers sampling, cancellable drag-to-tolerance), live modifier sliders, Refine Edge live preview, Quick Mask view, hold-to-subtract, compressed multi-level selection undo (`SelectionHistory`), and the selection is saved with the project (`selection.png`).
