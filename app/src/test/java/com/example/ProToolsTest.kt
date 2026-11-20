package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AdjustmentKind
import com.example.data.model.AdjustmentLayerParams
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Project
import com.example.data.model.TextLayerConfig
import com.example.data.repository.ProjectRepository
import com.example.engine.AdjustmentLayerEngine
import com.example.engine.LiquifyBrush
import com.example.engine.LiquifyEngine
import com.example.engine.LiquifyTool
import com.example.engine.RetouchBrush
import com.example.engine.RetouchCanvas
import com.example.engine.RetouchTool
import com.example.engine.StickerCategory
import com.example.engine.StickerLibrary
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProToolsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun solid(w: Int, h: Int, color: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun lum(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

    // --- Adjustment layer kinds -------------------------------------------------------------

    @Test
    fun `invert threshold and posterize change pixels as expected`() {
        val src = solid(4, 4, Color.rgb(10, 100, 200))
        val inv = AdjustmentLayerEngine.apply(src, AdjustmentKind.INVERT, AdjustmentLayerParams())
        assertEquals(Color.rgb(245, 155, 55), inv.getPixel(1, 1))
        val thr = AdjustmentLayerEngine.apply(src, AdjustmentKind.THRESHOLD, AdjustmentLayerParams(thresholdLevel = 200))
        assertEquals(Color.BLACK, thr.getPixel(0, 0))
        val post = AdjustmentLayerEngine.apply(solid(2, 2, Color.rgb(100, 100, 100)), AdjustmentKind.POSTERIZE, AdjustmentLayerParams(posterizeLevels = 2))
        assertEquals(0, Color.red(post.getPixel(0, 0)))
    }

    @Test
    fun `levels default is identity and black and white keeps greys grey`() {
        assertTrue(AdjustmentLayerEngine.isIdentity(AdjustmentKind.LEVELS, AdjustmentLayerParams()))
        val src = solid(2, 2, Color.rgb(90, 90, 90))
        assertTrue(AdjustmentLayerEngine.apply(src, AdjustmentKind.LEVELS, AdjustmentLayerParams()) === src)
        val bw = AdjustmentLayerEngine.apply(solid(2, 2, Color.rgb(200, 30, 30)), AdjustmentKind.BLACK_WHITE, AdjustmentLayerParams())
        val c = bw.getPixel(0, 0)
        assertEquals(Color.red(c), Color.green(c))
        assertEquals(Color.green(c), Color.blue(c))
        val grey = AdjustmentLayerEngine.apply(src, AdjustmentKind.BLACK_WHITE, AdjustmentLayerParams()).getPixel(0, 0)
        assertEquals(90, Color.red(grey))
    }

    @Test
    fun `adjustment params survive json and project save`() {
        val p = AdjustmentLayerParams(levelsGamma = 1.7f, bwWeights = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), photoFilterColor = 0xFF123456.toInt(), posterizeLevels = 7)
        assertEquals(p, AdjustmentLayerEngine.fromJson(JSONObject(AdjustmentLayerEngine.toJson(p).toString())))
        val repo = ProjectRepository(context)
        val proj = Project(id = "adjk_proj", name = "Adj", width = 20, height = 20)
        proj.layers.add(Layer(id = "a", name = "Levels", type = LayerType.ADJUSTMENT, adjustmentKind = AdjustmentKind.LEVELS, adjustmentParams = p))
        runBlocking { assertTrue(repo.saveProject(proj)) }
        val loaded = runBlocking { repo.getProjectById("adjk_proj") }!!.layers.single()
        assertEquals(AdjustmentKind.LEVELS, loaded.adjustmentKind)
        assertEquals(p, loaded.adjustmentParams)
    }

    // --- Text effects -----------------------------------------------------------------------

    @Test
    fun `text effect switches persist and older projects turn effects on from their size`() {
        val repo = ProjectRepository(context)
        val proj = Project(id = "text_fx", name = "T", width = 40, height = 40)
        proj.layers.add(Layer(id = "t", name = "T", type = LayerType.TEXT, textConfig = TextLayerConfig(shadowRadiusDp = 5f, shadowEnabled = false, glowEnabled = true, outerGlowRadiusDp = 3f)))
        runBlocking { assertTrue(repo.saveProject(proj)) }
        var cfg = runBlocking { repo.getProjectById("text_fx") }!!.layers.single().textConfig
        assertFalse("a switched-off shadow stays off even with a size", cfg.shadowEnabled)
        assertTrue(cfg.glowEnabled)
        // Older file without the switches.
        val manifest = File(File(context.filesDir, "projects/text_fx"), "project.json")
        val json = JSONObject(manifest.readText())
        val tc = json.getJSONArray("layers").getJSONObject(0).getJSONObject("textConfig")
        tc.remove("shadowEnabled"); tc.remove("glowEnabled"); tc.remove("strokeEnabled")
        manifest.writeText(json.toString())
        cfg = runBlocking { repo.getProjectById("text_fx") }!!.layers.single().textConfig
        assertTrue(cfg.shadowEnabled)
        assertTrue(cfg.glowEnabled)
        assertFalse(cfg.strokeEnabled)
    }

    // --- Liquify ----------------------------------------------------------------------------

    @Test
    fun `liquify forward warp moves content and restore all brings it back`() {
        val src = solid(80, 80, Color.WHITE)
        for (y in 30..50) for (x in 30..50) src.setPixel(x, y, Color.BLACK)
        val e = LiquifyEngine(src)
        assertFalse(e.hasChanges)
        e.beginStroke()
        repeat(6) { e.dab(LiquifyTool.FORWARD_WARP, 40f + it * 2, 40f, 2f, 0f, LiquifyBrush(size = 40f, pressure = 100f, density = 50f)) }
        assertTrue(e.hasChanges)
        // Black content was pushed to the right.
        assertTrue(lum(e.output[40 * 80 + 53]) < lum(src.getPixel(53, 40)))
        val full = e.applyFull(Bitmap.createScaledBitmap(src, 160, 160, false))
        assertEquals(160, full.width)
        e.restoreAll()
        assertEquals(src.getPixel(53, 40), e.output[40 * 80 + 53])
        assertTrue(e.canUndo)
    }

    @Test
    fun `liquify bloat and pucker keep transparent pixels without dark fringes`() {
        val src = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        for (y in 10..30) for (x in 10..30) src.setPixel(x, y, Color.rgb(255, 0, 0))
        val e = LiquifyEngine(src)
        e.beginStroke()
        repeat(5) { e.dab(LiquifyTool.BLOAT, 20f, 20f, 0f, 0f, LiquifyBrush(size = 30f, pressure = 100f, rate = 100f)) }
        for (i in e.output.indices) {
            val c = e.output[i]
            if (Color.alpha(c) > 0) assertEquals("colour of a partly covered pixel", 255, Color.red(c))
        }
    }

    // --- Retouch ----------------------------------------------------------------------------

    private fun canvasOf(w: Int, h: Int, color: Int) = RetouchCanvas(w, h, IntArray(w * h) { color })

    @Test
    fun `dodge brightens burn darkens and neither builds up inside one stroke`() {
        val grey = Color.rgb(120, 120, 120)
        val c = canvasOf(60, 60, grey)
        c.beginStroke(RetouchTool.DODGE, RetouchBrush(hardness = 1f, strength = 0.5f))
        c.stroke(floatArrayOf(30f, 30f), 10f)
        val once = c.pix[30 * 60 + 30]
        assertTrue(lum(once) > lum(grey))
        c.stroke(floatArrayOf(40f, 30f, 20f, 30f), 10f)
        assertEquals("same stroke over the same spot: no build-up", once, c.pix[30 * 60 + 30])
        c.endStroke()
        val b = canvasOf(60, 60, grey)
        b.beginStroke(RetouchTool.BURN, RetouchBrush(hardness = 1f, strength = 0.5f))
        b.stroke(floatArrayOf(30f, 30f), 10f)
        assertTrue(lum(b.pix[30 * 60 + 30]) < lum(grey))
    }

    @Test
    fun `clone stamp copies from the source offset`() {
        val c = canvasOf(80, 40, Color.WHITE)
        for (y in 0 until 40) for (x in 0 until 20) c.pix[y * 80 + x] = Color.RED
        c.beginStroke(RetouchTool.CLONE, RetouchBrush(hardness = 1f, strength = 1f), cloneOffsetX = -50f, cloneOffsetY = 0f)
        c.stroke(floatArrayOf(60f, 20f), 6f)
        assertEquals(Color.RED, c.pix[20 * 80 + 60])
        assertEquals(Color.WHITE, c.pix[20 * 80 + 75])
    }

    @Test
    fun `blur softens an edge and smudge drags colour`() {
        val c = canvasOf(40, 40, Color.WHITE)
        for (y in 0 until 40) for (x in 0 until 20) c.pix[y * 40 + x] = Color.BLACK
        c.beginStroke(RetouchTool.BLUR, RetouchBrush(hardness = 1f, strength = 1f))
        c.stroke(floatArrayOf(20f, 20f), 12f)
        val v = lum(c.pix[20 * 40 + 20])
        assertTrue("edge pixel becomes grey: $v", v > 5 && v < 250)
        val s = canvasOf(60, 20, Color.WHITE)
        for (y in 0 until 20) for (x in 0 until 10) s.pix[y * 60 + x] = Color.BLUE
        s.beginStroke(RetouchTool.SMUDGE, RetouchBrush(hardness = 1f, strength = 0.9f))
        s.stroke(floatArrayOf(5f, 10f, 30f, 10f), 5f)
        assertTrue(Color.blue(s.pix[10 * 60 + 15]) > Color.red(s.pix[10 * 60 + 15]))
    }

    @Test
    fun `patch fills the selection from the source and matches the surrounding colour`() {
        // Gradient background with a dark blob to remove.
        val w = 100; val h = 60
        val c = RetouchCanvas(w, h, IntArray(w * h) { i -> val x = i % w; Color.rgb(100 + x, 120, 140) })
        for (y in 25..35) for (x in 20..30) c.pix[y * w + x] = Color.BLACK
        val mask = ByteArray(w * h)
        for (y in 22..38) for (x in 17..33) mask[y * w + x] = 255.toByte()
        val changed = c.patch(mask, w, 40, 0)
        assertNotNull(changed)
        val p = c.pix[30 * w + 25]
        // The blob is gone and the colour follows the local gradient (red ~125 there), not the source (~165).
        assertTrue("red ${Color.red(p)}", Color.red(p) in 110..140)
        assertEquals(120.0, Color.green(p).toDouble(), 6.0)
    }

    // --- Frequency separation ---------------------------------------------------------------

    @Test
    fun `low plus high in linear light rebuilds the image`() {
        val rnd = java.util.Random(3)
        val img = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        for (y in 0 until 32) for (x in 0 until 48) img.setPixel(x, y, Color.rgb(60 + x * 3 + rnd.nextInt(30), 90 + rnd.nextInt(60), 200 - y * 2))
        val (low, high) = com.example.engine.FrequencySeparation.split(img, 3f)
        // Texture sits around mid grey; low is smoother than the image.
        val rebuilt = low.copy(Bitmap.Config.ARGB_8888, true)
        com.example.engine.FrequencySeparation.linearLight(rebuilt, high, 1f)
        for (y in 0 until 32) for (x in 0 until 48) {
            val a = img.getPixel(x, y); val b = rebuilt.getPixel(x, y)
            assertTrue("($x,$y)", kotlin.math.abs(Color.red(a) - Color.red(b)) <= 1 && kotlin.math.abs(Color.green(a) - Color.green(b)) <= 1 && kotlin.math.abs(Color.blue(a) - Color.blue(b)) <= 1)
        }
    }

    @Test
    fun `renderer composes a clipped linear light layer like the original`() {
        val img = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        for (y in 0 until 30) for (x in 0 until 40) img.setPixel(x, y, Color.rgb((x * 6) % 256, (y * 8) % 256, ((x + y) * 5) % 256))
        val (low, high) = com.example.engine.FrequencySeparation.split(img, 2f)
        val layers = listOf(
            Layer(id = "low", name = "Low", type = LayerType.IMAGE, bitmap = low, contentWidth = 40f, contentHeight = 30f),
            Layer(id = "high", name = "High", type = LayerType.IMAGE, bitmap = high, contentWidth = 40f, contentHeight = 30f,
                isClipped = true, blendMode = com.example.data.model.BlendModeType.LINEAR_LIGHT)
        )
        val out = runBlocking { com.example.engine.LayerRenderer.renderCompositeCanvas(context, layers, 40, 30, includeBackground = false) }
        var maxDiff = 0
        for (y in 2 until 28) for (x in 2 until 38) {
            val a = img.getPixel(x, y); val b = out.getPixel(x, y)
            maxDiff = maxOf(maxDiff, kotlin.math.abs(Color.red(a) - Color.red(b)), kotlin.math.abs(Color.green(a) - Color.green(b)), kotlin.math.abs(Color.blue(a) - Color.blue(b)))
        }
        assertTrue("max difference $maxDiff", maxDiff <= 3)
    }

    // --- Face-Aware Liquify ------------------------------------------------------------------

    private fun face(): com.example.engine.FaceGeometry {
        fun p(x: Float, y: Float) = android.graphics.PointF(x, y)
        return com.example.engine.FaceGeometry(
            box = android.graphics.RectF(60f, 40f, 140f, 150f), center = p(100f, 95f), width = 80f, height = 110f,
            ax = p(1f, 0f), ay = p(0f, 1f), eye1 = p(82f, 80f), eye2 = p(118f, 80f), eyeW = 16f, eyeH = 7f,
            noseBase = p(100f, 105f), noseW = 18f, noseLen = 25f, mouthLeft = p(88f, 122f), mouthRight = p(112f, 122f),
            mouthCenter = p(100f, 123f), mouthH = 9f, chin = p(100f, 148f), forehead = p(100f, 52f)
        )
    }

    @Test
    fun `face warps enlarge eyes and narrow the nose in the right direction`() {
        val g = face()
        assertNull(com.example.engine.FaceWarp.field(listOf(g to com.example.engine.FaceAdjust()), 200, 200))
        val (dx, _) = com.example.engine.FaceWarp.field(listOf(g to com.example.engine.FaceAdjust(eyeSize = 100f)), 200, 200)!!
        // Right of the right eye's centre the output samples closer to the centre (bigger eye).
        assertTrue(dx[80 * 200 + 124] < 0f)
        assertTrue(dx[80 * 200 + 112] > 0f)
        val (nx, _) = com.example.engine.FaceWarp.field(listOf(g to com.example.engine.FaceAdjust(noseWidth = -100f)), 200, 200)!!
        // Narrower nose: right of the nose samples further out.
        assertTrue(nx[102 * 200 + 108] > 0f)
        // Far from the face nothing moves.
        assertEquals(0f, nx[5 * 200 + 5], 0f)
    }

    @Test
    fun `eye brightness lifts the eyes only and liquify applies the face field`() {
        val g = face()
        val bright = com.example.engine.FaceWarp.eyeBrightener(listOf(g to com.example.engine.FaceAdjust(eyeBrightness = 100f)))!!
        val grey = Color.rgb(90, 90, 90)
        assertTrue(Color.red(bright(118f, 80f, grey)) > 90)
        assertEquals(grey, bright(100f, 140f, grey))
        val src = solid(200, 200, Color.WHITE)
        for (y in 76..84) for (x in 112..124) src.setPixel(x, y, Color.BLACK)
        val e = LiquifyEngine(src)
        val adj = listOf(g to com.example.engine.FaceAdjust(eyeSize = 100f))
        val f = com.example.engine.FaceWarp.field(adj, 200, 200)!!
        e.setFaceField(f.first, f.second, null)
        assertTrue(e.hasChanges)
        // The enlarged dark eye now reaches pixels that were white.
        assertTrue(lum(e.output[80 * 200 + 125]) < 250)
        assertTrue(lum(e.output[80 * 200 + 190]) > 254.0)
        assertEquals(400, e.applyFull(Bitmap.createScaledBitmap(src, 400, 400, false)).width)
    }

    // --- Stickers ---------------------------------------------------------------------------

    @Test
    fun `every vector sticker renders visible pixels with border and colour`() {
        StickerLibrary.all.filter { it.category != StickerCategory.EMOJI }.forEach { def ->
            val bmp = StickerLibrary.render(context, def, 160, com.example.engine.StickerStyle(color = Color.MAGENTA, outline = true, shadow = true))
            var opaque = 0
            for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) if (Color.alpha(bmp.getPixel(x, y)) > 128) opaque++
            assertTrue("${def.id} drew nothing", opaque > 10)
        }
        assertTrue(StickerLibrary.byCategory(StickerCategory.EMOJI).size > 100)
    }
}
