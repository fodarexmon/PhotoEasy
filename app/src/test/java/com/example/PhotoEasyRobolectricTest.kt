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
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
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
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
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

    private fun stripOf(vararg colors: Int): Bitmap {
        val b = Bitmap.createBitmap(colors.size, 1, Bitmap.Config.ARGB_8888)
        b.setPixels(colors, 0, colors.size, 0, 0, colors.size, 1)
        return b
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `warm filter keeps highlight detail instead of clipping`() {
        val src = stripOf(Color.rgb(230, 230, 230), Color.rgb(245, 245, 245))
        val out = com.example.engine.ImageProcessor.applyFilter(src, FilterType.WARM)
        // The old colour matrix (1.2·r + 20) pushed both to 255.
        assertTrue("highlights merged: ${Color.red(out.getPixel(0, 0))} vs ${Color.red(out.getPixel(1, 0))}",
            Color.red(out.getPixel(1, 0)) > Color.red(out.getPixel(0, 0)))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `filter intensity keeps the layer transparency`() {
        val src = stripOf(Color.argb(128, 120, 90, 60))
        val out = com.example.engine.ImageProcessor.applyFilter(src, FilterType.COOL, intensity = 0.5f)
        assertEquals(128, (out.getPixel(0, 0) ushr 24) and 0xFF)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `solarize has no jump around mid grey`() {
        val src = stripOf(Color.rgb(127, 127, 127), Color.rgb(129, 129, 129))
        val out = com.example.engine.ImageProcessor.applyFilter(src, FilterType.SOLARIZE)
        assertTrue(kotlin.math.abs(Color.red(out.getPixel(0, 0)) - Color.red(out.getPixel(1, 0))) <= 4)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `identity cube lut leaves colours unchanged and invalid files are rejected`() {
        val text = """
            TITLE "Identity"
            LUT_3D_SIZE 2
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 1
        """.trimIndent()
        val lut = com.example.engine.CubeLut.parse(text, "x")
        assertEquals("Identity", lut.title)
        val c = Color.argb(200, 37, 140, 222)
        val out = com.example.engine.ImageProcessor.applyLut(stripOf(c), lut).getPixel(0, 0)
        assertEquals(200, (out ushr 24) and 0xFF)
        assertTrue(kotlin.math.abs(Color.red(out) - 37) <= 1)
        assertTrue(kotlin.math.abs(Color.green(out) - 140) <= 1)
        assertTrue(kotlin.math.abs(Color.blue(out) - 222) <= 1)

        var rejected = false
        try { com.example.engine.CubeLut.parse("LUT_3D_SIZE 2\n0 0 0\n1 1 1", "bad") } catch (e: IllegalArgumentException) { rejected = true }
        assertTrue("incomplete LUT must be rejected", rejected)
    }

    @Test
    fun `every preset filter has a recipe or a special implementation`() {
        val special = setOf(FilterType.NONE, FilterType.PIXELATE, FilterType.EDGES, FilterType.GLITCH, FilterType.SOLARIZE)
        for (f in FilterType.values()) {
            if (f in special) continue
            assertNotNull("missing recipe for $f", com.example.engine.FilterRecipes.recipeFor(f))
        }
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
        val cfg = com.example.engine.RefineEdgeConfig(radiusPx = 6, smartRadius = false, smoothPx = 0f, contrastPercent = 0f)
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
        val cfg = com.example.engine.RefineEdgeConfig(radiusPx = 14, smartRadius = false, hairRefine = true, smoothPx = 0f, contrastPercent = 0f)
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
        val cfg = com.example.engine.RefineEdgeConfig(radiusPx = 5, smartRadius = false, smoothPx = 0f, contrastPercent = 0f, decontaminateColors = true, decontaminateAmount = 1f)
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
    fun `removal keeps the background texture contrast instead of a pale ghost`() {
        // High-contrast 2px stripes; averaging all patches would turn the hole into flat grey.
        val (src, mask) = redSquareScene(80, 80, { x, _ -> if ((x / 2) % 2 == 0) Color.BLACK else Color.WHITE }, 28, 28, 51, 51)
        val result = com.example.engine.InpaintEngine.inpaint(src, mask, com.example.engine.InpaintEngineMode.AI_PATCH_SYNTHESIS)
        fun lumStd(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var sum = 0.0; var sq = 0.0; var n = 0
            for (y in y0..y1) for (x in x0..x1) {
                val c = bmp.getPixel(x, y)
                val l = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
                sum += l; sq += l * l; n++
            }
            val mean = sum / n
            return kotlin.math.sqrt(sq / n - mean * mean)
        }
        val inside = lumStd(result, 34, 34, 45, 45)
        val outside = lumStd(src, 2, 2, 13, 13)
        assertTrue("fill lost the texture contrast: std $inside vs background $outside", inside > outside * 0.55)
    }

    @Test
    fun `removal also takes the object's coloured glow beyond the mask`() {
        val glow = Color.rgb(235, 95, 95)
        val blue = Color.rgb(40, 70, 210)
        // Object 20..39, a 5px reddish glow around it that the mask does NOT cover.
        val (src, mask) = redSquareScene(64, 64, { x, y ->
            val d = maxOf(maxOf(20 - x, x - 39), maxOf(20 - y, y - 39))
            if (d in 1..5) glow else blue
        }, 20, 20, 39, 39)
        val result = com.example.engine.InpaintEngine.inpaint(src, mask, com.example.engine.InpaintEngineMode.AI_PATCH_SYNTHESIS)
        // Outermost glow ring (5px outside the mask) must be background-coloured now.
        val c = result.getPixel(30, 44)
        assertTrue("glow remnant left behind: ${Integer.toHexString(c)}", Color.blue(c) > Color.red(c))
        assertEquals("untouched background", blue, result.getPixel(2, 2))
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

    // --- Magic wand engine ---------------------------------------------------------------

    private fun wandSource(w: Int, h: Int, colorAt: (Int, Int) -> Int): com.example.engine.WandSource {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) bmp.setPixel(x, y, colorAt(x, y))
        return com.example.engine.MagicWandEngine.buildSource(bmp, w, h)
    }

    private fun wand(
        src: com.example.engine.WandSource, x: Int, y: Int, tol: Int, contiguous: Boolean = true,
        options: com.example.engine.WandOptions = com.example.engine.WandOptions(softEdges = false)
    ): ByteArray = com.example.engine.MagicWandEngine.computeMask(src, x + 0.5f, y + 0.5f, tol, contiguous, options, null)!!

    private fun ByteArray.at(w: Int, x: Int, y: Int) = this[y * w + x].toInt() and 0xFF

    @Test
    fun magicWand_contiguousStopsAtEdge_globalFindsSameColourElsewhere() {
        // Red left half, blue right half with a separate red block inside the blue.
        val src = wandSource(60, 40) { x, y -> if (x < 30 || (x in 45..50 && y in 15..20)) Color.RED else Color.BLUE }
        val contiguous = wand(src, 5, 5, 30)
        assertEquals(255, contiguous.at(60, 10, 10))
        assertEquals(0, contiguous.at(60, 40, 10))
        assertEquals(0, contiguous.at(60, 47, 17))
        val global = wand(src, 5, 5, 30, contiguous = false)
        assertEquals(255, global.at(60, 47, 17))
        assertEquals(0, global.at(60, 40, 10))
    }

    @Test
    fun magicWand_cleanNoiseFillsPinholes() {
        val src = wandSource(50, 50) { x, y -> if (x == 25 && y == 25) Color.WHITE else Color.rgb(200, 30, 30) }
        val noisy = wand(src, 5, 5, 25, options = com.example.engine.WandOptions(cleanNoise = false, softEdges = false))
        assertEquals(0, noisy.at(50, 25, 25))
        val clean = wand(src, 5, 5, 25, options = com.example.engine.WandOptions(cleanNoise = true, softEdges = false))
        assertEquals(255, clean.at(50, 25, 25))
    }

    @Test
    fun magicWand_sampleSizeIgnoresOddSeedPixel() {
        val src = wandSource(40, 40) { x, y -> if (x == 20 && y == 20) Color.GREEN else Color.rgb(180, 40, 40) }
        val point = wand(src, 20, 20, 30, options = com.example.engine.WandOptions(sampleSize = 1, cleanNoise = false, softEdges = false))
        assertEquals(0, point.at(40, 5, 5))
        val averaged = wand(src, 20, 20, 30, options = com.example.engine.WandOptions(sampleSize = 5, cleanNoise = false, softEdges = false))
        assertEquals(255, averaged.at(40, 5, 5))
    }

    @Test
    fun magicWand_smartGradientFollowsSmoothRamp() {
        val src = wandSource(200, 10) { x, _ -> val v = 40 + x * 160 / 199; Color.rgb(v, v, v) }
        val plain = wand(src, 0, 5, 15)
        assertEquals(0, plain.at(200, 199, 5))
        val smart = wand(src, 0, 5, 15, options = com.example.engine.WandOptions(smartGradient = true, softEdges = false))
        assertEquals(255, smart.at(200, 199, 5))
    }

    @Test
    fun magicWand_softEdgesGradeTheBoundary() {
        val src = wandSource(40, 20) { x, _ -> if (x < 20) Color.rgb(200, 30, 30) else Color.rgb(30, 30, 200) }
        val soft = wand(src, 2, 10, 30, options = com.example.engine.WandOptions(softEdges = true))
        assertEquals(255, soft.at(40, 5, 10))
        assertEquals(0, soft.at(40, 35, 10))
        val edge = soft.at(40, 19, 10)
        assertTrue("edge pixel should be partially selected, was $edge", edge in 1..254)
    }

    // --- Layers and masks ------------------------------------------------------------------

    @Test
    fun linkedMask_followsLayerMove_unlinkedStays() {
        val layer = Layer(id = "lm", name = "L", type = LayerType.IMAGE)
        layer.maskBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ALPHA_8)
        layer.ensureMaskAnchor()
        assertEquals(null, LayerRenderer.linkedMaskMatrix(layer, 100, 100))
        layer.positionX = 10f
        val m = LayerRenderer.linkedMaskMatrix(layer, 100, 100)
        assertNotNull(m)
        val p = floatArrayOf(20f, 20f)
        m!!.mapPoints(p)
        assertEquals(30f, p[0], 0.01f)
        assertEquals(20f, p[1], 0.01f)
        layer.isMaskLinked = false
        assertEquals(null, LayerRenderer.linkedMaskMatrix(layer, 100, 100))
    }

    @Test
    fun clippingMask_showsOnlyWhereBaseIsOpaque() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val baseBmp = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        for (y in 0 until 10) for (x in 0 until 10) baseBmp.setPixel(x, y, Color.RED)
        val topBmp = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val base = Layer(id = "base", name = "Base", type = LayerType.IMAGE, bitmap = baseBmp)
        val top = Layer(id = "top", name = "Top", type = LayerType.IMAGE, bitmap = topBmp, isClipped = true)
        val out = LayerRenderer.renderCompositeCanvas(context, listOf(base, top), 20, 10, includeBackground = false)
        assertEquals(Color.BLUE, out.getPixel(5, 5))
        assertEquals(0, (out.getPixel(15, 5) ushr 24) and 0xFF)
    }

    @Test
    fun maskBrush_opacityCapsCoverageWithinOneStroke() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.viewmodel.EditorViewModel(app)
        val project = Project(name = "Brush", width = 100, height = 100)
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        project.layers.add(Layer(id = "b", name = "B", type = LayerType.IMAGE, bitmap = bmp))
        viewModel.openProject(project)
        viewModel.addLayerMask(fromSelection = false)
        viewModel.hideAllLayerMask()
        viewModel.setMaskBrushHardness(1f)
        viewModel.setMaskBrushOpacity(0.5f)
        val pts = listOf(android.graphics.PointF(50f, 50f), android.graphics.PointF(52f, 50f), android.graphics.PointF(50f, 50f))
        viewModel.paintMaskSegment(pts, 10f, isReveal = true, phase = com.example.ui.viewmodel.StrokePhase.START)
        viewModel.paintMaskSegment(pts, 10f, isReveal = true, phase = com.example.ui.viewmodel.StrokePhase.END)
        val mask = viewModel.uiState.value.currentProject!!.layers[0].maskBitmap!!
        val a = (mask.getPixel(50, 50) ushr 24) and 0xFF
        assertTrue("half-opacity stroke should paint mid grey, was $a", a in 120..135)
        assertEquals(0, (mask.getPixel(5, 5) ushr 24) and 0xFF)
    }

    @Test
    fun moveLayer_reordersAndKeepsSelection() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.viewmodel.EditorViewModel(app)
        val project = Project(name = "Order", width = 10, height = 10)
        for (n in listOf("a", "b", "c")) {
            project.layers.add(Layer(id = n, name = n, type = LayerType.IMAGE, bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)))
        }
        viewModel.openProject(project)
        viewModel.selectLayer(0)
        viewModel.moveLayer(0, 2)
        val layers = viewModel.uiState.value.currentProject!!.layers
        assertEquals(listOf("b", "c", "a"), layers.map { it.id })
        assertEquals(2, viewModel.uiState.value.selectedLayerIndex)
        viewModel.renameLayer(2, "  Sky  ")
        assertEquals("Sky", viewModel.uiState.value.currentProject!!.layers[2].name)
    }

    // --- Text tool ---------------------------------------------------------------------------

    @Test
    fun textBox_hugsShortText_andWrapsAtMaxWidth() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val short = Layer(id = "t", name = "T", type = LayerType.TEXT, textConfig = TextLayerConfig(text = "Hi"))
        val (w, _) = LayerRenderer.measureTextLayer(context, short, 1000f)
        assertTrue("short text box should hug the text, was $w", w < 400f)
        val long = Layer(
            id = "t2", name = "T", type = LayerType.TEXT,
            textConfig = TextLayerConfig(text = "word ".repeat(80), maxLineWidthPercent = 50f)
        )
        val (lw, lh) = LayerRenderer.measureTextLayer(context, long, 1000f)
        assertTrue("long text must wrap within 50% of the document, was $lw", lw <= 501f)
        assertTrue(lh > 100f)
    }

    @Test
    fun textBackground_enlargesTheLayerBox() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val plain = Layer(id = "a", name = "A", type = LayerType.TEXT, textConfig = TextLayerConfig(text = "Hello"))
        val boxed = plain.copy(id = "b", textConfig = plain.textConfig.copy(backgroundEnabled = true, backgroundPadding = 10f))
        val (pw, ph) = LayerRenderer.measureTextLayer(context, plain, 500f)
        val (bw, bh) = LayerRenderer.measureTextLayer(context, boxed, 500f)
        assertEquals(pw + 20f, bw, 0.5f)
        assertEquals(ph + 20f, bh, 0.5f)
    }

    @Test
    fun commitPendingHistory_doesNotAddEmptyUndoSteps() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.viewmodel.EditorViewModel(app)
        val project = Project(name = "History", width = 200, height = 200)
        project.layers.add(Layer(id = "t", name = "T", type = LayerType.TEXT, textConfig = TextLayerConfig(text = "A")))
        viewModel.openProject(project)
        viewModel.commitPendingHistory()
        assertFalse("opening and closing the text panel must not create an undo step", viewModel.uiState.value.canUndo)
        viewModel.updateTextConfigTransient(TextLayerConfig(text = "AB"))
        viewModel.commitPendingHistory()
        assertTrue(viewModel.uiState.value.canUndo)
    }

    @Test
    fun newTextLayer_hasVisiblePlaceholderAndAutoName() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.viewmodel.EditorViewModel(app)
        viewModel.openProject(Project(name = "Txt", width = 200, height = 200))
        viewModel.addTextLayer("")
        val layer = viewModel.uiState.value.currentProject!!.layers.last()
        assertTrue(layer.textConfig.text.isNotBlank())
        viewModel.updateTextConfigTransient(layer.textConfig.copy(text = "Sale"))
        val renamed = viewModel.uiState.value.currentProject!!.layers.last()
        assertTrue("auto name follows the text, was ${renamed.name}", renamed.name.contains("Sale"))
    }

    // --- Adjustments & Smart Auto -------------------------------------------------------------

    @Test
    fun brightness_keepsPureBlackAndWhite() {
        val src = Bitmap.createBitmap(3, 1, Bitmap.Config.ARGB_8888)
        src.setPixel(0, 0, Color.BLACK); src.setPixel(1, 0, Color.rgb(128, 128, 128)); src.setPixel(2, 0, Color.WHITE)
        val out = com.example.engine.ImageProcessor.applyAdjustments(src, ColorAdjustments(brightness = 60f))
        assertEquals(0, Color.red(out.getPixel(0, 0)))
        assertEquals(255, Color.red(out.getPixel(2, 0)))
        assertTrue("mid grey should get brighter", Color.red(out.getPixel(1, 0)) > 150)
    }

    @Test
    fun hslBands_areIndependentPerRange() {
        val a = ColorAdjustments()
            .withHslBand("REDS", com.example.data.model.HslBand(hue = 20f))
            .withHslBand("BLUES", com.example.data.model.HslBand(saturation = -50f))
        assertEquals(20f, a.hslBand("REDS").hue, 0.001f)
        assertEquals(-50f, a.hslBand("BLUES").saturation, 0.001f)
        // Legacy single-range values migrate instead of moving to the newly chosen range.
        val legacy = ColorAdjustments(hueShift = 30f, hslColorRange = "GREENS").migrateHsl().copy(hslColorRange = "BLUES")
        assertEquals(30f, legacy.hslBand("GREENS").hue, 0.001f)
        assertEquals(0f, legacy.hslBand("BLUES").hue, 0.001f)
    }

    @Test
    fun colorReplace_takesShortestWayRoundHueCircle() {
        val src = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val hsv = floatArrayOf(350f, 1f, 1f)
        src.setPixel(0, 0, Color.HSVToColor(hsv))
        val adj = ColorAdjustments(
            replaceColorEnabled = true,
            replaceSourceColorHex = Color.HSVToColor(hsv),
            replaceTolerance = 100f,
            replaceTargetHue = 10f
        )
        val out = com.example.engine.ImageProcessor.applyAdjustments(src, adj)
        val outHsv = FloatArray(3)
        Color.colorToHSV(out.getPixel(0, 0), outHsv)
        val distFromRed = minOf(outHsv[0], 360f - outHsv[0])
        assertTrue("hue should stay near red, was ${outHsv[0]}", distFromRed < 20f)
    }

    @Test
    fun vignette_negativeLightensCorners() {
        val src = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(100, 100, 100)) }
        val out = com.example.engine.ImageProcessor.applyAdjustments(src, ColorAdjustments(vignette = -80f))
        assertTrue(Color.red(out.getPixel(0, 0)) > 120)
        assertEquals(100, Color.red(out.getPixel(25, 25)))
    }

    @Test
    fun smartAuto_brightensDarkImage_andKeepsUserCurves() {
        val dark = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 64) {
            val v = 10 + (x + y) / 3
            dark.setPixel(x, y, Color.rgb(v, v, v))
        }
        val curve = listOf(android.graphics.PointF(0f, 0f), android.graphics.PointF(128f, 150f), android.graphics.PointF(255f, 255f))
        val current = ColorAdjustments(curvesRgb = curve)
        val delta = com.example.engine.ImageProcessor.computeSmartAutoAdjustments(dark, current)
        assertTrue("dark image needs more exposure, was ${delta.exposure}", delta.exposure > 10f)
        val combined = com.example.engine.ImageProcessor.combineSmartAuto(current, delta, 1f)
        assertEquals(3, combined.curvesRgb.size)
        // Half strength = half of the correction.
        val half = com.example.engine.ImageProcessor.combineSmartAuto(current, delta, 0.5f)
        assertEquals(delta.exposure / 2f, half.exposure, 0.01f)
    }

    // --- One Undo / Redo timeline for document and selection edits ---------------------------

    private fun waitUntil(condition: () -> Boolean) {
        repeat(300) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(10)
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue("condition not reached", condition())
    }

    @Test
    fun mainUndo_undoesSelectionAndDocumentEditsInOrder() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.viewmodel.EditorViewModel(app)
        val project = Project(name = "Timeline", width = 60, height = 60)
        project.layers.add(Layer(id = "img", name = "Img", type = LayerType.IMAGE, bitmap = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)))
        viewModel.openProject(project)
        fun hasSelection() = viewModel.uiState.value.selectionMask?.let { SelectionEngine.hasSelection(it) } == true

        // 1. Selection edit, 2. document edit.
        viewModel.selectAll()
        waitUntil { hasSelection() && viewModel.uiState.value.canUndo }
        viewModel.toggleSelectedLayerVisibility()
        assertFalse(viewModel.uiState.value.currentProject!!.layers[0].isVisible)

        // Undo #1 reverts the document edit and keeps the selection.
        viewModel.undo()
        waitUntil { viewModel.uiState.value.currentProject!!.layers[0].isVisible }
        assertTrue(hasSelection())

        // Undo #2 reverts the selection.
        viewModel.undo()
        waitUntil { !hasSelection() }

        // Redo replays them in the same order.
        viewModel.redo()
        waitUntil { hasSelection() }
        assertTrue(viewModel.uiState.value.currentProject!!.layers[0].isVisible)
        viewModel.redo()
        waitUntil { !viewModel.uiState.value.currentProject!!.layers[0].isVisible }
    }

    // --- Curves ------------------------------------------------------------------------------

    @Test
    fun curveLut_identityAndMonotone() {
        val id = com.example.engine.ImageProcessor.buildCurveLut(listOf(android.graphics.PointF(0f, 0f), android.graphics.PointF(255f, 255f)))
        for (i in 0..255) assertEquals(i, id[i])
        // Steep, unevenly spaced points: a monotone spline never dips (no inverted tones).
        val pts = listOf(
            android.graphics.PointF(0f, 0f), android.graphics.PointF(10f, 200f),
            android.graphics.PointF(20f, 210f), android.graphics.PointF(255f, 255f)
        )
        val lut = com.example.engine.ImageProcessor.buildCurveLut(pts)
        for (i in 1..255) assertTrue("curve must not go down at $i", lut[i] >= lut[i - 1])
        assertEquals(200, lut[10])
        assertEquals(210, lut[20])
    }

    @Test
    fun curveLut_movedEndPointSetsInputBlackPoint() {
        val lut = com.example.engine.ImageProcessor.buildCurveLut(listOf(android.graphics.PointF(20f, 0f), android.graphics.PointF(255f, 255f)))
        assertEquals(0, lut[0])
        assertEquals(0, lut[19])
        assertEquals(255, lut[255])
    }

    @Test
    fun sampleColorForCurves_readsLayerColourUnderPoint() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.viewmodel.EditorViewModel(app)
        val project = Project(name = "Pick", width = 40, height = 40)
        val bmp = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 40, 20)) }
        project.layers.add(Layer(id = "p", name = "P", type = LayerType.IMAGE, bitmap = bmp))
        viewModel.openProject(project)
        val c = viewModel.sampleColorForCurves(20f, 20f)
        assertNotNull(c)
        assertEquals(200, Color.red(c!!))
        assertEquals(40, Color.green(c))
    }

    // --- Smart removal ---------------------------------------------------------------------------

    private fun squareMask(w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): Bitmap {
        val m = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in y0..y1) for (x in x0..x1) m.setPixel(x, y, Color.WHITE)
        return m
    }

    @Test
    fun smartRemoval_thinScratchDisappearsSeamlessly() {
        val src = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(120, 130, 140)) }
        for (x in 5 until 75) for (y in 39..41) src.setPixel(x, y, Color.BLACK)
        val mask = squareMask(80, 80, 5, 39, 74, 41)
        val out = com.example.engine.InpaintEngine.inpaint(src, mask, com.example.engine.InpaintEngineMode.SMART)
        val p = out.getPixel(40, 40)
        assertTrue("scratch should be filled with the surrounding grey", abs(Color.red(p) - 120) < 12 && abs(Color.blue(p) - 140) < 12)
    }

    @Test
    fun smartRemoval_keepsAHorizontalEdgeAcrossTheHole() {
        val src = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        for (y in 0 until 120) for (x in 0 until 120) src.setPixel(x, y, if (y < 60) Color.rgb(200, 40, 40) else Color.rgb(40, 40, 200))
        val mask = squareMask(120, 120, 40, 40, 79, 79)
        val out = com.example.engine.InpaintEngine.inpaint(src, mask, com.example.engine.InpaintEngineMode.SMART)
        val above = out.getPixel(60, 50)
        val below = out.getPixel(60, 70)
        assertTrue("above the edge should stay red", Color.red(above) > Color.blue(above))
        assertTrue("below the edge should stay blue", Color.blue(below) > Color.red(below))
    }

    @Test
    fun removal_neverCopiesFromProtectedArea() {
        val src = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        for (y in 0 until 120) for (x in 0 until 120) src.setPixel(x, y, if (y < 30) Color.rgb(220, 30, 30) else Color.rgb(30, 200, 30))
        val mask = squareMask(120, 120, 50, 70, 70, 90)
        // Protect all the green: the only allowed texture source is the red band.
        val protect = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        for (y in 30 until 120) for (x in 0 until 120) protect.setPixel(x, y, Color.WHITE)
        val out = com.example.engine.InpaintEngine.inpaint(
            src, mask, com.example.engine.InpaintEngineMode.AI_PATCH_SYNTHESIS,
            seamlessBlend = false, protectMask = protect
        )
        val p = out.getPixel(60, 80)
        assertTrue("fill must come from the unprotected red area, was ${Integer.toHexString(p)}", Color.red(p) > Color.green(p))
    }

    // --- Crop & perspective ----------------------------------------------------------------------

    @Test
    fun perspectiveAspect_recoversSquareSeenAtAnAngle() {
        // A 2x2 square on a tilted plane, seen by a pinhole camera (f = 500) centred on a 1000x1000 image.
        fun project(x: Double, y: Double): android.graphics.PointF {
            val z = 6.0 + 1.2 * y
            return android.graphics.PointF((500 + 500 * x / z).toFloat(), (500 + 500 * y / z).toFloat())
        }
        val quad = listOf(project(-1.0, -1.0), project(1.0, -1.0), project(1.0, 1.0), project(-1.0, 1.0))
        val ratio = com.example.engine.ImageProcessor.estimateRectAspect(quad, 1000, 1000)
        assertNotNull(ratio)
        assertEquals("a square must come out square", 1f, ratio!!, 0.08f)
        val (w, h) = com.example.engine.ImageProcessor.perspectiveOutputSize(quad, 1000, 1000, null)
        assertEquals(1f, w.toFloat() / h, 0.08f)
    }

    @Test
    fun perspectiveAspect_plainRectangleKeepsItsRatio() {
        val quad = listOf(
            android.graphics.PointF(200f, 400f), android.graphics.PointF(800f, 400f),
            android.graphics.PointF(800f, 700f), android.graphics.PointF(200f, 700f)
        )
        assertEquals(2f, com.example.engine.ImageProcessor.estimateRectAspect(quad, 1000, 1000)!!, 0.01f)
    }

    @Test
    fun rotateCanvas90_swapsSizeAndRotatesLayers() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.viewmodel.EditorViewModel(app)
        val project = Project(name = "Rot", width = 300, height = 200)
        project.layers.add(Layer(id = "r", name = "R", type = LayerType.IMAGE, bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888), positionX = 10f, positionY = 0f))
        viewModel.openProject(project)
        viewModel.rotateCanvas90(clockwise = true)
        val p = viewModel.uiState.value.currentProject!!
        assertEquals(200, p.width)
        assertEquals(300, p.height)
        assertEquals(90f, p.layers[0].rotation, 0.01f)
        // A point right of centre moves below centre.
        assertEquals(0f, p.layers[0].positionX, 0.01f)
        assertEquals(10f, p.layers[0].positionY, 0.01f)
        assertTrue(viewModel.uiState.value.canUndo)
    }

    @Test
    fun cropWithStraighten_rotatesLayersAndUsesFrameSize() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.viewmodel.EditorViewModel(app)
        val project = Project(name = "Straight", width = 400, height = 300)
        project.layers.add(Layer(id = "s", name = "S", type = LayerType.IMAGE, bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)))
        viewModel.openProject(project)
        viewModel.setCropStraighten(10f)
        viewModel.applyCrop(android.graphics.RectF(100f, 75f, 300f, 225f), null)
        val p = viewModel.uiState.value.currentProject!!
        assertEquals(200, p.width)
        assertEquals(150, p.height)
        assertEquals(10f, p.layers[0].rotation, 0.01f)
        assertEquals(0f, viewModel.uiState.value.cropStraighten, 0.001f)
    }

    @Test
    fun cropToExactSize_resamplesTheDocument() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.viewmodel.EditorViewModel(app)
        val project = Project(name = "Size", width = 400, height = 500)
        project.layers.add(Layer(id = "z", name = "Z", type = LayerType.IMAGE, bitmap = Bitmap.createBitmap(400, 500, Bitmap.Config.ARGB_8888)))
        viewModel.openProject(project)
        viewModel.setCropTargetSize(1080, 1350)
        viewModel.applyCrop(android.graphics.RectF(0f, 0f, 400f, 500f), viewModel.uiState.value.selectedAspectRatio)
        val p = viewModel.uiState.value.currentProject!!
        assertEquals(1080, p.width)
        assertEquals(1350, p.height)
        assertEquals(2.7f, p.layers[0].scaleX, 0.01f)
    }

    // --- Transform: skew & distort ---------------------------------------------------------------

    @Test
    fun layerMatrix_appliesFreeDistort() {
        val layer = Layer(id = "d", name = "D", type = LayerType.IMAGE, bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))
        layer.distort = floatArrayOf(0f, 0f, 2f, 0f, 1f, 1f, 0f, 1f)
        val m = SelectionEngine.getLayerMatrix(layer, 100, 100)
        val p = floatArrayOf(100f, 0f, 0f, 0f)
        m.mapPoints(p)
        assertEquals(200f, p[0], 0.5f)
        assertEquals(0f, p[1], 0.5f)
        assertEquals(0f, p[2], 0.5f)
    }

    @Test
    fun layerMatrix_appliesSkew() {
        val layer = Layer(id = "k", name = "K", type = LayerType.IMAGE, bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888), skewX = 45f)
        val m = SelectionEngine.getLayerMatrix(layer, 100, 100)
        val p = floatArrayOf(50f, 0f, 50f, 100f)
        m.mapPoints(p)
        // Top centre slides left by half the height, bottom centre right.
        assertEquals(0f, p[0], 0.5f)
        assertEquals(100f, p[2], 0.5f)
    }

    @Test
    fun resetTransform_clearsSkewAndDistort() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.viewmodel.EditorViewModel(app)
        val project = Project(name = "Reset", width = 100, height = 100)
        val layer = Layer(id = "t", name = "T", type = LayerType.IMAGE, bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888), skewX = 20f)
        layer.distort = floatArrayOf(0f, 0f, 1.2f, 0f, 1f, 1f, 0f, 1f)
        project.layers.add(layer)
        viewModel.openProject(project)
        viewModel.resetSelectedLayerTransform()
        val l = viewModel.uiState.value.currentProject!!.layers[0]
        assertEquals(0f, l.skewX, 0.001f)
        assertEquals(null, l.distort)
    }

    // --- Replace colour ----------------------------------------------------------------------------

    @Test
    fun replaceColor_legacyToleranceIsRepaired() {
        val buggy = ColorAdjustments(replaceColorEnabled = true, replaceTolerance = 0.35f)
        assertEquals(35f, buggy.effectiveReplacements()[0].tolerance, 0.01f)
        val old = ColorAdjustments(replaceColorEnabled = true, replaceTolerance = 40f)
        assertEquals(40f, old.effectiveReplacements()[0].tolerance, 0.01f)
    }

    @Test
    fun replaceColor_changesHueKeepsShadingAndSparesGreys() {
        val src = Bitmap.createBitmap(3, 1, Bitmap.Config.ARGB_8888)
        src.setPixel(0, 0, Color.rgb(220, 30, 30))   // bright red
        src.setPixel(1, 0, Color.rgb(110, 15, 15))   // dark red (same colour in shadow)
        src.setPixel(2, 0, Color.rgb(128, 128, 128)) // grey
        val adj = ColorAdjustments().withReplacements(listOf(
            com.example.data.model.ColorReplacement(sourceColor = Color.rgb(220, 30, 30), targetColor = Color.rgb(30, 60, 220), tolerance = 40f)
        ))
        val out = com.example.engine.ImageProcessor.applyAdjustments(src, adj)
        val bright = out.getPixel(0, 0)
        val dark = out.getPixel(1, 0)
        assertTrue("bright red becomes blue", Color.blue(bright) > Color.red(bright))
        assertTrue("shadowed red becomes blue too", Color.blue(dark) > Color.red(dark))
        assertTrue("shading is kept", Color.blue(dark) < Color.blue(bright))
        assertEquals(128f, Color.red(out.getPixel(2, 0)).toFloat(), 2f)
    }

    @Test
    fun replaceColor_previewMaskShowsAffectedArea() {
        val src = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        src.setPixel(0, 0, Color.rgb(220, 30, 30))
        src.setPixel(1, 0, Color.rgb(30, 200, 30))
        val adj = ColorAdjustments(replacePreviewMask = true).withReplacements(listOf(
            com.example.data.model.ColorReplacement(sourceColor = Color.rgb(220, 30, 30), targetColor = Color.BLUE, tolerance = 30f)
        ))
        val out = com.example.engine.ImageProcessor.applyAdjustments(src, adj)
        assertTrue(Color.red(out.getPixel(0, 0)) > 240)
        assertTrue(Color.red(out.getPixel(1, 0)) < 15)
    }

    @Test
    fun replaceColor_listSurvivesJsonRoundTrip() {
        val adj = ColorAdjustments().withReplacements(listOf(
            com.example.data.model.ColorReplacement(sourceColor = Color.RED, targetColor = Color.GREEN, tolerance = 22f, softness = 70f),
            com.example.data.model.ColorReplacement(sourceColor = Color.BLUE, targetColor = Color.YELLOW, enabled = false)
        ))
        val back = com.example.engine.AdjustmentsJson.fromJson(com.example.engine.AdjustmentsJson.toJson(adj))
        assertEquals(2, back.colorReplacements.size)
        assertEquals(22f, back.colorReplacements[0].tolerance, 0.01f)
        assertEquals(70f, back.colorReplacements[0].softness, 0.01f)
        assertFalse(back.colorReplacements[1].enabled)
    }

    // --- HSL -----------------------------------------------------------------------------------------

    @Test
    fun hslRanges_neighbouringWeightsAddUpToOne() {
        val w = com.example.data.model.HslRanges.weights(15f)
        val keys = com.example.data.model.HslRanges.keys
        assertEquals(0.5f, w[keys.indexOf("REDS")], 0.01f)
        assertEquals(0.5f, w[keys.indexOf("ORANGES")], 0.01f)
        assertEquals(1f, w.sum(), 0.01f)
        val orange = com.example.engine.ImageProcessor.hslRangeWeights(Color.rgb(255, 128, 0))
        assertEquals("ORANGES", orange.maxByOrNull { it.value }!!.key)
    }

    @Test
    fun hslLightness_darkensBlueWithoutGreying() {
        val src = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, Color.rgb(60, 110, 220)) }
        val adj = ColorAdjustments().withHslBand("BLUES", com.example.data.model.HslBand(lightness = -50f))
        val out = com.example.engine.ImageProcessor.applyAdjustments(src, adj).getPixel(0, 0)
        val before = FloatArray(3).also { Color.colorToHSV(Color.rgb(60, 110, 220), it) }
        val after = FloatArray(3).also { Color.colorToHSV(out, it) }
        assertTrue("darker", after[2] < before[2] - 0.1f)
        assertTrue("still clearly blue (not grey), saturation ${after[1]}", after[1] > before[1] * 0.8f)
        assertEquals(before[0], after[0], 12f)
    }

    @Test
    fun colorize_tintsGreys() {
        val src = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, Color.rgb(128, 128, 128)) }
        val adj = ColorAdjustments(colorizeEnabled = true, colorizeHue = 30f, colorizeSaturation = 50f)
        val out = com.example.engine.ImageProcessor.applyAdjustments(src, adj).getPixel(0, 0)
        assertTrue("warm tint expected", Color.red(out) > Color.blue(out) + 20)
    }

    // --- Selection refine ----------------------------------------------------------------------------


    private fun squareSelection(size: Int, x0: Int, y0: Int, x1: Int, y1: Int): Bitmap {
        val m = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        android.graphics.Canvas(m).drawRect(x0.toFloat(), y0.toFloat(), (x1 + 1).toFloat(), (y1 + 1).toFloat(), android.graphics.Paint().apply { color = Color.WHITE })
        return m
    }

    @Test
    fun growSelection_isRoundNotSquare() {
        val m = squareSelection(80, 40, 40, 40, 40) // a single pixel
        SelectionEngine.shiftEdge(m, 10f)
        assertTrue("10 px straight out is inside", alphaAt(m, 49, 40) > 128)
        assertTrue("7,7 diagonal (9.9 px) is inside", alphaAt(m, 47, 47) > 128)
        assertTrue("9,9 diagonal (12.7 px) stays outside: round, not square", alphaAt(m, 49, 49) < 64)
    }

    @Test
    fun shrinkSelection_movesEdgeInward() {
        val m = squareSelection(80, 20, 20, 59, 59)
        SelectionEngine.shiftEdge(m, -5f)
        assertTrue(alphaAt(m, 27, 40) > 200)
        assertTrue(alphaAt(m, 22, 40) < 30)
    }

    @Test
    fun cleanup_removesSpecksAndFillsHoles() {
        val m = squareSelection(100, 20, 20, 79, 79)
        android.graphics.Canvas(m).drawRect(50f, 50f, 52f, 52f, android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) })
        android.graphics.Canvas(m).drawRect(5f, 5f, 7f, 7f, android.graphics.Paint().apply { color = Color.WHITE })
        SelectionEngine.cleanupMask(m, 16)
        assertTrue("hole filled", alphaAt(m, 50, 50) > 200)
        assertTrue("speck removed", alphaAt(m, 5, 5) < 30)
    }

    @Test
    fun border_selectsBandAroundEdge() {
        val m = squareSelection(100, 20, 20, 79, 79)
        SelectionEngine.borderMask(m, 10f)
        assertTrue("on the edge", alphaAt(m, 20, 50) > 200)
        assertTrue("deep inside is not selected", alphaAt(m, 50, 50) < 30)
        assertTrue("far outside is not selected", alphaAt(m, 5, 50) < 30)
    }
    // --- Quick Selection ------------------------------------------------------------------------

    private fun qsImage(w: Int, h: Int, paint: (Int, Int) -> Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h) { paint(it % w, it / w) }
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    private fun labColor(l: Float, a: Float, b: Float): Int {
        val out = FloatArray(3)
        com.example.engine.ImageProcessor.labToRgbF(l, a, b, out)
        return Color.rgb((out[0] * 255f).toInt().coerceIn(0, 255), (out[1] * 255f).toInt().coerceIn(0, 255), (out[2] * 255f).toInt().coerceIn(0, 255))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun quickSelection_shortStrokeGrowsToObjectOutline() {
        // A 100 px red square on white; a tiny brush dab in the middle selects the whole square.
        val img = qsImage(200, 200) { x, y -> if (x in 50..149 && y in 50..149) Color.RED else Color.WHITE }
        val mask = Bitmap.createBitmap(200, 200, Bitmap.Config.ALPHA_8)
        SelectionEngine.quickSelection(mask, listOf(android.graphics.PointF(100f, 100f)), 4f, img, SelectionMode.ADD, tolerance = 35)
        assertTrue("near the square's corner (far from the brush)", alphaAt(mask, 55, 55) > 200)
        assertTrue("near the square's edge", alphaAt(mask, 147, 100) > 200)
        assertTrue("outside the square", alphaAt(mask, 20, 20) < 20)
        assertTrue("just outside the square", alphaAt(mask, 153, 100) < 40)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun quickSelection_stopsAtColourEdgeWithSameBrightness() {
        // Same lightness, opposite hue: a brightness-only edge detector would leak across.
        val left = labColor(55f, 45f, 10f)
        val right = labColor(55f, -45f, 10f)
        val img = qsImage(120, 80) { x, _ -> if (x < 60) left else right }
        val mask = Bitmap.createBitmap(120, 80, Bitmap.Config.ALPHA_8)
        SelectionEngine.quickSelection(mask, listOf(android.graphics.PointF(30f, 40f)), 6f, img, SelectionMode.ADD, tolerance = 60)
        assertTrue(alphaAt(mask, 50, 40) > 200)
        assertTrue("does not cross the hue edge", alphaAt(mask, 64, 40) < 20)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun quickSelection_multiColourObjectIsOneObject() {
        // A red / yellow striped block on blue: a stroke across the stripes takes the whole block.
        val img = qsImage(160, 160) { x, y ->
            if (x in 40..119 && y in 40..119) (if (((y - 40) / 8) % 2 == 0) Color.RED else Color.YELLOW) else Color.BLUE
        }
        val mask = Bitmap.createBitmap(160, 160, Bitmap.Config.ALPHA_8)
        val stroke = (44..76 step 4).map { android.graphics.PointF(80f, it.toFloat()) }
        SelectionEngine.quickSelection(mask, stroke, 5f, img, SelectionMode.ADD, tolerance = 35)
        assertTrue("red stripe far from the stroke", alphaAt(mask, 45, 110) > 200 || alphaAt(mask, 45, 106) > 200)
        assertTrue("yellow stripe far from the stroke", alphaAt(mask, 115, 50) > 200)
        assertTrue("blue background", alphaAt(mask, 20, 80) < 20)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun quickSelection_subtractRemovesTheObjectSmartly() {
        val img = qsImage(200, 200) { x, y -> if (x in 50..149 && y in 50..149) Color.RED else Color.WHITE }
        val mask = Bitmap.createBitmap(200, 200, Bitmap.Config.ALPHA_8).apply { eraseColor(Color.BLACK) }
        SelectionEngine.quickSelection(mask, listOf(android.graphics.PointF(100f, 100f)), 4f, img, SelectionMode.SUBTRACT, tolerance = 35)
        assertTrue("the square is removed", alphaAt(mask, 60, 140) < 40)
        assertTrue("the rest stays selected", alphaAt(mask, 20, 20) > 200)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun quickSelection_edgeIsAntiAliased() {
        // The source is half the document size: the edge is drawn with sub-pixel coverage.
        val img = qsImage(100, 100) { x, y -> if (x in 25..74 && y in 25..74) Color.RED else Color.WHITE }
        val mask = Bitmap.createBitmap(200, 200, Bitmap.Config.ALPHA_8)
        SelectionEngine.quickSelection(mask, listOf(android.graphics.PointF(100f, 100f)), 6f, img, SelectionMode.ADD, tolerance = 35)
        assertTrue(alphaAt(mask, 100, 100) > 240)
        val partial = (146..153).any { alphaAt(mask, it, 100) in 20..235 }
        assertTrue("soft (anti-aliased) edge values exist", partial)
    }

    @Test
    fun quickSelection_toleranceIsIndependentFromMagicWand() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.ui.viewmodel.EditorViewModel(app)
        vm.setMagicWandTolerance(80)
        vm.setQuickSelectionTolerance(20)
        vm.setQuickSelectionEdgeSensitivity(90)
        assertEquals(80, vm.uiState.value.magicWandTolerance)
        assertEquals(20, vm.uiState.value.quickSelectionTolerance)
        assertEquals(90, vm.uiState.value.quickSelectionEdgeSensitivity)
    }

    @Test
    fun quickSelection_guidedFilterKeepsFlatAreas() {
        val w = 20; val h = 20
        val g = FloatArray(w * h) { if (it % w < 10) 0f else 1f }
        val p = FloatArray(w * h) { if (it % w < 10) 0f else 1f }
        val q = com.example.engine.QuickSelectEngine.guidedFilter(g, p, w, h, 2, 0.0025f)
        assertTrue(q[5 * w + 2] < 0.05f)
        assertTrue(q[5 * w + 17] > 0.95f)
    }
    // --- Pro effects ---------------------------------------------------------------------------

    private fun fx(b: Bitmap, t: com.example.data.model.ProFilterType, p: com.example.engine.ProEffectParams) =
        com.example.engine.ProEffects.apply(b, t, p)

    private fun lumaAt(b: Bitmap, x: Int, y: Int): Int {
        val c = b.getPixel(x, y)
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
    }

    private fun noisyGray(w: Int, h: Int, seed: Long, amp: Int): Bitmap {
        val rnd = java.util.Random(seed)
        return qsImage(w, h) { _, _ ->
            val n = rnd.nextInt(2 * amp + 1) - amp
            val cr = rnd.nextInt(2 * amp + 1) - amp
            Color.rgb((128 + n + cr).coerceIn(0, 255), (128 + n).coerceIn(0, 255), (128 + n - cr).coerceIn(0, 255))
        }
    }

    private fun stdDev(b: Bitmap, ch: (Int) -> Int): Double {
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in 4 until b.height - 4) for (x in 4 until b.width - 4) {
            val v = ch(b.getPixel(x, y)).toDouble(); sum += v; sq += v * v; n++
        }
        val m = sum / n
        return kotlin.math.sqrt(sq / n - m * m)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun proGaussian_noDarkHaloAtTransparentEdges() {
        val img = qsImage(60, 60) { x, y -> if (x in 20..39 && y in 20..39) Color.RED else Color.TRANSPARENT }
        val out = fx(img, com.example.data.model.ProFilterType.GAUSSIAN_BLUR, com.example.engine.ProEffectParams(4f))
        val edge = out.getPixel(19, 30)
        assertTrue("edge is partly transparent", Color.alpha(edge) in 20..235)
        assertTrue("edge colour stays red, not darkened", Color.red(edge) > 240 && Color.green(edge) < 15)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun proGaussian_fractionalRadiusChangesResult() {
        val img = qsImage(40, 40) { x, _ -> if (x < 20) Color.BLACK else Color.WHITE }
        val a = fx(img, com.example.data.model.ProFilterType.GAUSSIAN_BLUR, com.example.engine.ProEffectParams(1.0f))
        val b = fx(img, com.example.data.model.ProFilterType.GAUSSIAN_BLUR, com.example.engine.ProEffectParams(1.6f))
        assertTrue(lumaAt(a, 18, 20) != lumaAt(b, 18, 20))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun proSharpen_amountSliderWorksAndThresholdProtectsNoise() {
        val img = qsImage(40, 40) { x, _ -> if (x < 20) Color.rgb(90, 90, 90) else Color.rgb(160, 160, 160) }
        val low = fx(img, com.example.data.model.ProFilterType.SHARPEN, com.example.engine.ProEffectParams(50f, 1.5f, 0f))
        val high = fx(img, com.example.data.model.ProFilterType.SHARPEN, com.example.engine.ProEffectParams(300f, 1.5f, 0f))
        assertTrue("more amount = stronger edge overshoot", lumaAt(high, 20, 20) > lumaAt(low, 20, 20))
        // Small noise below the threshold is left alone.
        val noisy = noisyGray(40, 40, 3L, 3)
        val kept = fx(noisy, com.example.data.model.ProFilterType.SHARPEN, com.example.engine.ProEffectParams(300f, 1f, 20f))
        assertTrue(abs(stdDev(kept) { Color.green(it) } - stdDev(noisy) { Color.green(it) }) < 1.0)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun proNoiseReduction_luminanceAndColourControlsWork() {
        val noisy = noisyGray(64, 64, 7L, 20)
        val none = fx(noisy, com.example.data.model.ProFilterType.NOISE_REDUCTION, com.example.engine.ProEffectParams(0f, 0f, 0f))
        assertEquals(noisy.getPixel(30, 30), none.getPixel(30, 30))
        val lum = fx(noisy, com.example.data.model.ProFilterType.NOISE_REDUCTION, com.example.engine.ProEffectParams(100f, 0f, 0f))
        assertTrue("luminance noise reduced", stdDev(lum) { Color.green(it) } < stdDev(noisy) { Color.green(it) } * 0.7)
        val col = fx(noisy, com.example.data.model.ProFilterType.NOISE_REDUCTION, com.example.engine.ProEffectParams(0f, 100f, 0f))
        val chroma: (Int) -> Int = { Color.red(it) - Color.blue(it) }
        assertTrue("colour noise reduced", stdDev(col, chroma) < stdDev(noisy, chroma) * 0.6)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun proMotionBlur_followsTheAngle() {
        val img = qsImage(80, 80) { x, _ -> if (x == 40) Color.WHITE else Color.BLACK }
        val out = fx(img, com.example.data.model.ProFilterType.MOTION_BLUR, com.example.engine.ProEffectParams(20f, 0f))
        assertTrue("spread sideways", lumaAt(out, 46, 40) > 0)
        assertTrue("peak is dimmed", lumaAt(out, 40, 40) < 128)
        val vertical = fx(img, com.example.data.model.ProFilterType.MOTION_BLUR, com.example.engine.ProEffectParams(20f, 90f))
        assertTrue("a vertical blur keeps the vertical line", lumaAt(vertical, 40, 40) > 240)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun proRadialBlur_centreAndModeMatter() {
        val img = noisyGray(80, 80, 11L, 60)
        val zoom = fx(img, com.example.data.model.ProFilterType.RADIAL_BLUR, com.example.engine.ProEffectParams(60f, centerX = 0.1f, centerY = 0.1f, mode = 0))
        val spin = fx(img, com.example.data.model.ProFilterType.RADIAL_BLUR, com.example.engine.ProEffectParams(60f, centerX = 0.1f, centerY = 0.1f, mode = 1))
        assertTrue("near the centre the image stays sharp", abs(lumaAt(zoom, 8, 8) - lumaAt(img, 8, 8)) < 40)
        assertTrue("far from the centre it is blurred", stdDev(zoom) { Color.green(it) } < stdDev(img) { Color.green(it) })
        assertTrue("spin differs from zoom", zoom.getPixel(70, 70) != spin.getPixel(70, 70))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun proEffect_fadeZeroKeepsOriginal() {
        val img = noisyGray(30, 30, 5L, 40)
        val out = fx(img, com.example.data.model.ProFilterType.GAUSSIAN_BLUR, com.example.engine.ProEffectParams(6f, fade = 0f))
        assertEquals(img.getPixel(10, 10), out.getPixel(10, 10))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun proTiltShift_bandStaysSharp() {
        val img = noisyGray(80, 80, 9L, 60)
        val out = fx(img, com.example.data.model.ProFilterType.TILT_SHIFT, com.example.engine.ProEffectParams(6f, 20f, 10f, centerX = 0.5f, centerY = 0.5f))
        assertEquals("focus band is untouched", img.getPixel(10, 40), out.getPixel(10, 40))
        assertTrue("far from the band it is blurred", out.getPixel(10, 2) != img.getPixel(10, 2))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun proLensBlur_pointBecomesADisc() {
        val img = qsImage(60, 60) { x, y -> if (x == 30 && y == 30) Color.WHITE else Color.BLACK }
        val out = fx(img, com.example.data.model.ProFilterType.LENS_BLUR, com.example.engine.ProEffectParams(6f, 100f))
        assertTrue("inside the disc", lumaAt(out, 34, 30) > 0)
        assertEquals("outside the disc", 0, lumaAt(out, 39, 30))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun proSurfaceBlur_keepsEdgesSmoothsNoise() {
        val rnd = java.util.Random(4L)
        val img = qsImage(60, 60) { x, _ ->
            val base = if (x < 30) 50 else 200
            val v = (base + rnd.nextInt(17) - 8).coerceIn(0, 255)
            Color.rgb(v, v, v)
        }
        val out = fx(img, com.example.data.model.ProFilterType.SURFACE_BLUR, com.example.engine.ProEffectParams(5f, 30f))
        assertTrue("edge stays sharp", lumaAt(out, 31, 30) - lumaAt(out, 28, 30) > 120)
        var before = 0.0; var after = 0.0
        for (y in 5 until 55) for (x in 5 until 25) {
            before += abs(lumaAt(img, x, y) - 50); after += abs(lumaAt(out, x, y) - 50)
        }
        assertTrue("noise smoothed", after < before * 0.7)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun proOilPaint_andHighPassRun() {
        val img = noisyGray(40, 40, 2L, 30)
        val oil = fx(img, com.example.data.model.ProFilterType.OIL_PAINT, com.example.engine.ProEffectParams(3f, 40f))
        assertEquals(40, oil.width)
        val hp = fx(qsImage(40, 40) { _, _ -> Color.rgb(200, 30, 90) }, com.example.data.model.ProFilterType.HIGH_PASS, com.example.engine.ProEffectParams(3f))
        assertTrue("flat image -> 50% grey", abs(lumaAt(hp, 20, 20) - 128) <= 2)
    }

    @Test
    fun proEffect_previewScalesPixelRadii() {
        val p = com.example.engine.ProEffectParams(20f, 30f)
        val g = com.example.engine.ProEffects.scaleForPreview(com.example.data.model.ProFilterType.GAUSSIAN_BLUR, p, 0.25f)
        assertEquals(5f, g.p1, 0.001f)
        val s = com.example.engine.ProEffects.scaleForPreview(com.example.data.model.ProFilterType.SURFACE_BLUR, p, 0.25f)
        assertEquals("threshold is not a size", 30f, s.p2, 0.001f)
    }
    // --- Transform vs crop -------------------------------------------------------------------------

    @Test
    fun rotate90_onOneLayerImage_rotatesTheWholeImage() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.ui.viewmodel.EditorViewModel(app)
        val project = Project(name = "Rot", width = 120, height = 80)
        project.layers.add(Layer(id = "only", name = "Photo", type = LayerType.IMAGE, bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)))
        vm.openProject(project)
        vm.rotateSelectedLayer(90f)
        val p = vm.uiState.value.currentProject!!
        assertEquals("canvas turns with the picture", 80, p.width)
        assertEquals(120, p.height)
    }

    @Test
    fun rotate90_withSeveralLayers_rotatesOnlyTheSelectedLayer() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.ui.viewmodel.EditorViewModel(app)
        val project = Project(name = "Rot2", width = 120, height = 80)
        project.layers.add(Layer(id = "a", name = "A", type = LayerType.IMAGE, bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)))
        project.layers.add(Layer(id = "b", name = "B", type = LayerType.IMAGE, bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)))
        vm.openProject(project)
        vm.selectLayer(1)
        vm.rotateSelectedLayer(90f)
        val p = vm.uiState.value.currentProject!!
        assertEquals(120, p.width)
        assertEquals(90f, p.layers[1].rotation, 0.01f)
        assertEquals(0f, p.layers[0].rotation, 0.01f)
    }
    // --- Refine Edge & Hair: the single refine workspace ----------------------------------------

    private fun refineRun(mask: Bitmap, cfg: com.example.engine.RefineEdgeConfig): Bitmap {
        val src = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        return com.example.engine.RefineEdgeEngine.refineEdges(src, mask, cfg).refinedMask
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun refineWorkspace_shiftEdgeIsRoundAndInPixels() {
        val out = refineRun(squareSelection(80, 40, 40, 40, 40), com.example.engine.RefineEdgeConfig(radiusPx = 0, shiftEdgePx = 10f))
        assertTrue(alphaAt(out, 49, 40) > 128)
        assertTrue("round, not square", alphaAt(out, 49, 49) < 64)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun refineWorkspace_cleanupBorderAndFeather() {
        val m = squareSelection(100, 20, 20, 79, 79)
        android.graphics.Canvas(m).drawRect(5f, 5f, 7f, 7f, android.graphics.Paint().apply { color = Color.WHITE })
        val cleaned = refineRun(m, com.example.engine.RefineEdgeConfig(radiusPx = 0, removeSpecks = true))
        assertTrue("speck removed", alphaAt(cleaned, 5, 5) < 30)
        assertTrue(alphaAt(cleaned, 50, 50) > 200)
        val border = refineRun(squareSelection(100, 20, 20, 79, 79), com.example.engine.RefineEdgeConfig(radiusPx = 0, borderPx = 10f))
        assertTrue("edge band", alphaAt(border, 20, 50) > 200)
        assertTrue("inside dropped", alphaAt(border, 50, 50) < 30)
        val soft = refineRun(squareSelection(100, 20, 20, 79, 79), com.example.engine.RefineEdgeConfig(radiusPx = 0, featherPx = 8f))
        assertTrue("feathered edge is soft", alphaAt(soft, 20, 50) in 30..225)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun refineWorkspace_defaultsChangeNothingWithoutDetection() {
        val m = squareSelection(60, 10, 10, 49, 49)
        val out = refineRun(m, com.example.engine.RefineEdgeConfig(radiusPx = 0))
        assertEquals(255, alphaAt(out, 30, 30))
        assertEquals(0, alphaAt(out, 3, 3))
        assertEquals(255, alphaAt(out, 10, 30))
    }
    // --- Export ------------------------------------------------------------------------------------

    @Test
    fun export_outputSizeModes() {
        val E = com.example.engine.ExportEngine
        val base = com.example.engine.ExportSettings()
        assertEquals(4000 to 3000, E.outputSize(4000, 3000, base))
        assertEquals(2000 to 1500, E.outputSize(4000, 3000, base.copy(sizeMode = com.example.engine.ExportSizeMode.PERCENT, percent = 50)))
        assertEquals(1080 to 810, E.outputSize(4000, 3000, base.copy(sizeMode = com.example.engine.ExportSizeMode.LONG_EDGE, longEdge = 1080)))
    }

    @Test
    fun export_settingsAreRemembered() {
        val s = com.example.engine.ExportSettings(format = com.example.engine.ExportFormat.PNG, quality = 77, sizeMode = com.example.engine.ExportSizeMode.LONG_EDGE, longEdge = 1920, metadata = com.example.engine.ExportMetadata.NONE, shareAfter = true)
        val back = com.example.engine.ExportSettings.fromJson(s.toJson())
        assertEquals(s.format, back.format)
        assertEquals(77, back.quality)
        assertEquals(1920, back.longEdge)
        assertEquals(com.example.engine.ExportMetadata.NONE, back.metadata)
        assertTrue(back.shareAfter)
    }

    @Test
    fun export_transparentBackgroundDropsTheDarkFillLayer() {
        val E = com.example.engine.ExportEngine
        val bg = Layer(id = "bg", name = "Background", type = LayerType.COLOR_FILL, colorFillHex = E.LEGACY_AUTO_BACKGROUND)
        val photo = Layer(id = "p", name = "Photo", type = LayerType.IMAGE, bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        val layers = listOf(bg, photo)
        assertTrue(E.defaultTransparent(layers))
        assertEquals(listOf(photo), E.layersForExport(layers, transparent = true))
        assertEquals(layers, E.layersForExport(layers, transparent = false))
        val white = bg.copy(colorFillHex = Color.WHITE)
        assertFalse("a chosen white background stays unless asked", E.defaultTransparent(listOf(white, photo)))
        assertEquals(listOf(listOf(photo)), E.layerGroups(layers))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun export_pngKeepsTransparency_jpegUsesChosenBackground() {
        val E = com.example.engine.ExportEngine
        val img = qsImage(20, 20) { x, y -> if (x in 5..14 && y in 5..14) Color.RED else Color.TRANSPARENT }
        val png = java.io.ByteArrayOutputStream()
        assertTrue(E.encode(img, com.example.engine.ExportFormat.PNG, 100, png))
        val decoded = android.graphics.BitmapFactory.decodeByteArray(png.toByteArray(), 0, png.size())
        assertEquals("transparent corner, not black", 0, Color.alpha(decoded.getPixel(0, 0)))
        assertEquals(Color.RED, decoded.getPixel(10, 10))
        val flat = E.flatten(img, Color.WHITE)
        assertEquals(Color.WHITE, flat.getPixel(0, 0))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun export_importedPngRendersTransparentWithoutTheFill() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val E = com.example.engine.ExportEngine
        val photoBmp = qsImage(40, 40) { x, _ -> if (x < 20) Color.BLUE else Color.TRANSPARENT }
        val layers = listOf(
            Layer(id = "bg", name = "Background", type = LayerType.COLOR_FILL, colorFillHex = E.LEGACY_AUTO_BACKGROUND),
            Layer(id = "p", name = "Photo", type = LayerType.IMAGE, bitmap = photoBmp)
        )
        val out = LayerRenderer.renderCompositeCanvas(app, E.layersForExport(layers, true), 40, 40, includeBackground = true, backgroundColor = null)
        assertEquals("right half is transparent", 0, Color.alpha(out.getPixel(35, 20)))
        assertTrue(Color.alpha(out.getPixel(5, 20)) > 200)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun export_selectionOnlyCropsAndMasks() {
        val img = qsImage(50, 50) { _, _ -> Color.GREEN }
        val mask = squareSelection(50, 10, 20, 29, 39)
        val out = com.example.engine.ExportEngine.selectionOnly(img, mask)!!
        assertEquals(20, out.width)
        assertEquals(20, out.height)
        assertTrue(Color.alpha(out.getPixel(10, 10)) > 240)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun export_resizeHitsExactSize() {
        val img = qsImage(400, 300) { x, y -> if ((x / 10 + y / 10) % 2 == 0) Color.BLACK else Color.WHITE }
        val out = com.example.engine.ExportEngine.resize(img, 97, 73, sharpen = true)
        assertEquals(97, out.width)
        assertEquals(73, out.height)
    }
}
