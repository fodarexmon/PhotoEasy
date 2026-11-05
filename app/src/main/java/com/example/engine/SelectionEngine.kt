package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.example.data.model.Layer
import com.example.data.model.SelectionMode
import java.nio.ByteBuffer

object SelectionEngine {

    /**
     * Computes layer transformation matrix M_layer mapping local bitmap pixels to document canvas space
     */
    fun getLayerMatrix(layer: Layer, canvasWidth: Int, canvasHeight: Int): Matrix {
        val bmp = layer.bitmap
        val bw = bmp?.width?.toFloat() ?: layer.intrinsicDocumentWidth()
        val bh = bmp?.height?.toFloat() ?: layer.intrinsicDocumentHeight()
        val matrix = Matrix()
        matrix.postTranslate(-bw / 2f, -bh / 2f)
        matrix.postScale(layer.scaleX * layer.intrinsicDocumentWidth() / bw.coerceAtLeast(1f), layer.scaleY * layer.intrinsicDocumentHeight() / bh.coerceAtLeast(1f))
        matrix.postRotate(layer.rotation)
        matrix.postTranslate((canvasWidth / 2f) + layer.positionX, (canvasHeight / 2f) + layer.positionY)
        return matrix
    }

    /**
     * Maps document canvas coordinates (docX, docY) to layer-local bitmap space (x_bmp, y_bmp).
     * Returns null if point falls outside layer bitmap bounds.
     */
    fun mapCanvasToLayerLocal(
        docX: Float,
        docY: Float,
        layer: Layer,
        canvasWidth: Int,
        canvasHeight: Int
    ): PointF? {
        val matrix = getLayerMatrix(layer, canvasWidth, canvasHeight)
        val inverse = Matrix()
        if (!matrix.invert(inverse)) return null

        val pts = floatArrayOf(docX, docY)
        inverse.mapPoints(pts)

        val bmp = layer.bitmap ?: return null
        if (pts[0] < 0f || pts[0] >= bmp.width || pts[1] < 0f || pts[1] >= bmp.height) {
            return null
        }
        return PointF(pts[0], pts[1])
    }

    /**
     * Maps document-wide selection mask to layer-local bitmap space using ALPHA_8
     */
    fun mapDocumentMaskToLayerLocal(
        docMask: Bitmap,
        layer: Layer,
        canvasWidth: Int,
        canvasHeight: Int
    ): Bitmap {
        val bmp = layer.bitmap
        val width = bmp?.width ?: canvasWidth
        val height = bmp?.height ?: canvasHeight

        val localMask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(localMask)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val matrix = getLayerMatrix(layer, canvasWidth, canvasHeight)
        val inverse = Matrix()
        if (matrix.invert(inverse)) {
            canvas.drawBitmap(docMask, inverse, paint)
        }
        return localMask
    }

    /**
     * Composites a processed/filtered bitmap with the original source bitmap,
     * applying changes strictly inside [localMask] (with smooth anti-aliased edge blending).
     */
    fun compositeFilteredWithSelection(
        original: Bitmap,
        filtered: Bitmap,
        localMask: Bitmap
    ): Bitmap {
        val w = original.width
        val h = original.height
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val origPixels = IntArray(w * h)
        val filtPixels = IntArray(w * h)
        original.getPixels(origPixels, 0, w, 0, 0, w, h)
        filtered.getPixels(filtPixels, 0, w, 0, 0, w, h)

        val maskBmp = if (localMask.width == w && localMask.height == h) {
            localMask
        } else {
            Bitmap.createScaledBitmap(localMask, w, h, true)
        }
        val maskPixels = IntArray(w * h)
        maskBmp.getPixels(maskPixels, 0, w, 0, 0, w, h)
        if (maskBmp !== localMask && !maskBmp.isRecycled) {
            maskBmp.recycle()
        }

        val resultPixels = IntArray(w * h)
        for (i in 0 until w * h) {
            val mp = maskPixels[i]
            val a = (mp ushr 24) and 0xFF
            val maskVal = if (a > 0) a else (mp and 0xFF)
            if (maskVal == 0) {
                resultPixels[i] = origPixels[i]
            } else if (maskVal == 255) {
                resultPixels[i] = filtPixels[i]
            } else {
                val invA = 255 - maskVal
                val o = origPixels[i]
                val f = filtPixels[i]
                val or = (o ushr 16) and 0xFF
                val og = (o ushr 8) and 0xFF
                val ob = o and 0xFF

                val fr = (f ushr 16) and 0xFF
                val fg = (f ushr 8) and 0xFF
                val fb = f and 0xFF

                val r = (fr * maskVal + or * invA) / 255
                val g = (fg * maskVal + og * invA) / 255
                val b = (fb * maskVal + ob * invA) / 255
                resultPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        result.setPixels(resultPixels, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Creates a blank 8-bit alpha mask bitmap with dimensions [width] x [height] (ALPHA_8: 75% memory reduction)
     */
    fun createBlankMask(width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
    }

    /**
     * Quick Brush selection stroke onto ALPHA_8 mask with SelectionMode support
     */
    fun drawBrushStroke(
        mask: Bitmap,
        points: List<PointF>,
        brushRadius: Float,
        isEraser: Boolean = false,
        mode: SelectionMode = SelectionMode.ADD
    ) {
        if (points.isEmpty()) return
        if (mode == SelectionMode.REPLACE && !isEraser) mask.eraseColor(Color.TRANSPARENT)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = brushRadius * 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND

            if (isEraser || mode == SelectionMode.SUBTRACT) {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            } else {
                color = Color.WHITE
            }
        }

        val targetCanvas = if (mode == SelectionMode.INTERSECT) {
            val temp = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8)
            Canvas(temp).apply {
                val path = Path()
                path.moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    path.lineTo(points[i].x, points[i].y)
                }
                drawPath(path, paint)
            }
            val maskCanvas = Canvas(mask)
            val blendPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
            maskCanvas.drawBitmap(temp, 0f, 0f, blendPaint)
            temp.recycle()
            return
        } else {
            Canvas(mask)
        }

        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }
        targetCanvas.drawPath(path, paint)
    }

    /**
     * Lasso selection: connects freehand path and fills inside mask
     */
    fun fillLassoPath(
        mask: Bitmap,
        points: List<PointF>,
        mode: SelectionMode = SelectionMode.ADD
    ) {
        if (points.size < 3) return
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }
        path.close()

        applyShapeToMask(mask, path, mode)
    }

    /**
     * Polygon / Pen selection tool: connects polygon points and fills inside mask
     */
    fun fillPolygonPoints(
        mask: Bitmap,
        points: List<PointF>,
        mode: SelectionMode = SelectionMode.ADD
    ) {
        if (points.size < 3) return
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }
        path.close()

        applyShapeToMask(mask, path, mode)
    }

    fun fillRectangleSelection(mask: Bitmap, start: PointF, end: PointF, mode: SelectionMode = SelectionMode.ADD) {
        val r = RectF(minOf(start.x,end.x), minOf(start.y,end.y), maxOf(start.x,end.x), maxOf(start.y,end.y)); if (r.width()<1f || r.height()<1f) return
        applyShapeToMask(mask, Path().apply { addRect(r, Path.Direction.CW) }, mode)
    }

    fun fillEllipseSelection(mask: Bitmap, start: PointF, end: PointF, mode: SelectionMode = SelectionMode.ADD) {
        val r = RectF(minOf(start.x,end.x), minOf(start.y,end.y), maxOf(start.x,end.x), maxOf(start.y,end.y)); if (r.width()<1f || r.height()<1f) return
        applyShapeToMask(mask, Path().apply { addOval(r, Path.Direction.CW) }, mode)
    }

    fun selectAll(mask: Bitmap) = mask.eraseColor(Color.WHITE)

    fun hasSelection(mask: Bitmap): Boolean {
        val row = IntArray(mask.width)
        for (y in 0 until mask.height) { mask.getPixels(row,0,mask.width,0,y,mask.width,1); if (row.any { ((it ushr 24) and 0xFF) > 0 }) return true }
        return false
    }

    private fun applyShapeToMask(mask: Bitmap, path: Path, mode: SelectionMode) {
        when (mode) {
            SelectionMode.REPLACE -> {
                mask.eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(mask)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.WHITE
                }
                canvas.drawPath(path, paint)
            }
            SelectionMode.ADD -> {
                val canvas = Canvas(mask)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.WHITE
                }
                canvas.drawPath(path, paint)
            }
            SelectionMode.SUBTRACT -> {
                val canvas = Canvas(mask)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
                canvas.drawPath(path, paint)
            }
            SelectionMode.INTERSECT -> {
                val temp = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8)
                val tempCanvas = Canvas(temp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.WHITE
                }
                tempCanvas.drawPath(path, paint)

                val canvas = Canvas(mask)
                val blendPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
                canvas.drawBitmap(temp, 0f, 0f, blendPaint)
                temp.recycle()
            }
        }
    }

    fun applyBitmapToMask(mask: Bitmap, sourceMask: Bitmap, mode: SelectionMode) {
        when (mode) {
            SelectionMode.REPLACE -> {
                mask.eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(mask)
                canvas.drawBitmap(sourceMask, 0f, 0f, null)
            }
            SelectionMode.ADD -> {
                val canvas = Canvas(mask)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
                }
                canvas.drawBitmap(sourceMask, 0f, 0f, paint)
            }
            SelectionMode.SUBTRACT -> {
                val canvas = Canvas(mask)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                }
                canvas.drawBitmap(sourceMask, 0f, 0f, paint)
            }
            SelectionMode.INTERSECT -> {
                val canvas = Canvas(mask)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                canvas.drawBitmap(sourceMask, 0f, 0f, paint)
            }
        }
    }

    /**
     * Professional Photoshop-style Quick Selection Tool:
     * Takes stroke points, samples underlying pixel colors, computes local edge gradients (Sobel),
     * and performs edge-aware flood expansion that snaps tightly to boundaries of objects.
     */
    fun quickSelection(
        mask: Bitmap,
        points: List<PointF>,
        brushRadius: Float,
        sourceBitmap: Bitmap,
        mode: SelectionMode = SelectionMode.ADD,
        tolerance: Int = 30,
        autoEnhance: Boolean = true
    ) {
        if (points.isEmpty()) return
        val maskW = mask.width
        val maskH = mask.height
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height
        if (maskW <= 0 || maskH <= 0 || srcW <= 0 || srcH <= 0) return

        var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }

        val pad = (brushRadius * 2.5f).coerceAtLeast(20f)
        val boxLeft = (minX - pad).toInt().coerceIn(0, maskW - 1)
        val boxTop = (minY - pad).toInt().coerceIn(0, maskH - 1)
        val boxRight = (maxX + pad).toInt().coerceIn(0, maskW - 1)
        val boxBottom = (maxY + pad).toInt().coerceIn(0, maskH - 1)
        val boxW = boxRight - boxLeft + 1
        val boxH = boxBottom - boxTop + 1
        if (boxW <= 0 || boxH <= 0) return

        val scaleX = srcW.toFloat() / maskW.toFloat()
        val scaleY = srcH.toFloat() / maskH.toFloat()

        val srcBoxLeft = (boxLeft * scaleX).toInt().coerceIn(0, srcW - 1)
        val srcBoxTop = (boxTop * scaleY).toInt().coerceIn(0, srcH - 1)
        val srcBoxRight = (boxRight * scaleX).toInt().coerceIn(0, srcW - 1)
        val srcBoxBottom = (boxBottom * scaleY).toInt().coerceIn(0, srcH - 1)
        val srcRegionW = (srcBoxRight - srcBoxLeft + 1).coerceAtLeast(1)
        val srcRegionH = (srcBoxBottom - srcBoxTop + 1).coerceAtLeast(1)

        val srcPixels = IntArray(srcRegionW * srcRegionH)
        sourceBitmap.getPixels(srcPixels, 0, srcRegionW, srcBoxLeft, srcBoxTop, srcRegionW, srcRegionH)

        fun getSrcPixel(mx: Int, my: Int): Int {
            val sx = ((mx * scaleX) - srcBoxLeft).toInt().coerceIn(0, srcRegionW - 1)
            val sy = ((my * scaleY) - srcBoxTop).toInt().coerceIn(0, srcRegionH - 1)
            return srcPixels[sy * srcRegionW + sx]
        }

        var sumR = 0L; var sumG = 0L; var sumB = 0L; var sampleCount = 0
        val rSq = brushRadius * brushRadius

        val localSelected = ByteArray(boxW * boxH)
        val queue = IntArray(boxW * boxH)
        var head = 0
        var tail = 0

        for (p in points) {
            val px = p.x.toInt()
            val py = p.y.toInt()
            val rInt = brushRadius.toInt().coerceAtLeast(1)
            for (dy in -rInt..rInt) {
                val cy = py + dy
                if (cy !in boxTop..boxBottom) continue
                for (dx in -rInt..rInt) {
                    val cx = px + dx
                    if (cx !in boxLeft..boxRight) continue
                    if (dx * dx + dy * dy <= rSq) {
                        val lx = cx - boxLeft
                        val ly = cy - boxTop
                        val idx = ly * boxW + lx
                        if (localSelected[idx].toInt() == 0) {
                            localSelected[idx] = 1
                            queue[tail++] = (ly shl 16) or lx
                            val color = getSrcPixel(cx, cy)
                            val cr = (color shr 16) and 0xFF
                            val cg = (color shr 8) and 0xFF
                            val cb = color and 0xFF
                            sumR += cr; sumG += cg; sumB += cb
                            sampleCount++
                        }
                    }
                }
            }
        }

        if (sampleCount == 0) return
        val meanR = (sumR / sampleCount).toFloat()
        val meanG = (sumG / sampleCount).toFloat()
        val meanB = (sumB / sampleCount).toFloat()

        val tolNorm = tolerance.coerceIn(5, 100) / 100f
        val maxColorDistSq = (3.0f * (tolNorm * 220f) * (tolNorm * 220f)).toInt()
        val edgeThreshold = 35f + (1.0f - tolNorm) * 45f

        val luminance = FloatArray(boxW * boxH)
        for (ly in 0 until boxH) {
            val my = boxTop + ly
            val rowOffset = ly * boxW
            for (lx in 0 until boxW) {
                val mx = boxLeft + lx
                val c = getSrcPixel(mx, my)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                luminance[rowOffset + lx] = 0.299f * r + 0.587f * g + 0.114f * b
            }
        }

        fun getGradient(lx: Int, ly: Int): Float {
            val x0 = (lx - 1).coerceAtLeast(0)
            val x1 = (lx + 1).coerceAtMost(boxW - 1)
            val y0 = (ly - 1).coerceAtLeast(0)
            val y1 = (ly + 1).coerceAtMost(boxH - 1)
            val gx = luminance[ly * boxW + x1] - luminance[ly * boxW + x0]
            val gy = luminance[y1 * boxW + lx] - luminance[y0 * boxW + lx]
            return kotlin.math.abs(gx) + kotlin.math.abs(gy)
        }

        val maxExpSq = pad * pad
        val dirs = intArrayOf(1, 0, -1, 0, 0, 1, 0, -1)

        while (head < tail) {
            val cur = queue[head++]
            val cx = cur and 0xFFFF
            val cy = cur ushr 16

            val grad = getGradient(cx, cy)
            if (grad > edgeThreshold) {
                continue
            }

            for (d in 0 until 8 step 2) {
                val nx = cx + dirs[d]
                val ny = cy + dirs[d + 1]
                if (nx in 0 until boxW && ny in 0 until boxH) {
                    val nIdx = ny * boxW + nx
                    if (localSelected[nIdx].toInt() == 0) {
                        val mx = boxLeft + nx
                        val my = boxTop + ny
                        var closeToStroke = false
                        for (p in points) {
                            val pdx = mx - p.x
                            val pdy = my - p.y
                            if (pdx * pdx + pdy * pdy <= maxExpSq) {
                                closeToStroke = true
                                break
                            }
                        }
                        if (!closeToStroke) continue

                        val color = getSrcPixel(mx, my)
                        val cr = ((color shr 16) and 0xFF) - meanR
                        val cg = ((color shr 8) and 0xFF) - meanG
                        val cb = (color and 0xFF) - meanB
                        val distSq = (cr * cr + cg * cg + cb * cb).toInt()

                        if (distSq <= maxColorDistSq) {
                            localSelected[nIdx] = 1
                            queue[tail++] = (ny shl 16) or nx
                        }
                    }
                }
            }
        }

        val smoothed = if (autoEnhance) {
            val out = ByteArray(boxW * boxH)
            for (ly in 1 until boxH - 1) {
                for (lx in 1 until boxW - 1) {
                    var count = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (localSelected[(ly + dy) * boxW + (lx + dx)].toInt() == 1) count++
                        }
                    }
                    out[ly * boxW + lx] = if (count >= 5) 1 else 0
                }
            }
            out
        } else localSelected

        val grownMask = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ALPHA_8)
        val alphaPixels = IntArray(boxW)
        for (ly in 0 until boxH) {
            val my = boxTop + ly
            for (lx in 0 until boxW) {
                alphaPixels[lx] = if (smoothed[ly * boxW + lx].toInt() == 1) 0xFF000000.toInt() else 0
            }
            grownMask.setPixels(alphaPixels, 0, boxW, boxLeft, my, boxW, 1)
        }

        applyBitmapToMask(mask, grownMask, mode)
        grownMask.recycle()
    }

    /**
     * Magic Wand Color Selection onto ALPHA_8 mask with contiguous or global color-range matching
     */
    fun magicWandSelect(
        layer: Layer,
        canvasWidth: Int,
        canvasHeight: Int,
        docMask: Bitmap,
        docTouchX: Float,
        docTouchY: Float,
        tolerance: Int,
        contiguous: Boolean = true,
        mode: SelectionMode = SelectionMode.ADD,
        antiAlias: Boolean = true
    ) {
        val source = layer.bitmap ?: return
        val localPt = mapCanvasToLayerLocal(docTouchX, docTouchY, layer, canvasWidth, canvasHeight) ?: return
        val startX = localPt.x.toInt().coerceIn(0, source.width - 1)
        val startY = localPt.y.toInt().coerceIn(0, source.height - 1)
        val width = source.width
        val height = source.height
        val total = width * height

        // Source pixels are the only full ARGB buffer. Selection state itself remains 1 byte/pixel.
        val sourcePixels = IntArray(total)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        val targetColor = sourcePixels[startY * width + startX]
        val targetA = (targetColor ushr 24) and 0xFF
        val targetR = (targetColor shr 16) and 0xFF
        val targetG = (targetColor shr 8) and 0xFF
        val targetB = targetColor and 0xFF
        val maxChannelDistance = (tolerance.coerceIn(0, 100) / 100f) * 255f
        // Full redmean max distance squared across opposite corners is ~585,036 (~9.0 * 255^2)
        val maxDistSq = (9.0f * maxChannelDistance * maxChannelDistance).toInt()
        val selected = ByteArray(total)

        fun colorMatch(idx: Int): Boolean {
            val c = sourcePixels[idx]
            val a = (c ushr 24) and 0xFF
            if (targetA < 12) return a < 12
            if (a < 12) return false
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val dr = r - targetR
            val dg = g - targetG
            val db = b - targetB
            val rMean = (targetR + r) shr 1
            val distSq = (((512 + rMean) * dr * dr) shr 8) + (4 * dg * dg) + (((767 - rMean) * db * db) shr 8)
            return distSq <= maxDistSq
        }

        if (!colorMatch(startY * width + startX)) return

        if (!contiguous) {
            // Global Color Range: selects all matching tones across the entire image
            for (i in 0 until total) {
                if (colorMatch(i)) {
                    selected[i] = 0xFF.toByte()
                }
            }
        } else {
            // Packed scanline seed stack: one Int per seed instead of separate X/Y arrays.
            var stack = IntArray(minOf(total.coerceAtLeast(1), maxOf(4096, width * 4)))
            var stackPtr = 0
            fun push(x: Int, y: Int) {
                if (stackPtr == stack.size && stack.size < total) {
                    stack = stack.copyOf(minOf(total, (stack.size * 2).coerceAtLeast(stack.size + 1)))
                }
                if (stackPtr < stack.size) stack[stackPtr++] = y * width + x
            }
            push(startX, startY)

            while (stackPtr > 0) {
                val packed = stack[--stackPtr]
                val y = packed / width
                val x = packed - y * width
                val base = y * width
                if (selected[base + x].toInt() != 0) continue

                var left = x
                while (left > 0) {
                    val i = base + left - 1
                    if (selected[i].toInt() == 0 && colorMatch(i)) left-- else break
                }
                var right = x
                while (right < width - 1) {
                    val i = base + right + 1
                    if (selected[i].toInt() == 0 && colorMatch(i)) right++ else break
                }
                for (xx in left..right) selected[base + xx] = 0xFF.toByte()

                val yAbove = y - 1
                val yBelow = y + 1
                if (yAbove >= 0) {
                    val nextBase = yAbove * width
                    var inSpan = false
                    for (xx in left..right) {
                        val i = nextBase + xx
                        val match = selected[i].toInt() == 0 && colorMatch(i)
                        if (match && !inSpan) { push(xx, yAbove); inSpan = true } else if (!match) inSpan = false
                    }
                }
                if (yBelow < height) {
                    val nextBase = yBelow * width
                    var inSpan = false
                    for (xx in left..right) {
                        val i = nextBase + xx
                        val match = selected[i].toInt() == 0 && colorMatch(i)
                        if (match && !inSpan) { push(xx, yBelow); inSpan = true } else if (!match) inSpan = false
                    }
                }
            }
        }

        // Anti-aliasing pass along boundaries for smooth, organic selection edges
        if (antiAlias && width > 2 && height > 2) {
            val aaPass = selected.clone()
            for (y in 1 until height - 1) {
                val row = y * width
                for (x in 1 until width - 1) {
                    val idx = row + x
                    if (selected[idx].toInt() != 0) {
                        val nTop = selected[idx - width].toInt() and 0xFF
                        val nBot = selected[idx + width].toInt() and 0xFF
                        val nLeft = selected[idx - 1].toInt() and 0xFF
                        val nRight = selected[idx + 1].toInt() and 0xFF
                        if (nTop == 0 || nBot == 0 || nLeft == 0 || nRight == 0) {
                            val neighborSum = nTop + nBot + nLeft + nRight
                            aaPass[idx] = ((neighborSum + 255) / 5).coerceIn(128, 255).toByte()
                        }
                    }
                }
            }
            System.arraycopy(aaPass, 0, selected, 0, total)
        }

        val localMask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val rb = localMask.rowBytes
        val neededBytes = localMask.byteCount
        val packedMask = if (rb == width && selected.size == neededBytes) {
            selected
        } else {
            ByteArray(neededBytes).also { dst ->
                val copyW = minOf(width, rb)
                for (y in 0 until height) {
                    val srcPos = y * width
                    val dstPos = y * rb
                    if (srcPos + copyW <= selected.size && dstPos + copyW <= dst.size) {
                        System.arraycopy(selected, srcPos, dst, dstPos, copyW)
                    }
                }
            }
        }
        try {
            val buffer = ByteBuffer.wrap(packedMask)
            buffer.rewind()
            localMask.copyPixelsFromBuffer(buffer)
        } catch (_: Throwable) {
            val argbBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val argbPixels = IntArray(width * height) { i ->
                val a = selected[i].toInt() and 0xFF
                (a shl 24) or 0x00FFFFFF
            }
            argbBmp.setPixels(argbPixels, 0, width, 0, 0, width, height)
            Canvas(localMask).drawBitmap(argbBmp, 0f, 0f, null)
            argbBmp.recycle()
        }

        val matrix = getLayerMatrix(layer, canvasWidth, canvasHeight)
        val canvas = Canvas(docMask)

        when (mode) {
            SelectionMode.REPLACE -> {
                docMask.eraseColor(Color.TRANSPARENT)
                canvas.drawBitmap(localMask, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            SelectionMode.ADD -> canvas.drawBitmap(localMask, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            SelectionMode.SUBTRACT -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                }
                canvas.drawBitmap(localMask, matrix, paint)
            }
            SelectionMode.INTERSECT -> {
                val docTemp = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ALPHA_8)
                Canvas(docTemp).drawBitmap(localMask, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
                canvas.drawBitmap(docTemp, 0f, 0f, paint)
                docTemp.recycle()
            }
        }
        localMask.recycle()
    }

    /**
     * Inverts selection mask (ALPHA_8)
     */
    fun invertMask(mask: Bitmap) {
        val (data, rowBytes) = readAlphaBytes(mask)
        for (y in 0 until mask.height) {
            val row = y * rowBytes
            for (x in 0 until mask.width) {
                val i = row + x
                data[i] = (255 - (data[i].toInt() and 0xFF)).toByte()
            }
        }
        writeAlphaBytes(mask, data)
    }

    /**
     * Copies selected pixels from source image to a new bitmap layer with smooth anti-aliased alpha blending
     */
    fun copySelectedToNewBitmap(source: Bitmap, mask: Bitmap): Bitmap? {
        if (source.width != mask.width || source.height != mask.height) return null
        val w = source.width
        val h = source.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val (maskBytes, maskRowBytes) = readAlphaBytes(mask)
        val src = IntArray(w)
        val dst = IntArray(w)
        var count = 0L
        for (y in 0 until h) {
            source.getPixels(src, 0, w, 0, y, w, 1)
            val maskRow = y * maskRowBytes
            for (x in 0 until w) {
                val a = maskBytes[maskRow + x].toInt() and 0xFF
                if (a > 0) {
                    val pixel = src[x]
                    val sourceAlpha = (pixel ushr 24) and 0xFF
                    dst[x] = ((sourceAlpha * a / 255) shl 24) or (pixel and 0x00FFFFFF)
                    count++
                } else {
                    dst[x] = 0
                }
            }
            out.setPixels(dst, 0, w, 0, y, w, 1)
        }
        if (count == 0L) { out.recycle(); return null }
        return out
    }

    // --- Selection Edge Operations ---

    /**
     * Softens selection mask edge using fast in-place 2-pass box blur Gaussian approximation
     * Drastically reduces memory allocation overhead.
     */
    private fun readAlphaBytes(mask: Bitmap): Pair<ByteArray, Int> {
        val w = mask.width
        val h = mask.height
        val rowBytes = mask.rowBytes
        val bytes = ByteArray(rowBytes * h)
        try {
            mask.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
        } catch (_: Throwable) {
            val pixels = IntArray(w)
            for (y in 0 until h) {
                mask.getPixels(pixels, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    bytes[y * rowBytes + x] = (pixels[x] ushr 24).toByte()
                }
            }
        }
        return bytes to rowBytes
    }

    private fun writeAlphaBytes(mask: Bitmap, bytes: ByteArray) {
        try {
            mask.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        } catch (_: Throwable) {
            val w = mask.width
            val h = mask.height
            val rowBytes = mask.rowBytes
            val pixels = IntArray(w)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val a = bytes[y * rowBytes + x].toInt() and 0xFF
                    pixels[x] = (a shl 24) or 0x00FFFFFF
                }
                mask.setPixels(pixels, 0, w, 0, y, w, 1)
            }
        }
    }

    /**
     * Softens selection edges directly in ALPHA_8 memory using a separable box blur.
     * Memory stays close to 2 bytes per mask pixel instead of multiple ARGB IntArrays.
     */
    fun featherMask(mask: Bitmap, radiusPx: Float) {
        if (radiusPx <= 0f || mask.width <= 0 || mask.height <= 0) return
        val width = mask.width
        val height = mask.height
        val (data, rowBytes) = readAlphaBytes(mask)
        val temp = ByteArray(data.size)
        val r = radiusPx.toInt().coerceIn(1, 100)

        for (y in 0 until height) {
            val row = y * rowBytes
            var sum = 0
            var left = 0
            var right = -1
            for (x in 0 until width) {
                val newLeft = (x - r).coerceAtLeast(0)
                val newRight = (x + r).coerceAtMost(width - 1)
                while (right < newRight) { right++; sum += data[row + right].toInt() and 0xFF }
                while (left < newLeft) { sum -= data[row + left].toInt() and 0xFF; left++ }
                val count = (newRight - newLeft + 1).coerceAtLeast(1)
                temp[row + x] = (sum / count).toByte()
            }
        }

        for (x in 0 until width) {
            var sum = 0
            var top = 0
            var bottom = -1
            for (y in 0 until height) {
                val newTop = (y - r).coerceAtLeast(0)
                val newBottom = (y + r).coerceAtMost(height - 1)
                while (bottom < newBottom) { bottom++; sum += temp[bottom * rowBytes + x].toInt() and 0xFF }
                while (top < newTop) { sum -= temp[top * rowBytes + x].toInt() and 0xFF; top++ }
                val count = (newBottom - newTop + 1).coerceAtLeast(1)
                data[y * rowBytes + x] = (sum / count).toByte()
            }
        }
        writeAlphaBytes(mask, data)
    }

    private fun morphMask(mask: Bitmap, radiusPx: Int, grow: Boolean) {
        if (radiusPx <= 0 || mask.width <= 0 || mask.height <= 0) return
        val width = mask.width
        val height = mask.height
        val (data, rowBytes) = readAlphaBytes(mask)
        val temp = ByteArray(data.size)
        val r = radiusPx.coerceIn(1, 50)
        val deque = IntArray(maxOf(width, height))

        // Horizontal sliding maximum/minimum.
        for (y in 0 until height) {
            val row = y * rowBytes
            var head = 0; var tail = 0; var addedThrough = -1
            for (x in 0 until width) {
                val windowStart = (x - r).coerceAtLeast(0)
                val windowEnd = (x + r).coerceAtMost(width - 1)
                while (addedThrough < windowEnd) {
                    addedThrough++
                    val v = data[row + addedThrough].toInt() and 0xFF
                    while (tail > head) {
                        val last = deque[tail - 1]
                        val lastV = data[row + last].toInt() and 0xFF
                        if (if (grow) lastV <= v else lastV >= v) tail-- else break
                    }
                    deque[tail++] = addedThrough
                }
                while (head < tail && deque[head] < windowStart) head++
                val best = data[row + deque[head]].toInt() and 0xFF
                temp[row + x] = best.toByte()
            }
        }

        // Vertical sliding maximum/minimum.
        for (x in 0 until width) {
            var head = 0; var tail = 0; var addedThrough = -1
            for (y in 0 until height) {
                val windowStart = (y - r).coerceAtLeast(0)
                val windowEnd = (y + r).coerceAtMost(height - 1)
                while (addedThrough < windowEnd) {
                    addedThrough++
                    val v = temp[addedThrough * rowBytes + x].toInt() and 0xFF
                    while (tail > head) {
                        val last = deque[tail - 1]
                        val lastV = temp[last * rowBytes + x].toInt() and 0xFF
                        if (if (grow) lastV <= v else lastV >= v) tail-- else break
                    }
                    deque[tail++] = addedThrough
                }
                while (head < tail && deque[head] < windowStart) head++
                data[y * rowBytes + x] = temp[deque[head] * rowBytes + x]
            }
        }
        writeAlphaBytes(mask, data)
    }

    /** Expands the selection using an O(width*height) ALPHA_8 morphology pass. */
    fun growMask(mask: Bitmap, radiusPx: Int) = morphMask(mask, radiusPx, grow = true)

    /** Contracts the selection using an O(width*height) ALPHA_8 morphology pass. */
    fun shrinkMask(mask: Bitmap, radiusPx: Int) = morphMask(mask, radiusPx, grow = false)

    fun smoothMask(mask: Bitmap, radiusPx: Int = 3) {
        featherMask(mask, radiusPx.toFloat())
        refineMaskEdges(mask, contrastFactor = 2.5f)
    }

    /**
     * Adjusts edge contrast and threshold for clean, crisp selections
     */
    fun refineMaskEdges(mask: Bitmap, contrastFactor: Float = 2.0f, thresholdShift: Float = 0f) {
        val (data, rowBytes) = readAlphaBytes(mask)
        for (y in 0 until mask.height) {
            val row = y * rowBytes
            for (x in 0 until mask.width) {
                val index = row + x
                val alpha = data[index].toInt() and 0xFF
                if (alpha in 1..254) {
                    val normalized = (alpha - 128f) / 128f
                    val adjusted = normalized * contrastFactor + (thresholdShift / 100f)
                    data[index] = ((adjusted * 128f + 128f).toInt().coerceIn(0, 255)).toByte()
                }
            }
        }
        writeAlphaBytes(mask, data)
    }

    /**
     * One-Tap Intelligent Subject Selection using color saliency and contrast priors.
     */
    fun selectSubject(source: Bitmap): Bitmap {
        return RefineEdgeEngine.selectSubject(source)
    }

    /**
     * Professional Alpha Matting & Hair Edge Refinement using Trimap & Color Guidance.
     */
    fun refineEdgesWithMatting(
        source: Bitmap,
        mask: Bitmap,
        config: RefineEdgeConfig
    ): RefineResult {
        return RefineEdgeEngine.refineEdges(source, mask, config)
    }

    /**
     * Magnetic Edge Snapping:
     * Evaluates local Sobel luminance gradient around (px, py) and snaps to the highest contrast contour.
     */
    fun snapPointToEdge(point: PointF, source: Bitmap, searchRadius: Int = 10): PointF {
        val px = point.x.toInt().coerceIn(searchRadius, source.width - 1 - searchRadius)
        val py = point.y.toInt().coerceIn(searchRadius, source.height - 1 - searchRadius)

        var maxGrad = 0f
        var bestX = point.x
        var bestY = point.y

        for (dy in -searchRadius..searchRadius) {
            val y = py + dy
            for (dx in -searchRadius..searchRadius) {
                val x = px + dx
                val pL = source.getPixel(x - 1, y)
                val pR = source.getPixel(x + 1, y)
                val pU = source.getPixel(x, y - 1)
                val pD = source.getPixel(x, y + 1)

                val lumL = 0.299f * Color.red(pL) + 0.587f * Color.green(pL) + 0.114f * Color.blue(pL)
                val lumR = 0.299f * Color.red(pR) + 0.587f * Color.green(pR) + 0.114f * Color.blue(pR)
                val lumU = 0.299f * Color.red(pU) + 0.587f * Color.green(pU) + 0.114f * Color.blue(pU)
                val lumD = 0.299f * Color.red(pD) + 0.587f * Color.green(pD) + 0.114f * Color.blue(pD)

                val gx = lumR - lumL
                val gy = lumD - lumU
                val grad = gx * gx + gy * gy

                // Weight by distance to initial touch point
                val distSq = (dx * dx + dy * dy).toFloat() + 1f
                val score = grad / distSq

                if (score > maxGrad && grad > 100f) {
                    maxGrad = score
                    bestX = x.toFloat()
                    bestY = y.toFloat()
                }
            }
        }
        return PointF(bestX, bestY)
    }
}
