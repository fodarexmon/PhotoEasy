package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.example.data.model.AvailableFonts
import com.example.data.model.BlendModeType
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.TextAlignment
import com.example.data.model.TextLayerConfig
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/**
 * A Pro effect (blur, sharpen, ...) shown live on the canvas before it is applied destructively.
 * [sourceBitmap] is the layer bitmap the preview was made for: once the layer's pixels are replaced
 * (the effect was applied, undo, ...) the preview no longer matches and is ignored, so the effect is
 * never shown twice. [fullResMaxDimension] is the longest side of the image the effect will really
 * be applied to, used to scale pixel radii down for the smaller preview.
 */
data class ProEffectPreview(
    val layerId: String,
    val sourceBitmap: Bitmap?,
    val effect: com.example.data.model.ProFilterType,
    val param1: Float,
    val param2: Float,
    val fullResMaxDimension: Int
)

object LayerRenderer {

    // Last Pro effect preview result: panning, zooming or moving another slider re-renders the
    // canvas, and effects like oil paint are too slow to recompute for every frame.
    private val proCacheLock = Any()
    private var proCacheKey: String? = null
    private var proCacheBitmap: Bitmap? = null

    /** Frees the cached Pro effect preview (call when the preview is closed). */
    fun clearProEffectCache() {
        synchronized(proCacheLock) {
            proCacheBitmap?.let { if (!it.isRecycled) it.recycle() }
            proCacheBitmap = null
            proCacheKey = null
        }
    }

    private suspend fun proEffectFor(input: Bitmap, preview: ProEffectPreview, isCancelled: () -> Boolean): Bitmap {
        val inputMax = max(input.width, input.height)
        val ratio = if (preview.fullResMaxDimension > 0) {
            (inputMax.toFloat() / preview.fullResMaxDimension.toFloat()).coerceAtMost(1f)
        } else 1f
        val key = "${System.identityHashCode(preview.sourceBitmap)}:${preview.sourceBitmap?.generationId}:" +
                "${input.width}x${input.height}:${preview.effect}:${preview.param1}:${preview.param2}:${preview.fullResMaxDimension}"
        synchronized(proCacheLock) {
            val cached = proCacheBitmap
            if (key == proCacheKey && cached != null && !cached.isRecycled) {
                return cached.copy(Bitmap.Config.ARGB_8888, true)
            }
        }
        val (p1, p2) = ImageProcessor.scaleProEffectParams(preview.effect, preview.param1, preview.param2, ratio)
        var result = ImageProcessor.applyProEffect(input, preview.effect, p1, p2, isCancelled)
        if (result === input) result = input.copy(Bitmap.Config.ARGB_8888, true)
        // A cancelled effect may stop early with partial pixels; never cache those.
        try {
            coroutineContext.ensureActive()
        } catch (e: CancellationException) {
            if (!result.isRecycled) result.recycle()
            throw e
        }
        synchronized(proCacheLock) {
            proCacheBitmap?.let { if (!it.isRecycled) it.recycle() }
            proCacheBitmap = result.copy(Bitmap.Config.ARGB_8888, false)
            proCacheKey = key
        }
        return result
    }

    /** True when [preview] targets [layer] and still matches its current pixels. */
    private fun previewApplies(preview: ProEffectPreview?, layer: Layer): Boolean =
        preview != null && preview.layerId == layer.id && layer.type == LayerType.IMAGE &&
                preview.sourceBitmap != null && preview.sourceBitmap === layer.bitmap

    /**
     * Composites all visible layers onto a final canvas bitmap.
     * Supports [maxPreviewDimension] to compute a fast Proxy Preview for real-time UI editing,
     * while allowing full-resolution rendering when set to null (for Export / Save).
     * Supports [targetBitmap] buffer reuse to eliminate garbage collection & memory spikes during rapid slider scrubbing.
     */
    suspend fun renderCompositeCanvas(
        context: Context,
        layers: List<Layer>,
        width: Int,
        height: Int,
        includeBackground: Boolean = true,
        backgroundColor: Int? = null,
        maxPreviewDimension: Int? = null,
        targetBitmap: Bitmap? = null,
        generationId: Long? = null,
        activeSelectionMask: Bitmap? = null,
        selectedLayerId: String? = null,
        proEffectPreview: ProEffectPreview? = null,
        /** Document-space selection limiting [proEffectPreview], as the real effect is limited. */
        proEffectMask: Bitmap? = null
    ): Bitmap {
        coroutineContext.ensureActive()
        FilterLibrary.ensureLoaded(context)
        val currentJob = coroutineContext[Job]

        var targetW = width
        var targetH = height
        var scaleRatio = 1.0f

        if (maxPreviewDimension != null && (width > maxPreviewDimension || height > maxPreviewDimension)) {
            val maxSide = max(width, height)
            scaleRatio = maxPreviewDimension.toFloat() / maxSide.toFloat()
            targetW = (width * scaleRatio).toInt().coerceAtLeast(1)
            targetH = (height * scaleRatio).toInt().coerceAtLeast(1)
        }

        val canReuseTarget = targetBitmap != null &&
                !targetBitmap.isRecycled &&
                targetBitmap.isMutable &&
                targetBitmap.width == targetW &&
                targetBitmap.height == targetH

        val composite = if (canReuseTarget) {
            targetBitmap!!.apply {
                eraseColor(backgroundColor ?: Color.TRANSPARENT)
            }
        } else {
            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            if (backgroundColor != null) {
                bmp.eraseColor(backgroundColor)
            } else {
                bmp.eraseColor(Color.TRANSPARENT)
            }
            bmp
        }

        val canvas = Canvas(composite)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        try {
            val docW = width
            val docH = height
            var i = 0
            while (i < layers.size) {
                coroutineContext.ensureActive()
                val layer = layers[i]
                // A clipping group: a normal base layer plus the clipped layers directly above it.
                var j = i + 1
                if (layer.type != LayerType.ADJUSTMENT && !layer.isClipped) {
                    while (j < layers.size && layers[j].isClipped) j++
                }
                val clipped = if (j > i + 1) layers.subList(i + 1, j) else emptyList()
                i = j
                // A hidden base hides its clipped layers too (as in Photoshop).
                if (!layer.isVisible || layer.opacity <= 0.01f) continue

                if (layer.type == LayerType.ADJUSTMENT) {
                    // Non-destructive Adjustment Layer: applies adjustments/filters to accumulated composite
                    if (composite.width > 0 && composite.height > 0) {
                        val copy = composite.copy(Bitmap.Config.ARGB_8888, true)
                        var adjustedComposite = copy
                        try {
                            adjustedComposite = applyLook(copy, layer, currentJob)
                            coroutineContext.ensureActive()
                            // A masked adjustment layer only affects the revealed area; the hidden
                            // area becomes transparent and the composite below shows through unchanged.
                            applyLayerMask(Canvas(adjustedComposite), layer, adjustedComposite.width, adjustedComposite.height, docW, docH)
                            paint.alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                            applyBlendMode(paint, layer.blendMode)
                            canvas.drawBitmap(adjustedComposite, 0f, 0f, paint)
                        } finally {
                            if (!copy.isRecycled) copy.recycle()
                            if (!adjustedComposite.isRecycled) adjustedComposite.recycle()
                        }
                    }
                    continue
                }

                coroutineContext.ensureActive()
                val maskForLayer = if (layer.id == selectedLayerId) activeSelectionMask else null
                val layerBitmap = if (proEffectPreview != null && previewApplies(proEffectPreview, layer)) {
                    renderLayerWithProPreview(context, layer, targetW, targetH, scaleRatio, maskForLayer, proEffectPreview, proEffectMask)
                } else {
                    renderSingleLayerToBitmap(context, layer, targetW, targetH, scaleRatio, maskForLayer)
                } ?: continue
                try {
                    for (c in clipped) {
                        coroutineContext.ensureActive()
                        if (!c.isVisible || c.opacity <= 0.01f) continue
                        drawClippedLayer(context, layerBitmap, c, targetW, targetH, scaleRatio, docW, docH, currentJob,
                            if (c.id == selectedLayerId) activeSelectionMask else null)
                    }
                    coroutineContext.ensureActive()
                    paint.alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                    applyBlendMode(paint, layer.blendMode)
                    canvas.drawBitmap(layerBitmap, 0f, 0f, paint)
                } finally {
                    if (!layerBitmap.isRecycled) {
                        layerBitmap.recycle()
                    }
                }
            }
            coroutineContext.ensureActive()
        } catch (e: CancellationException) {
            // Clean up newly allocated bitmap on cooperative cancellation to prevent memory spikes
            if (!canReuseTarget && !composite.isRecycled) {
                composite.recycle()
            }
            throw e
        }

        return composite
    }

    /**
     * Renders a lightweight thumbnail bitmap directly scaled to targetMaxDimension (320px default)
     * without rendering full resolution canvas first.
     */
    suspend fun renderThumbnail(
        context: Context,
        layers: List<Layer>,
        width: Int,
        height: Int,
        targetMaxDimension: Int = 320
    ): Bitmap {
        return renderCompositeCanvas(
            context = context,
            layers = layers,
            width = width,
            height = height,
            includeBackground = true,
            maxPreviewDimension = targetMaxDimension
        )
    }

    /**
     * Renders a single layer (Image, Text, Color Fill) to a canvas sized bitmap
     */
    /** Applies [layer]'s adjustments, filter and LUT to [input]; returns [input] or a new bitmap. */
    private suspend fun applyLook(input: Bitmap, layer: Layer, currentJob: Job?): Bitmap {
        val isCancelled: () -> Boolean = { currentJob?.isActive == false }
        var bmp = input
        fun swap(next: Bitmap) {
            if (next !== bmp && bmp !== input && !bmp.isRecycled) bmp.recycle()
            bmp = next
        }
        if (!layer.adjustments.isIdentity()) {
            coroutineContext.ensureActive()
            swap(ImageProcessor.applyAdjustments(bmp, layer.adjustments, isCancelled))
        }
        if (layer.filter != com.example.data.model.FilterType.NONE) {
            coroutineContext.ensureActive()
            swap(ImageProcessor.applyFilter(bmp, layer.filter, layer.filterIntensity, isCancelled))
        }
        FilterLibrary.lut(layer.lutId)?.let { lut ->
            coroutineContext.ensureActive()
            swap(ImageProcessor.applyLut(bmp, lut, layer.filterIntensity, isCancelled))
        }
        return bmp
    }

    /**
     * Draws clipped layer [c] onto its clipping base [base] (canvas-sized): it only shows where the
     * base is opaque. A clipped adjustment layer adjusts the base only, not everything below.
     */
    private suspend fun drawClippedLayer(
        context: Context,
        base: Bitmap,
        c: Layer,
        targetW: Int,
        targetH: Int,
        scaleRatio: Float,
        docW: Int,
        docH: Int,
        currentJob: Job?,
        selectionForLayer: Bitmap?
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.alpha = (c.opacity * 255).toInt().coerceIn(0, 255)
        applyBlendMode(paint, c.blendMode)
        val keepBaseAlpha = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        if (c.type == LayerType.ADJUSTMENT) {
            val copy = base.copy(Bitmap.Config.ARGB_8888, true)
            var adjusted = copy
            try {
                adjusted = applyLook(copy, c, currentJob)
                applyLayerMask(Canvas(adjusted), c, adjusted.width, adjusted.height, docW, docH)
                Canvas(adjusted).drawBitmap(base, 0f, 0f, keepBaseAlpha)
                Canvas(base).drawBitmap(adjusted, 0f, 0f, paint)
            } finally {
                if (!copy.isRecycled) copy.recycle()
                if (!adjusted.isRecycled) adjusted.recycle()
            }
            return
        }
        val cb = renderSingleLayerToBitmap(context, c, targetW, targetH, scaleRatio, selectionForLayer) ?: return
        try {
            Canvas(cb).drawBitmap(base, 0f, 0f, keepBaseAlpha)
            Canvas(base).drawBitmap(cb, 0f, 0f, paint)
        } finally {
            if (!cb.isRecycled) cb.recycle()
        }
    }

    private fun placementMatrix(x: Float, y: Float, sx: Float, sy: Float, rot: Float, docW: Int, docH: Int): Matrix =
        Matrix().apply {
            postScale(sx, sy)
            postRotate(rot)
            postTranslate(docW / 2f + x, docH / 2f + y)
        }

    /**
     * Document-to-document matrix that moves a linked mask from where it was painted (its anchor)
     * to where the layer is now; null when the mask does not need to move.
     */
    fun linkedMaskMatrix(layer: Layer, docW: Int, docH: Int): Matrix? {
        if (!layer.isMaskLinked) return null
        val a = layer.maskAnchor ?: return null
        if (a == layer.currentMaskAnchor()) return null
        val anchor = placementMatrix(a.x, a.y, a.scaleX, a.scaleY, a.rotation, docW, docH)
        val inv = Matrix()
        if (!anchor.invert(inv)) return null
        inv.postConcat(placementMatrix(layer.positionX, layer.positionY, layer.scaleX, layer.scaleY, layer.rotation, docW, docH))
        return inv
    }

    // Effective masks (moved with the layer, feathered, density applied) at the render size. They
    // only change when the mask or its settings change, so panning and slider moves elsewhere do
    // not re-blur a full document-sized mask on every frame. Evicted entries are left to the GC
    // because another render may still be drawing them.
    private val maskCacheLock = Any()
    private val maskCache = object : LinkedHashMap<String, Pair<String, Bitmap>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Bitmap>>?): Boolean = size > 12
    }

    fun clearMaskCache() {
        synchronized(maskCacheLock) { maskCache.clear() }
    }

    private fun effectiveMask(layer: Layer, targetW: Int, targetH: Int, docW: Int, docH: Int, useCache: Boolean): Bitmap? {
        val mask = layer.maskBitmap ?: return null
        if (mask.isRecycled) return null
        val moved = linkedMaskMatrix(layer, docW, docH)
        val key = buildString {
            append(System.identityHashCode(mask)).append('|').append(mask.generationId).append('|')
            append(targetW).append('x').append(targetH).append('|').append(docW).append('x').append(docH).append('|')
            append(layer.maskFeather).append('|').append(layer.maskDensity)
            if (moved != null) {
                val v = FloatArray(9)
                moved.getValues(v)
                append('|').append(v.joinToString(","))
            }
        }
        if (useCache) {
            synchronized(maskCacheLock) {
                maskCache["${layer.id}@${targetW}x${targetH}"]?.let { (k, bmp) -> if (k == key && !bmp.isRecycled) return bmp }
            }
        }
        var out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ALPHA_8)
        val matrix = Matrix().apply {
            // Legacy masks of another size are stretched to the document first.
            setScale(docW.toFloat() / mask.width.coerceAtLeast(1), docH.toFloat() / mask.height.coerceAtLeast(1))
            if (moved != null) postConcat(moved)
            postScale(targetW.toFloat() / docW.coerceAtLeast(1), targetH.toFloat() / docH.coerceAtLeast(1))
        }
        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (moved != null) {
            // Area the moved mask no longer covers is revealed (white), like Photoshop's default.
            out.eraseColor(Color.WHITE)
            drawPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        Canvas(out).drawBitmap(mask, matrix, drawPaint)

        val featherPx = layer.maskFeather * targetW.toFloat() / docW.coerceAtLeast(1)
        if (featherPx > 0.5f) {
            val blurred = ImageProcessor.applyGaussianBlur(out, featherPx)
            if (blurred !== out) out.recycle()
            out = blurred
        }
        if (layer.maskDensity < 0.99f) {
            val modMask = out.copy(Bitmap.Config.ARGB_8888, true)
            val whiteOverlay = Paint().apply {
                color = Color.WHITE
                alpha = ((1f - layer.maskDensity) * 255).toInt().coerceIn(0, 255)
            }
            Canvas(modMask).drawRect(0f, 0f, modMask.width.toFloat(), modMask.height.toFloat(), whiteOverlay)
            out.recycle()
            out = modMask
        }
        if (useCache) synchronized(maskCacheLock) { maskCache["${layer.id}@${targetW}x${targetH}"] = key to out }
        return out
    }

    /**
     * Renders [layer] with the live Pro effect. With an active selection the effect is shown only
     * inside it (soft edges included): the plain layer is drawn and the effected one is masked over it.
     */
    private suspend fun renderLayerWithProPreview(
        context: Context,
        layer: Layer,
        targetW: Int,
        targetH: Int,
        scaleRatio: Float,
        maskForLayer: Bitmap?,
        preview: ProEffectPreview,
        selection: Bitmap?
    ): Bitmap? {
        val hasSelection = selection != null && !selection.isRecycled && SelectionEngine.hasSelection(selection)
        val effected = renderSingleLayerToBitmap(context, layer, targetW, targetH, scaleRatio, maskForLayer, preview)
            ?: return null
        if (!hasSelection || selection == null) return effected
        val base = try {
            renderSingleLayerToBitmap(context, layer, targetW, targetH, scaleRatio, maskForLayer)
        } catch (e: Throwable) {
            if (!effected.isRecycled) effected.recycle()
            throw e
        }
        if (base == null) return effected
        try {
            coroutineContext.ensureActive()
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            val maskMatrix = Matrix().apply {
                postScale(
                    targetW.toFloat() / selection.width.coerceAtLeast(1).toFloat(),
                    targetH.toFloat() / selection.height.coerceAtLeast(1).toFloat()
                )
            }
            Canvas(effected).drawBitmap(selection, maskMatrix, maskPaint)
            Canvas(base).drawBitmap(effected, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        } catch (e: Throwable) {
            if (!base.isRecycled) base.recycle()
            throw e
        } finally {
            if (!effected.isRecycled) effected.recycle()
        }
        return base
    }

    suspend fun renderSingleLayerToBitmap(
        context: Context,
        layer: Layer,
        canvasWidth: Int,
        canvasHeight: Int,
        scaleRatio: Float = 1.0f,
        activeSelectionMask: Bitmap? = null,
        proEffect: ProEffectPreview? = null
    ): Bitmap? {
        coroutineContext.ensureActive()
        FilterLibrary.ensureLoaded(context)
        val currentJob = coroutineContext[Job]
        val output = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            when (layer.type) {
                LayerType.IMAGE -> {
                    coroutineContext.ensureActive()
                    // Full render uses the authoritative source, but never decodes more pixels than the document can use.
                    var fullResSourceBmp: Bitmap? = null
                    if (scaleRatio >= 1.0f && layer.sourceAssetPath != null) {
                        val srcFile = File(layer.sourceAssetPath!!)
                        if (srcFile.exists()) fullResSourceBmp = ImageProcessor.decodeFileWithExifSampled(srcFile, max(canvasWidth, canvasHeight))
                    }

                    val baseBmp = fullResSourceBmp ?: layer.bitmap
                    if (baseBmp == null) {
                        if (!output.isRecycled) output.recycle()
                        return null
                    }
                    var processedBmp = baseBmp

                    // Optimize preview rendering by downscaling first before running heavy color matrices and filters
                    if (scaleRatio < 1.0f && (baseBmp.width > 800 || baseBmp.height > 800)) {
                        coroutineContext.ensureActive()
                        val pw = (baseBmp.width * scaleRatio).toInt().coerceAtLeast(1)
                        val ph = (baseBmp.height * scaleRatio).toInt().coerceAtLeast(1)
                        processedBmp = Bitmap.createScaledBitmap(baseBmp, pw, ph, true)
                    }

                    try {
                        // Live Pro effect preview: applied to the pixels first, exactly like the real
                        // destructive effect, so the layer's adjustments and filter stay on top of it.
                        if (proEffect != null && previewApplies(proEffect, layer)) {
                            coroutineContext.ensureActive()
                            val isCancelledCheck: () -> Boolean = { currentJob?.isActive == false }
                            val effected = proEffectFor(processedBmp, proEffect, isCancelledCheck)
                            if (processedBmp !== baseBmp && !processedBmp.isRecycled) processedBmp.recycle()
                            processedBmp = effected
                        }

                        // Apply color adjustments if present
                        if (!layer.adjustments.isIdentity()) {
                            coroutineContext.ensureActive()
                            val isCancelledCheck: () -> Boolean = { currentJob?.isActive == false }
                            val adjusted = ImageProcessor.applyAdjustments(processedBmp, layer.adjustments, isCancelledCheck)
                            if (processedBmp !== baseBmp && processedBmp !== adjusted && !processedBmp.isRecycled) {
                                processedBmp.recycle()
                            }
                            processedBmp = adjusted
                        }

                        // Apply filter if present
                        if (layer.filter != com.example.data.model.FilterType.NONE) {
                            coroutineContext.ensureActive()
                            val isCancelledCheck: () -> Boolean = { currentJob?.isActive == false }
                            val filtered = ImageProcessor.applyFilter(processedBmp, layer.filter, layer.filterIntensity, isCancelledCheck)
                            if (processedBmp !== baseBmp && processedBmp !== filtered && !processedBmp.isRecycled) {
                                processedBmp.recycle()
                            }
                            processedBmp = filtered
                        }

                        // Imported 3D LUT look
                        FilterLibrary.lut(layer.lutId)?.let { lut ->
                            coroutineContext.ensureActive()
                            val isCancelledCheck: () -> Boolean = { currentJob?.isActive == false }
                            val graded = ImageProcessor.applyLut(processedBmp, lut, layer.filterIntensity, isCancelledCheck)
                            if (processedBmp !== baseBmp && processedBmp !== graded && !processedBmp.isRecycled) {
                                processedBmp.recycle()
                            }
                            processedBmp = graded
                        }

                        coroutineContext.ensureActive()
                        val matrix = Matrix().apply {
                            SelectionEngine.layerShapeMatrix(layer, processedBmp.width.toFloat(), processedBmp.height.toFloat())?.let { set(it) }
                            postTranslate(-processedBmp.width / 2f, -processedBmp.height / 2f)
                            val contentW = layer.intrinsicDocumentWidth().coerceAtLeast(1f)
                            val contentH = layer.intrinsicDocumentHeight().coerceAtLeast(1f)
                            postScale(
                                layer.scaleX * (contentW / processedBmp.width.toFloat()) * scaleRatio,
                                layer.scaleY * (contentH / processedBmp.height.toFloat()) * scaleRatio
                            )
                            postRotate(layer.rotation)
                            postTranslate((canvasWidth / 2f) + layer.positionX * scaleRatio, (canvasHeight / 2f) + layer.positionY * scaleRatio)
                        }

                        val hasActiveSel = activeSelectionMask != null && !activeSelectionMask.isRecycled && SelectionEngine.hasSelection(activeSelectionMask)
                        val hasModifications = (!layer.adjustments.isIdentity() || layer.filter != com.example.data.model.FilterType.NONE || layer.lutId != null)

                        if (hasActiveSel && hasModifications) {
                            // Draw base unadjusted layer first
                            val baseMatrix = Matrix().apply {
                                SelectionEngine.layerShapeMatrix(layer, baseBmp.width.toFloat(), baseBmp.height.toFloat())?.let { set(it) }
                                postTranslate(-baseBmp.width / 2f, -baseBmp.height / 2f)
                                val contentW = layer.intrinsicDocumentWidth().coerceAtLeast(1f)
                                val contentH = layer.intrinsicDocumentHeight().coerceAtLeast(1f)
                                postScale(
                                    layer.scaleX * (contentW / baseBmp.width.toFloat()) * scaleRatio,
                                    layer.scaleY * (contentH / baseBmp.height.toFloat()) * scaleRatio
                                )
                                postRotate(layer.rotation)
                                postTranslate((canvasWidth / 2f) + layer.positionX * scaleRatio, (canvasHeight / 2f) + layer.positionY * scaleRatio)
                            }
                            canvas.drawBitmap(baseBmp, baseMatrix, paint)

                            // Composite adjusted/filtered result strictly within active selection mask
                            val bounds = RectF(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat())
                            canvas.saveLayer(bounds, null)
                            canvas.drawBitmap(processedBmp, matrix, paint)

                            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                            }
                            if (activeSelectionMask.width == canvasWidth && activeSelectionMask.height == canvasHeight) {
                                canvas.drawBitmap(activeSelectionMask, 0f, 0f, maskPaint)
                            } else {
                                val maskMatrix = Matrix().apply {
                                    postScale(
                                        canvasWidth.toFloat() / activeSelectionMask.width.coerceAtLeast(1).toFloat(),
                                        canvasHeight.toFloat() / activeSelectionMask.height.coerceAtLeast(1).toFloat()
                                    )
                                }
                                canvas.drawBitmap(activeSelectionMask, maskMatrix, maskPaint)
                            }
                            canvas.restore()
                        } else {
                            canvas.drawBitmap(processedBmp, matrix, paint)
                        }
                    } finally {
                        if (processedBmp !== baseBmp && !processedBmp.isRecycled) {
                            processedBmp.recycle()
                        }
                        if (fullResSourceBmp != null && !fullResSourceBmp.isRecycled) {
                            fullResSourceBmp.recycle()
                        }
                    }
                }

                LayerType.COLOR_FILL -> {
                    canvas.drawColor(layer.colorFillHex)
                }

                LayerType.TEXT -> {
                    coroutineContext.ensureActive()
                    // Text is laid out in document units, then mapped to the render target once.
                    val docWidth = if (scaleRatio > 0f) (canvasWidth / scaleRatio) else canvasWidth.toFloat()
                    val text = TextRenderSpec.create(context, layer.textConfig, docWidth)

                    val matrix = Matrix().apply {
                        SelectionEngine.layerShapeMatrix(layer, text.layoutWidth, text.layoutHeight)?.let { set(it) }
                        postTranslate(-text.layoutWidth / 2f, -text.layoutHeight / 2f)
                        postScale(layer.scaleX * scaleRatio, layer.scaleY * scaleRatio)
                        postRotate(layer.rotation)
                        postTranslate(
                            (canvasWidth / 2f) + (layer.positionX * scaleRatio),
                            (canvasHeight / 2f) + (layer.positionY * scaleRatio)
                        )
                    }
                    canvas.save()
                    canvas.concat(matrix)
                    text.draw(canvas)
                    canvas.restore()
                }

                LayerType.ADJUSTMENT -> {
                    // Adjustment layer
                }
            }

            // Apply Layer Mask if present and enabled (Photoshop-standard DST_IN compositing)
            coroutineContext.ensureActive()
            val docW = (canvasWidth / scaleRatio.coerceAtLeast(0.0001f)).roundToInt().coerceAtLeast(1)
            val docH = (canvasHeight / scaleRatio.coerceAtLeast(0.0001f)).roundToInt().coerceAtLeast(1)
            applyLayerMask(canvas, layer, output.width, output.height, docW, docH)
        } catch (e: CancellationException) {
            if (!output.isRecycled) {
                output.recycle()
            }
            throw e
        }

        return output
    }

    /**
     * Everything needed to draw one text layer in document units. Sizes in [TextLayerConfig] are
     * relative to a 500 px wide document, so text and all its effects (stroke, shadow, glow,
     * background) keep the same proportions on any image size.
     */
    private class TextRenderSpec(
        val cfg: TextLayerConfig,
        val unit: Float,
        val basePaint: TextPaint,
        val mainLayout: StaticLayout,
        val layoutWidth: Float,
        val layoutHeight: Float
    ) {
        private fun withOpacity(color: Int, opacity: Float): Int =
            (color and 0x00FFFFFF) or ((opacity.coerceIn(0f, 1f) * 255f).toInt() shl 24)

        private fun layoutWith(paint: TextPaint): StaticLayout = LayerRenderer.buildTextLayout(cfg, paint, layoutWidth.toInt())

        val strokePx: Float get() = cfg.strokeWidthDp * 3f * unit
        val paddingPx: Float get() = if (cfg.backgroundEnabled) cfg.backgroundPadding * unit else 0f

        fun draw(canvas: Canvas) {
            // Background box behind the whole text block.
            if (cfg.backgroundEnabled && cfg.backgroundOpacity > 0f) {
                val p = paddingPx
                val r = cfg.backgroundCorner * unit
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withOpacity(cfg.backgroundColorHex, cfg.backgroundOpacity) }
                canvas.drawRoundRect(RectF(-p, -p, layoutWidth + p, layoutHeight + p), r, r, bg)
            }

            // Outer glow: only the blurred halo outside the glyphs (no tinted copy of the text).
            if (cfg.outerGlowRadiusDp > 0f && cfg.glowOpacity > 0f) {
                val glowPaint = TextPaint(basePaint).apply {
                    shader = null
                    color = withOpacity(cfg.outerGlowColorHex, cfg.glowOpacity)
                    style = if (strokePx > 0f) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
                    strokeWidth = strokePx
                    maskFilter = android.graphics.BlurMaskFilter(
                        (cfg.outerGlowRadiusDp * 2.5f * unit).coerceAtLeast(0.5f),
                        android.graphics.BlurMaskFilter.Blur.OUTER
                    )
                }
                val glowLayout = layoutWith(glowPaint)
                glowLayout.draw(canvas)
                glowLayout.draw(canvas)
            }

            // Drop shadow: the text shape (with its outline) blurred and offset, drawn only once.
            if (cfg.shadowRadiusDp > 0f && cfg.shadowOpacity > 0f) {
                val blur = cfg.shadowRadiusDp * 2f * unit
                val shadowPaint = TextPaint(basePaint).apply {
                    shader = null
                    color = withOpacity(cfg.shadowColorHex, cfg.shadowOpacity)
                    style = if (strokePx > 0f) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
                    strokeWidth = strokePx
                    if (blur > 0.5f) maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                canvas.save()
                canvas.translate(cfg.shadowDxDp * 2f * unit, cfg.shadowDyDp * 2f * unit)
                layoutWith(shadowPaint).draw(canvas)
                canvas.restore()
            }

            // Outline under the fill.
            if (strokePx > 0f) {
                val strokePaint = TextPaint(basePaint).apply {
                    shader = null
                    style = Paint.Style.STROKE
                    strokeWidth = strokePx
                    color = cfg.strokeColorHex
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                }
                layoutWith(strokePaint).draw(canvas)
            }

            // Fill (solid or gradient across the text box).
            if (cfg.gradientEnabled) {
                val rad = Math.toRadians(cfg.gradientAngle.toDouble())
                val cx = layoutWidth / 2f
                val cy = layoutHeight / 2f
                val dx = kotlin.math.cos(rad).toFloat()
                val dy = kotlin.math.sin(rad).toFloat()
                val half = (kotlin.math.abs(layoutWidth * dx) + kotlin.math.abs(layoutHeight * dy)) / 2f
                basePaint.shader = android.graphics.LinearGradient(
                    cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                    cfg.colorHex, cfg.gradientEndColorHex, android.graphics.Shader.TileMode.CLAMP
                )
                basePaint.color = Color.WHITE
            }
            mainLayout.draw(canvas)
        }

        companion object {
            fun create(context: Context, cfg: TextLayerConfig, docWidth: Float): TextRenderSpec {
                val unit = LayerRenderer.textUnit(docWidth)
                val paint = LayerRenderer.buildTextPaint(context, cfg, docWidth)
                val width = LayerRenderer.textLayoutWidth(cfg, paint, docWidth)
                val layout = LayerRenderer.buildTextLayout(cfg, paint, width)
                return TextRenderSpec(cfg, unit, paint, layout, layout.width.toFloat(), layout.height.toFloat())
            }
        }
    }

    /** Document pixels per text "unit": sizes are designed for a 500 px wide document. */
    private fun textUnit(docWidth: Float): Float = (docWidth / 500f).coerceAtLeast(1f)

    private fun buildTextPaint(context: Context, cfg: TextLayerConfig, docWidth: Float): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = cfg.fontSizeSp * textUnit(docWidth)
            color = cfg.colorHex
            style = Paint.Style.FILL
            letterSpacing = cfg.letterSpacingEm
            isUnderlineText = cfg.isUnderline

            val fontItem = AvailableFonts.find { it.name.equals(cfg.fontName, ignoreCase = true) }
            val base = if (fontItem?.fontResId != null) {
                try {
                    ResourcesCompat.getFont(context, fontItem.fontResId) ?: Typeface.DEFAULT
                } catch (e: Exception) {
                    Typeface.DEFAULT
                }
            } else {
                Typeface.DEFAULT
            }
            val style = (if (cfg.isBold) Typeface.BOLD else 0) or (if (cfg.isItalic) Typeface.ITALIC else 0)
            typeface = if (style != 0) Typeface.create(base, style) else base
            // Fonts without a real bold / italic face get a synthetic one.
            if (cfg.isBold && typeface?.isBold != true) isFakeBoldText = true
            if (cfg.isItalic && typeface?.isItalic != true) textSkewX = -0.22f
        }

    /**
     * Width of the text box: as wide as the longest line needs (plus room for outline / italic),
     * never wider than [TextLayerConfig.maxLineWidthPercent] of the document. Long lines wrap.
     */
    private fun textLayoutWidth(cfg: TextLayerConfig, paint: TextPaint, docWidth: Float): Int {
        val maxWidth = (docWidth * (cfg.maxLineWidthPercent.coerceIn(10f, 100f) / 100f)).coerceAtLeast(1f)
        val extra = cfg.strokeWidthDp * 3f * textUnit(docWidth) + (if (cfg.isItalic) paint.textSize * 0.25f else 0f) + 2f
        val desired = StaticLayout.getDesiredWidth(cfg.text, paint) + extra
        return kotlin.math.ceil(minOf(maxWidth, desired)).toInt().coerceAtLeast(1)
    }

    private fun buildTextLayout(cfg: TextLayerConfig, paint: TextPaint, width: Int): StaticLayout {
        // Left / right are visual sides: for right-to-left text (Arabic) "normal" is the right side.
        val rtl = cfg.text.isNotEmpty() && TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(cfg.text, 0, cfg.text.length)
        val layoutAlign = when (cfg.alignment) {
            TextAlignment.LEFT -> if (rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
            TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignment.RIGHT -> if (rtl) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(cfg.text, 0, cfg.text.length, paint, width)
                .setAlignment(layoutAlign)
                .setLineSpacing(0f, cfg.lineSpacingMultiplier)
                .setIncludePad(true)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(cfg.text, paint, width, layoutAlign, cfg.lineSpacingMultiplier, 0f, true)
        }
    }

    /**
     * Unscaled document-space size (width, height) of a text layer's box, i.e. the rectangle the
     * renderer centres on the layer position before applying scale and rotation (including the
     * background box when enabled), used for selection frames and hit testing.
     */
    fun measureTextLayer(context: Context, layer: Layer, docWidth: Float): Pair<Float, Float> {
        val spec = TextRenderSpec.create(context, layer.textConfig, docWidth)
        val p = spec.paddingPx * 2f
        return (spec.layoutWidth + p).coerceAtLeast(1f) to (spec.layoutHeight + p).coerceAtLeast(1f)
    }

    /**
     * Renders the mask exactly as the compositor applies it (feather + density) into a
     * [width] x [height] ARGB bitmap whose alpha channel is the effective mask.
     */
    /** The mask exactly as rendered (moved with the layer, feathered, density), at [width] x [height]. */
    fun renderEffectiveMask(layer: Layer, width: Int, height: Int): Bitmap? {
        val mask = layer.maskBitmap ?: return null
        if (mask.isRecycled) return null
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        applyLayerMask(Canvas(out), layer.copy(isMaskEnabled = true), width, height, width, height, useCache = false)
        return out
    }

    /**
     * Multiplies the canvas content by the layer mask (DST_IN). Layer masks live in DOCUMENT space
     * ([docW] x [docH]); a linked mask is moved with the layer, and the result is drawn at the
     * render target size.
     */
    private fun applyLayerMask(
        canvas: Canvas,
        layer: Layer,
        targetWidth: Int,
        targetHeight: Int,
        docW: Int,
        docH: Int,
        useCache: Boolean = true
    ) {
        if (layer.maskBitmap == null || !layer.isMaskEnabled) return
        val eff = effectiveMask(layer, targetWidth, targetHeight, docW, docH, useCache) ?: return
        val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(eff, 0f, 0f, maskPaint)
        if (!useCache && !eff.isRecycled) eff.recycle()
    }

    private fun applyBlendMode(paint: Paint, mode: BlendModeType) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            paint.blendMode = when (mode) {
                BlendModeType.NORMAL -> android.graphics.BlendMode.SRC_OVER
                BlendModeType.MULTIPLY -> android.graphics.BlendMode.MULTIPLY
                BlendModeType.SCREEN -> android.graphics.BlendMode.SCREEN
                BlendModeType.OVERLAY -> android.graphics.BlendMode.OVERLAY
                BlendModeType.DARKEN -> android.graphics.BlendMode.DARKEN
                BlendModeType.LIGHTEN -> android.graphics.BlendMode.LIGHTEN
                BlendModeType.SOFT_LIGHT -> android.graphics.BlendMode.SOFT_LIGHT
                BlendModeType.HARD_LIGHT -> android.graphics.BlendMode.HARD_LIGHT
                BlendModeType.DIFFERENCE -> android.graphics.BlendMode.DIFFERENCE
                BlendModeType.ADD -> android.graphics.BlendMode.PLUS
                BlendModeType.COLOR_DODGE -> android.graphics.BlendMode.COLOR_DODGE
                BlendModeType.COLOR_BURN -> android.graphics.BlendMode.COLOR_BURN
                BlendModeType.EXCLUSION -> android.graphics.BlendMode.EXCLUSION
                BlendModeType.HUE -> android.graphics.BlendMode.HUE
                BlendModeType.SATURATION -> android.graphics.BlendMode.SATURATION
                BlendModeType.COLOR -> android.graphics.BlendMode.COLOR
                BlendModeType.LUMINOSITY -> android.graphics.BlendMode.LUMINOSITY
            }
        } else {
            paint.xfermode = when (mode) {
                BlendModeType.NORMAL -> PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
                BlendModeType.MULTIPLY -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                BlendModeType.SCREEN -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                BlendModeType.OVERLAY -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                BlendModeType.DARKEN -> PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                BlendModeType.LIGHTEN -> PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                BlendModeType.ADD -> PorterDuffXfermode(PorterDuff.Mode.ADD)
                // On API < 29, unsupported modes safely fallback to clean normal composition rather than garbled XOR/OVERLAY
                BlendModeType.SOFT_LIGHT, BlendModeType.HARD_LIGHT, BlendModeType.DIFFERENCE,
                BlendModeType.COLOR_DODGE, BlendModeType.COLOR_BURN, BlendModeType.EXCLUSION,
                BlendModeType.HUE, BlendModeType.SATURATION, BlendModeType.COLOR,
                BlendModeType.LUMINOSITY -> PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
            }
        }
    }
}
