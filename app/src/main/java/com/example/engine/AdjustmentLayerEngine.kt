package com.example.engine

import android.graphics.Bitmap
import com.example.data.model.AdjustmentKind
import com.example.data.model.AdjustmentLayerParams
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pixel processing of the Photoshop-style adjustment layer kinds that are not plain
 * ColorAdjustments: Levels, Exposure, Color Balance, Black & White, Photo Filter, Gradient Map,
 * Invert, Posterize and Threshold. Works on straight (unpremultiplied) colour and keeps alpha.
 */
object AdjustmentLayerEngine {

    /** Kinds edited through ColorAdjustments (their pixels are handled by ImageProcessor). */
    fun usesColorAdjustments(kind: AdjustmentKind): Boolean = when (kind) {
        AdjustmentKind.GENERAL, AdjustmentKind.BRIGHTNESS_CONTRAST, AdjustmentKind.CURVES,
        AdjustmentKind.VIBRANCE, AdjustmentKind.HUE_SATURATION -> true
        else -> false
    }

    /** True when [kind] with [p] leaves every pixel unchanged. */
    fun isIdentity(kind: AdjustmentKind, p: AdjustmentLayerParams): Boolean = when (kind) {
        AdjustmentKind.LEVELS -> p.levelsInBlack <= 0f && p.levelsInWhite >= 255f && p.levelsGamma == 1f &&
            p.levelsOutBlack <= 0f && p.levelsOutWhite >= 255f
        AdjustmentKind.EXPOSURE -> p.exposureEv == 0f && p.exposureOffset == 0f && p.exposureGamma == 1f
        AdjustmentKind.COLOR_BALANCE -> (p.balanceShadows + p.balanceMidtones + p.balanceHighlights).all { it == 0f }
        AdjustmentKind.PHOTO_FILTER -> p.photoFilterDensity <= 0f
        AdjustmentKind.BLACK_WHITE, AdjustmentKind.GRADIENT_MAP, AdjustmentKind.INVERT,
        AdjustmentKind.POSTERIZE, AdjustmentKind.THRESHOLD -> false
        else -> true
    }

    /** Applies [kind] to [source]; returns a new bitmap, or [source] when nothing changes. */
    fun apply(source: Bitmap, kind: AdjustmentKind, p: AdjustmentLayerParams, isCancelled: (() -> Boolean)? = null): Bitmap {
        if (isIdentity(kind, p)) return source
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source
        val row = IntArray(w)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val op = pixelOp(kind, p)
        for (y in 0 until h) {
            if ((y and 63) == 0 && isCancelled?.invoke() == true) {
                out.recycle()
                throw CancellationException("Adjustment layer cancelled")
            }
            source.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val a = c ushr 24
                if (a == 0) continue
                row[x] = (a shl 24) or (op(c) and 0x00FFFFFF)
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    private fun lum(r: Float, g: Float, b: Float) = 0.299f * r + 0.587f * g + 0.114f * b

    private fun pack(r: Float, g: Float, b: Float): Int {
        val ri = (r * 255f).roundToInt().coerceIn(0, 255)
        val gi = (g * 255f).roundToInt().coerceIn(0, 255)
        val bi = (b * 255f).roundToInt().coerceIn(0, 255)
        return (ri shl 16) or (gi shl 8) or bi
    }

    /** Moves (r,g,b) so its luminance becomes [target], staying in gamut where possible. */
    private fun withLuminance(r: Float, g: Float, b: Float, target: Float): Int {
        val d = target - lum(r, g, b)
        return pack(r + d, g + d, b + d)
    }

    /** A per-pixel function (input and output 0xRRGGBB in the low bits) for [kind]. */
    private fun pixelOp(kind: AdjustmentKind, p: AdjustmentLayerParams): (Int) -> Int = when (kind) {
        AdjustmentKind.LEVELS -> {
            val lut = IntArray(256)
            val inB = p.levelsInBlack.coerceIn(0f, 253f)
            val inW = p.levelsInWhite.coerceIn(inB + 2f, 255f)
            val gamma = p.levelsGamma.coerceIn(0.1f, 9.99f)
            for (v in 0..255) {
                val t = ((v - inB) / (inW - inB)).coerceIn(0f, 1f).pow(1f / gamma)
                lut[v] = (p.levelsOutBlack + (p.levelsOutWhite - p.levelsOutBlack) * t).roundToInt().coerceIn(0, 255)
            }
            ({ c: Int -> (lut[(c shr 16) and 255] shl 16) or (lut[(c shr 8) and 255] shl 8) or lut[c and 255] })
        }
        AdjustmentKind.EXPOSURE -> {
            // In linear light, like Photoshop: exposure scales, offset shifts the shadows, gamma bends.
            val lut = IntArray(256)
            val scale = 2f.pow(p.exposureEv)
            val invGamma = 1f / p.exposureGamma.coerceIn(0.1f, 9.99f)
            for (v in 0..255) {
                val lin = srgbToLinear(v / 255f)
                val e = (lin * scale + p.exposureOffset).coerceIn(0f, 1f).pow(invGamma)
                lut[v] = (linearToSrgb(e) * 255f).roundToInt().coerceIn(0, 255)
            }
            ({ c: Int -> (lut[(c shr 16) and 255] shl 16) or (lut[(c shr 8) and 255] shl 8) or lut[c and 255] })
        }
        AdjustmentKind.COLOR_BALANCE -> {
            val s = p.balanceShadows; val m = p.balanceMidtones; val hl = p.balanceHighlights
            val k = 0.6f / 100f
            ({ c: Int ->
                val r = ((c shr 16) and 255) / 255f
                val g = ((c shr 8) and 255) / 255f
                val b = (c and 255) / 255f
                val l = lum(r, g, b)
                // Shadows / midtones / highlights weights that add up to 1.
                val ws = (1f - l) * (1f - l)
                val wh = l * l
                val wm = 1f - ws - wh
                var nr = r + (ws * s[0] + wm * m[0] + wh * hl[0]) * k * (if (s[0] + m[0] + hl[0] >= 0) 1f - r else r)
                var ng = g + (ws * s[1] + wm * m[1] + wh * hl[1]) * k * (if (s[1] + m[1] + hl[1] >= 0) 1f - g else g)
                var nb = b + (ws * s[2] + wm * m[2] + wh * hl[2]) * k * (if (s[2] + m[2] + hl[2] >= 0) 1f - b else b)
                nr = nr.coerceIn(0f, 1f); ng = ng.coerceIn(0f, 1f); nb = nb.coerceIn(0f, 1f)
                if (p.balancePreserveLuminosity) withLuminance(nr, ng, nb, l) else pack(nr, ng, nb)
            })
        }
        AdjustmentKind.BLACK_WHITE -> {
            // Photoshop's method: grey = min + (mid - min) * secondary-colour weight + (max - mid) * primary weight.
            val wR = p.bwWeights[0] / 100f; val wY = p.bwWeights[1] / 100f; val wG = p.bwWeights[2] / 100f
            val wC = p.bwWeights[3] / 100f; val wB = p.bwWeights[4] / 100f; val wM = p.bwWeights[5] / 100f
            val tint = if (p.bwTint) hslToRgb(p.bwTintHue, p.bwTintSaturation / 100f) else null
            ({ c: Int ->
                val r = ((c shr 16) and 255) / 255f
                val g = ((c shr 8) and 255) / 255f
                val b = (c and 255) / 255f
                val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val mid = r + g + b - mx - mn
                val primary: Float
                val secondary: Float
                when {
                    r >= g && r >= b -> { primary = wR; secondary = if (g >= b) wY else wM }
                    g >= r && g >= b -> { primary = wG; secondary = if (r >= b) wY else wC }
                    else -> { primary = wB; secondary = if (r >= g) wM else wC }
                }
                val grey = (mn + (mid - mn) * secondary + (mx - mid) * primary).coerceIn(0f, 1f)
                if (tint == null) pack(grey, grey, grey)
                else withLuminance(tint[0] * grey * 2f, tint[1] * grey * 2f, tint[2] * grey * 2f, grey)
            })
        }
        AdjustmentKind.PHOTO_FILTER -> {
            val fr = ((p.photoFilterColor shr 16) and 255) / 255f
            val fg = ((p.photoFilterColor shr 8) and 255) / 255f
            val fb = (p.photoFilterColor and 255) / 255f
            val d = (p.photoFilterDensity / 100f).coerceIn(0f, 1f)
            ({ c: Int ->
                val r = ((c shr 16) and 255) / 255f
                val g = ((c shr 8) and 255) / 255f
                val b = (c and 255) / 255f
                // Like a coloured lens: multiply by the filter colour, mixed by density.
                val nr = r + (r * fr - r) * d
                val ng = g + (g * fg - g) * d
                val nb = b + (b * fb - b) * d
                if (p.photoFilterPreserveLuminosity) withLuminance(nr, ng, nb, lum(r, g, b)) else pack(nr, ng, nb)
            })
        }
        AdjustmentKind.GRADIENT_MAP -> {
            val a = if (p.gradientReverse) p.gradientEnd else p.gradientStart
            val z = if (p.gradientReverse) p.gradientStart else p.gradientEnd
            val lut = IntArray(256) { v ->
                val t = v / 255f
                val r = ((a shr 16) and 255) + (((z shr 16) and 255) - ((a shr 16) and 255)) * t
                val g = ((a shr 8) and 255) + (((z shr 8) and 255) - ((a shr 8) and 255)) * t
                val b = (a and 255) + ((z and 255) - (a and 255)) * t
                (r.roundToInt() shl 16) or (g.roundToInt() shl 8) or b.roundToInt()
            }
            ({ c: Int ->
                val l = lum(((c shr 16) and 255).toFloat(), ((c shr 8) and 255).toFloat(), (c and 255).toFloat())
                lut[l.roundToInt().coerceIn(0, 255)]
            })
        }
        AdjustmentKind.INVERT -> ({ c: Int -> c.inv() and 0x00FFFFFF })
        AdjustmentKind.POSTERIZE -> {
            val n = p.posterizeLevels.coerceIn(2, 255)
            val lut = IntArray(256) { v -> ((v * (n - 1) + 127) / 255) * 255 / (n - 1) }
            ({ c: Int -> (lut[(c shr 16) and 255] shl 16) or (lut[(c shr 8) and 255] shl 8) or lut[c and 255] })
        }
        AdjustmentKind.THRESHOLD -> {
            val t = p.thresholdLevel.coerceIn(1, 255)
            ({ c: Int ->
                val l = lum(((c shr 16) and 255).toFloat(), ((c shr 8) and 255).toFloat(), (c and 255).toFloat())
                if (l >= t) 0xFFFFFF else 0
            })
        }
        else -> ({ c: Int -> c })
    }

    private fun srgbToLinear(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    private fun linearToSrgb(v: Float) = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f

    /** Mid-lightness colour of [hueDeg] / [sat] (0..1) as r,g,b 0..1. */
    private fun hslToRgb(hueDeg: Float, sat: Float): FloatArray {
        val hsv = floatArrayOf(((hueDeg % 360f) + 360f) % 360f, sat.coerceIn(0f, 1f), 1f)
        val c = android.graphics.Color.HSVToColor(hsv)
        // Normalised so the colour's own luminance is 0.5 (grey * 2 then keeps the grey's level).
        val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
        val l = lum(r, g, b).coerceAtLeast(0.01f)
        return floatArrayOf(r * 0.5f / l, g * 0.5f / l, b * 0.5f / l)
    }

    // --- Persistence ------------------------------------------------------------------------

    private fun arr(a: FloatArray) = JSONArray().apply { a.forEach { put(it.toDouble()) } }
    private fun arr(o: JSONObject, key: String, def: FloatArray): FloatArray {
        val j = o.optJSONArray(key) ?: return def
        if (j.length() != def.size) return def
        return FloatArray(def.size) { j.optDouble(it, def[it].toDouble()).toFloat() }
    }

    fun toJson(p: AdjustmentLayerParams): JSONObject = JSONObject().apply {
        put("levelsInBlack", p.levelsInBlack.toDouble()); put("levelsInWhite", p.levelsInWhite.toDouble())
        put("levelsGamma", p.levelsGamma.toDouble()); put("levelsOutBlack", p.levelsOutBlack.toDouble())
        put("levelsOutWhite", p.levelsOutWhite.toDouble())
        put("exposureEv", p.exposureEv.toDouble()); put("exposureOffset", p.exposureOffset.toDouble())
        put("exposureGamma", p.exposureGamma.toDouble())
        put("balanceShadows", arr(p.balanceShadows)); put("balanceMidtones", arr(p.balanceMidtones))
        put("balanceHighlights", arr(p.balanceHighlights)); put("balancePreserveLuminosity", p.balancePreserveLuminosity)
        put("bwWeights", arr(p.bwWeights)); put("bwTint", p.bwTint)
        put("bwTintHue", p.bwTintHue.toDouble()); put("bwTintSaturation", p.bwTintSaturation.toDouble())
        put("photoFilterColor", p.photoFilterColor); put("photoFilterDensity", p.photoFilterDensity.toDouble())
        put("photoFilterPreserveLuminosity", p.photoFilterPreserveLuminosity)
        put("gradientStart", p.gradientStart); put("gradientEnd", p.gradientEnd); put("gradientReverse", p.gradientReverse)
        put("posterizeLevels", p.posterizeLevels); put("thresholdLevel", p.thresholdLevel)
    }

    fun fromJson(o: JSONObject?): AdjustmentLayerParams {
        val d = AdjustmentLayerParams()
        if (o == null) return d
        fun f(k: String, v: Float) = o.optDouble(k, v.toDouble()).toFloat()
        return AdjustmentLayerParams(
            levelsInBlack = f("levelsInBlack", d.levelsInBlack), levelsInWhite = f("levelsInWhite", d.levelsInWhite),
            levelsGamma = f("levelsGamma", d.levelsGamma), levelsOutBlack = f("levelsOutBlack", d.levelsOutBlack),
            levelsOutWhite = f("levelsOutWhite", d.levelsOutWhite),
            exposureEv = f("exposureEv", d.exposureEv), exposureOffset = f("exposureOffset", d.exposureOffset),
            exposureGamma = f("exposureGamma", d.exposureGamma),
            balanceShadows = arr(o, "balanceShadows", d.balanceShadows), balanceMidtones = arr(o, "balanceMidtones", d.balanceMidtones),
            balanceHighlights = arr(o, "balanceHighlights", d.balanceHighlights),
            balancePreserveLuminosity = o.optBoolean("balancePreserveLuminosity", d.balancePreserveLuminosity),
            bwWeights = arr(o, "bwWeights", d.bwWeights), bwTint = o.optBoolean("bwTint", d.bwTint),
            bwTintHue = f("bwTintHue", d.bwTintHue), bwTintSaturation = f("bwTintSaturation", d.bwTintSaturation),
            photoFilterColor = o.optInt("photoFilterColor", d.photoFilterColor),
            photoFilterDensity = f("photoFilterDensity", d.photoFilterDensity),
            photoFilterPreserveLuminosity = o.optBoolean("photoFilterPreserveLuminosity", d.photoFilterPreserveLuminosity),
            gradientStart = o.optInt("gradientStart", d.gradientStart), gradientEnd = o.optInt("gradientEnd", d.gradientEnd),
            gradientReverse = o.optBoolean("gradientReverse", d.gradientReverse),
            posterizeLevels = o.optInt("posterizeLevels", d.posterizeLevels), thresholdLevel = o.optInt("thresholdLevel", d.thresholdLevel)
        )
    }
}
