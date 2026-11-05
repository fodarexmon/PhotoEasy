package com.example.engine

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class RefineEdgeConfig(
    val radiusPx: Int = 10,
    val smartRadius: Boolean = true,
    val hairRefine: Boolean = true,
    val smoothPx: Int = 2,
    val featherPx: Float = 0f,
    val contrastPercent: Float = 20f,
    val shiftEdgePercent: Float = 0f,
    // Off by default: decontamination rewrites the layer's pixels, so it must be an explicit choice.
    val decontaminateColors: Boolean = false,
    val decontaminateAmount: Float = 0.8f
)

/**
 * [refinedMask] is an ALPHA_8 bitmap the size of the source (alpha = selection strength).
 * [decontaminatedSource] is a copy of the source with background colour removed from edge pixels
 * (original transparency preserved), or null when decontamination was not requested.
 */
data class RefineResult(
    val refinedMask: Bitmap,
    val decontaminatedSource: Bitmap? = null
)

/**
 * Edge refinement and hair matting ("Refine Edge" / "Select and Mask").
 *
 * 1. Shift Edge grows/shrinks the input selection (separable min/max filter, O(N)).
 * 2. Trimap: definite foreground, definite background and an UNKNOWN band of [RefineEdgeConfig.radiusPx]
 *    around the edge in every direction (dilate − erode). With Smart Radius the wide band is only
 *    kept where the image is textured (hair, fur); smooth edges keep a narrow band.
 * 3. Alpha estimation for every unknown pixel by colour sampling (in the spirit of Shared/Global
 *    Sampling matting): real foreground and background samples are collected along 8 directions,
 *    and the (F, B) pair that best explains the pixel colour C ≈ αF + (1−α)B is chosen.
 * 4. A guided filter (He et al.), with the image as guide, cleans the estimate while keeping thin
 *    structures such as hair strands that follow image edges.
 * 5. Smooth / Contrast / Feather on the result; optional colour decontamination F = (C − (1−α)B)/α.
 *
 * Memory: only per-pixel bytes are kept for the whole image (alpha + trimap); colour work happens in
 * tiles around the edge band, so 12-megapixel photos stay within a few tens of MB.
 */
object RefineEdgeEngine {

    private const val TILE = 384
    private const val TRI_BG: Byte = 0
    private const val TRI_UNKNOWN: Byte = 1
    private const val TRI_FG: Byte = 2
    /** Wide-band candidate (Smart Radius); resolved to UNKNOWN / FG / BG by local texture. */
    private const val TRI_CANDIDATE: Byte = 3

    fun refineEdges(
        source: Bitmap,
        mask: Bitmap,
        config: RefineEdgeConfig
    ): RefineResult {
        require(source.width == mask.width && source.height == mask.height) {
            "Source and mask dimensions must match"
        }
        val w = source.width
        val h = source.height
        val n = w * h

        // --- 1. Input alpha (+ Shift Edge) -------------------------------------------------------
        var alpha = readMaskAlpha(mask)
        if (abs(config.shiftEdgePercent) > 1f) {
            val shift = (abs(config.shiftEdgePercent) / 100f * 15f).roundToInt().coerceIn(1, 15)
            alpha = morphBytes(alpha, w, h, shift, grow = config.shiftEdgePercent > 0f)
        }

        // --- 2. Trimap ---------------------------------------------------------------------------
        val radius = config.radiusPx.coerceIn(1, 64)
        val tri = buildTrimap(alpha, w, h, radius, config.smartRadius)

        // Bounding box of the band; nothing to refine → only the global post-processing applies.
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val t = tri[row + x]
                if (t == TRI_UNKNOWN || t == TRI_CANDIDATE) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        // Output alpha starts canonical: definite regions are exactly 0 / 255.
        val out = ByteArray(n)
        for (i in 0 until n) {
            out[i] = when (tri[i]) {
                TRI_FG -> 0xFF.toByte()
                TRI_BG -> 0.toByte()
                else -> alpha[i]
            }
        }

        val decontaminated: Bitmap? = if (config.decontaminateColors) source.copy(Bitmap.Config.ARGB_8888, true) else null

        if (maxX >= 0) {
            val maxWalk = (radius * 2 + 8).coerceIn(12, 96)
            val guideRadius = (radius / 2).coerceIn(2, 16)
            // Small eps keeps individual strands; larger eps gives smoother, cleaner edges.
            val eps = if (config.hairRefine) 1.5e-4f else 2e-3f

            // Pass A: resolve Smart-Radius candidates by local texture.
            if (config.smartRadius) {
                forEachTile(minX, minY, maxX, maxY, margin = 3, w = w, h = h) { cx0, cy0, cx1, cy1, rx0, ry0, rw, rh ->
                    val px = IntArray(rw * rh)
                    source.getPixels(px, 0, rw, rx0, ry0, rw, rh)
                    resolveCandidates(px, rx0, ry0, rw, rh, cx0, cy0, cx1, cy1, tri, alpha, w)
                }
            }

            // Pass B: sampling + guided filter, written into `out` for the tile core.
            forEachTile(minX, minY, maxX, maxY, margin = maxWalk + guideRadius + 2, w = w, h = h) { cx0, cy0, cx1, cy1, rx0, ry0, rw, rh ->
                // Skip tiles without unknown pixels in their core.
                var any = false
                loop@ for (y in cy0..cy1) for (x in cx0..cx1) if (tri[y * w + x] == TRI_UNKNOWN) { any = true; break@loop }
                if (!any) return@forEachTile
                val px = IntArray(rw * rh)
                source.getPixels(px, 0, rw, rx0, ry0, rw, rh)
                refineTile(
                    px, rx0, ry0, rw, rh, cx0, cy0, cx1, cy1,
                    tri, alpha, out, w, maxWalk, guideRadius, eps,
                    decontaminated, config.decontaminateAmount
                )
            }
        }

        // --- 5. Global post-processing -----------------------------------------------------------
        var result = out
        if (config.smoothPx > 0) result = boxBlurBytes(result, w, h, config.smoothPx.coerceIn(1, 40))
        if (config.contrastPercent > 0f) {
            val factor = 1f + (config.contrastPercent / 40f)
            for (i in 0 until n) {
                val v = result[i].toInt() and 0xFF
                if (v in 3..252) {
                    val a = v / 255f
                    result[i] = (((a - 0.5f) * factor + 0.5f).coerceIn(0f, 1f) * 255f).roundToInt().toByte()
                }
            }
        }
        if (config.featherPx > 0.5f) {
            // Two box passes ≈ a Gaussian of the requested radius.
            val r = (config.featherPx / 2f).roundToInt().coerceAtLeast(1)
            result = boxBlurBytes(boxBlurBytes(result, w, h, r), w, h, r)
        }

        val maskOut = SelectionEngine.alphaMaskFromPacked(result, w, h)
        return RefineResult(maskOut, decontaminated)
    }

    // ---------------------------------------------------------------------------------------------
    // Input / trimap
    // ---------------------------------------------------------------------------------------------

    /** Mask strength per pixel: ALPHA_8 alpha, ARGB alpha, or gray value for opaque masks. */
    private fun readMaskAlpha(mask: Bitmap): ByteArray {
        if (mask.config == Bitmap.Config.ALPHA_8) return SelectionEngine.alphaBytesPacked(mask)
        val w = mask.width
        val h = mask.height
        val out = ByteArray(w * h)
        val row = IntArray(w)
        val useAlpha = mask.hasAlpha()
        for (y in 0 until h) {
            mask.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                out[y * w + x] = (if (useAlpha) (p ushr 24) and 0xFF else (p shr 16) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun buildTrimap(alpha: ByteArray, w: Int, h: Int, radius: Int, smart: Boolean): ByteArray {
        val n = w * h
        val fg = BooleanArray(n) { (alpha[it].toInt() and 0xFF) >= 128 }
        val soft = BooleanArray(n) { val v = alpha[it].toInt() and 0xFF; v in 13..242 }
        val dilR = dilate(fg, w, h, radius)
        val eroR = erode(fg, w, h, radius)
        val narrow: BooleanArray? = if (smart) {
            val rn = max(1, radius / 4)
            val d = dilate(fg, w, h, rn)
            val e = erode(fg, w, h, rn)
            BooleanArray(n) { d[it] && !e[it] }
        } else null
        // Soft (already semi-transparent) pixels always stay in the band, plus a small ring around them.
        val softRing = dilate(soft, w, h, 2)
        val tri = ByteArray(n)
        for (i in 0 until n) {
            tri[i] = when {
                softRing[i] -> TRI_UNKNOWN
                eroR[i] -> TRI_FG
                !dilR[i] -> TRI_BG
                narrow == null -> TRI_UNKNOWN
                narrow[i] -> TRI_UNKNOWN
                else -> TRI_CANDIDATE
            }
        }
        return tri
    }

    /**
     * Smart Radius: a wide-band candidate stays UNKNOWN only where the image is textured (local
     * gradient energy), otherwise it snaps back to the binary selection (FG / BG).
     */
    private fun resolveCandidates(
        px: IntArray, rx0: Int, ry0: Int, rw: Int, rh: Int,
        cx0: Int, cy0: Int, cx1: Int, cy1: Int,
        tri: ByteArray, alpha: ByteArray, w: Int
    ) {
        val lum = FloatArray(rw * rh)
        for (i in lum.indices) {
            val p = px[i]
            lum[i] = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
        val threshold = 14f
        for (y in cy0..cy1) {
            for (x in cx0..cx1) {
                val gi = y * w + x
                if (tri[gi] != TRI_CANDIDATE) continue
                val lx = x - rx0
                val ly = y - ry0
                var energy = 0f
                var count = 0
                for (dy in -2..2) {
                    val yy = ly + dy
                    if (yy < 1 || yy >= rh - 1) continue
                    for (dx in -2..2) {
                        val xx = lx + dx
                        if (xx < 1 || xx >= rw - 1) continue
                        val c = yy * rw + xx
                        energy += abs(lum[c + 1] - lum[c - 1]) + abs(lum[c + rw] - lum[c - rw])
                        count++
                    }
                }
                val textured = count > 0 && energy / count > threshold
                tri[gi] = when {
                    textured -> TRI_UNKNOWN
                    (alpha[gi].toInt() and 0xFF) >= 128 -> TRI_FG
                    else -> TRI_BG
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Matting in one tile
    // ---------------------------------------------------------------------------------------------

    private fun refineTile(
        px: IntArray, rx0: Int, ry0: Int, rw: Int, rh: Int,
        cx0: Int, cy0: Int, cx1: Int, cy1: Int,
        tri: ByteArray, inAlpha: ByteArray, out: ByteArray, w: Int,
        maxWalk: Int, guideRadius: Int, eps: Float,
        decontaminated: Bitmap?, decontamAmount: Float
    ) {
        val rn = rw * rh
        // p = initial alpha for the guided filter; F/B colours of the chosen sample pair.
        val p = FloatArray(rn)
        val fgColor = IntArray(rn)
        val bgColor = IntArray(rn)
        val estimated = BooleanArray(rn)

        val dirs = 8
        val cosT = FloatArray(dirs * 2)
        val sinT = FloatArray(dirs * 2)
        for (k in 0 until dirs * 2) {
            val a = (Math.PI * 2.0 * k / (dirs * 2)).toFloat()
            cosT[k] = cos(a); sinT[k] = sin(a)
        }
        val fgS = IntArray(dirs); val fgD = FloatArray(dirs)
        val bgS = IntArray(dirs); val bgD = FloatArray(dirs)

        for (ly in 0 until rh) {
            val gy = ry0 + ly
            for (lx in 0 until rw) {
                val gx = rx0 + lx
                val gi = gy * w + gx
                val li = ly * rw + lx
                when (tri[gi]) {
                    TRI_FG -> { p[li] = 1f; continue }
                    TRI_BG -> { p[li] = 0f; continue }
                    TRI_CANDIDATE -> { p[li] = if ((inAlpha[gi].toInt() and 0xFF) >= 128) 1f else 0f; continue }
                    else -> {}
                }
                // Collect the first definite FG and BG pixel along each direction. The direction
                // set is rotated per pixel (odd/even) so neighbours sample different rays.
                val rot = (gx + gy) and 1
                var nf = 0
                var nb = 0
                for (d in 0 until dirs) {
                    val k = d * 2 + rot
                    var foundF = false
                    var foundB = false
                    var s = 1
                    while (s <= maxWalk && !(foundF && foundB)) {
                        val sx = lx + (cosT[k] * s).roundToInt()
                        val sy = ly + (sinT[k] * s).roundToInt()
                        if (sx < 0 || sy < 0 || sx >= rw || sy >= rh) break
                        val t = tri[(ry0 + sy) * w + (rx0 + sx)]
                        if (!foundF && t == TRI_FG) { fgS[nf] = px[sy * rw + sx]; fgD[nf] = s.toFloat(); nf++; foundF = true }
                        else if (!foundB && t == TRI_BG) { bgS[nb] = px[sy * rw + sx]; bgD[nb] = s.toFloat(); nb++; foundB = true }
                        s += if (s < 8) 1 else 2
                    }
                }
                val initial = (inAlpha[gi].toInt() and 0xFF) / 255f
                if (nf == 0 || nb == 0) {
                    // Only one side reachable: lean on it, otherwise keep the input value.
                    p[li] = when {
                        nf > 0 && nb == 0 -> max(initial, 0.85f)
                        nb > 0 && nf == 0 -> min(initial, 0.15f)
                        else -> initial
                    }
                    continue
                }
                val c = px[li]
                val cr = ((c shr 16) and 0xFF).toFloat(); val cg = ((c shr 8) and 0xFF).toFloat(); val cb = (c and 0xFF).toFloat()
                var bestCost = Float.MAX_VALUE
                var bestA = initial
                var bestF = fgS[0]
                var bestB = bgS[0]
                var minFd = Float.MAX_VALUE; var minBd = Float.MAX_VALUE
                for (i in 0 until nf) if (fgD[i] < minFd) minFd = fgD[i]
                for (j in 0 until nb) if (bgD[j] < minBd) minBd = bgD[j]
                for (i in 0 until nf) {
                    val f = fgS[i]
                    val fr = ((f shr 16) and 0xFF).toFloat(); val fgc = ((f shr 8) and 0xFF).toFloat(); val fb = (f and 0xFF).toFloat()
                    for (j in 0 until nb) {
                        val b = bgS[j]
                        val br = ((b shr 16) and 0xFF).toFloat(); val bgc = ((b shr 8) and 0xFF).toFloat(); val bb = (b and 0xFF).toFloat()
                        val dr = fr - br; val dg = fgc - bgc; val db = fb - bb
                        val denom = dr * dr + dg * dg + db * db
                        if (denom < 64f) continue // F and B indistinguishable: this pair tells nothing
                        val a = (((cr - br) * dr + (cg - bgc) * dg + (cb - bb) * db) / denom).coerceIn(0f, 1f)
                        // Chromatic distortion: how well αF + (1−α)B reproduces C, relative to |F−B|.
                        val er = cr - (a * fr + (1 - a) * br)
                        val eg = cg - (a * fgc + (1 - a) * bgc)
                        val eb = cb - (a * fb + (1 - a) * bb)
                        val distortion = sqrt((er * er + eg * eg + eb * eb) / denom)
                        // Prefer near samples (spatial coherence).
                        val spatial = fgD[i] / minFd + bgD[j] / minBd
                        val cost = distortion * 3f + spatial * 0.15f
                        if (cost < bestCost) { bestCost = cost; bestA = a; bestF = f; bestB = b }
                    }
                }
                p[li] = if (bestCost == Float.MAX_VALUE) initial else bestA
                fgColor[li] = bestF
                bgColor[li] = bestB
                estimated[li] = bestCost != Float.MAX_VALUE
            }
        }

        // Colour-guided filter (He, Sun, Tang). A luminance-only guide would wash out strands whose
        // colour differs from the background but whose brightness does not (e.g. red hair on blue).
        val q = colorGuidedFilter(px, p, rw, rh, guideRadius, eps)

        // Write the tile core back; decontaminate edge colours if requested.
        val decoRow = if (decontaminated != null) IntArray(cx1 - cx0 + 1) else null
        for (gy in cy0..cy1) {
            val ly = gy - ry0
            var rowChanged = false
            for (gx in cx0..cx1) {
                val gi = gy * w + gx
                if (tri[gi] != TRI_UNKNOWN) {
                    decoRow?.set(gx - cx0, px[ly * rw + (gx - rx0)])
                    continue
                }
                val li = ly * rw + (gx - rx0)
                val a = q[li].coerceIn(0f, 1f)
                out[gi] = (a * 255f).roundToInt().coerceIn(0, 255).toByte()
                if (decoRow != null) {
                    val c = px[li]
                    decoRow[gx - cx0] = if (estimated[li] && a in 0.02f..0.98f) {
                        rowChanged = true
                        decontaminate(c, fgColor[li], bgColor[li], a, decontamAmount)
                    } else c
                }
            }
            if (decoRow != null && rowChanged) {
                decontaminated!!.setPixels(decoRow, 0, decoRow.size, cx0, gy, decoRow.size, 1)
            }
        }
    }

    /** F = (C − (1−α)B) / α, stabilised with the sampled F for low α; source alpha is preserved. */
    private fun decontaminate(c: Int, f: Int, b: Int, a: Float, amount: Float): Int {
        val k = amount.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val cv = ((c shr shift) and 0xFF).toFloat()
            val fv = ((f shr shift) and 0xFF).toFloat()
            val bv = ((b shr shift) and 0xFF).toFloat()
            val solved = ((cv - (1f - a) * bv) / a.coerceAtLeast(0.05f)).coerceIn(0f, 255f)
            val est = if (a > 0.3f) solved * 0.6f + fv * 0.4f else fv
            return (cv + (est - cv) * k).roundToInt().coerceIn(0, 255)
        }
        return (c and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Colour guided filter: q = aᵀI + b with a = (Σ + εU)⁻¹ cov(I, p) per window. O(N) via
     * integral images. Edges of the output follow colour edges of the image, which is what keeps
     * hair strands and fur separated from the background.
     */
    private fun colorGuidedFilter(px: IntArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val ir = FloatArray(n); val ig = FloatArray(n); val ib = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]
            ir[i] = ((c shr 16) and 0xFF) / 255f
            ig[i] = ((c shr 8) and 0xFF) / 255f
            ib[i] = (c and 0xFF) / 255f
        }
        fun prod(a: FloatArray, b: FloatArray) = FloatArray(n) { a[it] * b[it] }
        val mR = boxMean(ir, w, h, r); val mG = boxMean(ig, w, h, r); val mB = boxMean(ib, w, h, r)
        val mP = boxMean(p, w, h, r)
        val mRP = boxMean(prod(ir, p), w, h, r)
        val mGP = boxMean(prod(ig, p), w, h, r)
        val mBP = boxMean(prod(ib, p), w, h, r)
        val vRR = boxMean(prod(ir, ir), w, h, r)
        val vRG = boxMean(prod(ir, ig), w, h, r)
        val vRB = boxMean(prod(ir, ib), w, h, r)
        val vGG = boxMean(prod(ig, ig), w, h, r)
        val vGB = boxMean(prod(ig, ib), w, h, r)
        val vBB = boxMean(prod(ib, ib), w, h, r)

        val aR = FloatArray(n); val aG = FloatArray(n); val aB = FloatArray(n); val bb = FloatArray(n)
        for (i in 0 until n) {
            val covR = mRP[i] - mR[i] * mP[i]
            val covG = mGP[i] - mG[i] * mP[i]
            val covB = mBP[i] - mB[i] * mP[i]
            // Σ + εU (symmetric 3×3)
            val s11 = vRR[i] - mR[i] * mR[i] + eps
            val s12 = vRG[i] - mR[i] * mG[i]
            val s13 = vRB[i] - mR[i] * mB[i]
            val s22 = vGG[i] - mG[i] * mG[i] + eps
            val s23 = vGB[i] - mG[i] * mB[i]
            val s33 = vBB[i] - mB[i] * mB[i] + eps
            // Adjugate / determinant inverse.
            val i11 = s22 * s33 - s23 * s23
            val i12 = s13 * s23 - s12 * s33
            val i13 = s12 * s23 - s13 * s22
            val i22 = s11 * s33 - s13 * s13
            val i23 = s12 * s13 - s11 * s23
            val i33 = s11 * s22 - s12 * s12
            val det = s11 * i11 + s12 * i12 + s13 * i13
            if (abs(det) < 1e-18f) {
                aR[i] = 0f; aG[i] = 0f; aB[i] = 0f; bb[i] = mP[i]
            } else {
                val inv = 1f / det
                aR[i] = (i11 * covR + i12 * covG + i13 * covB) * inv
                aG[i] = (i12 * covR + i22 * covG + i23 * covB) * inv
                aB[i] = (i13 * covR + i23 * covG + i33 * covB) * inv
                bb[i] = mP[i] - aR[i] * mR[i] - aG[i] * mG[i] - aB[i] * mB[i]
            }
        }
        val maR = boxMean(aR, w, h, r); val maG = boxMean(aG, w, h, r); val maB = boxMean(aB, w, h, r)
        val mb = boxMean(bb, w, h, r)
        return FloatArray(n) { maR[it] * ir[it] + maG[it] * ig[it] + maB[it] * ib[it] + mb[it] }
    }

    private fun boxMean(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val sat = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0.0
            for (x in 0 until w) {
                rowSum += src[y * w + x]
                sat[(y + 1) * (w + 1) + (x + 1)] = sat[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r); val y1 = min(h - 1, y + r) + 1
            for (x in 0 until w) {
                val x0 = max(0, x - r); val x1 = min(w - 1, x + r) + 1
                val sum = sat[y1 * (w + 1) + x1] - sat[y0 * (w + 1) + x1] - sat[y1 * (w + 1) + x0] + sat[y0 * (w + 1) + x0]
                out[y * w + x] = (sum / ((x1 - x0) * (y1 - y0))).toFloat()
            }
        }
        return out
    }

    /**
     * Iterates TILE×TILE cores covering [minX..maxX]×[minY..maxY]; each call gets the core bounds
     * (inclusive) and the region read around it (core + margin, clamped to the image).
     */
    private inline fun forEachTile(
        minX: Int, minY: Int, maxX: Int, maxY: Int, margin: Int, w: Int, h: Int,
        block: (cx0: Int, cy0: Int, cx1: Int, cy1: Int, rx0: Int, ry0: Int, rw: Int, rh: Int) -> Unit
    ) {
        var ty = minY
        while (ty <= maxY) {
            val cy1 = min(maxY, ty + TILE - 1)
            var tx = minX
            while (tx <= maxX) {
                val cx1 = min(maxX, tx + TILE - 1)
                val rx0 = max(0, tx - margin)
                val ry0 = max(0, ty - margin)
                val rx1 = min(w - 1, cx1 + margin)
                val ry1 = min(h - 1, cy1 + margin)
                block(tx, ty, cx1, cy1, rx0, ry0, rx1 - rx0 + 1, ry1 - ry0 + 1)
                tx = cx1 + 1
            }
            ty = cy1 + 1
        }
    }

    // ---------------------------------------------------------------------------------------------
    // O(N) morphology / blur helpers on full-image arrays
    // ---------------------------------------------------------------------------------------------

    /** Square (chessboard) dilation via separable running counts. */
    private fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return src.copyOf()
        val tmp = BooleanArray(w * h)
        val prefix = IntArray(max(w, h) + 1)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) prefix[x + 1] = prefix[x] + if (src[row + x]) 1 else 0
            for (x in 0 until w) {
                val a = max(0, x - r); val b = min(w - 1, x + r)
                tmp[row + x] = prefix[b + 1] - prefix[a] > 0
            }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) prefix[y + 1] = prefix[y] + if (tmp[y * w + x]) 1 else 0
            for (y in 0 until h) {
                val a = max(0, y - r); val b = min(h - 1, y + r)
                out[y * w + x] = prefix[b + 1] - prefix[a] > 0
            }
        }
        return out
    }

    private fun erode(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val inv = BooleanArray(w * h) { !src[it] }
        val d = dilate(inv, w, h, r)
        return BooleanArray(w * h) { !d[it] }
    }

    /** Grey-level max (grow) / min (shrink) filter with a monotonic deque, O(N). */
    private fun morphBytes(data: ByteArray, w: Int, h: Int, r: Int, grow: Boolean): ByteArray {
        val tmp = ByteArray(w * h)
        val out = ByteArray(w * h)
        val dq = IntArray(max(w, h))
        fun better(a: Int, b: Int) = if (grow) a >= b else a <= b
        for (y in 0 until h) {
            val row = y * w
            var head = 0; var tail = 0; var added = -1
            for (x in 0 until w) {
                val end = min(w - 1, x + r)
                while (added < end) {
                    added++
                    val v = data[row + added].toInt() and 0xFF
                    while (tail > head && better(v, data[row + dq[tail - 1]].toInt() and 0xFF)) tail--
                    dq[tail++] = added
                }
                while (dq[head] < x - r) head++
                tmp[row + x] = data[row + dq[head]]
            }
        }
        for (x in 0 until w) {
            var head = 0; var tail = 0; var added = -1
            for (y in 0 until h) {
                val end = min(h - 1, y + r)
                while (added < end) {
                    added++
                    val v = tmp[added * w + x].toInt() and 0xFF
                    while (tail > head && better(v, tmp[dq[tail - 1] * w + x].toInt() and 0xFF)) tail--
                    dq[tail++] = added
                }
                while (dq[head] < y - r) head++
                out[y * w + x] = tmp[dq[head] * w + x]
            }
        }
        return out
    }

    /** Separable running-sum box blur on bytes, O(N) for any radius. */
    private fun boxBlurBytes(data: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val tmp = ByteArray(w * h)
        val out = ByteArray(w * h)
        val prefix = IntArray(max(w, h) + 1)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) prefix[x + 1] = prefix[x] + (data[row + x].toInt() and 0xFF)
            for (x in 0 until w) {
                val a = max(0, x - r); val b = min(w - 1, x + r)
                tmp[row + x] = ((prefix[b + 1] - prefix[a]) / (b - a + 1)).toByte()
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) prefix[y + 1] = prefix[y] + (tmp[y * w + x].toInt() and 0xFF)
            for (y in 0 until h) {
                val a = max(0, y - r); val b = min(h - 1, y + r)
                out[y * w + x] = ((prefix[b + 1] - prefix[a]) / (b - a + 1)).toByte()
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Fallback subject selection (used when ML Kit is unavailable)
    // ---------------------------------------------------------------------------------------------

    /**
     * One-Tap Subject Selection fallback: colour saliency against the border colour plus a central
     * prior, then refined with the matting above.
     */
    fun selectSubject(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val total = w * h
        val pixels = IntArray(total)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1. Background colour model from the 4 image borders (10% outer margin)
        var bgSumR = 0.0; var bgSumG = 0.0; var bgSumB = 0.0; var bgCount = 0
        val marginX = (w * 0.1f).toInt().coerceAtLeast(2)
        val marginY = (h * 0.1f).toInt().coerceAtLeast(2)
        for (y in 0 until h step 2) {
            val row = y * w
            for (x in 0 until w step 2) {
                if (x < marginX || x >= w - marginX || y < marginY || y >= h - marginY) {
                    val p = pixels[row + x]
                    bgSumR += ((p ushr 16) and 0xFF)
                    bgSumG += ((p ushr 8) and 0xFF)
                    bgSumB += (p and 0xFF)
                    bgCount++
                }
            }
        }
        val bgMeanR = if (bgCount > 0) bgSumR / bgCount else 128.0
        val bgMeanG = if (bgCount > 0) bgSumG / bgCount else 128.0
        val bgMeanB = if (bgCount > 0) bgSumB / bgCount else 128.0

        // 2. Saliency weighted by a central prior
        val saliency = FloatArray(total)
        val centerX = w / 2f
        val centerY = h / 2f
        val maxDistSq = (centerX * centerX + centerY * centerY)
        var maxSal = 0.001f
        for (y in 0 until h) {
            val row = y * w
            val dy = y - centerY
            for (x in 0 until w) {
                val idx = row + x
                val p = pixels[idx]
                val dR = ((p ushr 16) and 0xFF) - bgMeanR
                val dG = ((p ushr 8) and 0xFF) - bgMeanG
                val dB = (p and 0xFF) - bgMeanB
                val colorDist = sqrt(dR * dR + dG * dG + dB * dB).toFloat()
                val dx = x - centerX
                val centerWeight = (1.0f - ((dx * dx + dy * dy) / maxDistSq) * 0.65f).coerceIn(0.1f, 1.0f)
                val sal = colorDist * centerWeight
                saliency[idx] = sal
                if (sal > maxSal) maxSal = sal
            }
        }

        // 3. Threshold → binary mask → matting refinement
        val threshold = maxSal * 0.35f
        val maskPixels = IntArray(total) { if (saliency[it] >= threshold) 0xFFFFFFFF.toInt() else 0 }
        val subjectMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        subjectMask.setPixels(maskPixels, 0, w, 0, 0, w, h)

        val refined = refineEdges(
            source = source,
            mask = subjectMask,
            config = RefineEdgeConfig(radiusPx = 6, smoothPx = 2, hairRefine = true, contrastPercent = 30f, decontaminateColors = false)
        )
        subjectMask.recycle()
        refined.decontaminatedSource?.let { if (!it.isRecycled) it.recycle() }
        return refined.refinedMask
    }
}
