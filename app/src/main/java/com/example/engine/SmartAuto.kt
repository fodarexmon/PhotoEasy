package com.example.engine

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.example.data.model.ColorAdjustments
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One-button Smart Auto. Reads the image as it currently looks and finds the corrections it
 * still needs:
 *  - the kind of picture (night, low key, high key / snow, sunset, backlit) so an intended mood
 *    is kept instead of every image being pushed to mid grey;
 *  - exposure metered on faces when there are any (backlit faces get their shadows lifted);
 *  - white balance from several estimates (neutral pixels, neutral edges, brightest areas, skin)
 *    weighted by how much they agree, held back for sunsets and one-colour scenes;
 *  - black / white points, highlights / shadows, haze, colour;
 *  - a gentle automatic tone curve for flat mid-tones;
 *  - sharpening only when the image is clean, measured at full detail;
 * and checks its own result: the corrections are applied, the result measured and adjusted.
 */
object SmartAuto {

    enum class Scene { NORMAL, NIGHT, LOW_KEY, HIGH_KEY, SUNSET, BACKLIT }

    class Result(
        /** Corrections to add to the current adjustments (see [ImageProcessor.combineSmartAuto]). */
        val delta: ColorAdjustments,
        val scene: Scene,
        /** Visible noise: sharpening was skipped and noise reduction is worth trying. */
        val noisy: Boolean,
        val faces: Int
    )

    /** Tone and colour measurements of opaque pixels. */
    class Stats(
        val count: Int,
        val hist: IntArray,
        val mean: Float,
        val std: Float,
        val p005: Int, val p01: Int, val median: Int, val p99: Int, val p995: Int,
        val darkFrac: Float, val brightFrac: Float,
        val clipLow: Float, val clipHigh: Float,
        val meanChroma: Float,
        /** Share of strongly warm (orange / red / yellow) pixels. */
        val warmFrac: Float,
        /** Mean colour of near-neutral mid-tones and their share. */
        val neutral: FloatArray?, val neutralFrac: Float,
        /** Mean colour of the brightest unclipped pixels. */
        val brightest: FloatArray?,
        /** Mean absolute gradient per channel (grey-edge). */
        val edges: FloatArray?
    ) {
        fun percentile(q: Double): Int {
            val target = (count * q).toLong()
            var acc = 0L
            for (v in 0 until 256) { acc += hist[v]; if (acc > target) return v }
            return 255
        }
    }

    fun luma(r: Int, g: Int, b: Int): Int = (r * 54 + g * 183 + b * 19) shr 8

    fun measure(px: IntArray, w: Int, h: Int): Stats {
        val hist = IntArray(256)
        var count = 0
        var sum = 0.0; var sum2 = 0.0; var chroma = 0.0
        var nR = 0.0; var nG = 0.0; var nB = 0.0; var nN = 0
        var warm = 0
        for (p in px) {
            if ((p ushr 24) < 128) continue
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            val l = luma(r, g, b)
            hist[l]++; count++
            sum += l; sum2 += l.toDouble() * l
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            chroma += (mx - mn) / 255.0
            if (l in 40..225 && mx - mn < 60) { nR += r; nG += g; nB += b; nN++ }
            // Warm: red the largest, blue clearly the smallest, and colourful.
            if (mx == r && mn == b && mx - mn > 70 && g > b) warm++
        }
        if (count == 0) return Stats(0, hist, 0f, 0f, 0, 0, 0, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f, null, 0f, null, null)
        fun pct(q: Double): Int {
            val target = (count * q).toLong(); var acc = 0L
            for (v in 0 until 256) { acc += hist[v]; if (acc > target) return v }
            return 255
        }
        val mean = sum / count
        val std = sqrt((sum2 / count - mean * mean).coerceAtLeast(0.0)).toFloat()
        val p99 = pct(0.99)
        // Brightest unclipped pixels (white patch).
        var bR = 0.0; var bG = 0.0; var bB = 0.0; var bN = 0
        val brightCut = max(pct(0.97), 60)
        // Grey edge: mean absolute horizontal / vertical differences per channel.
        var eR = 0.0; var eG = 0.0; var eB = 0.0; var eN = 0
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val p = px[i]
            if ((p ushr 24) < 128) continue
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            val l = luma(r, g, b)
            if (l >= brightCut && r < 250 && g < 250 && b < 250) { bR += r; bG += g; bB += b; bN++ }
            if (x + 1 < w && y + 1 < h) {
                val q1 = px[i + 1]; val q2 = px[i + w]
                if ((q1 ushr 24) >= 128 && (q2 ushr 24) >= 128) {
                    val dr = abs(r - ((q1 shr 16) and 255)) + abs(r - ((q2 shr 16) and 255))
                    val dg = abs(g - ((q1 shr 8) and 255)) + abs(g - ((q2 shr 8) and 255))
                    val db = abs(b - (q1 and 255)) + abs(b - (q2 and 255))
                    // Real edges only, not noise or flat colour.
                    if (dr + dg + db > 24) { eR += dr; eG += dg; eB += db; eN++ }
                }
            }
        }
        return Stats(
            count = count, hist = hist, mean = mean.toFloat(), std = std,
            p005 = pct(0.005), p01 = pct(0.01), median = pct(0.5).coerceAtLeast(1), p99 = p99, p995 = pct(0.995),
            darkFrac = (0..35).sumOf { hist[it] }.toFloat() / count,
            brightFrac = (230..255).sumOf { hist[it] }.toFloat() / count,
            clipLow = (hist[0] + hist[1]).toFloat() / count,
            clipHigh = (hist[254] + hist[255]).toFloat() / count,
            meanChroma = (chroma / count).toFloat(),
            warmFrac = warm.toFloat() / count,
            neutral = if (nN > 0) floatArrayOf((nR / nN).toFloat(), (nG / nN).toFloat(), (nB / nN).toFloat()) else null,
            neutralFrac = nN.toFloat() / count,
            brightest = if (bN > count * 0.003) floatArrayOf((bR / bN).toFloat(), (bG / bN).toFloat(), (bB / bN).toFloat()) else null,
            edges = if (eN > count * 0.01) floatArrayOf((eR / eN).toFloat(), (eG / eN).toFloat(), (eB / eN).toFloat()) else null
        )
    }

    /** (temperature, tint) that would make a colour with these channel means neutral. */
    fun wbFor(r: Float, g: Float, b: Float, expectBoverR: Float = 1f, expectGG: Float = 1f): Pair<Float, Float> {
        val ar = r.coerceAtLeast(1f); val ag = g.coerceAtLeast(1f); val ab = b.coerceAtLeast(1f)
        val q = (ab / ar) / expectBoverR
        val x = (q - 1f) / (q + 1f)
        val temp = x / 0.18f * 100f
        val gg = (((ar + ab) / 2f) / ag) / expectGG
        val tint = (gg - 1f) / 0.12f * 100f
        return temp to tint
    }

    /** Mean colour and luma of skin-like pixels in the middle of each face (normalised boxes). */
    private fun skin(px: IntArray, w: Int, h: Int, faces: List<RectF>): Pair<FloatArray, Int>? {
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        val lumas = ArrayList<Int>()
        for (f in faces) {
            // The central part of the face box: cheeks, nose, forehead.
            val x0 = ((f.left + f.width() * 0.25f) * w).toInt().coerceIn(0, w - 1)
            val x1 = ((f.right - f.width() * 0.25f) * w).toInt().coerceIn(0, w - 1)
            val y0 = ((f.top + f.height() * 0.3f) * h).toInt().coerceIn(0, h - 1)
            val y1 = ((f.bottom - f.height() * 0.2f) * h).toInt().coerceIn(0, h - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val p = px[y * w + x]
                if ((p ushr 24) < 128) continue
                val pr = (p shr 16) and 255; val pg = (p shr 8) and 255; val pb = p and 255
                val l = luma(pr, pg, pb)
                // Not eyes, brows, teeth or hair: skin is mid-light with red > green > blue (roughly).
                if (l < 25 || l > 245 || pr < pb) continue
                r += pr; g += pg; b += pb; n++; lumas.add(l)
            }
        }
        if (n < 20) return null
        lumas.sort()
        return floatArrayOf((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat()) to lumas[lumas.size / 2]
    }

    /**
     * Noise level (standard deviation in levels) from [detail] (a full-detail crop), robust to
     * texture: Immerkaer's mask on the flatter half of the pixels.
     */
    fun noiseSigma(detail: Bitmap?): Float {
        if (detail == null || detail.isRecycled || detail.width < 16 || detail.height < 16) return 0f
        val w = detail.width; val h = detail.height
        val px = IntArray(w * h); detail.getPixels(px, 0, w, 0, 0, w, h)
        val l = IntArray(w * h) { val p = px[it]; luma((p shr 16) and 255, (p shr 8) and 255, p and 255) }
        val vals = FloatArray((w - 2) * (h - 2))
        val grads = FloatArray(vals.size)
        var k = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val v = l[i - w - 1] - 2 * l[i - w] + l[i - w + 1] - 2 * l[i - 1] + 4 * l[i] - 2 * l[i + 1] + l[i + w - 1] - 2 * l[i + w] + l[i + w + 1]
            vals[k] = abs(v).toFloat()
            grads[k] = (abs(l[i + 1] - l[i - 1]) + abs(l[i + w] - l[i - w])).toFloat()
            k++
        }
        // Flat areas only: the half of the pixels with the smallest gradient.
        val cut = grads.copyOf().also { it.sort() }[grads.size / 2]
        var s = 0.0; var n = 0
        for (i in vals.indices) if (grads[i] <= cut) { s += vals[i]; n++ }
        if (n == 0) return 0f
        return (sqrt(Math.PI / 2.0) / 6.0 * s / n).toFloat()
    }

    fun classify(st: Stats, faceLuma: Int?): Scene = when {
        faceLuma != null && faceLuma < st.median - 30 && st.brightFrac > 0.12f -> Scene.BACKLIT
        st.median < 45 && st.darkFrac > 0.45f && st.p995 > 180 -> Scene.NIGHT
        st.median < 70 && st.darkFrac > 0.35f && st.p995 > 170 -> Scene.LOW_KEY
        st.median > 165 && st.meanChroma < 0.16f && st.p005 > 40 -> Scene.HIGH_KEY
        st.warmFrac > 0.25f && st.meanChroma > 0.22f -> Scene.SUNSET
        faceLuma == null && st.darkFrac > 0.25f && st.brightFrac > 0.2f -> Scene.BACKLIT
        else -> Scene.NORMAL
    }

    private fun log2(v: Double) = ln(v) / ln(2.0)

    /** First pass: exposure, tone, colour sliders from [st]. */
    private fun sliders(st: Stats, scene: Scene, faceLuma: Int?, skinColor: FloatArray?): ColorAdjustments {
        // Exposure: faces to a pleasant skin level, otherwise the median to mid grey, kept within
        // what the kind of picture allows.
        val evGlobal = if (scene == Scene.HIGH_KEY) log2(max(st.median, 175).toDouble() / st.median) else log2(118.0 / st.median)
        var ev = if (faceLuma != null) 0.7 * log2(140.0 / faceLuma.coerceAtLeast(8)) + 0.3 * evGlobal else evGlobal
        if (abs(ev) < 0.2) ev = 0.0
        ev *= 0.75
        val (lo, hi) = when (scene) {
            Scene.NIGHT -> -0.2 to 0.35
            Scene.LOW_KEY -> -0.3 to 0.45
            Scene.HIGH_KEY -> -0.1 to 0.6
            else -> -1.0 to 1.2
        }
        ev = ev.coerceIn(lo, hi)
        // Brightening stops before the highlights clip; what is left goes to the shadows.
        var shadowsExtra = 0f
        if (ev > 0 && st.p995 > 0) {
            val room = log2(250.0 / st.p995).coerceAtLeast(0.0) + 0.15
            if (ev > room) { shadowsExtra = ((ev - room) * 45).toFloat(); ev = room }
        }
        val exposure = (ev * 50).toFloat().coerceIn(-60f, 60f)

        val blacks = when {
            scene == Scene.NIGHT || scene == Scene.LOW_KEY -> 0f
            st.p005 > 10 -> -((st.p005 - 4) * 4f).coerceAtMost(60f)
            st.clipLow > 0.03f -> 15f
            else -> 0f
        }
        val whites = when {
            scene == Scene.HIGH_KEY -> if (st.p995 < 235) ((245 - st.p995) * 2f).coerceIn(0f, 30f) else 0f
            st.p995 < 240 && ev <= 0.2 -> ((248 - st.p995) * 3f).coerceIn(0f, 50f)
            st.clipHigh > 0.03f -> -15f
            else -> 0f
        }
        val highlights = when {
            scene == Scene.HIGH_KEY -> 0f
            st.brightFrac > 0.08f -> -(st.brightFrac * 220f).coerceAtMost(45f)
            else -> 0f
        }
        var shadows = when (scene) {
            Scene.NIGHT, Scene.LOW_KEY -> 0f
            Scene.BACKLIT -> (25f + st.darkFrac * 80f).coerceAtMost(55f)
            else -> if (st.darkFrac > 0.15f) (st.darkFrac * 120f).coerceAtMost(40f) else 0f
        }
        shadows = (shadows + shadowsExtra).coerceAtMost(65f)
        // Only for really flat images; the tone curve does the rest.
        val contrast = when {
            st.std < 30f && scene != Scene.HIGH_KEY -> ((34f - st.std) * 1.0f).coerceIn(0f, 15f)
            st.std > 78f -> -((st.std - 78f) * 0.6f).coerceAtMost(15f)
            else -> 0f
        }

        val (temperature, tint) = whiteBalance(st, scene, skinColor)
        val vibrance = when {
            scene == Scene.SUNSET -> 0f
            st.meanChroma < 0.2f -> ((0.24f - st.meanChroma) * 120f).coerceIn(0f, if (skinColor != null) 15f else 30f)
            else -> 0f
        }
        val dehaze = if (st.p005 > 35 && st.meanChroma < 0.18f && scene != Scene.HIGH_KEY) ((st.p005 - 30) * 0.8f).coerceIn(0f, 30f) else 0f
        return ColorAdjustments(
            exposure = exposure, contrast = contrast, highlights = highlights, shadows = shadows,
            whites = whites, blacks = blacks, temperature = temperature, tint = tint,
            vibrance = vibrance, dehaze = dehaze
        )
    }

    /** White balance from several estimates, weighted by how trustworthy and how consistent they are. */
    fun whiteBalance(st: Stats, scene: Scene, skinColor: FloatArray?): Pair<Float, Float> {
        val est = ArrayList<Triple<Float, Float, Float>>() // temp, tint, weight
        st.neutral?.let { n ->
            val (t, ti) = wbFor(n[0], n[1], n[2])
            est.add(Triple(t, ti, (st.neutralFrac / 0.2f).coerceIn(0f, 1f)))
        }
        st.edges?.let { e -> val (t, ti) = wbFor(e[0], e[1], e[2]); est.add(Triple(t, ti, 0.7f)) }
        st.brightest?.let { b ->
            val (t, ti) = wbFor(b[0], b[1], b[2])
            est.add(Triple(t, ti, if (st.p99 > 180) 0.6f else 0.2f))
        }
        // Typical skin: blue about 0.72 of red, (red + blue) / 2 about 1.05 x green.
        skinColor?.let { s -> val (t, ti) = wbFor(s[0], s[1], s[2], 0.72f, 1.05f); est.add(Triple(t, ti, 1.5f)) }
        val wsum = est.sumOf { it.third.toDouble() }.toFloat()
        if (wsum < 0.15f) return 0f to 0f
        val mt = est.sumOf { (it.first * it.third).toDouble() }.toFloat() / wsum
        val mi = est.sumOf { (it.second * it.third).toDouble() }.toFloat() / wsum
        // Estimates that disagree mean the cast is not certain.
        val spread = sqrt(est.sumOf { (((it.first - mt) * (it.first - mt)) * it.third).toDouble() }.toFloat() / wsum)
        var conf = 1f / (1f + spread / 12f)
        // One-colour scenes (forest, coloured wall): few neutrals to trust.
        if (st.neutralFrac < 0.08f && skinColor == null) conf *= 0.5f
        var temp = mt * 0.65f * conf
        var tint = mi * 0.6f * conf
        if (scene == Scene.SUNSET || scene == Scene.NIGHT) temp = temp.coerceAtLeast(-6f)
        temp = temp.coerceIn(-40f, 40f); tint = tint.coerceIn(-30f, 30f)
        if (abs(temp) < 3f) temp = 0f
        if (abs(tint) < 3f) tint = 0f
        return temp to tint
    }

    /** A gentle tone curve (partial equalisation of the mid-tones) for flat images, or null. */
    fun toneCurve(st: Stats, scene: Scene): List<PointF>? {
        if (scene == Scene.NIGHT || scene == Scene.HIGH_KEY || st.std >= 52f || st.count < 100) return null
        val cdf = DoubleArray(256)
        var acc = 0.0
        for (v in 0 until 256) { acc += st.hist[v]; cdf[v] = acc / st.count }
        val xs = intArrayOf(64, 128, 192)
        val amount = ((52f - st.std) / 30f).coerceIn(0.15f, 0.4f)
        var prev = 0f
        val pts = ArrayList<PointF>()
        pts.add(PointF(0f, 0f))
        for (x in xs) {
            val eq = (255.0 * cdf[x]).toFloat()
            var y = x + (eq - x) * amount
            y = y.coerceIn(x - 16f, x + 16f).coerceAtLeast(prev + 8f)
            pts.add(PointF(x.toFloat(), y)); prev = y
        }
        pts.add(PointF(255f, 255f))
        return if (pts.all { abs(it.y - it.x) < 2f }) null else pts
    }

    private fun plus(a: ColorAdjustments, b: ColorAdjustments) = ImageProcessor.combineSmartAuto(a, b, 1f)

    /**
     * Smart Auto for [source] as it looks with [current]. [faces] are face boxes normalised to
     * 0..1; [detail] is a crop at full detail for the noise measurement.
     */
    fun analyze(source: Bitmap, current: ColorAdjustments = ColorAdjustments(), faces: List<RectF> = emptyList(), detail: Bitmap? = null): Result {
        val thumb = ImageProcessor.scaleBitmapToMaxDimension(source, 512)
        try {
            fun pixelsWith(adj: ColorAdjustments): IntArray {
                val look = ImageProcessor.applyAdjustments(thumb, adj)
                val px = IntArray(look.width * look.height)
                look.getPixels(px, 0, look.width, 0, 0, look.width, look.height)
                if (look !== thumb && !look.isRecycled) look.recycle()
                return px
            }
            val w = thumb.width; val h = thumb.height
            val px0 = pixelsWith(current)
            val st0 = measure(px0, w, h)
            if (st0.count < 16) return Result(ColorAdjustments(), Scene.NORMAL, false, 0)
            val sk0 = if (faces.isNotEmpty()) skin(px0, w, h, faces) else null
            val scene = classify(st0, sk0?.second)

            // Pass 1.
            var delta = sliders(st0, scene, sk0?.second, sk0?.first)

            // Pass 2: measure the result and correct what is still off (half of it).
            val px1 = pixelsWith(plus(current, delta))
            val st1 = measure(px1, w, h)
            val sk1 = if (faces.isNotEmpty()) skin(px1, w, h, faces) else null
            val target = when {
                sk1 != null -> null
                scene == Scene.NIGHT || scene == Scene.LOW_KEY || scene == Scene.HIGH_KEY -> null
                else -> 118
            }
            var fixEv = 0.0
            if (sk1 != null) {
                val d = log2(140.0 / sk1.second.coerceAtLeast(8))
                if (abs(d) > 0.15) fixEv = d * 0.5
            } else if (target != null && abs(st1.median - target) > 12) {
                fixEv = log2(target.toDouble() / st1.median) * 0.5
            }
            if (fixEv > 0 && st1.p995 > 0) fixEv = min(fixEv, log2(252.0 / st1.p995).coerceAtLeast(0.0))
            val (rt, rti) = whiteBalance(st1, scene, sk1?.first)
            delta = plus(delta, ColorAdjustments(
                exposure = (fixEv * 50).toFloat().coerceIn(-20f, 20f),
                temperature = (rt * 0.5f).coerceIn(-8f, 8f),
                tint = (rti * 0.5f).coerceIn(-8f, 8f)
            ))

            // Intended warmth (sunset, night lights) stays: limit cooling and tint after both passes.
            if (scene == Scene.SUNSET || scene == Scene.NIGHT) {
                delta = delta.copy(temperature = delta.temperature.coerceAtLeast(-6f), tint = delta.tint.coerceIn(-6f, 6f))
            }

            // Tone curve from the corrected image; kept only if it does not clip more.
            val st2 = measure(pixelsWith(plus(current, delta)), w, h)
            val noBaseCurve = !current.hasCurves()
            var curve = if (noBaseCurve) toneCurve(st2, scene) else null
            if (curve != null) {
                val withCurve = delta.copy(curvesRgb = curve)
                val st3 = measure(pixelsWith(plus(current, withCurve)), w, h)
                if (st3.clipHigh > st2.clipHigh + 0.015f || st3.clipLow > st2.clipLow + 0.015f) {
                    // Halve the curve.
                    curve = curve.map { PointF(it.x, it.x + (it.y - it.x) / 2f) }
                }
                delta = delta.copy(curvesRgb = curve)
            }

            // Detail: sharpen clean images only, softly on faces.
            val sigma = if (detail != null) noiseSigma(detail) else noiseSigma(thumb) * 1.6f
            val noisy = sigma > 3.2f
            val sharp = when {
                noisy -> 0f
                sigma < 1.6f -> if (faces.isNotEmpty()) 10f else 20f
                else -> if (faces.isNotEmpty()) 5f else 10f
            }
            val clarity = if (!noisy && faces.isEmpty() && st2.std < 60f && scene != Scene.HIGH_KEY) 10f else 0f
            delta = delta.copy(sharpness = sharp, clarity = clarity)
            return Result(delta, scene, noisy, faces.size)
        } finally {
            if (thumb !== source && !thumb.isRecycled) thumb.recycle()
        }
    }
}
