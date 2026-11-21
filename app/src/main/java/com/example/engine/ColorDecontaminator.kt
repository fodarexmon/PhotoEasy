package com.example.engine

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Photoshop-style "Decontaminate Colors" for a finished matte.
 *
 * 1. Foreground and background colours are each extended smoothly from trusted pixels (well
 *    inside the subject / well outside it) over the whole edge area (push-pull fill, at up to
 *    [WORK_MAX] px; colours vary slowly, the result is scaled back up). One estimate per area
 *    instead of one sample per pixel: no speckles.
 * 2. Every partly transparent pixel C with alpha a is solved for its own foreground colour
 *    F = (C - (1 - a) B) / a (keeps the strand's own detail); where a is small that solution is
 *    unreliable, so it leans on the extended foreground colour instead.
 * 3. The opaque rim just inside the edge (often tinted by the background) is pulled towards the
 *    extended foreground colour too, in colour only (its brightness and texture stay).
 * [amount] (0..1) scales the change; the source alpha is never changed.
 */
object ColorDecontaminator {
    private const val WORK_MAX = 1024

    fun decontaminate(source: Bitmap, alpha: ByteArray, amount: Float, isCancelled: () -> Boolean = { false }): Bitmap? {
        val w = source.width; val h = source.height
        val n = w * h
        if (alpha.size != n || amount <= 0f) return null
        val k = amount.coerceIn(0f, 1f)
        val ring = (max(w, h) / 1000).coerceIn(2, 6)

        // Soft pixels (partly selected) and their bounding box.
        val soft = BooleanArray(n)
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val v = alpha[row + x].toInt() and 0xFF
                if (v in 1..254) {
                    soft[row + x] = true
                    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                }
            }
        }
        if (x1 < 0) return null
        // Work area: the soft band plus a margin wide enough to reach trusted colours.
        val margin = max(32, ((x1 - x0 + y1 - y0) / 16))
        val X0 = max(0, x0 - margin); val Y0 = max(0, y0 - margin)
        val X1 = min(w - 1, x1 + margin); val Y1 = min(h - 1, y1 + margin)
        val RW = X1 - X0 + 1; val RH = Y1 - Y0 + 1

        // Near the edge: within `ring` px of a soft pixel (rim), within 2 x ring (not trusted).
        val near1 = dilateBox(soft, w, h, ring, X0, Y0, X1, Y1)
        val near2 = dilateBox(soft, w, h, ring * 2, X0, Y0, X1, Y1)
        if (isCancelled()) return null

        // Trusted colours on a reduced grid.
        val f = min(1f, WORK_MAX.toFloat() / max(RW, RH))
        val lw = max(1, (RW * f).roundToInt()); val lh = max(1, (RH * f).roundToInt())
        val fr = FloatArray(lw * lh); val fg = FloatArray(lw * lh); val fb = FloatArray(lw * lh); val fw = FloatArray(lw * lh)
        val br = FloatArray(lw * lh); val bg = FloatArray(lw * lh); val bb = FloatArray(lw * lh); val bw = FloatArray(lw * lh)
        val row = IntArray(RW)
        for (y in Y0..Y1) {
            source.getPixels(row, 0, RW, X0, y, RW, 1)
            val ly = min(lh - 1, ((y - Y0) * f).toInt()) * lw
            for (x in X0..X1) {
                val i = y * w + x
                if (near2[i]) continue
                val a = alpha[i].toInt() and 0xFF
                val c = row[x - X0]
                if ((c ushr 24) < 8) continue
                val li = ly + min(lw - 1, ((x - X0) * f).toInt())
                val r = ((c shr 16) and 255).toFloat(); val g = ((c shr 8) and 255).toFloat(); val b = (c and 255).toFloat()
                if (a == 255) { fr[li] += r; fg[li] += g; fb[li] += b; fw[li] += 1f }
                else if (a == 0) { br[li] += r; bg[li] += g; bb[li] += b; bw[li] += 1f }
            }
        }
        if (isCancelled()) return null
        val hasFg = pushPull(fr, fg, fb, fw, lw, lh)
        val hasBg = pushPull(br, bg, bb, bw, lw, lh)
        if (!hasFg) return null

        val out = source.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val px = IntArray(RW)
        fun sample(arr: FloatArray, lx: Float, ly: Float): Float {
            val xa = lx.coerceIn(0f, (lw - 1).toFloat()); val ya = ly.coerceIn(0f, (lh - 1).toFloat())
            val ix = xa.toInt(); val iy = ya.toInt()
            val ix1 = min(lw - 1, ix + 1); val iy1 = min(lh - 1, iy + 1)
            val tx = xa - ix; val ty = ya - iy
            return (arr[iy * lw + ix] * (1 - tx) + arr[iy * lw + ix1] * tx) * (1 - ty) +
                (arr[iy1 * lw + ix] * (1 - tx) + arr[iy1 * lw + ix1] * tx) * ty
        }
        for (y in Y0..Y1) {
            if ((y and 63) == 0 && isCancelled()) { out.recycle(); return null }
            out.getPixels(px, 0, RW, X0, y, RW, 1)
            var changed = false
            val ly = (y - Y0 + 0.5f) * f - 0.5f
            for (x in X0..X1) {
                val i = y * w + x
                val a8 = alpha[i].toInt() and 0xFF
                val isSoft = a8 in 1..254
                val isRim = !isSoft && a8 == 255 && near1[i]
                if (!isSoft && !isRim) continue
                val c = px[x - X0]
                val ca = c ushr 24
                if (ca == 0) continue
                val lx = (x - X0 + 0.5f) * f - 0.5f
                val er = sample(fr, lx, ly); val eg = sample(fg, lx, ly); val eb = sample(fb, lx, ly)
                val cr = ((c shr 16) and 255).toFloat(); val cg = ((c shr 8) and 255).toFloat(); val cb = (c and 255).toFloat()
                var nr: Float; var ng: Float; var nb: Float
                if (isSoft) {
                    val a = a8 / 255f
                    if (hasBg) {
                        val gr = sample(br, lx, ly); val gg = sample(bg, lx, ly); val gb = sample(bb, lx, ly)
                        val sr = ((cr - (1 - a) * gr) / a).coerceIn(0f, 255f)
                        val sg = ((cg - (1 - a) * gg) / a).coerceIn(0f, 255f)
                        val sb = ((cb - (1 - a) * gb) / a).coerceIn(0f, 255f)
                        // The solved colour is reliable for fairly opaque pixels only.
                        val ws = smooth(0.2f, 0.7f, a)
                        nr = sr * ws + er * (1 - ws); ng = sg * ws + eg * (1 - ws); nb = sb * ws + eb * (1 - ws)
                    } else {
                        nr = er; ng = eg; nb = eb
                    }
                    // Keep the strand's own light / dark detail around the estimate.
                    val detail = lum(cr, cg, cb) - lum(nr, ng, nb)
                    val keep = 0.35f * smooth(0.3f, 0.9f, a)
                    nr += detail * keep; ng += detail * keep; nb += detail * keep
                    val t = k
                    nr = cr + (nr - cr) * t; ng = cg + (ng - cg) * t; nb = cb + (nb - cb) * t
                } else {
                    // Opaque rim: the extended foreground colour, at the pixel's own brightness.
                    val le = max(1f, lum(er, eg, eb)); val s = lum(cr, cg, cb) / le
                    val tr = er * s; val tg = eg * s; val tb = eb * s
                    val t = 0.45f * k
                    nr = cr + (tr - cr) * t; ng = cg + (tg - cg) * t; nb = cb + (tb - cb) * t
                }
                px[x - X0] = (ca shl 24) or (clamp(nr) shl 16) or (clamp(ng) shl 8) or clamp(nb)
                changed = true
            }
            if (changed) out.setPixels(px, 0, RW, X0, y, RW, 1)
        }
        return out
    }

    private fun lum(r: Float, g: Float, b: Float) = 0.299f * r + 0.587f * g + 0.114f * b
    private fun clamp(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)
    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** True within [r] px (box) of a true pixel of [m], computed inside the given area only. */
    private fun dilateBox(m: BooleanArray, w: Int, h: Int, r: Int, X0: Int, Y0: Int, X1: Int, Y1: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
        val out = BooleanArray(w * h)
        // Rows: prefix counts give "any true in [x - r, x + r]" in O(1).
        val pre = IntArray(w + 1)
        for (y in max(0, Y0 - r)..min(h - 1, Y1 + r)) {
            for (x in 0 until w) pre[x + 1] = pre[x] + if (m[y * w + x]) 1 else 0
            for (x in X0..X1) {
                val a = max(0, x - r); val b = min(w - 1, x + r)
                if (pre[b + 1] - pre[a] > 0) tmp[y * w + x] = true
            }
        }
        val preY = IntArray(h + 1)
        for (x in X0..X1) {
            for (y in 0 until h) preY[y + 1] = preY[y] + if (tmp[y * w + x]) 1 else 0
            for (y in Y0..Y1) {
                val a = max(0, y - r); val b = min(h - 1, y + r)
                if (preY[b + 1] - preY[a] > 0) out[y * w + x] = true
            }
        }
        return out
    }

    /**
     * Fills cells without samples from their surroundings (push-pull): [r], [g], [b] hold colour
     * sums and [wt] sample counts; afterwards every cell holds an average colour. Returns false
     * when there was no sample at all.
     */
    private fun pushPull(r: FloatArray, g: FloatArray, b: FloatArray, wt: FloatArray, w: Int, h: Int): Boolean {
        if (wt.all { it <= 0f }) return false
        // Normalise the finest level to colours + confidence (0..1).
        val levels = ArrayList<FloatArray>() // r, g, b, wt per level, interleaved as 4 arrays
        val dims = ArrayList<IntArray>()
        var cr = r.copyOf(); var cg = g.copyOf(); var cb = b.copyOf(); var cw = wt.copyOf()
        var cwid = w; var chei = h
        levels.add(cr); levels.add(cg); levels.add(cb); levels.add(cw); dims.add(intArrayOf(cwid, chei))
        // Pull: sums of 2x2 blocks up to a single cell.
        while (cwid > 1 || chei > 1) {
            val nw = (cwid + 1) / 2; val nh = (chei + 1) / 2
            val nr = FloatArray(nw * nh); val ng = FloatArray(nw * nh); val nb = FloatArray(nw * nh); val nwt = FloatArray(nw * nh)
            for (y in 0 until chei) for (x in 0 until cwid) {
                val i = y * cwid + x; val j = (y / 2) * nw + (x / 2)
                nr[j] += cr[i]; ng[j] += cg[i]; nb[j] += cb[i]; nwt[j] += cw[i]
            }
            cr = nr; cg = ng; cb = nb; cw = nwt; cwid = nw; chei = nh
            levels.add(cr); levels.add(cg); levels.add(cb); levels.add(cw); dims.add(intArrayOf(cwid, chei))
        }
        // Coarsest level: average colour.
        val top = dims.size - 1
        run {
            val t = levels[top * 4 + 3]
            for (i in t.indices) if (t[i] > 0f) {
                levels[top * 4][i] /= t[i]; levels[top * 4 + 1][i] /= t[i]; levels[top * 4 + 2][i] /= t[i]
            }
        }
        // Push: each finer cell mixes its own average with the coarser estimate by its confidence.
        for (lvl in top - 1 downTo 0) {
            val (lw, lh) = dims[lvl].let { it[0] to it[1] }
            val (pw, _) = dims[lvl + 1].let { it[0] to it[1] }
            val R = levels[lvl * 4]; val G = levels[lvl * 4 + 1]; val B = levels[lvl * 4 + 2]; val W = levels[lvl * 4 + 3]
            val PR = levels[(lvl + 1) * 4]; val PG = levels[(lvl + 1) * 4 + 1]; val PB = levels[(lvl + 1) * 4 + 2]
            for (y in 0 until lh) for (x in 0 until lw) {
                val i = y * lw + x; val j = (y / 2) * pw + (x / 2)
                val wv = W[i]
                if (wv > 0f) {
                    val conf = min(1f, wv / 4f)
                    val ar = R[i] / wv; val ag = G[i] / wv; val ab = B[i] / wv
                    R[i] = ar * conf + PR[j] * (1 - conf); G[i] = ag * conf + PG[j] * (1 - conf); B[i] = ab * conf + PB[j] * (1 - conf)
                } else {
                    R[i] = PR[j]; G[i] = PG[j]; B[i] = PB[j]
                }
            }
        }
        System.arraycopy(levels[0], 0, r, 0, r.size)
        System.arraycopy(levels[1], 0, g, 0, g.size)
        System.arraycopy(levels[2], 0, b, 0, b.size)
        return true
    }
}
