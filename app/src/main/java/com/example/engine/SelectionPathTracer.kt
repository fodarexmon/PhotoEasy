package com.example.engine

import android.graphics.Bitmap
import android.graphics.Path
import java.nio.ByteBuffer

/**
 * High-performance vector boundary tracer for ALPHA_8 selection masks.
 * Extracts connected, closed contour loops for rendering Photoshop-style
 * "Marching Ants" (alternating black and white dashed strokes) along selection perimeters.
 */
object SelectionPathTracer {

    fun traceSelectionContour(mask: Bitmap): Path {
        val path = Path()
        if (mask.isRecycled || mask.width <= 0 || mask.height <= 0) return path

        val w = mask.width
        val h = mask.height

        // Adaptive stride keeps boundary extraction under 4ms even on multi-megapixel canvases
        val stride = if (maxOf(w, h) > 1200) (maxOf(w, h) / 800).coerceAtLeast(1) else 1
        val sw = (w + stride - 1) / stride
        val sh = (h + stride - 1) / stride

        val rowBytes = mask.rowBytes
        val byteCount = maxOf(mask.byteCount, rowBytes * h)
        val alphaBytes = ByteArray(byteCount)
        try {
            mask.copyPixelsToBuffer(ByteBuffer.wrap(alphaBytes))
        } catch (e: Throwable) {
            val pixels = IntArray(w)
            for (y in 0 until h) {
                mask.getPixels(pixels, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    alphaBytes[y * rowBytes + x] = (pixels[x] ushr 24).toByte()
                }
            }
        }

        val isSel = BooleanArray(sw * sh)
        var hasAnySelected = false
        var allSelected = true

        for (sy in 0 until sh) {
            val y = (sy * stride).coerceAtMost(h - 1)
            val row = y * rowBytes
            val baseIdx = sy * sw
            for (sx in 0 until sw) {
                val x = (sx * stride).coerceAtMost(w - 1)
                val alpha = alphaBytes[row + x].toInt() and 0xFF
                val sel = alpha >= 128
                isSel[baseIdx + sx] = sel
                if (sel) hasAnySelected = true else allSelected = false
            }
        }

        if (!hasAnySelected) return path
        if (allSelected) {
            path.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
            return path
        }

        fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
        fun unpackX(k: Long): Float = (k ushr 32).toInt() * stride.toFloat()
        fun unpackY(k: Long): Float = k.toInt() * stride.toFloat()

        val outgoing = HashMap<Long, ArrayList<Long>>()

        fun addEdge(x1: Int, y1: Int, x2: Int, y2: Int) {
            if (x1 == x2 && y1 == y2) return
            val k1 = pack(x1, y1)
            val k2 = pack(x2, y2)
            var list = outgoing[k1]
            if (list == null) {
                list = ArrayList(2)
                outgoing[k1] = list
            }
            list.add(k2)
        }

        // 1. Horizontal boundary edges (collinear spans merged per row)
        for (sy in 0 until sh) {
            val rowBase = sy * sw
            val prevRowBase = (sy - 1) * sw
            val nextRowBase = (sy + 1) * sw

            var topSpanStart = -1
            var botSpanStart = -1

            for (sx in 0 until sw) {
                val sel = isSel[rowBase + sx]

                val isTop = sel && (sy == 0 || !isSel[prevRowBase + sx])
                if (isTop) {
                    if (topSpanStart == -1) topSpanStart = sx
                } else {
                    if (topSpanStart != -1) {
                        addEdge(topSpanStart, sy, sx, sy)
                        topSpanStart = -1
                    }
                }

                val isBot = sel && (sy == sh - 1 || !isSel[nextRowBase + sx])
                if (isBot) {
                    if (botSpanStart == -1) botSpanStart = sx
                } else {
                    if (botSpanStart != -1) {
                        addEdge(sx, sy + 1, botSpanStart, sy + 1)
                        botSpanStart = -1
                    }
                }
            }
            if (topSpanStart != -1) {
                addEdge(topSpanStart, sy, sw, sy)
            }
            if (botSpanStart != -1) {
                addEdge(sw, sy + 1, botSpanStart, sy + 1)
            }
        }

        // 2. Vertical boundary edges (collinear spans merged per column)
        for (sx in 0 until sw) {
            var leftSpanStart = -1
            var rightSpanStart = -1

            for (sy in 0 until sh) {
                val sel = isSel[sy * sw + sx]

                val isLeft = sel && (sx == 0 || !isSel[sy * sw + sx - 1])
                if (isLeft) {
                    if (leftSpanStart == -1) leftSpanStart = sy
                } else {
                    if (leftSpanStart != -1) {
                        addEdge(sx, sy, sx, leftSpanStart)
                        leftSpanStart = -1
                    }
                }

                val isRight = sel && (sx == sw - 1 || !isSel[sy * sw + sx + 1])
                if (isRight) {
                    if (rightSpanStart == -1) rightSpanStart = sy
                } else {
                    if (rightSpanStart != -1) {
                        addEdge(sx + 1, rightSpanStart, sx + 1, sy)
                        rightSpanStart = -1
                    }
                }
            }
            if (leftSpanStart != -1) {
                addEdge(sx, sh, sx, leftSpanStart)
            }
            if (rightSpanStart != -1) {
                addEdge(sx + 1, rightSpanStart, sx + 1, sh)
            }
        }

        // 3. Assemble directed edges into closed loops
        val maxSteps = sw * sh * 4
        while (outgoing.isNotEmpty()) {
            val entry = outgoing.entries.first()
            val startKey = entry.key
            val nextList = entry.value
            val firstNext = nextList.removeAt(nextList.size - 1)
            if (nextList.isEmpty()) outgoing.remove(startKey)

            path.moveTo(
                unpackX(startKey).coerceAtMost(w.toFloat()),
                unpackY(startKey).coerceAtMost(h.toFloat())
            )
            var currKey = firstNext
            var steps = 0

            while (currKey != startKey && steps < maxSteps) {
                val cx = unpackX(currKey).coerceAtMost(w.toFloat())
                val cy = unpackY(currKey).coerceAtMost(h.toFloat())
                path.lineTo(cx, cy)

                val list = outgoing[currKey]
                if (list == null || list.isEmpty()) {
                    outgoing.remove(currKey)
                    break
                }
                val nxt = list.removeAt(list.size - 1)
                if (list.isEmpty()) outgoing.remove(currKey)
                currKey = nxt
                steps++
            }
            path.close()
        }

        return path
    }
}
