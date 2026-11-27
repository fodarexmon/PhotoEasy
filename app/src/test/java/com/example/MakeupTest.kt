package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import com.example.engine.EyeShape
import com.example.engine.FaceMesh
import com.example.engine.Makeup
import com.example.engine.MakeupFace
import com.example.engine.MakeupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Makeup on a drawn face (skin, lips, eyes with irises, brows) with known geometry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MakeupTest {

    private val skin = Color.rgb(222, 180, 150)

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, n: Int = 24) =
        (0 until n).map { k -> val a = k * 2.0 * Math.PI / n; PointF(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat()) }

    private fun eye(cx: Float, cy: Float, innerOnRight: Boolean): EyeShape {
        val contour = ellipse(cx, cy, 30f, 12f)
        val l = PointF(cx - 30f, cy); val r = PointF(cx + 30f, cy)
        val upperLR = (0..10).map { k -> val t = k / 10f; PointF(cx - 30f + 60f * t, cy - 12f * sin(Math.PI * t).toFloat()) }
        val lowerLR = (0..10).map { k -> val t = k / 10f; PointF(cx - 30f + 60f * t, cy + 12f * sin(Math.PI * t).toFloat()) }
        val upper = if (innerOnRight) upperLR.reversed() else upperLR
        val lower = if (innerOnRight) lowerLR.reversed() else lowerLR
        val brow = ellipse(cx, cy - 40f, 36f, 7f)
        return EyeShape(contour, upper, lower, if (innerOnRight) r else l, if (innerOnRight) l else r, brow, PointF(cx, cy), 11f)
    }

    /** 400 × 400 picture with a face, and the face's geometry. */
    private fun scene(): Pair<Bitmap, MakeupFace> {
        val b = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 60, 90)) }
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = skin; c.drawOval(RectF(80f, 40f, 320f, 360f), p)
        // Eyes: white with a brown iris and a black pupil.
        listOf(150f, 250f).forEach { x ->
            p.color = Color.rgb(235, 235, 230); c.drawOval(RectF(x - 30f, 168f, x + 30f, 192f), p)
            p.color = Color.rgb(110, 70, 40); c.drawCircle(x, 180f, 11f, p)
            p.color = Color.rgb(15, 10, 10); c.drawCircle(x, 180f, 4f, p)
            p.color = Color.rgb(120, 85, 60); c.drawOval(RectF(x - 36f, 133f, x + 36f, 147f), p)
        }
        // Lips and the dark mouth gap.
        p.color = Color.rgb(200, 120, 115); c.drawOval(RectF(160f, 280f, 240f, 312f), p)
        val face = MakeupFace(
            box = RectF(80f, 40f, 320f, 360f),
            oval = ellipse(200f, 200f, 120f, 160f, 36),
            lipsOuter = ellipse(200f, 296f, 40f, 16f),
            lipsInner = ellipse(200f, 296f, 30f, 2f),
            eyes = listOf(eye(150f, 180f, innerOnRight = true), eye(250f, 180f, innerOnRight = false)),
            cheeks = listOf(PointF(135f, 245f), PointF(265f, 245f)),
            cheekbones = listOf(PointF(120f, 220f), PointF(280f, 220f)),
            earSides = listOf(PointF(82f, 200f), PointF(318f, 200f)),
            noseTop = PointF(200f, 175f), noseTip = PointF(200f, 250f),
            noseWings = listOf(PointF(183f, 250f), PointF(217f, 250f)),
            forehead = PointF(200f, 100f), chin = PointF(200f, 358f),
            mouthCorners = listOf(PointF(160f, 296f), PointF(240f, 296f)),
            precise = true
        )
        return b to face
    }

    private fun lab(c: Int) = FloatArray(3).also { com.example.engine.Makeup.toLab(c, it) }

    @Test
    fun neutralSettingsChangeNothing() {
        val (b, f) = scene()
        val out = Makeup.render(b, listOf(f), listOf(MakeupSettings()))
        for (y in 0 until 400 step 7) for (x in 0 until 400 step 7) assertEquals(b.getPixel(x, y), out.getPixel(x, y))
    }

    @Test
    fun lipstickColoursTheLipsOnly() {
        val (b, f) = scene()
        val out = Makeup.render(b, listOf(f), listOf(MakeupSettings(lipAmount = 100f, lipColor = 0x2050C0)))
        val before = lab(b.getPixel(200, 288)); val after = lab(out.getPixel(200, 288))
        // Blue lipstick: b* goes down a lot (towards blue).
        assertTrue("lips turn blue: b* ${before[2]} -> ${after[2]}", after[2] < before[2] - 20f)
        // Skin next to the lips, the eyes and the background stay as they were.
        assertEquals(b.getPixel(200, 340), out.getPixel(200, 340))
        assertEquals(b.getPixel(150, 180), out.getPixel(150, 180))
        assertEquals(b.getPixel(20, 20), out.getPixel(20, 20))
    }

    @Test
    fun eyeColourChangesTheIrisNotThePupilOrTheWhite() {
        val (b, f) = scene()
        val out = Makeup.render(b, listOf(f), listOf(MakeupSettings(eyeColorAmount = 100f, eyeColor = 0x2E7DD8)))
        val iris0 = lab(b.getPixel(158, 180)); val iris1 = lab(out.getPixel(158, 180))
        assertTrue("iris turns blue: b* ${iris0[2]} -> ${iris1[2]}", iris1[2] < iris0[2] - 15f)
        val pupil0 = lab(b.getPixel(150, 180)); val pupil1 = lab(out.getPixel(150, 180))
        assertTrue("pupil stays dark", abs(pupil1[0] - pupil0[0]) < 6f)
        assertEquals("eye white untouched", b.getPixel(176, 180), out.getPixel(176, 180))
    }

    @Test
    fun blushWarmsTheCheeksInsideTheFace() {
        val (b, f) = scene()
        val out = Makeup.render(b, listOf(f), listOf(MakeupSettings(blushAmount = 100f, blushColor = 0xE0506A)))
        val c0 = lab(b.getPixel(138, 240)); val c1 = lab(out.getPixel(138, 240))
        assertTrue("cheek gets redder: a* ${c0[1]} -> ${c1[1]}", c1[1] > c0[1] + 5f)
        assertEquals("background untouched", b.getPixel(30, 240), out.getPixel(30, 240))
        assertEquals("eye untouched", b.getPixel(150, 180), out.getPixel(150, 180))
    }

    @Test
    fun eyelinerAndMascaraDarkenTheUpperLid() {
        val (b, f) = scene()
        val out = Makeup.render(b, listOf(f), listOf(MakeupSettings(linerAmount = 100f, linerThickness = 60f, mascara = 100f)))
        // Just above the upper lid, towards the outer corner of the left eye.
        val y = 168
        val l0 = lab(b.getPixel(140, y))[0]; val l1 = lab(out.getPixel(140, y))[0]
        assertTrue("liner darkens the lid $l0 -> $l1", l1 < l0 - 25f)
    }

    @Test
    fun contourDarkensAndHighlightBrightens() {
        val (b, f) = scene()
        val dark = Makeup.render(b, listOf(f), listOf(MakeupSettings(contour = 100f)))
        val bright = Makeup.render(b, listOf(f), listOf(MakeupSettings(highlight = 100f)))
        // Jaw, just inside the outline.
        val jaw0 = lab(b.getPixel(200, 348))[0]; val jaw1 = lab(dark.getPixel(200, 348))[0]
        assertTrue("jaw shaded $jaw0 -> $jaw1", jaw1 < jaw0 - 3f)
        // Nose bridge.
        val n0 = lab(b.getPixel(200, 210))[0]; val n1 = lab(bright.getPixel(200, 210))[0]
        assertTrue("nose bridge highlighted $n0 -> $n1", n1 > n0 + 1.5f)
    }

    @Test
    fun makeupLayerHoldsOnlyTheChangedPixels() {
        val (b, f) = scene()
        val made = Makeup.render(b, listOf(f), listOf(Makeup.look(Makeup.Look.GLAM)))
        val layer = Makeup.layerFrom(b, made)
        assertEquals(0, Color.alpha(layer.getPixel(20, 20)))
        assertEquals(255, Color.alpha(layer.getPixel(200, 288)))
        // Layer over the picture gives the made-up picture.
        val comp = b.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(comp).drawBitmap(layer, 0f, 0f, null)
        for (y in 0 until 400 step 5) for (x in 0 until 400 step 5) assertEquals("($x,$y)", made.getPixel(x, y), comp.getPixel(x, y))
    }

    @Test
    fun everyLookRendersAndTwoFacesGetTheirOwnSettings() {
        val (b, f) = scene()
        Makeup.Look.values().forEach { Makeup.render(b, listOf(f), listOf(Makeup.look(it))) }
        val moved = f.scaled(1f)
        val out = Makeup.render(b, listOf(f, moved), listOf(MakeupSettings(), MakeupSettings()))
        assertEquals(b.getPixel(200, 288), out.getPixel(200, 288))
        // Scaling the geometry scales every point.
        val big = f.scaled(2f)
        assertEquals(f.lipsOuter[3].x * 2f, big.lipsOuter[3].x, 0.01f)
        assertEquals(f.eyes[0].irisRadius * 2f, big.eyes[0].irisRadius, 0.01f)
    }

    @Test
    fun irisIsFoundInTheEyeWithoutTheMesh() {
        val (b, _) = scene()
        val contour = ellipse(250f, 180f, 30f, 12f)
        val found = FaceMesh.findIris(b, contour, 60f)!!
        assertTrue("iris near its centre: ${found.first}", abs(found.first.x - 250f) < 6f && abs(found.first.y - 180f) < 5f)
        // Lids split at the corners, inner corner towards the face middle.
        val e = FaceMesh.eyeFromContour(contour, PointF(200f, 180f), emptyList(), b)
        assertTrue("inner corner is the one near the middle", e.inner.x < e.outer.x)
        assertTrue("upper lid above", e.upperLid.map { it.y }.average() < e.lowerLid.map { it.y }.average())
    }
}
