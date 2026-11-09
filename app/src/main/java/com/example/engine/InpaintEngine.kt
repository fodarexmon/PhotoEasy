package com.example.engine

import android.graphics.Bitmap
import java.util.PriorityQueue
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class InpaintEngineMode {
    SMART,              // Picks per part of the hole: fast marching for thin parts, structure + texture for the rest
    AI_PATCH_SYNTHESIS, // Multi-scale PatchMatch completion: copies real texture from the surroundings
    FAST_MARCHING       // Telea fast marching: smooth diffusion, best for thin lines / small blemishes
}

/**
 * Content-aware object removal.
 *
 * Pipeline:
 *  1. The hole is the mask (alpha > 32) grown by a few pixels (anti-halo), plus an optional feather
 *     ring that is cross-faded with the original for an invisible seam.
 *  2. Only a crop around the hole is processed. Large crops are processed at a bounded working size
 *     (MAX_WORK_DIM) and the fill is upsampled back, so big photos stay fast.
 *  3. AI mode: coarse-to-fine image completion (Wexler et al.) where every level alternates a
 *     PatchMatch nearest-neighbour search with weighted patch voting. Hole pixels are therefore built
 *     only from real pixels of the image, never from black/uninitialised values.
 *     Fast mode: Telea-style fast marching from the hole boundary inwards.
 *
 * Pure Kotlin (no OpenCV) and only uses getPixels/setPixels on bitmaps.
 */
object InpaintEngine {

    private const val MAX_WORK_DIM = 768
    private const val COARSEST_DIM = 64
    private const val PATCH_RADIUS = 3
    private const val MASK_THRESHOLD = 32

    fun inpaint(
        source: Bitmap,
        maskBitmap: Bitmap,
        mode: InpaintEngineMode = InpaintEngineMode.SMART,
        smartEdgeSnap: Boolean = true,
        seamlessBlend: Boolean = true,
        featherPx: Int = 4,
        radiusPx: Double = 5.0,
        /** Same size as [source]; texture is never copied from where its alpha is set. */
        protectMask: Bitmap? = null,
        /** Different seeds give different (equally valid) texture choices ("another result"). */
        seed: Int = 0x5EED
    ): Bitmap {
        require(source.width == maskBitmap.width && source.height == maskBitmap.height) {
            "Source and mask dimensions must match"
        }
        val width = source.width
        val height = source.height

        val maskPixels = IntArray(width * height)
        maskBitmap.getPixels(maskPixels, 0, width, 0, 0, width, height)

        var minX = width; var maxX = -1; var minY = height; var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (((maskPixels[row + x] ushr 24) and 0xFF) > MASK_THRESHOLD) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX) return source.copy(Bitmap.Config.ARGB_8888, true)

        // Grow the hole so anti-aliased object edges are removed too. A fixed 3 px is far too little
        // on a 4000 px photo, so the growth scales with the image size.
        val maxDim = max(width, height)
        val grow = if (smartEdgeSnap) max(3, (maxDim / 350f).roundToInt()).coerceAtMost(16) else max(1, (maxDim / 1200f).roundToInt()).coerceAtMost(6)
        val feather = if (seamlessBlend) featherPx.coerceIn(1, 24) else 0
        // Smart edge snap also looks for the object's fringe / glow beyond the grown edge.
        val haloMax = if (smartEdgeSnap) (grow * 2).coerceIn(4, 24) else 0
        val bgSampleWidth = 8
        val reach = grow + haloMax + feather + bgSampleWidth + 2

        // Crop with enough surrounding context to borrow texture from.
        val holeSpan = max(maxX - minX + 1, maxY - minY + 1)
        val margin = (holeSpan + reach).coerceIn(32, 384)
        val cropX0 = (minX - reach - margin).coerceAtLeast(0)
        val cropY0 = (minY - reach - margin).coerceAtLeast(0)
        val cropX1 = (maxX + reach + margin).coerceAtMost(width - 1)
        val cropY1 = (maxY + reach + margin).coerceAtMost(height - 1)
        val cw = cropX1 - cropX0 + 1
        val ch = cropY1 - cropY0 + 1
        val n = cw * ch

        val srcPixels = IntArray(n)
        source.getPixels(srcPixels, 0, cw, cropX0, cropY0, cw, ch)

        // Chessboard distance (in px) from the original mask, up to `reach`.
        val dist = IntArray(n) { Int.MAX_VALUE }
        val queue = IntArray(n)
        var qHead = 0
        var qTail = 0
        for (cy in 0 until ch) {
            val mRow = (cropY0 + cy) * width + cropX0
            for (cx in 0 until cw) {
                if (((maskPixels[mRow + cx] ushr 24) and 0xFF) > MASK_THRESHOLD) {
                    val i = cy * cw + cx
                    dist[i] = 0
                    queue[qTail++] = i
                }
            }
        }
        while (qHead < qTail) {
            val i = queue[qHead++]
            val d = dist[i]
            if (d >= reach) continue
            val x = i % cw
            val y = i / cw
            for (dy in -1..1) {
                val ny = y + dy
                if (ny < 0 || ny >= ch) continue
                for (dx in -1..1) {
                    val nx = x + dx
                    if (nx < 0 || nx >= cw) continue
                    val ni = ny * cw + nx
                    if (dist[ni] > d + 1) {
                        dist[ni] = d + 1
                        queue[qTail++] = ni
                    }
                }
            }
        }
        // Core = pixels that are fully replaced: the mask grown by `grow`, plus any connected fringe
        // pixels whose colour belongs to the object rather than to the background (halo / glow /
        // soft edge). Leaving those in place is what produced coloured remnants around the fill.
        val core = BooleanArray(n) { dist[it] <= grow }
        if (haloMax > 0) absorbObjectFringe(srcPixels, dist, core, cw, ch, grow, haloMax, bgSampleWidth)

        // Feather ring distance measured from the (possibly enlarged) core.
        val ringDist = IntArray(n) { if (core[it]) 0 else Int.MAX_VALUE }
        qHead = 0
        qTail = 0
        for (i in 0 until n) if (core[i]) queue[qTail++] = i
        while (qHead < qTail) {
            val i = queue[qHead++]
            val d = ringDist[i]
            if (d >= feather) continue
            val x = i % cw
            val y = i / cw
            for (dy in -1..1) {
                val ny = y + dy
                if (ny < 0 || ny >= ch) continue
                for (dx in -1..1) {
                    val nx = x + dx
                    if (nx < 0 || nx >= cw) continue
                    val ni = ny * cw + nx
                    if (ringDist[ni] > d + 1) {
                        ringDist[ni] = d + 1
                        queue[qTail++] = ni
                    }
                }
            }
        }
        val hole = BooleanArray(n) { ringDist[it] <= feather }

        // Areas the user protected are never used as a texture source.
        var forbid: BooleanArray? = null
        if (protectMask != null && protectMask.width == width && protectMask.height == height && !protectMask.isRecycled) {
            val pp = IntArray(n)
            protectMask.getPixels(pp, 0, cw, cropX0, cropY0, cw, ch)
            val fb = BooleanArray(n) { !hole[it] && ((pp[it] ushr 24) and 0xFF) > MASK_THRESHOLD }
            if (fb.any { it }) forbid = fb
        }

        val telaRadius = radiusPx.roundToInt().coerceIn(2, 15)
        val fill = when (mode) {
            InpaintEngineMode.SMART -> smartFill(srcPixels, hole, cw, ch, forbid, seed, telaRadius, maxDim, grow + feather)
            InpaintEngineMode.AI_PATCH_SYNTHESIS -> patchSynthesis(srcPixels, hole, cw, ch, forbid, seed)
            InpaintEngineMode.FAST_MARCHING -> teleaFill(srcPixels, hole, cw, ch, telaRadius)
        }

        // Composite: full replacement inside the core, cross-fade across the feather ring, which
        // now lies entirely on clean background.
        val out = srcPixels.copyOf()
        for (i in 0 until n) {
            if (!hole[i]) continue
            val d = ringDist[i]
            val t = if (d == 0 || feather == 0) 1f else 1f - d.toFloat() / (feather + 1).toFloat()
            out[i] = if (t >= 0.999f) fill[i] else lerpArgb(srcPixels[i], fill[i], t)
        }
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        result.setPixels(out, 0, cw, cropX0, cropY0, cw, ch)
        return result
    }

    /**
     * Grows [core] outwards, ring by ring (up to [haloMax] px beyond the grown mask), into pixels whose
     * colour is closer to the object's colours than to the surrounding background's. Object colours
     * are sampled inside the mask, background colours in a ring just outside the search zone.
     */
    private fun absorbObjectFringe(
        px: IntArray, dist: IntArray, core: BooleanArray, w: Int, h: Int,
        grow: Int, haloMax: Int, bgWidth: Int
    ) {
        val n = w * h
        val objSamples = ArrayList<Int>()
        val bgSamples = ArrayList<Int>()
        val bgFrom = grow + haloMax + 2
        val bgTo = bgFrom + bgWidth
        var objCount = 0
        var bgCount = 0
        for (i in 0 until n) {
            val d = dist[i]
            if (d == 0) objCount++ else if (d in bgFrom..bgTo) bgCount++
        }
        if (objCount == 0 || bgCount == 0) return
        val objStep = max(1, objCount / 3000)
        val bgStep = max(1, bgCount / 3000)
        var oi = 0
        var bi = 0
        for (i in 0 until n) {
            val d = dist[i]
            if (d == 0) { if (oi++ % objStep == 0) objSamples.add(px[i]) }
            else if (d in bgFrom..bgTo) { if (bi++ % bgStep == 0) bgSamples.add(px[i]) }
        }
        val objCenters = kMeans(objSamples, 4)
        val bgCenters = kMeans(bgSamples, 5)

        fun nearest(c: Int, centers: FloatArray): Float {
            val r = ((c shr 16) and 0xFF).toFloat(); val g = ((c shr 8) and 0xFF).toFloat(); val b = (c and 0xFF).toFloat()
            var best = Float.MAX_VALUE
            var k = 0
            while (k < centers.size) {
                val dr = r - centers[k]; val dg = g - centers[k + 1]; val db = b - centers[k + 2]
                val d = dr * dr + dg * dg + db * db
                if (d < best) best = d
                k += 3
            }
            return best
        }

        val accepted = ArrayList<Int>()
        for (ring in grow + 1..grow + haloMax) {
            accepted.clear()
            for (i in 0 until n) {
                if (dist[i] != ring || core[i]) continue
                val x = i % w
                val y = i / w
                var touchesCore = false
                loop@ for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h && core[ny * w + nx]) { touchesCore = true; break@loop }
                }
                if (!touchesCore) continue
                val c = px[i]
                // Clearly closer to the object than to the background (squared distances, ~0.8×).
                if (nearest(c, objCenters) < nearest(c, bgCenters) * 0.64f) accepted.add(i)
            }
            if (accepted.isEmpty()) break
            for (i in accepted) core[i] = true
        }
    }

    /** Small k-means on packed RGB colours → [r,g,b, r,g,b, …] (empty clusters dropped). */
    private fun kMeans(samples: List<Int>, k: Int): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val kk = min(k, samples.size)
        val centers = FloatArray(kk * 3)
        for (c in 0 until kk) {
            val p = samples[(c * (samples.size - 1)) / max(1, kk - 1)]
            centers[c * 3] = ((p shr 16) and 0xFF).toFloat()
            centers[c * 3 + 1] = ((p shr 8) and 0xFF).toFloat()
            centers[c * 3 + 2] = (p and 0xFF).toFloat()
        }
        val sums = DoubleArray(kk * 3)
        val counts = IntArray(kk)
        repeat(6) {
            java.util.Arrays.fill(sums, 0.0)
            java.util.Arrays.fill(counts, 0)
            for (p in samples) {
                val r = ((p shr 16) and 0xFF).toFloat(); val g = ((p shr 8) and 0xFF).toFloat(); val b = (p and 0xFF).toFloat()
                var best = 0; var bestD = Float.MAX_VALUE
                for (c in 0 until kk) {
                    val dr = r - centers[c * 3]; val dg = g - centers[c * 3 + 1]; val db = b - centers[c * 3 + 2]
                    val d = dr * dr + dg * dg + db * db
                    if (d < bestD) { bestD = d; best = c }
                }
                sums[best * 3] += r.toDouble(); sums[best * 3 + 1] += g.toDouble(); sums[best * 3 + 2] += b.toDouble()
                counts[best]++
            }
            for (c in 0 until kk) if (counts[c] > 0) {
                centers[c * 3] = (sums[c * 3] / counts[c]).toFloat()
                centers[c * 3 + 1] = (sums[c * 3 + 1] / counts[c]).toFloat()
                centers[c * 3 + 2] = (sums[c * 3 + 2] / counts[c]).toFloat()
            }
        }
        val kept = ArrayList<Float>()
        for (c in 0 until kk) if (counts[c] > 0) { kept.add(centers[c * 3]); kept.add(centers[c * 3 + 1]); kept.add(centers[c * 3 + 2]) }
        return kept.toFloatArray()
    }

    private fun lerpArgb(a: Int, b: Int, t: Float): Int {
        val inv = 1f - t
        val aa = ((a ushr 24) and 0xFF) * inv + ((b ushr 24) and 0xFF) * t
        val ar = ((a ushr 16) and 0xFF) * inv + ((b ushr 16) and 0xFF) * t
        val ag = ((a ushr 8) and 0xFF) * inv + ((b ushr 8) and 0xFF) * t
        val ab = (a and 0xFF) * inv + (b and 0xFF) * t
        return (aa.roundToInt().coerceIn(0, 255) shl 24) or (ar.roundToInt().coerceIn(0, 255) shl 16) or
            (ag.roundToInt().coerceIn(0, 255) shl 8) or ab.roundToInt().coerceIn(0, 255)
    }

    // ---------------------------------------------------------------------------------------------
    // Multi-scale PatchMatch image completion
    // ---------------------------------------------------------------------------------------------

    /** One pyramid level: interleaved ARGB floats (4 per pixel) and the hole mask. */
    private class Level(val w: Int, val h: Int, val img: FloatArray, val hole: BooleanArray) {
        /** Pixels that must not be used as a texture source (protected by the user). */
        var forbid: BooleanArray? = null
    }

    // ---------------------------------------------------------------------------------------------
    // Smart removal: one tool that picks the right technique for every part of the hole
    // ---------------------------------------------------------------------------------------------

    /**
     * 1. The hole is split into connected parts and each part's thickness is measured.
     * 2. Thin / small parts (scratches, wires, dust, hairs) are filled by fast marching, which is
     *    seamless there.
     * 3. Straight edges that run behind a thick part (horizon, table edge, window frame) are
     *    continued across it first.
     * 4. The remaining thick parts get PatchMatch texture synthesis, started from the fast-marching
     *    fill (a stable, artefact-free start) and avoiding [forbid] as a texture source.
     */
    private fun smartFill(
        src: IntArray,
        hole: BooleanArray,
        w: Int,
        h: Int,
        forbid: BooleanArray?,
        seed: Int,
        radius: Int,
        imageMaxDim: Int,
        /** How far the hole was grown beyond the painted mask (anti-halo + feather). */
        grownBy: Int
    ): IntArray {
        val n = w * h
        val diffused = teleaFill(src, hole, w, h, radius)

        // Distance from each hole pixel to the nearest known pixel (4-neighbour BFS) = half thickness.
        val inDist = IntArray(n) { if (hole[it]) Int.MAX_VALUE else 0 }
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        for (i in 0 until n) if (!hole[i]) {
            val x = i % w
            val y = i / w
            if ((x > 0 && hole[i - 1]) || (x < w - 1 && hole[i + 1]) || (y > 0 && hole[i - w]) || (y < h - 1 && hole[i + w])) {
                queue[tail++] = i
            }
        }
        while (head < tail) {
            val i = queue[head++]
            val d = inDist[i]
            val x = i % w
            val y = i / w
            fun visit(j: Int) {
                if (hole[j] && inDist[j] > d + 1) { inDist[j] = d + 1; queue[tail++] = j }
            }
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }

        // Connected parts (8-neighbour) with their thickness and area.
        val label = IntArray(n) { -1 }
        val thick = ArrayList<Boolean>()
        // A painted stroke up to ~2x this wide (after growing) counts as thin.
        val thinLimit = grownBy + max(3, (imageMaxDim / 600f).roundToInt())
        var stack = IntArray(1024)
        for (start in 0 until n) {
            if (!hole[start] || label[start] >= 0) continue
            val id = thick.size
            var sp = 0
            var area = 0
            var maxIn = 0
            label[start] = id
            stack[sp++] = start
            while (sp > 0) {
                val i = stack[--sp]
                area++
                if (inDist[i] != Int.MAX_VALUE && inDist[i] > maxIn) maxIn = inDist[i]
                val x = i % w
                val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (hole[j] && label[j] < 0) {
                        label[j] = id
                        if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
                        stack[sp++] = j
                    }
                }
            }
            thick.add(maxIn > thinLimit && area > thinLimit * thinLimit * 4)
        }
        if (thick.none { it }) return diffused

        // Thin parts are final; thick parts remain to be synthesised.
        val work = src.copyOf()
        val thickHole = BooleanArray(n)
        for (i in 0 until n) {
            if (!hole[i]) continue
            if (thick[label[i]]) thickHole[i] = true else work[i] = diffused[i]
        }

        // Continue straight structures across the thick parts, then synthesise texture around them.
        val fixed = completeStructures(work, thickHole, w, h, max(1, (imageMaxDim / 800f).roundToInt()))
        val patchHole = BooleanArray(n) { thickHole[it] && !fixed[it] }
        if (patchHole.none { it }) return work
        return patchSynthesis(work, patchHole, w, h, forbid, seed, init = diffused)
    }

    /**
     * Finds strong straight edges that enter a hole on one side and leave it on the other (matching
     * direction and matching colours on both sides of the edge), and paints each one across the
     * hole as a short band blended between its two ends. Returns the painted pixels, which the
     * texture synthesis then keeps.
     */
    private fun completeStructures(img: IntArray, hole: BooleanArray, w: Int, h: Int, halfWidth: Int): BooleanArray {
        val n = w * h
        val fixed = BooleanArray(n)
        // Chessboard distance outward from the hole, up to 6 px.
        val outDist = IntArray(n) { if (hole[it]) 0 else Int.MAX_VALUE }
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        for (i in 0 until n) if (hole[i]) queue[tail++] = i
        while (head < tail) {
            val i = queue[head++]
            val d = outDist[i]
            if (d >= 6) continue
            val x = i % w
            val y = i / w
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (outDist[j] > d + 1) { outDist[j] = d + 1; queue[tail++] = j }
            }
        }
        fun luma(p: Int): Float = 0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)

        // Edge points on the ring 3 px outside the hole (Sobel on luminance, all neighbours known).
        class EdgePt(val x: Float, val y: Float, val ex: Float, val ey: Float, val mag: Float)
        val raw = ArrayList<EdgePt>()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (outDist[i] != 3) continue
                var ok = true
                for (dy in -1..1) for (dx in -1..1) if (hole[i + dy * w + dx]) ok = false
                if (!ok) continue
                val gx = (luma(img[i - w + 1]) + 2 * luma(img[i + 1]) + luma(img[i + w + 1])) -
                    (luma(img[i - w - 1]) + 2 * luma(img[i - 1]) + luma(img[i + w - 1]))
                val gy = (luma(img[i + w - 1]) + 2 * luma(img[i + w]) + luma(img[i + w + 1])) -
                    (luma(img[i - w - 1]) + 2 * luma(img[i - w]) + luma(img[i - w + 1]))
                val mag = sqrt(gx * gx + gy * gy)
                if (mag < 90f) continue
                raw.add(EdgePt(x.toFloat(), y.toFloat(), -gy / mag, gx / mag, mag))
            }
        }
        if (raw.size < 2) return fixed
        // Keep the strongest point in each 6 px neighbourhood.
        raw.sortByDescending { it.mag }
        val pts = ArrayList<EdgePt>()
        for (p in raw) {
            if (pts.none { (it.x - p.x) * (it.x - p.x) + (it.y - p.y) * (it.y - p.y) < 36f }) pts.add(p)
            if (pts.size >= 40) break
        }

        fun sampleKnown(fx: Float, fy: Float): Int {
            val x = fx.roundToInt().coerceIn(0, w - 1)
            val y = fy.roundToInt().coerceIn(0, h - 1)
            return img[y * w + x]
        }
        fun colorDist(a: Int, b: Int): Float {
            val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
            val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
            val db = (a and 0xFF) - (b and 0xFF)
            return sqrt((dr * dr + dg * dg + db * db).toFloat())
        }

        class EdgePair(val a: EdgePt, val b: EdgePt, val cost: Float)
        val candidates = ArrayList<EdgePair>()
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            val a = pts[i]
            val b = pts[j]
            val vx = b.x - a.x
            val vy = b.y - a.y
            val len = sqrt(vx * vx + vy * vy)
            if (len < 8f) continue
            val ux = vx / len
            val uy = vy / len
            val alignA = abs(a.ex * ux + a.ey * uy)
            val alignB = abs(b.ex * ux + b.ey * uy)
            if (alignA < 0.94f || alignB < 0.94f) continue
            // The segment must actually cross the hole.
            var inHole = 0
            val steps = len.toInt()
            for (s in 0..steps) {
                val px = (a.x + ux * s).roundToInt().coerceIn(0, w - 1)
                val py = (a.y + uy * s).roundToInt().coerceIn(0, h - 1)
                if (hole[py * w + px]) inHole++
            }
            if (inHole < steps * 0.4f) continue
            // Same colours on each side of the edge at both ends.
            val lx = -uy
            val ly = ux
            val d = colorDist(sampleKnown(a.x + lx * 2, a.y + ly * 2), sampleKnown(b.x + lx * 2, b.y + ly * 2)) +
                colorDist(sampleKnown(a.x - lx * 2, a.y - ly * 2), sampleKnown(b.x - lx * 2, b.y - ly * 2))
            if (d > 90f) continue
            candidates.add(EdgePair(a, b, d + (2f - alignA - alignB) * 300f))
        }
        candidates.sortBy { it.cost }
        val used = HashSet<EdgePt>()
        var painted = 0
        for (c in candidates) {
            if (c.a in used || c.b in used) continue
            used.add(c.a); used.add(c.b)
            val vx = c.b.x - c.a.x
            val vy = c.b.y - c.a.y
            val len = sqrt(vx * vx + vy * vy)
            val ux = vx / len
            val uy = vy / len
            val lx = -uy
            val ly = ux
            val steps = (len * 2).toInt()
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                for (k in -halfWidth..halfWidth) {
                    val px = (c.a.x + vx * t + lx * k).roundToInt()
                    val py = (c.a.y + vy * t + ly * k).roundToInt()
                    if (px < 0 || py < 0 || px >= w || py >= h) continue
                    val idx = py * w + px
                    if (!hole[idx] || fixed[idx]) continue
                    val ca = sampleKnown(c.a.x + lx * k, c.a.y + ly * k)
                    val cb = sampleKnown(c.b.x + lx * k, c.b.y + ly * k)
                    img[idx] = lerpArgb(ca, cb, t)
                    fixed[idx] = true
                }
            }
            if (++painted >= 12) break
        }
        return fixed
    }

    /** Boolean OR downsampling (a coarse pixel is set if any covered fine pixel is). */
    private fun downsampleOr(src: BooleanArray, w: Int, h: Int, nw: Int, nh: Int): BooleanArray {
        val out = BooleanArray(nw * nh)
        for (y in 0 until nh) {
            val sy0 = (y.toLong() * h / nh).toInt()
            val sy1 = max(sy0 + 1, ((y + 1).toLong() * h / nh).toInt()).coerceAtMost(h)
            for (x in 0 until nw) {
                val sx0 = (x.toLong() * w / nw).toInt()
                val sx1 = max(sx0 + 1, ((x + 1).toLong() * w / nw).toInt()).coerceAtMost(w)
                var any = false
                loop@ for (sy in sy0 until sy1) for (sx in sx0 until sx1) if (src[sy * w + sx]) { any = true; break@loop }
                out[y * nw + x] = any
            }
        }
        return out
    }

    private fun patchSynthesis(
        src: IntArray,
        hole: BooleanArray,
        cw: Int,
        ch: Int,
        forbid: BooleanArray? = null,
        seed: Int = 0x5EED,
        /** Optional starting fill for the hole (e.g. fast marching) used at the coarsest level. */
        init: IntArray? = null
    ): IntArray {
        // Full-resolution crop as floats.
        val full = FloatArray(cw * ch * 4)
        for (i in 0 until cw * ch) {
            val p = src[i]
            full[i * 4] = ((p ushr 24) and 0xFF).toFloat()
            full[i * 4 + 1] = ((p ushr 16) and 0xFF).toFloat()
            full[i * 4 + 2] = ((p ushr 8) and 0xFF).toFloat()
            full[i * 4 + 3] = (p and 0xFF).toFloat()
        }

        // Working level (bounded size).
        val scale = min(1f, MAX_WORK_DIM.toFloat() / max(cw, ch).toFloat())
        val ww = max(1, (cw * scale).roundToInt())
        val wh = max(1, (ch * scale).roundToInt())
        val working = if (ww == cw && wh == ch) Level(cw, ch, full.copyOf(), hole.copyOf()) else resampleLevel(full, hole, cw, ch, ww, wh)
        if (forbid != null) working.forbid = if (ww == cw && wh == ch) forbid.copyOf() else downsampleOr(forbid, cw, ch, ww, wh)

        // Pyramid (index 0 = working / finest).
        val pyramid = ArrayList<Level>()
        pyramid.add(working)
        while (pyramid.size < 7) {
            val cur = pyramid.last()
            if (max(cur.w, cur.h) <= COARSEST_DIM || min(cur.w, cur.h) < 4 * PATCH_RADIUS + 4) break
            val next = resampleLevel(cur.img, cur.hole, cur.w, cur.h, (cur.w + 1) / 2, (cur.h + 1) / 2)
            if (next.hole.all { it }) break // no known context left at this scale
            cur.forbid?.let { next.forbid = downsampleOr(it, cur.w, cur.h, next.w, next.h) }
            pyramid.add(next)
        }

        val rnd = Random(seed)
        var prevNnf: IntArray? = null
        var prevW = 0
        var prevH = 0
        var finest: LevelResult? = null
        for (li in pyramid.indices.reversed()) {
            val level = pyramid[li]
            if (li == pyramid.lastIndex) {
                diffusionFill(level)
                if (init != null) {
                    // Start from the given fill instead of a plain diffusion (smoother, no artefacts).
                    val initFloats = FloatArray(cw * ch * 4)
                    for (i in 0 until cw * ch) {
                        val p = init[i]
                        initFloats[i * 4] = ((p ushr 24) and 0xFF).toFloat()
                        initFloats[i * 4 + 1] = ((p ushr 16) and 0xFF).toFloat()
                        initFloats[i * 4 + 2] = ((p ushr 8) and 0xFF).toFloat()
                        initFloats[i * 4 + 3] = (p and 0xFF).toFloat()
                    }
                    val initLevel = resampleLevel(initFloats, BooleanArray(cw * ch), cw, ch, level.w, level.h)
                    for (i in 0 until level.w * level.h) if (level.hole[i]) {
                        for (c in 0 until 4) level.img[i * 4 + c] = initLevel.img[i * 4 + c]
                    }
                }
            } else {
                upsampleInto(pyramid[li + 1], level)
            }
            val emIterations = when {
                li == pyramid.lastIndex -> 6
                li == 0 -> 3
                else -> 3
            }
            // Coarse levels average (stable structure); the two finest levels vote sharply so the
            // fill keeps the contrast and grain of the real background.
            val result = completeLevel(level, emIterations, prevNnf, prevW, prevH, rnd, sharpVote = li <= 1)
            prevNnf = result?.nnf
            prevW = level.w
            prevH = level.h
            if (li == 0) finest = result
        }

        // Back to full crop resolution (hole pixels only).
        val fin = pyramid[0]
        val out = src.copyOf()
        if (fin.w == cw && fin.h == ch) {
            for (i in 0 until cw * ch) if (hole[i]) out[i] = packArgb(fin.img, i)
        } else {
            transferFullResolution(src, hole, cw, ch, fin, finest, out)
        }
        return out
    }

    /**
     * The working level is smaller than the crop: instead of enlarging its (necessarily soft) colours,
     * every full-resolution hole pixel follows the patch matches found at the working level back to
     * REAL full-resolution background pixels. That restores the original grain and sharpness; the
     * soft upscaled fill is only used where no valid match exists.
     */
    private fun transferFullResolution(
        src: IntArray,
        hole: BooleanArray,
        cw: Int,
        ch: Int,
        fin: Level,
        finest: LevelResult?,
        out: IntArray
    ) {
        val sx = fin.w.toFloat() / cw
        val sy = fin.h.toFloat() / ch
        val tmp = FloatArray(4)
        val candColor = IntArray(16)
        val candDist = FloatArray(16)
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                val i = y * cw + x
                if (!hole[i]) continue
                val wx = (x + 0.5f) * sx - 0.5f
                val wy = (y + 0.5f) * sy - 0.5f
                var nc = 0
                if (finest != null) {
                    val bx = kotlin.math.floor(wx).toInt()
                    val by = kotlin.math.floor(wy).toInt()
                    for (dy in -1..2) {
                        val ty = by + dy
                        if (ty < 0 || ty >= fin.h) continue
                        for (dx in -1..2) {
                            val tx = bx + dx
                            if (tx < 0 || tx >= fin.w) continue
                            val t = ty * fin.w + tx
                            val q = finest.nnf[t]
                            if (q < 0) continue
                            // Same offset from the matched centre, mapped back to full resolution.
                            val swx = (q % fin.w) + (wx - tx)
                            val swy = (q / fin.w) + (wy - ty)
                            val fx = ((swx + 0.5f) / sx - 0.5f).roundToInt()
                            val fy = ((swy + 0.5f) / sy - 0.5f).roundToInt()
                            if (fx < 0 || fy < 0 || fx >= cw || fy >= ch) continue
                            val fi = fy * cw + fx
                            if (hole[fi]) continue
                            candColor[nc] = src[fi]
                            candDist[nc] = finest.dist[t]
                            nc++
                        }
                    }
                }
                if (nc == 0) {
                    sampleBilinear(fin, wx, wy, tmp)
                    out[i] = (tmp[0].roundToInt().coerceIn(0, 255) shl 24) or (tmp[1].roundToInt().coerceIn(0, 255) shl 16) or
                        (tmp[2].roundToInt().coerceIn(0, 255) shl 8) or tmp[3].roundToInt().coerceIn(0, 255)
                    continue
                }
                // Average only the near-best candidates (sharp, but without hard seams).
                var minD = Float.MAX_VALUE
                for (k in 0 until nc) if (candDist[k] < minD) minD = candDist[k]
                var a = 0f; var r = 0f; var g = 0f; var b = 0f; var cnt = 0
                for (k in 0 until nc) {
                    if (candDist[k] > minD * 1.3f + 1f) continue
                    val c = candColor[k]
                    a += (c ushr 24) and 0xFF; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                    cnt++
                }
                out[i] = ((a / cnt).roundToInt() shl 24) or ((r / cnt).roundToInt() shl 16) or
                    ((g / cnt).roundToInt() shl 8) or (b / cnt).roundToInt()
            }
        }
    }

    private fun packArgb(img: FloatArray, i: Int): Int =
        (img[i * 4].roundToInt().coerceIn(0, 255) shl 24) or (img[i * 4 + 1].roundToInt().coerceIn(0, 255) shl 16) or
            (img[i * 4 + 2].roundToInt().coerceIn(0, 255) shl 8) or img[i * 4 + 3].roundToInt().coerceIn(0, 255)

    /**
     * Area-average resampling. Known pixels are averaged from known source pixels only; a target
     * pixel is a hole if ANY covered source pixel is a hole (so coarse "known" pixels are truly known).
     */
    private fun resampleLevel(img: FloatArray, hole: BooleanArray, w: Int, h: Int, nw: Int, nh: Int): Level {
        val outImg = FloatArray(nw * nh * 4)
        val outHole = BooleanArray(nw * nh)
        for (y in 0 until nh) {
            val sy0 = (y.toLong() * h / nh).toInt()
            val sy1 = max(sy0 + 1, ((y + 1).toLong() * h / nh).toInt()).coerceAtMost(h)
            for (x in 0 until nw) {
                val sx0 = (x.toLong() * w / nw).toInt()
                val sx1 = max(sx0 + 1, ((x + 1).toLong() * w / nw).toInt()).coerceAtMost(w)
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                var count = 0
                var anyHole = false
                for (sy in sy0 until sy1) {
                    for (sx in sx0 until sx1) {
                        val si = sy * w + sx
                        if (hole[si]) {
                            anyHole = true
                        } else {
                            a += img[si * 4]; r += img[si * 4 + 1]; g += img[si * 4 + 2]; b += img[si * 4 + 3]
                            count++
                        }
                    }
                }
                val o = y * nw + x
                outHole[o] = anyHole
                if (count > 0) {
                    outImg[o * 4] = a / count; outImg[o * 4 + 1] = r / count
                    outImg[o * 4 + 2] = g / count; outImg[o * 4 + 3] = b / count
                }
            }
        }
        return Level(nw, nh, outImg, outHole)
    }

    /** Onion-peel diffusion: fills the hole from its boundary inwards with neighbour averages. */
    private fun diffusionFill(level: Level) {
        val w = level.w
        val h = level.h
        val img = level.img
        val known = BooleanArray(w * h) { !level.hole[it] }
        if (known.none { it }) return
        val frontier = ArrayList<Int>()
        val pending = ArrayList<Int>()
        while (true) {
            frontier.clear()
            for (i in 0 until w * h) {
                if (known[i]) continue
                val x = i % w
                val y = i / w
                var hasKnown = false
                loop@ for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h && known[ny * w + nx]) { hasKnown = true; break@loop }
                }
                if (hasKnown) frontier.add(i)
            }
            if (frontier.isEmpty()) break
            pending.clear()
            pending.addAll(frontier)
            val values = FloatArray(pending.size * 4)
            for ((k, i) in pending.withIndex()) {
                val x = i % w
                val y = i / w
                var a = 0f; var r = 0f; var g = 0f; var b = 0f; var c = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) {
                        val ni = ny * w + nx
                        if (known[ni]) {
                            a += img[ni * 4]; r += img[ni * 4 + 1]; g += img[ni * 4 + 2]; b += img[ni * 4 + 3]; c++
                        }
                    }
                }
                values[k * 4] = a / c; values[k * 4 + 1] = r / c; values[k * 4 + 2] = g / c; values[k * 4 + 3] = b / c
            }
            for ((k, i) in pending.withIndex()) {
                img[i * 4] = values[k * 4]; img[i * 4 + 1] = values[k * 4 + 1]
                img[i * 4 + 2] = values[k * 4 + 2]; img[i * 4 + 3] = values[k * 4 + 3]
                known[i] = true
            }
        }
    }

    private fun sampleBilinear(level: Level, fx: Float, fy: Float, out: FloatArray) {
        val x = fx.coerceIn(0f, (level.w - 1).toFloat())
        val y = fy.coerceIn(0f, (level.h - 1).toFloat())
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = min(x0 + 1, level.w - 1); val y1 = min(y0 + 1, level.h - 1)
        val tx = x - x0; val ty = y - y0
        val i00 = (y0 * level.w + x0) * 4; val i10 = (y0 * level.w + x1) * 4
        val i01 = (y1 * level.w + x0) * 4; val i11 = (y1 * level.w + x1) * 4
        val img = level.img
        for (c in 0 until 4) {
            val top = img[i00 + c] * (1 - tx) + img[i10 + c] * tx
            val bottom = img[i01 + c] * (1 - tx) + img[i11 + c] * tx
            out[c] = top * (1 - ty) + bottom * ty
        }
    }

    /** Initialises the hole of [fine] from the already completed coarser level. */
    private fun upsampleInto(coarse: Level, fine: Level) {
        val sx = coarse.w.toFloat() / fine.w
        val sy = coarse.h.toFloat() / fine.h
        val tmp = FloatArray(4)
        for (y in 0 until fine.h) {
            for (x in 0 until fine.w) {
                val i = y * fine.w + x
                if (!fine.hole[i]) continue
                sampleBilinear(coarse, (x + 0.5f) * sx - 0.5f, (y + 0.5f) * sy - 0.5f, tmp)
                fine.img[i * 4] = tmp[0]; fine.img[i * 4 + 1] = tmp[1]
                fine.img[i * 4 + 2] = tmp[2]; fine.img[i * 4 + 3] = tmp[3]
            }
        }
    }

    /**
     * EM completion of one level: PatchMatch finds, for every patch overlapping the hole, the most
     * similar fully-known patch; hole pixels are then re-estimated by weighted voting of those
     * patches. Returns the nearest-neighbour field (centre index per pixel, -1 = none) and the
     * matching patch distances.
     *
     * [sharpVote]: only near-best patches covering a pixel vote. Averaging every overlapping patch
     * (classic voting) lowers contrast and leaves a pale, smooth "ghost" of the removed object, so
     * the fine levels restrict the votes to the best matches.
     */
    private class LevelResult(val nnf: IntArray, val dist: FloatArray)

    private fun completeLevel(
        level: Level,
        emIterations: Int,
        prevNnf: IntArray?,
        prevW: Int,
        prevH: Int,
        rnd: Random,
        sharpVote: Boolean = false
    ): LevelResult? {
        val w = level.w
        val h = level.h
        val r = PATCH_RADIUS
        if (w < 2 * r + 1 || h < 2 * r + 1) return null
        val hole = level.hole
        val img = level.img

        // Summed-area table of hole pixels → O(1) "does this patch contain hole pixels".
        val sat = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            for (x in 0 until w) {
                if (hole[y * w + x]) rowSum++
                sat[(y + 1) * (w + 1) + (x + 1)] = sat[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        fun holeCount(cx: Int, cy: Int): Int {
            val x0 = cx - r; val y0 = cy - r; val x1 = cx + r + 1; val y1 = cy + r + 1
            return sat[y1 * (w + 1) + x1] - sat[y0 * (w + 1) + x1] - sat[y1 * (w + 1) + x0] + sat[y0 * (w + 1) + x0]
        }

        val forbid = level.forbid
        val fsat = if (forbid != null) IntArray((w + 1) * (h + 1)).also { t ->
            for (y in 0 until h) {
                var rowSum = 0
                for (x in 0 until w) {
                    if (forbid[y * w + x]) rowSum++
                    t[(y + 1) * (w + 1) + (x + 1)] = t[y * (w + 1) + (x + 1)] + rowSum
                }
            }
        } else null
        fun forbidCount(cx: Int, cy: Int): Int {
            val t = fsat ?: return 0
            val x0 = cx - r; val y0 = cy - r; val x1 = cx + r + 1; val y1 = cy + r + 1
            return t[y1 * (w + 1) + x1] - t[y0 * (w + 1) + x1] - t[y1 * (w + 1) + x0] + t[y0 * (w + 1) + x0]
        }

        val validSrc = BooleanArray(w * h)
        val srcList = ArrayList<Int>()
        val targets = ArrayList<Int>()
        for (y in r until h - r) {
            for (x in r until w - r) {
                val c = holeCount(x, y)
                val i = y * w + x
                if (c == 0) {
                    if (forbidCount(x, y) == 0) { validSrc[i] = true; srcList.add(i) }
                } else targets.add(i)
            }
        }
        if (srcList.isEmpty() || targets.isEmpty()) return null

        fun randomSource(): Int = srcList[rnd.nextInt(srcList.size)]

        // Patch distance (sum of squared ARGB differences) with early termination.
        val offsets = IntArray((2 * r + 1) * (2 * r + 1))
        run {
            var k = 0
            for (dy in -r..r) for (dx in -r..r) offsets[k++] = dy * w + dx
        }
        fun patchDistance(p: Int, q: Int, limit: Float): Float {
            var sum = 0f
            for (o in offsets) {
                val pi = (p + o) * 4
                val qi = (q + o) * 4
                val d0 = img[pi] - img[qi]
                val d1 = img[pi + 1] - img[qi + 1]
                val d2 = img[pi + 2] - img[qi + 2]
                val d3 = img[pi + 3] - img[qi + 3]
                sum += d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3
                if (sum >= limit) return sum
            }
            return sum
        }

        // Initial nearest-neighbour field: upscaled from the coarser level when available.
        val nnf = IntArray(w * h) { -1 }
        for (t in targets) {
            var q = -1
            if (prevNnf != null && prevW > 0) {
                val x = t % w; val y = t / w
                val cx = min(x * prevW / w, prevW - 1)
                val cy = min(y * prevH / h, prevH - 1)
                val cq = prevNnf[cy * prevW + cx]
                if (cq >= 0) {
                    val qx = ((cq % prevW) * w / prevW + (x - cx * w / prevW)).coerceIn(r, w - r - 1)
                    val qy = ((cq / prevW) * h / prevH + (y - cy * h / prevH)).coerceIn(r, h - r - 1)
                    val cand = qy * w + qx
                    if (validSrc[cand]) q = cand
                }
            }
            nnf[t] = if (q >= 0) q else randomSource()
        }

        val distArr = FloatArray(w * h)
        val acc = FloatArray(w * h * 4)
        val wsum = FloatArray(w * h)
        val minPix = if (sharpVote) FloatArray(w * h) else null
        val maxRadius = max(w, h)

        for (em in 0 until emIterations) {
            for (t in targets) distArr[t] = patchDistance(t, nnf[t], Float.MAX_VALUE)

            val pmIterations = if (em == 0) 4 else 2
            for (pm in 0 until pmIterations) {
                val forward = pm % 2 == 0
                val dir = if (forward) 1 else -1
                val count = targets.size
                for (k in 0 until count) {
                    val t = if (forward) targets[k] else targets[count - 1 - k]
                    val x = t % w
                    val y = t / w
                    var best = nnf[t]
                    var bestD = distArr[t]

                    // Propagation from the already-visited horizontal and vertical neighbour.
                    val nbX = x - dir
                    if (nbX in r until w - r) {
                        val nb = t - dir
                        val nq = nnf[nb]
                        if (nq >= 0) {
                            val qx = nq % w + dir
                            val qy = nq / w
                            if (qx in r until w - r) {
                                val cand = qy * w + qx
                                if (cand != best && validSrc[cand]) {
                                    val d = patchDistance(t, cand, bestD)
                                    if (d < bestD) { best = cand; bestD = d }
                                }
                            }
                        }
                    }
                    val nbY = y - dir
                    if (nbY in r until h - r) {
                        val nb = t - dir * w
                        val nq = nnf[nb]
                        if (nq >= 0) {
                            val qx = nq % w
                            val qy = nq / w + dir
                            if (qy in r until h - r) {
                                val cand = qy * w + qx
                                if (cand != best && validSrc[cand]) {
                                    val d = patchDistance(t, cand, bestD)
                                    if (d < bestD) { best = cand; bestD = d }
                                }
                            }
                        }
                    }

                    // Random search around the current best with exponentially shrinking radius.
                    var radius = maxRadius
                    while (radius >= 1) {
                        val bx = best % w
                        val by = best / w
                        val qx = (bx + rnd.nextInt(2 * radius + 1) - radius).coerceIn(r, w - r - 1)
                        val qy = (by + rnd.nextInt(2 * radius + 1) - radius).coerceIn(r, h - r - 1)
                        val cand = qy * w + qx
                        if (cand != best && validSrc[cand]) {
                            val d = patchDistance(t, cand, bestD)
                            if (d < bestD) { best = cand; bestD = d }
                        }
                        radius /= 2
                    }
                    nnf[t] = best
                    distArr[t] = bestD
                }
            }

            // Weighted voting: similar patches contribute more.
            java.util.Arrays.fill(acc, 0f)
            java.util.Arrays.fill(wsum, 0f)
            var meanD = 0.0
            for (t in targets) meanD += distArr[t]
            val sigma = max((meanD / targets.size).toFloat(), 1f)
            if (minPix != null) {
                java.util.Arrays.fill(minPix, Float.MAX_VALUE)
                for (t in targets) {
                    val d = distArr[t]
                    for (o in offsets) {
                        val pi = t + o
                        if (hole[pi] && d < minPix[pi]) minPix[pi] = d
                    }
                }
            }
            for (t in targets) {
                val q = nnf[t]
                val d = distArr[t]
                // Sharp voting: a much steeper weight, and patches clearly worse than the best one
                // for this pixel are ignored. Keeps the real texture contrast of the surroundings.
                val weight = if (minPix != null) exp(-d / (sigma * 0.25f)).coerceAtLeast(1e-12f) else exp(-d / sigma).coerceAtLeast(1e-6f)
                for (o in offsets) {
                    val pi = t + o
                    if (!hole[pi]) continue
                    if (minPix != null && d > minPix[pi] * 1.3f + 1f) continue
                    val qi = (q + o) * 4
                    val ai = pi * 4
                    acc[ai] += weight * img[qi]
                    acc[ai + 1] += weight * img[qi + 1]
                    acc[ai + 2] += weight * img[qi + 2]
                    acc[ai + 3] += weight * img[qi + 3]
                    wsum[pi] += weight
                }
            }
            for (i in 0 until w * h) {
                if (!hole[i] || wsum[i] <= 0f) continue
                val inv = 1f / wsum[i]
                img[i * 4] = acc[i * 4] * inv
                img[i * 4 + 1] = acc[i * 4 + 1] * inv
                img[i * 4 + 2] = acc[i * 4 + 2] * inv
                img[i * 4 + 3] = acc[i * 4 + 3] * inv
            }
        }
        return LevelResult(nnf, distArr)
    }

    // ---------------------------------------------------------------------------------------------
    // Telea fast marching
    // ---------------------------------------------------------------------------------------------

    /**
     * Fills [hole] from its boundary inwards in order of arrival time T. Each pixel is a weighted
     * average of already-known pixels within [radius]; the pixel itself is excluded (including it
     * — while still uninitialised — is what produced black blobs before).
     */
    private fun teleaFill(src: IntArray, hole: BooleanArray, w: Int, h: Int, radius: Int): IntArray {
        val n = w * h
        val known = BooleanArray(n) { !hole[it] }
        val t = FloatArray(n) { if (hole[it]) Float.MAX_VALUE else 0f }
        val a = FloatArray(n); val rr = FloatArray(n); val gg = FloatArray(n); val bb = FloatArray(n)
        for (i in 0 until n) {
            if (!hole[i]) {
                val p = src[i]
                a[i] = ((p ushr 24) and 0xFF).toFloat()
                rr[i] = ((p ushr 16) and 0xFF).toFloat()
                gg[i] = ((p ushr 8) and 0xFF).toFloat()
                bb[i] = (p and 0xFF).toFloat()
            }
        }
        if (known.none { it }) return src.copyOf()

        val band = PriorityQueue<Int>(1024) { x, y -> t[x].compareTo(t[y]) }
        val dx4 = intArrayOf(1, -1, 0, 0)
        val dy4 = intArrayOf(0, 0, 1, -1)
        for (i in 0 until n) {
            if (!hole[i]) continue
            val x = i % w; val y = i / w
            for (k in 0 until 4) {
                val nx = x + dx4[k]; val ny = y + dy4[k]
                if (nx in 0 until w && ny in 0 until h && known[ny * w + nx]) {
                    t[i] = 1f
                    band.add(i)
                    break
                }
            }
        }

        val rSq = radius * radius
        while (band.isNotEmpty()) {
            val p = band.poll() ?: break
            if (known[p]) continue
            val px = p % w; val py = p / w

            var sa = 0f; var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
            for (dy in -radius..radius) {
                val qy = py + dy
                if (qy !in 0 until h) continue
                for (dx in -radius..radius) {
                    if (dx == 0 && dy == 0) continue
                    val d2 = dx * dx + dy * dy
                    if (d2 > rSq) continue
                    val qx = px + dx
                    if (qx !in 0 until w) continue
                    val q = qy * w + qx
                    if (!known[q]) continue
                    // Distance term × level-set term (pixels on the same "front" weigh more).
                    val wDist = 1f / d2
                    val wLevel = 1f / (1f + abs(t[q] - t[p]))
                    val wt = wDist * wLevel
                    sa += wt * a[q]; sr += wt * rr[q]; sg += wt * gg[q]; sb += wt * bb[q]; sw += wt
                }
            }
            if (sw > 0f) {
                a[p] = sa / sw; rr[p] = sr / sw; gg[p] = sg / sw; bb[p] = sb / sw
            }
            known[p] = true

            for (k in 0 until 4) {
                val nx = px + dx4[k]; val ny = py + dy4[k]
                if (nx !in 0 until w || ny !in 0 until h) continue
                val nIdx = ny * w + nx
                if (known[nIdx]) continue
                val newT = solveEikonal(nx, ny, w, h, t, known)
                if (newT < t[nIdx]) {
                    t[nIdx] = newT
                    band.add(nIdx)
                }
            }
        }

        val out = src.copyOf()
        for (i in 0 until n) {
            if (!hole[i]) continue
            out[i] = (a[i].roundToInt().coerceIn(0, 255) shl 24) or (rr[i].roundToInt().coerceIn(0, 255) shl 16) or
                (gg[i].roundToInt().coerceIn(0, 255) shl 8) or bb[i].roundToInt().coerceIn(0, 255)
        }
        return out
    }

    private fun solveEikonal(x: Int, y: Int, w: Int, h: Int, t: FloatArray, known: BooleanArray): Float {
        var tx = Float.MAX_VALUE
        if (x > 0 && known[y * w + x - 1]) tx = min(tx, t[y * w + x - 1])
        if (x < w - 1 && known[y * w + x + 1]) tx = min(tx, t[y * w + x + 1])
        var ty = Float.MAX_VALUE
        if (y > 0 && known[(y - 1) * w + x]) ty = min(ty, t[(y - 1) * w + x])
        if (y < h - 1 && known[(y + 1) * w + x]) ty = min(ty, t[(y + 1) * w + x])
        return when {
            tx != Float.MAX_VALUE && ty != Float.MAX_VALUE -> {
                val d = abs(tx - ty)
                if (d < 1f) (tx + ty + sqrt(2f - d * d)) / 2f else min(tx, ty) + 1f
            }
            tx != Float.MAX_VALUE -> tx + 1f
            ty != Float.MAX_VALUE -> ty + 1f
            else -> Float.MAX_VALUE
        }
    }
}
