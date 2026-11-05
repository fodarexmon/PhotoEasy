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

    fun clamp(width: Int, height: Int): Pair<Int, Int> {
        val w = width.coerceIn(MIN_DIMENSION, MAX_DIMENSION)
        val h = height.coerceIn(MIN_DIMENSION, MAX_DIMENSION)
        val mp = (w.toLong() * h.toLong()) / 1_000_000f
        return if (mp > MAX_MEGAPIXELS) {
            val scale = kotlin.math.sqrt((MAX_MEGAPIXELS * 1_000_000f) / (w.toLong() * h.toLong()))
            Pair((w * scale).toInt().coerceAtLeast(MIN_DIMENSION), (h * scale).toInt().coerceAtLeast(MIN_DIMENSION))
        } else {
            Pair(w, h)
        }
    }
}
