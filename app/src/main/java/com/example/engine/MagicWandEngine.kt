package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.example.data.model.SelectionMode
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** User options of the magic wand. */
data class WandOptions(
    /** Side of the square sampled around the tap and averaged (1 = single pixel, 3, 5). */
    val sampleSize: Int = 3,
    /** Sample the whole visible image instead of only the layer under the finger. */
    val sampleAllLayers: Boolean = false,
    /** Follow smooth gradients (sky, shaded walls) instead of comparing only with the tapped colour. */
    val smartGradient: Boolean = false,
    /** Fill small holes and drop small specks caused by noise / JPEG artefacts. */
    val cleanNoise: Boolean = true,
    /** Soft, anti-aliased edge graded by colour distance (off = hard edge). */
    val softEdges: Boolean = true
)

/**
 * Colour data the wand works on: the image exactly as shown (adjustments, filters and LUTs
 * applied), in document space, converted once to CIE Lab so tolerance follows perceived colour.
 * Built once per wand session / until the image changes, so dragging only re-runs the fill.
 */
class WandSource internal constructor(
    val width: Int,
    val height: Int,
    /** Source pixels per document pixel. */
    val docScaleX: Float,
    val docScaleY: Float,
    /** Per pixel: alpha << 24 | round(L* x 2.55) << 16 | (a* + 128) << 8 | (b* + 128). */
    internal val lab: IntArray
)

object MagicWandEngine {
    private const val ALPHA_MIN = 12
    private const val L_INV_SQ = 1f / (2.55f * 2.55f)
    private const val F_LUT_SIZE = 4096

    private val srgbToLinear = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }
    private val fLut = FloatArray(F_LUT_SIZE + 1) { i ->
        val t = i.toFloat() / F_LUT_SIZE
        if (t > 0.008856f) Math.cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f
    }

    private fun labF(t: Float): Float {
        val x = t.coerceIn(0f, 1f) * F_LUT_SIZE
        val i = x.toInt().coerceAtMost(F_LUT_SIZE - 1)
        return fLut[i] + (fLut[i + 1] - fLut[i]) * (x - i)
    }

    /** Packs a non-premultiplied ARGB colour into the Lab layout used by [WandSource]. */
    internal fun packLab(argb: Int): Int {
        val a = argb ushr 24
        val r = srgbToLinear[(argb shr 16) and 0xFF]
        val g = srgbToLinear[(argb shr 8) and 0xFF]
        val b = srgbToLinear[argb and 0xFF]
        val fx = labF((0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f)
        val fy = labF(0.2126f * r + 0.7152f * g + 0.0722f * b)
        val fz = labF((0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f)
        val l = ((116f * fy - 16f) * 2.55f).roundToInt().coerceIn(0, 255)
        val la = (500f * (fx - fy) + 128f).roundToInt().coerceIn(0, 255)
        val lb = (200f * (fy - fz) + 128f).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (l shl 16) or (la shl 8) or lb
    }

    /**
     * Converts a document-space render of the image ([bitmap], any size with the document's
     * aspect ratio) into a [WandSource].
     */
    fun buildSource(bitmap: Bitmap, docWidth: Int, docHeight: Int, isCancelled: (() -> Boolean)? = null): WandSource {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            if (isCancelled?.invoke() == true) throw CancellationException("wand source cancelled")
            val row = y * w
            for (x in 0 until w) px[row + x] = packLab(px[row + x])
        }
        return WandSource(
            w, h,
            w.toFloat() / docWidth.coerceAtLeast(1).toFloat(),
            h.toFloat() / docHeight.coerceAtLeast(1).toFloat(),
            px
        )
    }

    /**
     * Maps the 1..100 tolerance to a CIE76 delta E. Tolerance 32 is about the colour step of
     * Photoshop's default; the curve grows faster at the top so high values reach far.
     */
    fun toleranceToDeltaE(tolerance: Int): Float {
        val t = tolerance.coerceIn(0, 100).toFloat()
        return 0.35f * t + 0.004f * t * t
    }

    /**
     * Magic wand into [docMask] (document-space ALPHA_8) from the document point ([docX], [docY]).
     * Returns false when nothing was selected.
     */
    fun select(
        source: WandSource,
        docMask: Bitmap,
        docX: Float,
        docY: Float,
        tolerance: Int,
        contiguous: Boolean,
        mode: SelectionMode,
        options: WandOptions = WandOptions(),
        isCancelled: (() -> Boolean)? = null
    ): Boolean {
        val out = computeMask(source, docX, docY, tolerance, contiguous, options, isCancelled) ?: return false
        if (isCancelled?.invoke() == true) throw CancellationException("wand cancelled")
        val local = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ALPHA_8)
        try {
            val rb = local.rowBytes
            val bytes = if (rb == source.width) out else ByteArray(rb * source.height).also { dst ->
                for (y in 0 until source.height) System.arraycopy(out, y * source.width, dst, y * rb, source.width)
            }
            SelectionEngine.writeAlphaBytes(local, bytes)
            val matrix = Matrix().apply {
                postScale(docMask.width.toFloat() / source.width, docMask.height.toFloat() / source.height)
            }
            val canvas = Canvas(docMask)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            when (mode) {
                SelectionMode.REPLACE -> {
                    docMask.eraseColor(Color.TRANSPARENT)
                    canvas.drawBitmap(local, matrix, paint)
                }
                SelectionMode.ADD -> canvas.drawBitmap(local, matrix, paint)
                SelectionMode.SUBTRACT -> {
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    canvas.drawBitmap(local, matrix, paint)
                }
                SelectionMode.INTERSECT -> {
                    val docTemp = Bitmap.createBitmap(docMask.width, docMask.height, Bitmap.Config.ALPHA_8)
                    try {
                        Canvas(docTemp).drawBitmap(local, matrix, paint)
                        canvas.drawBitmap(docTemp, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
                    } finally {
                        docTemp.recycle()
                    }
                }
            }
        } finally {
            local.recycle()
        }
        return true
    }

    /**
     * The wand result at source resolution (one alpha byte per source pixel), or null when the
     * tap is outside the image. Exposed for tests.
     */
    internal fun computeMask(
        source: WandSource,
        docX: Float,
        docY: Float,
        tolerance: Int,
        contiguous: Boolean,
        options: WandOptions,
        isCancelled: (() -> Boolean)?
    ): ByteArray? {
        val w = source.width
        val h = source.height
        val total = w * h
        if (total == 0) return null
        val lab = source.lab
        val sx = (docX * source.docScaleX).toInt()
        val sy = (docY * source.docScaleY).toInt()
        if (sx !in 0 until w || sy !in 0 until h) return null
        fun checkCancel() {
            if (isCancelled?.invoke() == true) throw CancellationException("wand cancelled")
        }

        val seedIdx = sy * w + sx
        val seedTransparent = (lab[seedIdx] ushr 24) < ALPHA_MIN
        // Seed colour = average of the opaque pixels in the sample square, so noise or a JPEG
        // block under the finger does not decide the whole selection.
        var sL = 0f; var sA = 0f; var sB = 0f
        if (!seedTransparent) {
            val r = (options.sampleSize.coerceIn(1, 11)) / 2
            var n = 0
            for (yy in (sy - r).coerceAtLeast(0)..(sy + r).coerceAtMost(h - 1)) {
                for (xx in (sx - r).coerceAtLeast(0)..(sx + r).coerceAtMost(w - 1)) {
                    val p = lab[yy * w + xx]
                    if ((p ushr 24) < ALPHA_MIN) continue
                    sL += ((p shr 16) and 0xFF); sA += ((p shr 8) and 0xFF); sB += (p and 0xFF)
                    n++
                }
            }
            sL /= n; sA /= n; sB /= n // n >= 1: the centre pixel is opaque
        }

        val selectAll = tolerance >= 100
        val tDelta = toleranceToDeltaE(tolerance)
        val tSq = tDelta * tDelta

        fun distSq(p: Int): Float {
            val dl = ((p shr 16) and 0xFF) - sL
            val da = ((p shr 8) and 0xFF) - sA
            val db = (p and 0xFF) - sB
            return dl * dl * L_INV_SQ + da * da + db * db
        }

        fun core(idx: Int): Boolean {
            val p = lab[idx]
            val a = p ushr 24
            if (seedTransparent) return a < ALPHA_MIN
            if (a < ALPHA_MIN) return false
            return selectAll || distSq(p) <= tSq
        }

        // state: 0 = out, 1 = selected core
        val state = ByteArray(total)
        if (!contiguous) {
            for (y in 0 until h) {
                checkCancel()
                val row = y * w
                for (x in 0 until w) if (core(row + x)) state[row + x] = 1
            }
        } else if (options.smartGradient && !seedTransparent && !selectAll) {
            // Region growing: a pixel joins when it is close to the tapped colour, or close to the
            // neighbour it is reached from while staying within a wider cap. Smooth gradients are
            // followed; a real edge (a big step) still stops the fill.
            val capSq = (tDelta * 6f + 60f).let { it * it }
            val step = max(2f, tDelta * 0.3f)
            val stepSq = step * step
            var stack = IntArray(4096)
            var sp = 0
            state[seedIdx] = 1
            stack[sp++] = seedIdx
            // seedIdx may be an outlier of an averaged sample; its neighbours are judged by distance
            // to the averaged colour, so the region still grows from the right colour.
            var iter = 0
            while (sp > 0) {
                if ((++iter and 0xFFFF) == 0) checkCancel()
                val p = stack[--sp]
                val px = p % w
                val pp = lab[p]
                for (k in 0 until 4) {
                    val q = when (k) {
                        0 -> if (px > 0) p - 1 else -1
                        1 -> if (px < w - 1) p + 1 else -1
                        2 -> if (p >= w) p - w else -1
                        else -> if (p + w < total) p + w else -1
                    }
                    if (q < 0 || state[q].toInt() != 0) continue
                    val pq = lab[q]
                    if ((pq ushr 24) < ALPHA_MIN) continue
                    val d = distSq(pq)
                    var join = d <= tSq
                    if (!join && d <= capSq) {
                        val dl = ((pq shr 16) and 0xFF) - ((pp shr 16) and 0xFF)
                        val da = ((pq shr 8) and 0xFF) - ((pp shr 8) and 0xFF)
                        val db = (pq and 0xFF) - (pp and 0xFF)
                        join = dl * dl * L_INV_SQ + da * da + db * db <= stepSq
                    }
                    if (join) {
                        state[q] = 1
                        if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
                        stack[sp++] = q
                    }
                }
            }
        } else {
            // Scanline flood fill; the seed stack grows as needed so no seed is ever dropped.
            // With an averaged sample the tapped pixel itself can be an outlier (a noise speck);
            // start from the nearest matching pixel of the sample square instead.
            var start = if (core(seedIdx)) seedIdx else -1
            if (start < 0) {
                val r = (options.sampleSize.coerceIn(1, 11)) / 2
                var best = Int.MAX_VALUE
                for (yy in (sy - r).coerceAtLeast(0)..(sy + r).coerceAtMost(h - 1)) {
                    for (xx in (sx - r).coerceAtLeast(0)..(sx + r).coerceAtMost(w - 1)) {
                        val d2 = (xx - sx) * (xx - sx) + (yy - sy) * (yy - sy)
                        if (d2 < best && core(yy * w + xx)) { best = d2; start = yy * w + xx }
                    }
                }
            }
            if (start < 0) return ByteArray(total)
            var stack = IntArray(maxOf(4096, w * 4))
            var sp = 0
            stack[sp++] = start
            var iter = 0
            fun push(i: Int) {
                if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
                stack[sp++] = i
            }
            while (sp > 0) {
                if ((++iter and 0x3FF) == 0) checkCancel()
                val packed = stack[--sp]
                val y = packed / w
                val x = packed - y * w
                val base = y * w
                if (state[base + x].toInt() != 0) continue
                var left = x
                while (left > 0 && state[base + left - 1].toInt() == 0 && core(base + left - 1)) left--
                var right = x
                while (right < w - 1 && state[base + right + 1].toInt() == 0 && core(base + right + 1)) right++
                for (xx in left..right) state[base + xx] = 1
                for (ny in intArrayOf(y - 1, y + 1)) {
                    if (ny < 0 || ny >= h) continue
                    val nb = ny * w
                    var inSpan = false
                    for (xx in left..right) {
                        val i = nb + xx
                        val match = state[i].toInt() == 0 && core(i)
                        if (match && !inSpan) { push(i); inSpan = true } else if (!match) inSpan = false
                    }
                }
            }
        }

        if (options.cleanNoise && !selectAll) {
            val minArea = (total * 0.00003f).toInt().coerceIn(6, 4000)
            // Holes inside the selection (not touching the image border).
            removeSmallComponents(state, w, h, target = 0, fill = 1, minArea = minArea, interiorOnly = true, protect = -1, checkCancel = { checkCancel() })
            // Isolated specks of the global colour range; the tapped region is always kept.
            if (!contiguous) {
                removeSmallComponents(state, w, h, target = 1, fill = 0, minArea = minArea, interiorOnly = false, protect = seedIdx, checkCancel = { checkCancel() })
            }
        }

        // Alpha: selected pixels follow the image's own transparency (soft cut-out edges stay soft).
        val out = ByteArray(total)
        for (i in 0 until total) {
            if (state[i].toInt() == 1) out[i] = if (seedTransparent) 0xFF.toByte() else (lab[i] ushr 24).toByte()
        }

        if (options.softEdges && !selectAll) {
            checkCancel()
            if (!seedTransparent) {
                // Graded fringe: pixels just beyond the tolerance get partial selection by distance,
                // instead of a hard on/off stair-step.
                val band = max(2.5f, tDelta * 0.35f)
                for (y in 0 until h) {
                    if ((y and 63) == 0) checkCancel()
                    val row = y * w
                    for (x in 0 until w) {
                        val i = row + x
                        if (state[i].toInt() != 0) continue
                        val p = lab[i]
                        val a = p ushr 24
                        if (a < ALPHA_MIN) continue
                        if (contiguous) {
                            val nearCore = (x > 0 && state[i - 1].toInt() == 1) || (x < w - 1 && state[i + 1].toInt() == 1) ||
                                    (y > 0 && state[i - w].toInt() == 1) || (y < h - 1 && state[i + w].toInt() == 1)
                            if (!nearCore) continue
                        }
                        val d = sqrt(distSq(p))
                        if (d <= tDelta || d >= tDelta + band) continue
                        val v = 1f - (d - tDelta) / band
                        out[i] = (v * a).roundToInt().coerceIn(0, 255).toByte()
                    }
                }
            }
            antiAliasBoundary(out, w, h)
        }
        return out
    }

    /**
     * Connected components (4-neighbour) of [target] pixels no larger than [minArea] become [fill].
     * [interiorOnly] skips components touching the image border; [protect] is never removed.
     * Memory stays bounded: only the first [minArea] + 1 pixels of a component are remembered.
     */
    private fun removeSmallComponents(
        state: ByteArray,
        w: Int,
        h: Int,
        target: Int,
        fill: Int,
        minArea: Int,
        interiorOnly: Boolean,
        protect: Int,
        checkCancel: () -> Unit
    ) {
        val total = w * h
        val mark = (target + 2).toByte()
        val t = target.toByte()
        var stack = IntArray(1024)
        val comp = IntArray(minArea + 1)
        for (start in 0 until total) {
            if ((start and 0xFFFF) == 0) checkCancel()
            if (state[start] != t) continue
            var sp = 0
            var count = 0
            var border = false
            var isProtected = false
            state[start] = mark
            stack[sp++] = start
            while (sp > 0) {
                val p = stack[--sp]
                if (count <= minArea) comp[count] = p
                count++
                if (p == protect) isProtected = true
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
                    if (q < 0 || state[q] != t) continue
                    state[q] = mark
                    if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
                    stack[sp++] = q
                }
            }
            if (count <= minArea && !isProtected && !(interiorOnly && border)) {
                for (i in 0 until count) state[comp[i]] = fill.toByte()
            }
        }
        for (i in 0 until total) if (state[i] == mark) state[i] = t
    }

    /** 3x3 average on edge pixels only, so stair-steps become smooth while the inside stays solid. */
    private fun antiAliasBoundary(out: ByteArray, w: Int, h: Int) {
        if (w < 3 || h < 3) return
        val src = out.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val c = src[i].toInt() and 0xFF
                val l = src[i - 1].toInt() and 0xFF
                val r = src[i + 1].toInt() and 0xFF
                val t = src[i - w].toInt() and 0xFF
                val b = src[i + w].toInt() and 0xFF
                if (c == l && c == r && c == t && c == b) continue
                var sum = 0
                for (dy in -w..w step w) {
                    sum += (src[i + dy - 1].toInt() and 0xFF) + (src[i + dy].toInt() and 0xFF) + (src[i + dy + 1].toInt() and 0xFF)
                }
                // Keep the core of thin features: never drop a selected pixel below half.
                val avg = sum / 9
                out[i] = (if (c >= 128) max(avg, (c + avg) / 2) else (c + avg) / 2).coerceIn(0, 255).toByte()
            }
        }
    }
}
