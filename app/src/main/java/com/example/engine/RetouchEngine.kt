package com.example.engine

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class RetouchTool { BLUR, SHARPEN, SMUDGE, DODGE, BURN, CLONE, PATCH }

enum class ToneRange { SHADOWS, MIDTONES, HIGHLIGHTS }

/** Brush options of the retouch tools (strength / hardness 0..1). */
data class RetouchBrush(
    val hardness: Float = 0.5f,
    val strength: Float = 0.5f,
    val range: ToneRange = ToneRange.MIDTONES,
    /** Dodge / burn: change lightness only, keeping hue and colourfulness (Photoshop "Protect Tones"). */
    val protectTones: Boolean = true
)

/**
 * Photoshop-style retouch brushes on a layer's own full-resolution pixels ([pix], ARGB, w x h).
 *
 * - Blur / Sharpen / Smudge build up with every dab, as in Photoshop.
 * - Dodge / Burn / Clone Stamp do not build up inside one stroke: each pixel gets at most the
 *   brush's strength per stroke (coverage), computed from the pixels as they were when the stroke
 *   started, so going over the same spot does not darken / duplicate it more and more.
 * - Patch replaces a selected area with texture from elsewhere and blends its colour seamlessly
 *   into the surroundings (membrane interpolation of the edge differences).
 *
 * Not thread-safe: use from one thread.
 */
class RetouchCanvas(val width: Int, val height: Int, val pix: IntArray) {

    // Pixels as they were at the start of the stroke, copied per 64 px tile on first change.
    private val tile = 64
    private val tilesX = (width + tile - 1) / tile
    private val baseTiles = HashMap<Int, IntArray>()
    private val coverage = HashMap<Int, ByteArray>()
    private var tool = RetouchTool.BLUR
    private var brush = RetouchBrush()
    private var cloneDx = 0f
    private var cloneDy = 0f
    private var smudgeBuf: IntArray? = null
    private var smudgeR = 0
    private var lastX = Float.NaN
    private var lastY = Float.NaN

    fun beginStroke(tool: RetouchTool, brush: RetouchBrush, cloneOffsetX: Float = 0f, cloneOffsetY: Float = 0f) {
        this.tool = tool; this.brush = brush
        cloneDx = cloneOffsetX; cloneDy = cloneOffsetY
        baseTiles.clear(); coverage.clear()
        smudgeBuf = null
        lastX = Float.NaN; lastY = Float.NaN
    }

    fun endStroke() {
        baseTiles.clear(); coverage.clear(); smudgeBuf = null
    }

    private fun tileKey(x: Int, y: Int) = (y / tile) * tilesX + (x / tile)

    private fun ensureBase(l: Int, t: Int, r: Int, b: Int) {
        var ty = t / tile
        while (ty * tile <= b) {
            var tx = l / tile
            while (tx * tile <= r) {
                val key = ty * tilesX + tx
                if (!baseTiles.containsKey(key)) {
                    val x0 = tx * tile; val y0 = ty * tile
                    val tw = min(tile, width - x0); val th = min(tile, height - y0)
                    val copy = IntArray(tile * tile)
                    for (yy in 0 until th) System.arraycopy(pix, (y0 + yy) * width + x0, copy, yy * tile, tw)
                    baseTiles[key] = copy
                }
                tx++
            }
            ty++
        }
    }

    /** Pixel at the start of the stroke. */
    private fun base(x: Int, y: Int): Int {
        val t = baseTiles[tileKey(x, y)] ?: return pix[y * width + x]
        return t[(y % tile) * tile + (x % tile)]
    }

    private fun cover(x: Int, y: Int, value: Float): Float {
        val key = tileKey(x, y)
        val arr = coverage.getOrPut(key) { ByteArray(tile * tile) }
        val i = (y % tile) * tile + (x % tile)
        val old = (arr[i].toInt() and 255) / 255f
        if (value > old) { arr[i] = (value * 255f).roundToInt().coerceIn(0, 255).toByte(); return value }
        return old
    }

    private fun weight(d: Float, r: Float): Float {
        if (d >= r) return 0f
        val core = r * brush.hardness.coerceIn(0f, 0.98f)
        if (d <= core) return 1f
        val t = ((d - core) / (r - core)).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    /**
     * Dabs along the polyline [pts] (x0, y0, x1, y1, ... in local pixels) with radius [r].
     * Returns the changed area, or null.
     */
    fun stroke(pts: FloatArray, r: Float): Rect? {
        if (pts.size < 2) return null
        val radius = r.coerceAtLeast(1f)
        val spacing = (radius * if (tool == RetouchTool.SMUDGE || tool == RetouchTool.BLUR) 0.15f else 0.25f).coerceAtLeast(1f)
        var dirty: Rect? = null
        fun add(rr: Rect?) { if (rr != null) { if (dirty == null) dirty = Rect(rr) else dirty!!.union(rr) } }
        var i = 0
        while (i + 1 < pts.size) {
            val x = pts[i]; val y = pts[i + 1]
            if (lastX.isNaN()) {
                add(dab(x, y, radius)); lastX = x; lastY = y
            } else {
                val dx = x - lastX; val dy = y - lastY
                val dist = sqrt(dx * dx + dy * dy)
                if (dist >= spacing) {
                    val n = (dist / spacing).toInt()
                    for (k in 1..n) {
                        val t = k * spacing / dist
                        add(dab(lastX + dx * t, lastY + dy * t, radius))
                    }
                    lastX += dx * (n * spacing / dist); lastY += dy * (n * spacing / dist)
                }
            }
            i += 2
        }
        return dirty
    }

    private fun dab(cx: Float, cy: Float, r: Float): Rect? {
        val l = max(0, (cx - r).toInt()); val t = max(0, (cy - r).toInt())
        val rr = min(width - 1, (cx + r).toInt() + 1); val b = min(height - 1, (cy + r).toInt() + 1)
        if (l > rr || t > b) return null
        when (tool) {
            RetouchTool.DODGE, RetouchTool.BURN -> { ensureBase(l, t, rr, b); toneDab(cx, cy, r, l, t, rr, b) }
            RetouchTool.CLONE -> { ensureBase(l, t, rr, b); cloneDab(cx, cy, r, l, t, rr, b) }
            RetouchTool.BLUR, RetouchTool.SHARPEN -> filterDab(cx, cy, r, l, t, rr, b)
            RetouchTool.SMUDGE -> smudgeDab(cx, cy, r, l, t, rr, b)
            RetouchTool.PATCH -> return null
        }
        return Rect(l, t, rr + 1, b + 1)
    }

    private fun rangeWeight(lum: Float): Float = when (brush.range) {
        ToneRange.SHADOWS -> (1f - lum).pow(2)
        ToneRange.MIDTONES -> 4f * lum * (1f - lum)
        ToneRange.HIGHLIGHTS -> lum * lum
    }

    private fun toneDab(cx: Float, cy: Float, r: Float, l: Int, t: Int, rr: Int, b: Int) {
        val dodge = tool == RetouchTool.DODGE
        for (y in t..b) for (x in l..rr) {
            val w = weight(dist(x, y, cx, cy), r)
            if (w <= 0f) continue
            val cov = cover(x, y, w * brush.strength)
            val c = base(x, y)
            val a = c ushr 24
            if (a == 0) continue
            val rf = ((c shr 16) and 255) / 255f; val gf = ((c shr 8) and 255) / 255f; val bf = (c and 255) / 255f
            val lum = 0.299f * rf + 0.587f * gf + 0.114f * bf
            val k = 1.6f * cov * rangeWeight(lum)
            val gamma = if (dodge) 1f / (1f + k) else 1f + k
            val out = if (brush.protectTones) {
                // Change lightness only: scale towards the new luminance, keep hue and colourfulness.
                val nl = lum.coerceIn(1e-4f, 1f).pow(gamma)
                if (lum < 1e-4f) { val v = nl; pack(a, v, v, v) }
                else {
                    val s = nl / lum
                    var nr = rf * s; var ng = gf * s; var nb = bf * s
                    val mx = max(nr, max(ng, nb))
                    if (mx > 1f) {
                        // Over the top: blend towards white while keeping the luminance.
                        val over = (mx - 1f) / mx
                        nr = nr + (1f - nr) * over; ng = ng + (1f - ng) * over; nb = nb + (1f - nb) * over
                    }
                    pack(a, nr, ng, nb)
                }
            } else {
                pack(a, rf.pow(gamma), gf.pow(gamma), bf.pow(gamma))
            }
            pix[y * width + x] = out
        }
    }

    private fun cloneDab(cx: Float, cy: Float, r: Float, l: Int, t: Int, rr: Int, b: Int) {
        val op = brush.strength.coerceIn(0.01f, 1f)
        for (y in t..b) for (x in l..rr) {
            val w = weight(dist(x, y, cx, cy), r)
            if (w <= 0f) continue
            val sx = (x + cloneDx).roundToInt(); val sy = (y + cloneDy).roundToInt()
            if (sx < 0 || sy < 0 || sx >= width || sy >= height) continue
            val cov = cover(x, y, w * op)
            pix[y * width + x] = mix(base(x, y), base(sx, sy), cov)
        }
    }

    private fun filterDab(cx: Float, cy: Float, r: Float, l: Int, t: Int, rr: Int, b: Int) {
        val k = (r / 10f).roundToInt().coerceIn(1, 6)
        // Box blur of the area (+k margin) from a copy, so the dab reads unchanged neighbours.
        val l2 = max(0, l - k); val t2 = max(0, t - k); val r2 = min(width - 1, rr + k); val b2 = min(height - 1, b + k)
        val w2 = r2 - l2 + 1; val h2 = b2 - t2 + 1
        val area = IntArray(w2 * h2)
        for (yy in 0 until h2) System.arraycopy(pix, (t2 + yy) * width + l2, area, yy * w2, w2)
        val sharpen = tool == RetouchTool.SHARPEN
        val s = brush.strength
        for (y in t..b) for (x in l..rr) {
            val wgt = weight(dist(x, y, cx, cy), r)
            if (wgt <= 0f) continue
            var sa = 0f; var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
            val kk = if (sharpen) 1 else k
            for (yy in max(t2, y - kk)..min(b2, y + kk)) for (xx in max(l2, x - kk)..min(r2, x + kk)) {
                val c = area[(yy - t2) * w2 + (xx - l2)]
                val a = (c ushr 24).toFloat()
                sa += a; sr += ((c shr 16) and 255) * a; sg += ((c shr 8) and 255) * a; sb += (c and 255) * a; n++
            }
            val cur = area[(y - t2) * w2 + (x - l2)]
            if (sa <= 0f) continue
            val blurred = ((sa / n).roundToInt().coerceIn(0, 255) shl 24) or
                ((sr / sa).roundToInt().coerceIn(0, 255) shl 16) or ((sg / sa).roundToInt().coerceIn(0, 255) shl 8) or (sb / sa).roundToInt().coerceIn(0, 255)
            pix[y * width + x] = if (sharpen) {
                val amt = 0.9f * s * wgt
                val ca = cur ushr 24
                fun ch(sh: Int): Float { val v = (cur shr sh) and 255; val bl = (blurred shr sh) and 255; return (v + (v - bl) * amt) / 255f }
                pack(ca, ch(16), ch(8), ch(0))
            } else {
                mix(cur, blurred, 0.65f * s * wgt)
            }
        }
    }

    private fun smudgeDab(cx: Float, cy: Float, r: Float, l: Int, t: Int, rr: Int, b: Int) {
        val ir = r.roundToInt().coerceAtLeast(1)
        val size = 2 * ir + 1
        val icx = cx.roundToInt(); val icy = cy.roundToInt()
        var buf = smudgeBuf
        if (buf == null || smudgeR != ir) {
            // Pick up the paint under the brush at the start of the stroke.
            buf = IntArray(size * size) { j ->
                val x = (icx - ir + j % size).coerceIn(0, width - 1); val y = (icy - ir + j / size).coerceIn(0, height - 1)
                pix[y * width + x]
            }
            smudgeBuf = buf; smudgeR = ir
            return
        }
        val s = brush.strength.coerceIn(0.01f, 0.98f)
        for (y in t..b) for (x in l..rr) {
            val w = weight(dist(x, y, cx, cy), r)
            if (w <= 0f) continue
            val bx = x - icx + ir; val by = y - icy + ir
            if (bx < 0 || by < 0 || bx >= size || by >= size) continue
            val j = by * size + bx
            val i = y * width + x
            val before = pix[i]
            pix[i] = mix(before, buf[j], s * w)
            // The brush slowly takes on the colour it passes over (less at high strength).
            buf[j] = mix(buf[j], before, (1f - s) * w)
        }
    }

    // --- Patch -------------------------------------------------------------------------------

    /**
     * Replaces the area selected in [mask] (alpha bytes, w x h, row stride [maskStride]) with the
     * pixels found [ox], [oy] away, and blends their colour into the surroundings so no seam shows.
     * Returns the changed area, or null when nothing is selected.
     */
    fun patch(mask: ByteArray, maskStride: Int, ox: Int, oy: Int): Rect? {
        var l = width; var t = height; var r = -1; var b = -1
        for (y in 0 until height) {
            val row = y * maskStride
            for (x in 0 until width) if ((mask[row + x].toInt() and 255) > 8) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            }
        }
        if (r < 0) return null
        // Grid over the area plus a margin of known pixels, at most ~200 cells per side.
        val margin = max(4, (max(r - l, b - t) * 0.06f).toInt())
        val gl = max(0, l - margin); val gt = max(0, t - margin); val gr = min(width - 1, r + margin); val gb = min(height - 1, b + margin)
        val bw = gr - gl + 1; val bh = gb - gt + 1
        val cell = max(1, (max(bw, bh) + 199) / 200)
        val gw = (bw + cell - 1) / cell; val gh = (bh + cell - 1) / cell
        fun srcAt(x: Int, y: Int): Int = pix[(y + oy).coerceIn(0, height - 1) * width + (x + ox).coerceIn(0, width - 1)]
        // Edge differences (destination - source) on cells outside the selection; unknown inside.
        val known = BooleanArray(gw * gh)
        val dr = FloatArray(gw * gh); val dg = FloatArray(gw * gh); val db = FloatArray(gw * gh)
        var sumR = 0f; var sumG = 0f; var sumB = 0f; var cnt = 0
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var inside = false
            var ar = 0f; var ag = 0f; var ab = 0f; var n = 0
            for (yy in gt + gy * cell until min(gb + 1, gt + (gy + 1) * cell)) for (xx in gl + gx * cell until min(gr + 1, gl + (gx + 1) * cell)) {
                if ((mask[yy * maskStride + xx].toInt() and 255) > 8) inside = true
                val d = pix[yy * width + xx]; val s = srcAt(xx, yy)
                ar += ((d shr 16) and 255) - ((s shr 16) and 255)
                ag += ((d shr 8) and 255) - ((s shr 8) and 255)
                ab += (d and 255) - (s and 255); n++
            }
            val g = gy * gw + gx
            if (!inside && n > 0) {
                known[g] = true; dr[g] = ar / n; dg[g] = ag / n; db[g] = ab / n
                sumR += dr[g]; sumG += dg[g]; sumB += db[g]; cnt++
            }
        }
        if (cnt > 0) for (g in 0 until gw * gh) if (!known[g]) { dr[g] = sumR / cnt; dg[g] = sumG / cnt; db[g] = sumB / cnt }
        // Laplace smoothing of the unknown cells (Gauss-Seidel): a smooth colour membrane.
        repeat(400) {
            for (gy in 0 until gh) for (gx in 0 until gw) {
                val g = gy * gw + gx
                if (known[g]) continue
                var ar = 0f; var ag = 0f; var ab = 0f; var n = 0
                if (gx > 0) { ar += dr[g - 1]; ag += dg[g - 1]; ab += db[g - 1]; n++ }
                if (gx < gw - 1) { ar += dr[g + 1]; ag += dg[g + 1]; ab += db[g + 1]; n++ }
                if (gy > 0) { ar += dr[g - gw]; ag += dg[g - gw]; ab += db[g - gw]; n++ }
                if (gy < gh - 1) { ar += dr[g + gw]; ag += dg[g + gw]; ab += db[g + gw]; n++ }
                dr[g] = ar / n; dg[g] = ag / n; db[g] = ab / n
            }
        }
        // Source + interpolated difference, mixed in by the selection's own soft edge.
        val out = IntArray(bw * bh)
        for (y in gt..gb) for (x in gl..gr) {
            val m = (mask[y * maskStride + x].toInt() and 255) / 255f
            val i = y * width + x
            if (m <= 0f) { out[(y - gt) * bw + (x - gl)] = pix[i]; continue }
            val fx = ((x - gl) / cell.toFloat() - 0.5f).coerceIn(0f, (gw - 1).toFloat())
            val fy = ((y - gt) / cell.toFloat() - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(x0 + 1, gw - 1); val y1 = min(y0 + 1, gh - 1)
            val ax = fx - x0; val ay = fy - y0
            fun bil(a: FloatArray) = (a[y0 * gw + x0] * (1 - ax) + a[y0 * gw + x1] * ax) * (1 - ay) + (a[y1 * gw + x0] * (1 - ax) + a[y1 * gw + x1] * ax) * ay
            val s = srcAt(x, y)
            val patched = pack(
                pix[i] ushr 24,
                (((s shr 16) and 255) + bil(dr)) / 255f,
                (((s shr 8) and 255) + bil(dg)) / 255f,
                ((s and 255) + bil(db)) / 255f
            )
            out[(y - gt) * bw + (x - gl)] = mix(pix[i], patched, m)
        }
        for (y in gt..gb) System.arraycopy(out, (y - gt) * bw, pix, y * width + gl, bw)
        return Rect(gl, gt, gr + 1, gb + 1)
    }

    // --- Preview -----------------------------------------------------------------------------

    /**
     * Updates [preview] (the layer's smaller preview of these same pixels) inside the full-res
     * area [area] with a box-filtered (premultiplied) downscale.
     */
    fun downsampleInto(preview: Bitmap, area: Rect) {
        val pw = preview.width; val ph = preview.height
        val sx = pw.toFloat() / width; val sy = ph.toFloat() / height
        val pl = max(0, (area.left * sx).toInt()); val pt = max(0, (area.top * sy).toInt())
        val pr = min(pw, kotlin.math.ceil(area.right * sx).toInt()); val pb = min(ph, kotlin.math.ceil(area.bottom * sy).toInt())
        if (pl >= pr || pt >= pb) return
        val rowOut = IntArray(pr - pl)
        for (py in pt until pb) {
            val fy0 = (py / sy).toInt(); val fy1 = min(height, max(fy0 + 1, ((py + 1) / sy).toInt()))
            for (px in pl until pr) {
                val fx0 = (px / sx).toInt(); val fx1 = min(width, max(fx0 + 1, ((px + 1) / sx).toInt()))
                var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
                for (y in fy0 until fy1) for (x in fx0 until fx1) {
                    val c = pix[y * width + x]; val a = (c ushr 24).toLong()
                    sa += a; sr += ((c shr 16) and 255) * a; sg += ((c shr 8) and 255) * a; sb += (c and 255) * a; n++
                }
                rowOut[px - pl] = if (sa == 0L || n == 0) 0 else
                    (((sa / n).toInt().coerceIn(0, 255)) shl 24) or ((sr / sa).toInt() shl 16) or ((sg / sa).toInt() shl 8) or (sb / sa).toInt()
            }
            preview.setPixels(rowOut, 0, pr - pl, pl, py, pr - pl, 1)
        }
    }

    // --- Helpers -----------------------------------------------------------------------------

    private fun dist(x: Int, y: Int, cx: Float, cy: Float): Float {
        val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
        return sqrt(dx * dx + dy * dy)
    }

    private fun pack(a: Int, r: Float, g: Float, b: Float): Int =
        (a shl 24) or ((r * 255f).roundToInt().coerceIn(0, 255) shl 16) or
            ((g * 255f).roundToInt().coerceIn(0, 255) shl 8) or (b * 255f).roundToInt().coerceIn(0, 255)

    companion object {
        /** Premultiplied mix of two ARGB colours: [t] = 0 gives [a], 1 gives [b]. */
        fun mix(a: Int, b: Int, t: Float): Int {
            if (t <= 0f) return a
            if (t >= 1f) return b
            val aa = (a ushr 24).toFloat(); val ba = (b ushr 24).toFloat()
            val oa = aa + (ba - aa) * t
            if (oa < 0.5f) return 0
            fun ch(sh: Int): Int {
                val av = ((a shr sh) and 255) * aa; val bv = ((b shr sh) and 255) * ba
                return ((av + (bv - av) * t) / oa).roundToInt().coerceIn(0, 255)
            }
            return (oa.roundToInt().coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
