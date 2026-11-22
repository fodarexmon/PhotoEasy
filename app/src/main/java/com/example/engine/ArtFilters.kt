package com.example.engine

import android.graphics.Bitmap
import com.example.data.model.ProFilterType
import kotlinx.coroutines.CancellationException
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Artistic presets: oil painting and charcoal drawing. Every size is relative to the image, so the
 * downscaled preview and the full-resolution export look the same.
 */
object ArtFilters {

    private fun check(isCancelled: (() -> Boolean)?) {
        if (isCancelled?.invoke() == true) throw CancellationException("Filter cancelled")
    }

    // ---------------------------------------------------------------------------------------------
    // Oil painting
    // ---------------------------------------------------------------------------------------------

    /**
     * Paint strokes come from two passes of the generalised Kuwahara filter at a working size of
     * at most 1600 px (so the brush stays in proportion on large photos), then the strokes are
     * re-sharpened at full size with bristle relief, a faint canvas weave and richer colour.
     */
    fun oilPainting(source: Bitmap, isCancelled: (() -> Boolean)? = null): Bitmap {
        val w = source.width
        val h = source.height
        val maxDim = max(w, h)
        val workScale = min(1f, 1600f / maxDim)
        val ww = max(1, (w * workScale).roundToInt())
        val wh = max(1, (h * workScale).roundToInt())
        val work = if (workScale < 1f) Bitmap.createScaledBitmap(source, ww, wh, true) else source
        val brush = (max(ww, wh) / 230f).coerceIn(1.5f, 8f)
        check(isCancelled)
        val pass1 = ProEffects.apply(work, ProFilterType.OIL_PAINT, ProEffectParams(brush, 0f), isCancelled)
        val pass2 = ProEffects.apply(pass1, ProFilterType.OIL_PAINT, ProEffectParams(max(1f, brush * 0.6f), 0f), isCancelled)
        val big = if (workScale < 1f) Bitmap.createScaledBitmap(pass2, w, h, true) else pass2
        // Full size: a small Kuwahara pass gives the upscaled strokes crisp edges back, with relief.
        val texture = if (maxDim >= 900) 70f else 45f
        val painted = ProEffects.apply(big, ProFilterType.OIL_PAINT, ProEffectParams(if (maxDim >= 900) 2f else 1f, texture), isCancelled)
        // Richer colour and a little contrast, as oil pigments look.
        val toned = FilterRecipes.render(painted, FilterRecipe(contrast = 0.08f, vibrance = 0.25f, highlightRolloff = 0.3f), isCancelled)

        val px = IntArray(w * h)
        toned.getPixels(px, 0, w, 0, 0, w, h)
        val period = max(4f, maxDim / 320f)
        // The weave is a canvas detail: faint on small previews, where it would turn into a pattern.
        val weaveAmp = 5f * ((maxDim - 300f) / 900f).coerceIn(0.2f, 1f)
        val twoPi = (2.0 * PI).toFloat()
        for (y in 0 until h) {
            if ((y and 63) == 0) check(isCancelled)
            val sy = sin(y * twoPi / period)
            for (x in 0 until w) {
                val i = y * w + x
                val p = px[i]
                val a = p ushr 24
                if (a == 0) continue
                val weave = (sin(x * twoPi / period) * 0.5f + sy * 0.5f) * weaveAmp
                val rr = (((p shr 16) and 0xFF) + weave).roundToInt().coerceIn(0, 255)
                val gg = (((p shr 8) and 0xFF) + weave).roundToInt().coerceIn(0, 255)
                val bb = ((p and 0xFF) + weave).roundToInt().coerceIn(0, 255)
                px[i] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        // Keep the source alpha.
        val src = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)
        for (i in px.indices) px[i] = (src[i] and 0xFF000000.toInt()) or (px[i] and 0x00FFFFFF)
        listOf(work, pass1, pass2, big, painted, toned).distinct().forEach { if (it !== source && !it.isRecycled) it.recycle() }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }

    // ---------------------------------------------------------------------------------------------
    // Charcoal drawing
    // ---------------------------------------------------------------------------------------------

    /**
     * Charcoal on paper: outlines from a colour-dodge sketch, smudged tonal shading, diagonal
     * stroke texture in the dark areas and paper grain on a warm off-white sheet.
     */
    fun charcoal(source: Bitmap, isCancelled: (() -> Boolean)? = null): Bitmap {
        val w = source.width
        val h = source.height
        val n = w * h
        val maxDim = max(w, h).toFloat()
        val px = IntArray(n)
        source.getPixels(px, 0, w, 0, 0, w, h)

        val gray = FloatArray(n)
        for (i in 0 until n) {
            val p = px[i]
            gray[i] = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        }
        check(isCancelled)
        // Outlines: colour dodge of the grey over its blurred negative.
        val inv = FloatArray(n) { 1f - gray[it] }
        val invBlur = FloatArray(n)
        blur(inv, invBlur, w, h, max(1f, maxDim / 260f))
        check(isCancelled)
        // Smudged shading: the charcoal is rubbed into broad tones.
        val shade = FloatArray(n)
        blur(gray, shade, w, h, max(1f, maxDim / 180f))
        check(isCancelled)

        val strokeW = max(1.2f, maxDim / 420f)
        val strokeL = max(8f, maxDim / 35f)
        val grainCell = max(1, (maxDim / 1400f).roundToInt())
        for (y in 0 until h) {
            if ((y and 63) == 0) check(isCancelled)
            for (x in 0 until w) {
                val i = y * w + x
                val a = px[i] ushr 24
                if (a == 0) continue
                val g = gray[i]
                // Line layer: 1 = paper, darker where edges are.
                val dodge = (g / (1f - invBlur[i] + 0.02f)).coerceIn(0f, 1f)
                val line = dodge.pow(3.2f)
                // Tone layer, posterised softly into a few charcoal values.
                val s = (shade[i] * 0.6f + g * 0.4f)
                val tone = smooth(0.08f, 0.92f, s)
                val darkness = (1f - tone).pow(1.3f)
                // Diagonal strokes (value noise stretched along 45°), strongest in mid-dark areas.
                val u = (x + y) * 0.7071f / strokeW
                val v = (x - y) * 0.7071f / strokeL
                val streak = valueNoise(u, v)
                val strokes = darkness * (0.8f + 0.5f * streak)
                var out = min(line, 1f - strokes.coerceIn(0f, 0.96f))
                // Paper tooth: charcoal skips the raised grain, lighter specks in the dark.
                val tooth = hash(x / grainCell, y / grainCell)
                out += (1f - out) * 0.12f * max(0f, tooth) * darkness
                out -= 0.03f * tooth * (1f - darkness)
                out = out.coerceIn(0f, 1f)
                // Warm off-white paper, neutral black charcoal.
                val r = (24f + (243f - 24f) * out).roundToInt()
                val gg = (24f + (239f - 24f) * out).roundToInt()
                val b = (26f + (229f - 26f) * out).roundToInt()
                px[i] = (a shl 24) or (r shl 16) or (gg shl 8) or b
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Deterministic hash noise in [-1, 1]. */
    private fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return ((h and 0xFFFF) / 32767.5f) - 1f
    }

    /** Smooth value noise in [0, 1]. */
    private fun valueNoise(u: Float, v: Float): Float {
        val iu = floor(u).toInt()
        val iv = floor(v).toInt()
        val fu = u - iu
        val fv = v - iv
        val su = fu * fu * (3f - 2f * fu)
        val sv = fv * fv * (3f - 2f * fv)
        val a = hash(iu, iv); val b = hash(iu + 1, iv); val c = hash(iu, iv + 1); val d = hash(iu + 1, iv + 1)
        val top = a + (b - a) * su
        val bot = c + (d - c) * su
        return ((top + (bot - top) * sv) * 0.5f + 0.5f).coerceIn(0f, 1f)
    }

    /** Approximate Gaussian blur of a plane: three box passes. */
    internal fun blur(src: FloatArray, dst: FloatArray, w: Int, h: Int, sigma: Float) {
        val r = max(1, (sigma * 0.87f).roundToInt())
        val a = src.copyOf()
        val tmp = FloatArray(w * h)
        repeat(3) {
            boxH(a, tmp, w, h, r)
            boxV(tmp, a, w, h, r)
        }
        System.arraycopy(a, 0, dst, 0, w * h)
    }

    private fun boxH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            for (k in -r..r) acc += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = acc * inv
                acc += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
    }

    private fun boxV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        for (x in 0 until w) {
            var acc = 0f
            for (k in -r..r) acc += src[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = acc * inv
                acc += src[(y + r + 1).coerceAtMost(h - 1) * w + x] - src[(y - r).coerceAtLeast(0) * w + x]
            }
        }
    }
}
