package com.example.engine

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Skin retouching of Face-Aware Liquify, done on the pixels before the face warp:
 * blemish removal (small dark / red spots on the skin), dark circles under the eyes, and
 * texture-keeping skin smoothing. Works on an ARGB pixel array in working coordinates times
 * a scale (so the same settings give the same result on the preview and at full resolution).
 */
object FaceSkin {

    /** True when some face asks for skin work. */
    fun active(faces: List<Pair<FaceGeometry, FaceAdjust>>): Boolean =
        faces.any { (_, a) -> a.blemishes > 0f || a.darkCircles > 0f || a.skinSmooth > 0f }

    /**
     * Returns the fixer: it retouches pixels (w x h, in place) whose coordinates are the
     * faces' coordinates times scale. Null when there is nothing to do.
     */
    fun fixer(faces: List<Pair<FaceGeometry, FaceAdjust>>): ((IntArray, Int, Int, Float) -> Unit)? {
        val todo = faces.filter { (_, a) -> a.blemishes > 0f || a.darkCircles > 0f || a.skinSmooth > 0f }
        if (todo.isEmpty()) return null
        return { px, w, h, scale -> for ((g, a) in todo) fixFace(px, w, h, g, a, scale) }
    }

    // --- Geometry ----------------------------------------------------------------------------

    private class Ell(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val ax: PointF, val ay: PointF) {
        /** 1 inside, smooth to 0 at the edge (soft band [soft] of the radius). */
        fun weight(x: Float, y: Float, soft: Float = 0.35f): Float {
            val ox = x - cx; val oy = y - cy
            val lx = ox * ax.x + oy * ax.y; val ly = ox * ay.x + oy * ay.y
            val r = sqrt((lx / rx) * (lx / rx) + (ly / ry) * (ly / ry))
            if (r >= 1f) return 0f
            val core = 1f - soft
            if (r <= core) return 1f
            val t = (r - core) / soft
            return 1f - t * t * (3f - 2f * t)
        }
    }

    private fun along(p: PointF, g: FaceGeometry, s: Float, alongX: Float, alongY: Float) =
        PointF((p.x + g.ax.x * alongX + g.ay.x * alongY) * s, (p.y + g.ax.y * alongX + g.ay.y * alongY) * s)

    // --- Box blur with integral images (over a region, optionally weighted) -----------------

    private class Integral(val w: Int, val h: Int) {
        val r = DoubleArray((w + 1) * (h + 1)); val g = DoubleArray((w + 1) * (h + 1))
        val b = DoubleArray((w + 1) * (h + 1)); val n = DoubleArray((w + 1) * (h + 1))
        fun build(get: (Int, Int) -> Int, weight: (Int, Int) -> Float) {
            val s = w + 1
            for (y in 0 until h) {
                var sr = 0.0; var sg = 0.0; var sb = 0.0; var sn = 0.0
                for (x in 0 until w) {
                    val c = get(x, y); val k = weight(x, y).toDouble()
                    sr += ((c shr 16) and 255) * k; sg += ((c shr 8) and 255) * k; sb += (c and 255) * k; sn += k
                    val i = (y + 1) * s + x + 1
                    r[i] = r[i - s] + sr; g[i] = g[i - s] + sg; b[i] = b[i - s] + sb; n[i] = n[i - s] + sn
                }
            }
        }
        /** Mean colour (as floats 0..255) in the box of radius [rad] around (x, y); false if empty. */
        fun mean(x: Int, y: Int, rad: Int, out: FloatArray): Boolean {
            val s = w + 1
            val l = max(0, x - rad); val t = max(0, y - rad); val rr = min(w, x + rad + 1); val bb = min(h, y + rad + 1)
            fun sum(a: DoubleArray) = a[bb * s + rr] - a[t * s + rr] - a[bb * s + l] + a[t * s + l]
            val cnt = sum(n)
            if (cnt < 1e-6) return false
            out[0] = (sum(r) / cnt).toFloat(); out[1] = (sum(g) / cnt).toFloat(); out[2] = (sum(b) / cnt).toFloat()
            return true
        }
    }

    private fun lum(r: Float, g: Float, b: Float) = 0.299f * r + 0.587f * g + 0.114f * b

    private fun pack(a: Int, r: Float, g: Float, b: Float): Int =
        (a shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)

    // --- The work ------------------------------------------------------------------------------

    /** Longest side the skin analysis works at; the correction is then scaled to the image. */
    private const val WORK_MAX = 640

    private fun fixFace(px: IntArray, w: Int, h: Int, g: FaceGeometry, a: FaceAdjust, s: Float) {
        // Region: the face box with a margin.
        val cx = g.center.x * s; val cy = g.center.y * s
        val reach = max(g.width, g.height) * s * 0.75f
        val X0 = max(0, (cx - reach).toInt()); val Y0 = max(0, (cy - reach).toInt())
        val X1 = min(w - 1, (cx + reach).toInt()); val Y1 = min(h - 1, (cy + reach).toInt())
        if (X1 - X0 < 8 || Y1 - Y0 < 8) return
        val RW = X1 - X0 + 1; val RH = Y1 - Y0 + 1
        val f = min(1f, WORK_MAX.toFloat() / max(RW, RH))
        val lw = max(8, (RW * f).roundToInt()); val lh = max(8, (RH * f).roundToInt())
        val fx = lw.toFloat() / RW; val fy = lh.toFloat() / RH
        // Box-averaged copy of the region at working size.
        val low = IntArray(lw * lh)
        for (y in 0 until lh) for (x in 0 until lw) {
            val sx0 = X0 + (x / fx).toInt(); val sx1 = min(X1, X0 + ((x + 1) / fx).toInt() - 1).coerceAtLeast(sx0)
            val sy0 = Y0 + (y / fy).toInt(); val sy1 = min(Y1, Y0 + ((y + 1) / fy).toInt() - 1).coerceAtLeast(sy0)
            var sa = 0; var sr = 0; var sg = 0; var sb = 0; var n = 0
            var yy = sy0
            while (yy <= sy1) {
                var xx = sx0
                while (xx <= sx1) {
                    val c = px[yy * w + xx]
                    sa += c ushr 24; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255; n++
                    xx++
                }
                yy++
            }
            low[y * lw + x] = ((sa / n) shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
        }
        val fixed = low.copyOf()
        fixRegion(fixed, lw, lh, X0 * fx, Y0 * fy, g, a, s * fx)
        // The change, scaled up and added to every pixel: the image keeps its own fine detail.
        val dr = FloatArray(lw * lh); val dg = FloatArray(lw * lh); val db = FloatArray(lw * lh)
        var any = false
        for (i in low.indices) {
            val o = low[i]; val n = fixed[i]
            dr[i] = (((n shr 16) and 255) - ((o shr 16) and 255)).toFloat()
            dg[i] = (((n shr 8) and 255) - ((o shr 8) and 255)).toFloat()
            db[i] = ((n and 255) - (o and 255)).toFloat()
            if (dr[i] != 0f || dg[i] != 0f || db[i] != 0f) any = true
        }
        if (!any) return
        for (y in Y0..Y1) {
            val ly = ((y - Y0 + 0.5f) * fy - 0.5f).coerceIn(0f, (lh - 1).toFloat())
            val y0 = ly.toInt(); val y1 = min(lh - 1, y0 + 1); val ty = ly - y0
            for (x in X0..X1) {
                val lx = ((x - X0 + 0.5f) * fx - 0.5f).coerceIn(0f, (lw - 1).toFloat())
                val x0 = lx.toInt(); val x1 = min(lw - 1, x0 + 1); val tx = lx - x0
                fun bil(d: FloatArray) = (d[y0 * lw + x0] * (1 - tx) + d[y0 * lw + x1] * tx) * (1 - ty) + (d[y1 * lw + x0] * (1 - tx) + d[y1 * lw + x1] * tx) * ty
                val r = bil(dr); val gg = bil(dg); val b = bil(db)
                if (r == 0f && gg == 0f && b == 0f) continue
                val i = y * w + x
                val c = px[i]
                px[i] = pack(c ushr 24, ((c shr 16) and 255) + r, ((c shr 8) and 255) + gg, (c and 255) + b)
            }
        }
    }

    /**
     * Retouches [buf] (rw x rh), whose pixel (x, y) is at (x + ox, y + oy) in the faces'
     * coordinates times [s].
     */
    private fun fixRegion(buf: IntArray, rw: Int, rh: Int, ox: Float, oy: Float, g: FaceGeometry, a: FaceAdjust, s: Float) {
        val fw = g.width * s; val fh = g.height * s
        val cx = g.center.x * s; val cy = g.center.y * s
        val x0 = ox; val y0 = oy
        fun at(x: Int, y: Int) = buf[y * rw + x]

        // Skin: the face ellipse without eyes, brows, nostrils and mouth.
        val face = Ell(cx, cy - fh * 0.04f, fw * 0.5f, fh * 0.6f, g.ax, g.ay)
        val eyeRx = g.eyeW * s * 0.85f; val eyeRy = max(g.eyeH * s * 1.7f, g.eyeW * s * 0.45f)
        val eyes = listOf(g.eye1, g.eye2).map { e -> Ell(e.x * s, e.y * s, eyeRx, eyeRy, g.ax, g.ay) }
        val brows = listOf(g.eye1, g.eye2).map { e ->
            val c = along(e, g, s, 0f, -g.eyeH * 2.7f)
            Ell(c.x, c.y, g.eyeW * s * 0.95f, g.eyeH * s * 1.4f, g.ax, g.ay)
        }
        val mouthHalf = hypot(g.mouthRight.x - g.mouthLeft.x, g.mouthRight.y - g.mouthLeft.y) * s * 0.62f
        val mouth = Ell(g.mouthCenter.x * s, g.mouthCenter.y * s, mouthHalf, max(g.mouthH * s * 1.6f, mouthHalf * 0.45f), g.ax, g.ay)
        val nose = along(g.noseBase, g, s, 0f, -g.noseLen * 0.08f)
        val nostrils = Ell(nose.x, nose.y, g.noseW * s * 0.62f, g.noseLen * s * 0.28f, g.ax, g.ay)
        val skin = FloatArray(rw * rh)
        for (y in 0 until rh) for (x in 0 until rw) {
            val fx = (x + x0).toFloat(); val fy = (y + y0).toFloat()
            var k = face.weight(fx, fy, 0.25f)
            if (k <= 0f) continue
            for (e in eyes) k *= 1f - e.weight(fx, fy)
            for (e in brows) k *= 1f - e.weight(fx, fy)
            k *= 1f - mouth.weight(fx, fy)
            k *= 1f - nostrils.weight(fx, fy)
            // Never treat very dark pixels (hair, shadows) or transparent ones as skin.
            val c = at(x, y)
            if ((c ushr 24) < 200) k = 0f
            skin[y * rw + x] = k
        }
        val out = IntArray(rw * rh) { at(it % rw, it / rw) }
        val tmp = FloatArray(3)

        // 1. Blemishes: spots darker or redder than the skin around them, at most ~3% of the face.
        if (a.blemishes > 0f) {
            val strength = a.blemishes / 100f
            val rad = max(2, (fw * 0.028f).roundToInt())
            val around = Integral(rw, rh).also { it.build({ x, y -> out[y * rw + x] }, { x, y -> skin[y * rw + x] }) }
            val score = FloatArray(rw * rh)
            for (y in 0 until rh) for (x in 0 until rw) {
                val i = y * rw + x
                if (skin[i] < 0.3f || !around.mean(x, y, rad, tmp)) continue
                val c = out[i]
                val r = ((c shr 16) and 255).toFloat(); val gg = ((c shr 8) and 255).toFloat(); val b = (c and 255).toFloat()
                val dark = (lum(tmp[0], tmp[1], tmp[2]) - lum(r, gg, b)) / 255f
                val red = ((r - gg) - (tmp[0] - tmp[1])) / 255f
                score[i] = max(0f, dark) + max(0f, red) * 0.8f
            }
            val thr = 0.11f - 0.075f * strength
            // Spot mask (soft), grown by a pixel or two so the rim goes too.
            val spot = FloatArray(rw * rh)
            for (i in spot.indices) {
                val v = (score[i] - thr) / (thr * 0.8f)
                spot[i] = v.coerceIn(0f, 1f)
            }
            val grow = max(1, rad / 4)
            val spotI = Integral(rw, rh).also { it.build({ x, y -> (spot[y * rw + x] * 255f).toInt() }, { _, _ -> 1f }) }
            val grown = FloatArray(rw * rh)
            for (y in 0 until rh) for (x in 0 until rw) {
                if (spotI.mean(x, y, grow, tmp)) grown[y * rw + x] = min(1f, max(spot[y * rw + x], tmp[2] / 255f * 2.5f))
            }
            // Fill: the mean of the clean skin around (spots excluded), plus the fine texture.
            val clean = Integral(rw, rh).also { it.build({ x, y -> out[y * rw + x] }, { x, y -> skin[y * rw + x] * (1f - grown[y * rw + x]) }) }
            val fine = Integral(rw, rh).also { it.build({ x, y -> out[y * rw + x] }, { _, _ -> 1f }) }
            val bigRad = rad + grow * 2
            val t2 = FloatArray(3)
            for (y in 0 until rh) for (x in 0 until rw) {
                val i = y * rw + x
                val m = grown[i] * min(1f, skin[i] * 1.5f)
                if (m <= 0.01f || !clean.mean(x, y, bigRad, tmp)) continue
                val c = out[i]
                fine.mean(x, y, 1, t2)
                val r = ((c shr 16) and 255) - t2[0]; val gg = ((c shr 8) and 255) - t2[1]; val b = (c and 255) - t2[2]
                val fixed = pack(c ushr 24, tmp[0] + r * 0.5f, tmp[1] + gg * 0.5f, tmp[2] + b * 0.5f)
                out[i] = RetouchCanvas.mix(c, fixed, m)
            }
        }

        // 2. Dark circles: lift the area under each eye to the tone of the cheek below it.
        if (a.darkCircles > 0f) {
            val k = a.darkCircles / 100f
            val local = Integral(rw, rh).also { it.build({ x, y -> out[y * rw + x] }, { _, _ -> 1f }) }
            val localRad = max(2, (g.eyeH * s * 0.9f).roundToInt())
            for (e in listOf(g.eye1, g.eye2)) {
                val under = along(e, g, s, 0f, g.eyeH * 2.1f)
                val cheek = along(e, g, s, 0f, g.eyeH * 5.2f)
                val zone = Ell(under.x, under.y, g.eyeW * s * 0.85f, max(g.eyeH * s * 1.5f, g.eyeW * s * 0.32f), g.ax, g.ay)
                val ccx = (cheek.x - x0).roundToInt().coerceIn(0, rw - 1); val ccy = (cheek.y - y0).roundToInt().coerceIn(0, rh - 1)
                val ref = FloatArray(3)
                if (!local.mean(ccx, ccy, max(3, (g.eyeW * s * 0.35f).roundToInt()), ref)) continue
                val refL = lum(ref[0], ref[1], ref[2])
                val l = max(0, (zone.cx - zone.rx - x0).toInt()); val r = min(rw - 1, (zone.cx + zone.rx - x0).toInt() + 1)
                val t = max(0, (zone.cy - zone.ry * 1.6f - y0).toInt()); val b = min(rh - 1, (zone.cy + zone.ry * 1.6f - y0).toInt() + 1)
                for (y in t..b) for (x in l..r) {
                    val i = y * rw + x
                    val wz = zone.weight((x + x0).toFloat(), (y + y0).toFloat(), 0.6f) * max(skin[i], 0.4f * face.weight((x + x0).toFloat(), (y + y0).toFloat()))
                    if (wz <= 0f || !local.mean(x, y, localRad, tmp)) continue
                    if (lum(tmp[0], tmp[1], tmp[2]) >= refL) continue
                    val c = out[i]
                    val f = wz * k
                    out[i] = pack(
                        c ushr 24,
                        ((c shr 16) and 255) + (ref[0] - tmp[0]) * f,
                        ((c shr 8) and 255) + (ref[1] - tmp[1]) * f,
                        (c and 255) + (ref[2] - tmp[2]) * f
                    )
                }
            }
        }

        // 3. Skin smoothing: less fine texture, edges (nose, jaw, wrinkles' shadows) kept.
        if (a.skinSmooth > 0f) {
            val k = a.skinSmooth / 100f * 0.8f
            val rad = max(1, (fw * 0.012f).roundToInt())
            val blur = Integral(rw, rh).also { it.build({ x, y -> out[y * rw + x] }, { x, y -> 0.2f + skin[y * rw + x] }) }
            for (y in 0 until rh) for (x in 0 until rw) {
                val i = y * rw + x
                if (skin[i] <= 0.01f || !blur.mean(x, y, rad, tmp)) continue
                val c = out[i]
                val r = ((c shr 16) and 255).toFloat(); val gg = ((c shr 8) and 255).toFloat(); val b = (c and 255).toFloat()
                val d = abs(lum(r, gg, b) - lum(tmp[0], tmp[1], tmp[2])) / 255f
                val edge = ((0.14f - d) / 0.1f).coerceIn(0f, 1f)
                val m = k * skin[i] * edge
                if (m <= 0f) continue
                out[i] = pack(c ushr 24, r + (tmp[0] - r) * m, gg + (tmp[1] - gg) * m, b + (tmp[2] - b) * m)
            }
        }
        System.arraycopy(out, 0, buf, 0, rw * rh)
    }
}
