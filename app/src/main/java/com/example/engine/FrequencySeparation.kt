package com.example.engine

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Frequency separation, as in Photoshop: the image is split into a Low frequency layer (a
 * Gaussian blur: colour and tone) and a High frequency layer (fine texture, stored around mid
 * grey) that rebuilds the original exactly when blended over Low with [linearLight].
 */
object FrequencySeparation {

    /** Returns (low, high) for [source] with blur [sigma] (pixels of [source]). */
    fun split(source: Bitmap, sigma: Float, isCancelled: (() -> Boolean)? = null): Pair<Bitmap, Bitmap> {
        val low = ProEffects.gaussian(source, sigma.coerceAtLeast(0.3f), isCancelled)
        val w = source.width; val h = source.height
        val img = IntArray(w); val lo = IntArray(w); val out = IntArray(w)
        val high = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            if ((y and 63) == 0 && isCancelled?.invoke() == true) {
                low.recycle(); high.recycle()
                throw kotlinx.coroutines.CancellationException("Frequency separation cancelled")
            }
            source.getPixels(img, 0, w, 0, y, w, 1)
            low.getPixels(lo, 0, w, 0, y, w, 1)
            for (x in 0 until w) out[x] = highPixel(img[x], lo[x])
            high.setPixels(out, 0, w, 0, y, w, 1)
        }
        return low to high
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
