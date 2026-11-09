package com.example.data.model

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.annotation.FontRes
import com.example.R

enum class ProFilterType(val displayNameResId: Int) {
    GAUSSIAN_BLUR(R.string.effect_gaussian_blur),
    SURFACE_BLUR(R.string.effect_surface_blur),
    OIL_PAINT(R.string.effect_oil_paint),
    SHARPEN(R.string.effect_sharpen),
    NOISE_REDUCTION(R.string.effect_noise_reduction),
    MOTION_BLUR(R.string.effect_motion_blur),
    RADIAL_BLUR(R.string.effect_radial_blur),
    LENS_BLUR(R.string.effect_lens_blur),
    TILT_SHIFT(R.string.effect_tilt_shift),
    HIGH_PASS(R.string.effect_high_pass);

    val resId: Int get() = displayNameResId
}

enum class BlendModeType(val displayName: String, val resId: Int, val minApiLevel: Int = 24) {
    NORMAL("Normal", R.string.blend_normal, 24),
    MULTIPLY("Multiply", R.string.blend_multiply, 24),
    SCREEN("Screen", R.string.blend_screen, 24),
    OVERLAY("Overlay", R.string.blend_overlay, 24),
    DARKEN("Darken", R.string.blend_darken, 24),
    LIGHTEN("Lighten", R.string.blend_lighten, 24),
    SOFT_LIGHT("Soft Light", R.string.blend_soft_light, 29),
    HARD_LIGHT("Hard Light", R.string.blend_hard_light, 29),
    DIFFERENCE("Difference", R.string.blend_difference, 29),
    ADD("Add", R.string.blend_add, 24),
    COLOR_DODGE("Color Dodge", R.string.blend_color_dodge, 29),
    COLOR_BURN("Color Burn", R.string.blend_color_burn, 29),
    EXCLUSION("Exclusion", R.string.blend_exclusion, 29),
    HUE("Hue", R.string.blend_hue, 29),
    SATURATION("Saturation", R.string.blend_saturation, 29),
    COLOR("Color", R.string.blend_color, 29),
    LUMINOSITY("Luminosity", R.string.blend_luminosity, 29);

    companion object {
        fun getAvailableModes(): List<BlendModeType> {
            val currentApi = android.os.Build.VERSION.SDK_INT
            return values().filter { it.minApiLevel <= currentApi }
        }
    }
}

enum class FilterType(val displayName: String, val resId: Int) {
    NONE("Original", R.string.filter_none),
    VINTAGE("Vintage", R.string.filter_vintage),
    SEPIA("Sepia", R.string.filter_sepia),
    GRAYSCALE("B&W", R.string.filter_grayscale),
    CYBERPUNK("Cyberpunk", R.string.filter_cyberpunk),
    CINEMATIC("Cinematic", R.string.filter_cinematic),
    HDR("HDR Look", R.string.filter_hdr),
    WARM("Warm Sunset", R.string.filter_warm),
    COOL("Cool Breeze", R.string.filter_cool),
    GLOW("Soft Glow", R.string.filter_glow),
    PIXELATE("Pixelate", R.string.filter_pixelate),
    EDGES("Edge Detect", R.string.filter_edges),
    GLITCH("Glitch", R.string.filter_glitch),
    ORANGE_TEAL("Orange & Teal", R.string.filter_orange_teal),
    MOODY_DARK("Moody Dark", R.string.filter_moody_dark),
    VAPORWAVE("Vaporwave", R.string.filter_vaporwave),
    FILM_NOIR("Film Noir", R.string.filter_film_noir),
    KODACHROME("Kodachrome", R.string.filter_kodachrome),
    PASTEL_DREAM("Pastel Dream", R.string.filter_pastel_dream),
    DUOTONE_VIOLET("Duotone", R.string.filter_duotone_violet),
    EMERALD_MINT("Emerald", R.string.filter_emerald_mint),
    GOLDEN_HOUR("Golden Hour", R.string.filter_golden_hour),
    SOLARIZE("Solarize", R.string.filter_solarize),
    BLEACH_BYPASS("Bleach Bypass", R.string.filter_bleach_bypass),
    CROSS_PROCESS("Cross Process", R.string.filter_cross_process),
    PORTRA_FILM("Portra Film", R.string.filter_portra_film),
    FUJI_FILM("Fuji Film", R.string.filter_fuji_film),
    TRI_X_BW("Tri-X B&W", R.string.filter_tri_x_bw),
    FADED_MATTE("Faded Matte", R.string.filter_faded_matte),
    LIGHT_LEAK("Light Leak", R.string.filter_light_leak)
}

enum class LayerType {
    IMAGE,
    TEXT,
    COLOR_FILL,
    ADJUSTMENT
}

/** Direction of a layer-mask gradient (revealed -> hidden). */
enum class MaskGradient { TOP_TO_BOTTOM, BOTTOM_TO_TOP, LEFT_TO_RIGHT, RIGHT_TO_LEFT, RADIAL }

enum class SelectionToolType {
    QUICK_SELECTION,
    QUICK_BRUSH,
    RECTANGLE,
    ELLIPSE,
    LASSO,
    POLYGON,
    MAGIC_WAND,
    /** Drags the selection outline itself (no image pixels move). */
    MOVE_SELECTION
}

enum class SelectionMode {
    ADD,
    SUBTRACT,
    INTERSECT,
    REPLACE
}

enum class TextAlignment {
    LEFT,
    CENTER,
    RIGHT
}

data class FontItem(
    val name: String,
    val displayNameResId: Int,
    @FontRes val fontResId: Int?
)

val AvailableFonts = listOf(
    FontItem("Cairo", R.string.font_cairo, R.font.cairo),
    FontItem("Tajawal", R.string.font_tajawal, R.font.tajawal),
    FontItem("Amiri", R.string.font_amiri, R.font.amiri),
    FontItem("Montserrat", R.string.font_montserrat, R.font.montserrat),
    FontItem("Default", R.string.font_default, null)
)

/**
 * One colour replacement: pixels close to [sourceColor] take the hue and colourfulness of
 * [targetColor] while keeping their own shading. [tolerance] 1..100 (how different a colour may
 * be and still match), [softness] 0..100 (width of the soft edge), [saturation] / [lightness]
 * -100..100 fine-tune the result.
 */
data class ColorReplacement(
    val sourceColor: Int = 0xFFFF1744.toInt(),
    val targetColor: Int = 0xFF2979FF.toInt(),
    val tolerance: Float = 35f,
    val softness: Float = 50f,
    val saturation: Float = 0f,
    val lightness: Float = 0f,
    val enabled: Boolean = true
)

/** Hue (degrees), saturation and lightness (-100..100) shift of one HSL colour range. */
data class HslBand(
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val lightness: Float = 0f,
    /** Range width multiplier (0.5 = narrower, 2 = wider); 1 = neighbouring ranges meet smoothly. */
    val width: Float = 1f
) {
    fun isIdentity(): Boolean = hue == 0f && saturation == 0f && lightness == 0f
}

/**
 * The eight HSL colour ranges (Lightroom layout) with their centre hues. "CYANS" keeps its old id
 * for saved projects and is shown as Aqua.
 */
object HslRanges {
    val keys = arrayOf("REDS", "ORANGES", "YELLOWS", "GREENS", "CYANS", "BLUES", "PURPLES", "MAGENTAS")
    val centers = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 270f, 300f)

    private fun circDist(a: Float, b: Float): Float {
        val d = kotlin.math.abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /**
     * Weight of every range for a colour of [hue] (degrees). With width 1 the weights of the two
     * neighbouring ranges add up to 1, so edits blend smoothly across the colour wheel.
     */
    fun weights(hue: Float, widths: FloatArray? = null): FloatArray {
        val n = keys.size
        val out = FloatArray(n)
        for (k in 0 until n) {
            val c = centers[k]
            val prev = centers[(k - 1 + n) % n]
            val next = centers[(k + 1) % n]
            val gapLeft = circDist(c, prev)
            val gapRight = circDist(next, c)
            val signed = ((hue - c + 540f) % 360f) - 180f
            val wMul = widths?.get(k) ?: 1f
            val w = if (signed < 0f) 1f - (-signed) / (gapLeft * wMul) else 1f - signed / (gapRight * wMul)
            out[k] = w.coerceIn(0f, 1f)
        }
        return out
    }
}

data class ColorAdjustments(
    val brightness: Float = 0f,    // -100 to 100
    val contrast: Float = 0f,      // -100 to 100
    val saturation: Float = 0f,    // -100 to 100
    val temperature: Float = 0f,   // -100 to 100
    val tint: Float = 0f,          // -100 to 100
    val exposure: Float = 0f,      // -100 to 100
    val highlights: Float = 0f,    // -100 to 100
    val shadows: Float = 0f,       // -100 to 100
    val sharpness: Float = 0f,     // 0 to 100
    val vignette: Float = 0f,      // -100 (lighten edges) to 100 (darken edges)
    val whites: Float = 0f,        // -100 to 100 (white point)
    val blacks: Float = 0f,        // -100 to 100 (black point)
    val vibrance: Float = 0f,      // -100 to 100 (saturation that protects skin and saturated colours)
    val clarity: Float = 0f,       // -100 to 100 (local mid-tone contrast)
    val dehaze: Float = 0f,        // -100 to 100
    // Curves (control points in [0..255] range)
    val curvesRgb: List<PointF> = listOf(PointF(0f, 0f), PointF(255f, 255f)),
    val curvesRed: List<PointF> = listOf(PointF(0f, 0f), PointF(255f, 255f)),
    val curvesGreen: List<PointF> = listOf(PointF(0f, 0f), PointF(255f, 255f)),
    val curvesBlue: List<PointF> = listOf(PointF(0f, 0f), PointF(255f, 255f)),
    // Hue / Saturation / Lightness (HSL)
    val hueShift: Float = 0f,       // -180 to 180
    val hslSaturation: Float = 0f,  // -100 to 100
    val hslLightness: Float = 0f,   // -100 to 100
    val hslColorRange: String = "ALL", // ALL, REDS, YELLOWS, GREENS, CYANS, BLUES, MAGENTAS (range shown in the panel)
    /** Independent HSL shift per colour range (keys as [hslColorRange]). [hueShift]/[hslSaturation]/
     *  [hslLightness] are the older single-range values, migrated into this map when edited. */
    val hslBands: Map<String, HslBand> = emptyMap(),
    // Color Replacement
    val replaceColorEnabled: Boolean = false,
    val replaceSourceColorHex: Int = 0xFFFF0000.toInt(),
    val replaceTolerance: Float = 35f, // 1 to 100
    val replaceTargetHue: Float = 120f, // 0 to 360
    val replaceTargetSaturation: Float = 0f, // -100 to 100
    val replaceTargetLightness: Float = 0f, // -100 to 100
    /** Up to three colour replacements (supersedes the single legacy replace* fields above). */
    val colorReplacements: List<ColorReplacement> = emptyList(),
    /** Transient (not saved): render the replacement match as a black / white mask. */
    val replacePreviewMask: Boolean = false,
    /** Colorize: the whole image in one hue (keeps its light and shade). */
    val colorizeEnabled: Boolean = false,
    val colorizeHue: Float = 30f,          // 0..360
    val colorizeSaturation: Float = 35f,   // 0..100
    val colorizeLightness: Float = 0f      // -100..100
) {
    fun hasCurves(): Boolean =
        curvesRgb.size > 2 || (curvesRgb.size == 2 && (curvesRgb[0].x != 0f || curvesRgb[0].y != 0f || curvesRgb[1].x != 255f || curvesRgb[1].y != 255f)) ||
        curvesRed.size > 2 || (curvesRed.size == 2 && (curvesRed[0].x != 0f || curvesRed[0].y != 0f || curvesRed[1].x != 255f || curvesRed[1].y != 255f)) ||
        curvesGreen.size > 2 || (curvesGreen.size == 2 && (curvesGreen[0].x != 0f || curvesGreen[0].y != 0f || curvesGreen[1].x != 255f || curvesGreen[1].y != 255f)) ||
        curvesBlue.size > 2 || (curvesBlue.size == 2 && (curvesBlue[0].x != 0f || curvesBlue[0].y != 0f || curvesBlue[1].x != 255f || curvesBlue[1].y != 255f))

    fun isIdentity(): Boolean =
        brightness == 0f && contrast == 0f && saturation == 0f &&
                temperature == 0f && tint == 0f && exposure == 0f &&
                highlights == 0f && shadows == 0f && sharpness == 0f && vignette == 0f &&
                whites == 0f && blacks == 0f && vibrance == 0f && clarity == 0f && dehaze == 0f &&
                !hasCurves() && !hasHsl() && effectiveReplacements().none { it.enabled } && !replacePreviewMask && !colorizeEnabled

    fun hasHsl(): Boolean = effectiveHslBands().values.any { !it.isIdentity() } || colorizeEnabled

    /**
     * The colour replacements to apply, including one converted from the older single-replacement
     * fields. Older versions also stored the tolerance as 0..1 by mistake; that is scaled back.
     */
    fun effectiveReplacements(): List<ColorReplacement> {
        if (colorReplacements.isNotEmpty()) return colorReplacements
        if (!replaceColorEnabled) return emptyList()
        val tol = (if (replaceTolerance <= 1f) replaceTolerance * 100f else replaceTolerance).coerceIn(1f, 100f)
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(replaceSourceColorHex, hsv)
        val target = android.graphics.Color.HSVToColor(floatArrayOf(((replaceTargetHue % 360f) + 360f) % 360f, hsv[1], hsv[2]))
        return listOf(ColorReplacement(replaceSourceColorHex, target, tol, 40f, replaceTargetSaturation, replaceTargetLightness))
    }

    /** Stores [list] as the replacements and retires the legacy single replacement. */
    fun withReplacements(list: List<ColorReplacement>): ColorAdjustments =
        copy(colorReplacements = list.take(3), replaceColorEnabled = false)

    /** All HSL bands, including the legacy single-range values. */
    fun effectiveHslBands(): Map<String, HslBand> {
        val legacy = HslBand(hueShift, hslSaturation, hslLightness)
        if (legacy.isIdentity() || hslBands.containsKey(hslColorRange)) return hslBands
        return hslBands + (hslColorRange to legacy)
    }

    fun hslBand(range: String): HslBand = effectiveHslBands()[range] ?: HslBand()

    /** Moves legacy single-range values into [hslBands] so switching ranges never moves an edit. */
    fun migrateHsl(): ColorAdjustments =
        copy(hslBands = effectiveHslBands().filterValues { !it.isIdentity() }, hueShift = 0f, hslSaturation = 0f, hslLightness = 0f)

    fun withHslBand(range: String, band: HslBand): ColorAdjustments {
        val m = migrateHsl()
        val bands = if (band.isIdentity()) m.hslBands - range else m.hslBands + (range to band)
        return m.copy(hslBands = bands, hslColorRange = range)
    }
}

data class TextLayerConfig(
    val text: String = "PhotoEasy",
    val fontName: String = "Cairo",
    val fontSizeSp: Float = 42f,
    val colorHex: Int = 0xFFFFFFFF.toInt(),
    val strokeColorHex: Int = 0xFF000000.toInt(),
    val strokeWidthDp: Float = 0f,
    val alignment: TextAlignment = TextAlignment.CENTER,
    /** Shadow colour (RGB); its transparency is [shadowOpacity]. */
    val shadowColorHex: Int = 0xFF000000.toInt(),
    val shadowRadiusDp: Float = 0f,
    val shadowDxDp: Float = 4f,
    val shadowDyDp: Float = 6f,
    val outerGlowColorHex: Int = 0xFF00E5FF.toInt(),
    val outerGlowRadiusDp: Float = 0f,
    val lineSpacingMultiplier: Float = 1.0f,
    val letterSpacingEm: Float = 0f,
    val maxLineWidthPercent: Float = 80f,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val shadowOpacity: Float = 0.5f,
    val glowOpacity: Float = 0.9f,
    /** Rounded box behind the text. */
    val backgroundEnabled: Boolean = false,
    val backgroundColorHex: Int = 0xFF000000.toInt(),
    val backgroundOpacity: Float = 0.6f,
    val backgroundPadding: Float = 12f,
    val backgroundCorner: Float = 12f,
    /** Linear gradient fill from [colorHex] to [gradientEndColorHex]. */
    val gradientEnabled: Boolean = false,
    val gradientEndColorHex: Int = 0xFF06B6D4.toInt(),
    val gradientAngle: Float = 90f
)

/**
 * The layer transform at the moment its (document-space) mask pixels were last written. While the
 * mask is linked, the renderer moves the mask by (current transform) x (anchor transform)^-1, so
 * it follows the layer when it is moved, scaled or rotated.
 */
data class MaskAnchor(val x: Float, val y: Float, val scaleX: Float, val scaleY: Float, val rotation: Float)

data class Layer(
    val id: String,
    var name: String,
    var type: LayerType = LayerType.IMAGE,
    var bitmap: Bitmap? = null,
    var maskBitmap: Bitmap? = null,
    var isMaskEnabled: Boolean = true,
    var isEditingMask: Boolean = false,
    var maskDensity: Float = 1.0f,       // 0.0 to 1.0
    var maskFeather: Float = 0f,          // 0 to 30 dp
    var opacity: Float = 1.0f,          // 0.0 to 1.0
    var isVisible: Boolean = true,
    var isLocked: Boolean = false,
    var blendMode: BlendModeType = BlendModeType.NORMAL,
    var positionX: Float = 0f,
    var positionY: Float = 0f,
    var scaleX: Float = 1.0f,
    var scaleY: Float = 1.0f,
    var rotation: Float = 0f,
    var textConfig: TextLayerConfig = TextLayerConfig(),
    var colorFillHex: Int = 0xFF10111A.toInt(),
    var filter: FilterType = FilterType.NONE,
    var filterIntensity: Float = 1.0f,
    /** Imported 3D LUT (FilterLibrary id). When set it is the layer's look; [filterIntensity] applies. */
    var lutId: String? = null,
    var adjustments: ColorAdjustments = ColorAdjustments(),
    var isBitmapDirty: Boolean = true,
    var sourceAssetPath: String? = null,
    var originalWidth: Int = 0,
    var originalHeight: Int = 0,
    var contentWidth: Float = 0f,
    var contentHeight: Float = 0f,
    /** Mask follows the layer's move / scale / rotate (Photoshop's chain link). */
    var isMaskLinked: Boolean = true,
    var maskAnchor: MaskAnchor? = null,
    /** Clipping mask: drawn only where the nearest non-clipped layer below is opaque. */
    var isClipped: Boolean = false,
    /** Skew in degrees (horizontal / vertical), applied around the layer centre. */
    var skewX: Float = 0f,
    var skewY: Float = 0f,
    /**
     * Free distort: where the layer's four corners (TL, TR, BR, BL) go, in units of its own width
     * and height (identity = 0,0, 1,0, 1,1, 0,1). Null = no distortion. Non-destructive.
     */
    var distort: FloatArray? = null
) {
    fun currentMaskAnchor(): MaskAnchor = MaskAnchor(positionX, positionY, scaleX, scaleY, rotation)

    /** Records the anchor if a mask exists without one (new or legacy masks). */
    fun ensureMaskAnchor() {
        if (maskBitmap != null && maskAnchor == null) maskAnchor = currentMaskAnchor()
    }

    fun intrinsicDocumentWidth(): Float = when {
        contentWidth > 0f -> contentWidth
        originalWidth > 0 -> originalWidth.toFloat()
        bitmap != null -> bitmap!!.width.toFloat()
        else -> 1f
    }

    fun intrinsicDocumentHeight(): Float = when {
        contentHeight > 0f -> contentHeight
        originalHeight > 0 -> originalHeight.toFloat()
        bitmap != null -> bitmap!!.height.toFloat()
        else -> 1f
    }
    /**
     * Snapshot for Undo/Redo history.
     * Preserves exact layer ID and name, and reuses bitmap reference without extra RAM allocation.
     */
    fun snapshotLayer(): Layer {
        return this.copy(
            textConfig = textConfig.copy(),
            adjustments = adjustments.copy(),
            isBitmapDirty = isBitmapDirty,
            maskBitmap = maskBitmap?.copy(maskBitmap?.config ?: Bitmap.Config.ALPHA_8, true)
        )
    }

    /**
     * Cheap, per-frame snapshot for the background preview renderer: freezes the layer's scalar
     * properties but shares bitmap and mask references (they are replaced, never recycled, while
     * a render may be reading them), avoiding a full mask copy on every frame.
     */
    fun previewSnapshot(): Layer = this.copy()

    /**
     * Explicit layer duplication by user action.
     * Creates new layer ID, appends (Copy) to name, and duplicates bitmap pixels.
     */
    fun duplicateLayer(): Layer {
        val copiedBitmap = bitmap?.copy(bitmap?.config ?: Bitmap.Config.ARGB_8888, true)
        val copiedMask = maskBitmap?.copy(maskBitmap?.config ?: Bitmap.Config.ALPHA_8, true)
        return this.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = if (name.endsWith("(Copy)")) name else "$name (Copy)",
            bitmap = copiedBitmap,
            maskBitmap = copiedMask,
            isMaskEnabled = isMaskEnabled,
            isEditingMask = isEditingMask,
            maskDensity = maskDensity,
            maskFeather = maskFeather,
            textConfig = textConfig.copy(),
            adjustments = adjustments.copy(),
            isBitmapDirty = true,
            sourceAssetPath = sourceAssetPath,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight
        )
    }
}

data class CanvasPreset(
    val titleResId: Int,
    val width: Int,
    val height: Int,
    val description: String
)

val StandardCanvasPresets = listOf(
    CanvasPreset(R.string.preset_square, 1080, 1080, "1:1 Instagram Post"),
    CanvasPreset(R.string.preset_story, 1080, 1920, "9:16 Story / Reel"),
    CanvasPreset(R.string.preset_landscape, 1920, 1080, "16:9 HD Display"),
    CanvasPreset(R.string.preset_portrait, 1080, 1350, "4:5 Portrait Feed")
)

data class ProjectSummary(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val thumbnailPath: String? = null
)

data class Project(
    val id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "Untitled Project",
    var width: Int = 1080,
    var height: Int = 1080,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    val layers: MutableList<Layer> = mutableListOf(),
    var thumbnailPath: String? = null,
    var renderRevision: Long = 0L
) {
    fun markDirty() {
        renderRevision++
        updatedAt = System.currentTimeMillis()
    }
}

data class ProjectSnapshot(
    val width: Int,
    val height: Int,
    val layers: List<Layer>,
    val renderRevision: Long
)
