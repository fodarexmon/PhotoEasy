package com.example.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Where a selection stroke lies relative to the selection edge (Photoshop's Edit > Stroke). */
enum class StrokePosition { INSIDE, CENTER, OUTSIDE }

/**
 * Pixel commands on a selection: Delete (clear), Fill and Stroke. All work on unpremultiplied
 * ARGB pixels [px] (row-major, width w) with the selection as alpha bytes [sel] (row stride
 * [stride]); soft selection edges give soft results.
 */
object SelectionOps {

    /** Edit > Clear: selected pixels become transparent (partly, at a soft edge). */
    fun erase(px: IntArray, w: Int, h: Int, sel: ByteArray, stride: Int) {
        for (y in 0 until h) for (x in 0 until w) {
            val m = sel[y * stride + x].toInt() and 255
            if (m == 0) continue
            val i = y * w + x
            val c = px[i]
            val a = (c ushr 24) * (255 - m) / 255
            px[i] = if (a == 0) 0 else (a shl 24) or (c and 0xFFFFFF)
        }
    }

    /** Edit > Fill: [color] at [opacity] over the selected pixels. */
    fun fill(px: IntArray, w: Int, h: Int, sel: ByteArray, stride: Int, color: Int, opacity: Float) {
        val op = opacity.coerceIn(0f, 1f)
        for (y in 0 until h) for (x in 0 until w) {
            val m = sel[y * stride + x].toInt() and 255
            if (m == 0) continue
            val i = y * w + x
            px[i] = over(px[i], color, m / 255f * op)
        }
    }

    /**
     * Edit > Stroke: a [width] px line of [color] along the selection edge, inside it, centred
     * on it or outside it ([position]), anti-aliased.
     */
    fun stroke(
        px: IntArray, w: Int, h: Int, sel: ByteArray, stride: Int, color: Int, width: Float,
        position: StrokePosition, opacity: Float
    ) {
        val cov = strokeCoverage(w, h, sel, stride, width, position)
        val op = opacity.coerceIn(0f, 1f)
        for (i in 0 until w * h) {
            val c = cov[i]
            if (c > 0f) px[i] = over(px[i], color, c * op)
        }
    }

    /** Coverage 0..1 of the stroke per pixel (see [stroke]). */
    fun strokeCoverage(w: Int, h: Int, sel: ByteArray, stride: Int, width: Float, position: StrokePosition): FloatArray {
        val inside = BooleanArray(w * h)
        var any = false
        for (y in 0 until h) for (x in 0 until w) {
            val v = (sel[y * stride + x].toInt() and 255) >= 128
            inside[y * w + x] = v
            if (v) any = true
        }
        val out = FloatArray(w * h)
        if (!any) return out
        // Distance (pixel centres) to the nearest pixel on the other side of the edge; the edge
        // itself lies half a pixel from both.
        val dIn = distance(inside, w, h, target = false)   // inside pixels: to the nearest outside one
        val dOut = distance(inside, w, h, target = true)   // outside pixels: to the nearest inside one
        val wd = width.coerceAtLeast(0.5f)
        for (i in 0 until w * h) {
            // Signed distance from the edge: > 0 inside, < 0 outside.
            val s = if (inside[i]) dIn[i] - 0.5f else -(dOut[i] - 0.5f)
            val (lo, hi) = when (position) {
                StrokePosition.INSIDE -> 0f to wd
                StrokePosition.CENTER -> -wd / 2f to wd / 2f
                StrokePosition.OUTSIDE -> -wd to 0f
            }
            // 1 px anti-aliasing on both sides of the band.
            val c = min(s - lo + 0.5f, hi - s + 0.5f).coerceIn(0f, 1f)
            out[i] = c
        }
        return out
    }

    /**
     * Euclidean distance from every pixel to the nearest pixel whose [inside] value equals
     * [target] (0 for those pixels themselves). Felzenszwalb's two-pass transform.
     */
    private fun distance(inside: BooleanArray, w: Int, h: Int, target: Boolean): FloatArray {
        val inf = 1e20
        val f = DoubleArray(w * h) { if (inside[it] == target) 0.0 else inf }
        val n = max(w, h)
        val d = DoubleArray(n); val v = IntArray(n); val z = DoubleArray(n + 1); val col = DoubleArray(n)
        // Columns, then rows (squared distances).
        for (x in 0 until w) {
            for (y in 0 until h) col[y] = f[y * w + x]
            edt1(col, h, d, v, z)
            for (y in 0 until h) f[y * w + x] = d[y]
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            System.arraycopy(f, y * w, col, 0, w)
            edt1(col, w, d, v, z)
            for (x in 0 until w) out[y * w + x] = sqrt(min(d[x], 1e12)).toFloat()
        }
        return out
    }

    /** 1-D squared distance transform of sampled function [f] (length [n]) into [d]. */
    private fun edt1(f: DoubleArray, n: Int, d: DoubleArray, v: IntArray, z: DoubleArray) {
        var k = 0
        v[0] = 0; z[0] = Double.NEGATIVE_INFINITY; z[1] = Double.POSITIVE_INFINITY
        for (q in 1 until n) {
            var s = ((f[q] + q.toDouble() * q) - (f[v[k]] + v[k].toDouble() * v[k])) / (2.0 * (q - v[k]))
            while (s <= z[k]) {
                k--
                s = ((f[q] + q.toDouble() * q) - (f[v[k]] + v[k].toDouble() * v[k])) / (2.0 * (q - v[k]))
            }
            k++
            v[k] = q; z[k] = s; z[k + 1] = Double.POSITIVE_INFINITY
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val p = v[k]
            d[q] = (q - p).toDouble() * (q - p) + f[p]
        }
    }

    /** [src] with alpha [k] over [dst] (unpremultiplied ARGB). */
    fun over(dst: Int, src: Int, k: Float): Int {
        val sa = (src ushr 24) / 255f * k
        if (sa <= 0f) return dst
        val da = (dst ushr 24) / 255f
        val oa = sa + da * (1f - sa)
        if (oa <= 0f) return 0
        fun ch(sh: Int): Int {
            val cs = (src shr sh) and 255; val cd = (dst shr sh) and 255
            return ((cs * sa + cd * da * (1f - sa)) / oa + 0.5f).toInt().coerceIn(0, 255)
        }
        return ((oa * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
