package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.BlendModeType
import com.example.data.model.ColorAdjustments
import com.example.data.model.FilterType
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Project
import com.example.data.model.SelectionMode
import com.example.data.model.TextLayerConfig
import com.example.data.repository.ProjectRepository
import com.example.engine.LayerRenderer
import com.example.engine.SelectionEngine
import com.example.util.CanvasValidation
import com.example.ui.viewmodel.ActiveTool
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhotoEasyRobolectricTest {

    private lateinit var context: Context
    private lateinit var repository: ProjectRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = ProjectRepository(context)
    }

    @Test
    fun `context reads correct application name`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("PhotoEasy", appName)
    }

    // Real pixel encode/decode is needed to verify the mask contents survive a save.
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `round trip persists curves hsl filter intensity text effects and an editable mask`() = runBlocking {
        val project = Project(id = "test_proj_persist", name = "Persist", width = 100, height = 100)
        val curve = listOf(android.graphics.PointF(0f, 0f), android.graphics.PointF(128f, 160f), android.graphics.PointF(255f, 255f))
        val mask = Bitmap.createBitmap(100, 100, Bitmap.Config.ALPHA_8)
        android.graphics.Canvas(mask).drawRect(0f, 0f, 50f, 100f, android.graphics.Paint().apply { color = Color.WHITE })
        val layer = Layer(
            id = "layer_persist",
            name = "Img",
            type = LayerType.IMAGE,
            bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) },
            maskBitmap = mask,
            maskDensity = 0.6f,
            maskFeather = 4f,
            filter = FilterType.SEPIA,
            filterIntensity = 0.4f,
            adjustments = ColorAdjustments(curvesRgb = curve, hueShift = 30f, hslLightness = -10f, replaceColorEnabled = true, replaceTargetHue = 200f),
            textConfig = TextLayerConfig(shadowDxDp = 9f, shadowDyDp = -3f, outerGlowRadiusDp = 5f, outerGlowColorHex = 0xFF112233.toInt())
        )
        project.layers.add(layer)
        assertTrue(repository.saveProject(project))

        val loaded = repository.getProjectById("test_proj_persist")!!.layers[0]
        assertEquals(0.4f, loaded.filterIntensity, 0.001f)
        assertEquals(0.6f, loaded.maskDensity, 0.001f)
        assertEquals(4f, loaded.maskFeather, 0.001f)
        assertEquals(3, loaded.adjustments.curvesRgb.size)
        assertEquals(160f, loaded.adjustments.curvesRgb[1].y, 0.001f)
        assertEquals(30f, loaded.adjustments.hueShift, 0.001f)
        assertEquals(-10f, loaded.adjustments.hslLightness, 0.001f)
        assertTrue(loaded.adjustments.replaceColorEnabled)
        assertEquals(200f, loaded.adjustments.replaceTargetHue, 0.001f)
        assertEquals(9f, loaded.textConfig.shadowDxDp, 0.001f)
        assertEquals(-3f, loaded.textConfig.shadowDyDp, 0.001f)
        assertEquals(5f, loaded.textConfig.outerGlowRadiusDp, 0.001f)
        assertEquals(0xFF112233.toInt(), loaded.textConfig.outerGlowColorHex)

        val loadedMask = loaded.maskBitmap!!
        assertTrue("Reloaded mask must be editable", loadedMask.isMutable)
        assertEquals(Bitmap.Config.ALPHA_8, loadedMask.config)
        assertTrue(((loadedMask.getPixel(10, 50) ushr 24) and 0xFF) > 200)
        assertEquals(0, (loadedMask.getPixel(90, 50) ushr 24) and 0xFF)
        // Must not throw "Immutable bitmap passed to Canvas constructor"
        android.graphics.Canvas(loadedMask).drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
    }

    @Test
    fun `save and load project round trip maintains layer IDs schema version and attributes`() = runBlocking {
        val project = Project(
            id = "test_proj_001",
            name = "Test Project",
            width = 1080,
            height = 1080
        )

        val layer1Id = "layer_img_1"
        val imgLayer = Layer(
            id = layer1Id,
            name = "Background Image",
            type = LayerType.IMAGE,
            opacity = 0.85f,
            blendMode = BlendModeType.MULTIPLY
        )

        val layer2Id = "layer_adj_2"
        val adjLayer = Layer(
            id = layer2Id,
            name = "Adjustment Layer 1",
            type = LayerType.ADJUSTMENT,
            adjustments = ColorAdjustments(brightness = 15f, contrast = 1.2f),
            filter = FilterType.VINTAGE
        )

        val layer3Id = "layer_txt_3"
        val txtLayer = Layer(
            id = layer3Id,
            name = "Text: PhotoEasy",
            type = LayerType.TEXT,
            textConfig = TextLayerConfig(text = "PhotoEasy", fontSizeSp = 48f)
        )

        project.layers.addAll(listOf(imgLayer, adjLayer, txtLayer))

        // Save project transactionally
        val saveSuccess = repository.saveProject(project)
        assertTrue("Project save should succeed", saveSuccess)

        // Read back project from repository
        val loadedProject = repository.getProjectById("test_proj_001")
        assertNotNull("Loaded project should not be null", loadedProject)
        assertEquals("test_proj_001", loadedProject!!.id)
        assertEquals("Test Project", loadedProject.name)
        assertEquals(1080, loadedProject.width)
        assertEquals(1080, loadedProject.height)
        assertEquals(3, loadedProject.layers.size)

        // Verify layer ID stability and attributes preservation
        val l1 = loadedProject.layers[0]
        assertEquals(layer1Id, l1.id)
        assertEquals(LayerType.IMAGE, l1.type)
        assertEquals(0.85f, l1.opacity, 0.001f)
        assertEquals(BlendModeType.MULTIPLY, l1.blendMode)

        val l2 = loadedProject.layers[1]
        assertEquals(layer2Id, l2.id)
        assertEquals(LayerType.ADJUSTMENT, l2.type)
        assertEquals(15f, l2.adjustments.brightness, 0.001f)
        assertEquals(1.2f, l2.adjustments.contrast, 0.001f)
        assertEquals(FilterType.VINTAGE, l2.filter)

        val l3 = loadedProject.layers[2]
        assertEquals(layer3Id, l3.id)
        assertEquals(LayerType.TEXT, l3.type)
        assertEquals("PhotoEasy", l3.textConfig.text)
    }

    @Test
    fun `canvas validation clamps extreme and negative dimensions safely`() {
        // Test normal valid size
        val valid = CanvasValidation.validate(1080, 1080)
        assertTrue(valid.isValid)

        // Test extreme oversized 100,000 x 100,000 dimensions (Request 48)
        val extreme = CanvasValidation.validate(100000, 100000)
        assertFalse("Extreme dimension must be rejected", extreme.isValid)

        // Test zero / negative dimensions
        val zero = CanvasValidation.validate(0, 500)
        assertFalse("Zero dimension must be rejected", zero.isValid)

        val negative = CanvasValidation.validate(-100, -100)
        assertFalse("Negative dimension must be rejected", negative.isValid)

        // Test CanvasValidation.clamp
        val (cw, ch) = CanvasValidation.clamp(100000, 100000)
        assertTrue("Clamped width should be <= 8192", cw <= CanvasValidation.MAX_DIMENSION)
        assertTrue("Clamped height should be <= 8192", ch <= CanvasValidation.MAX_DIMENSION)
        val mp = (cw.toLong() * ch.toLong()) / 1_000_000f
        assertTrue("Clamped megapixels should be <= 25 MP", mp <= CanvasValidation.MAX_MEGAPIXELS + 0.1f)
    }

    @Test
    fun `layer renderer handles transparent PNG export and background filled JPEG export`() = runBlocking {
        val layers = listOf(
            Layer(
                id = "fill_1",
                name = "Color Fill",
                type = LayerType.COLOR_FILL,
                colorFillHex = Color.RED
            )
        )

        // Transparent canvas (includeBackground = false)
        val transparentComposite = LayerRenderer.renderCompositeCanvas(
            context = context,
            layers = layers,
            width = 200,
            height = 200,
            includeBackground = false
        )
        assertNotNull(transparentComposite)
        assertEquals(200, transparentComposite.width)
        assertEquals(200, transparentComposite.height)

        // Background composite (includeBackground = true with white background)
        val bgComposite = LayerRenderer.renderCompositeCanvas(
            context = context,
            layers = layers,
            width = 200,
            height = 200,
            includeBackground = true,
            backgroundColor = Color.WHITE
        )
        assertNotNull(bgComposite)
        assertEquals(200, bgComposite.width)
        assertEquals(200, bgComposite.height)
    }

    @Test
    fun `selection engine creates valid mask matching canvas dimensions`() {
        val mask = SelectionEngine.createBlankMask(800, 600)
        assertNotNull(mask)
        assertEquals(800, mask.width)
        assertEquals(600, mask.height)

        // Test feathering mask
        SelectionEngine.featherMask(mask, radiusPx = 5f)
        assertEquals(800, mask.width)

        // Test refining mask edges
        SelectionEngine.refineMaskEdges(mask, contrastFactor = 2.0f)
        assertEquals(800, mask.width)
    }

    @Test
    fun `thumbnail renderer produces proxy bitmap at target max dimension`() = runBlocking {
        val layers = listOf(
            Layer(
                id = "l1",
                name = "Layer 1",
                type = LayerType.COLOR_FILL,
                colorFillHex = Color.BLUE
            )
        )

        val thumb = LayerRenderer.renderThumbnail(
            context = context,
            layers = layers,
            width = 2000,
            height = 1000,
            targetMaxDimension = 320
        )

        assertNotNull(thumb)
        assertTrue("Thumbnail width should be <= 320", thumb.width <= 320)
        assertTrue("Thumbnail height should be <= 320", thumb.height <= 320)
        assertEquals(320, thumb.width) // 2000x1000 scaled to max 320 => 320x160
        assertEquals(160, thumb.height)
    }

    @Test
    fun `decodeSampledBitmapFromUriWithExif safely loads photo from Uri`() {
        // Create sample image file in cache directory
        val testFile = java.io.File(context.cacheDir, "test_gallery_photo.png")
        val sampleBmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        java.io.FileOutputStream(testFile).use { out ->
            sampleBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        val fileUri = android.net.Uri.fromFile(testFile)
        val decoded = com.example.engine.ImageProcessor.decodeSampledBitmapFromUriWithExif(context, fileUri, maxDim = 500)

        assertNotNull("Decoded image from URI should not be null", decoded)
        assertEquals(400, decoded!!.width)
        assertEquals(300, decoded.height)
        testFile.delete()
    }

    @Test
    fun `createSampleProject generates complete multi-layer project ready for editing`() = runBlocking {
        val sampleProj = repository.createSampleProject()
        assertNotNull(sampleProj)
        assertTrue(sampleProj.layers.size >= 2)
        assertEquals(1080, sampleProj.width)
        assertEquals(1080, sampleProj.height)

        val imageLayer = sampleProj.layers.find { it.type == LayerType.IMAGE }
        assertNotNull("Sample project must contain an image layer", imageLayer)
        assertNotNull("Sample image layer must contain bitmap", imageLayer?.bitmap)

        // Verify markDirty increments renderRevision
        val prevRevision = sampleProj.renderRevision
        sampleProj.markDirty()
        assertTrue(sampleProj.renderRevision > prevRevision)
    }

    @Test
    fun `color adjustments applyBrightness and contrast produces valid non-null bitmap`() {
        val src = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val adj = ColorAdjustments(brightness = 25f, contrast = 10f, saturation = 15f)
        val processed = com.example.engine.ImageProcessor.applyAdjustments(src, adj)
        assertNotNull(processed)
        assertEquals(100, processed.width)
        assertEquals(100, processed.height)
    }

    @Test
    fun `transient slider updates capture initial state and undo correctly restores initial state`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.viewmodel.EditorViewModel(app)

        val project = Project(name = "Slider Test Project", width = 500, height = 500)
        val layer = Layer(id = "l1", name = "Test Layer", type = LayerType.IMAGE, adjustments = ColorAdjustments(brightness = 0f))
        project.layers.add(layer)

        viewModel.openProject(project)

        // Initial brightness is 0
        assertEquals(0f, viewModel.uiState.value.currentProject?.layers?.first()?.adjustments?.brightness ?: -1f, 0.001f)

        // Simulate slider dragging: brightness changes continuously from 0 -> 10 -> 20 -> 30
        viewModel.updateSelectedLayerTransient { l -> l.adjustments = l.adjustments.copy(brightness = 10f) }
        viewModel.updateSelectedLayerTransient { l -> l.adjustments = l.adjustments.copy(brightness = 20f) }
        viewModel.updateSelectedLayerTransient { l -> l.adjustments = l.adjustments.copy(brightness = 30f) }

        // Current brightness is 30
        assertEquals(30f, viewModel.uiState.value.currentProject?.layers?.first()?.adjustments?.brightness ?: -1f, 0.001f)

        // Slider release calls saveHistoryState / commitHistoryTransaction
        viewModel.commitHistoryTransaction()

        // Verify canUndo is true
        assertTrue(viewModel.uiState.value.canUndo)

        // Execute Undo
        viewModel.undo()

        // Brightness should be restored to 0f (state before gesture started), NOT 30f!
        val restoredBrightness = viewModel.uiState.value.currentProject?.layers?.first()?.adjustments?.brightness ?: -1f
        assertEquals("Undo must restore pre-gesture brightness of 0", 0f, restoredBrightness, 0.001f)

        // Execute Redo
        viewModel.redo()
        val redoneBrightness = viewModel.uiState.value.currentProject?.layers?.first()?.adjustments?.brightness ?: -1f
        assertEquals("Redo must restore gesture brightness of 30", 30f, redoneBrightness, 0.001f)
    }

    @Test
    fun `text editing via updateSelectedLayerTransient sets isProjectDirty true and commits history on transaction finish`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.viewmodel.EditorViewModel(app)

        val project = Project(name = "Text Edit Project", width = 500, height = 500)
        val textLayer = Layer(
            id = "t1",
            name = "Text Layer",
            type = LayerType.TEXT,
            textConfig = TextLayerConfig(text = "Hello World")
        )
        project.layers.add(textLayer)

        viewModel.openProject(project)

        assertFalse("Project should initially not be dirty", viewModel.uiState.value.isProjectDirty)

        // Type new text
        viewModel.updateSelectedLayerTransient { l ->
            l.textConfig = l.textConfig.copy(text = "Hello World Extra")
        }

        assertTrue("Project must be dirty during text editing", viewModel.uiState.value.isProjectDirty)

        // Commit transaction (e.g. IME Done or Focus Loss)
        viewModel.commitHistoryTransaction()

        assertTrue("canUndo must be true after text edit commit", viewModel.uiState.value.canUndo)

        // Undo text edit
        viewModel.undo()

        val restoredText = viewModel.uiState.value.currentProject?.layers?.first()?.textConfig?.text
        assertEquals("Undo must restore text to Hello World", "Hello World", restoredText)
    }

    @Test
    fun `layer mutations update renderRevision and set project dirty state`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.viewmodel.EditorViewModel(app)

        val project = Project(name = "RenderRevision Test", width = 500, height = 500)
        val layer1 = Layer(id = "l1", name = "Layer 1", type = LayerType.COLOR_FILL)
        val layer2 = Layer(id = "l2", name = "Layer 2", type = LayerType.COLOR_FILL)
        project.layers.add(layer1)
        project.layers.add(layer2)

        viewModel.openProject(project)
        val initialRev = viewModel.uiState.value.renderRevision

        // Duplicate layer
        viewModel.duplicateSelectedLayer()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        withTimeout(5_000) {
            viewModel.uiState.first { it.currentProject?.layers?.size == 3 && !it.isProcessing }
        }
        val rev1 = viewModel.uiState.value.renderRevision
        assertTrue("renderRevision must increment on duplicate", rev1 > initialRev)
        assertTrue("isProjectDirty must be true on duplicate", viewModel.uiState.value.isProjectDirty)

        // Move layer
        viewModel.moveLayerDown()
        val rev2 = viewModel.uiState.value.renderRevision
        assertTrue("renderRevision must increment on move", rev2 > rev1)

        // Delete layer
        viewModel.deleteSelectedLayer()
        val rev3 = viewModel.uiState.value.renderRevision
        assertTrue("renderRevision must increment on delete", rev3 > rev2)
    }
    @Test
    fun `object removal always enters additive mask mode`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.viewmodel.EditorViewModel(app)
        viewModel.setSelectionMode(SelectionMode.SUBTRACT)
        viewModel.selectTool(ActiveTool.OBJECT_REMOVAL)
        assertEquals(SelectionMode.ADD, viewModel.uiState.value.selectionMode)
    }

    @Test
    fun `leaving crop tool disables perspective interaction mode`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.viewmodel.EditorViewModel(app)
        val project = Project(name = "Perspective UX", width = 500, height = 500)
        project.layers.add(Layer(id = "l1", name = "Layer", type = LayerType.COLOR_FILL))
        viewModel.openProject(project)
        viewModel.selectTool(ActiveTool.CROP_PERSPECTIVE)
        viewModel.setPerspectiveMode(true)
        assertTrue(viewModel.uiState.value.isPerspectiveModeActive)
        viewModel.selectTool(ActiveTool.FILTERS)
        assertFalse(viewModel.uiState.value.isPerspectiveModeActive)
    }

    @Test
    fun `perspective crop unwarps quadrilateral to rectangular output correctly`() {
        val source = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLUE)
        for (y in 50 until 150) {
            for (x in 50 until 150) {
                source.setPixel(x, y, Color.RED)
            }
        }
        val corners = listOf(
            android.graphics.PointF(50f, 50f),
            android.graphics.PointF(150f, 50f),
            android.graphics.PointF(150f, 150f),
            android.graphics.PointF(50f, 150f)
        )
        val result = com.example.engine.ImageProcessor.transformPerspective(source, corners, 100, 100)
        assertEquals(100, result.width)
        assertEquals(100, result.height)
        assertEquals(Color.RED, result.getPixel(50, 50))
    }

    @Test
    fun `quick selection snaps to high contrast edges within stroke area`() {
        // Create 100x100 bitmap: left half Red (high contrast), right half Blue
        val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) {
            for (x in 0 until 50) image.setPixel(x, y, Color.RED)
            for (x in 50 until 100) image.setPixel(x, y, Color.BLUE)
        }
        val mask = Bitmap.createBitmap(100, 100, Bitmap.Config.ALPHA_8)

        // User strokes on the red side: x from 20 to 30, y from 40 to 60
        val stroke = listOf(
            android.graphics.PointF(25f, 45f),
            android.graphics.PointF(25f, 55f)
        )
        SelectionEngine.quickSelection(
            mask = mask,
            points = stroke,
            brushRadius = 15f,
            sourceBitmap = image,
            mode = SelectionMode.REPLACE,
            tolerance = 30
        )

        // Red side inside expansion should be selected (alpha > 0)
        val pixelOnRed = (mask.getPixel(25, 50) ushr 24) and 0xFF
        assertTrue("Red area under stroke should be selected", pixelOnRed > 0)

        // Blue side beyond x=50 boundary should NOT be selected because of high contrast edge
        val pixelOnBlue = (mask.getPixel(75, 50) ushr 24) and 0xFF
        assertEquals("Blue area across edge should not be selected", 0, pixelOnBlue)
    }

    @Test
    fun `layer mask add, invert, and paint operations function accurately`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.viewmodel.EditorViewModel(app)
        val project = Project(name = "Mask Test", width = 100, height = 100)
        val testBmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        val layer = Layer(id = "mask_layer", name = "Test", type = LayerType.IMAGE, bitmap = testBmp)
        project.layers.add(layer)
        viewModel.openProject(project)

        // Add blank mask (reveals everything = white)
        viewModel.addLayerMask(fromSelection = false)
        assertNotNull(layer.maskBitmap)
        assertTrue(layer.isMaskEnabled)
        assertTrue(layer.isEditingMask)

        // Invert mask (white becomes black = hides everything)
        viewModel.invertLayerMask()
        val activeLayerAfterInvert = viewModel.uiState.value.currentProject?.layers?.getOrNull(0)
        assertNotNull(activeLayerAfterInvert?.maskBitmap)
        val invertedAlpha = (activeLayerAfterInvert!!.maskBitmap!!.getPixel(50, 50) ushr 24) and 0xFF
        assertEquals(0, invertedAlpha)

        // Paint white stroke to reveal a section
        viewModel.paintOnLayerMask(
            points = listOf(android.graphics.PointF(45f, 50f), android.graphics.PointF(55f, 50f)),
            brushRadius = 10f,
            isReveal = true
        )
        val activeLayerAfterPaint = viewModel.uiState.value.currentProject?.layers?.getOrNull(0)
        assertNotNull(activeLayerAfterPaint?.maskBitmap)
        val revealedAlpha = (activeLayerAfterPaint!!.maskBitmap!!.getPixel(50, 50) ushr 24) and 0xFF
        assertTrue("Painted stroke should reveal area (was $revealedAlpha)", revealedAlpha > 0)
    }

    @Test
    fun `before and after comparison snapshot preserves live layer state while resetting preview clone`() {
        val testBmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val layer = Layer(
            id = "layer_compare_test",
            name = "Compare Layer",
            type = LayerType.IMAGE,
            bitmap = testBmp,
            adjustments = ColorAdjustments(brightness = 45f, contrast = 30f, saturation = -20f),
            filter = FilterType.VINTAGE
        )

        // Snapshot for comparison before/after
        val snapshot = layer.snapshotLayer().also {
            it.filter = FilterType.NONE
            it.adjustments = ColorAdjustments()
        }

        // Verify clone is reset to original baseline
        assertEquals(FilterType.NONE, snapshot.filter)
        assertEquals(0f, snapshot.adjustments.brightness, 0.001f)
        assertEquals(0f, snapshot.adjustments.contrast, 0.001f)
        assertEquals(0f, snapshot.adjustments.saturation, 0.001f)

        // Verify original live layer remains completely unmodified
        assertEquals(FilterType.VINTAGE, layer.filter)
        assertEquals(45f, layer.adjustments.brightness, 0.001f)
        assertEquals(30f, layer.adjustments.contrast, 0.001f)
        assertEquals(-20f, layer.adjustments.saturation, 0.001f)
    }

    @Test
    fun `before and after comparison string resources are defined`() {
        assertNotNull(context.getString(R.string.compare_title))
        assertNotNull(context.getString(R.string.compare_before))
        assertNotNull(context.getString(R.string.compare_after))
        assertNotNull(context.getString(R.string.compare_split_mode))
        assertNotNull(context.getString(R.string.compare_this_adjustment))
    }

    /** Builds an ARGB image from a colour function and an ALPHA_8 mask from a predicate. */
    private fun scene(w: Int, h: Int, color: (Int, Int) -> Int, selected: (Int, Int) -> Boolean): Pair<Bitmap, Bitmap> {
        val img = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val px = IntArray(w * h)
        val mk = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            px[y * w + x] = color(x, y)
            mk[y * w + x] = if (selected(x, y)) (255 shl 24) else 0
        }
        img.setPixels(px, 0, w, 0, 0, w, h)
        mask.setPixels(mk, 0, w, 0, 0, w, h)
        return img to mask
    }

    private fun alphaAt(b: Bitmap, x: Int, y: Int) = (b.getPixel(x, y) ushr 24) and 0xFF

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `refine edge finds a soft horizontal edge the rough selection missed`() {
        // Red subject below y = 20, a 50% red/blue mixed row at y = 20, blue above.
        // The rough selection starts 2 rows too high (y >= 18).
        val red = Color.rgb(220, 30, 30)
        val blue = Color.rgb(30, 60, 220)
        val mixed = Color.rgb(125, 45, 125)
        val (img, mask) = scene(64, 64, { _, y -> when { y < 20 -> blue; y == 20 -> mixed; else -> red } }, { _, y -> y >= 18 })
        val cfg = com.example.engine.RefineEdgeConfig(radiusPx = 6, smartRadius = false, smoothPx = 0, contrastPercent = 0f)
        val result = com.example.engine.RefineEdgeEngine.refineEdges(img, mask, cfg).refinedMask
        assertTrue("background above the edge must be dropped (was ${alphaAt(result, 32, 18)})", alphaAt(result, 32, 18) < 60)
        val edge = alphaAt(result, 32, 20)
        assertTrue("mixed edge row should be partial (was $edge)", edge in 70..190)
        assertTrue("subject below must stay selected (was ${alphaAt(result, 32, 24)})", alphaAt(result, 32, 24) > 200)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `refine edge recovers a thin hair strand outside the rough selection`() {
        val red = Color.rgb(200, 40, 40)
        val blue = Color.rgb(40, 70, 210)
        // Subject block for y >= 40, one-pixel "hair" at x = 30 from y = 28 to 39; the selection only covers the block.
        val (img, mask) = scene(64, 64, { x, y -> if (y >= 40 || (x == 30 && y in 28..39)) red else blue }, { _, y -> y >= 40 })
        val cfg = com.example.engine.RefineEdgeConfig(radiusPx = 14, smartRadius = false, hairRefine = true, smoothPx = 0, contrastPercent = 0f)
        val result = com.example.engine.RefineEdgeEngine.refineEdges(img, mask, cfg).refinedMask
        assertTrue("hair strand pixel should be selected (was ${alphaAt(result, 30, 33)})", alphaAt(result, 30, 33) > 150)
        assertTrue("background next to the strand should not (was ${alphaAt(result, 35, 33)})", alphaAt(result, 35, 33) < 80)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `decontamination keeps the layer transparency`() {
        val red = Color.argb(128, 220, 30, 30)
        val blue = Color.argb(128, 30, 60, 220)
        val mixed = Color.argb(128, 125, 45, 125)
        val (img, mask) = scene(48, 48, { _, y -> when { y < 20 -> blue; y == 20 -> mixed; else -> red } }, { _, y -> y >= 19 })
        val cfg = com.example.engine.RefineEdgeConfig(radiusPx = 5, smartRadius = false, smoothPx = 0, contrastPercent = 0f, decontaminateColors = true, decontaminateAmount = 1f)
        val out = com.example.engine.RefineEdgeEngine.refineEdges(img, mask, cfg).decontaminatedSource!!
        assertEquals(128, (out.getPixel(24, 20) ushr 24) and 0xFF)
        // The edge colour moves towards the subject colour (less blue).
        assertTrue(Color.blue(out.getPixel(24, 20)) < Color.blue(mixed))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `selection history keeps many compressed steps and restores them exactly`() {
        val history = com.example.engine.SelectionHistory(maxEntries = 30)
        val w = 120
        val h = 80
        fun maskWithBar(x0: Int): Bitmap {
            val m = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val px = IntArray(w * h) { i -> if (i % w in x0 until x0 + 10) (255 shl 24) else 0 }
            m.setPixels(px, 0, w, 0, 0, w, h)
            return m
        }
        // 20 steps: bar moves right each time.
        for (step in 0 until 20) history.push(maskWithBar(step * 5))
        assertTrue(history.canUndo)
        var current = maskWithBar(100)
        for (step in 19 downTo 0) {
            current = history.undo(current)!!
            assertEquals("step $step left edge", 255, (current.getPixel(step * 5, 10) ushr 24) and 0xFF)
            assertEquals("step $step outside", 0, (current.getPixel((step * 5 + 15) % w, 10) ushr 24) and 0xFF)
        }
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        val redone = history.redo(current)!!
        assertEquals(255, (redone.getPixel(5, 10) ushr 24) and 0xFF)
    }

    private fun redSquareScene(width: Int, height: Int, background: (Int, Int) -> Int, x0: Int, y0: Int, x1: Int, y1: Int): Pair<Bitmap, Bitmap> {
        val src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val px = IntArray(width * height)
        val mk = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val inside = x in x0..x1 && y in y0..y1
            px[y * width + x] = if (inside) Color.RED else background(x, y)
            mk[y * width + x] = if (inside) (255 shl 24) else 0
        }
        src.setPixels(px, 0, width, 0, 0, width, height)
        mask.setPixels(mk, 0, width, 0, 0, width, height)
        return src to mask
    }

    @Test
    fun `fast marching removal does not leave a black blob`() {
        val (src, mask) = redSquareScene(60, 60, { _, _ -> Color.BLUE }, 22, 22, 37, 37)
        val result = com.example.engine.InpaintEngine.inpaint(src, mask, com.example.engine.InpaintEngineMode.FAST_MARCHING)
        val c = result.getPixel(30, 30)
        assertTrue("Center must be blue, was ${Integer.toHexString(c)}", Color.blue(c) > 200 && Color.red(c) < 40)
    }

    @Test
    fun `patch synthesis rebuilds surrounding texture instead of a colored blob`() {
        // Vertical black/white stripes, 4px wide, with a red object in the middle.
        val (src, mask) = redSquareScene(80, 80, { x, _ -> if ((x / 4) % 2 == 0) Color.BLACK else Color.WHITE }, 30, 30, 49, 49)
        val result = com.example.engine.InpaintEngine.inpaint(src, mask, com.example.engine.InpaintEngineMode.AI_PATCH_SYNTHESIS)
        for (y in 32..47 step 5) for (x in 32..47 step 5) {
            val c = result.getPixel(x, y)
            // Neutral (black / white / grey) — no red left from the object.
            assertTrue("Pixel ($x,$y) still reddish: ${Integer.toHexString(c)}", abs(Color.red(c) - Color.green(c)) < 40 && abs(Color.green(c) - Color.blue(c)) < 40)
        }
        // Untouched far away pixels stay exactly the same.
        assertEquals(src.getPixel(2, 2), result.getPixel(2, 2))
    }

    @Test
    fun `patch synthesis also fills an object touching the image border`() {
        val (src, mask) = redSquareScene(64, 64, { _, _ -> Color.GREEN }, 0, 20, 10, 40)
        val result = com.example.engine.InpaintEngine.inpaint(src, mask, com.example.engine.InpaintEngineMode.AI_PATCH_SYNTHESIS)
        val c = result.getPixel(0, 30)
        assertTrue("Border pixel must be green, was ${Integer.toHexString(c)}", Color.green(c) > 200 && Color.red(c) < 40)
    }

    @Test
    fun `inpaint engine criminisi texture synthesis fills masked region seamlessly`() {
        val width = 40
        val height = 40
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            // Mask a small 6x6 square in the middle
            for (y in 17..22) {
                for (x in 17..22) {
                    setPixel(x, y, Color.WHITE)
                }
            }
        }

        val result = com.example.engine.InpaintEngine.inpaint(
            source = source,
            maskBitmap = mask,
            mode = com.example.engine.InpaintEngineMode.AI_PATCH_SYNTHESIS,
            smartEdgeSnap = true,
            seamlessBlend = true
        )

        assertNotNull(result)
        assertEquals(width, result.width)
        assertEquals(height, result.height)

        // Verify center pixel is filled with background texture (blue)
        val centerPixel = result.getPixel(20, 20)
        assertEquals(Color.blue(Color.BLUE), Color.blue(centerPixel))
    }

    @Test
    fun `inpaint engine string resources are present and localized`() {
        assertNotNull(context.getString(R.string.inpaint_engine_title))
        assertNotNull(context.getString(R.string.inpaint_engine_ai))
        assertNotNull(context.getString(R.string.inpaint_engine_telea))
        assertNotNull(context.getString(R.string.inpaint_smart_snap))
        assertNotNull(context.getString(R.string.inpaint_seamless_blend))
    }
}
