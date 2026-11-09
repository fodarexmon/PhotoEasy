package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.example.data.model.SelectionMode
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** User options of the Quick Selection brush (independent of the magic wand). */
data class QuickSelectOptions(
    /** How different a colour may be from the brushed colours and still be selected (5..100). */
    val tolerance: Int = 35,
    /** How strongly colour edges stop the growth (0 = ignores edges, 100 = stops at faint edges). */
    val edgeSensitivity: Int = 60,
    /** Fill small holes and snap a soft, anti-aliased edge to the object outline. */
    val autoEnhance: Boolean = true
)

/**
 * Image data the Quick Selection works on: the image as shown, in document space, converted once
 * to CIE Lab, plus a colour (not only brightness) edge map. Built once per stroke.
 */
class QuickSelectSource internal constructor(
    val width: Int,
    val height: Int,
    /** Source pixels per document pixel. */
    val docScaleX: Float,
    val docScaleY: Float,
    /** Per pixel: alpha << 24 | round(L* x 2.55) << 16 | (a* + 128) << 8 | (b* + 128). */
    internal val lab: IntArray,
    /** Colour edge strength per pixel, in delta E (Sobel on L*, a*, b*), 0..255. */
    internal val edge: ByteArray
)

/**
 * Professional Quick Selection (like Photoshop's Quick Selection tool):
 *
 * - Foreground colour model: k-means clusters (Lab) of the colours under the brush centre, so
 *   multi-coloured objects (a striped shirt, a face with hair) are grown as one object.
 * - Background colour model: clusters of the colours around the search area that do not look
 *   like the object; a pixel is only taken when it is closer to the object than to the background.
 * - Edge-aware growth: a geodesic (Dijkstra) expansion from the stroke whose cost is the colour edge
 *   strength crossed, so growth flows freely through smooth areas and gradients but stops at the
 *   object's outline. Edges between two object colours are internal and cost little.
 * - The growth reaches the object's boundary even far beyond the brush, so one short stroke can
 *   select a whole object. The same intelligence applies when subtracting.
 * - Soft, anti-aliased output: holes are filled and the edge is snapped with an edge-aware
 *   (guided) filter, then drawn into the document mask with bilinear filtering.
 */
object QuickSelectEngine {
    private const val ALPHA_MIN = 12
    private const val COST_SCALE = 4f

    /** Builds the Lab image + colour edge map from [bitmap] (document image, possibly scaled). */
    fun buildSource(bitmap: Bitmap, docWidth: Int, docHeight: Int, isCancelled: (() -> Boolean)? = null): QuickSelectSource {
        val w = bitmap.width
        val h = bitmap.height
        val lab = IntArray(w * h)
        bitmap.getPixels(lab, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            if (isCancelled?.invoke() == true) throw CancellationException("quick selection source cancelled")
            val row = y * w
            for (x in 0 until w) lab[row + x] = MagicWandEngine.packLab(lab[row + x])
        }
        val edge = ByteArray(w * h)
        val inv255 = 1f / 2.55f
        for (y in 0 until h) {
            if ((y and 63) == 0 && isCancelled?.invoke() == true) throw CancellationException("quick selection source cancelled")
            val y0 = max(0, y - 1) * w
            val y1 = y * w
            val y2 = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                val x0 = max(0, x - 1)
                val x2 = min(w - 1, x + 1)
                var sum = 0f
                for (ch in 0 until 3) {
                    val shift = 16 - ch * 8
                    fun v(i: Int): Float {
                        val c = (lab[i] shr shift) and 0xFF
                        return if (ch == 0) c * inv255 else c.toFloat()
                    }
                    val gx = (v(y0 + x2) + 2f * v(y1 + x2) + v(y2 + x2)) - (v(y0 + x0) + 2f * v(y1 + x0) + v(y2 + x0))
                    val gy = (v(y2 + x0) + 2f * v(y2 + x) + v(y2 + x2)) - (v(y0 + x0) + 2f * v(y0 + x) + v(y0 + x2))
                    sum += gx * gx + gy * gy
                }
                // Sobel weights sum to 4 per side: /4 makes a step edge read as its delta E.
                var e = sqrt(sum) / 4f
                // Transparent pixels border an edge as strong as possible.
                val a = lab[y1 + x] ushr 24
                if (a < ALPHA_MIN) e = 255f
                edge[y1 + x] = e.coerceIn(0f, 255f).toInt().toByte()
            }
        }
        return QuickSelectSource(
            w, h,
            w.toFloat() / docWidth.coerceAtLeast(1).toFloat(),
            h.toFloat() / docHeight.coerceAtLeast(1).toFloat(),
            lab, edge
        )
    }

    /** Colour gate in delta E for a 5..100 tolerance. */
    fun toleranceToDeltaE(tolerance: Int): Float {
        val t = tolerance.coerceIn(5, 100).toFloat()
        return 8f + 0.5f * t + 0.0015f * t * t
    }

    private fun lOf(p: Int) = ((p shr 16) and 0xFF) / 2.55f
    private fun aOf(p: Int) = (((p shr 8) and 0xFF) - 128).toFloat()
    private fun bOf(p: Int) = ((p and 0xFF) - 128).toFloat()

    /** Small k-means in Lab. Returns centres [L0,a0,b0, L1,a1,b1, ...]; tiny clusters are dropped. */
    internal fun labClusters(samples: IntArray, count: Int, maxClusters: Int): FloatArray {
        if (count <= 0) return FloatArray(0)
        val k = min(maxClusters, count)
        val c = FloatArray(k * 3)
        // Farthest-point initialisation (deterministic): spreads the centres over distinct colours.
        c[0] = lOf(samples[0]); c[1] = aOf(samples[0]); c[2] = bOf(samples[0])
        val best = FloatArray(count) { Float.MAX_VALUE }
        for (ci in 1 until k) {
            var far = 0
            var farD = -1f
            for (i in 0 until count) {
                val p = samples[i]
                val dl = lOf(p) - c[(ci - 1) * 3]; val da = aOf(p) - c[(ci - 1) * 3 + 1]; val db = bOf(p) - c[(ci - 1) * 3 + 2]
                val d = dl * dl + da * da + db * db
                if (d < best[i]) best[i] = d
                if (best[i] > farD) { farD = best[i]; far = i }
            }
            val p = samples[far]
            c[ci * 3] = lOf(p); c[ci * 3 + 1] = aOf(p); c[ci * 3 + 2] = bOf(p)
        }
        val sums = DoubleArray(k * 3)
        val counts = IntArray(k)
        repeat(6) {
            java.util.Arrays.fill(sums, 0.0)
            java.util.Arrays.fill(counts, 0)
            for (i in 0 until count) {
                val p = samples[i]
                val l = lOf(p); val a = aOf(p); val b = bOf(p)
                var bi = 0
                var bd = Float.MAX_VALUE
                for (ci in 0 until k) {
                    val dl = l - c[ci * 3]; val da = a - c[ci * 3 + 1]; val db = b - c[ci * 3 + 2]
                    val d = dl * dl + da * da + db * db
                    if (d < bd) { bd = d; bi = ci }
                }
                sums[bi * 3] += l.toDouble(); sums[bi * 3 + 1] += a.toDouble(); sums[bi * 3 + 2] += b.toDouble()
                counts[bi]++
            }
            for (ci in 0 until k) if (counts[ci] > 0) {
                c[ci * 3] = (sums[ci * 3] / counts[ci]).toFloat()
                c[ci * 3 + 1] = (sums[ci * 3 + 1] / counts[ci]).toFloat()
                c[ci * 3 + 2] = (sums[ci * 3 + 2] / counts[ci]).toFloat()
            }
        }
        val minCount = max(1, (count * 0.02f).toInt())
        val kept = ArrayList<Float>()
        for (ci in 0 until k) if (counts[ci] >= minCount) {
            kept.add(c[ci * 3]); kept.add(c[ci * 3 + 1]); kept.add(c[ci * 3 + 2])
        }
        return if (kept.isEmpty()) c else kept.toFloatArray()
    }

    /** Distance (delta E) from packed Lab [p] to the nearest centre, or MAX when there are none. */
    private fun nearest(p: Int, centres: FloatArray): Float {
        if (centres.isEmpty()) return Float.MAX_VALUE
        val l = lOf(p); val a = aOf(p); val b = bOf(p)
        var bd = Float.MAX_VALUE
        var i = 0
        while (i < centres.size) {
            val dl = l - centres[i]; val da = a - centres[i + 1]; val db = b - centres[i + 2]
            val d = dl * dl + da * da + db * db
            if (d < bd) bd = d
            i += 3
        }
        return sqrt(bd)
    }

    /**
     * Quick Selection into [docMask] (document-space ALPHA_8).
     *
     * @param seedPoints the stroke points to grow from (document space).
     * @param modelPoints the points whose colours define the object (usually the whole stroke so far,
     *   so a live stroke keeps a consistent colour model); defaults to [seedPoints].
     * @param brushRadius brush radius in document pixels.
     * @return true when something was selected.
     */
    fun select(
        source: QuickSelectSource,
        docMask: Bitmap,
        seedPoints: List<PointF>,
        brushRadius: Float,
        mode: SelectionMode,
        options: QuickSelectOptions = QuickSelectOptions(),
        modelPoints: List<PointF> = seedPoints,
        isCancelled: (() -> Boolean)? = null
    ): Boolean {
        if (seedPoints.isEmpty()) return false
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0 || docMask.width <= 0 || docMask.height <= 0) return false
        val sx = source.docScaleX
        val sy = source.docScaleY
        val rS = max(1f, brushRadius * (sx + sy) / 2f)
        val lab = source.lab
        val edge = source.edge

        // --- Search area: the stroke plus a generous reach, so growth can reach the object's outline.
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in seedPoints) {
            val x = p.x * sx; val y = p.y * sy
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val reach = max(rS * 6f, 0.35f * max(w, h)).coerceAtMost(max(w, h).toFloat())
        val bx0 = (minX - reach).toInt().coerceIn(0, w - 1)
        val by0 = (minY - reach).toInt().coerceIn(0, h - 1)
        val bx1 = (maxX + reach).toInt().coerceIn(0, w - 1)
        val by1 = (maxY + reach).toInt().coerceIn(0, h - 1)
        if (maxX + rS < 0 || maxY + rS < 0 || minX - rS > w - 1 || minY - rS > h - 1) return false
        val lw = bx1 - bx0 + 1
        val lh = by1 - by0 + 1
        val n = lw * lh

        // --- Foreground model: colours under the brush centre (inner 60%) of the model points.
        val core = max(0.75f, rS * 0.6f)
        val fgSamples = IntArray(4096)
        var fgCount = 0
        var fgOpaque = 0
        run {
            val pts = if (modelPoints.isEmpty()) seedPoints else modelPoints
            val area = pts.size * Math.PI.toFloat() * core * core
            val step = max(1, kotlin.math.ceil(sqrt(area / fgSamples.size)).toInt())
            // Thin the points too when the stroke is long (they overlap heavily).
            val pStep = max(1, (pts.size * Math.PI * core * core / (fgSamples.size * step * step)).toInt())
            var seen = 0
            var idx = 0
            while (idx < pts.size) {
                val p = pts[idx]
                val cx = p.x * sx; val cy = p.y * sy
                val r = core.toInt()
                var dy = -r
                while (dy <= r) {
                    var dx = -r
                    while (dx <= r) {
                        if (dx * dx + dy * dy <= core * core) {
                            val x = (cx + dx).toInt(); val y = (cy + dy).toInt()
                            if (x in 0 until w && y in 0 until h) {
                                val v = lab[y * w + x]
                                if ((v ushr 24) >= ALPHA_MIN) fgOpaque++
                                if (fgCount < fgSamples.size) fgSamples[fgCount++] = v
                                else {
                                    // Reservoir sampling keeps an even spread of a long stroke.
                                    val j = ((((seen.toLong() * 2654435761L) ushr 7).toInt()) and 0x7FFFFFFF) % (seen + 1)
                                    if (j < fgSamples.size) fgSamples[j] = v
                                }
                                seen++
                            }
                        }
                        dx += step
                    }
                    dy += step
                }
                idx += pStep
            }
        }
        if (fgCount == 0) return false
        // Seeds on transparent pixels select transparent pixels too (e.g. empty layer areas).
        val needOpaque = fgOpaque * 2 >= fgCount
        val fgOnly = if (needOpaque) {
            var k = 0
            for (i in 0 until fgCount) if ((fgSamples[i] ushr 24) >= ALPHA_MIN) fgSamples[k++] = fgSamples[i]
            k
        } else fgCount
        val fg = labClusters(fgSamples, max(1, fgOnly), maxClusters = 6)

        val tolE = toleranceToDeltaE(options.tolerance)

        // --- Background model: the ring around the search area, minus colours that look like the
        // object (the object may touch the ring) and, when adding, minus what is already selected.
        val bgSamples = IntArray(2048)
        var bgCount = 0
        run {
            val perimeter = 2 * (lw + lh)
            val step = max(1, perimeter / 1600)
            val inset = max(1, (rS * 0.5f).toInt())
            val rx0 = (bx0 + inset).coerceAtMost(bx1); val rx1 = (bx1 - inset).coerceAtLeast(bx0)
            val ry0 = (by0 + inset).coerceAtMost(by1); val ry1 = (by1 - inset).coerceAtLeast(by0)
            fun consider(x: Int, y: Int) {
                if (bgCount >= bgSamples.size) return
                val v = lab[y * w + x]
                if (needOpaque && (v ushr 24) < ALPHA_MIN) return
                if (nearest(v, fg) < tolE * 0.5f) return
                if (mode == SelectionMode.ADD) {
                    val mx = (x / sx).toInt().coerceIn(0, docMask.width - 1)
                    val my = (y / sy).toInt().coerceIn(0, docMask.height - 1)
                    if ((docMask.getPixel(mx, my) ushr 24) >= 128) return
                }
                bgSamples[bgCount++] = v
            }
            var x = rx0
            while (x <= rx1) { consider(x, ry0); consider(x, ry1); x += step }
            var y = ry0
            while (y <= ry1) { consider(rx0, y); consider(rx1, y); y += step }
        }
        val bg = if (bgCount >= 12) labClusters(bgSamples, bgCount, maxClusters = 8) else FloatArray(0)

        // --- Growth parameters.
        val sens = options.edgeSensitivity.coerceIn(0, 100) / 100f
        val edgeFloor = 3f + (1f - sens) * 22f          // edges below this are texture / noise
        val edgeGain = 0.15f + sens * 1.6f              // how expensive crossing an edge is
        val budget = ((6f + options.tolerance.coerceIn(5, 100) * 0.45f) * COST_SCALE).toInt()

        // Per pixel: -2 unknown, -1 blocked, else the colour-drift cost of entering it.
        val gate = IntArray(n) { -2 }
        val inner = BooleanArray(n) // colour is clearly one of the object colours
        val prob = FloatArray(n)    // foreground probability (guides the soft edge)
        fun evaluate(li: Int): Int {
            val c = gate[li]
            if (c != -2) return c
            val gi = (by0 + li / lw) * w + (bx0 + li % lw)
            val v = lab[gi]
            if (needOpaque && (v ushr 24) < ALPHA_MIN) { gate[li] = -1; return -1 }
            val dF = nearest(v, fg)
            val dB = nearest(v, bg)
            prob[li] = if (bg.isEmpty()) (1f - dF / (tolE * 2f)).coerceIn(0f, 1f) else (dB / (dF + dB + 1e-3f))
            inner[li] = dF < tolE * 0.35f
            if (dF > tolE || (bg.isNotEmpty() && dF > dB * 1.1f + 2f)) { gate[li] = -1; return -1 }
            // Slow drift away from the object colours costs a little.
            val r = (max(0f, dF - tolE * 0.5f) * 0.12f * COST_SCALE).toInt()
            gate[li] = r
            return r
        }
        /**
         * Cost of stepping p -> q: the colour edge crossed. The pair difference catches thin lines,
         * the smaller Sobel response of the two catches soft edges and is charged only once (the
         * pixel next to an edge on the object side is free). Edges between two object colours
         * (stripes, a face and its hair) are internal and free.
         */
        fun stepCost(pl: Int, ql: Int): Int {
            val drift = evaluate(ql)
            if (drift < 0) return -1
            val pg = (by0 + pl / lw) * w + (bx0 + pl % lw)
            val qg = (by0 + ql / lw) * w + (bx0 + ql % lw)
            val a = lab[pg]; val b = lab[qg]
            val dl = lOf(a) - lOf(b); val da = aOf(a) - aOf(b); val db = bOf(a) - bOf(b)
            val pair = sqrt(dl * dl + da * da + db * db)
            val sob = min(edge[pg].toInt() and 0xFF, edge[qg].toInt() and 0xFF).toFloat()
            var cost = max(0f, max(pair, sob) - edgeFloor) * edgeGain
            // Both sides are object colours: an internal edge (stripes, pattern) is free.
            if (inner[pl] && inner[ql]) cost = 0f
            return ((cost * COST_SCALE).toInt() + drift).coerceIn(0, budget + 1)
        }

        // --- Dial's algorithm (bucket queue) Dijkstra from the brush cores.
        val dist = IntArray(n) { Int.MAX_VALUE }
        val bucketHead = IntArray(budget + 1) { -1 }
        var poolPix = IntArray(max(1024, n / 4))
        var poolNext = IntArray(poolPix.size)
        var poolSize = 0
        fun push(li: Int, d: Int) {
            if (poolSize == poolPix.size) {
                val ns = poolPix.size * 2
                poolPix = poolPix.copyOf(ns)
                poolNext = poolNext.copyOf(ns)
            }
            poolPix[poolSize] = li
            poolNext[poolSize] = bucketHead[d]
            bucketHead[d] = poolSize
            poolSize++
        }
        val selected = ByteArray(n)
        for (p in seedPoints) {
            val cx = p.x * sx; val cy = p.y * sy
            val r = core.toInt()
            for (dy in -r..r) for (dx in -r..r) {
                if (dx * dx + dy * dy > core * core) continue
                val x = (cx + dx).toInt(); val y = (cy + dy).toInt()
                if (x < bx0 || x > bx1 || y < by0 || y > by1) continue
                val li = (y - by0) * lw + (x - bx0)
                if (dist[li] == 0) continue
                if (needOpaque && (lab[y * w + x] ushr 24) < ALPHA_MIN) continue
                dist[li] = 0
                evaluate(li)
                prob[li] = max(prob[li], 0.9f)
                push(li, 0)
            }
        }
        var selCount = 0
        var sx0 = lw; var sy0 = lh; var sx1 = -1; var sy1 = -1
        var pops = 0
        for (d in 0..budget) {
            while (bucketHead[d] >= 0) {
                val e = bucketHead[d]
                bucketHead[d] = poolNext[e]
                val li = poolPix[e]
                if (dist[li] != d || selected[li].toInt() != 0) continue
                selected[li] = 1
                selCount++
                val x = li % lw
                val y = li / lw
                if (x < sx0) sx0 = x; if (x > sx1) sx1 = x
                if (y < sy0) sy0 = y; if (y > sy1) sy1 = y
                if ((++pops and 0xFFFF) == 0 && isCancelled?.invoke() == true) throw CancellationException("quick selection cancelled")
                for (k in 0 until 4) {
                    val q = when (k) {
                        0 -> if (x > 0) li - 1 else -1
                        1 -> if (x < lw - 1) li + 1 else -1
                        2 -> if (y > 0) li - lw else -1
                        else -> if (y < lh - 1) li + lw else -1
                    }
                    if (q < 0 || selected[q].toInt() != 0) continue
                    val c = stepCost(li, q)
                    if (c < 0) continue
                    val nd = d + c
                    if (nd > budget || nd >= dist[q]) continue
                    dist[q] = nd
                    push(q, nd)
                }
            }
        }
        if (selCount == 0) return false

        // --- Output region (selected bounds + a margin for the soft edge).
        val margin = 4
        val ox0 = (sx0 - margin).coerceAtLeast(0); val oy0 = (sy0 - margin).coerceAtLeast(0)
        val ox1 = (sx1 + margin).coerceAtMost(lw - 1); val oy1 = (sy1 + margin).coerceAtMost(lh - 1)
        val ow = ox1 - ox0 + 1
        val oh = oy1 - oy0 + 1
        val alpha = FloatArray(ow * oh)
        for (y in 0 until oh) for (x in 0 until ow) {
            if (selected[(y + oy0) * lw + (x + ox0)].toInt() != 0) alpha[y * ow + x] = 1f
        }

        if (options.autoEnhance) {
            fillHoles(alpha, ow, oh, maxHole = max(16, (rS * rS * 2f).toInt()))
            // Edge-aware soft edge: a guided filter steered by the object probability and lightness.
            val guide = FloatArray(ow * oh)
            for (y in 0 until oh) for (x in 0 until ow) {
                val li = (y + oy0) * lw + (x + ox0)
                if (gate[li] == -2) evaluate(li)
                val l = lOf(lab[(by0 + y + oy0) * w + (bx0 + x + ox0)]) / 100f
                guide[y * ow + x] = if (bg.isEmpty()) l else 0.6f * prob[li] + 0.4f * l
            }
            val radius = max(1, kotlin.math.round(min(3f, 1f + rS / 20f)).toInt())
            val filtered = guidedFilter(guide, alpha, ow, oh, radius, eps = 0.0025f)
            for (i in alpha.indices) {
                // Re-sharpen around 50% so the edge is crisp but anti-aliased.
                val t = ((filtered[i] - 0.5f) * 1.6f + 0.5f).coerceIn(0f, 1f)
                alpha[i] = t * t * (3f - 2f * t)
            }
        }

        // --- Draw into the document mask (bilinear upscale = anti-aliased edge).
        val local = Bitmap.createBitmap(ow, oh, Bitmap.Config.ALPHA_8)
        try {
            val rb = local.rowBytes
            val bytes = ByteArray(rb * oh)
            for (y in 0 until oh) for (x in 0 until ow) {
                bytes[y * rb + x] = (alpha[y * ow + x] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
            SelectionEngine.writeAlphaBytes(local, bytes)
            val gx0 = (bx0 + ox0).toFloat()
            val gy0 = (by0 + oy0).toFloat()
            val dst = RectF(gx0 / sx, gy0 / sy, (gx0 + ow) / sx, (gy0 + oh) / sy)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            when (mode) {
                SelectionMode.REPLACE -> {
                    docMask.eraseColor(Color.TRANSPARENT)
                    Canvas(docMask).drawBitmap(local, null, dst, paint)
                }
                SelectionMode.ADD -> Canvas(docMask).drawBitmap(local, null, dst, paint)
                SelectionMode.SUBTRACT -> {
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    Canvas(docMask).drawBitmap(local, null, dst, paint)
                }
                SelectionMode.INTERSECT -> {
                    val full = Bitmap.createBitmap(docMask.width, docMask.height, Bitmap.Config.ALPHA_8)
                    try {
                        Canvas(full).drawBitmap(local, null, dst, paint)
                        val p2 = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
                        Canvas(docMask).drawBitmap(full, 0f, 0f, p2)
                    } finally {
                        full.recycle()
                    }
                }
            }
        } finally {
            local.recycle()
        }
        return true
    }

    /** Fills unselected 4-connected holes of at most [maxHole] pixels that do not touch the border. */
    internal fun fillHoles(alpha: FloatArray, w: Int, h: Int, maxHole: Int) {
        val n = w * h
        val visited = BooleanArray(n)
        var stack = IntArray(1024)
        val comp = IntArray(maxHole + 1)
        for (start in 0 until n) {
            if (visited[start] || alpha[start] >= 0.5f) continue
            var sp = 0
            var count = 0
            var border = false
            visited[start] = true
            stack[sp++] = start
            while (sp > 0) {
                val p = stack[--sp]
                if (count <= maxHole) comp[count] = p
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
                    if (q < 0 || visited[q] || alpha[q] >= 0.5f) continue
                    visited[q] = true
                    if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
                    stack[sp++] = q
                }
            }
            if (!border && count <= maxHole) for (i in 0 until count) alpha[comp[i]] = 1f
        }
    }

    /** Separable box mean with radius [r] (clamped window at the borders). */
    private fun boxMean(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0.0
            var left = 0
            var right = -1
            for (x in 0 until w) {
                val nl = max(0, x - r)
                val nr = min(w - 1, x + r)
                while (right < nr) { right++; sum += src[row + right] }
                while (left < nl) { sum -= src[row + left]; left++ }
                tmp[row + x] = (sum / (nr - nl + 1)).toFloat()
            }
        }
        for (x in 0 until w) {
            var sum = 0.0
            var top = 0
            var bottom = -1
            for (y in 0 until h) {
                val nt = max(0, y - r)
                val nb = min(h - 1, y + r)
                while (bottom < nb) { bottom++; sum += tmp[bottom * w + x] }
                while (top < nt) { sum -= tmp[top * w + x]; top++ }
                out[y * w + x] = (sum / (nb - nt + 1)).toFloat()
            }
        }
        return out
    }

    /** Grey guided filter (He et al.): smooths [p] while keeping the edges of the guide [g]. */
    internal fun guidedFilter(g: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val gp = FloatArray(n) { g[it] * p[it] }
        val gg = FloatArray(n) { g[it] * g[it] }
        val mG = boxMean(g, w, h, r)
        val mP = boxMean(p, w, h, r)
        val mGP = boxMean(gp, w, h, r)
        val mGG = boxMean(gg, w, h, r)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val varG = mGG[i] - mG[i] * mG[i]
            val cov = mGP[i] - mG[i] * mP[i]
            a[i] = cov / (varG + eps)
            b[i] = mP[i] - a[i] * mG[i]
        }
        val mA = boxMean(a, w, h, r)
        val mB = boxMean(b, w, h, r)
        return FloatArray(n) { mA[it] * g[it] + mB[it] }
    }
}
