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
