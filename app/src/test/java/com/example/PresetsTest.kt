package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.data.model.ColorAdjustments
import com.example.data.model.FilterType
import com.example.engine.AdjustmentsJson
import com.example.engine.ArtFilters
import com.example.engine.FilterRecipes
import com.example.engine.ImageProcessor
import com.example.engine.PresetSliders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresetsTest {

    private fun solid(w: Int, h: Int, color: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun lum(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
    private fun hue(c: Int): Float = FloatArray(3).also { Color.colorToHSV(c, it) }[0]

    /** A colourful test chart: hue across, lightness down. */
    private fun chart(w: Int = 72, h: Int = 48): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val v = 0.15f + 0.8f * (1f - y / (h - 1f))
            b.setPixel(x, y, Color.HSVToColor(floatArrayOf(x * 360f / w, if (y % 12 < 3) 0f else 0.7f, v)))
        }
        return b
    }

    private fun meanDiff(a: Bitmap, b: Bitmap): Double {
        var s = 0.0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            s += abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))
        }
        return s / (a.width * a.height * 3)
    }

    @Test
    fun everyPresetRunsAndKeepsSizeAndAlpha() {
        val src = chart(40, 30)
        src.setPixel(0, 0, Color.TRANSPARENT)
        for (f in FilterType.values()) {
            val out = ImageProcessor.applyFilter(src, f, 1f)
            assertEquals(f.name, 40, out.width)
            assertEquals(f.name, 30, out.height)
            assertEquals(f.name, 0, Color.alpha(out.getPixel(0, 0)))
            assertEquals(f.name, 255, Color.alpha(out.getPixel(20, 15)))
        }
    }

    @Test
    fun orangeAndTealMovesGreensToTealAndKeepsWarmTonesOrange() {
        val green = ImageProcessor.applyFilter(solid(8, 8, Color.rgb(70, 150, 60)), FilterType.ORANGE_TEAL, 1f).getPixel(4, 4)
        val h = hue(green)
        assertTrue("green should turn teal, hue=$h", h in 150f..210f)
        val skin = ImageProcessor.applyFilter(solid(8, 8, Color.rgb(210, 150, 115)), FilterType.ORANGE_TEAL, 1f).getPixel(4, 4)
        assertTrue("warm tones stay warm", Color.red(skin) > Color.blue(skin) + 40)
        val hs = hue(skin)
        assertTrue("skin hue stays orange, hue=$hs", hs in 10f..45f)
        // Purple is muted.
        val purple = Color.rgb(140, 60, 180)
        val p2 = ImageProcessor.applyFilter(solid(8, 8, purple), FilterType.ORANGE_TEAL, 1f).getPixel(4, 4)
        val s0 = FloatArray(3).also { Color.colorToHSV(purple, it) }[1]
        val s1 = FloatArray(3).also { Color.colorToHSV(p2, it) }[1]
        assertTrue("purple desaturated $s0 -> $s1", s1 < s0 - 0.15f)
    }

    @Test
    fun formerDuplicatesAreClearlyDifferent() {
        val src = chart()
        val pairs = listOf(
            FilterType.WARM to FilterType.GOLDEN_HOUR,
            FilterType.CYBERPUNK to FilterType.VAPORWAVE,
            FilterType.FADED_MATTE to FilterType.VINTAGE,
            FilterType.GRAYSCALE to FilterType.TRI_X_BW,
            FilterType.ORANGE_TEAL to FilterType.CINEMATIC,
            FilterType.CYBERPUNK to FilterType.NEON_NIGHT
        )
        for ((a, b) in pairs) {
            val d = meanDiff(ImageProcessor.applyFilter(src, a, 1f), ImageProcessor.applyFilter(src, b, 1f))
            assertTrue("$a vs $b differ only by $d", d > 6.0)
        }
    }

    @Test
    fun colorGradingTintsShadowsAndHighlightsSeparately() {
        val adj = ColorAdjustments(gradeShadowHue = 220f, gradeShadowSat = 80f, gradeHighHue = 35f, gradeHighSat = 80f)
        val dark = ImageProcessor.applyAdjustments(solid(8, 8, Color.rgb(50, 50, 50)), adj).getPixel(4, 4)
        val light = ImageProcessor.applyAdjustments(solid(8, 8, Color.rgb(200, 200, 200)), adj).getPixel(4, 4)
        assertTrue("shadows blue $dark", Color.blue(dark) > Color.red(dark) + 8)
        assertTrue("highlights warm $light", Color.red(light) > Color.blue(light) + 8)
        // Lightness is kept.
        assertTrue(abs(lum(dark) - 50) < 8)
        assertTrue(abs(lum(light) - 200) < 8)
        // Round trip through the saved-project JSON.
        val back = AdjustmentsJson.fromJson(AdjustmentsJson.toJson(adj.copy(gradeBalance = 20f, gradeBlending = 70f)))
        assertEquals(80f, back.gradeShadowSat, 0.01f)
        assertEquals(35f, back.gradeHighHue, 0.01f)
        assertEquals(20f, back.gradeBalance, 0.01f)
        assertEquals(70f, back.gradeBlending, 0.01f)
        assertTrue(ColorAdjustments().copy(gradeMidSat = 10f).isIdentity().not())
    }

    @Test
    fun blackAndWhiteFiltersBehaveLikeTheirLensFilters() {
        val sky = solid(8, 8, Color.rgb(70, 130, 220))
        val leaves = solid(8, 8, Color.rgb(70, 150, 50))
        val gSky = lum(ImageProcessor.applyFilter(sky, FilterType.GRAYSCALE, 1f).getPixel(4, 4))
        val rSky = lum(ImageProcessor.applyFilter(sky, FilterType.BW_RED_FILTER, 1f).getPixel(4, 4))
        assertTrue("red filter darkens blue sky $rSky < $gSky", rSky < gSky - 25)
        val gLeaf = lum(ImageProcessor.applyFilter(leaves, FilterType.GRAYSCALE, 1f).getPixel(4, 4))
        val irLeaf = ImageProcessor.applyFilter(leaves, FilterType.INFRARED, 1f).getPixel(4, 4)
        assertTrue("infrared foliage glows ${lum(irLeaf)} > $gLeaf", lum(irLeaf) > gLeaf + 40)
        assertTrue("infrared is monochrome", abs(Color.red(irLeaf) - Color.blue(irLeaf)) < 6)
    }

    @Test
    fun charcoalDrawsDarkOnPaper() {
        val src = solid(80, 80, Color.WHITE)
        for (y in 20 until 60) for (x in 20 until 60) src.setPixel(x, y, Color.rgb(20, 30, 40))
        val out = ArtFilters.charcoal(src)
        val paper = out.getPixel(4, 4)
        val ink = out.getPixel(40, 40)
        assertTrue("paper is light ${lum(paper)}", lum(paper) > 200)
        assertTrue("charcoal is dark ${lum(ink)}", lum(ink) < 110)
        assertTrue("neutral ink", abs(Color.red(ink) - Color.blue(ink)) < 12)
    }

    @Test
    fun oilPaintingSmoothsNoiseIntoStrokes() {
        val src = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(3)
        for (y in 0 until 64) for (x in 0 until 64) {
            val base = if (x < 32) 80 else 180
            val n = rnd.nextInt(60) - 30
            src.setPixel(x, y, Color.rgb((base + n).coerceIn(0, 255), (base + n).coerceIn(0, 255), base))
        }
        fun variance(b: Bitmap): Double {
            val v = (10 until 26).flatMap { y -> (6 until 26).map { x -> lum(b.getPixel(x, y)) } }
            val m = v.average()
            return v.sumOf { (it - m) * (it - m) } / v.size
        }
        val out = ArtFilters.oilPainting(src)
        assertEquals(64, out.width)
        assertTrue("noise smoothed ${variance(out)} < ${variance(src)}", variance(out) < variance(src) * 0.6)
        // The edge between the two halves survives.
        assertTrue(lum(out.getPixel(56, 32)) - lum(out.getPixel(8, 32)) > 60)
    }

    @Test
    fun presetConvertsToSlidersThatLookAlike() {
        assertNull(PresetSliders.toAdjustments(FilterType.CHARCOAL))
        assertNull(PresetSliders.toAdjustments(FilterType.NONE))
        val full = PresetSliders.toAdjustments(FilterType.ORANGE_TEAL)
        assertNotNull(full)
        val adj = full!!.adjustments
        assertTrue(adj.hslBands.containsKey("GREENS"))
        assertTrue(adj.gradeShadowSat > 0f)
        // The sliders give the same direction as the preset: greens to teal.
        val g = ImageProcessor.applyAdjustments(solid(8, 8, Color.rgb(70, 150, 60)), adj).getPixel(4, 4)
        assertTrue("sliders turn green teal, hue=${hue(g)}", hue(g) in 150f..210f)
        // Intensity scales the sliders.
        val half = PresetSliders.toAdjustments(FilterType.ORANGE_TEAL, 0.5f)!!.adjustments
        assertEquals(adj.contrast / 2f, half.contrast, 0.5f)
        assertEquals(adj.gradeShadowSat / 2f, half.gradeShadowSat, 0.5f)
        // Effects that sliders cannot express are reported.
        assertTrue(!PresetSliders.toAdjustments(FilterType.GOLDEN_HOUR)!!.exact)
        assertTrue(PresetSliders.toAdjustments(FilterType.CLASSIC_CHROME)!!.exact)
        // Merging into existing adjustments adds them up.
        val merged = PresetSliders.merge(ColorAdjustments(contrast = 10f), ColorAdjustments(contrast = 15f, clarity = 5f))
        assertEquals(25f, merged.contrast, 0.01f)
        assertEquals(5f, merged.clarity, 0.01f)
        // A preset converted from a warm look warms up.
        val warm = ImageProcessor.applyAdjustments(solid(8, 8, Color.rgb(128, 128, 128)), PresetSliders.toAdjustments(FilterType.WARM)!!.adjustments).getPixel(4, 4)
        assertTrue(Color.red(warm) > Color.blue(warm) + 6)
    }

    @Test
    fun everyRecipePresetHasACategory() {
        for (f in FilterType.values()) {
            if (FilterRecipes.recipeFor(f) == null) continue
            FilterRecipes.categoryOf(f)
        }
        assertEquals(com.example.engine.FilterCategory.FILM, FilterRecipes.categoryOf(FilterType.CINESTILL_800T))
        assertEquals(com.example.engine.FilterCategory.BLACK_WHITE, FilterRecipes.categoryOf(FilterType.INFRARED))
    }
}
