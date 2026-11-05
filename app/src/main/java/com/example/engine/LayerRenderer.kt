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
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

object LayerRenderer {

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
        selectedLayerId: String? = null
    ): Bitmap {
        coroutineContext.ensureActive()
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
            for (layer in layers) {
                coroutineContext.ensureActive()
                if (!layer.isVisible || layer.opacity <= 0.01f) continue

                if (layer.type == LayerType.ADJUSTMENT) {
                    // Non-destructive Adjustment Layer: applies adjustments/filters to accumulated composite
                    if (composite.width > 0 && composite.height > 0) {
                        coroutineContext.ensureActive()
                        var adjustedComposite = composite.copy(Bitmap.Config.ARGB_8888, true)
                        try {
                            if (!layer.adjustments.isIdentity()) {
                                coroutineContext.ensureActive()
                                val isCancelledCheck: () -> Boolean = { currentJob?.isActive == false }
                                val temp = ImageProcessor.applyAdjustments(adjustedComposite, layer.adjustments, isCancelledCheck)
                                if (temp !== adjustedComposite && !adjustedComposite.isRecycled) {
                                    adjustedComposite.recycle()
                                }
                                adjustedComposite = temp
                            }
                            coroutineContext.ensureActive()
                            if (layer.filter != com.example.data.model.FilterType.NONE) {
                                coroutineContext.ensureActive()
                                val isCancelledCheck: () -> Boolean = { currentJob?.isActive == false }
                                val temp = ImageProcessor.applyFilter(adjustedComposite, layer.filter, layer.filterIntensity, isCancelledCheck)
                                if (temp !== adjustedComposite && !adjustedComposite.isRecycled) {
                                    adjustedComposite.recycle()
                                }
                                adjustedComposite = temp
                            }

                            coroutineContext.ensureActive()
                            paint.alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                            applyBlendMode(paint, layer.blendMode)
                            canvas.drawBitmap(adjustedComposite, 0f, 0f, paint)
                        } finally {
                            if (!adjustedComposite.isRecycled) {
                                adjustedComposite.recycle()
                            }
                        }
                    }
                    continue
                }

                coroutineContext.ensureActive()
                val maskForLayer = if (layer.id == selectedLayerId) activeSelectionMask else null
                val layerBitmap = renderSingleLayerToBitmap(context, layer, targetW, targetH, scaleRatio, maskForLayer) ?: continue
                try {
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
    suspend fun renderSingleLayerToBitmap(
        context: Context,
        layer: Layer,
        canvasWidth: Int,
        canvasHeight: Int,
        scaleRatio: Float = 1.0f,
        activeSelectionMask: Bitmap? = null
    ): Bitmap? {
        coroutineContext.ensureActive()
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

                        coroutineContext.ensureActive()
                        val matrix = Matrix().apply {
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
                        val hasModifications = (!layer.adjustments.isIdentity() || layer.filter != com.example.data.model.FilterType.NONE)

                        if (hasActiveSel && hasModifications) {
                            // Draw base unadjusted layer first
                            val baseMatrix = Matrix().apply {
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
                    val cfg = layer.textConfig
                    // Compute true unscaled document dimensions to construct layout strictly in document coordinate space
                    val docWidth = if (scaleRatio > 0f) (canvasWidth / scaleRatio) else canvasWidth.toFloat()

                    val basePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        // Document-space font size (scaled relative to 500px reference document width)
                        textSize = cfg.fontSizeSp * (docWidth / 500f).coerceAtLeast(1f)
                        color = cfg.colorHex
                        style = Paint.Style.FILL
                        letterSpacing = cfg.letterSpacingEm

                        // Font selection
                        val fontItem = AvailableFonts.find { it.name.equals(cfg.fontName, ignoreCase = true) }
                        if (fontItem?.fontResId != null) {
                            try {
                                typeface = ResourcesCompat.getFont(context, fontItem.fontResId)
                            } catch (e: Exception) {
                                typeface = Typeface.DEFAULT
                            }
                        } else {
                            typeface = Typeface.DEFAULT
                        }
                    }

                    val docMaxLayoutWidth = (docWidth * (cfg.maxLineWidthPercent / 100f)).toInt().coerceAtLeast(50)

                    val layoutAlign = when (cfg.alignment) {
                        TextAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
                        TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                        TextAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                    }

                    fun buildLayout(paint: TextPaint): StaticLayout {
                        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            StaticLayout.Builder.obtain(cfg.text, 0, cfg.text.length, paint, docMaxLayoutWidth)
                                .setAlignment(layoutAlign)
                                .setLineSpacing(0f, cfg.lineSpacingMultiplier)
                                .setIncludePad(true)
                                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                                .build()
                        } else {
                            @Suppress("DEPRECATION")
                            StaticLayout(
                                cfg.text,
                                paint,
                                docMaxLayoutWidth,
                                layoutAlign,
                                cfg.lineSpacingMultiplier,
                                0f,
                                true
                            )
                        }
                    }

                    val mainLayout = buildLayout(basePaint)
                    val layoutWidth = mainLayout.width.toFloat()
                    val layoutHeight = mainLayout.height.toFloat()

                    // Matrix maps document-space layout into canvas target space (applying scaleRatio exactly once)
                    val matrix = Matrix().apply {
                        postTranslate(-layoutWidth / 2f, -layoutHeight / 2f)
                        postScale(layer.scaleX * scaleRatio, layer.scaleY * scaleRatio)
                        postRotate(layer.rotation)
                        postTranslate(
                            (canvasWidth / 2f) + (layer.positionX * scaleRatio),
                            (canvasHeight / 2f) + (layer.positionY * scaleRatio)
                        )
                    }

                    canvas.save()
                    canvas.concat(matrix)

                    // Draw Outer Glow if configured (radiates softly around glyphs)
                    if (cfg.outerGlowRadiusDp > 0f) {
                        val glowPaint = TextPaint(basePaint).apply {
                            color = cfg.outerGlowColorHex
                            setShadowLayer(
                                cfg.outerGlowRadiusDp * 2.5f,
                                0f,
                                0f,
                                cfg.outerGlowColorHex
                            )
                        }
                        val glowLayout = buildLayout(glowPaint)
                        glowLayout.draw(canvas)
                        glowLayout.draw(canvas)
                    }

                    // Draw Drop Shadow if configured (defined in document units, transformed via canvas matrix)
                    if (cfg.shadowRadiusDp > 0f) {
                        val shadowPaint = TextPaint(basePaint).apply {
                            color = cfg.shadowColorHex
                            setShadowLayer(
                                cfg.shadowRadiusDp * 2f,
                                cfg.shadowDxDp * 2f,
                                cfg.shadowDyDp * 2f,
                                cfg.shadowColorHex
                            )
                        }
                        val shadowLayout = buildLayout(shadowPaint)
                        shadowLayout.draw(canvas)
                    }

                    // Draw Text Stroke/Outline if configured (defined in document units, transformed via canvas matrix)
                    if (cfg.strokeWidthDp > 0f) {
                        val strokePaint = TextPaint(basePaint).apply {
                            style = Paint.Style.STROKE
                            strokeWidth = cfg.strokeWidthDp * 3f
                            color = cfg.strokeColorHex
                            strokeJoin = Paint.Join.ROUND
                            strokeCap = Paint.Cap.ROUND
                        }
                        val strokeLayout = buildLayout(strokePaint)
                        strokeLayout.draw(canvas)
                    }

                    // Draw Fill Text
                    mainLayout.draw(canvas)

                    canvas.restore()
                }

                LayerType.ADJUSTMENT -> {
                    // Adjustment layer
                }
            }

            // Apply Layer Mask if present and enabled (Photoshop-standard DST_IN compositing)
            val maskBmp = layer.maskBitmap
            if (maskBmp != null && layer.isMaskEnabled && !maskBmp.isRecycled) {
                coroutineContext.ensureActive()
                var effectiveMask = maskBmp
                var tempAllocated: Bitmap? = null

                if (layer.maskFeather > 0.5f) {
                    tempAllocated = ImageProcessor.applyGaussianBlur(effectiveMask, layer.maskFeather)
                    effectiveMask = tempAllocated
                }

                if (layer.maskDensity < 0.99f) {
                    val modMask = effectiveMask.copy(Bitmap.Config.ARGB_8888, true)
                    val mc = Canvas(modMask)
                    val whiteOverlay = Paint().apply {
                        color = Color.WHITE
                        alpha = ((1f - layer.maskDensity) * 255).toInt().coerceIn(0, 255)
                    }
                    mc.drawRect(0f, 0f, modMask.width.toFloat(), modMask.height.toFloat(), whiteOverlay)
                    if (tempAllocated != null && !tempAllocated.isRecycled) tempAllocated.recycle()
                    tempAllocated = modMask
                    effectiveMask = modMask
                }

                try {
                    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    }
                    if (effectiveMask.width == output.width && effectiveMask.height == output.height) {
                        canvas.drawBitmap(effectiveMask, 0f, 0f, maskPaint)
                    } else {
                        val maskMatrix = Matrix().apply {
                            postScale(
                                output.width.toFloat() / effectiveMask.width.coerceAtLeast(1).toFloat(),
                                output.height.toFloat() / effectiveMask.height.coerceAtLeast(1).toFloat()
                            )
                        }
                        canvas.drawBitmap(effectiveMask, maskMatrix, maskPaint)
                    }
                } finally {
                    if (tempAllocated != null && !tempAllocated.isRecycled) {
                        tempAllocated.recycle()
                    }
                }
            }
        } catch (e: CancellationException) {
            if (!output.isRecycled) {
                output.recycle()
            }
            throw e
        }

        return output
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
                BlendModeType.SOFT_LIGHT, BlendModeType.HARD_LIGHT, BlendModeType.DIFFERENCE -> PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
            }
        }
    }
}
