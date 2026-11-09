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
import kotlin.math.max
import kotlin.math.min

object SelectionEngine {

    /**
     * Computes layer transformation matrix M_layer mapping local bitmap pixels to document canvas space
     */
    /**
     * The layer's own shape change (free distort, then skew) in its bitmap's pixel space
     * ([w] x [h]); null when the layer is neither skewed nor distorted.
     */
    fun layerShapeMatrix(layer: Layer, w: Float, h: Float): Matrix? {
        val d = layer.distort?.takeIf { it.size == 8 }
        val hasSkew = kotlin.math.abs(layer.skewX) > 0.01f || kotlin.math.abs(layer.skewY) > 0.01f
        if (d == null && !hasSkew) return null
        val m = Matrix()
        if (d != null) {
            m.setPolyToPoly(
                floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0,
                floatArrayOf(d[0] * w, d[1] * h, d[2] * w, d[3] * h, d[4] * w, d[5] * h, d[6] * w, d[7] * h), 0,
                4
            )
        }
        if (hasSkew) {
            val kx = kotlin.math.tan(Math.toRadians(layer.skewX.toDouble())).toFloat()
            val ky = kotlin.math.tan(Math.toRadians(layer.skewY.toDouble())).toFloat()
            m.postSkew(kx, ky, w / 2f, h / 2f)
        }
        return m
    }

    fun getLayerMatrix(layer: Layer, canvasWidth: Int, canvasHeight: Int): Matrix {
        val bmp = layer.bitmap
        val bw = bmp?.width?.toFloat() ?: layer.intrinsicDocumentWidth()
        val bh = bmp?.height?.toFloat() ?: layer.intrinsicDocumentHeight()
        val matrix = Matrix()
        layerShapeMatrix(layer, bw, bh)?.let { matrix.set(it) }
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
        if (mask.isRecycled) return false
        val (data, rowBytes) = readAlphaBytes(mask)
        val w = mask.width
        for (y in 0 until mask.height) {
            val row = y * rowBytes
            for (x in 0 until w) if (data[row + x].toInt() != 0) return true
        }
        return false
    }

    /** Tight bounding box of all non-zero mask pixels, or null when nothing is selected. */
    fun selectionBounds(mask: Bitmap): android.graphics.Rect? {
        if (mask.isRecycled) return null
        val (data, rowBytes) = readAlphaBytes(mask)
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        for (y in 0 until mask.height) {
            val row = y * rowBytes
            for (x in 0 until mask.width) {
                if (data[row + x].toInt() != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX < 0) null else android.graphics.Rect(minX, minY, maxX + 1, maxY + 1)
    }

    /** Alpha values packed row by row (width * height bytes), independent of the bitmap's row padding. */
    fun alphaBytesPacked(mask: Bitmap): ByteArray {
        val (data, rowBytes) = readAlphaBytes(mask)
        val w = mask.width
        if (rowBytes == w) return if (data.size == w * mask.height) data else data.copyOf(w * mask.height)
        val out = ByteArray(w * mask.height)
        for (y in 0 until mask.height) System.arraycopy(data, y * rowBytes, out, y * w, w)
        return out
    }

    /** Builds a mutable ALPHA_8 mask from [alphaBytesPacked] output. */
    fun alphaMaskFromPacked(bytes: ByteArray, width: Int, height: Int): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val rowBytes = mask.rowBytes
        val data = if (rowBytes == width) {
            if (bytes.size == mask.byteCount) bytes else bytes.copyOf(mask.byteCount)
        } else {
            ByteArray(rowBytes * height).also { dst ->
                for (y in 0 until height) System.arraycopy(bytes, y * width, dst, y * rowBytes, width)
            }
        }
        writeAlphaBytes(mask, data)
        return mask
    }

    /** Moves the selection outline by (dx, dy) document pixels without touching image pixels. */
    fun translateMask(mask: Bitmap, dx: Float, dy: Float): Bitmap {
        val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8)
        Canvas(out).drawBitmap(mask, dx, dy, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
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
     * Quick Selection from a plain bitmap (document image, possibly scaled). Builds the Lab / edge
     * source and runs [QuickSelectEngine.select]: foreground + background colour models, colour
     * edge-aware geodesic growth up to the object's outline, soft anti-aliased edges.
     * Live strokes build the source once and call [QuickSelectEngine.select] directly.
     */
    fun quickSelection(
        mask: Bitmap,
        points: List<PointF>,
        brushRadius: Float,
        sourceBitmap: Bitmap,
        mode: SelectionMode = SelectionMode.ADD,
        tolerance: Int = 35,
        autoEnhance: Boolean = true,
        edgeSensitivity: Int = 60
    ): Boolean {
        if (points.isEmpty() || mask.width <= 0 || mask.height <= 0) return false
        val source = QuickSelectEngine.buildSource(sourceBitmap, mask.width, mask.height)
        return QuickSelectEngine.select(
            source, mask, points, brushRadius, mode,
            QuickSelectOptions(tolerance = tolerance, edgeSensitivity = edgeSensitivity, autoEnhance = autoEnhance)
        )
    }

    // The magic wand lives in MagicWandEngine (Lab colour, image as shown, soft edges).

    // ---------------------------------------------------------------------------------------------
    // Professional selection modifiers
    // ---------------------------------------------------------------------------------------------

    /** One box-blur pass (horizontal + vertical) of an alpha byte buffer with row stride [rb]. */
    private fun boxBlurPass(data: ByteArray, w: Int, h: Int, rb: Int, r: Int) {
        val temp = ByteArray(data.size)
        for (y in 0 until h) {
            val row = y * rb
            var sum = 0
            var left = 0
            var right = -1
            for (x in 0 until w) {
                val nl = (x - r).coerceAtLeast(0)
                val nr = (x + r).coerceAtMost(w - 1)
                while (right < nr) { right++; sum += data[row + right].toInt() and 0xFF }
                while (left < nl) { sum -= data[row + left].toInt() and 0xFF; left++ }
                temp[row + x] = (sum / (nr - nl + 1)).toByte()
            }
        }
        for (x in 0 until w) {
            var sum = 0
            var top = 0
            var bottom = -1
            for (y in 0 until h) {
                val nt = (y - r).coerceAtLeast(0)
                val nb = (y + r).coerceAtMost(h - 1)
                while (bottom < nb) { bottom++; sum += temp[bottom * rb + x].toInt() and 0xFF }
                while (top < nt) { sum -= temp[top * rb + x].toInt() and 0xFF; top++ }
                data[y * rb + x] = (sum / (nb - nt + 1)).toByte()
            }
        }
    }

    /** Gaussian-like feather: three box passes (smooth, no visible ramp corners). */
    fun gaussianFeather(mask: Bitmap, radiusPx: Float) {
        if (radiusPx < 0.5f || mask.width <= 0 || mask.height <= 0) return
        val (data, rb) = readAlphaBytes(mask)
        val r = kotlin.math.max(1, kotlin.math.round(radiusPx / 2f).toInt()).coerceAtMost(200)
        repeat(3) { boxBlurPass(data, mask.width, mask.height, rb, r) }
        writeAlphaBytes(mask, data)
    }

    /**
     * Squared Euclidean distance (Felzenszwalb & Huttenlocher) from every pixel to the nearest
     * pixel where [isFeature] is true; [out] receives the squared distances (capped).
     */
    private fun squaredDistance(w: Int, h: Int, isFeature: (Int) -> Boolean, out: FloatArray) {
        val inf = 1e12f
        val n = kotlin.math.max(w, h)
        val f = FloatArray(n)
        val d = FloatArray(n)
        val v = IntArray(n)
        val z = FloatArray(n + 1)
        fun transform(len: Int) {
            var k = 0
            v[0] = 0
            z[0] = -inf
            z[1] = inf
            for (q in 1 until len) {
                var s = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) / (2f * q - 2f * v[k])
                while (s <= z[k]) {
                    k--
                    s = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) / (2f * q - 2f * v[k])
                }
                k++
                v[k] = q
                z[k] = s
                z[k + 1] = inf
            }
            k = 0
            for (q in 0 until len) {
                while (z[k + 1] < q) k++
                val dq = q - v[k]
                d[q] = dq * dq + f[v[k]]
            }
        }
        // Columns.
        for (x in 0 until w) {
            for (y in 0 until h) f[y] = if (isFeature(y * w + x)) 0f else inf
            transform(h)
            for (y in 0 until h) out[y * w + x] = d[y]
        }
        // Rows.
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) f[x] = out[row + x]
            transform(w)
            for (x in 0 until w) out[row + x] = d[x]
        }
    }

    /**
     * Moves the selection edge by [px] (positive = grow, negative = shrink) using true Euclidean
     * distance, so shapes grow evenly in every direction (round, not square, corners). The new
     * edge is anti-aliased.
     */
    fun shiftEdge(mask: Bitmap, px: Float) {
        if (kotlin.math.abs(px) < 0.25f || mask.width <= 0 || mask.height <= 0) return
        val w = mask.width
        val h = mask.height
        val (data, rb) = readAlphaBytes(mask)
        val inside = BooleanArray(w * h) { (data[(it / w) * rb + it % w].toInt() and 0xFF) >= 128 }
        val dist = FloatArray(w * h)
        if (px > 0f) {
            // Distance from outside pixels to the selection.
            squaredDistance(w, h, { inside[it] }, dist)
            for (i in 0 until w * h) {
                if (inside[i]) continue
                val dd = kotlin.math.sqrt(dist[i]) - 0.5f
                val a = ((px - dd) + 0.5f).coerceIn(0f, 1f)
                val idx = (i / w) * rb + i % w
                val old = data[idx].toInt() and 0xFF
                data[idx] = kotlin.math.max(old, (a * 255f).toInt()).toByte()
            }
        } else {
            val s = -px
            squaredDistance(w, h, { !inside[it] }, dist)
            for (i in 0 until w * h) {
                val idx = (i / w) * rb + i % w
                if (!inside[i]) { data[idx] = 0; continue }
                val dd = kotlin.math.sqrt(dist[i]) - 0.5f
                val a = ((dd - s) + 0.5f).coerceIn(0f, 1f)
                val old = data[idx].toInt() and 0xFF
                data[idx] = kotlin.math.min(old, (a * 255f).toInt()).toByte()
            }
        }
        writeAlphaBytes(mask, data)
    }

    /**
     * Removes selected specks smaller than [minArea] pixels and fills unselected holes of that size
     * inside the selection (4-connected, on the 50% outline). Soft edges elsewhere are kept.
     */
    fun cleanupMask(mask: Bitmap, minArea: Int) {
        val w = mask.width
        val h = mask.height
        if (w <= 0 || h <= 0 || minArea <= 0) return
        val (data, rb) = readAlphaBytes(mask)
        val n = w * h
        fun on(i: Int) = (data[(i / w) * rb + i % w].toInt() and 0xFF) >= 128
        val visited = BooleanArray(n)
        var stack = IntArray(1024)
        val comp = IntArray(minArea + 1)
        for (target in booleanArrayOf(true, false)) {
            java.util.Arrays.fill(visited, false)
            for (start in 0 until n) {
                if (visited[start] || on(start) != target) continue
                var sp = 0
                var count = 0
                var border = false
                visited[start] = true
                stack[sp++] = start
                while (sp > 0) {
                    val p = stack[--sp]
                    if (count <= minArea) comp[count] = p
                    count++
                    val x = p % w
                    val y = p / w
                    if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border = true
                    for (k in 0 until 4) {
                        val q = when (k) {
                            0 -> if (x > 0) p - 1 else -1
                            1 -> if (x < w - 1) p + 1 else -1
                            2 -> if (y > 0) p - w else -1
                            else -> if (y < h - 1) p + w else -1
                        }
                        if (q < 0 || visited[q] || on(q) != target) continue
                        visited[q] = true
                        if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
                        stack[sp++] = q
                    }
                }
                // Islands anywhere; holes only when enclosed (not touching the image border).
                if (count <= minArea && (target || !border)) {
                    val value: Byte = if (target) 0 else 0xFF.toByte()
                    for (i in 0 until count) {
                        val p = comp[i]
                        data[(p / w) * rb + p % w] = value
                    }
                }
            }
        }
        writeAlphaBytes(mask, data)
    }

    /**
     * Smooth: removes jagged edges, small specks and holes without shrinking the selection
     * (blur, then re-sharpen around the 50% level, then clean up).
     */
    fun smoothSelection(mask: Bitmap, radiusPx: Float) {
        if (radiusPx < 0.5f) return
        gaussianFeather(mask, radiusPx * 2f)
        refineMaskEdges(mask, contrastFactor = (2f + radiusPx / 4f).coerceAtMost(8f))
        val r = radiusPx.toInt().coerceAtLeast(1)
        cleanupMask(mask, (r * r * 3).coerceAtLeast(9))
    }

    /** A band of [widthPx] centred on the selection edge (Select > Modify > Border). */
    fun borderMask(mask: Bitmap, widthPx: Float) {
        if (widthPx < 1f) return
        val grown = mask.copy(Bitmap.Config.ALPHA_8, true)
        val shrunk = mask.copy(Bitmap.Config.ALPHA_8, true)
        try {
            shiftEdge(grown, widthPx / 2f)
            shiftEdge(shrunk, -widthPx / 2f)
            val (g, rbg) = readAlphaBytes(grown)
            val (s, rbs) = readAlphaBytes(shrunk)
            val (out, rb) = readAlphaBytes(mask)
            for (y in 0 until mask.height) for (x in 0 until mask.width) {
                val a = g[y * rbg + x].toInt() and 0xFF
                val b = 255 - (s[y * rbs + x].toInt() and 0xFF)
                out[y * rb + x] = kotlin.math.min(a, b).toByte()
            }
            writeAlphaBytes(mask, out)
        } finally {
            grown.recycle()
            shrunk.recycle()
        }
    }

    /**
     * The combined refine step used by the live refine sliders, in Photoshop's order:
     * shift edge -> smooth -> feather -> contrast.
     */
    fun applyRefine(mask: Bitmap, shiftPx: Float, smoothPx: Float, featherPx: Float, contrast: Float) {
        if (kotlin.math.abs(shiftPx) >= 0.25f) shiftEdge(mask, shiftPx)
        if (smoothPx >= 0.5f) smoothSelection(mask, smoothPx)
        if (featherPx >= 0.5f) gaussianFeather(mask, featherPx)
        if (contrast >= 0.5f) refineMaskEdges(mask, contrastFactor = 1f + contrast / 12.5f)
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
    internal fun readAlphaBytes(mask: Bitmap): Pair<ByteArray, Int> {
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

    internal fun writeAlphaBytes(mask: Bitmap, bytes: ByteArray) {
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
    fun featherMask(mask: Bitmap, radiusPx: Float) = gaussianFeather(mask, radiusPx)

    @Suppress("unused")
    private fun boxFeatherSinglePass(mask: Bitmap, radiusPx: Float) {
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
    fun growMask(mask: Bitmap, radiusPx: Int) = shiftEdge(mask, radiusPx.toFloat())

    /** Contracts the selection using an O(width*height) ALPHA_8 morphology pass. */
    fun shrinkMask(mask: Bitmap, radiusPx: Int) = shiftEdge(mask, -radiusPx.toFloat())

    fun smoothMask(mask: Bitmap, radiusPx: Int = 3) = smoothSelection(mask, radiusPx.toFloat())

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
        // The search window plus the 1px gradient border must fit inside the image; otherwise
        // coerceIn below would receive an empty range and throw.
        if (source.isRecycled || source.width < 2 * searchRadius + 3 || source.height < 2 * searchRadius + 3) return point
        // +1 margin: the gradient below samples x±1 / y±1 around every candidate.
        val px = point.x.toInt().coerceIn(searchRadius + 1, source.width - 2 - searchRadius)
        val py = point.y.toInt().coerceIn(searchRadius + 1, source.height - 2 - searchRadius)

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
