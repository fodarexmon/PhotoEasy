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
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `before images survive closing the app and go with their layer`() {
        val repo = ProjectRepository(context)
        val proj = Project(id = "before_proj", name = "B", width = 20, height = 20)
        proj.layers.add(Layer(id = "img-1", name = "Img", type = LayerType.IMAGE, bitmap = solid(20, 20, Color.BLUE), contentWidth = 20f, contentHeight = 20f))
        runBlocking { assertTrue(repo.saveProject(proj)) }
        val before = com.example.data.model.LayerBefore(solid(20, 20, Color.RED), 20f, 13.5f)
        runBlocking { assertTrue(repo.saveBeforeImage("before_proj", "img-1", before)) }
        val loaded = runBlocking { repo.loadBeforeImages("before_proj", setOf("img-1")) }
        val b = loaded["img-1"]!!
        assertEquals(Color.RED, b.bitmap.getPixel(5, 5))
        assertEquals(20f, b.contentWidth, 0f); assertEquals(13.5f, b.contentHeight, 0f)
        // Kept while undo can bring the layer back, removed once it is really gone.
        proj.layers.clear()
        runBlocking { assertTrue(repo.saveProject(proj, setOf(ProjectRepository.BEFORE_KEEP_PREFIX + "img-1"))) }
        assertEquals(1, runBlocking { repo.loadBeforeImages("before_proj", setOf("img-1")) }.size)
        runBlocking { assertTrue(repo.saveProject(proj)) }
        assertTrue(runBlocking { repo.loadBeforeImages("before_proj", setOf("img-1")) }.isEmpty())
    }

    // --- Blend If -----------------------------------------------------------------------------

    @Test
    fun `blend if weights hard and split handles`() {
        val e = com.example.engine.BlendIfEngine
        // Hard low handle at 100: below hidden, from it on shown.
        assertEquals(0f, e.weight(99f, 100f, 100f, 255f, 255f), 0f)
        assertEquals(1f, e.weight(100f, 100f, 100f, 255f, 255f), 0f)
        // Split 50/150: halfway at 100.
        assertEquals(0.5f, e.weight(100f, 50f, 150f, 255f, 255f), 0.001f)
        // Split high 200/240.
        assertEquals(1f, e.weight(200f, 0f, 0f, 200f, 240f), 0f)
        assertEquals(0.5f, e.weight(220f, 0f, 0f, 200f, 240f), 0.001f)
        assertEquals(0f, e.weight(241f, 0f, 0f, 200f, 240f), 0f)
        assertTrue(com.example.data.model.BlendIf().isIdentity())
    }

    @Test
    fun `blend if hides a layer by its own tones and by the tones under it`() {
        // Under: left half black, right half white. Layer: red everywhere, dark grey bottom row.
        val w = 20; val h = 10
        val under = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) under.setPixel(x, y, if (x < 10) Color.BLACK else Color.WHITE)
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) layer.setPixel(x, y, if (y == h - 1) Color.rgb(20, 20, 20) else Color.RED)
        // Show only over the light part of the image under it.
        val onLight = com.example.engine.BlendIfEngine.apply(layer.copy(Bitmap.Config.ARGB_8888, true), under,
            com.example.data.model.BlendIf(underLow0 = 128f, underLow1 = 128f))
        assertEquals(0, Color.alpha(onLight.getPixel(3, 3)))
        assertEquals(255, Color.alpha(onLight.getPixel(15, 3)))
        assertEquals(Color.RED, onLight.getPixel(15, 3))
        // Hide the layer's own darks.
        val noDarks = com.example.engine.BlendIfEngine.apply(layer.copy(Bitmap.Config.ARGB_8888, true), under,
            com.example.data.model.BlendIf(thisLow0 = 50f, thisLow1 = 50f))
        assertEquals(0, Color.alpha(noDarks.getPixel(5, h - 1)))
        assertEquals(255, Color.alpha(noDarks.getPixel(5, 2)))
        // Through the renderer and saved with the project.
        val repo = ProjectRepository(context)
        val proj = Project(id = "blendif_proj", name = "BI", width = w, height = h)
        proj.layers.add(Layer(id = "u", name = "U", type = LayerType.IMAGE, bitmap = under, contentWidth = w.toFloat(), contentHeight = h.toFloat()))
        val top = Layer(id = "t", name = "T", type = LayerType.IMAGE, bitmap = layer, contentWidth = w.toFloat(), contentHeight = h.toFloat(),
            blendIf = com.example.data.model.BlendIf(channel = 0, underLow0 = 100f, underLow1 = 140f))
        proj.layers.add(top)
        val out = runBlocking { com.example.engine.LayerRenderer.renderCompositeCanvas(context, proj.layers, w, h, includeBackground = false) }
        assertEquals(Color.BLACK, out.getPixel(3, 3))
        assertEquals(Color.RED, out.getPixel(15, 3))
        runBlocking { assertTrue(repo.saveProject(proj)) }
        val loaded = runBlocking { repo.getProjectById("blendif_proj") }!!.layers.first { it.id == "t" }.blendIf
        assertEquals(top.blendIf, loaded)
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

    // --- Fill (paint bucket) ------------------------------------------------------------------

    @Test
    fun `fill paints the connected area only and respects tolerance and selection`() {
        val w = 40; val h = 20
        // Left half white, a black wall at x = 20, right half white.
        val px = IntArray(w * h) { i -> if (i % w == 20) Color.BLACK else Color.WHITE }
        val c = com.example.engine.RetouchCanvas(w, h, px)
        val red = Color.RED
        assertNotNull(c.fill(5, 5, red, 1f, 32, contiguous = true, antiAlias = false))
        assertEquals(red, px[5 * w + 0]); assertEquals(red, px[19 * w + 19])
        assertEquals(Color.BLACK, px[3 * w + 20]); assertEquals(Color.WHITE, px[3 * w + 30])
        // Not contiguous: every white pixel.
        val px2 = IntArray(w * h) { i -> if (i % w == 20) Color.BLACK else Color.WHITE }
        com.example.engine.RetouchCanvas(w, h, px2).fill(5, 5, red, 1f, 32, contiguous = false, antiAlias = false)
        assertEquals(red, px2[3 * w + 30])
        // Selection: only selected pixels change, a tap outside it fills nothing.
        val px3 = IntArray(w * h) { Color.WHITE }
        val mask = ByteArray(w * h) { i -> if (i % w < 10) 255.toByte() else 0 }
        val c3 = com.example.engine.RetouchCanvas(w, h, px3)
        assertNull(c3.fill(30, 5, red, 1f, 32, true, false, mask, w))
        assertNotNull(c3.fill(2, 5, red, 1f, 32, true, false, mask, w))
        assertEquals(red, px3[5 * w + 9]); assertEquals(Color.WHITE, px3[5 * w + 10])
        // Half opacity over transparent pixels gives the colour at half alpha.
        val px4 = IntArray(w * h)
        com.example.engine.RetouchCanvas(w, h, px4).fill(1, 1, Color.BLUE, 0.5f, 0, true, false)
        assertTrue(kotlin.math.abs((px4[0] ushr 24) - 128) <= 1)
        assertEquals(255, px4[0] and 255)
    }

    @Test
    fun `brushes and healing stay inside the selection`() {
        val grey = Color.rgb(120, 120, 120)
        // Left half selected.
        val sel = ByteArray(60 * 60) { i -> if (i % 60 < 30) 255.toByte() else 0 }
        for (tool in listOf(RetouchTool.DODGE, RetouchTool.BURN, RetouchTool.BRUSH, RetouchTool.ERASER)) {
            val c = canvasOf(60, 60, grey)
            c.beginStroke(tool, RetouchBrush(hardness = 1f, strength = 1f, fillColor = Color.RED), selection = sel, selectionStride = 60)
            c.stroke(floatArrayOf(10f, 30f, 50f, 30f), 12f)
            c.endStroke()
            assertNotEquals("$tool inside", grey, c.pix[30 * 60 + 15])
            assertEquals("$tool outside", grey, c.pix[30 * 60 + 45])
        }
        // A stroke after the selected one paints everywhere again.
        val c = canvasOf(60, 60, grey)
        c.beginStroke(RetouchTool.BRUSH, RetouchBrush(hardness = 1f, fillColor = Color.RED), selection = sel, selectionStride = 60)
        c.stroke(floatArrayOf(45f, 30f), 6f); c.endStroke()
        assertEquals(grey, c.pix[30 * 60 + 45])
        c.beginStroke(RetouchTool.BRUSH, RetouchBrush(hardness = 1f, fillColor = Color.RED))
        c.stroke(floatArrayOf(45f, 30f), 6f); c.endStroke()
        assertNotEquals(grey, c.pix[30 * 60 + 45])
        // Healing entirely outside the selection does nothing.
        val h = canvasOf(60, 60, grey)
        h.pix[30 * 60 + 45] = Color.BLACK
        assertNull(h.heal(floatArrayOf(45f, 30f), 4f, sel, 60))
        assertEquals(Color.BLACK, h.pix[30 * 60 + 45])
    }

    @Test
    fun `selection delete fill and stroke`() {
        val w = 40; val h = 30
        val sel = ByteArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 10..29 && y in 8..21) 255.toByte() else 0 }
        val px = IntArray(w * h) { Color.WHITE }
        com.example.engine.SelectionOps.erase(px, w, h, sel, w)
        assertEquals(0, px[15 * w + 15] ushr 24); assertEquals(Color.WHITE, px[0])
        val f = IntArray(w * h) { Color.WHITE }
        com.example.engine.SelectionOps.fill(f, w, h, sel, w, Color.RED, 1f)
        assertEquals(Color.RED, f[15 * w + 15]); assertEquals(Color.WHITE, f[15 * w + 5])
        // A 3 px stroke inside the edge: the border rows are covered, the middle and outside not.
        val st = IntArray(w * h) { Color.WHITE }
        com.example.engine.SelectionOps.stroke(st, w, h, sel, w, Color.BLUE, 3f, com.example.engine.StrokePosition.INSIDE, 1f)
        assertEquals(Color.BLUE, st[15 * w + 10]); assertEquals(Color.BLUE, st[15 * w + 12])
        assertEquals(Color.WHITE, st[15 * w + 13]); assertEquals(Color.WHITE, st[15 * w + 9]); assertEquals(Color.WHITE, st[15 * w + 20])
        val out = IntArray(w * h) { Color.WHITE }
        com.example.engine.SelectionOps.stroke(out, w, h, sel, w, Color.BLUE, 3f, com.example.engine.StrokePosition.OUTSIDE, 1f)
        assertEquals(Color.BLUE, out[15 * w + 7]); assertEquals(Color.BLUE, out[15 * w + 9]); assertEquals(Color.WHITE, out[15 * w + 10])
    }

    @Test
    fun `liquify does not move what is outside the selection`() {
        val src = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        for (y in 0 until 80) for (x in 0 until 80) src.setPixel(x, y, if ((x / 4 + y / 4) % 2 == 0) Color.BLACK else Color.WHITE)
        val e = LiquifyEngine(src)
        // Only the left half is selected.
        e.freezeOutside(ByteArray(80 * 80) { i -> if (i % 80 < 40) 255.toByte() else 0 }, 80)
        e.beginStroke()
        repeat(6) { e.dab(LiquifyTool.FORWARD_WARP, 30f + it * 3, 40f, 3f, 0f, LiquifyBrush(size = 60f, pressure = 100f, density = 50f)) }
        assertTrue(e.hasChanges)
        for (y in 0 until 80) for (x in 42 until 80) assertEquals("($x,$y)", src.getPixel(x, y), e.output[y * 80 + x])
    }

    @Test
    fun `a new fill on the previous fill replaces it`() {
        val w = 40; val h = 20
        val px = IntArray(w * h) { i -> if (i % w == 20) Color.BLACK else Color.WHITE }
        val c = com.example.engine.RetouchCanvas(w, h, px)
        // A pattern of red stripes with transparent gaps.
        val stripes: (Int, Int) -> Int = { x, _ -> if (x % 4 < 2) Color.RED else 0 }
        assertNotNull(c.fill(5, 5, stripes, 1f, 32, true, false))
        assertEquals(Color.WHITE, px[5 * w + 2])
        // Clicking a red stripe with another pattern: the whole first fill is replaced.
        val blueDots: (Int, Int) -> Int = { x, y -> if ((x + y) % 2 == 0) Color.BLUE else 0 }
        assertNotNull(c.fill(1, 5, blueDots, 1f, 32, true, false))
        for (y in 0 until h) for (x in 0 until 20) {
            val expect = if ((x + y) % 2 == 0) Color.BLUE else Color.WHITE
            assertEquals("($x,$y)", expect, px[y * w + x])
        }
        assertEquals(Color.BLACK, px[5 * w + 20]); assertEquals(Color.WHITE, px[5 * w + 30])
        // A solid colour then replaces the pattern everywhere.
        assertNotNull(c.fill(3, 3, Color.GREEN, 1f, 0, true, false))
        assertTrue((0 until 20).all { px[7 * w + it] == Color.GREEN })
        // Once something else changes the area, a fill works on the pixels as they are.
        px[2 * w + 2] = Color.YELLOW
        assertNotNull(c.fill(10, 10, Color.CYAN, 1f, 0, true, false))
        assertEquals(Color.YELLOW, px[2 * w + 2]); assertEquals(Color.CYAN, px[10 * w + 10])
    }

    @Test
    fun `cropped layers are decoded at the layer size not the canvas size`() {
        val layer = Layer(id = "p", name = "Photo", type = LayerType.IMAGE, contentWidth = 1440f, contentHeight = 3200f)
        assertEquals(3200, com.example.engine.LayerRenderer.sourcePixelsNeeded(layer, 1f))
        assertEquals(1600, com.example.engine.LayerRenderer.sourcePixelsNeeded(layer, 0.5f))
        layer.scaleX = 3f; layer.scaleY = 3f
        assertEquals(4096, com.example.engine.LayerRenderer.sourcePixelsNeeded(layer, 1f))
    }

    // --- Brush, eraser, gradient and pattern fills -----------------------------------------

    @Test
    fun `brush opacity caps a stroke and the eraser clears`() {
        val w = 60; val h = 40
        val px = IntArray(w * h) { Color.WHITE }
        val c = com.example.engine.RetouchCanvas(w, h, px)
        c.beginStroke(com.example.engine.RetouchTool.BRUSH, com.example.engine.RetouchBrush(hardness = 1f, fillColor = Color.BLUE, brushOpacity = 0.5f, brushSmoothing = 0f))
        c.stroke(floatArrayOf(10f, 20f, 50f, 20f, 10f, 20f), 5f); c.endStroke()
        val mid = px[20 * w + 30]
        assertEquals(255, mid and 255)
        assertTrue(((mid shr 16) and 255) in 120..135)
        assertEquals(Color.WHITE, px[2 * w + 2])
        c.beginStroke(com.example.engine.RetouchTool.ERASER, com.example.engine.RetouchBrush(hardness = 1f, brushSmoothing = 0f))
        c.stroke(floatArrayOf(30f, 5f, 30f, 35f), 3f); c.endStroke()
        assertEquals(0, px[20 * w + 30] ushr 24)
    }

    @Test
    fun `gradient and pattern fills`() {
        val w = 60; val h = 40
        val b = com.example.engine.RetouchBrush(fillColor = Color.RED, fillColor2 = Color.BLUE)
        val g = IntArray(w * h) { Color.WHITE }
        com.example.engine.RetouchCanvas(w, h, g).fill(5, 5, { x, y -> com.example.engine.RetouchCanvas.gradientAt(b, 0f, 0f, w.toFloat(), 0f, x, y) }, 1f, 10, true, false)
        assertTrue(Color.red(g[10 * w + 1]) > 240 && Color.blue(g[10 * w + 58]) > 230)
        val gt = IntArray(w * h) { Color.GREEN }
        val bt = b.copy(gradientToTransparent = true)
        com.example.engine.RetouchCanvas(w, h, gt).fill(5, 5, { x, y -> com.example.engine.RetouchCanvas.gradientAt(bt, 0f, 0f, w.toFloat(), 0f, x, y) }, 1f, 10, true, false)
        assertEquals(255, gt[10 * w + 59] ushr 24)
        assertTrue(Color.green(gt[10 * w + 59]) > 230)
        for (p in com.example.engine.FillPattern.values()) {
            val vals = HashSet<Int>()
            for (y in 0 until 200 step 3) for (x in 0 until 200 step 3) vals.add(com.example.engine.RetouchCanvas.patternAt(b.copy(pattern = p), 200, x, y))
            assertTrue("$p", vals.size >= 2)
        }
    }

    @Test
    fun `skin retouch removes a blemish and keeps clean skin`() {
        val w = 400; val h = 400
        val skinColor = Color.rgb(210, 160, 140)
        val g = face()
        val fix = com.example.engine.FaceSkin.fixer(listOf(g to com.example.engine.FaceAdjust(blemishes = 100f)))!!
        // Geometry of face() is in a 200 px image: scale 2 for this 400 px one.
        val px2 = IntArray(w * h) { skinColor }
        for (y in 206..214) for (x in 146..154) px2[y * w + x] = Color.rgb(122, 74, 64)
        fix(px2, w, h, 2f)
        assertTrue(lum(px2[210 * w + 150]) > lum(Color.rgb(122, 74, 64)) + 40)
        assertTrue(kotlin.math.abs(lum(px2[260 * w + 150]) - lum(skinColor)) < 6)
        assertNull(com.example.engine.FaceSkin.fixer(listOf(g to com.example.engine.FaceAdjust())))
        // Liquify carries the skin fix to the full-resolution result.
        val src = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(skinColor) }
        for (y in 103..107) for (x in 73..77) src.setPixel(x, y, Color.rgb(122, 74, 64))
        val e = LiquifyEngine(src)
        e.setSkinFix(fix)
        assertTrue(e.hasChanges)
        assertTrue(lum(e.output[105 * 200 + 75]) > lum(Color.rgb(122, 74, 64)) + 30)
    }

    // --- Refine Edge Brush and AI assist -----------------------------------------------------

    @Test
    fun `refine edge brush mattes painted areas far from the selection edge`() {
        val w = 80; val h = 40
        val red = Color.rgb(220, 30, 30); val blue = Color.rgb(30, 30, 220); val mixed = Color.rgb(125, 30, 125)
        val img = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) img.setPixel(x, y, when { x < 30 -> red; x in 50..53 -> mixed; else -> blue })
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        android.graphics.Canvas(mask).drawRect(0f, 0f, 30f, h.toFloat(), android.graphics.Paint().apply { color = Color.BLACK })
        val cfg = com.example.engine.RefineEdgeConfig(radiusPx = 3, smartRadius = false)
        val plain = com.example.engine.RefineEdgeEngine.refineEdges(img, mask, cfg).refinedMask
        assertTrue(Color.alpha(plain.getPixel(51, 20)) < 20)
        val area = ByteArray(w * h) { i -> if (i % w in 44..60) 255.toByte() else 0 }
        val brushed = com.example.engine.RefineEdgeEngine.refineEdges(img, mask, cfg, area).refinedMask
        val a = Color.alpha(brushed.getPixel(51, 20))
        assertTrue("strand alpha $a", a in 60..200)
        assertTrue(Color.alpha(brushed.getPixel(58, 20)) < 40)
        // Where colours cannot separate (same colour both sides), the AI confidence decides.
        val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(red) }
        val ai = ByteArray(w * h) { i -> if (i % w < 45) 255.toByte() else 0 }
        val withAi = com.example.engine.RefineEdgeEngine.refineEdges(flat, mask, cfg, area, ai).refinedMask
        assertTrue(Color.alpha(withAi.getPixel(52, 20)) < 40)
    }

    @Test
    fun `refine brush on a large image keeps far strands like the preview`() {
        // Strand 300 px from the subject: beyond the full-resolution sample search, within reach
        // at preview scale. Without the preview-scale reference it was lost (alpha 0) on apply.
        val w = 3000; val h = 120
        val red = Color.rgb(220, 30, 30); val blue = Color.rgb(30, 30, 220); val mixed = Color.rgb(125, 30, 125)
        val img = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w) { x -> when { x < 1000 -> red; x in 1296..1311 -> mixed; else -> blue } }
        for (y in 0 until h) img.setPixels(row, 0, w, 0, y, w, 1)
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        android.graphics.Canvas(mask).drawRect(0f, 0f, 1000f, h.toFloat(), android.graphics.Paint().apply { color = Color.BLACK })
        val cfg = com.example.engine.RefineEdgeConfig(radiusPx = 3, smartRadius = false)
        val area = ByteArray(w * h) { i -> if (i % w in 990..1500) 255.toByte() else 0 }
        val res = com.example.engine.RefineEdgeEngine.refineEdges(img, mask, cfg, area).refinedMask
        val a = Color.alpha(res.getPixel(1303, 60))
        assertTrue("far strand alpha $a", a in 50..220)
        assertTrue(Color.alpha(res.getPixel(1450, 60)) < 50)
        assertTrue(Color.alpha(res.getPixel(500, 60)) > 240)
    }

    @Test
    fun `spot healing removes a spot and keeps the skin texture`() {
        val w = 300; val h = 300
        val rnd = java.util.Random(5)
        val px = IntArray(w * h) { val v = rnd.nextInt(17) - 8; Color.rgb(210 + v, 160 + v, 140 + v) }
        val orig = px.copyOf()
        for (y in 140..152) for (x in 140..152) if ((x - 146) * (x - 146) + (y - 146) * (y - 146) <= 36) px[y * w + x] = Color.rgb(120, 60, 55)
        val c = com.example.engine.RetouchCanvas(w, h, px)
        assertNotNull(c.heal(floatArrayOf(146f, 146f, 147f, 146f), 9f))
        assertTrue(lum(px[146 * w + 146]) > 160)
        fun std(a: IntArray, x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var s = 0.0; var q = 0.0; var n = 0
            for (y in y0..y1) for (x in x0..x1) { val l = lum(a[y * w + x]); s += l; q += l * l; n++ }
            val m = s / n; return kotlin.math.sqrt(q / n - m * m)
        }
        assertTrue("texture kept", std(px, 143, 143, 149, 149) > 0.5 * std(orig, 50, 50, 56, 56))
        assertEquals(orig[20 * w + 20], px[20 * w + 20])
    }

    @Test
    fun `clipping groups find their base and count`() {
        fun l(id: String, clipped: Boolean) = Layer(id = id, name = id, type = LayerType.IMAGE, isClipped = clipped)
        // Bottom to top: photo, curves (clipped), text (clipped), sticker.
        val layers = listOf(l("photo", false), l("curves", true), l("text", true), l("sticker", false))
        assertEquals(0, com.example.ui.components.clipBaseIndex(layers, 1))
        assertEquals(0, com.example.ui.components.clipBaseIndex(layers, 2))
        assertNull(com.example.ui.components.clipBaseIndex(layers, 3))
        assertEquals(2, com.example.ui.components.clippedCountAbove(layers, 0))
        assertEquals(0, com.example.ui.components.clippedCountAbove(layers, 3))
        assertEquals(0, com.example.ui.components.clippedCountAbove(layers, 1))
    }

    // --- Colour decontamination --------------------------------------------------------------

    @Test
    fun `decontamination restores strand colours and cleans the tinted rim`() {
        val w = 120; val h = 60
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val alpha = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val a = when { x < 50 -> 1f; x >= 60 -> 0f; else -> 1f - (x - 49) / 11f }
            alpha[y * w + x] = (a * 255).toInt().coerceIn(0, 255).toByte()
            val fr = 200 + (y % 5) * 6
            var r = (a * fr + (1 - a) * 30).toInt(); val g = (a * 40 + (1 - a) * 60).toInt(); var b = (a * 30 + (1 - a) * 220).toInt()
            if (x in 46..49) { r -= 40; b += 60 }
            bmp.setPixel(x, y, Color.argb(255, r, g, b.coerceAtMost(255)))
        }
        val out = com.example.engine.ColorDecontaminator.decontaminate(bmp, alpha, 1f)!!
        assertTrue(Color.red(out.getPixel(58, 30)) > 150 && Color.blue(out.getPixel(58, 30)) < 90)
        assertTrue(Color.red(out.getPixel(52, 30)) > 170 && Color.blue(out.getPixel(52, 30)) < 80)
        assertTrue(Color.blue(out.getPixel(49, 30)) < Color.blue(bmp.getPixel(49, 30)) - 15)
        assertEquals(bmp.getPixel(10, 30), out.getPixel(10, 30))
        assertEquals(bmp.getPixel(100, 30), out.getPixel(100, 30))
        assertTrue((25..34).map { Color.red(out.getPixel(53, it)) }.toSet().size > 1)
        // Amount 0 or no soft pixels: nothing to do.
        assertNull(com.example.engine.ColorDecontaminator.decontaminate(bmp, alpha, 0f))
        assertNull(com.example.engine.ColorDecontaminator.decontaminate(bmp, ByteArray(w * h) { 255.toByte() }, 1f))
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

    private fun fsImage(): Bitmap {
        val rnd = java.util.Random(5)
        val img = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        for (y in 0 until 30) for (x in 0 until 40) {
            // A hard edge (for Surface / Median) plus noise.
            val base = if (x < 20) 70 else 190
            img.setPixel(x, y, Color.rgb(base + rnd.nextInt(30), 100 + rnd.nextInt(40), 220 - y * 3))
        }
        return img
    }

    private fun maxDiff(a: Bitmap, b: Bitmap): Int {
        var m = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            m = maxOf(m, kotlin.math.abs(Color.red(p) - Color.red(q)), kotlin.math.abs(Color.green(p) - Color.green(q)), kotlin.math.abs(Color.blue(p) - Color.blue(q)))
        }
        return m
    }

    @Test
    fun `every low pass method rebuilds the image exactly`() {
        val img = fsImage()
        for (m in com.example.engine.LowPassMethod.values()) {
            val (low, high) = com.example.engine.FrequencySeparation.split(img, 3f, m, 20f)
            val rebuilt = low.copy(Bitmap.Config.ARGB_8888, true)
            com.example.engine.FrequencySeparation.linearLight(rebuilt, high, 1f)
            assertTrue("$m: ${maxDiff(img, rebuilt)}", maxDiff(img, rebuilt) <= 1)
        }
        // Median and Surface keep the hard edge in Low far better than Gaussian.
        fun edgeStep(m: com.example.engine.LowPassMethod): Int {
            val low = com.example.engine.FrequencySeparation.lowPass(img, 3f, m, 20f)
            return Color.red(low.getPixel(21, 15)) - Color.red(low.getPixel(18, 15))
        }
        assertTrue(edgeStep(com.example.engine.LowPassMethod.MEDIAN) > edgeStep(com.example.engine.LowPassMethod.GAUSSIAN))
    }

    @Test
    fun `three band separation rebuilds the image`() {
        val img = fsImage()
        val (low, mid, high) = com.example.engine.FrequencySeparation.split3(img, 5f, 1.2f, com.example.engine.LowPassMethod.GAUSSIAN, 20f)
        val rebuilt = low.copy(Bitmap.Config.ARGB_8888, true)
        com.example.engine.FrequencySeparation.linearLight(rebuilt, mid, 1f)
        com.example.engine.FrequencySeparation.linearLight(rebuilt, high, 1f)
        assertTrue("diff ${maxDiff(img, rebuilt)}", maxDiff(img, rebuilt) <= 2)
        // Mid carries detail: not flat grey.
        val greys = (0 until 40).map { Color.red(mid.getPixel(it, 15)) }.toSet()
        assertTrue(greys.size > 3)
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
    fun `unlinked eyes change one eye only and the jaw follows the real outline`() {
        val g0 = face()
        // Only the image-right eye is enlarged when the eyes are unlinked.
        val a = com.example.engine.FaceAdjust().unlinked().copy(eye2Size = 100f)
        val (dx, _) = com.example.engine.FaceWarp.field(listOf(g0 to a), 200, 200)!!
        assertTrue(dx[80 * 200 + 124] < 0f)
        assertEquals(0f, dx[80 * 200 + 76], 1e-4f)
        // Outline (an ellipse) known: face width moves the outline outwards on both sides.
        val oval = (0 until 36).map { i ->
            val t = i * 2 * Math.PI / 36
            android.graphics.PointF((100 + 40 * kotlin.math.cos(t)).toFloat(), (95 + 55 * kotlin.math.sin(t)).toFloat())
        }
        val g = g0.copy(oval = oval)
        val (wx, _) = com.example.engine.FaceWarp.field(listOf(g to com.example.engine.FaceAdjust(faceWidth = 100f)), 200, 200)!!
        // Right edge at mouth level: samples further in (content moved out) -> negative dx.
        assertTrue(wx[120 * 200 + 140] < 0f)
        assertTrue(wx[120 * 200 + 60] > 0f)
        // Far from the face nothing moves.
        assertEquals(0f, wx[5 * 200 + 5], 0f)
        val (jx, _) = com.example.engine.FaceWarp.field(listOf(g to com.example.engine.FaceAdjust(jawline = -100f)), 200, 200)!!
        // Narrower jaw: below the mouth the right side samples further out (content moved in).
        assertTrue(jx[135 * 200 + 132] > 0f)
        // Above the eyes the jaw setting does nothing.
        assertEquals(0f, jx[55 * 200 + 140], 1e-3f)
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
