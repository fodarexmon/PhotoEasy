package com.example.engine

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Camera Raw style Noise Reduction and Grain as non-destructive adjustments, on unpremultiplied
 * ARGB pixels in place.
 */
object NoiseAndGrain {

    // --- Noise reduction ----------------------------------------------------------------------

    /** Mean over a (2r+1)^2 window, clamped at the borders (separable sliding sums). */
    private fun box(src: FloatArray, w: Int, h: Int, r: Int, tmp: FloatArray, out: FloatArray) {
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            var cnt = 0
            for (x in 0..min(r, w - 1)) { sum += src[row + x]; cnt++ }
            for (x in 0 until w) {
                tmp[row + x] = sum / cnt
                val add = x + r + 1; val rem = x - r
                if (add < w) { sum += src[row + add]; cnt++ }
                if (rem >= 0) { sum -= src[row + rem]; cnt-- }
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            var cnt = 0
            for (y in 0..min(r, h - 1)) { sum += tmp[y * w + x]; cnt++ }
            for (y in 0 until h) {
                out[y * w + x] = sum / cnt
                val add = y + r + 1; val rem = y - r
                if (add < h) { sum += tmp[add * w + x]; cnt++ }
                if (rem >= 0) { sum -= tmp[rem * w + x]; cnt-- }
            }
        }
    }

    /**
     * Guided filter of [p] with guide [g] (He et al.): smooths [p] where [g] is flat and keeps
     * edges of [g]. Result in [out].
     */
    private fun guided(g: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float, out: FloatArray) {
        val n = w * h
        val tmp = FloatArray(n)
        val mI = FloatArray(n); val mP = FloatArray(n); val mIP = FloatArray(n); val mII = FloatArray(n)
        box(g, w, h, r, tmp, mI)
        box(p, w, h, r, tmp, mP)
        val prod = FloatArray(n)
        for (i in 0 until n) prod[i] = g[i] * p[i]
        box(prod, w, h, r, tmp, mIP)
        for (i in 0 until n) prod[i] = g[i] * g[i]
        box(prod, w, h, r, tmp, mII)
        val a = prod // reuse
        val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = mII[i] - mI[i] * mI[i]
            val cov = mIP[i] - mI[i] * mP[i]
            a[i] = cov / (varI + eps)
            b[i] = mP[i] - a[i] * mI[i]
        }
        box(a, w, h, r, tmp, mIP)
        box(b, w, h, r, tmp, mII)
        for (i in 0 until n) out[i] = mIP[i] * g[i] + mII[i]
    }

    /**
     * Noise reduction, [amount] 0..100: luminance noise smoothed with an edge-preserving filter,
     * colour noise (blotches) smoothed more strongly along the image's own edges.
     */
    fun reduceNoise(px: IntArray, w: Int, h: Int, amount: Float, checkCancel: () -> Unit = {}) {
        if (amount <= 0f || w < 3 || h < 3) return
        val n = w * h
        val t = (amount / 100f).coerceIn(0f, 1f)
        val yy = FloatArray(n); val cb = FloatArray(n); val cr = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]
            val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            yy[i] = l; cb[i] = b - l; cr[i] = r - l
        }
        checkCancel()
        // Luminance: a small edge-aware smoothing, stronger with the amount.
        val ly = FloatArray(n)
        val rl = 1 + (t * 2f).toInt()
        guided(yy, yy, w, h, rl, 0.0004f + t * t * 0.01f, ly)
        checkCancel()
        // Colour: wider, guided by the luminance so colours do not bleed across edges.
        val rc = 2 + (t * 5f).toInt()
        val ocb = FloatArray(n); val ocr = FloatArray(n)
        guided(ly, cb, w, h, rc, 0.002f, ocb)
        checkCancel()
        guided(ly, cr, w, h, rc, 0.002f, ocr)
        val kl = min(1f, t * 1.4f)
        val kc = min(1f, t * 2f)
        for (i in 0 until n) {
            val a = px[i] ushr 24
            if (a == 0) continue
            val l = yy[i] + (ly[i] - yy[i]) * kl
            val vb = cb[i] + (ocb[i] - cb[i]) * kc
            val vr = cr[i] + (ocr[i] - cr[i]) * kc
            val r = l + vr; val b = l + vb
            val g = (l - 0.299f * r - 0.114f * b) / 0.587f
            fun q(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
            px[i] = (a shl 24) or (q(r) shl 16) or (q(g) shl 8) or q(b)
        }
    }

    // --- Grain -------------------------------------------------------------------------------

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 362437
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777215f * 2f - 1f
    }

    /** Smooth value noise (-1..1) with cells [cell] pixels wide. */
    private fun valueNoise(x: Float, y: Float, cell: Float, seed: Int): Float {
        val fx = x / cell; val fy = y / cell
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        var tx = fx - x0; var ty = fy - y0
        tx = tx * tx * (3f - 2f * tx); ty = ty * ty * (3f - 2f * ty)
        val a = hash(x0, y0, seed); val b = hash(x0 + 1, y0, seed)
        val c = hash(x0, y0 + 1, seed); val d = hash(x0 + 1, y0 + 1, seed)
        val top = a + (b - a) * tx; val bot = c + (d - c) * tx
        return top + (bot - top) * ty
    }

    /**
     * Film grain: [amount] 0..100 strength, [size] 0..100 grain size (relative to the image, so
     * a preview and the export look alike), [roughness] 0..100 (smooth to clumpy). Monochrome,
     * strongest in the mid-tones, fixed pattern (the same every render).
     */
    fun addGrain(px: IntArray, w: Int, h: Int, amount: Float, size: Float, roughness: Float) {
        if (amount <= 0f || w == 0 || h == 0) return
        val short = min(w, h).toFloat()
        val cell = max(0.7f, short * (0.0006f + size.coerceIn(0f, 100f) / 100f * 0.004f))
        // The cell follows the image size, so the pattern lines up across resolutions.
        val rough = roughness.coerceIn(0f, 100f) / 100f
        val strength = amount.coerceIn(0f, 100f) / 100f * 0.22f
        ImageProcessor.parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                val a = c ushr 24
                if (a == 0) continue
                var nz = valueNoise(x.toFloat(), y.toFloat(), cell, 1)
                if (rough > 0f) nz = nz * (1f - rough * 0.5f) + valueNoise(x.toFloat(), y.toFloat(), cell * 0.5f, 2) * rough * 0.7f
                val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                val mid = 0.35f + 0.65f * 4f * l * (1f - l)
                val d = nz * strength * mid * 255f
                fun q(v: Int) = (v + d + 0.5f).toInt().coerceIn(0, 255)
                px[i] = (a shl 24) or (q(r) shl 16) or (q(g) shl 8) or q(b)
            }
        }
    }
}
