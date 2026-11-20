package com.example.util

import androidx.annotation.StringRes
import com.example.R

object CanvasValidation {
    const val MIN_DIMENSION = 64
    const val MAX_DIMENSION = 4096
    const val MAX_MEGAPIXELS = 12.0f // Safe 12 MP (~4000x3000 px) to prevent Android Dalvik OOM heap exhaustion

    /**
     * [errorResId] + [errorArgs] describe the problem as a localized string resource, so the UI
     * shows it in the user's language (format with stringResource(errorResId, *errorArgs)).
     */
    data class ValidationResult(
        val isValid: Boolean,
        val width: Int,
        val height: Int,
        @StringRes val errorResId: Int? = null,
        val errorArgs: List<Any> = emptyList()
    )

    fun validate(widthInput: Int?, heightInput: Int?): ValidationResult {
        if (widthInput == null || heightInput == null) {
            return ValidationResult(false, 0, 0, R.string.canvas_error_numeric)
        }
        if (widthInput < MIN_DIMENSION || heightInput < MIN_DIMENSION) {
            return ValidationResult(false, widthInput, heightInput, R.string.canvas_error_min, listOf(MIN_DIMENSION))
        }
        if (widthInput > MAX_DIMENSION || heightInput > MAX_DIMENSION) {
            return ValidationResult(false, widthInput, heightInput, R.string.canvas_error_max, listOf(MAX_DIMENSION))
        }
        val megaPixels = (widthInput.toLong() * heightInput.toLong()) / 1_000_000f
        if (megaPixels > MAX_MEGAPIXELS) {
            return ValidationResult(
                false, widthInput, heightInput,
                R.string.canvas_error_megapixels,
                listOf(MAX_MEGAPIXELS.toInt(), widthInput, heightInput, String.format(java.util.Locale.US, "%.1f", megaPixels))
            )
        }
        return ValidationResult(true, widthInput, heightInput)
    }

    /**
     * Fits [width] x [height] inside the canvas limits with ONE uniform scale, so the aspect ratio
     * is kept (an imported 6000 x 4000 photo becomes ~4096 x 2731, not a stretched near-square).
     * Only a side that would fall below [MIN_DIMENSION] (extreme panoramas) is padded up.
     */
    fun clamp(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return Pair(MIN_DIMENSION, MIN_DIMENSION)
        val w = width.toDouble()
        val h = height.toDouble()
        val bySide = MAX_DIMENSION / maxOf(w, h)
        val byArea = kotlin.math.sqrt(MAX_MEGAPIXELS * 1_000_000.0 / (w * h))
        val scale = minOf(1.0, bySide, byArea)
        val outW = kotlin.math.floor(w * scale).toInt().coerceIn(MIN_DIMENSION, MAX_DIMENSION)
        val outH = kotlin.math.floor(h * scale).toInt().coerceIn(MIN_DIMENSION, MAX_DIMENSION)
        return Pair(outW, outH)
    }
}
