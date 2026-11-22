package com.example

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AdjustmentKind
import com.example.data.model.ColorAdjustments
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Project
import com.example.engine.LayerRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Layer masks as the canvas and the export render them (black hides, white shows). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskRenderTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun vmWith(vararg layers: Layer, w: Int, h: Int): com.example.ui.viewmodel.EditorViewModel {
        val vm = com.example.ui.viewmodel.EditorViewModel(app)
        val p = Project(name = "Masks", width = w, height = h)
        layers.forEach { p.layers.add(it) }
        vm.openProject(p)
        return vm
    }

    private fun image(id: String, w: Int, h: Int, color: Int) = Layer(
        id = id, name = id, type = LayerType.IMAGE,
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) },
        contentWidth = w.toFloat(), contentHeight = h.toFloat()
    )

    private fun render(vm: com.example.ui.viewmodel.EditorViewModel, preview: Int?): Bitmap = runBlocking {
        val p = vm.uiState.value.currentProject!!
        // Same as the canvas: preview snapshots of the layers.
        LayerRenderer.renderCompositeCanvas(app, p.layers.map { it.previewSnapshot() }, p.width, p.height,
            includeBackground = false, maxPreviewDimension = preview)
    }

    private fun state(vm: com.example.ui.viewmodel.EditorViewModel, i: Int): String {
        val l = vm.uiState.value.currentProject!!.layers[i]
        val m = l.maskBitmap
        return "mask=${m?.let { "${it.width}x${it.height} ${it.config} a(c)=${(it.getPixel(it.width / 2, it.height / 2) ushr 24)}" }} " +
            "enabled=${l.isMaskEnabled} linked=${l.isMaskLinked} anchor=${l.maskAnchor} density=${l.maskDensity} feather=${l.maskFeather}"
    }

    private fun checkHidden(vm: com.example.ui.viewmodel.EditorViewModel, preview: Int?, what: String) {
        val out = render(vm, preview)
        val c = out.getPixel(out.width / 2, out.height / 2)
        assertEquals("$what preview=$preview ${state(vm, vm.uiState.value.selectedLayerIndex)} pixel=${Integer.toHexString(c)}",
            0, Color.alpha(c))
    }

    @Test
    fun probe() {
        val vm = vmWith(image("a", 200, 150, Color.RED), w = 200, h = 150)
        vm.selectLayer(0)
        vm.addLayerMask(fromSelection = false)
        vm.invertLayerMask()
        val l = vm.uiState.value.currentProject!!.layers[0]
        val eff = LayerRenderer.renderEffectiveMask(l, 200, 150)
        val single = runBlocking { LayerRenderer.renderSingleLayerToBitmap(app, l.previewSnapshot(), 200, 150, 1f) }
        val singlePooled = runBlocking { LayerRenderer.renderSingleLayerToBitmap(app, l.previewSnapshot(), 200, 150, 1f, cacheLook = true) }
        // Raw Android: DST_IN with this mask.
        val raw = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        android.graphics.Canvas(raw).drawBitmap(l.maskBitmap!!, 0f, 0f, android.graphics.Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) })
        val a8 = Bitmap.createBitmap(200, 150, Bitmap.Config.ALPHA_8)
        android.graphics.Canvas(a8).drawBitmap(l.maskBitmap!!, 0f, 0f, android.graphics.Paint())
        fun tryDraw(src: Bitmap, paint: android.graphics.Paint): String {
            val t = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            android.graphics.Canvas(t).drawBitmap(src, 0f, 0f, paint)
            return Integer.toHexString(t.getPixel(100, 75))
        }
        val argb = l.maskBitmap!!.copy(Bitmap.Config.ARGB_8888, false)
        val fresh = Bitmap.createBitmap(200, 150, Bitmap.Config.ALPHA_8) // all zero, never written
        val erased = Bitmap.createBitmap(200, 150, Bitmap.Config.ALPHA_8).apply { eraseColor(Color.TRANSPARENT) }
        val probes = "argbCopy=${tryDraw(argb, android.graphics.Paint())}(a=${argb.getPixel(100,75) ushr 24}) " +
            "blackPaint=${tryDraw(l.maskBitmap!!, android.graphics.Paint().apply { color = Color.BLACK })} " +
            "fresh=${tryDraw(fresh, android.graphics.Paint())} erased=${tryDraw(erased, android.graphics.Paint())} " +
            "a8a8=${a8.let { tryDraw(it, android.graphics.Paint()) }}"
        val msg = probes + " eff=${eff?.let { "${it.config} a=${it.getPixel(100, 75) ushr 24}" }} single=${single?.let { Integer.toHexString(it.getPixel(100, 75)) }} " +
            "pooled=${singlePooled?.let { Integer.toHexString(it.getPixel(100, 75)) }} raw=${Integer.toHexString(raw.getPixel(100, 75))} a8copy=${a8.getPixel(100, 75) ushr 24} " +
            "maskHasAlpha=${l.maskBitmap!!.hasAlpha()} premul=${l.maskBitmap!!.isPremultiplied}"
        throw AssertionError("PROBE $msg")
    }

    @Test
    fun invertedMaskHidesImageLayerSmallDoc() {
        val vm = vmWith(image("a", 200, 150, Color.RED), w = 200, h = 150)
        vm.selectLayer(0)
        vm.addLayerMask(fromSelection = false)
        vm.invertLayerMask()
        checkHidden(vm, 1440, "small doc, preview")
        checkHidden(vm, null, "small doc, export")
    }

    @Test
    fun invertedMaskHidesImageLayerLargeDoc() {
        // Larger than the preview size: the canvas renders it downscaled.
        val vm = vmWith(image("a", 3000, 2000, Color.RED), w = 3000, h = 2000)
        vm.selectLayer(0)
        vm.addLayerMask(fromSelection = false)
        checkVisibleThenHidden(vm)
    }

    private fun checkVisibleThenHidden(vm: com.example.ui.viewmodel.EditorViewModel) {
        // Render once with the white mask (fills the caches), then invert.
        val first = render(vm, 1440)
        assertEquals("white mask shows ${state(vm, 0)}", 255, Color.alpha(first.getPixel(first.width / 2, first.height / 2)))
        vm.invertLayerMask()
        checkHidden(vm, 1440, "after invert")
        checkHidden(vm, 600, "quick pass")
    }

    @Test
    fun paintedBlackHidesImageLayer() {
        val vm = vmWith(image("a", 400, 300, Color.RED), w = 400, h = 300)
        vm.selectLayer(0)
        vm.addLayerMask(fromSelection = false)
        render(vm, 1440)
        vm.paintMaskSegment(listOf(android.graphics.PointF(150f, 150f), android.graphics.PointF(250f, 150f)), 60f,
            isReveal = false, phase = com.example.ui.viewmodel.StrokePhase.START)
        vm.paintMaskSegment(listOf(android.graphics.PointF(260f, 150f)), 60f,
            isReveal = false, phase = com.example.ui.viewmodel.StrokePhase.END)
        checkHidden(vm, 1440, "painted black")
    }

    @Test
    fun invertedMaskOnMovedLayer() {
        val vm = vmWith(image("a", 400, 300, Color.RED), w = 400, h = 300)
        vm.selectLayer(0)
        vm.addLayerMask(fromSelection = false)
        vm.updateSelectedLayerTransient { it.positionX += 10f }
        vm.commitHistoryTransaction()
        render(vm, 1440)
        vm.invertLayerMask()
        checkHidden(vm, 1440, "moved layer")
    }

    @Test
    fun invertedMaskStopsAdjustmentLayer() {
        val adj = Layer(id = "adj", name = "adj", type = LayerType.ADJUSTMENT, adjustmentKind = AdjustmentKind.GENERAL,
            adjustments = ColorAdjustments(saturation = -100f))
        val vm = vmWith(image("a", 400, 300, Color.RED), adj, w = 400, h = 300)
        vm.selectLayer(1)
        vm.addLayerMask(fromSelection = false)
        val before = render(vm, 1440)
        val c0 = before.getPixel(200, 150)
        assertTrue("adjustment works: ${Integer.toHexString(c0)}", Color.red(c0) - Color.green(c0) < 30)
        vm.invertLayerMask()
        val after = render(vm, 1440)
        val c = after.getPixel(200, 150)
        assertTrue("masked adjustment leaves red: ${Integer.toHexString(c)} ${state(vm, 1)}", Color.red(c) > 200 && Color.green(c) < 40)
    }

    @Test
    fun invertedMaskOnPhotoFromFile() {
        // A photo layer as imported: full-size source file plus a smaller preview bitmap.
        val dir = java.io.File(app.cacheDir, "masktest").apply { mkdirs() }
        val file = java.io.File(dir, "photo.png")
        val full = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        file.outputStream().use { full.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val layer = Layer(id = "p", name = "p", type = LayerType.IMAGE,
            bitmap = Bitmap.createScaledBitmap(full, 1200, 800, true), sourceAssetPath = file.absolutePath,
            contentWidth = 2400f, contentHeight = 1600f, originalWidth = 2400, originalHeight = 1600)
        val vm = vmWith(layer, w = 2400, h = 1600)
        vm.selectLayer(0)
        vm.addLayerMask(fromSelection = false)
        checkVisibleThenHidden(vm)
        checkHidden(vm, null, "export")
    }
}
