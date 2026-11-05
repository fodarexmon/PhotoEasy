package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.engine.ImageProcessor
import com.example.engine.SelectionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("PhotoEasy", appName)
  }

  @Test
  fun `inpaint preserves unmasked background pixels`() {
    val size = 64
    val src = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    src.eraseColor(Color.BLUE)

    // Put a red square in the center
    for (y in 24..40) {
      for (x in 24..40) {
        src.setPixel(x, y, Color.RED)
      }
    }

    // Mask strictly over the red square
    val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    mask.eraseColor(Color.TRANSPARENT)
    for (y in 24..40) {
      for (x in 24..40) {
        mask.setPixel(x, y, Color.WHITE)
      }
    }

    val inpainted = ImageProcessor.inpaintObjectRemoval(src, mask)

    // Unmasked corner pixels must remain EXACTLY original blue
    assertEquals(Color.BLUE, inpainted.getPixel(0, 0))
    assertEquals(Color.BLUE, inpainted.getPixel(5, 5))
    assertEquals(Color.BLUE, inpainted.getPixel(60, 60))

    // Inpainted center pixel must no longer be the solid red object
    val centerPixel = inpainted.getPixel(32, 32)
    assertNotEquals(Color.RED, centerPixel)
  }

  @Test
  fun `selection filter composite only modifies masked area`() {
    val size = 50
    val orig = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    orig.eraseColor(Color.BLACK)

    val filtered = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    filtered.eraseColor(Color.WHITE)

    // Local mask with top-left quadrant selected
    val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
    mask.eraseColor(Color.TRANSPARENT)
    for (y in 0 until 20) {
      for (x in 0 until 20) {
        mask.setPixel(x, y, (255 shl 24))
      }
    }

    val comp = SelectionEngine.compositeFilteredWithSelection(orig, filtered, mask)

    // Top-left quadrant should be filtered (white)
    val tl = comp.getPixel(10, 10)
    assertEquals(255, (tl ushr 16) and 0xFF) // Red
    assertEquals(255, (tl ushr 8) and 0xFF)  // Green
    assertEquals(255, tl and 0xFF)          // Blue

    // Bottom-right quadrant should remain unselected (black)
    val br = comp.getPixel(40, 40)
    assertEquals(0, (br ushr 16) and 0xFF)
    assertEquals(0, (br ushr 8) and 0xFF)
    assertEquals(0, br and 0xFF)
  }
}
