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
    RADIAL_BLUR(R.string.effect_radial_blur);

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
    ADD("Add", R.string.blend_add, 24);

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
    CROSS_PROCESS("Cross Process", R.string.filter_cross_process)
}

enum class LayerType {
    IMAGE,
    TEXT,
    COLOR_FILL,
    ADJUSTMENT
}

enum class SelectionToolType {
    QUICK_SELECTION,
    QUICK_BRUSH,
    RECTANGLE,
    ELLIPSE,
    LASSO,
    POLYGON,
    MAGIC_WAND
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
    val vignette: Float = 0f,      // 0 to 100
    // Curves (control points in [0..255] range)
    val curvesRgb: List<PointF> = listOf(PointF(0f, 0f), PointF(255f, 255f)),
    val curvesRed: List<PointF> = listOf(PointF(0f, 0f), PointF(255f, 255f)),
    val curvesGreen: List<PointF> = listOf(PointF(0f, 0f), PointF(255f, 255f)),
    val curvesBlue: List<PointF> = listOf(PointF(0f, 0f), PointF(255f, 255f)),
    // Hue / Saturation / Lightness (HSL)
    val hueShift: Float = 0f,       // -180 to 180
    val hslSaturation: Float = 0f,  // -100 to 100
    val hslLightness: Float = 0f,   // -100 to 100
    val hslColorRange: String = "ALL", // ALL, REDS, YELLOWS, GREENS, CYANS, BLUES, MAGENTAS
    // Color Replacement
    val replaceColorEnabled: Boolean = false,
    val replaceSourceColorHex: Int = 0xFFFF0000.toInt(),
    val replaceTolerance: Float = 35f, // 1 to 100
    val replaceTargetHue: Float = 120f, // 0 to 360
    val replaceTargetSaturation: Float = 0f, // -100 to 100
    val replaceTargetLightness: Float = 0f // -100 to 100
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
                !hasCurves() && hueShift == 0f && hslSaturation == 0f && hslLightness == 0f &&
                !replaceColorEnabled
}

data class TextLayerConfig(
    val text: String = "PhotoEasy",
    val fontName: String = "Cairo",
    val fontSizeSp: Float = 42f,
    val colorHex: Int = 0xFFFFFFFF.toInt(),
    val strokeColorHex: Int = 0xFF000000.toInt(),
    val strokeWidthDp: Float = 0f,
    val alignment: TextAlignment = TextAlignment.CENTER,
    val shadowColorHex: Int = 0x80000000.toInt(),
    val shadowRadiusDp: Float = 0f,
    val shadowDxDp: Float = 4f,
    val shadowDyDp: Float = 6f,
    val outerGlowColorHex: Int = 0xFF00E5FF.toInt(),
    val outerGlowRadiusDp: Float = 0f,
    val lineSpacingMultiplier: Float = 1.0f,
    val letterSpacingEm: Float = 0f,
    val maxLineWidthPercent: Float = 80f
)

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
    var adjustments: ColorAdjustments = ColorAdjustments(),
    var isBitmapDirty: Boolean = true,
    var sourceAssetPath: String? = null,
    var originalWidth: Int = 0,
    var originalHeight: Int = 0,
    var contentWidth: Float = 0f,
    var contentHeight: Float = 0f
) {
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
