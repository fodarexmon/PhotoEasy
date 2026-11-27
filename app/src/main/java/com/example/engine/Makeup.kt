package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import kotlinx.coroutines.CancellationException
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Makeup for one face. Amounts are 0..100 (0 = off); colours are 0xRRGGBB.
 */
data class MakeupSettings(
    val lipAmount: Float = 0f, val lipColor: Int = 0xB0394A, val lipGloss: Float = 0f,
    val blushAmount: Float = 0f, val blushColor: Int = 0xE8848C,
    val contour: Float = 0f, val highlight: Float = 0f,
    val shadowAmount: Float = 0f, val shadowColor: Int = 0x8A5A4A, val shadowHeight: Float = 50f,
    val linerAmount: Float = 0f, val linerColor: Int = 0x1A1414, val linerThickness: Float = 35f, val linerWing: Float = 0f,
    val mascara: Float = 0f, val lashLength: Float = 50f,
    val browAmount: Float = 0f, val browColor: Int = 0x3B2A22,
    val eyeColorAmount: Float = 0f, val eyeColor: Int = 0x3A7DB8,
    val teeth: Float = 0f
) {
    val isNeutral: Boolean
        get() = lipAmount == 0f && lipGloss == 0f && blushAmount == 0f && contour == 0f && highlight == 0f &&
            shadowAmount == 0f && linerAmount == 0f && mascara == 0f && browAmount == 0f && eyeColorAmount == 0f && teeth == 0f
}

object Makeup {

    /** Ready-made looks (the colours and amounts the sliders start from). */
    enum class Look { NATURAL, GLAM, SMOKEY, NUDE, BOLD_RED }

    fun look(l: Look): MakeupSettings = when (l) {
        Look.NATURAL -> MakeupSettings(
            lipAmount = 35f, lipColor = 0xC4686E, lipGloss = 20f, blushAmount = 30f, blushColor = 0xEE9A92,
            contour = 20f, highlight = 25f, mascara = 40f, lashLength = 40f, browAmount = 25f, browColor = 0x4A3528, teeth = 25f
        )
        Look.GLAM -> MakeupSettings(
            lipAmount = 65f, lipColor = 0xB24A5C, lipGloss = 45f, blushAmount = 45f, blushColor = 0xE57E86,
            contour = 45f, highlight = 50f, shadowAmount = 55f, shadowColor = 0x9A6248, shadowHeight = 55f,
            linerAmount = 80f, linerThickness = 40f, linerWing = 55f, mascara = 80f, lashLength = 70f,
            browAmount = 45f, browColor = 0x3B2A22, teeth = 35f
        )
        Look.SMOKEY -> MakeupSettings(
            lipAmount = 40f, lipColor = 0xA0646A, lipGloss = 15f, blushAmount = 20f, blushColor = 0xC98585,
            contour = 50f, highlight = 30f, shadowAmount = 80f, shadowColor = 0x2E2A2E, shadowHeight = 65f,
            linerAmount = 90f, linerThickness = 55f, linerWing = 30f, mascara = 90f, lashLength = 75f,
            browAmount = 40f, browColor = 0x2E221C
        )
        Look.NUDE -> MakeupSettings(
            lipAmount = 50f, lipColor = 0xC08A7E, lipGloss = 30f, blushAmount = 25f, blushColor = 0xD9A08C,
            contour = 30f, highlight = 35f, shadowAmount = 40f, shadowColor = 0xB08870, shadowHeight = 45f,
            linerAmount = 40f, linerThickness = 25f, mascara = 55f, lashLength = 50f, browAmount = 30f, browColor = 0x5A4232
        )
        Look.BOLD_RED -> MakeupSettings(
            lipAmount = 85f, lipColor = 0xB0101E, lipGloss = 25f, blushAmount = 25f, blushColor = 0xE08080,
            contour = 35f, highlight = 40f, linerAmount = 85f, linerThickness = 45f, linerWing = 70f,
            mascara = 75f, lashLength = 65f, browAmount = 40f, browColor = 0x2E221C, teeth = 40f
        )
    }

    private fun check(isCancelled: (() -> Boolean)?) {
        if (isCancelled?.invoke() == true) throw CancellationException("Makeup cancelled")
    }

    /** [src] with the makeup of every face drawn ([settings] by face index; missing = none). */
    fun render(src: Bitmap, faces: List<MakeupFace>, settings: List<MakeupSettings>, isCancelled: (() -> Boolean)? = null): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        faces.forEachIndexed { i, f ->
            val s = settings.getOrNull(i) ?: return@forEachIndexed
            if (!s.isNeutral) FaceRender(out, f, s, isCancelled).run()
        }
        return out
    }

    /**
     * The makeup alone, as a layer for above [src]: opaque where the makeup changed a pixel,
     * transparent elsewhere (the blending is already in the colours, so it matches exactly).
     */
    fun layerFrom(src: Bitmap, made: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val a = IntArray(w); val b = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(a, 0, w, 0, y, w, 1)
            made.getPixels(b, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                b[x] = if ((a[x] and 0xFFFFFF) == (b[x] and 0xFFFFFF) || (a[x] ushr 24) == 0) 0 else (b[x] or (0xFF shl 24))
            }
            out.setPixels(b, 0, w, 0, y, w, 1)
        }
        return out
    }

    // --- Colour ---------------------------------------------------------------------------------

    private val toLinear = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun f(t: Float): Float = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f

    /** sRGB colour to Lab (L 0..100). */
    internal fun toLab(c: Int, out: FloatArray) {
        val r = toLinear[(c shr 16) and 0xFF]; val g = toLinear[(c shr 8) and 0xFF]; val b = toLinear[c and 0xFF]
        val x = (0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val z = (0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f
        val fx = f(x); val fy = f(y); val fz = f(z)
        out[0] = 116f * fy - 16f
        out[1] = 500f * (fx - fy)
        out[2] = 200f * (fy - fz)
    }

    private fun gamma(v: Float): Int {
        val c = v.coerceIn(0f, 1f)
        val s = if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
        return (s * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /** Lab to an opaque sRGB colour (0xRRGGBB). */
    internal fun fromLab(l: Float, a: Float, b: Float): Int {
        val fy = (l + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f
        fun inv(t: Float) = if (t * t * t > 0.008856f) t * t * t else (t - 16f / 116f) / 7.787f
        val x = inv(fx) * 0.95047f; val y = inv(fy); val z = inv(fz) * 1.08883f
        val r = 3.2406f * x - 1.5372f * y - 0.4986f * z
        val g = -0.9689f * x + 1.8758f * y + 0.0415f * z
        val bl = 0.0557f * x - 0.2040f * y + 1.0570f * z
        return (gamma(r) shl 16) or (gamma(g) shl 8) or gamma(bl)
    }

    internal fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // --- One face -----------------------------------------------------------------------------

    private class FaceRender(val img: Bitmap, val f: MakeupFace, val s: MakeupSettings, val isCancelled: (() -> Boolean)?) {
        val u = f.width
        val eyeAxis: PointF = run {
            val a = f.eyes.getOrNull(0)?.center; val b = f.eyes.getOrNull(1)?.center
            if (a == null || b == null) PointF(1f, 0f) else {
                val d = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1f)
                PointF((b.x - a.x) / d, (b.y - a.y) / d)
            }
        }
        /** "Down" the face (perpendicular to the eye line). */
        val down = PointF(-eyeAxis.y, eyeAxis.x).let { if (it.y < 0f) PointF(-it.x, -it.y) else it }

        fun run() {
            if (s.contour > 0f || s.highlight > 0f) sculpt()
            if (s.blushAmount > 0f) blush()
            f.eyes.forEach { e ->
                if (s.shadowAmount > 0f) eyeshadow(e)
                if (s.linerAmount > 0f) eyeliner(e)
                if (s.mascara > 0f) mascara(e)
                if (s.browAmount > 0f && e.brow.size >= 3) brow(e)
                if (s.eyeColorAmount > 0f) eyeColor(e)
            }
            if (s.lipAmount > 0f || s.lipGloss > 0f) lips()
            if (s.teeth > 0f) teeth()
        }

        // ---- Masks ----

        /** A region of the image with a soft mask (0..1) drawn by [draw]. */
        inner class Area(bounds: RectF, pad: Float) {
            val left = floor(bounds.left - pad).toInt().coerceIn(0, img.width - 1)
            val top = floor(bounds.top - pad).toInt().coerceIn(0, img.height - 1)
            val right = ceil(bounds.right + pad).toInt().coerceIn(left + 1, img.width)
            val bottom = ceil(bounds.bottom + pad).toInt().coerceIn(top + 1, img.height)
            val w = right - left
            val h = bottom - top

            fun mask(blur: Float, draw: (Canvas, Paint) -> Unit): FloatArray {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
                val c = Canvas(bmp)
                c.translate(-left.toFloat(), -top.toFloat())
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.FILL }
                draw(c, p)
                val bytes = ByteArray(bmp.rowBytes * h)
                bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(bytes))
                val m = FloatArray(w * h)
                for (y in 0 until h) for (x in 0 until w) m[y * w + x] = (bytes[y * bmp.rowBytes + x].toInt() and 0xFF) / 255f
                bmp.recycle()
                if (blur >= 0.6f) {
                    val o = FloatArray(w * h)
                    ArtFilters.blur(m, o, w, h, blur)
                    return o
                }
                return m
            }

            /** Runs [op] on every pixel with a mask weight > 0, in Lab. */
            fun apply(mask: FloatArray, op: (w: Float, lab: FloatArray) -> Unit) {
                check(isCancelled)
                val px = IntArray(w * h)
                img.getPixels(px, 0, w, left, top, w, h)
                val lab = FloatArray(3)
                for (i in px.indices) {
                    val m = mask[i]
                    if (m < 0.003f) continue
                    val c = px[i]
                    if ((c ushr 24) == 0) continue
                    toLab(c, lab)
                    op(m, lab)
                    px[i] = (c and 0xFF000000.toInt()) or fromLab(lab[0], lab[1], lab[2])
                }
                img.setPixels(px, 0, w, left, top, w, h)
            }

            /** Mask-weighted mean Lab of the area (where the mask is over [min]). */
            fun meanLab(mask: FloatArray, min: Float = 0.3f): FloatArray {
                val px = IntArray(w * h)
                img.getPixels(px, 0, w, left, top, w, h)
                val lab = FloatArray(3); val sum = FloatArray(3); var n = 0f
                for (i in px.indices) {
                    val m = mask[i]
                    if (m < min) continue
                    toLab(px[i], lab)
                    sum[0] += lab[0] * m; sum[1] += lab[1] * m; sum[2] += lab[2] * m; n += m
                }
                if (n <= 0f) return floatArrayOf(50f, 0f, 0f)
                return floatArrayOf(sum[0] / n, sum[1] / n, sum[2] / n)
            }

            fun maxL(mask: FloatArray): Float {
                val px = IntArray(w * h)
                img.getPixels(px, 0, w, left, top, w, h)
                val lab = FloatArray(3); var mx = 0f
                for (i in px.indices) if (mask[i] > 0.5f) { toLab(px[i], lab); mx = max(mx, lab[0]) }
                return mx
            }
        }

        fun path(pts: List<PointF>, close: Boolean = true): Path = Path().apply {
            if (pts.isEmpty()) return@apply
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            if (close) close()
        }

        fun boundsOf(vararg lists: List<PointF>): RectF {
            val all = lists.flatMap { it }
            return RectF(all.minOf { it.x }, all.minOf { it.y }, all.maxOf { it.x }, all.maxOf { it.y })
        }

        fun lerp(a: PointF, b: PointF, t: Float) = PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        fun add(a: PointF, v: PointF, k: Float) = PointF(a.x + v.x * k, a.y + v.y * k)

        fun lab(color: Int): FloatArray = FloatArray(3).also { toLab(color or 0xFF000000.toInt(), it) }

        /** Weight that keeps makeup off the eyes and lips. */
        fun keepOff(area: Area): FloatArray {
            val m = area.mask(u * 0.01f) { c, p ->
                f.eyes.forEach { e -> c.drawPath(path(e.contour), p) }
                c.drawPath(path(f.lipsOuter), p)
            }
            for (i in m.indices) m[i] = 1f - m[i]
            return m
        }

        fun faceClip(area: Area, blur: Float): FloatArray = area.mask(blur) { c, p -> c.drawPath(path(f.oval), p) }

        // ---- Elements ----

        fun lips() {
            val area = Area(boundsOf(f.lipsOuter), u * 0.03f)
            val m = area.mask(u * 0.004f) { c, p ->
                val pth = path(f.lipsOuter)
                pth.addPath(path(f.lipsInner))
                pth.fillType = Path.FillType.EVEN_ODD
                c.drawPath(pth, p)
            }
            val mean = area.meanLab(m)
            val t = lab(s.lipColor)
            val k = s.lipAmount / 100f
            val gloss = s.lipGloss / 100f
            val top = area.maxL(m)
            val g0 = mean[0] + (top - mean[0]) * 0.35f
            area.apply(m) { w, l ->
                val origL = l[0]
                if (k > 0f) {
                    val a = w * k
                    l[0] += ((origL + (t[0] - mean[0]) * 0.85f) - origL) * a
                    l[1] += ((t[1] + (l[1] - mean[1]) * 0.4f) - l[1]) * a
                    l[2] += ((t[2] + (l[2] - mean[2]) * 0.4f) - l[2]) * a
                }
                if (gloss > 0f) {
                    // Wet shine on the lips' own highlights.
                    val spec = smooth(g0, max(top, g0 + 1f), origL) * w * gloss
                    l[0] += (100f - l[0]) * spec * 0.55f
                    l[1] *= 1f - spec * 0.45f
                    l[2] *= 1f - spec * 0.45f
                }
            }
        }

        fun teeth() {
            if (f.lipsInner.size < 3) return
            val area = Area(boundsOf(f.lipsInner), u * 0.01f)
            val m = area.mask(u * 0.002f) { c, p -> c.drawPath(path(f.lipsInner), p) }
            val k = s.teeth / 100f
            area.apply(m) { w, l ->
                // Bright, not-red pixels only (teeth, not gums, tongue or lips).
                val tw = w * k * smooth(35f, 62f, l[0]) * (1f - smooth(16f, 32f, l[1]))
                if (tw <= 0f) return@apply
                l[2] -= max(l[2], 0f) * 0.75f * tw
                l[1] *= 1f - 0.3f * tw
                l[0] += (100f - l[0]) * 0.12f * tw
            }
        }

        fun blush() {
            val k = s.blushAmount / 100f
            val t = lab(s.blushColor)
            f.cheeks.forEachIndexed { i, cheek ->
                val bone = f.cheekbones.getOrNull(i) ?: cheek
                val ear = f.earSides.getOrNull(i) ?: bone
                val ctr = lerp(cheek, bone, 0.3f)
                val ang = atan2(ear.y - cheek.y, ear.x - cheek.x) * 180f / PI.toFloat()
                val rx = u * 0.14f; val ry = u * 0.09f
                val area = Area(RectF(ctr.x - rx, ctr.y - rx, ctr.x + rx, ctr.y + rx), u * 0.1f)
                val m = area.mask(u * 0.05f) { c, p ->
                    c.save(); c.rotate(ang, ctr.x, ctr.y)
                    c.drawOval(RectF(ctr.x - rx, ctr.y - ry, ctr.x + rx, ctr.y + ry), p)
                    c.restore()
                }
                val clip = faceClip(area, u * 0.02f); val off = keepOff(area)
                for (j in m.indices) m[j] *= clip[j] * off[j]
                area.apply(m) { w, l ->
                    val a = w * k
                    l[1] += (t[1] - l[1]) * a * 0.75f
                    l[2] += (t[2] - l[2]) * a * 0.75f
                    l[0] += (t[0] - l[0]) * a * 0.12f
                }
            }
        }

        fun sculpt() {
            val area = Area(f.box, u * 0.05f)
            val clip = faceClip(area, u * 0.02f)
            val off = keepOff(area)
            val center = PointF(f.box.centerX(), f.box.centerY())
            fun along(p: PointF) = (p.x - center.x) * down.x + (p.y - center.y) * down.y
            if (s.contour > 0f) {
                val broad = area.mask(u * 0.035f) { c, p ->
                    val st = Paint(p).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
                    // Under the cheekbones, from the ear towards the mouth.
                    st.strokeWidth = u * 0.08f
                    f.earSides.forEachIndexed { i, ear ->
                        val mouth = f.mouthCorners.getOrNull(i) ?: return@forEachIndexed
                        val a = add(lerp(ear, mouth, 0.05f), down, u * 0.02f)
                        val b = add(lerp(ear, mouth, 0.5f), down, u * 0.03f)
                        c.drawLine(a.x, a.y, b.x, b.y, st)
                    }
                    // Along the jaw, just inside the face outline.
                    st.strokeWidth = u * 0.06f
                    val jaw = f.oval.filter { along(it) > f.height * 0.12f }.map { lerp(it, center, 0.04f) }
                    if (jaw.size >= 2) {
                        val sorted = jaw.sortedBy { atan2(it.y - center.y, it.x - center.x) }
                        c.drawPath(path(sorted, close = false), st)
                    }
                }
                val nose = area.mask(u * 0.012f) { c, p ->
                    val st = Paint(p).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = u * 0.028f }
                    f.eyes.forEachIndexed { i, e ->
                        val wing = f.noseWings.getOrNull(i) ?: return@forEachIndexed
                        val a = lerp(e.inner, f.noseTop, 0.35f)
                        val b = lerp(wing, f.noseTip, 0.15f)
                        c.drawLine(a.x, a.y, b.x, b.y, st)
                    }
                }
                val m = FloatArray(broad.size) { max(broad[it], nose[it] * 0.8f) * clip[it] * off[it] }
                val k = s.contour / 100f
                area.apply(m) { w, l ->
                    val a = w * k
                    l[0] -= 16f * a
                    l[1] += 1.5f * a
                    l[2] += 4f * a
                }
            }
            if (s.highlight > 0f) {
                val m = area.mask(u * 0.03f) { c, p ->
                    val st = Paint(p).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = u * 0.03f }
                    val tip = lerp(f.noseTop, f.noseTip, 0.85f)
                    c.drawLine(f.noseTop.x, f.noseTop.y, tip.x, tip.y, st)
                    f.cheekbones.forEachIndexed { i, b ->
                        val ear = f.earSides.getOrNull(i) ?: b
                        val ctr = add(lerp(b, ear, 0.25f), down, -u * 0.015f)
                        val ang = atan2(ear.y - b.y, ear.x - b.x) * 180f / PI.toFloat()
                        c.save(); c.rotate(ang, ctr.x, ctr.y)
                        c.drawOval(RectF(ctr.x - u * 0.08f, ctr.y - u * 0.03f, ctr.x + u * 0.08f, ctr.y + u * 0.03f), p)
                        c.restore()
                    }
                    c.drawCircle(f.forehead.x, f.forehead.y, u * 0.07f, p)
                    val chin = lerp(f.chin, centroidOf(f.lipsOuter), 0.3f)
                    c.drawCircle(chin.x, chin.y, u * 0.04f, p)
                }
                for (j in m.indices) m[j] *= clip[j] * off[j]
                val k = s.highlight / 100f
                area.apply(m) { w, l ->
                    val a = w * k
                    l[0] += (100f - l[0]) * 0.28f * a
                    l[2] += 1.5f * a
                }
            }
        }

        fun eyeshadow(e: EyeShape) {
            val browC = if (e.brow.isNotEmpty()) centroidOf(e.brow) else add(e.center, down, -e.width * 0.6f)
            val dist = hypot(browC.x - e.center.x, browC.y - e.center.y).coerceAtLeast(e.width * 0.3f)
            val hk = s.shadowHeight / 100f
            val lid = e.upperLid
            val outDir = PointF((e.outer.x - e.inner.x) / e.width.coerceAtLeast(1f), (e.outer.y - e.inner.y) / e.width.coerceAtLeast(1f))
            fun lifted(scale: Float) = lid.mapIndexed { i, q ->
                val t = i / max(1f, (lid.size - 1).toFloat())
                add(q, down, -dist * (0.25f + 0.45f * hk) * (0.55f + 0.45f * t) * scale)
            }
            val ext = add(add(e.outer, outDir, e.width * 0.14f), down, -dist * 0.18f)
            val outerShape = lid + listOf(ext) + lifted(1f).reversed()
            val innerShape = lid + listOf(lerp(e.outer, ext, 0.5f)) + lifted(0.5f).reversed()
            val area = Area(boundsOf(outerShape), u * 0.04f)
            val m = area.mask(u * 0.018f) { c, p ->
                p.alpha = 140; c.drawPath(path(outerShape), p)
                p.alpha = 115; c.drawPath(path(innerShape), p)
            }
            val eyeM = area.mask(u * 0.003f) { c, p -> c.drawPath(path(e.contour), p) }
            for (i in m.indices) m[i] = m[i].coerceAtMost(1f) * (1f - eyeM[i])
            val t = lab(s.shadowColor)
            val k = s.shadowAmount / 100f
            area.apply(m) { w, l ->
                val a = w * k
                l[0] += ((l[0] * 0.7f + t[0] * 0.3f) - l[0]) * a
                l[1] += (t[1] - l[1]) * a * 0.8f
                l[2] += (t[2] - l[2]) * a * 0.8f
            }
        }

        /** Outward normal of the upper lid at point [i] (away from the eye centre). */
        fun lidNormal(e: EyeShape, pts: List<PointF>, i: Int): PointF {
            val a = pts[max(0, i - 1)]; val b = pts[min(pts.size - 1, i + 1)]
            var nx = -(b.y - a.y); var ny = b.x - a.x
            val d = hypot(nx, ny).coerceAtLeast(1e-3f); nx /= d; ny /= d
            val c = e.center
            if ((pts[i].x - c.x) * nx + (pts[i].y - c.y) * ny < 0f) { nx = -nx; ny = -ny }
            return PointF(nx, ny)
        }

        /** [pts] resampled to [n] points evenly along its length. */
        fun resample(pts: List<PointF>, n: Int): List<PointF> {
            if (pts.size < 2) return pts
            val seg = FloatArray(pts.size); var total = 0f
            for (i in 1 until pts.size) { total += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y); seg[i] = total }
            return (0 until n).map { k ->
                val d = total * k / (n - 1).coerceAtLeast(1)
                var i = 1
                while (i < pts.size - 1 && seg[i] < d) i++
                val span = (seg[i] - seg[i - 1]).coerceAtLeast(1e-3f)
                lerp(pts[i - 1], pts[i], ((d - seg[i - 1]) / span).coerceIn(0f, 1f))
            }
        }

        fun eyeliner(e: EyeShape) {
            val lid = resample(e.upperLid, 24)
            val base = e.width * (0.025f + 0.09f * s.linerThickness / 100f)
            val outer = lid.mapIndexed { i, q ->
                val t = i / (lid.size - 1f)
                if (t < 0.12f) q else add(q, lidNormal(e, lid, i), base * (0.25f + 0.75f * t))
            }
            val outDir = PointF((e.outer.x - e.inner.x) / e.width.coerceAtLeast(1f), (e.outer.y - e.inner.y) / e.width.coerceAtLeast(1f))
            val wingK = s.linerWing / 100f
            val shape = ArrayList<PointF>()
            shape.addAll(lid)
            if (wingK > 0f) {
                val tip = add(add(e.outer, outDir, e.width * 0.32f * wingK), down, -e.width * 0.16f * wingK)
                shape.add(tip)
            }
            shape.addAll(outer.reversed())
            val area = Area(boundsOf(shape), u * 0.02f)
            val m = area.mask(u * 0.0015f) { c, p -> c.drawPath(path(shape), p) }
            val t = lab(s.linerColor)
            val k = s.linerAmount / 100f
            area.apply(m) { w, l ->
                val a = (w * k * 0.95f).coerceAtMost(1f)
                l[0] += (t[0] - l[0]) * a; l[1] += (t[1] - l[1]) * a; l[2] += (t[2] - l[2]) * a
            }
        }

        fun mascara(e: EyeShape) {
            val upper = resample(e.upperLid, 40)
            val lower = resample(e.lowerLid, 30)
            val len = e.width * (0.1f + 0.2f * s.lashLength / 100f)
            val outDir = PointF((e.outer.x - e.inner.x) / e.width.coerceAtLeast(1f), (e.outer.y - e.inner.y) / e.width.coerceAtLeast(1f))
            val area = Area(boundsOf(upper, lower), len * 1.3f + u * 0.01f)
            val m = area.mask(u * 0.0008f) { c, p ->
                val st = Paint(p).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
                // Lash line.
                st.strokeWidth = e.width * 0.022f
                c.drawPath(path(upper.drop(4), close = false), st)
                fun lashes(pts: List<PointF>, from: Float, count: Int, scale: Float, alpha: Int) {
                    st.alpha = alpha
                    for (k in 0 until count) {
                        val t = from + (1f - from) * k / (count - 1f)
                        val i = (t * (pts.size - 1)).roundToInt().coerceIn(0, pts.size - 1)
                        val n = lidNormal(e, pts, i)
                        // Lashes lean towards the outer corner more and more.
                        val lean = 0.15f + 0.65f * t
                        val dir = PointF(n.x * (1f - lean) + outDir.x * lean, n.y * (1f - lean) + outDir.y * lean)
                        val dl = hypot(dir.x, dir.y).coerceAtLeast(1e-3f)
                        val l = len * scale * (0.55f + 0.6f * t) * (0.85f + 0.3f * ((k * 7919) % 13) / 13f)
                        val b = pts[i]
                        val tip = PointF(b.x + dir.x / dl * l, b.y + dir.y / dl * l)
                        val ctrl = PointF(b.x + n.x * l * 0.6f, b.y + n.y * l * 0.6f)
                        st.strokeWidth = e.width * 0.012f
                        c.drawPath(Path().apply { moveTo(b.x, b.y); quadTo(ctrl.x, ctrl.y, tip.x, tip.y) }, st)
                    }
                }
                lashes(upper, 0.12f, 34, 1f, 235)
                lashes(lower, 0.45f, 12, 0.4f, 130)
            }
            val k = s.mascara / 100f
            area.apply(m) { w, l ->
                val a = (w * k).coerceAtMost(1f)
                l[0] += (8f - l[0]) * a; l[1] *= 1f - a; l[2] *= 1f - a
            }
        }

        fun brow(e: EyeShape) {
            val area = Area(boundsOf(e.brow), u * 0.02f)
            val m = area.mask(u * 0.006f) { c, p -> c.drawPath(path(e.brow), p) }
            val mean = area.meanLab(m)
            val t = lab(s.browColor)
            val k = s.browAmount / 100f
            area.apply(m) { w, l ->
                // Darker pixels are hair: they take more colour, the skin between less.
                val hair = ((mean[0] + 12f - l[0]) / 30f).coerceIn(0.35f, 1f)
                val a = w * k * hair
                l[0] += (min(l[0], t[0] + (l[0] - mean[0]) * 0.5f) - l[0]) * a
                l[1] += (t[1] - l[1]) * a * 0.6f
                l[2] += (t[2] - l[2]) * a * 0.6f
            }
        }

        fun eyeColor(e: EyeShape) {
            val r = e.irisRadius
            if (r < 1f) return
            val c0 = e.iris
            val area = Area(RectF(c0.x - r, c0.y - r, c0.x + r, c0.y + r), u * 0.01f)
            val disc = area.mask(r * 0.08f) { c, p -> c.drawCircle(c0.x, c0.y, r * 0.97f, p) }
            val open = area.mask(r * 0.05f) { c, p -> c.drawPath(path(e.contour), p) }
            val pupil = area.mask(r * 0.08f) { c, p -> c.drawCircle(c0.x, c0.y, r * 0.36f, p) }
            val m = FloatArray(disc.size) { disc[it] * open[it] * (1f - pupil[it]) }
            val mean = area.meanLab(m, 0.4f)
            val t = lab(s.eyeColor)
            val k = s.eyeColorAmount / 100f
            area.apply(m) { w, l ->
                // Leave the catch-light alone.
                val a = w * k * (1f - smooth(72f, 90f, l[0]))
                l[1] += ((t[1] * 0.9f + (l[1] - mean[1]) * 0.3f) - l[1]) * a
                l[2] += ((t[2] * 0.9f + (l[2] - mean[2]) * 0.3f) - l[2]) * a
                l[0] += (t[0] - mean[0]) * 0.45f * a
            }
        }
    }

    @Suppress("unused")
    private fun rot(v: PointF, deg: Float): PointF {
        val r = deg * PI.toFloat() / 180f
        return PointF(v.x * cos(r) - v.y * sin(r), v.x * sin(r) + v.y * cos(r))
    }
}
