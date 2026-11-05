package com.example.util

object CanvasValidation {
    const val MIN_DIMENSION = 64
    const val MAX_DIMENSION = 4096
    const val MAX_MEGAPIXELS = 12.0f // Safe 12 MP (~4000x3000 px) to prevent Android Dalvik OOM heap exhaustion

    data class ValidationResult(
        val isValid: Boolean,
        val width: Int,
        val height: Int,
        val errorMessage: String? = null
    )

    fun validate(widthInput: Int?, heightInput: Int?): ValidationResult {
        if (widthInput == null || heightInput == null) {
            return ValidationResult(false, 0, 0, "Please enter valid numeric dimensions.")
        }
        if (widthInput < MIN_DIMENSION || heightInput < MIN_DIMENSION) {
            return ValidationResult(false, widthInput, heightInput, "Minimum canvas dimension is $MIN_DIMENSION px.")
        }
        if (widthInput > MAX_DIMENSION || heightInput > MAX_DIMENSION) {
            return ValidationResult(false, widthInput, heightInput, "Maximum canvas dimension is $MAX_DIMENSION px.")
        }
        val megaPixels = (widthInput.toLong() * heightInput.toLong()) / 1_000_000f
        if (megaPixels > MAX_MEGAPIXELS) {
            return ValidationResult(
                false, widthInput, heightInput,
                "Canvas exceeds limit of ${MAX_MEGAPIXELS.toInt()} Megapixels ($widthInput x $heightInput = ${String.format("%.1f", megaPixels)} MP)."
            )
        }
        return ValidationResult(true, widthInput, heightInput, null)
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
