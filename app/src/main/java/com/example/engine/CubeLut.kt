package com.example.engine

import kotlinx.coroutines.CancellationException

/**
 * A 3D colour lookup table in the Adobe/Resolve ".cube" format (the industry standard for film
 * looks and colour grades). Applied with trilinear interpolation.
 */
class CubeLut(
    val title: String,
    val size: Int,
    /** RGB triplets, red fastest: index = ((b * size + g) * size + r) * 3. */
    private val data: FloatArray,
    private val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    private val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f)
) {
    companion object {
        const val MAX_SIZE = 65

        /** Parses .cube text. Throws IllegalArgumentException for unsupported / malformed files. */
        fun parse(text: String, fallbackTitle: String): CubeLut {
            var title = fallbackTitle
            var size = 0
            val dMin = floatArrayOf(0f, 0f, 0f)
            val dMax = floatArrayOf(1f, 1f, 1f)
            var values: FloatArray? = null
            var count = 0
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val upper = line.uppercase()
                when {
                    upper.startsWith("TITLE") -> title = line.substringAfter("TITLE").trim().trim('"').ifBlank { fallbackTitle }
                    upper.startsWith("LUT_1D_SIZE") -> throw IllegalArgumentException("1D LUTs are not supported")
                    upper.startsWith("LUT_3D_SIZE") -> {
                        size = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: 0
                        require(size in 2..MAX_SIZE) { "Unsupported LUT size $size" }
                        values = FloatArray(size * size * size * 3)
                    }
                    upper.startsWith("DOMAIN_MIN") -> line.split(Regex("\\s+")).drop(1).take(3).forEachIndexed { i, v -> dMin[i] = v.toFloat() }
                    upper.startsWith("DOMAIN_MAX") -> line.split(Regex("\\s+")).drop(1).take(3).forEachIndexed { i, v -> dMax[i] = v.toFloat() }
                    upper[0].isLetter() -> Unit // other keywords (e.g. LUT_IN_VIDEO_RANGE) are ignored
                    else -> {
                        val arr = values ?: throw IllegalArgumentException("LUT_3D_SIZE missing")
                        val parts = line.split(Regex("\\s+"))
                        require(parts.size >= 3) { "Malformed LUT line" }
                        require(count + 3 <= arr.size) { "Too many LUT entries" }
                        arr[count++] = parts[0].toFloat()
                        arr[count++] = parts[1].toFloat()
                        arr[count++] = parts[2].toFloat()
                    }
                }
            }
            val arr = values ?: throw IllegalArgumentException("Not a 3D .cube LUT")
            require(count == arr.size) { "Incomplete LUT ($count of ${arr.size} values)" }
            return CubeLut(title, size, arr, dMin, dMax)
        }
    }

    /** Maps every ARGB pixel through the LUT (alpha untouched). */
    fun applyInPlace(px: IntArray, isCancelled: (() -> Boolean)? = null) {
        val n = size - 1
        val sR = n / (domainMax[0] - domainMin[0]).coerceAtLeast(1e-6f)
        val sG = n / (domainMax[1] - domainMin[1]).coerceAtLeast(1e-6f)
        val sB = n / (domainMax[2] - domainMin[2]).coerceAtLeast(1e-6f)
        for (i in px.indices) {
            if ((i and 0xFFFF) == 0 && isCancelled?.invoke() == true) throw CancellationException("LUT cancelled")
            val p = px[i]
            val a = (p ushr 24) and 0xFF
            if (a == 0) continue
            val fr = (((p shr 16) and 0xFF) / 255f - domainMin[0]) * sR
            val fg = (((p shr 8) and 0xFF) / 255f - domainMin[1]) * sG
            val fb = ((p and 0xFF) / 255f - domainMin[2]) * sB
            val r0 = fr.toInt().coerceIn(0, n); val g0 = fg.toInt().coerceIn(0, n); val b0 = fb.toInt().coerceIn(0, n)
            val r1 = minOf(r0 + 1, n); val g1 = minOf(g0 + 1, n); val b1 = minOf(b0 + 1, n)
            val tr = (fr - r0).coerceIn(0f, 1f); val tg = (fg - g0).coerceIn(0f, 1f); val tb = (fb - b0).coerceIn(0f, 1f)
            var outR = 0f; var outG = 0f; var outB = 0f
            for (c in 0 until 3) {
                fun v(r: Int, g: Int, b: Int) = data[((b * size + g) * size + r) * 3 + c]
                val c00 = v(r0, g0, b0) + (v(r1, g0, b0) - v(r0, g0, b0)) * tr
                val c10 = v(r0, g1, b0) + (v(r1, g1, b0) - v(r0, g1, b0)) * tr
                val c01 = v(r0, g0, b1) + (v(r1, g0, b1) - v(r0, g0, b1)) * tr
                val c11 = v(r0, g1, b1) + (v(r1, g1, b1) - v(r0, g1, b1)) * tr
                val c0 = c00 + (c10 - c00) * tg
                val c1 = c01 + (c11 - c01) * tg
                val value = c0 + (c1 - c0) * tb
                when (c) { 0 -> outR = value; 1 -> outG = value; else -> outB = value }
            }
            px[i] = (a shl 24) or ((outR * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((outG * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or (outB * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
    }
}
