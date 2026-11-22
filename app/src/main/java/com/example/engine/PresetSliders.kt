package com.example.engine

import android.graphics.PointF
import com.example.data.model.ColorAdjustments
import com.example.data.model.FilterType
import com.example.data.model.HslBand
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * "Edit as adjustments": a preset is moved into the ordinary adjustment sliders (Lightroom-style),
 * so every part of the look can be fine-tuned instead of being a closed box.
 */
object PresetSliders {

    /** The sliders of [type] at [intensity]; [exact] is false when a part could only be approximated. */
    data class Result(val adjustments: ColorAdjustments, val exact: Boolean)

    private val identity = listOf(PointF(0f, 0f), PointF(255f, 255f))

    fun canConvert(type: FilterType): Boolean = FilterRecipes.recipeFor(type) != null

    fun toAdjustments(type: FilterType, intensity: Float = 1f): Result? {
        val r = FilterRecipes.recipeFor(type) ?: return null
        val k = intensity.coerceIn(0f, 1f)
        var exact = r.glow == 0f && r.lightLeak == 0f
        val base = r.adjust ?: ColorAdjustments()

        // Tone and colour, converted to the adjustment engine's scales (it works in linear light).
        var saturation = base.saturation + r.saturation * 100f
        var vibrance = base.vibrance + r.vibrance * 100f
        if (r.protectSkin && saturation > 0f) { vibrance += saturation; saturation = 0f }
        if (r.mono != null) {
            saturation = -100f
            val m = r.mono
            // Only the standard luminance mix converts exactly; filters like the red one are approximated.
            if (abs(m.first - 0.3f) > 0.08f || abs(m.second - 0.59f) > 0.08f || abs(m.third - 0.11f) > 0.08f) exact = false
        }

        // Fade (matte) is a lifted black point on the master curve.
        val fadeLevel = r.fade * 0.16f
        val fadeTop = 1f - fadeLevel - r.fade * 0.04f
        val master = (r.curve ?: identity).map { PointF(it.x, (fadeLevel * 255f + it.y * fadeTop)) }
            .let { if (r.fade > 0f && r.curve == null) listOf(it.first(), PointF(128f, fadeLevel * 255f + 128f * fadeTop), it.last()) else it }

        // Split toning becomes Color Grading when the zone is still free.
        fun hueOf(rgb: Int): Pair<Float, Float> {
            val rr = ((rgb shr 16) and 0xFF) / 255f; val gg = ((rgb shr 8) and 0xFF) / 255f; val bb = (rgb and 0xFF) / 255f
            val mx = max(rr, max(gg, bb)); val mn = min(rr, min(gg, bb)); val d = mx - mn
            if (d < 1e-4f) return 0f to 0f
            val h = when (mx) {
                rr -> 60f * (((gg - bb) / d) % 6f)
                gg -> 60f * ((bb - rr) / d + 2f)
                else -> 60f * ((rr - gg) / d + 4f)
            }
            return ((h + 360f) % 360f) to (d / mx)
        }
        var adj = base.copy(
            exposure = base.exposure + r.exposure * 110f,
            contrast = base.contrast + r.contrast * 80f,
            temperature = base.temperature + r.temperature * 150f,
            tint = base.tint - r.tint * 130f,
            saturation = saturation,
            vibrance = vibrance,
            highlights = base.highlights - r.highlightRolloff * 35f,
            vignette = base.vignette + r.vignette * 70f,
            grainAmount = base.grainAmount + r.grain * 60f,
            grainSize = if (r.grain > 0f) 25f * r.grainSize else base.grainSize,
            curvesRgb = master,
            curvesRed = r.curveR ?: identity,
            curvesGreen = r.curveG ?: identity,
            curvesBlue = r.curveB ?: identity
        )
        if (r.shadowAmount > 0f && adj.gradeShadowSat == 0f) {
            val (h, s) = hueOf(r.shadowTint)
            adj = adj.copy(gradeShadowHue = h, gradeShadowSat = (r.shadowAmount * s * 70f).coerceIn(0f, 100f))
        }
        if (r.highlightAmount > 0f && adj.gradeHighSat == 0f) {
            val (h, s) = hueOf(r.highlightTint)
            adj = adj.copy(gradeHighHue = h, gradeHighSat = (r.highlightAmount * s * 70f).coerceIn(0f, 100f))
        }
        // Duotones colour pure greys strongly; Color Grading stays subtler.
        if (r.mono != null && (r.shadowAmount > 0.6f || r.highlightAmount > 0.6f)) exact = false
        return Result(clamp(scale(adj, k)), exact)
    }

    /** [a] scaled towards neutral by [k] (the preset's intensity). */
    fun scale(a: ColorAdjustments, k: Float): ColorAdjustments {
        if (k >= 0.999f) return a
        fun c(p: List<PointF>) = p.map { PointF(it.x, it.x + (it.y - it.x) * k) }
        return a.copy(
            brightness = a.brightness * k, contrast = a.contrast * k, saturation = a.saturation * k,
            temperature = a.temperature * k, tint = a.tint * k, exposure = a.exposure * k,
            highlights = a.highlights * k, shadows = a.shadows * k, vignette = a.vignette * k,
            whites = a.whites * k, blacks = a.blacks * k, vibrance = a.vibrance * k,
            clarity = a.clarity * k, dehaze = a.dehaze * k, grainAmount = a.grainAmount * k,
            gradeShadowSat = a.gradeShadowSat * k, gradeMidSat = a.gradeMidSat * k, gradeHighSat = a.gradeHighSat * k,
            curvesRgb = c(a.curvesRgb), curvesRed = c(a.curvesRed), curvesGreen = c(a.curvesGreen), curvesBlue = c(a.curvesBlue),
            hslBands = a.hslBands.mapValues { (_, b) -> HslBand(b.hue * k, b.saturation * k, b.lightness * k, b.width) }
        )
    }

    /**
     * The layer's own adjustments plus the preset's: values add up, the preset's grading fills the
     * zones left neutral, and its curves are used where the layer's curves are straight.
     */
    fun merge(layer: ColorAdjustments, preset: ColorAdjustments): ColorAdjustments {
        if (layer.isIdentity()) return preset
        fun straight(p: List<PointF>) = p.all { abs(it.x - it.y) < 0.5f }
        val bands = (layer.hslBands.keys + preset.hslBands.keys).associateWith { key ->
            val a = layer.hslBands[key] ?: HslBand()
            val b = preset.hslBands[key] ?: HslBand()
            HslBand(a.hue + b.hue, a.saturation + b.saturation, a.lightness + b.lightness, if (layer.hslBands.containsKey(key)) a.width else b.width)
        }
        return clamp(layer.copy(
            brightness = layer.brightness + preset.brightness, contrast = layer.contrast + preset.contrast,
            saturation = layer.saturation + preset.saturation, temperature = layer.temperature + preset.temperature,
            tint = layer.tint + preset.tint, exposure = layer.exposure + preset.exposure,
            highlights = layer.highlights + preset.highlights, shadows = layer.shadows + preset.shadows,
            vignette = layer.vignette + preset.vignette, whites = layer.whites + preset.whites,
            blacks = layer.blacks + preset.blacks, vibrance = layer.vibrance + preset.vibrance,
            clarity = layer.clarity + preset.clarity, dehaze = layer.dehaze + preset.dehaze,
            grainAmount = layer.grainAmount + preset.grainAmount,
            grainSize = if (preset.grainAmount > 0f && layer.grainAmount == 0f) preset.grainSize else layer.grainSize,
            hslBands = bands,
            gradeShadowHue = if (layer.gradeShadowSat == 0f) preset.gradeShadowHue else layer.gradeShadowHue,
            gradeShadowSat = if (layer.gradeShadowSat == 0f) preset.gradeShadowSat else layer.gradeShadowSat,
            gradeMidHue = if (layer.gradeMidSat == 0f) preset.gradeMidHue else layer.gradeMidHue,
            gradeMidSat = if (layer.gradeMidSat == 0f) preset.gradeMidSat else layer.gradeMidSat,
            gradeHighHue = if (layer.gradeHighSat == 0f) preset.gradeHighHue else layer.gradeHighHue,
            gradeHighSat = if (layer.gradeHighSat == 0f) preset.gradeHighSat else layer.gradeHighSat,
            gradeBalance = if (layer.gradeShadowSat == 0f && layer.gradeMidSat == 0f && layer.gradeHighSat == 0f) preset.gradeBalance else layer.gradeBalance,
            gradeBlending = if (layer.gradeShadowSat == 0f && layer.gradeMidSat == 0f && layer.gradeHighSat == 0f) preset.gradeBlending else layer.gradeBlending,
            curvesRgb = if (straight(layer.curvesRgb)) preset.curvesRgb else layer.curvesRgb,
            curvesRed = if (straight(layer.curvesRed)) preset.curvesRed else layer.curvesRed,
            curvesGreen = if (straight(layer.curvesGreen)) preset.curvesGreen else layer.curvesGreen,
            curvesBlue = if (straight(layer.curvesBlue)) preset.curvesBlue else layer.curvesBlue
        ))
    }

    private fun clamp(a: ColorAdjustments): ColorAdjustments {
        fun s(v: Float) = v.coerceIn(-100f, 100f)
        return a.copy(
            brightness = s(a.brightness), contrast = s(a.contrast), saturation = s(a.saturation),
            temperature = s(a.temperature), tint = s(a.tint), exposure = s(a.exposure),
            highlights = s(a.highlights), shadows = s(a.shadows), vignette = s(a.vignette),
            whites = s(a.whites), blacks = s(a.blacks), vibrance = s(a.vibrance),
            clarity = s(a.clarity), dehaze = s(a.dehaze), grainAmount = a.grainAmount.coerceIn(0f, 100f),
            grainSize = a.grainSize.coerceIn(0f, 100f),
            hslBands = a.hslBands.mapValues { (_, b) -> b.copy(hue = b.hue.coerceIn(-100f, 100f), saturation = s(b.saturation), lightness = s(b.lightness)) }
        )
    }
}
