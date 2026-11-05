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
        mode: InpaintEngineMode = InpaintEngineMode.AI_PATCH_SYNTHESIS,
        smartEdgeSnap: Boolean = true,
        seamlessBlend: Boolean = true,
        featherPx: Int = 4,
        radiusPx: Double = 5.0
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

        // Grow the hole slightly so anti-aliased object edges / halos are removed too.
        val grow = if (smartEdgeSnap) 3 else 1
        val feather = if (seamlessBlend) featherPx.coerceIn(1, 24) else 0
        val reach = grow + feather

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
        val hole = BooleanArray(n) { dist[it] <= reach }

        val fill = when (mode) {
            InpaintEngineMode.AI_PATCH_SYNTHESIS -> patchSynthesis(srcPixels, hole, cw, ch)
            InpaintEngineMode.FAST_MARCHING -> teleaFill(srcPixels, hole, cw, ch, radiusPx.roundToInt().coerceIn(2, 15))
        }

        // Composite: full replacement inside the grown hole, cross-fade across the feather ring.
        val out = srcPixels.copyOf()
        for (i in 0 until n) {
            if (!hole[i]) continue
            val d = dist[i]
            val t = if (d <= grow || feather == 0) 1f else 1f - (d - grow).toFloat() / (feather + 1).toFloat()
            out[i] = if (t >= 0.999f) fill[i] else lerpArgb(srcPixels[i], fill[i], t)
        }
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        result.setPixels(out, 0, cw, cropX0, cropY0, cw, ch)
        return result
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
    private class Level(val w: Int, val h: Int, val img: FloatArray, val hole: BooleanArray)

    private fun patchSynthesis(src: IntArray, hole: BooleanArray, cw: Int, ch: Int): IntArray {
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

        // Pyramid (index 0 = working / finest).
        val pyramid = ArrayList<Level>()
        pyramid.add(working)
        while (pyramid.size < 7) {
            val cur = pyramid.last()
            if (max(cur.w, cur.h) <= COARSEST_DIM || min(cur.w, cur.h) < 4 * PATCH_RADIUS + 4) break
            val next = resampleLevel(cur.img, cur.hole, cur.w, cur.h, (cur.w + 1) / 2, (cur.h + 1) / 2)
            if (next.hole.all { it }) break // no known context left at this scale
            pyramid.add(next)
        }

        val rnd = Random(0x5EED)
        var prevNnf: IntArray? = null
        var prevW = 0
        var prevH = 0
        for (li in pyramid.indices.reversed()) {
            val level = pyramid[li]
            if (li == pyramid.lastIndex) {
                diffusionFill(level)
            } else {
                upsampleInto(pyramid[li + 1], level)
            }
            val emIterations = when {
                li == pyramid.lastIndex -> 6
                li == 0 -> 2
                else -> 3
            }
            prevNnf = completeLevel(level, emIterations, prevNnf, prevW, prevH, rnd)
            prevW = level.w
            prevH = level.h
        }

        // Back to full crop resolution (hole pixels only).
        val fin = pyramid[0]
        val out = src.copyOf()
        if (fin.w == cw && fin.h == ch) {
            for (i in 0 until cw * ch) if (hole[i]) out[i] = packArgb(fin.img, i)
        } else {
            val sx = fin.w.toFloat() / cw
            val sy = fin.h.toFloat() / ch
            val tmp = FloatArray(4)
            for (y in 0 until ch) {
                for (x in 0 until cw) {
                    val i = y * cw + x
                    if (!hole[i]) continue
                    sampleBilinear(fin, (x + 0.5f) * sx - 0.5f, (y + 0.5f) * sy - 0.5f, tmp)
                    out[i] = (tmp[0].roundToInt().coerceIn(0, 255) shl 24) or (tmp[1].roundToInt().coerceIn(0, 255) shl 16) or
                        (tmp[2].roundToInt().coerceIn(0, 255) shl 8) or tmp[3].roundToInt().coerceIn(0, 255)
                }
            }
        }
        return out
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
     * patches. Returns the nearest-neighbour field (centre index per pixel, -1 = none).
     */
    private fun completeLevel(
        level: Level,
        emIterations: Int,
        prevNnf: IntArray?,
        prevW: Int,
        prevH: Int,
        rnd: Random
    ): IntArray? {
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

        val validSrc = BooleanArray(w * h)
        val srcList = ArrayList<Int>()
        val targets = ArrayList<Int>()
        for (y in r until h - r) {
            for (x in r until w - r) {
                val c = holeCount(x, y)
                val i = y * w + x
                if (c == 0) { validSrc[i] = true; srcList.add(i) } else targets.add(i)
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
            for (t in targets) {
                val q = nnf[t]
                val weight = exp(-distArr[t] / sigma).coerceAtLeast(1e-6f)
                for (o in offsets) {
                    val pi = t + o
                    if (!hole[pi]) continue
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
        return nnf
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
