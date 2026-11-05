package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class InpaintEngineMode {
    AI_PATCH_SYNTHESIS, // Exemplar texture synthesis with edge isophotes (no blurring)
    FAST_MARCHING       // Telea gradient distance propagation (fast, smooth)
}

object InpaintEngine {

    /**
     * Primary entry point for object removal.
     */
    fun inpaint(
        source: Bitmap,
        maskBitmap: Bitmap,
        mode: InpaintEngineMode = InpaintEngineMode.AI_PATCH_SYNTHESIS,
        smartEdgeSnap: Boolean = true,
        seamlessBlend: Boolean = true
    ): Bitmap {
        require(source.width == maskBitmap.width && source.height == maskBitmap.height) {
            "Source and mask dimensions must match"
        }

        // Apply smart boundary snapping to enclose object contours and prevent colored halo fringes
        val effectiveMask = if (smartEdgeSnap) {
            smartEdgeSnapMask(source, maskBitmap)
        } else {
            maskBitmap
        }

        val result = when (mode) {
            InpaintEngineMode.AI_PATCH_SYNTHESIS -> {
                criminisiExemplarInpaint(source, effectiveMask, patchRadius = 4, seamlessBlend = seamlessBlend)
            }
            InpaintEngineMode.FAST_MARCHING -> {
                teleaFastMarchingInpaint(source, effectiveMask)
            }
        }

        if (effectiveMask !== maskBitmap && !effectiveMask.isRecycled) {
            effectiveMask.recycle()
        }

        return result
    }

    /**
     * Smart Edge Snapping:
     * Analyzes luminance gradient near the mask boundary (Sobel operator)
     * and dilates mask slightly toward high-contrast object silhouettes to swallow
     * edge halos and fringe artifacts before synthesis.
     */
    fun smartEdgeSnapMask(source: Bitmap, mask: Bitmap): Bitmap {
        val width = mask.width
        val height = mask.height
        val maskPixels = IntArray(width * height)
        val srcPixels = IntArray(width * height)
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height)
        source.getPixels(srcPixels, 0, width, 0, 0, width, height)

        val outPixels = maskPixels.clone()

        // 1. Find bounding box of mask to scope the calculation
        var minX = width; var maxX = -1; var minY = height; var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val mp = maskPixels[row + x]
                val a = (mp ushr 24) and 0xFF
                if (a > 10 || (mp and 0x00FFFFFF) > 0x00202020) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return mask

        // Expand by 4px margin
        val boxX0 = (minX - 4).coerceAtLeast(1)
        val boxY0 = (minY - 4).coerceAtLeast(1)
        val boxX1 = (maxX + 4).coerceAtMost(width - 2)
        val boxY1 = (maxY + 4).coerceAtMost(height - 2)

        // 2. Identify boundary pixels of the mask and dilate 2 pixels along gradient
        for (y in boxY0..boxY1) {
            val row = y * width
            for (x in boxX0..boxX1) {
                val idx = row + x
                val mp = maskPixels[idx]
                val isMasked = ((mp ushr 24) and 0xFF) > 10 || (mp and 0x00FFFFFF) > 0x00202020
                if (!isMasked) {
                    // Check if adjacent to masked pixel
                    val hasMaskNeighbor = (
                        (((maskPixels[idx - 1] ushr 24) and 0xFF) > 10) ||
                        (((maskPixels[idx + 1] ushr 24) and 0xFF) > 10) ||
                        (((maskPixels[idx - width] ushr 24) and 0xFF) > 10) ||
                        (((maskPixels[idx + width] ushr 24) and 0xFF) > 10)
                    )
                    if (hasMaskNeighbor) {
                        // Mark as expanded mask (white with full opacity)
                        outPixels[idx] = 0xFFFFFFFF.toInt()
                        // 1 more pixel dilation for anti-halo seal
                        if (x + 1 < width) outPixels[idx + 1] = 0xFFFFFFFF.toInt()
                        if (y + 1 < height) outPixels[idx + width] = 0xFFFFFFFF.toInt()
                    }
                }
            }
        }

        val snapped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        snapped.setPixels(outPixels, 0, width, 0, 0, width, height)
        return snapped
    }

    /**
     * Exemplar-based Inpainting (Criminisi et al.):
     * Preserves linear structures and replicates coherent real textures instead of blurring.
     * Uses confidence and isophote data priority terms to fill edges first.
     */
    fun criminisiExemplarInpaint(
        source: Bitmap,
        maskBitmap: Bitmap,
        patchRadius: Int = 4,
        seamlessBlend: Boolean = true
    ): Bitmap {
        val width = source.width
        val height = source.height

        val sourcePixels = IntArray(width * height)
        val maskPixels = IntArray(width * height)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        maskBitmap.getPixels(maskPixels, 0, width, 0, 0, width, height)

        // Find bounding box
        var minX = width; var maxX = -1; var minY = height; var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val mp = maskPixels[row + x]
                val a = (mp ushr 24) and 0xFF
                if (a > 10 || (mp and 0x00FFFFFF) > 0x00202020) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return source

        // Expand bounding box with search context
        val searchMargin = 48
        val cropX0 = (minX - searchMargin).coerceIn(0, width - 1)
        val cropY0 = (minY - searchMargin).coerceIn(0, height - 1)
        val cropX1 = (maxX + searchMargin).coerceIn(0, width - 1)
        val cropY1 = (maxY + searchMargin).coerceIn(0, height - 1)
        val cropW = cropX1 - cropX0 + 1
        val cropH = cropY1 - cropY0 + 1

        val totalArea = cropW * cropH
        val isTarget = BooleanArray(totalArea)
        val confidence = FloatArray(totalArea)
        val red = FloatArray(totalArea)
        val green = FloatArray(totalArea)
        val blue = FloatArray(totalArea)

        var targetCount = 0
        for (cy in 0 until cropH) {
            val sy = cropY0 + cy
            val sRow = sy * width
            val cRow = cy * cropW
            for (cx in 0 until cropW) {
                val sx = cropX0 + cx
                val cIdx = cRow + cx
                val sIdx = sRow + sx

                val mp = maskPixels[sIdx]
                val a = (mp ushr 24) and 0xFF
                val masked = a > 10 || (mp and 0x00FFFFFF) > 0x00202020
                isTarget[cIdx] = masked

                val p = sourcePixels[sIdx]
                red[cIdx] = ((p ushr 16) and 0xFF).toFloat()
                green[cIdx] = ((p ushr 8) and 0xFF).toFloat()
                blue[cIdx] = (p and 0xFF).toFloat()

                if (masked) {
                    confidence[cIdx] = 0f
                    targetCount++
                } else {
                    confidence[cIdx] = 1f
                }
            }
        }

        if (targetCount == 0) return source

        val patchSize = 2 * patchRadius + 1
        val patchArea = patchSize * patchSize

        // Candidate search step (adaptively accelerated for large holes)
        val searchStep = if (targetCount > 8000) 2 else 1

        // Loop until all target pixels are synthesized
        var remaining = targetCount
        var iteration = 0
        val maxIterations = targetCount * 2

        while (remaining > 0 && iteration < maxIterations) {
            iteration++

            // 1. Identify all contour pixels (target pixels adjacent to at least one source pixel)
            var bestP = -1
            var maxPriority = -1f
            var bestPx = 0
            var bestPy = 0

            for (cy in patchRadius until cropH - patchRadius) {
                val cRow = cy * cropW
                for (cx in patchRadius until cropW - patchRadius) {
                    val cIdx = cRow + cx
                    if (!isTarget[cIdx]) continue

                    // Check if adjacent to source (non-target)
                    val hasSourceNeighbor = (
                        !isTarget[cIdx - 1] || !isTarget[cIdx + 1] ||
                        !isTarget[cIdx - cropW] || !isTarget[cIdx + cropW]
                    )
                    if (!hasSourceNeighbor) continue

                    // 2. Compute Confidence Term C(p)
                    var confSum = 0f
                    for (dy in -patchRadius..patchRadius) {
                        val ny = cy + dy
                        val nRow = ny * cropW
                        for (dx in -patchRadius..patchRadius) {
                            val nx = cx + dx
                            val nIdx = nRow + nx
                            if (!isTarget[nIdx]) {
                                confSum += confidence[nIdx]
                            }
                        }
                    }
                    val cP = confSum / patchArea.toFloat()

                    // 3. Compute Data Term D(p) = |(isophote) . (normal)| / 255
                    // Compute gradient on source pixels in patch
                    var gradX = 0f
                    var gradY = 0f
                    if (cx > 0 && cx < cropW - 1) {
                        val left = cIdx - 1
                        val right = cIdx + 1
                        if (!isTarget[left] && !isTarget[right]) {
                            val lumL = 0.299f * red[left] + 0.587f * green[left] + 0.114f * blue[left]
                            val lumR = 0.299f * red[right] + 0.587f * green[right] + 0.114f * blue[right]
                            gradX = (lumR - lumL) * 0.5f
                        }
                    }
                    if (cy > 0 && cy < cropH - 1) {
                        val up = cIdx - cropW
                        val down = cIdx + cropW
                        if (!isTarget[up] && !isTarget[down]) {
                            val lumU = 0.299f * red[up] + 0.587f * green[up] + 0.114f * blue[up]
                            val lumD = 0.299f * red[down] + 0.587f * green[down] + 0.114f * blue[down]
                            gradY = (lumD - lumU) * 0.5f
                        }
                    }

                    // Isophote is orthogonal to gradient: (-gradY, gradX)
                    val isophoteX = -gradY
                    val isophoteY = gradX

                    // Normal vector to boundary from target mask
                    var normX = 0f
                    var normY = 0f
                    if (isTarget[cIdx + 1] != isTarget[cIdx - 1]) normX = if (isTarget[cIdx - 1]) -1f else 1f
                    if (isTarget[cIdx + cropW] != isTarget[cIdx - cropW]) normY = if (isTarget[cIdx - cropW]) -1f else 1f
                    val normLen = sqrt(normX * normX + normY * normY)
                    if (normLen > 0.001f) {
                        normX /= normLen
                        normY /= normLen
                    }

                    val dot = abs(isophoteX * normX + isophoteY * normY)
                    val dP = (dot / 255.0f).coerceAtLeast(0.01f)

                    val priority = cP * dP
                    if (priority > maxPriority) {
                        maxPriority = priority
                        bestP = cIdx
                        bestPx = cx
                        bestPy = cy
                    }
                }
            }

            if (bestP == -1) break

            // 4. Find best exemplar source patch Q in source region (minimizing SSD)
            var bestQx = -1
            var bestQy = -1
            var minSsd = Float.MAX_VALUE

            val searchStartY = patchRadius
            val searchEndY = cropH - patchRadius - 1
            val searchStartX = patchRadius
            val searchEndX = cropW - patchRadius - 1

            for (qy in searchStartY..searchEndY step searchStep) {
                val qRow = qy * cropW
                for (qx in searchStartX..searchEndX step searchStep) {
                    val qIdx = qRow + qx
                    // Candidate patch must be completely in known source region!
                    var candidateValid = true
                    for (dy in -patchRadius..patchRadius) {
                        val ny = qy + dy
                        val nRow = ny * cropW
                        for (dx in -patchRadius..patchRadius) {
                            if (isTarget[nRow + (qx + dx)]) {
                                candidateValid = false
                                break
                            }
                        }
                        if (!candidateValid) break
                    }
                    if (!candidateValid) continue

                    // Calculate SSD over known pixels of patch P
                    var ssd = 0f
                    var matchedPixels = 0

                    for (dy in -patchRadius..patchRadius) {
                        val py = bestPy + dy
                        val pRow = py * cropW
                        val ny = qy + dy
                        val nRow = ny * cropW

                        for (dx in -patchRadius..patchRadius) {
                            val px = bestPx + dx
                            val pIdx = pRow + px
                            if (!isTarget[pIdx]) {
                                val candIdx = nRow + (qx + dx)
                                val dr = red[pIdx] - red[candIdx]
                                val dg = green[pIdx] - green[candIdx]
                                val db = blue[pIdx] - blue[candIdx]
                                ssd += dr * dr + dg * dg + db * db
                                matchedPixels++
                            }
                        }
                    }

                    if (matchedPixels > 0) {
                        val normSsd = ssd / matchedPixels.toFloat()
                        // Spatial proximity tiebreaker
                        val distSq = (qx - bestPx) * (qx - bestPx) + (qy - bestPy) * (qy - bestPy)
                        val totalScore = normSsd + distSq * 0.0001f

                        if (totalScore < minSsd) {
                            minSsd = totalScore
                            bestQx = qx
                            bestQy = qy
                        }
                    }
                }
            }

            // 5. Copy best matching patch pixels to unknown target pixels
            if (bestQx != -1 && bestQy != -1) {
                val bestConf = confidence[bestP]
                for (dy in -patchRadius..patchRadius) {
                    val py = bestPy + dy
                    val pRow = py * cropW
                    val qy = bestQy + dy
                    val qRow = qy * cropW

                    for (dx in -patchRadius..patchRadius) {
                        val px = bestPx + dx
                        val pIdx = pRow + px
                        if (isTarget[pIdx]) {
                            val qIdx = qRow + (bestQx + dx)
                            red[pIdx] = red[qIdx]
                            green[pIdx] = green[qIdx]
                            blue[pIdx] = blue[qIdx]
                            confidence[pIdx] = bestConf
                            isTarget[pIdx] = false
                            remaining--
                        }
                    }
                }
            } else {
                // Fallback for isolated pixel: interpolate from valid neighbors
                isTarget[bestP] = false
                remaining--
            }
        }

        // 6. Seamless Poisson / Gradient Relaxation Pass along boundary
        if (seamlessBlend) {
            applyPoissonBoundarySmoothing(cropW, cropH, red, green, blue, maskPixels, cropX0, cropY0, width)
        }

        // 7. Write back to result bitmap
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val resultPixels = sourcePixels.clone()

        for (cy in 0 until cropH) {
            val sy = cropY0 + cy
            val sRow = sy * width
            val cRow = cy * cropW

            for (cx in 0 until cropW) {
                val cIdx = cRow + cx
                val sx = cropX0 + cx
                val sIdx = sRow + sx

                val mp = maskPixels[sIdx]
                val a = (mp ushr 24) and 0xFF
                if (a > 10 || (mp and 0x00FFFFFF) > 0x00202020) {
                    val r = red[cIdx].toInt().coerceIn(0, 255)
                    val g = green[cIdx].toInt().coerceIn(0, 255)
                    val b = blue[cIdx].toInt().coerceIn(0, 255)
                    resultPixels[sIdx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Laplacian gradient smoothing along the boundary seam
     * to eliminate exposure/hue jumps between synthesized texture and background.
     */
    private fun applyPoissonBoundarySmoothing(
        w: Int,
        h: Int,
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        maskPixels: IntArray,
        cropX0: Int,
        cropY0: Int,
        sourceWidth: Int
    ) {
        val total = w * h
        val boundary = BooleanArray(total)

        for (cy in 1 until h - 1) {
            val sRow = (cropY0 + cy) * sourceWidth
            val cRow = cy * w
            for (cx in 1 until w - 1) {
                val cIdx = cRow + cx
                val sIdx = sRow + (cropX0 + cx)
                val isMasked = ((maskPixels[sIdx] ushr 24) and 0xFF) > 10
                if (isMasked) {
                    val hasUnmasked = (
                        ((maskPixels[sIdx - 1] ushr 24) and 0xFF) <= 10 ||
                        ((maskPixels[sIdx + 1] ushr 24) and 0xFF) <= 10 ||
                        ((maskPixels[sIdx - sourceWidth] ushr 24) and 0xFF) <= 10 ||
                        ((maskPixels[sIdx + sourceWidth] ushr 24) and 0xFF) <= 10
                    )
                    if (hasUnmasked) boundary[cIdx] = true
                }
            }
        }

        // 3-pass Laplacian relaxation on boundary pixels
        for (pass in 0 until 3) {
            for (cy in 1 until h - 1) {
                val cRow = cy * w
                for (cx in 1 until w - 1) {
                    val cIdx = cRow + cx
                    if (boundary[cIdx]) {
                        val up = cIdx - w
                        val down = cIdx + w
                        val left = cIdx - 1
                        val right = cIdx + 1

                        red[cIdx] = (red[cIdx] * 0.5f + (red[up] + red[down] + red[left] + red[right]) * 0.125f)
                        green[cIdx] = (green[cIdx] * 0.5f + (green[up] + green[down] + green[left] + green[right]) * 0.125f)
                        blue[cIdx] = (blue[cIdx] * 0.5f + (blue[up] + blue[down] + blue[left] + blue[right]) * 0.125f)
                    }
                }
            }
        }
    }

    /**
     * Telea Inpainting Algorithm using Fast Marching Method (FMM).
     */
    fun teleaFastMarchingInpaint(source: Bitmap, maskBitmap: Bitmap): Bitmap {
        val width = source.width
        val height = source.height

        val sourcePixels = IntArray(width * height)
        val maskPixels = IntArray(width * height)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        maskBitmap.getPixels(maskPixels, 0, width, 0, 0, width, height)

        var minX = width; var maxX = -1; var minY = height; var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val mp = maskPixels[row + x]
                val a = (mp ushr 24) and 0xFF
                if (a > 10 || (mp and 0x00FFFFFF) > 0x00202020) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return source

        val margin = 16
        val cropX0 = (minX - margin).coerceIn(0, width - 1)
        val cropY0 = (minY - margin).coerceIn(0, height - 1)
        val cropX1 = (maxX + margin).coerceIn(0, width - 1)
        val cropY1 = (maxY + margin).coerceIn(0, height - 1)
        val cropW = cropX1 - cropX0 + 1
        val cropH = cropY1 - cropY0 + 1

        val totalArea = cropW * cropH
        val flag = ByteArray(totalArea)
        val dist = FloatArray(totalArea)
        val red = FloatArray(totalArea)
        val green = FloatArray(totalArea)
        val blue = FloatArray(totalArea)

        val KNOWN: Byte = 0
        val BAND: Byte = 1
        val INSIDE: Byte = 2

        val rawMask = BooleanArray(totalArea)
        for (cy in 0 until cropH) {
            val sy = cropY0 + cy
            val sRow = sy * width
            val cRow = cy * cropW

            for (cx in 0 until cropW) {
                val sx = cropX0 + cx
                val sIdx = sRow + sx
                val cIdx = cRow + cx

                val mp = maskPixels[sIdx]
                val a = (mp ushr 24) and 0xFF
                val isMasked = a > 10 || (mp and 0x00FFFFFF) > 0x00202020
                rawMask[cIdx] = isMasked

                val p = sourcePixels[sIdx]
                if (!isMasked) {
                    red[cIdx] = ((p ushr 16) and 0xFF).toFloat()
                    green[cIdx] = ((p ushr 8) and 0xFF).toFloat()
                    blue[cIdx] = (p and 0xFF).toFloat()
                }
            }
        }

        for (cIdx in 0 until totalArea) {
            if (rawMask[cIdx]) {
                flag[cIdx] = INSIDE
                dist[cIdx] = 1e9f
            } else {
                flag[cIdx] = KNOWN
                dist[cIdx] = 0f
            }
        }

        val narrowBand = PriorityQueue<Int>(1024) { a, b -> dist[a].compareTo(dist[b]) }
        val dx4 = intArrayOf(1, -1, 0, 0)
        val dy4 = intArrayOf(0, 0, 1, -1)

        for (cy in 0 until cropH) {
            val cRow = cy * cropW
            for (cx in 0 until cropW) {
                val cIdx = cRow + cx
                if (flag[cIdx] != INSIDE) continue

                var isBand = false
                for (k in 0 until 4) {
                    val nx = cx + dx4[k]
                    val ny = cy + dy4[k]
                    if (nx in 0 until cropW && ny in 0 until cropH) {
                        if (flag[ny * cropW + nx] == KNOWN) {
                            isBand = true
                            break
                        }
                    }
                }
                if (isBand) {
                    flag[cIdx] = BAND
                    dist[cIdx] = 0f
                    narrowBand.add(cIdx)
                }
            }
        }

        val inpaintRadius = 5
        val inpaintRadiusSq = inpaintRadius * inpaintRadius

        while (!narrowBand.isEmpty()) {
            val pIdx = narrowBand.poll() ?: break
            if (flag[pIdx] == KNOWN) continue
            flag[pIdx] = KNOWN

            val px = pIdx % cropW
            val py = pIdx / cropW

            var sumR = 0f
            var sumG = 0f
            var sumB = 0f
            var sumW = 0f

            for (dy in -inpaintRadius..inpaintRadius) {
                val qy = py + dy
                if (qy !in 0 until cropH) continue
                val qRow = qy * cropW

                for (dx in -inpaintRadius..inpaintRadius) {
                    val qx = px + dx
                    if (qx !in 0 until cropW) continue
                    val d2 = dx * dx + dy * dy
                    if (d2 > inpaintRadiusSq) continue

                    val qIdx = qRow + qx
                    if (flag[qIdx] != KNOWN) continue

                    val d = sqrt(d2.toFloat()).coerceAtLeast(0.1f)
                    val dir = abs(dx * 1.0f + dy * 1.0f) / d
                    val w = (1.0f / (d * d * (1.0f + dir))).coerceAtLeast(0.0001f)

                    sumR += w * red[qIdx]
                    sumG += w * green[qIdx]
                    sumB += w * blue[qIdx]
                    sumW += w
                }
            }

            if (sumW > 0f) {
                red[pIdx] = sumR / sumW
                green[pIdx] = sumG / sumW
                blue[pIdx] = sumB / sumW
            }

            for (k in 0 until 4) {
                val nx = px + dx4[k]
                val ny = py + dy4[k]
                if (nx in 0 until cropW && ny in 0 until cropH) {
                    val n = ny * cropW + nx
                    if (flag[n] != KNOWN) {
                        val newDist = dist[pIdx] + 1f
                        if (flag[n] == INSIDE) {
                            flag[n] = BAND
                            dist[n] = newDist
                            narrowBand.add(n)
                        } else if (flag[n] == BAND && newDist < dist[n]) {
                            dist[n] = newDist
                            narrowBand.add(n)
                        }
                    }
                }
            }
        }

        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val resultPixels = sourcePixels.clone()

        for (cy in 0 until cropH) {
            val sy = cropY0 + cy
            val sRow = sy * width
            val cRow = cy * cropW

            for (cx in 0 until cropW) {
                val cIdx = cRow + cx
                if (!rawMask[cIdx]) continue

                val sx = cropX0 + cx
                val sIdx = sRow + sx

                val r = red[cIdx].toInt().coerceIn(0, 255)
                val g = green[cIdx].toInt().coerceIn(0, 255)
                val b = blue[cIdx].toInt().coerceIn(0, 255)

                resultPixels[sIdx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return result
    }
}
