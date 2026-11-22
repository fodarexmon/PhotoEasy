package com.example.engine

import android.graphics.Bitmap
import com.example.data.model.BlendIf

/**
 * Photoshop's Blend If. A layer pixel keeps (weight(this) * weight(under)) of its alpha, where
 * the weights come from the pixel's own value and from the value of the image under it.
 */
object BlendIfEngine {

    /** Value of [c] (ARGB) on [channel]: 0 = grey (luminosity), 1..3 = R, G, B. */
    fun valueOf(c: Int, channel: Int): Int {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        return when (channel) {
            1 -> r
            2 -> g
            3 -> b
            else -> (r * 77 + g * 151 + b * 28) shr 8
        }
    }

    /**
     * How much a value [v] shows (0..1) for one slider: fully between [low1] and [high0], fading
     * from [low0] to [low1] and from [high0] to [high1], hidden outside.
     */
    fun weight(v: Float, low0: Float, low1: Float, high0: Float, high1: Float): Float {
        val lo = when {
            v >= low1 -> 1f
            v < low0 -> 0f
            low1 <= low0 -> 1f
            else -> (v - low0) / (low1 - low0)
        }
        if (lo <= 0f) return 0f
        val hi = when {
            v <= high0 -> 1f
            v > high1 -> 0f
            high1 <= high0 -> 1f
            else -> (high1 - v) / (high1 - high0)
        }
        return lo * hi
    }

    /** Weight table (256 entries) for one slider. */
    private fun table(low0: Float, low1: Float, high0: Float, high1: Float, identity: Boolean): FloatArray? =
        if (identity) null else FloatArray(256) { weight(it.toFloat(), low0, low1, high0, high1) }

    /** Unpremultiplied ARGB pixels [from]..[to] of [layer] faded by Blend If against [under]. */
    fun applyPixels(layer: IntArray, under: IntArray?, b: BlendIf, from: Int = 0, to: Int = layer.size) {
        val tw = table(b.thisLow0, b.thisLow1, b.thisHigh0, b.thisHigh1, b.thisIsDefault)
        val uw = table(b.underLow0, b.underLow1, b.underHigh0, b.underHigh1, b.underIsDefault)
        for (i in from until to) {
            val c = layer[i]
            val a = c ushr 24
            if (a == 0) continue
            var w = 1f
            if (tw != null) w *= tw[valueOf(c, b.channel)]
            if (uw != null && under != null) w *= uw[valueOf(under[i], b.channel)]
            if (w >= 1f) continue
            val na = (a * w + 0.5f).toInt().coerceIn(0, 255)
            layer[i] = if (na == 0) 0 else (na shl 24) or (c and 0xFFFFFF)
        }
    }

    /**
     * [layerBitmap] (canvas sized) with Blend If applied against [under] (the image below,
     * same size). Changes [layerBitmap] itself when it is mutable, else returns a new bitmap.
     */
    fun apply(layerBitmap: Bitmap, under: Bitmap, b: BlendIf): Bitmap {
        val out = if (layerBitmap.isMutable) layerBitmap else layerBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width; val h = out.height
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        // The image under the layer, on the same pixels (nothing under it outside [under]).
        val up = if (b.underIsDefault) null else IntArray(w * h).also { u ->
            val cw = minOf(w, under.width); val ch = minOf(h, under.height)
            under.getPixels(u, 0, w, 0, 0, cw, ch)
        }
        ImageProcessor.parallelRows(h) { y0, y1 -> applyPixels(px, up, b, y0 * w, y1 * w) }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }
}
