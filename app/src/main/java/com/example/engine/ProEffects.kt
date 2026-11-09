package com.example.engine

import android.graphics.Bitmap
import com.example.data.model.ProFilterType
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Settings of a Pro effect. What p1 / p2 / p3 mean depends on the effect (see [ProEffects.defaults]
 * and the panel). Centres are normalised to the layer image (0..1), so the preview (a smaller
 * image) and the real full-resolution apply put the centre at the same place.
 */
data class ProEffectParams(
    val p1: Float,
    val p2: Float = 0f,
    val p3: Float = 0f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    /** Radial blur: 0 = zoom, 1 = spin. */
    val mode: Int = 0,
    /** Tilt-shift focus line angle, degrees. */
    val angle: Float = 0f,
    /** Effect opacity 0..100 (mixes the result with the original). */
    val fade: Float = 100f
)

/**
 * Professional blur and effect filters.
 *
 * All blurs work on premultiplied colour, so transparent areas never bleed dark or coloured halos
 * into the image. Radii are fractional. Heavy filters run in horizontal strips (bounded memory),
 * are cancellable and report progress.
 *
 * - Gaussian blur: exact separable kernel for small radii, 3 box passes sized for the exact sigma
 *   for large radii (Kovesi), so the result is a true Gaussian of the chosen radius.
 * - Surface blur: edge-preserving guided filter (He et al.), O(1) per pixel for any radius.
 * - Oil paint: generalised Kuwahara (four weighted quadrants) + bristle texture.
 * - Sharpen: unsharp mask on luminance only (no colour halos) with a soft threshold.
 * - Noise reduction: separate luminance and colour noise (YCbCr), luminance by an edge-preserving
 *   filter with detail recovery, colour by a luma-guided filter (colour edges stay aligned).
 * - Motion / radial (zoom & spin) blur: bilinear sampling with recursive doubling (log passes), so
 *   long blurs are smooth with no ghost copies, at any length.
 * - Lens blur: disc (bokeh) kernel in linear light with highlight bloom.
 * - Tilt-shift: a sharp focus band with an adjustable angle, size and soft transition.
 * - High pass: detail layer (grey 50% + detail) for Overlay / Soft light sharpening.
 */
object ProEffects {

    /** Default settings and slider ranges live in the panel; these are the defaults per effect. */
    fun defaults(type: ProFilterType): ProEffectParams = when (type) {
        ProFilterType.GAUSSIAN_BLUR -> ProEffectParams(8f)
        ProFilterType.SURFACE_BLUR -> ProEffectParams(8f, 20f)
        ProFilterType.OIL_PAINT -> ProEffectParams(3f, 40f)
        ProFilterType.SHARPEN -> ProEffectParams(120f, 1.2f, 2f)
        ProFilterType.NOISE_REDUCTION -> ProEffectParams(40f, 50f, 50f)
        ProFilterType.MOTION_BLUR -> ProEffectParams(30f, 0f)
        ProFilterType.RADIAL_BLUR -> ProEffectParams(30f)
        ProFilterType.LENS_BLUR -> ProEffectParams(12f, 40f)
        ProFilterType.TILT_SHIFT -> ProEffectParams(14f, 25f, 25f)
        ProFilterType.HIGH_PASS -> ProEffectParams(4f)
    }

    /** True for effects whose centre is chosen by tapping the image. */
    fun usesCenter(type: ProFilterType) = type == ProFilterType.RADIAL_BLUR || type == ProFilterType.TILT_SHIFT

    /**
     * Pixel-sized settings shrink with the image so a downscaled preview looks like the
     * full-resolution result. [ratio] = previewSize / fullSize (<= 1).
     */
    fun scaleForPreview(type: ProFilterType, p: ProEffectParams, ratio: Float): ProEffectParams {
        val r = ratio.coerceIn(0.001f, 1f)
        return when (type) {
            ProFilterType.GAUSSIAN_BLUR, ProFilterType.SURFACE_BLUR, ProFilterType.MOTION_BLUR,
            ProFilterType.LENS_BLUR, ProFilterType.TILT_SHIFT, ProFilterType.HIGH_PASS -> p.copy(p1 = p.p1 * r)
            ProFilterType.OIL_PAINT -> p.copy(p1 = max(1f, p.p1 * r))
            ProFilterType.SHARPEN -> p.copy(p2 = p.p2 * r)
            // Noise is per pixel (the preview is noisier per pixel too); radial / zoom is relative.
            ProFilterType.NOISE_REDUCTION, ProFilterType.RADIAL_BLUR -> p
        }
    }

    /** Applies [type] to [source] and returns a new bitmap. */
    fun apply(
        source: Bitmap,
        type: ProFilterType,
        p: ProEffectParams,
        isCancelled: (() -> Boolean)? = null,
        onProgress: ((Float) -> Unit)? = null
    ): Bitmap {
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)
        val ctx = Ctx(isCancelled, onProgress)
        val out = when (type) {
            ProFilterType.GAUSSIAN_BLUR -> gaussianPixels(src, w, h, p.p1, ctx)
            ProFilterType.SURFACE_BLUR -> surfaceBlur(src, w, h, p.p1, p.p2, ctx)
            ProFilterType.OIL_PAINT -> oilPaint(src, w, h, p.p1, p.p2, ctx)
            ProFilterType.SHARPEN -> sharpen(src, w, h, p.p1, p.p2, p.p3, ctx)
            ProFilterType.NOISE_REDUCTION -> denoise(src, w, h, p.p1, p.p2, p.p3, ctx)
            ProFilterType.MOTION_BLUR -> motionBlur(src, w, h, p.p1, p.p2, ctx)
            ProFilterType.RADIAL_BLUR -> radialBlur(src, w, h, p.p1, p.mode, p.centerX, p.centerY, ctx)
            ProFilterType.LENS_BLUR -> lensBlur(src, w, h, p.p1, p.p2, ctx)
            ProFilterType.TILT_SHIFT -> tiltShift(src, w, h, p, ctx)
            ProFilterType.HIGH_PASS -> highPass(src, w, h, p.p1, ctx)
        }
        val fade = (p.fade / 100f).coerceIn(0f, 1f)
        if (fade < 0.999f) mix(src, out, fade)
        ctx.progress(1f)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    /** Gaussian blur of a bitmap (premultiplied, fractional radius = sigma). */
    fun gaussian(source: Bitmap, sigma: Float, isCancelled: (() -> Boolean)? = null): Bitmap {
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)
        val out = gaussianPixels(src, w, h, sigma, Ctx(isCancelled, null))
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    // ---------------------------------------------------------------------------------------------
    // Infrastructure
    // ---------------------------------------------------------------------------------------------

    private class Ctx(val isCancelled: (() -> Boolean)?, val onProgress: ((Float) -> Unit)?) {
        private var last = -1f
        fun check() {
            if (isCancelled?.invoke() == true) throw CancellationException("effect cancelled")
        }
        fun progress(f: Float) {
            val v = f.coerceIn(0f, 1f)
            if (onProgress != null && (v - last >= 0.02f || v >= 1f)) {
                last = v
                onProgress.invoke(v)
            }
        }
    }

    private fun mix(src: IntArray, out: IntArray, t: Float) {
        val ti = (t * 256f).roundToInt()
        val si = 256 - ti
        for (i in out.indices) {
            val a = src[i]; val b = out[i]
            val ca = (((a ushr 24) and 0xFF) * si + ((b ushr 24) and 0xFF) * ti) shr 8
            val cr = (((a shr 16) and 0xFF) * si + ((b shr 16) and 0xFF) * ti) shr 8
            val cg = (((a shr 8) and 0xFF) * si + ((b shr 8) and 0xFF) * ti) shr 8
            val cb = ((a and 0xFF) * si + (b and 0xFF) * ti) shr 8
            out[i] = (ca shl 24) or (cr shl 16) or (cg shl 8) or cb
        }
    }

    /** Non-premultiplied ARGB -> premultiplied ARGB (in place). */
    private fun premultiply(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 255) continue
            if (a == 0) { px[i] = 0; continue }
            val r = (((c shr 16) and 0xFF) * a + 127) / 255
            val g = (((c shr 8) and 0xFF) * a + 127) / 255
            val b = ((c and 0xFF) * a + 127) / 255
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Premultiplied ARGB -> non-premultiplied ARGB (in place). */
    private fun unpremultiply(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 255) continue
            if (a == 0) { px[i] = 0; continue }
            val r = min(255, (((c shr 16) and 0xFF) * 255 + a / 2) / a)
            val g = min(255, (((c shr 8) and 0xFF) * 255 + a / 2) / a)
            val b = min(255, ((c and 0xFF) * 255 + a / 2) / a)
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Runs [body] over horizontal strips of at most [rows] output rows. Each strip sees [pad] extra
     * rows above and below (clamped to the image) so neighbourhood filters are exact in the core.
     * body(paddedStart, paddedRows, coreOffset, coreRows).
     */
    private inline fun strips(h: Int, pad: Int, ctx: Ctx, p0: Float, p1: Float, rows: Int = 192, body: (Int, Int, Int, Int) -> Unit) {
        var y = 0
        while (y < h) {
            ctx.check()
            val core = min(rows, h - y)
            val ps = max(0, y - pad)
            val pe = min(h, y + core + pad)
            body(ps, pe - ps, y - ps, core)
            y += core
            ctx.progress(p0 + (p1 - p0) * y / h)
        }
    }

    /** Separable box mean of a float plane, radius [r] (window clamped at the borders). */
    private fun boxMean(src: FloatArray, dst: FloatArray, tmp: FloatArray, w: Int, h: Int, r: Int) {
        if (r <= 0) { System.arraycopy(src, 0, dst, 0, w * h); return }
        for (y in 0 until h) {
            val row = y * w
            var sum = 0.0
            var left = 0
            var right = -1
            for (x in 0 until w) {
                val nl = max(0, x - r)
                val nr = min(w - 1, x + r)
                while (right < nr) { right++; sum += src[row + right] }
                while (left < nl) { sum -= src[row + left]; left++ }
                tmp[row + x] = (sum / (nr - nl + 1)).toFloat()
            }
        }
        for (x in 0 until w) {
            var sum = 0.0
            var top = 0
            var bottom = -1
            for (y in 0 until h) {
                val nt = max(0, y - r)
                val nb = min(h - 1, y + r)
                while (bottom < nb) { bottom++; sum += tmp[bottom * w + x] }
                while (top < nt) { sum -= tmp[top * w + x]; top++ }
                dst[y * w + x] = (sum / (nb - nt + 1)).toFloat()
            }
        }
    }

    /**
     * Guided filter of [p] guided by [g] (He et al.), radius [r], regularisation [eps]
     * (in squared plane units). Writes the result into [out]. [t1]..[t5] are scratch planes.
     */
    private fun guided(
        g: FloatArray, p: FloatArray, out: FloatArray, w: Int, h: Int, r: Int, eps: Float,
        t1: FloatArray, t2: FloatArray, t3: FloatArray, t4: FloatArray, t5: FloatArray, tmp: FloatArray
    ) {
        val n = w * h
        boxMean(g, t1, tmp, w, h, r) // mean g
        boxMean(p, t2, tmp, w, h, r) // mean p
        for (i in 0 until n) t3[i] = g[i] * p[i]
        boxMean(t3, t4, tmp, w, h, r) // mean gp
        for (i in 0 until n) t3[i] = g[i] * g[i]
        boxMean(t3, t5, tmp, w, h, r) // mean gg
        for (i in 0 until n) {
            val varG = t5[i] - t1[i] * t1[i]
            val cov = t4[i] - t1[i] * t2[i]
            val a = cov / (varG + eps)
            t3[i] = a                 // a
            t4[i] = t2[i] - a * t1[i] // b
        }
        boxMean(t3, t1, tmp, w, h, r)
        boxMean(t4, t2, tmp, w, h, r)
        for (i in 0 until n) out[i] = t1[i] * g[i] + t2[i]
    }

    // ---------------------------------------------------------------------------------------------
    // Gaussian blur (premultiplied, exact sigma)
    // ---------------------------------------------------------------------------------------------

    /** Box sizes (odd) whose 3 passes approximate a Gaussian of [sigma] (Kovesi). */
    private fun boxesForGauss(sigma: Float, n: Int = 3): IntArray {
        val wIdeal = sqrt(12.0 * sigma * sigma / n + 1.0)
        var wl = floor(wIdeal).toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4.0)
        val m = mIdeal.roundToInt()
        return IntArray(n) { if (it < m) wl else wu }
    }

    private fun gaussianPixels(src: IntArray, w: Int, h: Int, sigma: Float, ctx: Ctx): IntArray {
        val out = src.copyOf()
        if (sigma < 0.3f || w <= 0 || h <= 0) return out
        premultiply(out)
        gaussianPremul(out, w, h, sigma, ctx, 0f, 1f)
        unpremultiply(out)
        return out
    }

    /** In-place Gaussian blur of premultiplied packed pixels. */
    private fun gaussianPremul(px: IntArray, w: Int, h: Int, sigma: Float, ctx: Ctx, p0: Float, p1: Float) {
        val tmp = IntArray(px.size)
        if (sigma < 3f) {
            val r = ceil(sigma * 3f).toInt().coerceAtLeast(1)
            val k = IntArray(2 * r + 1)
            var sum = 0.0
            val fk = DoubleArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2.0 * sigma * sigma)).also { v -> sum += v } }
            // Fixed point weights that add up to exactly 65536 (no brightness drift).
            var acc = 0
            for (i in k.indices) { k[i] = (fk[i] / sum * 65536.0).roundToInt(); acc += k[i] }
            k[r] += 65536 - acc
            convolve(px, tmp, w, h, k, r, horizontal = true, ctx)
            ctx.progress(p0 + (p1 - p0) * 0.5f)
            convolve(tmp, px, w, h, k, r, horizontal = false, ctx)
        } else {
            val boxes = boxesForGauss(sigma)
            for ((i, b) in boxes.withIndex()) {
                val r = (b - 1) / 2
                boxPass(px, tmp, w, h, r, horizontal = true, ctx)
                boxPass(tmp, px, w, h, r, horizontal = false, ctx)
                ctx.progress(p0 + (p1 - p0) * (i + 1) / boxes.size)
            }
        }
    }

    private fun convolve(src: IntArray, dst: IntArray, w: Int, h: Int, k: IntArray, r: Int, horizontal: Boolean, ctx: Ctx) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        for (line in 0 until lines) {
            if ((line and 63) == 0) ctx.check()
            val base = if (horizontal) line * w else line
            for (i in 0 until len) {
                var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
                for (j in -r..r) {
                    val q = (i + j).coerceIn(0, len - 1)
                    val c = src[base + q * step]
                    val kw = k[j + r]
                    sa += ((c ushr 24) and 0xFF).toLong() * kw
                    sr += ((c shr 16) and 0xFF).toLong() * kw
                    sg += ((c shr 8) and 0xFF).toLong() * kw
                    sb += (c and 0xFF).toLong() * kw
                }
                val a = ((sa + 32768) shr 16).toInt().coerceIn(0, 255)
                val rr = ((sr + 32768) shr 16).toInt().coerceIn(0, a)
                val g = ((sg + 32768) shr 16).toInt().coerceIn(0, a)
                val b = ((sb + 32768) shr 16).toInt().coerceIn(0, a)
                dst[base + i * step] = (a shl 24) or (rr shl 16) or (g shl 8) or b
            }
        }
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean, ctx: Ctx) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val win = 2 * r + 1
        val half = win / 2
        for (line in 0 until lines) {
            if ((line and 63) == 0) ctx.check()
            val base = if (horizontal) line * w else line
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (j in -r..r) {
                val c = src[base + j.coerceIn(0, len - 1) * step]
                sa += (c ushr 24) and 0xFF; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (i in 0 until len) {
                val a = (sa + half) / win
                dst[base + i * step] = (a shl 24) or (min(a, (sr + half) / win) shl 16) or (min(a, (sg + half) / win) shl 8) or min(a, (sb + half) / win)
                val cOut = src[base + (i - r).coerceIn(0, len - 1) * step]
                val cIn = src[base + (i + r + 1).coerceIn(0, len - 1) * step]
                sa += ((cIn ushr 24) and 0xFF) - ((cOut ushr 24) and 0xFF)
                sr += ((cIn shr 16) and 0xFF) - ((cOut shr 16) and 0xFF)
                sg += ((cIn shr 8) and 0xFF) - ((cOut shr 8) and 0xFF)
                sb += (cIn and 0xFF) - (cOut and 0xFF)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Surface blur (edge-preserving guided filter)
    // ---------------------------------------------------------------------------------------------

    private fun surfaceBlur(src: IntArray, w: Int, h: Int, radius: Float, threshold: Float, ctx: Ctx): IntArray {
        val r = radius.roundToInt().coerceIn(1, 100)
        // Threshold is in levels (like Photoshop): differences above it are edges and are kept.
        val t = (threshold.coerceIn(1f, 255f) / 255f)
        val eps = t * t
        val pre = src.copyOf()
        premultiply(pre)
        val out = IntArray(src.size)
        val pad = 2 * r + 1
        strips(h, pad, ctx, 0f, 1f) { ps, sh, co, ch ->
            val n = w * sh
            val planes = Array(4) { FloatArray(n) }
            for (i in 0 until n) {
                val c = pre[ps * w + i]
                planes[0][i] = ((c ushr 24) and 0xFF) / 255f
                planes[1][i] = ((c shr 16) and 0xFF) / 255f
                planes[2][i] = ((c shr 8) and 0xFF) / 255f
                planes[3][i] = (c and 0xFF) / 255f
            }
            val res = FloatArray(n)
            val t1 = FloatArray(n); val t2 = FloatArray(n); val t3 = FloatArray(n)
            val t4 = FloatArray(n); val t5 = FloatArray(n); val tmp = FloatArray(n)
            val results = Array(4) { FloatArray(0) }
            for (c in 0 until 4) {
                guided(planes[c], planes[c], res, w, sh, r, eps, t1, t2, t3, t4, t5, tmp)
                results[c] = res.copyOf()
            }
            for (y in co until co + ch) for (x in 0 until w) {
                val i = y * w + x
                val a = (results[0][i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val rr = (results[1][i] * 255f + 0.5f).toInt().coerceIn(0, a)
                val g = (results[2][i] * 255f + 0.5f).toInt().coerceIn(0, a)
                val b = (results[3][i] * 255f + 0.5f).toInt().coerceIn(0, a)
                out[(ps + y) * w + x] = (a shl 24) or (rr shl 16) or (g shl 8) or b
            }
        }
        unpremultiply(out)
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Oil paint (generalised Kuwahara + bristle texture)
    // ---------------------------------------------------------------------------------------------

    private fun oilPaint(src: IntArray, w: Int, h: Int, brush: Float, texture: Float, ctx: Ctx): IntArray {
        val q = brush.roundToInt().coerceIn(1, 12)
        val out = IntArray(src.size)
        val pad = 2 * q + 1
        strips(h, pad, ctx, 0f, 0.9f) { ps, sh, co, ch ->
            val n = w * sh
            val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n); val l = FloatArray(n); val l2 = FloatArray(n)
            for (i in 0 until n) {
                val c = src[ps * w + i]
                r[i] = ((c shr 16) and 0xFF) / 255f
                g[i] = ((c shr 8) and 0xFF) / 255f
                b[i] = (c and 0xFF) / 255f
                l[i] = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
                l2[i] = l[i] * l[i]
            }
            val tmp = FloatArray(n)
            val mr = FloatArray(n); val mg = FloatArray(n); val mb = FloatArray(n); val ml = FloatArray(n); val ml2 = FloatArray(n)
            boxMean(r, mr, tmp, w, sh, q); boxMean(g, mg, tmp, w, sh, q); boxMean(b, mb, tmp, w, sh, q)
            boxMean(l, ml, tmp, w, sh, q); boxMean(l2, ml2, tmp, w, sh, q)
            for (y in co until co + ch) {
                for (x in 0 until w) {
                    var wSum = 0f; var sr = 0f; var sg = 0f; var sb = 0f
                    // Four quadrant windows centred at (x +- q, y +- q).
                    for (k in 0 until 4) {
                        val qx = (x + if ((k and 1) == 0) -q else q).coerceIn(0, w - 1)
                        val qy = (y + if ((k and 2) == 0) -q else q).coerceIn(0, sh - 1)
                        val i = qy * w + qx
                        val v = max(0f, ml2[i] - ml[i] * ml[i]) + 1e-4f
                        val v2 = v * v
                        val wt = 1f / (v2 * v2) // sharp, painterly choice of the calmest quadrant
                        wSum += wt
                        sr += mr[i] * wt; sg += mg[i] * wt; sb += mb[i] * wt
                    }
                    val c = src[(ps + y) * w + x]
                    val a = c ushr 24
                    val rr = (sr / wSum * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val gg = (sg / wSum * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val bb = (sb / wSum * 255f + 0.5f).toInt().coerceIn(0, 255)
                    out[(ps + y) * w + x] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }
        // Bristle texture: light from the top left on the painted strokes.
        val k = (texture / 100f).coerceIn(0f, 1f) * 0.9f
        if (k > 0.01f) {
            val lum = FloatArray(w * h) {
                val c = out[it]
                0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            }
            for (y in 0 until h) {
                if ((y and 63) == 0) ctx.check()
                val ya = max(0, y - 1); val yb = min(h - 1, y + 1)
                for (x in 0 until w) {
                    val xa = max(0, x - 1); val xb = min(w - 1, x + 1)
                    val e = (lum[ya * w + xa] - lum[yb * w + xb]) * k
                    val c = out[y * w + x]
                    val rr = (((c shr 16) and 0xFF) + e).roundToInt().coerceIn(0, 255)
                    val gg = (((c shr 8) and 0xFF) + e).roundToInt().coerceIn(0, 255)
                    val bb = ((c and 0xFF) + e).roundToInt().coerceIn(0, 255)
                    out[y * w + x] = (c and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Sharpen (luminance unsharp mask with threshold) and high pass
    // ---------------------------------------------------------------------------------------------

    /** Float Gaussian blur of a plane (exact kernel below sigma 3, 3 boxes above). */
    private fun gaussPlane(src: FloatArray, dst: FloatArray, w: Int, h: Int, sigma: Float) {
        if (sigma < 0.3f) { System.arraycopy(src, 0, dst, 0, w * h); return }
        val tmp = FloatArray(w * h)
        if (sigma < 3f) {
            val r = ceil(sigma * 3f).toInt().coerceAtLeast(1)
            val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2f * sigma * sigma)) }
            val s = k.sum()
            for (i in k.indices) k[i] /= s
            for (y in 0 until h) for (x in 0 until w) {
                var acc = 0f
                for (j in -r..r) acc += src[y * w + (x + j).coerceIn(0, w - 1)] * k[j + r]
                tmp[y * w + x] = acc
            }
            for (y in 0 until h) for (x in 0 until w) {
                var acc = 0f
                for (j in -r..r) acc += tmp[(y + j).coerceIn(0, h - 1) * w + x] * k[j + r]
                dst[y * w + x] = acc
            }
        } else {
            val boxes = boxesForGauss(sigma)
            var cur = src
            val a = FloatArray(w * h)
            for ((i, b) in boxes.withIndex()) {
                val target = if (i == boxes.size - 1) dst else a
                boxMean(if (i == 0) src else cur, target, tmp, w, h, (b - 1) / 2)
                cur = target
            }
        }
    }

    private fun sharpen(src: IntArray, w: Int, h: Int, amountPct: Float, radius: Float, threshold: Float, ctx: Ctx): IntArray {
        val amt = (amountPct / 100f).coerceIn(0f, 5f)
        val sigma = radius.coerceIn(0.1f, 50f)
        val thr = threshold.coerceIn(0f, 255f)
        val out = src.copyOf()
        if (amt <= 0f) return out
        val pad = ceil(sigma * 3f).toInt() + 2
        strips(h, pad, ctx, 0f, 1f) { ps, sh, co, ch ->
            val n = w * sh
            val y = FloatArray(n)
            for (i in 0 until n) {
                val c = src[ps * w + i]
                y[i] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            }
            val blur = FloatArray(n)
            gaussPlane(y, blur, w, sh, sigma)
            for (row in co until co + ch) for (x in 0 until w) {
                val i = row * w + x
                var d = y[i] - blur[i]
                val ad = abs(d)
                // Soft threshold: small differences (noise, skin texture) are left alone.
                if (thr > 0f && ad < thr * 2f) {
                    val t = (ad / (thr * 2f))
                    d *= t * t
                }
                val delta = amt * d
                val gi = (ps + row) * w + x
                val c = src[gi]
                if ((c ushr 24) == 0) continue
                val rr = (((c shr 16) and 0xFF) + delta).roundToInt().coerceIn(0, 255)
                val gg = (((c shr 8) and 0xFF) + delta).roundToInt().coerceIn(0, 255)
                val bb = ((c and 0xFF) + delta).roundToInt().coerceIn(0, 255)
                out[gi] = (c and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        return out
    }

    private fun highPass(src: IntArray, w: Int, h: Int, radius: Float, ctx: Ctx): IntArray {
        val sigma = radius.coerceIn(0.1f, 100f)
        val out = IntArray(src.size)
        val pad = ceil(sigma * 3f).toInt() + 2
        strips(h, pad, ctx, 0f, 1f) { ps, sh, co, ch ->
            val n = w * sh
            val res = Array(3) { FloatArray(0) }
            for (c in 0 until 3) {
                val shift = 16 - c * 8
                val plane = FloatArray(n) { ((src[ps * w + it] shr shift) and 0xFF).toFloat() }
                val blur = FloatArray(n)
                gaussPlane(plane, blur, w, sh, sigma)
                for (i in 0 until n) blur[i] = plane[i] - blur[i] + 128f
                res[c] = blur
            }
            for (row in co until co + ch) for (x in 0 until w) {
                val i = row * w + x
                val gi = (ps + row) * w + x
                val a = src[gi] ushr 24
                out[gi] = (a shl 24) or
                    (res[0][i].roundToInt().coerceIn(0, 255) shl 16) or
                    (res[1][i].roundToInt().coerceIn(0, 255) shl 8) or
                    res[2][i].roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Noise reduction (luminance + colour, YCbCr)
    // ---------------------------------------------------------------------------------------------

    private fun denoise(src: IntArray, w: Int, h: Int, luminance: Float, color: Float, detail: Float, ctx: Ctx): IntArray {
        val sL = (luminance / 100f).coerceIn(0f, 1f)
        val sC = (color / 100f).coerceIn(0f, 1f)
        val keep = (detail / 100f).coerceIn(0f, 1f)
        val out = src.copyOf()
        if (sL < 0.005f && sC < 0.005f) return out
        val rL = 1 + (sL * 2f).roundToInt()
        val rC = 2 + (sC * 8f).roundToInt()
        val epsL = (0.012f + 0.09f * sL).let { it * it }
        val epsC = (0.01f + 0.06f * sC).let { it * it }
        val pad = 2 * max(rL, rC) + 2
        strips(h, pad, ctx, 0f, 1f) { ps, sh, co, ch ->
            val n = w * sh
            val yy = FloatArray(n); val cb = FloatArray(n); val cr = FloatArray(n)
            for (i in 0 until n) {
                val c = src[ps * w + i]
                val r = ((c shr 16) and 0xFF) / 255f
                val g = ((c shr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                yy[i] = 0.299f * r + 0.587f * g + 0.114f * b
                cb[i] = (b - yy[i]) * 0.564f
                cr[i] = (r - yy[i]) * 0.713f
            }
            val t1 = FloatArray(n); val t2 = FloatArray(n); val t3 = FloatArray(n)
            val t4 = FloatArray(n); val t5 = FloatArray(n); val tmp = FloatArray(n)
            val yOut = FloatArray(n)
            if (sL >= 0.005f) {
                guided(yy, yy, yOut, w, sh, rL, epsL, t1, t2, t3, t4, t5, tmp)
                // Detail recovery: bring back part of the fine structure the filter removed.
                val back = keep * 0.55f
                for (i in 0 until n) yOut[i] = yOut[i] + (yy[i] - yOut[i]) * back
            } else System.arraycopy(yy, 0, yOut, 0, n)
            val cbOut = FloatArray(n); val crOut = FloatArray(n)
            if (sC >= 0.005f) {
                // Colour noise: smoothed strongly, guided by the (clean) luminance so colours stay
                // inside their objects instead of bleeding across edges.
                guided(yOut, cb, cbOut, w, sh, rC, epsC, t1, t2, t3, t4, t5, tmp)
                guided(yOut, cr, crOut, w, sh, rC, epsC, t1, t2, t3, t4, t5, tmp)
            } else {
                System.arraycopy(cb, 0, cbOut, 0, n); System.arraycopy(cr, 0, crOut, 0, n)
            }
            for (row in co until co + ch) for (x in 0 until w) {
                val i = row * w + x
                val gi = (ps + row) * w + x
                val c = src[gi]
                val y0 = yOut[i]
                val r = y0 + crOut[i] / 0.713f
                val b = y0 + cbOut[i] / 0.564f
                val g = (y0 - 0.299f * r - 0.114f * b) / 0.587f
                out[gi] = (c and 0xFF000000.toInt()) or
                    ((r * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                    ((g * 255f).roundToInt().coerceIn(0, 255) shl 8) or
                    (b * 255f).roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Motion and radial blur (bilinear, recursive doubling)
    // ---------------------------------------------------------------------------------------------

    /** Bilinear sample of premultiplied packed pixels, clamped to the image. Returns packed ARGB. */
    private fun sample(px: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x = fx.coerceIn(0f, (w - 1).toFloat())
        val y = fy.coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
        val ax = ((x - x0) * 256f).toInt(); val ay = ((y - y0) * 256f).toInt()
        val c00 = px[y0 * w + x0]; val c10 = px[y0 * w + x1]; val c01 = px[y1 * w + x0]; val c11 = px[y1 * w + x1]
        var res = 0
        var shift = 0
        while (shift < 32) {
            val v00 = (c00 ushr shift) and 0xFF; val v10 = (c10 ushr shift) and 0xFF
            val v01 = (c01 ushr shift) and 0xFF; val v11 = (c11 ushr shift) and 0xFF
            val top = v00 * (256 - ax) + v10 * ax
            val bot = v01 * (256 - ax) + v11 * ax
            val v = (top * (256 - ay) + bot * ay + 32768) shr 16
            res = res or (v.coerceIn(0, 255) shl shift)
            shift += 8
        }
        return res
    }

    /** Average of two packed premultiplied colours; [up] alternates the rounding (no drift). */
    private fun avg2(a: Int, b: Int, up: Boolean): Int {
        val add = if (up) 1 else 0
        val ca = ((a ushr 24) + (b ushr 24) + add) shr 1
        val cr = (((a shr 16) and 0xFF) + ((b shr 16) and 0xFF) + add) shr 1
        val cg = (((a shr 8) and 0xFF) + ((b shr 8) and 0xFF) + add) shr 1
        val cb = ((a and 0xFF) + (b and 0xFF) + add) shr 1
        return (ca shl 24) or (min(ca, cr) shl 16) or (min(ca, cg) shl 8) or min(ca, cb)
    }

    /** Number of doubling passes so the sample spacing of a blur of [length] px is <= 1 px. */
    private fun passesFor(length: Float): Int = if (length < 1f) 0 else (ceil(ln(length.toDouble()) / ln(2.0)).toInt() + 1).coerceIn(1, 14)

    private fun motionBlur(src: IntArray, w: Int, h: Int, distance: Float, angleDeg: Float, ctx: Ctx): IntArray {
        val len = distance.coerceIn(0f, 2000f)
        var cur = src.copyOf()
        premultiply(cur)
        val k = passesFor(len)
        if (k == 0) { unpremultiply(cur); return cur }
        val rad = angleDeg * PI.toFloat() / 180f
        val dx = cos(rad); val dy = -sin(rad) // 0 deg = horizontal, counter-clockwise on screen
        var next = IntArray(cur.size)
        for (pass in 1..k) {
            // Each pass averages two copies shifted by +-s: after k passes 2^k evenly spaced samples
            // cover the whole length (a box blur along the direction).
            val s = len / (1 shl (pass + 1)).toFloat()
            val ox = dx * s; val oy = dy * s
            val up = pass % 2 == 0
            for (y in 0 until h) {
                if ((y and 63) == 0) ctx.check()
                for (x in 0 until w) {
                    next[y * w + x] = avg2(sample(cur, w, h, x + ox, y + oy), sample(cur, w, h, x - ox, y - oy), up)
                }
            }
            val t = cur; cur = next; next = t
            ctx.progress(pass.toFloat() / k)
        }
        unpremultiply(cur)
        return cur
    }

    private fun radialBlur(src: IntArray, w: Int, h: Int, amount: Float, mode: Int, cxN: Float, cyN: Float, ctx: Ctx): IntArray {
        val a = (amount / 100f).coerceIn(0f, 1f)
        var cur = src.copyOf()
        premultiply(cur)
        val cx = cxN.coerceIn(0f, 1f) * (w - 1)
        val cy = cyN.coerceIn(0f, 1f) * (h - 1)
        val maxR = run {
            val dx = max(cx, w - 1 - cx); val dy = max(cy, h - 1 - cy)
            sqrt(dx * dx + dy * dy)
        }
        val spin = mode == 1
        // Zoom: up to 50% scale; spin: up to 60 degrees of arc.
        val total = if (spin) a * (PI.toFloat() / 3f) else a * 0.5f
        val k = passesFor(total * maxR)
        if (k == 0) { unpremultiply(cur); return cur }
        var next = IntArray(cur.size)
        for (pass in 1..k) {
            val s = total / (1 shl (pass + 1)).toFloat()
            val up = pass % 2 == 0
            val cs = cos(s); val sn = sin(s)
            for (y in 0 until h) {
                if ((y and 63) == 0) ctx.check()
                val ry = y - cy
                for (x in 0 until w) {
                    val rx = x - cx
                    val c1: Int
                    val c2: Int
                    if (spin) {
                        c1 = sample(cur, w, h, cx + rx * cs - ry * sn, cy + rx * sn + ry * cs)
                        c2 = sample(cur, w, h, cx + rx * cs + ry * sn, cy - rx * sn + ry * cs)
                    } else {
                        c1 = sample(cur, w, h, cx + rx * (1f + s), cy + ry * (1f + s))
                        c2 = sample(cur, w, h, cx + rx * (1f - s), cy + ry * (1f - s))
                    }
                    next[y * w + x] = avg2(c1, c2, up)
                }
            }
            val t = cur; cur = next; next = t
            ctx.progress(pass.toFloat() / k)
        }
        unpremultiply(cur)
        return cur
    }

    // ---------------------------------------------------------------------------------------------
    // Lens blur (bokeh disc in linear light) and tilt-shift
    // ---------------------------------------------------------------------------------------------

    private val toLinear = FloatArray(256) { ((it / 255.0).pow(2.2)).toFloat() }

    private fun lensBlur(src: IntArray, w: Int, h: Int, radius: Float, highlights: Float, ctx: Ctx): IntArray {
        val r = radius.coerceIn(0f, 400f)
        if (r < 0.5f) return src.copyOf()
        val boost = (highlights / 100f).coerceIn(0f, 1f) * 6f
        // Large discs are computed on a reduced image (the disc stays smooth) and scaled back.
        val f = max(1, ceil(r / 14f).toInt())
        val sw = (w + f - 1) / f
        val shh = (h + f - 1) / f
        val n = sw * shh
        val pa = FloatArray(n); val pr = FloatArray(n); val pg = FloatArray(n); val pb = FloatArray(n)
        // Linear light, premultiplied, highlights boosted so bright points bloom into discs.
        for (y in 0 until h) {
            if ((y and 63) == 0) ctx.check()
            for (x in 0 until w) {
                val c = src[y * w + x]
                val a = ((c ushr 24) and 0xFF) / 255f
                if (a <= 0f) continue
                val lr = toLinear[(c shr 16) and 0xFF]; val lg = toLinear[(c shr 8) and 0xFF]; val lb = toLinear[c and 0xFF]
                val lum = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
                val t = ((lum - 0.55f) / 0.45f).coerceIn(0f, 1f)
                val m = 1f + boost * t * t * (3f - 2f * t)
                val i = (y / f) * sw + (x / f)
                pa[i] += a; pr[i] += lr * m * a; pg[i] += lg * m * a; pb[i] += lb * m * a
            }
        }
        val norm = 1f / (f * f)
        for (i in 0 until n) { pa[i] *= norm; pr[i] *= norm; pg[i] *= norm; pb[i] *= norm }
        ctx.progress(0.15f)
        val rs = r / f
        val ri = ceil(rs).toInt()
        // Half-widths of the disc rows (with a soft, anti-aliased rim weight).
        val spans = IntArray(2 * ri + 1) { val dy = it - ri; val v = rs * rs - dy * dy; if (v < 0f) -1 else sqrt(v).toInt() }
        var count = 0
        for (s in spans) if (s >= 0) count += 2 * s + 1
        val inv = 1f / count.coerceAtLeast(1)
        val planes = arrayOf(pa, pr, pg, pb)
        val outPlanes = Array(4) { FloatArray(n) }
        val prefix = FloatArray((sw + 1) * shh)
        for ((pi, plane) in planes.withIndex()) {
            for (y in 0 until shh) {
                var acc = 0f
                val base = y * (sw + 1)
                prefix[base] = 0f
                for (x in 0 until sw) { acc += plane[y * sw + x]; prefix[base + x + 1] = acc }
            }
            val o = outPlanes[pi]
            for (y in 0 until shh) {
                if ((y and 31) == 0) ctx.check()
                for (x in 0 until sw) {
                    var sum = 0f
                    for (j in spans.indices) {
                        val s = spans[j]
                        if (s < 0) continue
                        val yy = (y + j - ri).coerceIn(0, shh - 1)
                        val base = yy * (sw + 1)
                        // Clamp the span at the borders by edge extension.
                        val x0 = x - s; val x1 = x + s
                        val l = x0.coerceIn(0, sw - 1); val rr = x1.coerceIn(0, sw - 1)
                        var v = prefix[base + rr + 1] - prefix[base + l]
                        if (x0 < 0) v += plane[yy * sw] * (-x0)
                        if (x1 > sw - 1) v += plane[yy * sw + sw - 1] * (x1 - (sw - 1))
                        sum += v
                    }
                    o[y * sw + x] = sum * inv
                }
            }
            ctx.progress(0.15f + 0.8f * (pi + 1) / 4f)
        }
        // Back to full size (bilinear) and to sRGB.
        val out = IntArray(src.size)
        val invGamma = 1.0 / 2.2
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / f - 0.5f).coerceIn(0f, (shh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(shh - 1, y0 + 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / f - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(sw - 1, x0 + 1); val tx = fx - x0
                fun bil(p: FloatArray): Float {
                    val top = p[y0 * sw + x0] * (1 - tx) + p[y0 * sw + x1] * tx
                    val bot = p[y1 * sw + x0] * (1 - tx) + p[y1 * sw + x1] * tx
                    return top * (1 - ty) + bot * ty
                }
                val a = bil(outPlanes[0])
                if (a <= 1e-4f) { out[y * w + x] = 0; continue }
                fun enc(v: Float) = ((v / a).coerceIn(0f, 1f).toDouble().pow(invGamma) * 255.0 + 0.5).toInt().coerceIn(0, 255)
                val ai = (a * 255f + 0.5f).toInt().coerceIn(0, 255)
                out[y * w + x] = (ai shl 24) or (enc(bil(outPlanes[1])) shl 16) or (enc(bil(outPlanes[2])) shl 8) or enc(bil(outPlanes[3]))
            }
        }
        return out
    }

    private fun tiltShift(src: IntArray, w: Int, h: Int, p: ProEffectParams, ctx: Ctx): IntArray {
        val blurred = src.copyOf()
        val sigma = p.p1.coerceIn(0f, 200f)
        if (sigma >= 0.3f) {
            premultiply(blurred)
            gaussianPremul(blurred, w, h, sigma, ctx, 0f, 0.85f)
            unpremultiply(blurred)
        }
        val cx = p.centerX.coerceIn(0f, 1f) * w
        val cy = p.centerY.coerceIn(0f, 1f) * h
        val rad = p.angle * PI.toFloat() / 180f
        val nx = -sin(rad); val ny = cos(rad) // normal of the focus line
        val ref = h.toFloat().coerceAtLeast(1f)
        val half = (p.p2 / 100f).coerceIn(0f, 1f) * ref / 2f
        val trans = max(1f, (p.p3 / 100f).coerceIn(0f, 1f) * ref)
        val out = IntArray(src.size)
        for (y in 0 until h) {
            if ((y and 63) == 0) ctx.check()
            for (x in 0 until w) {
                val d = abs((x - cx) * nx + (y - cy) * ny)
                val t0 = ((d - half) / trans).coerceIn(0f, 1f)
                val t = t0 * t0 * (3f - 2f * t0)
                val i = y * w + x
                if (t <= 0f) { out[i] = src[i]; continue }
                if (t >= 1f) { out[i] = blurred[i]; continue }
                val ti = (t * 256f).toInt(); val si = 256 - ti
                val a = src[i]; val b = blurred[i]
                out[i] = ((((a ushr 24) * si + (b ushr 24) * ti) shr 8) shl 24) or
                    (((((a shr 16) and 0xFF) * si + ((b shr 16) and 0xFF) * ti) shr 8) shl 16) or
                    (((((a shr 8) and 0xFF) * si + ((b shr 8) and 0xFF) * ti) shr 8) shl 8) or
                    (((a and 0xFF) * si + (b and 0xFF) * ti) shr 8)
            }
        }
        return out
    }
}
