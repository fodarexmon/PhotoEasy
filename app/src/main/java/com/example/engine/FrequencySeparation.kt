package com.example.engine

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Frequency separation, as in Photoshop: the image is split into a Low frequency layer (a
 * Gaussian blur: colour and tone) and a High frequency layer (fine texture, stored around mid
 * grey) that rebuilds the original exactly when blended over Low with [linearLight].
 */
/** How the Low frequency layer is blurred. */
enum class LowPassMethod {
    /** Classic: soft everywhere. */
    GAUSSIAN,
    /** Keeps big edges (nose, jaw, eyes) in Low: no halos when Low is smoothed near them. */
    SURFACE,
    /** Removes small spots from Low while keeping edges. */
    MEDIAN
}

object FrequencySeparation {

    /** Returns (low, high) for [source] with blur [sigma] (pixels of [source]). */
    fun split(source: Bitmap, sigma: Float, isCancelled: (() -> Boolean)? = null): Pair<Bitmap, Bitmap> =
        split(source, sigma, LowPassMethod.GAUSSIAN, 20f, isCancelled)

    /**
     * Returns (low, high) for [source]: Low blurred with [method] at radius [sigma] (pixels of
     * [source]); [threshold] (levels) is Surface Blur's edge threshold. Whatever Low is, High
     * is computed from it, so Low + High always rebuilds the image.
     */
    fun split(source: Bitmap, sigma: Float, method: LowPassMethod, threshold: Float, isCancelled: (() -> Boolean)? = null): Pair<Bitmap, Bitmap> {
        val low = lowPass(source, sigma, method, threshold, isCancelled)
        return try { low to difference(source, low, isCancelled) } catch (t: Throwable) { low.recycle(); throw t }
    }

    /**
     * Three-band separation: Low (colour and tone, [sigma] with [method]), Mid (in-between
     * detail such as wrinkles and shadows of pores, between [detailSigma] and [sigma]) and High
     * (finest texture, below [detailSigma]). Low, then Mid and High in Linear Light, rebuild the
     * image.
     */
    fun split3(
        source: Bitmap, sigma: Float, detailSigma: Float, method: LowPassMethod, threshold: Float,
        isCancelled: (() -> Boolean)? = null
    ): Triple<Bitmap, Bitmap, Bitmap> {
        val low = lowPass(source, sigma, method, threshold, isCancelled)
        val fine = ProEffects.gaussian(source, detailSigma.coerceIn(0.3f, sigma.coerceAtLeast(0.6f) * 0.8f), isCancelled)
        try {
            val mid = try { difference(fine, low, isCancelled) } catch (t: Throwable) { low.recycle(); throw t }
            val high = try { difference(source, fine, isCancelled) } catch (t: Throwable) { low.recycle(); mid.recycle(); throw t }
            return Triple(low, mid, high)
        } finally {
            fine.recycle()
        }
    }

    /** The Low frequency layer of [source]. */
    fun lowPass(source: Bitmap, sigma: Float, method: LowPassMethod, threshold: Float, isCancelled: (() -> Boolean)? = null): Bitmap =
        when (method) {
            LowPassMethod.GAUSSIAN -> ProEffects.gaussian(source, sigma.coerceAtLeast(0.3f), isCancelled)
            LowPassMethod.SURFACE -> ProEffects.apply(
                source, com.example.data.model.ProFilterType.SURFACE_BLUR,
                ProEffectParams(sigma.coerceAtLeast(1f), threshold.coerceIn(2f, 255f)), isCancelled
            )
            LowPassMethod.MEDIAN -> median(source, sigma.coerceAtLeast(1f), isCancelled)
        }

    /** (image - base) / 2 around mid grey, per pixel: the layer that rebuilds image over base. */
    private fun difference(image: Bitmap, base: Bitmap, isCancelled: (() -> Boolean)?): Bitmap {
        val w = image.width; val h = image.height
        val img = IntArray(w); val lo = IntArray(w); val out = IntArray(w)
        val high = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            if ((y and 63) == 0 && isCancelled?.invoke() == true) {
                high.recycle()
                throw kotlinx.coroutines.CancellationException("Frequency separation cancelled")
            }
            image.getPixels(img, 0, w, 0, y, w, 1)
            base.getPixels(lo, 0, w, 0, y, w, 1)
            for (x in 0 until w) out[x] = highPixel(img[x], lo[x])
            high.setPixels(out, 0, w, 0, y, w, 1)
        }
        return high
    }

    /**
     * Median filter (per channel) of radius [radius]: worked at a size where the window is at
     * most 13 x 13 (and the image at most 2400 px), with sliding histograms, then scaled back.
     */
    private fun median(source: Bitmap, radius: Float, isCancelled: (() -> Boolean)?): Bitmap {
        val w = source.width; val h = source.height
        val s = minOf(1f, 6f / radius, 2400f / maxOf(w, h))
        val sw = maxOf(1, (w * s).roundToInt()); val sh = maxOf(1, (h * s).roundToInt())
        val small = if (s < 0.999f) Bitmap.createScaledBitmap(source, sw, sh, true) else source
        val r = maxOf(1, (radius * s).roundToInt())
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        val out = IntArray(sw * sh)
        ImageProcessor.parallelRows(sh) { y0, y1 ->
            val hist = Array(3) { IntArray(256) }
            val med = IntArray(3); val below = IntArray(3)
            for (y in y0 until y1) {
                if ((y and 31) == 0 && isCancelled?.invoke() == true) throw kotlinx.coroutines.CancellationException("Median cancelled")
                val t = maxOf(0, y - r); val b = minOf(sh - 1, y + r)
                for (c in 0 until 3) { hist[c].fill(0); med[c] = 0; below[c] = 0 }
                var count = 0
                fun addCol(x: Int, sign: Int) {
                    for (yy in t..b) {
                        val v = px[yy * sw + x]
                        val rv = (v shr 16) and 255; val gv = (v shr 8) and 255; val bv = v and 255
                        hist[0][rv] += sign; hist[1][gv] += sign; hist[2][bv] += sign
                        if (rv < med[0]) below[0] += sign
                        if (gv < med[1]) below[1] += sign
                        if (bv < med[2]) below[2] += sign
                    }
                    count += sign * (b - t + 1)
                }
                for (x in 0..minOf(sw - 1, r)) addCol(x, 1)
                for (x in 0 until sw) {
                    val half = count / 2
                    var o = px[y * sw + x] and 0xFF000000.toInt()
                    for (c in 0 until 3) {
                        val hc = hist[c]
                        var m = med[c]; var lo = below[c]
                        // Move the median until half the samples lie below it.
                        while (m > 0 && lo > half) { m--; lo -= hc[m] }
                        while (m < 255 && lo + hc[m] <= half) { lo += hc[m]; m++ }
                        med[c] = m; below[c] = lo
                        o = o or (m shl (16 - 8 * c))
                    }
                    out[y * sw + x] = o
                    val add = x + r + 1; val rem = x - r
                    if (add < sw) addCol(add, 1)
                    if (rem >= 0) addCol(rem, -1)
                }
            }
        }
        val res = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        res.setPixels(out, 0, sw, 0, 0, sw, sh)
        if (small !== source) small.recycle()
        if (sw == w && sh == h) return res
        val up = Bitmap.createScaledBitmap(res, w, h, true)
        if (up !== res) res.recycle()
        return up
    }

    /** Texture of one pixel: (image - low) / 2 around mid grey (Photoshop's 8-bit Subtract, scale 2). */
    fun highPixel(image: Int, low: Int): Int {
        val a = image ushr 24
        if (a == 0) return 0
        fun ch(sh: Int): Int = ((((image shr sh) and 255) - ((low shr sh) and 255) + 255) / 2f).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Linear Light blend of [blend] over [base] with [opacity] (separable, Photoshop formula
     * base + 2 * blend - 1, composited with alpha), writing into [base]. Same size bitmaps.
     */
    fun linearLight(base: Bitmap, blend: Bitmap, opacity: Float) {
        val w = minOf(base.width, blend.width); val h = minOf(base.height, blend.height)
        val b = IntArray(w); val s = IntArray(w)
        val op = opacity.coerceIn(0f, 1f)
        for (y in 0 until h) {
            base.getPixels(b, 0, w, 0, y, w, 1)
            blend.getPixels(s, 0, w, 0, y, w, 1)
            var changed = false
            for (x in 0 until w) {
                val sc = s[x]
                val sa = (sc ushr 24) / 255f * op
                if (sa <= 0f) continue
                b[x] = compose(b[x], sc, sa)
                changed = true
            }
            if (changed) base.setPixels(b, 0, w, 0, y, w, 1)
        }
    }

    /** One pixel of [linearLight]: [src] with effective alpha [sa] over [dst] (unpremultiplied ARGB). */
    fun compose(dst: Int, src: Int, sa: Float): Int {
        val da = (dst ushr 24) / 255f
        val oa = sa + da * (1f - sa)
        if (oa <= 0f) return 0
        fun ch(sh: Int): Int {
            val cb = ((dst shr sh) and 255).toFloat()
            val cs = ((src shr sh) and 255).toFloat()
            val mixed = (cb + 2f * cs - 255f).coerceIn(0f, 255f)
            // Photoshop: where the backdrop is transparent the layer shows as it is.
            val co = sa * (1f - da) * cs + sa * da * mixed + (1f - sa) * da * cb
            return (co / oa).roundToInt().coerceIn(0, 255)
        }
        return ((oa * 255f).roundToInt().coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
