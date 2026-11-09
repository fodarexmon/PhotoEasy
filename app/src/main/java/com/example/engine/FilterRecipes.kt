package com.example.engine

import android.graphics.Bitmap
import android.graphics.PointF
import com.example.data.model.FilterType
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Groups used by the filter browser. */
enum class FilterCategory { PORTRAIT, LANDSCAPE, FILM, BLACK_WHITE, CREATIVE }

/**
 * A preset described the way photo editors build looks: tone curves, white balance, saturation
 * (with skin protection), split toning, matte fade and finishing effects — instead of a single
 * linear colour matrix, which clips highlights and turns blacks milky.
 *
 * All sizes are relative to the image, so the preview proxy and the full-resolution export look alike.
 */
data class FilterRecipe(
    val exposure: Float = 0f,            // stops
    val contrast: Float = 0f,            // -1..1, S-curve strength
    val curve: List<PointF>? = null,     // master curve, 0..255
    val curveR: List<PointF>? = null,
    val curveG: List<PointF>? = null,
    val curveB: List<PointF>? = null,
    val fade: Float = 0f,                // 0..1 matte (lifted, soft blacks)
    val highlightRolloff: Float = 0f,    // 0..1 film-like shoulder, keeps highlight detail
    val temperature: Float = 0f,         // -1 cool … 1 warm
    val tint: Float = 0f,                // -1 green … 1 magenta
    val saturation: Float = 0f,          // -1..1
    val vibrance: Float = 0f,            // -1..1 (boosts muted colours most)
    val protectSkin: Boolean = false,    // keep skin tones natural
    val shadowTint: Int = 0x808080,      // split toning colours (RGB)
    val shadowAmount: Float = 0f,
    val highlightTint: Int = 0x808080,
    val highlightAmount: Float = 0f,
    val mono: Triple<Float, Float, Float>? = null, // B&W channel mixer weights
    val grain: Float = 0f,               // 0..1
    val grainSize: Float = 1f,
    val vignette: Float = 0f,            // 0..1
    val glow: Float = 0f,                // 0..1 highlight bloom
    val lightLeak: Float = 0f,           // 0..1
    val leakColor: Int = 0xFF7A2E
)

object FilterRecipes {

    private fun pts(vararg v: Int): List<PointF> = v.toList().chunked(2).map { PointF(it[0].toFloat(), it[1].toFloat()) }

    /** Recipe per preset; null = a special pixel effect handled by [ImageProcessor]. */
    fun recipeFor(type: FilterType): FilterRecipe? = when (type) {
        FilterType.NONE -> null
        FilterType.PIXELATE, FilterType.EDGES, FilterType.GLITCH, FilterType.SOLARIZE -> null

        FilterType.VINTAGE -> FilterRecipe(
            contrast = -0.1f, fade = 0.45f, highlightRolloff = 0.5f, temperature = 0.35f, saturation = -0.25f,
            curveB = pts(0, 22, 128, 120, 255, 228),
            shadowTint = 0x5A6A80, shadowAmount = 0.25f, highlightTint = 0xF2D7A8, highlightAmount = 0.35f,
            grain = 0.35f, vignette = 0.35f
        )
        FilterType.SEPIA -> FilterRecipe(
            mono = Triple(0.3f, 0.59f, 0.11f), contrast = 0.05f, fade = 0.15f, highlightRolloff = 0.4f,
            shadowTint = 0x4A3020, shadowAmount = 0.7f, highlightTint = 0xF0D8B0, highlightAmount = 0.65f, grain = 0.2f
        )
        FilterType.GRAYSCALE -> FilterRecipe(mono = Triple(0.3f, 0.59f, 0.11f), contrast = 0.12f, highlightRolloff = 0.3f)
        FilterType.CYBERPUNK -> FilterRecipe(
            contrast = 0.3f, temperature = -0.45f, tint = 0.55f, saturation = 0.25f,
            shadowTint = 0x2030A0, shadowAmount = 0.55f, highlightTint = 0xFF40C0, highlightAmount = 0.45f, glow = 0.3f, vignette = 0.3f
        )
        FilterType.CINEMATIC -> FilterRecipe(
            contrast = 0.22f, fade = 0.12f, highlightRolloff = 0.6f, saturation = -0.1f, protectSkin = true,
            shadowTint = 0x2A5A70, shadowAmount = 0.4f, highlightTint = 0xF0C890, highlightAmount = 0.25f, vignette = 0.4f
        )
        FilterType.HDR -> FilterRecipe(
            contrast = 0.15f, curve = pts(0, 0, 50, 72, 128, 132, 200, 196, 255, 248), vibrance = 0.4f, highlightRolloff = 0.7f
        )
        FilterType.WARM -> FilterRecipe(temperature = 0.45f, vibrance = 0.15f, highlightRolloff = 0.4f, protectSkin = true)
        FilterType.COOL -> FilterRecipe(temperature = -0.45f, tint = -0.05f, vibrance = 0.1f, highlightRolloff = 0.3f)
        FilterType.GLOW -> FilterRecipe(glow = 0.75f, contrast = -0.05f, highlightRolloff = 0.5f, vibrance = 0.1f)
        FilterType.ORANGE_TEAL -> FilterRecipe(
            contrast = 0.18f, highlightRolloff = 0.5f, saturation = 0.05f, protectSkin = true,
            shadowTint = 0x1E7080, shadowAmount = 0.6f, highlightTint = 0xFFA050, highlightAmount = 0.45f, vignette = 0.2f
        )
        FilterType.MOODY_DARK -> FilterRecipe(
            exposure = -0.35f, contrast = 0.25f, fade = 0.2f, saturation = -0.3f, highlightRolloff = 0.6f,
            shadowTint = 0x283848, shadowAmount = 0.45f, vignette = 0.5f
        )
        FilterType.VAPORWAVE -> FilterRecipe(
            contrast = 0.1f, fade = 0.25f, tint = 0.5f, saturation = 0.3f,
            shadowTint = 0x5030C0, shadowAmount = 0.6f, highlightTint = 0xFF80D0, highlightAmount = 0.5f, glow = 0.2f
        )
        FilterType.FILM_NOIR -> FilterRecipe(
            mono = Triple(0.25f, 0.65f, 0.1f), contrast = 0.55f, curve = pts(0, 0, 64, 40, 192, 215, 255, 255),
            grain = 0.4f, vignette = 0.6f
        )
        FilterType.KODACHROME -> FilterRecipe(
            contrast = 0.2f, saturation = 0.25f, highlightRolloff = 0.4f, temperature = 0.1f, protectSkin = true,
            curveR = pts(0, 0, 128, 136, 255, 255), curveB = pts(0, 6, 128, 120, 255, 245), grain = 0.15f
        )
        FilterType.PASTEL_DREAM -> FilterRecipe(
            exposure = 0.2f, contrast = -0.3f, fade = 0.4f, saturation = -0.15f, highlightRolloff = 0.8f,
            shadowTint = 0xB090C0, shadowAmount = 0.3f, highlightTint = 0xFFE0E8, highlightAmount = 0.3f
        )
        FilterType.DUOTONE_VIOLET -> FilterRecipe(
            mono = Triple(0.3f, 0.59f, 0.11f), contrast = 0.15f,
            shadowTint = 0x301060, shadowAmount = 1f, highlightTint = 0xFFB0E0, highlightAmount = 0.9f
        )
        FilterType.EMERALD_MINT -> FilterRecipe(
            tint = -0.35f, temperature = -0.15f, saturation = 0.1f, fade = 0.15f, protectSkin = true,
            shadowTint = 0x186050, shadowAmount = 0.45f, highlightTint = 0xD8FFE8, highlightAmount = 0.3f
        )
        FilterType.GOLDEN_HOUR -> FilterRecipe(
            exposure = 0.1f, temperature = 0.6f, vibrance = 0.25f, highlightRolloff = 0.6f, protectSkin = true,
            highlightTint = 0xFFC060, highlightAmount = 0.45f, glow = 0.35f, vignette = 0.25f
        )
        FilterType.BLEACH_BYPASS -> FilterRecipe(
            contrast = 0.45f, saturation = -0.55f, highlightRolloff = 0.3f, curve = pts(0, 0, 64, 52, 192, 206, 255, 255), grain = 0.2f
        )
        FilterType.CROSS_PROCESS -> FilterRecipe(
            contrast = 0.25f, saturation = 0.2f,
            curveR = pts(0, 0, 64, 52, 192, 220, 255, 255), curveG = pts(0, 0, 64, 70, 192, 206, 255, 240),
            curveB = pts(0, 40, 128, 118, 255, 200), vignette = 0.25f
        )
        FilterType.PORTRA_FILM -> FilterRecipe(
            contrast = -0.05f, fade = 0.12f, highlightRolloff = 0.7f, temperature = 0.18f, saturation = -0.12f, protectSkin = true,
            curveG = pts(0, 0, 128, 132, 255, 252), shadowTint = 0x4A6070, shadowAmount = 0.2f,
            highlightTint = 0xF8DCC0, highlightAmount = 0.3f, grain = 0.25f, grainSize = 1.2f
        )
        FilterType.FUJI_FILM -> FilterRecipe(
            contrast = 0.15f, highlightRolloff = 0.5f, tint = -0.15f, vibrance = 0.2f, protectSkin = true,
            curveB = pts(0, 10, 128, 134, 255, 250), shadowTint = 0x205850, shadowAmount = 0.35f, grain = 0.25f
        )
        FilterType.TRI_X_BW -> FilterRecipe(
            mono = Triple(0.35f, 0.55f, 0.1f), contrast = 0.35f, highlightRolloff = 0.5f, grain = 0.6f, grainSize = 1.4f, vignette = 0.2f
        )
        FilterType.FADED_MATTE -> FilterRecipe(
            contrast = -0.15f, fade = 0.6f, saturation = -0.25f, highlightRolloff = 0.6f,
            shadowTint = 0x406070, shadowAmount = 0.2f
        )
        FilterType.LIGHT_LEAK -> FilterRecipe(
            fade = 0.15f, temperature = 0.2f, highlightRolloff = 0.5f, lightLeak = 0.75f, grain = 0.25f, vignette = 0.2f
        )
    }

    fun categoryOf(type: FilterType): FilterCategory = when (type) {
        FilterType.WARM, FilterType.PORTRA_FILM, FilterType.PASTEL_DREAM, FilterType.GOLDEN_HOUR,
        FilterType.FADED_MATTE, FilterType.GLOW -> FilterCategory.PORTRAIT
        FilterType.HDR, FilterType.COOL, FilterType.EMERALD_MINT, FilterType.ORANGE_TEAL,
        FilterType.MOODY_DARK, FilterType.CINEMATIC -> FilterCategory.LANDSCAPE
        FilterType.VINTAGE, FilterType.KODACHROME, FilterType.FUJI_FILM, FilterType.CROSS_PROCESS,
        FilterType.BLEACH_BYPASS, FilterType.LIGHT_LEAK -> FilterCategory.FILM
        FilterType.GRAYSCALE, FilterType.SEPIA, FilterType.FILM_NOIR, FilterType.TRI_X_BW -> FilterCategory.BLACK_WHITE
        else -> FilterCategory.CREATIVE
    }

    // ---------------------------------------------------------------------------------------------

    private fun buildChannelLut(r: FilterRecipe, channelCurve: List<PointF>?): IntArray {
        val master = r.curve?.let { ImageProcessor.buildCurveLut(it) }
        val channel = channelCurve?.let { ImageProcessor.buildCurveLut(it) }
        val expGain = 2f.pow(r.exposure)
        val fadeLevel = r.fade * 0.16f
        val lut = IntArray(256)
        for (v in 0..255) {
            var x = (v / 255f) * expGain
            if (r.contrast != 0f) {
                val c = x.coerceIn(0f, 1f)
                val s = c * c * (3f - 2f * c) // smoothstep S-curve
                x = if (r.contrast > 0f) c + (s - c) * r.contrast * 1.4f else c + (0.5f - c) * (-r.contrast) * 0.5f
            }
            if (r.highlightRolloff > 0f && x > 0.65f) {
                // Soft shoulder: compresses the brightest values instead of clipping them.
                val over = x - 0.65f
                x = 0.65f + over / (1f + r.highlightRolloff * over * 2.5f)
            }
            var y = (x.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            if (master != null) y = master[y]
            if (channel != null) y = channel[y]
            var f = y / 255f
            if (fadeLevel > 0f) f = fadeLevel + f * (1f - fadeLevel - r.fade * 0.04f)
            lut[v] = (f * 255f).roundToInt().coerceIn(0, 255)
        }
        return lut
    }

    private fun isSkin(r: Int, g: Int, b: Int): Boolean =
        r > 95 && g > 40 && b > 20 && r > g && r > b && (r - min(g, b)) > 15 && abs(r - g) > 15

    /** Deterministic hash noise in [-1, 1]. */
    private fun hashNoise(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return ((h and 0xFFFF) / 32767.5f) - 1f
    }

    /** Applies [r] to [source] and returns a new bitmap (source alpha preserved). */
    fun render(source: Bitmap, r: FilterRecipe, isCancelled: (() -> Boolean)? = null): Bitmap {
        val w = source.width
        val h = source.height
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, 0, 0, w, h)

        val lutR = buildChannelLut(r, r.curveR)
        val lutG = buildChannelLut(r, r.curveG)
        val lutB = buildChannelLut(r, r.curveB)
        val wbR = 1f + r.temperature * 0.14f + r.tint * 0.05f
        val wbG = 1f - r.tint * 0.1f
        val wbB = 1f - r.temperature * 0.14f + r.tint * 0.05f
        val shR = ((r.shadowTint shr 16) and 0xFF) - 128f; val shG = ((r.shadowTint shr 8) and 0xFF) - 128f; val shB = (r.shadowTint and 0xFF) - 128f
        val hiR = ((r.highlightTint shr 16) and 0xFF) - 128f; val hiG = ((r.highlightTint shr 8) and 0xFF) - 128f; val hiB = (r.highlightTint and 0xFF) - 128f
        val maxDim = max(w, h)
        val grainCell = max(1, (maxDim / 1600f * r.grainSize).roundToInt())
        val grainAmp = r.grain * 26f
        val mono = r.mono

        for (y in 0 until h) {
            if (y % 64 == 0 && isCancelled?.invoke() == true) throw CancellationException("Filter cancelled")
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val p = px[i]
                val a = (p ushr 24) and 0xFF
                if (a == 0) continue
                var rf = ((p shr 16) and 0xFF).toFloat()
                var gf = ((p shr 8) and 0xFF).toFloat()
                var bf = (p and 0xFF).toFloat()
                val skin = r.protectSkin && isSkin(rf.toInt(), gf.toInt(), bf.toInt())

                if (mono != null) {
                    val v = rf * mono.first + gf * mono.second + bf * mono.third
                    rf = v; gf = v; bf = v
                }
                // White balance (skin keeps most of its original warmth).
                val wbK = if (skin) 0.4f else 1f
                rf *= 1f + (wbR - 1f) * wbK; gf *= 1f + (wbG - 1f) * wbK; bf *= 1f + (wbB - 1f) * wbK

                var ri = lutR[rf.roundToInt().coerceIn(0, 255)].toFloat()
                var gi = lutG[gf.roundToInt().coerceIn(0, 255)].toFloat()
                var bi = lutB[bf.roundToInt().coerceIn(0, 255)].toFloat()

                // Saturation + vibrance (vibrance favours muted colours; skin is protected).
                if (mono == null && (r.saturation != 0f || r.vibrance != 0f)) {
                    val l = 0.299f * ri + 0.587f * gi + 0.114f * bi
                    val cur = (max(ri, max(gi, bi)) - min(ri, min(gi, bi))) / 255f
                    var k = 1f + r.saturation + r.vibrance * (1f - cur)
                    if (skin) k = 1f + (k - 1f) * 0.3f
                    ri = l + (ri - l) * k; gi = l + (gi - l) * k; bi = l + (bi - l) * k
                }

                // Split toning by luminance.
                if (r.shadowAmount > 0f || r.highlightAmount > 0f) {
                    val ln = ((0.299f * ri + 0.587f * gi + 0.114f * bi) / 255f).coerceIn(0f, 1f)
                    val ws = (1f - ln) * (1f - ln) * r.shadowAmount * (if (skin) 0.4f else 1f) * 0.7f
                    val wh = ln * ln * r.highlightAmount * (if (skin) 0.5f else 1f) * 0.7f
                    ri += shR * ws + hiR * wh
                    gi += shG * ws + hiG * wh
                    bi += shB * ws + hiB * wh
                }

                // Luminance grain, strongest in the midtones, size relative to the image.
                if (grainAmp > 0f) {
                    val ln = ((0.299f * ri + 0.587f * gi + 0.114f * bi) / 255f).coerceIn(0f, 1f)
                    val n = hashNoise(x / grainCell, y / grainCell) * grainAmp * (0.35f + 2.6f * ln * (1f - ln))
                    ri += n; gi += n; bi += n
                }

                px[i] = (a shl 24) or (ri.roundToInt().coerceIn(0, 255) shl 16) or
                    (gi.roundToInt().coerceIn(0, 255) shl 8) or bi.roundToInt().coerceIn(0, 255)
            }
        }

        if (r.lightLeak > 0f) applyLightLeak(px, w, h, r.lightLeak, r.leakColor)
        if (r.vignette > 0f) applyVignette(px, w, h, r.vignette)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        if (r.glow > 0f) applyGlow(out, r.glow, isCancelled)
        return out
    }

    private fun applyVignette(px: IntArray, w: Int, h: Int, amount: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val maxR = sqrt(cx * cx + cy * cy)
        for (y in 0 until h) {
            val dy = y - cy
            for (x in 0 until w) {
                val i = y * w + x
                val p = px[i]
                val a = (p ushr 24) and 0xFF
                if (a == 0) continue
                val dx = x - cx
                val d = (sqrt(dx * dx + dy * dy) / maxR).coerceIn(0f, 1f)
                // Smooth falloff that leaves the centre untouched.
                val t = ((d - 0.35f) / 0.65f).coerceIn(0f, 1f)
                val f = 1f - amount * 0.75f * t * t * (3f - 2f * t)
                px[i] = (a shl 24) or ((((p shr 16) and 0xFF) * f).toInt() shl 16) or
                    ((((p shr 8) and 0xFF) * f).toInt() shl 8) or ((p and 0xFF) * f).toInt()
            }
        }
    }

    private fun applyLightLeak(px: IntArray, w: Int, h: Int, amount: Float, color: Int) {
        val lr = (color shr 16) and 0xFF; val lg = (color shr 8) and 0xFF; val lb = color and 0xFF
        val diag = sqrt((w * w + h * h).toFloat())
        // Warm leak from the top-right corner plus a softer one from the left edge.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val p = px[i]
                val a = (p ushr 24) and 0xFF
                if (a == 0) continue
                val d1 = sqrt(((w - x) * (w - x) + y * y).toFloat()) / diag
                val d2 = sqrt((x * x + (y - h * 0.6f) * (y - h * 0.6f))) / diag
                val k = (((1f - d1 / 0.7f).coerceIn(0f, 1f)).pow(2) + 0.5f * ((1f - d2 / 0.45f).coerceIn(0f, 1f)).pow(2)) * amount
                if (k <= 0f) continue
                fun screen(c: Int, l: Int): Int = (255 - (255 - c) * (255 - (l * k).toInt().coerceIn(0, 255)) / 255).coerceIn(0, 255)
                px[i] = (a shl 24) or (screen((p shr 16) and 0xFF, lr) shl 16) or
                    (screen((p shr 8) and 0xFF, lg) shl 8) or screen(p and 0xFF, lb)
            }
        }
    }

    /**
     * Highlight bloom computed in linear light (so it glows instead of greying), at a size relative
     * to the image, screened back over the picture.
     */
    private fun applyGlow(bitmap: Bitmap, amount: Float, isCancelled: (() -> Boolean)?) {
        val w = bitmap.width
        val h = bitmap.height
        val scale = min(1f, 480f / max(w, h))
        val sw = max(1, (w * scale).roundToInt())
        val sh = max(1, (h * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        val sp = IntArray(sw * sh)
        small.getPixels(sp, 0, sw, 0, 0, sw, sh)
        val toLin = FloatArray(256) { (it / 255f).pow(2.2f) }
        for (i in sp.indices) {
            val p = sp[i]
            val r = toLin[(p shr 16) and 0xFF]; val g = toLin[(p shr 8) and 0xFF]; val b = toLin[p and 0xFF]
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val k = ((lum - 0.45f) / 0.55f).coerceIn(0f, 1f)
            fun enc(v: Float) = ((v * k).coerceIn(0f, 1f).pow(1f / 2.2f) * 255f).roundToInt()
            sp[i] = (0xFF shl 24) or (enc(r) shl 16) or (enc(g) shl 8) or enc(b)
        }
        val bright = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        bright.setPixels(sp, 0, sw, 0, 0, sw, sh)
        val radius = max(2, (max(sw, sh) * 0.025f).roundToInt())
        val blurred = ImageProcessor.applyGaussianBlur(bright, radius.toFloat(), isCancelled)
        val big = Bitmap.createScaledBitmap(blurred, w, h, true)
        val bp = IntArray(w * h)
        val op = IntArray(w * h)
        big.getPixels(bp, 0, w, 0, 0, w, h)
        bitmap.getPixels(op, 0, w, 0, 0, w, h)
        val k = (amount * 0.9f).coerceIn(0f, 1f)
        for (i in op.indices) {
            val o = op[i]
            val a = (o ushr 24) and 0xFF
            if (a == 0) continue
            val b2 = bp[i]
            fun screen(c: Int, l: Int): Int = (255 - (255 - c) * (255 - (l * k).toInt()) / 255).coerceIn(0, 255)
            op[i] = (a shl 24) or (screen((o shr 16) and 0xFF, (b2 shr 16) and 0xFF) shl 16) or
                (screen((o shr 8) and 0xFF, (b2 shr 8) and 0xFF) shl 8) or screen(o and 0xFF, b2 and 0xFF)
        }
        bitmap.setPixels(op, 0, w, 0, 0, w, h)
        listOf(small, bright, blurred, big).forEach { if (it !== bitmap && !it.isRecycled) it.recycle() }
    }
}
