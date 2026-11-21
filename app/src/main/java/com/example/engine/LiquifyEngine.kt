package com.example.engine

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.CancellationException
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class LiquifyTool { FORWARD_WARP, RECONSTRUCT, SMOOTH, TWIRL_CW, TWIRL_CCW, PUCKER, BLOAT, PUSH_LEFT, FREEZE, THAW }

/** Brush settings, as in Photoshop's Liquify (size in working pixels, the rest 0..100). */
data class LiquifyBrush(
    val size: Float = 120f,
    val pressure: Float = 50f,
    val density: Float = 50f,
    val rate: Float = 60f
)

/**
 * Liquify: a backward displacement field (each output pixel samples the source at
 * p + d(p)) edited by brush tools, like Photoshop's Liquify filter.
 *
 * The field lives at a reduced working resolution so editing is interactive; [applyFull] samples
 * it again (bilinearly, vectors scaled) for the full-resolution source, row by row, so the result
 * keeps every pixel of the original and needs no full-size field in memory. Colour is sampled
 * premultiplied, so transparent edges do not get dark fringes.
 */
class LiquifyEngine(private val source: Bitmap) {
    val width = source.width
    val height = source.height
    private val n = width * height
    private val src = IntArray(n).also { source.getPixels(it, 0, width, 0, 0, width, height) }
    private var dx = FloatArray(n)
    private var dy = FloatArray(n)
    /** Freeze mask 0..1 (1 = protected). */
    private val frozen = FloatArray(n)
    /** Warped working image (ARGB, unpremultiplied). */
    val output = src.copyOf()

    private val undo = ArrayDeque<Pair<FloatArray, FloatArray>>()
    private val maxUndo = 6

    /**
     * Face-Aware Liquify field, applied after the brush warp: output pixel p samples the source at
     * q + f(q) with q = p + d(p). Null when no face slider is used.
     */
    private var fx: FloatArray? = null
    private var fy: FloatArray? = null
    /** Colour change applied at the source position (x, y in working pixels), e.g. brighter eyes. */
    var postProcess: ((Float, Float, Int) -> Int)? = null
    /** Skin retouch done on the source before warping (pixels, w, h, scale from working size). */
    private var skinFix: ((IntArray, Int, Int, Float) -> Unit)? = null
    /** The source with [skinFix] applied (working size), or null. */
    private var fixedSrc: IntArray? = null

    val canUndo: Boolean get() = undo.isNotEmpty()
    val hasChanges: Boolean
        get() = dx.any { it != 0f } || dy.any { it != 0f } ||
            (fx?.any { it != 0f } == true) || (fy?.any { it != 0f } == true) || postProcess != null || fixedSrc != null

    /** Sets (or clears, with nulls) the face field; arrays are width x height. Re-renders everything. */
    fun setFaceField(faceDx: FloatArray?, faceDy: FloatArray?, post: ((Float, Float, Int) -> Int)?): Rect {
        fx = faceDx?.takeIf { it.size == n }
        fy = faceDy?.takeIf { it.size == n }
        postProcess = post
        return render(Rect(0, 0, width, height))
    }

    /** Sets (or clears) the skin retouch; it is slow, so only call it when its settings change. */
    fun setSkinFix(fix: ((IntArray, Int, Int, Float) -> Unit)?): Rect {
        skinFix = fix
        fixedSrc = fix?.let { f -> src.copyOf().also { f(it, width, height, 1f) } }
        return render(Rect(0, 0, width, height))
    }

    /** Source position (working pixels) shown at output (x, y): brush warp, then face warp. */
    private fun sourcePos(x: Float, y: Float, bx: Float, by: Float, out: FloatArray, t: FloatArray) {
        var qx = x + bx; var qy = y + by
        val ffx = fx; val ffy = fy
        if (ffx != null && ffy != null) {
            fieldAt(qx, qy, t, ffx, ffy)
            qx += t[0]; qy += t[1]
        }
        out[0] = qx; out[1] = qy
    }

    /** Call when a stroke starts: the field before it becomes an undo step. */
    fun beginStroke() {
        undo.addLast(dx.copyOf() to dy.copyOf())
        while (undo.size > maxUndo) undo.removeFirst()
    }

    /** Restores the field before the last stroke; returns the area to redraw (all). */
    fun undoStroke(): Rect? {
        val (ux, uy) = undo.removeLastOrNull() ?: return null
        dx = ux; dy = uy
        return render(Rect(0, 0, width, height))
    }

    fun restoreAll(): Rect {
        beginStroke()
        dx.fill(0f); dy.fill(0f)
        return render(Rect(0, 0, width, height))
    }

    fun frozenAt(i: Int) = frozen[i]

    /** Brush weight at distance [d] for radius [r]: 1 in the core, soft edge set by density. */
    private fun falloff(d: Float, r: Float, density: Float): Float {
        if (d >= r) return 0f
        val core = r * (1f - density / 100f).coerceIn(0f, 0.95f)
        if (d <= core) return 1f
        val t = ((d - core) / (r - core)).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    /** Bilinear sample of the current field at (x, y) (clamped to the image). */
    private fun fieldAt(x: Float, y: Float, out: FloatArray, fx: FloatArray = dx, fy: FloatArray = dy) {
        val cx = x.coerceIn(0f, (width - 1).toFloat()); val cy = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = cx.toInt(); val y0 = cy.toInt()
        val x1 = min(x0 + 1, width - 1); val y1 = min(y0 + 1, height - 1)
        val ax = cx - x0; val ay = cy - y0
        val i00 = y0 * width + x0; val i10 = y0 * width + x1; val i01 = y1 * width + x0; val i11 = y1 * width + x1
        out[0] = (fx[i00] * (1 - ax) + fx[i10] * ax) * (1 - ay) + (fx[i01] * (1 - ax) + fx[i11] * ax) * ay
        out[1] = (fy[i00] * (1 - ax) + fy[i10] * ax) * (1 - ay) + (fy[i01] * (1 - ax) + fy[i11] * ax) * ay
    }

    /**
     * Applies [tool] at (x, y). For Forward Warp and Push Left, (vx, vy) is the finger movement since
     * the last call; the other tools act in place (call repeatedly while the finger is held).
     * Returns the area whose pixels changed.
     */
    fun dab(tool: LiquifyTool, x: Float, y: Float, vx: Float, vy: Float, brush: LiquifyBrush): Rect? {
        val r = (brush.size / 2f).coerceAtLeast(2f)
        val left = max(0, floor(x - r).toInt()); val top = max(0, floor(y - r).toInt())
        val right = min(width - 1, (x + r).toInt() + 1); val bottom = min(height - 1, (y + r).toInt() + 1)
        if (left > right || top > bottom) return null
        val pressure = (brush.pressure / 100f).coerceIn(0.01f, 1f)
        val rate = (brush.rate / 100f).coerceIn(0.01f, 1f)

        if (tool == LiquifyTool.FREEZE || tool == LiquifyTool.THAW) {
            for (py in top..bottom) for (px in left..right) {
                val f = falloff(dist(px, py, x, y), r, brush.density) * pressure
                if (f <= 0f) continue
                val i = py * width + px
                frozen[i] = if (tool == LiquifyTool.FREEZE) max(frozen[i], f) else frozen[i] * (1f - f)
            }
            return Rect(left, top, right + 1, bottom + 1)
        }

        // New field values are computed from the old field (copies of the brush area), so
        // overlapping samples inside one dab never read half-updated values.
        val bw = right - left + 1; val bh = bottom - top + 1
        val ndx = FloatArray(bw * bh); val ndy = FloatArray(bw * bh)
        val tmp = FloatArray(2)
        val oldDx = dx; val oldDy = dy
        val twirlAngle = 0.12f * rate * pressure
        for (py in top..bottom) {
            for (px in left..right) {
                val j = (py - top) * bw + (px - left)
                val i = py * width + px
                val f0 = falloff(dist(px, py, x, y), r, brush.density)
                val f = f0 * (1f - frozen[i])
                if (f <= 0f) { ndx[j] = oldDx[i]; ndy[j] = oldDy[i]; continue }
                // q = where this output pixel now takes its content from (in output space).
                var qx = px.toFloat(); var qy = py.toFloat()
                val ox = px - x; val oy = py - y
                when (tool) {
                    LiquifyTool.FORWARD_WARP -> { qx -= vx * f * pressure * 1.6f; qy -= vy * f * pressure * 1.6f }
                    LiquifyTool.PUSH_LEFT -> {
                        // Pixels move to the left of the stroke direction.
                        qx -= vy * f * pressure; qy += vx * f * pressure
                    }
                    LiquifyTool.TWIRL_CW, LiquifyTool.TWIRL_CCW -> {
                        val a = (if (tool == LiquifyTool.TWIRL_CW) -twirlAngle else twirlAngle) * f
                        val c = cos(a); val s = sin(a)
                        qx = x + ox * c - oy * s; qy = y + ox * s + oy * c
                    }
                    LiquifyTool.PUCKER -> { val k = 1f + 0.08f * rate * pressure * f; qx = x + ox * k; qy = y + oy * k }
                    LiquifyTool.BLOAT -> { val k = 1f - 0.08f * rate * pressure * f; qx = x + ox * k; qy = y + oy * k }
                    LiquifyTool.RECONSTRUCT -> {
                        val k = 1f - (0.15f * rate * pressure * f)
                        ndx[j] = oldDx[i] * k; ndy[j] = oldDy[i] * k
                        continue
                    }
                    LiquifyTool.SMOOTH -> {
                        // Average of the neighbourhood: removes kinks, keeps the overall warp.
                        var sx = 0f; var sy = 0f; var cnt = 0
                        val rad = 3
                        for (yy in max(0, py - rad)..min(height - 1, py + rad)) for (xx in max(0, px - rad)..min(width - 1, px + rad)) {
                            sx += oldDx[yy * width + xx]; sy += oldDy[yy * width + xx]; cnt++
                        }
                        val k = 0.5f * rate * pressure * f
                        ndx[j] = oldDx[i] + (sx / cnt - oldDx[i]) * k; ndy[j] = oldDy[i] + (sy / cnt - oldDy[i]) * k
                        continue
                    }
                    else -> {}
                }
                // Compose with the existing warp: content at q came from q + d(q).
                fieldAt(qx, qy, tmp, oldDx, oldDy)
                ndx[j] = qx + tmp[0] - px
                ndy[j] = qy + tmp[1] - py
            }
        }
        for (py in top..bottom) {
            val base = (py - top) * bw
            System.arraycopy(ndx, base, dx, py * width + left, bw)
            System.arraycopy(ndy, base, dy, py * width + left, bw)
        }
        return render(Rect(left, top, right + 1, bottom + 1))
    }

    private fun dist(px: Int, py: Int, x: Float, y: Float): Float {
        val ex = px - x; val ey = py - y
        return sqrt(ex * ex + ey * ey)
    }

    /** Re-renders [area] of [output] from the source and the field; returns [area]. */
    fun render(area: Rect): Rect {
        val q = FloatArray(2)
        val t = FloatArray(2)
        val post = postProcess
        val plain = fx == null && post == null
        val src = fixedSrc ?: this.src
        for (py in area.top until area.bottom) {
            for (px in area.left until area.right) {
                val i = py * width + px
                if (plain) {
                    output[i] = sample(src, width, height, px + dx[i], py + dy[i])
                } else {
                    sourcePos(px.toFloat(), py.toFloat(), dx[i], dy[i], q, t)
                    val c = sample(src, width, height, q[0], q[1])
                    output[i] = if (post != null && (c ushr 24) != 0) post(q[0], q[1], c) else c
                }
            }
        }
        return area
    }

    /**
     * Warps the full-resolution [full] (same content as the working source, any size) with the
     * field, scaled to it. Row by row; cancellable through [isCancelled]; [onProgress] 0..1.
     */
    fun applyFull(full: Bitmap, isCancelled: () -> Boolean = { false }, onProgress: (Float) -> Unit = {}): Bitmap {
        val fw = full.width; val fh = full.height
        val sx = width.toFloat() / fw; val sy = height.toFloat() / fh
        // Source rows are read on demand through a small band cache.
        val fullPx = IntArray(fw * fh)
        full.getPixels(fullPx, 0, fw, 0, 0, fw, fh)
        skinFix?.invoke(fullPx, fw, fh, fw.toFloat() / width)
        val out = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        val row = IntArray(fw)
        val tmp = FloatArray(2)
        val q = FloatArray(2)
        val t2 = FloatArray(2)
        val post = postProcess
        for (y in 0 until fh) {
            if ((y and 31) == 0) {
                if (isCancelled()) { out.recycle(); throw CancellationException("Liquify cancelled") }
                onProgress(y.toFloat() / fh)
            }
            val wy = (y + 0.5f) * sy - 0.5f
            for (x in 0 until fw) {
                val wx = (x + 0.5f) * sx - 0.5f
                fieldAt(wx, wy, tmp)
                // Working-space source position (brush + face warp), then back to full resolution.
                sourcePos(wx, wy, tmp[0], tmp[1], q, t2)
                val c = sample(fullPx, fw, fh, (q[0] + 0.5f) / sx - 0.5f, (q[1] + 0.5f) / sy - 0.5f)
                row[x] = if (post != null && (c ushr 24) != 0) post(q[0], q[1], c) else c
            }
            out.setPixels(row, 0, fw, 0, y, fw, 1)
        }
        onProgress(1f)
        return out
    }

    /** Field vector at working pixel (x, y), for drawing the mesh. */
    fun displacement(x: Float, y: Float, out: FloatArray) = fieldAt(x, y, out)

    companion object {
        /** Bilinear, premultiplied sample of [px] (w x h, ARGB) at (x, y); outside = edge pixel. */
        fun sample(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
            val cx = x.coerceIn(0f, (w - 1).toFloat()); val cy = y.coerceIn(0f, (h - 1).toFloat())
            val x0 = cx.toInt(); val y0 = cy.toInt()
            val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
            val ax = cx - x0; val ay = cy - y0
            if (ax < 1e-4f && ay < 1e-4f) return px[y0 * w + x0]
            val c00 = px[y0 * w + x0]; val c10 = px[y0 * w + x1]; val c01 = px[y1 * w + x0]; val c11 = px[y1 * w + x1]
            val w00 = (1 - ax) * (1 - ay); val w10 = ax * (1 - ay); val w01 = (1 - ax) * ay; val w11 = ax * ay
            fun a(c: Int) = (c ushr 24).toFloat()
            val aa = a(c00) * w00 + a(c10) * w10 + a(c01) * w01 + a(c11) * w11
            if (aa < 0.5f) return 0
            fun ch(c: Int, sh: Int) = ((c shr sh) and 255) * a(c)
            val r = (ch(c00, 16) * w00 + ch(c10, 16) * w10 + ch(c01, 16) * w01 + ch(c11, 16) * w11) / aa
            val g = (ch(c00, 8) * w00 + ch(c10, 8) * w10 + ch(c01, 8) * w01 + ch(c11, 8) * w11) / aa
            val b = (ch(c00, 0) * w00 + ch(c10, 0) * w10 + ch(c01, 0) * w01 + ch(c11, 0) * w11) / aa
            return ((aa + 0.5f).toInt().coerceIn(0, 255) shl 24) or
                ((r + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (b + 0.5f).toInt().coerceIn(0, 255)
        }
    }
}
