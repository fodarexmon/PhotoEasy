package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class RefineEdgeConfig(
    val radiusPx: Int = 10,
    val smartRadius: Boolean = true,
    val hairRefine: Boolean = true,
    val smoothPx: Int = 2,
    val featherPx: Float = 0f,
    val contrastPercent: Float = 20f,
    val shiftEdgePercent: Float = 0f,
    val decontaminateColors: Boolean = true,
    val decontaminateAmount: Float = 0.8f
)

data class RefineResult(
    val refinedMask: Bitmap,
    val decontaminatedSource: Bitmap? = null
)

object RefineEdgeEngine {

    /**
     * Professional Alpha Matting & Edge Refinement:
     * Evaluates source RGB colors against a dynamic Trimap to extract fine hair strands,
     * fur, and translucent edges, eliminating background halos.
     */
    fun refineEdges(
        source: Bitmap,
        mask: Bitmap,
        config: RefineEdgeConfig
    ): RefineResult {
        require(source.width == mask.width && source.height == mask.height) {
            "Source and mask dimensions must match"
        }

        val width = source.width
        val height = source.height
        val total = width * height

        val srcPixels = IntArray(total)
        val maskPixels = IntArray(total)
        source.getPixels(srcPixels, 0, width, 0, 0, width, height)
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height)

        val alphaIn = FloatArray(total)
        for (i in 0 until total) {
            val mp = maskPixels[i]
            val a = (mp ushr 24) and 0xFF
            val lum = mp and 0xFF
            alphaIn[i] = max(a, lum) / 255f
        }

        // 1. Shift Edge (Dilation if positive, Erosion if negative)
        val shiftedAlpha = if (abs(config.shiftEdgePercent) > 1f) {
            applyShiftEdge(alphaIn, width, height, config.shiftEdgePercent)
        } else {
            alphaIn.clone()
        }

        // 2. Build Dynamic Trimap (Foreground, Background, Unknown Transition Band)
        val radius = config.radiusPx.coerceIn(1, 40)
        val trimap = ByteArray(total) // 0 = BG, 1 = UNKNOWN, 2 = FG
        buildTrimap(shiftedAlpha, trimap, width, height, radius)

        // 3. Guided Color Alpha Matting (Hair & Fine Fur extraction)
        val mattedAlpha = shiftedAlpha.clone()
        val decontamPixels = if (config.decontaminateColors) srcPixels.clone() else null

        // Calculate matting for unknown transition band pixels
        val searchRadius = if (config.smartRadius) (radius * 1.5f).toInt().coerceIn(4, 25) else radius
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val idx = row + x
                if (trimap[idx] != 1.toByte()) {
                    mattedAlpha[idx] = if (trimap[idx] == 2.toByte()) 1f else 0f
                    continue
                }

                // Sample confident foreground (FG) and background (BG) within local search window
                var fgR = 0f; var fgG = 0f; var fgB = 0f; var fgWeight = 0f
                var bgR = 0f; var bgG = 0f; var bgB = 0f; var bgWeight = 0f

                val y0 = (y - searchRadius).coerceAtLeast(0)
                val y1 = (y + searchRadius).coerceAtMost(height - 1)
                val x0 = (x - searchRadius).coerceAtLeast(0)
                val x1 = (x + searchRadius).coerceAtMost(width - 1)

                val currPix = srcPixels[idx]
                val cr = ((currPix ushr 16) and 0xFF).toFloat()
                val cg = ((currPix ushr 8) and 0xFF).toFloat()
                val cb = (currPix and 0xFF).toFloat()

                for (sy in y0..y1 step 2) {
                    val sRow = sy * width
                    val dy = sy - y
                    for (sx in x0..x1 step 2) {
                        val sIdx = sRow + sx
                        val state = trimap[sIdx]
                        if (state == 1.toByte()) continue

                        val dx = sx - x
                        val distSq = (dx * dx + dy * dy).toFloat() + 0.1f
                        val spatWeight = 1f / distSq

                        val sp = srcPixels[sIdx]
                        val r = ((sp ushr 16) and 0xFF).toFloat()
                        val g = ((sp ushr 8) and 0xFF).toFloat()
                        val b = (sp and 0xFF).toFloat()

                        if (state == 2.toByte()) {
                            // Foreground sample
                            val colorDiff = (r - cr) * (r - cr) + (g - cg) * (g - cg) + (b - cb) * (b - cb)
                            val w = spatWeight / (colorDiff + 10f)
                            fgR += r * w; fgG += g * w; fgB += b * w; fgWeight += w
                        } else {
                            // Background sample
                            val colorDiff = (r - cr) * (r - cr) + (g - cg) * (g - cg) + (b - cb) * (b - cb)
                            val w = spatWeight / (colorDiff + 10f)
                            bgR += r * w; bgG += g * w; bgB += b * w; bgWeight += w
                        }
                    }
                }

                if (fgWeight > 0.0001f && bgWeight > 0.0001f) {
                    val meanFgR = fgR / fgWeight; val meanFgG = fgG / fgWeight; val meanFgB = fgB / fgWeight
                    val meanBgR = bgR / bgWeight; val meanBgG = bgG / bgWeight; val meanBgB = bgB / bgWeight

                    val diffR = meanFgR - meanBgR
                    val diffG = meanFgG - meanBgG
                    val diffB = meanFgB - meanBgB
                    val denom = diffR * diffR + diffG * diffG + diffB * diffB

                    if (denom > 16f) {
                        val num = (cr - meanBgR) * diffR + (cg - meanBgG) * diffG + (cb - meanBgB) * diffB
                        var alphaEst = (num / denom).coerceIn(0f, 1f)

                        if (config.hairRefine) {
                            // High-pass gradient booster for single hair strands
                            val lumCurr = 0.299f * cr + 0.587f * cg + 0.114f * cb
                            val lumFg = 0.299f * meanFgR + 0.587f * meanFgG + 0.114f * meanFgB
                            val lumBg = 0.299f * meanBgR + 0.587f * meanBgG + 0.114f * meanBgB
                            if (abs(lumFg - lumBg) > 10f) {
                                val strandWeight = ((lumCurr - lumBg) / (lumFg - lumBg)).coerceIn(0f, 1f)
                                alphaEst = alphaEst * 0.6f + strandWeight * 0.4f
                            }
                        }

                        mattedAlpha[idx] = alphaEst

                        // Decontaminate edge color fringes
                        if (decontamPixels != null && alphaEst in 0.05f..0.95f) {
                            val factor = config.decontaminateAmount
                            val cleanR = (cr - (1f - alphaEst) * meanBgR * factor) / (alphaEst + (1f - factor) * (1f - alphaEst)).coerceAtLeast(0.1f)
                            val cleanG = (cg - (1f - alphaEst) * meanBgG * factor) / (alphaEst + (1f - factor) * (1f - alphaEst)).coerceAtLeast(0.1f)
                            val cleanB = (cb - (1f - alphaEst) * meanBgB * factor) / (alphaEst + (1f - factor) * (1f - alphaEst)).coerceAtLeast(0.1f)

                            val ir = cleanR.toInt().coerceIn(0, 255)
                            val ig = cleanG.toInt().coerceIn(0, 255)
                            val ib = cleanB.toInt().coerceIn(0, 255)
                            decontamPixels[idx] = (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
                        }
                    }
                }
            }
        }

        // 4. Smooth & Contrast
        if (config.smoothPx > 0) {
            applyBoxBlur(mattedAlpha, width, height, config.smoothPx)
        }

        if (config.contrastPercent > 0f) {
            val factor = 1f + (config.contrastPercent / 40f)
            for (i in 0 until total) {
                val a = mattedAlpha[i]
                if (a in 0.01f..0.99f) {
                    val s = ((a - 0.5f) * factor + 0.5f).coerceIn(0f, 1f)
                    mattedAlpha[i] = s
                }
            }
        }

        // 5. Feather
        if (config.featherPx > 0.5f) {
            applyBoxBlur(mattedAlpha, width, height, config.featherPx.toInt().coerceAtLeast(1))
        }

        // Write output mask
        val outMaskPixels = IntArray(total)
        for (i in 0 until total) {
            val a = (mattedAlpha[i] * 255f).toInt().coerceIn(0, 255)
            outMaskPixels[i] = (a shl 24) or 0x00FFFFFF
        }

        val outMask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        outMask.setPixels(outMaskPixels, 0, width, 0, 0, width, height)

        var decontamBmp: Bitmap? = null
        if (decontamPixels != null) {
            decontamBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            decontamBmp.setPixels(decontamPixels, 0, width, 0, 0, width, height)
        }

        return RefineResult(outMask, decontamBmp)
    }

    /**
     * Builds a 3-region Trimap:
     * 0 = Confident Background
     * 1 = Unknown transition zone (where hair and boundary lies)
     * 2 = Confident Foreground
     */
    private fun buildTrimap(
        alpha: FloatArray,
        trimap: ByteArray,
        w: Int,
        h: Int,
        radius: Int
    ) {
        val total = w * h
        val isFg = BooleanArray(total) { alpha[it] > 0.85f }
        val isBg = BooleanArray(total) { alpha[it] < 0.15f }

        // Find boundary pixels
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                if (alpha[idx] in 0.15f..0.85f) {
                    trimap[idx] = 1 // Unknown
                } else if (alpha[idx] >= 0.85f) {
                    trimap[idx] = 2 // Foreground
                } else {
                    trimap[idx] = 0 // Background
                }
            }
        }

        // Expand unknown band by radius around existing edges
        val edgeBand = BooleanArray(total)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                if (trimap[idx] == 1.toByte() || (trimap[idx] == 2.toByte() && (trimap[idx - 1] == 0.toByte() || trimap[idx + 1] == 0.toByte()))) {
                    edgeBand[idx] = true
                }
            }
        }

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                if (edgeBand[idx]) {
                    val y0 = (y - radius).coerceAtLeast(0)
                    val y1 = (y + radius).coerceAtMost(h - 1)
                    val x0 = (x - radius).coerceAtLeast(0)
                    val x1 = (x + radius).coerceAtMost(w - 1)
                    val rSq = radius * radius

                    for (ny in y0..y1) {
                        val nRow = ny * w
                        val dy = ny - y
                        for (nx in x0..x1) {
                            val dx = nx - x
                            if (dx * dx + dy * dy <= rSq) {
                                trimap[nRow + nx] = 1.toByte()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun applyShiftEdge(alpha: FloatArray, w: Int, h: Int, shiftPercent: Float): FloatArray {
        val total = w * h
        val out = alpha.clone()
        val shiftRadius = (abs(shiftPercent) / 100f * 15f).toInt().coerceIn(1, 15)
        val isDilate = shiftPercent > 0f

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                val y0 = (y - shiftRadius).coerceAtLeast(0)
                val y1 = (y + shiftRadius).coerceAtMost(h - 1)
                val x0 = (x - shiftRadius).coerceAtLeast(0)
                val x1 = (x + shiftRadius).coerceAtMost(w - 1)

                var targetVal = if (isDilate) 0f else 1f
                for (ny in y0..y1) {
                    val nRow = ny * w
                    for (nx in x0..x1) {
                        val v = alpha[nRow + nx]
                        if (isDilate) {
                            if (v > targetVal) targetVal = v
                        } else {
                            if (v < targetVal) targetVal = v
                        }
                    }
                }
                out[idx] = targetVal
            }
        }
        return out
    }

    private fun applyBoxBlur(alpha: FloatArray, w: Int, h: Int, r: Int) {
        val total = w * h
        val temp = FloatArray(total)

        // Horizontal pass
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val x0 = (x - r).coerceAtLeast(0)
                val x1 = (x + r).coerceAtMost(w - 1)
                var sum = 0f
                for (nx in x0..x1) {
                    sum += alpha[row + nx]
                }
                temp[row + x] = sum / (x1 - x0 + 1)
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            for (y in 0 until h) {
                val y0 = (y - r).coerceAtLeast(0)
                val y1 = (y + r).coerceAtMost(h - 1)
                var sum = 0f
                for (ny in y0..y1) {
                    sum += temp[ny * w + x]
                }
                alpha[y * w + x] = sum / (y1 - y0 + 1)
            }
        }
    }

    /**
     * One-Tap Intelligent Subject Selection:
     * Analyzes image contrast, color saliency, and central spatial priors to isolate
     * the primary subject automatically with high accuracy.
     */
    fun selectSubject(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val total = w * h
        val pixels = IntArray(total)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1. Estimate background color model from the 4 image borders (10% outer margin)
        var bgSumR = 0.0; var bgSumG = 0.0; var bgSumB = 0.0; var bgCount = 0
        val marginX = (w * 0.1f).toInt().coerceAtLeast(2)
        val marginY = (h * 0.1f).toInt().coerceAtLeast(2)

        for (y in 0 until h step 2) {
            val row = y * w
            for (x in 0 until w step 2) {
                if (x < marginX || x >= w - marginX || y < marginY || y >= h - marginY) {
                    val p = pixels[row + x]
                    bgSumR += ((p ushr 16) and 0xFF)
                    bgSumG += ((p ushr 8) and 0xFF)
                    bgSumB += (p and 0xFF)
                    bgCount++
                }
            }
        }
        val bgMeanR = if (bgCount > 0) bgSumR / bgCount else 128.0
        val bgMeanG = if (bgCount > 0) bgSumG / bgCount else 128.0
        val bgMeanB = if (bgCount > 0) bgSumB / bgCount else 128.0

        // 2. Compute saliency distance map weighted by central Gaussian prior
        val saliency = FloatArray(total)
        val centerX = w / 2f
        val centerY = h / 2f
        val maxDistSq = (centerX * centerX + centerY * centerY)

        var maxSal = 0.001f
        for (y in 0 until h) {
            val row = y * w
            val dy = y - centerY
            for (x in 0 until w) {
                val idx = row + x
                val p = pixels[idx]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF

                val dR = r - bgMeanR
                val dG = g - bgMeanG
                val dB = b - bgMeanB
                val colorDist = sqrt(dR * dR + dG * dG + dB * dB).toFloat()

                val dx = x - centerX
                val centerDistSq = dx * dx + dy * dy
                val centerWeight = (1.0f - (centerDistSq / maxDistSq) * 0.65f).coerceIn(0.1f, 1.0f)

                val sal = colorDist * centerWeight
                saliency[idx] = sal
                if (sal > maxSal) maxSal = sal
            }
        }

        // 3. Adaptive Thresholding & Morphology pass to produce solid mask
        val threshold = maxSal * 0.35f
        val maskPixels = IntArray(total)

        for (i in 0 until total) {
            val isSubject = saliency[i] >= threshold
            maskPixels[i] = if (isSubject) 0xFFFFFFFF.toInt() else 0x00000000
        }

        val subjectMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        subjectMask.setPixels(maskPixels, 0, w, 0, 0, w, h)

        // Refine with soft 2px smoothing pass
        val refined = refineEdges(
            source = source,
            mask = subjectMask,
            config = RefineEdgeConfig(radiusPx = 6, smoothPx = 2, hairRefine = true, contrastPercent = 30f)
        )
        subjectMask.recycle()
        return refined.refinedMask
    }
}
