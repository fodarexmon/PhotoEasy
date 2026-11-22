package com.example.engine.ai

import android.content.Context
import android.graphics.Bitmap
import com.example.engine.ImageProcessor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * Automatic colorization with a downloaded network (Zhang et al., SIGGRAPH 2017). The network
 * sees a small copy of the image's lightness and predicts its colour (Lab a, b); the colour is
 * scaled up and put on the original lightness at full resolution, so detail stays sharp.
 */
object AiColorizer {

    const val MODEL = "colorize-v1"

    // --- CIE Lab (D65), as the network was trained ----------------------------------------------

    private val toLinear = FloatArray(256) { i ->
        val c = i / 255f
        if (c > 0.04045f) ((c + 0.055f) / 1.055f).pow(2.4f) else c / 12.92f
    }
    private const val GAMMA_STEPS = 4096
    private val toSrgb = IntArray(GAMMA_STEPS + 1) { i ->
        val c = i.toFloat() / GAMMA_STEPS
        val v = if (c > 0.0031308f) 1.055f * c.pow(1f / 2.4f) - 0.055f else 12.92f * c
        (v * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun f(t: Float) = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f
    private fun fInv(t: Float) = if (t > 0.206893f) t * t * t else (t - 16f / 116f) / 7.787f

    /** Lab L (0..100) of an sRGB colour. */
    fun lightness(c: Int): Float {
        val r = toLinear[(c shr 16) and 255]; val g = toLinear[(c shr 8) and 255]; val b = toLinear[c and 255]
        val y = 0.2126729f * r + 0.7151522f * g + 0.0721750f * b
        return 116f * f(y) - 16f
    }

    /** sRGB (opaque bits only) of a Lab colour. */
    fun labToRgb(l: Float, a: Float, b: Float): Int {
        val fy = (l + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f
        val x = 0.95047f * fInv(fx); val y = fInv(fy); val z = 1.08883f * fInv(fz)
        val rl = 3.2404542f * x - 1.5371385f * y - 0.4985314f * z
        val gl = -0.9692660f * x + 1.8760108f * y + 0.0415560f * z
        val bl = 0.0556434f * x - 0.2040259f * y + 1.0572252f * z
        fun q(v: Float) = toSrgb[(v.coerceIn(0f, 1f) * GAMMA_STEPS).toInt()]
        return (q(rl) shl 16) or (q(gl) shl 8) or q(bl)
    }

    /**
     * Colorizes [src] with [predict], which gets the lightness of a [size] x [size] copy (row by
     * row, 0..100) and returns a and b for it (planar: all a, then all b). [intensity] scales
     * the predicted colour (1 = as predicted). Returns a new bitmap of [src]'s size.
     */
    fun colorizeWith(src: Bitmap, size: Int, intensity: Float, predict: (FloatArray) -> FloatArray): Bitmap {
        val small = Bitmap.createScaledBitmap(src, size, size, true)
        val sp = IntArray(size * size)
        small.getPixels(sp, 0, size, 0, 0, size, size)
        if (small !== src) small.recycle()
        val lSmall = FloatArray(size * size) { lightness(sp[it]) }
        val ab = predict(lSmall)
        val n2 = size * size
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val k = intensity.coerceIn(0f, 3f)
        ImageProcessor.parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                // Bilinear sample of the small colour map at this pixel's centre.
                val fy = ((y + 0.5f) * size / h - 0.5f).coerceIn(0f, size - 1f)
                val iy = fy.toInt().coerceAtMost(size - 2).coerceAtLeast(0); val ty = (fy - iy).coerceIn(0f, 1f)
                val iy1 = (iy + 1).coerceAtMost(size - 1)
                for (x in 0 until w) {
                    val i = y * w + x
                    val c = px[i]
                    val alpha = c ushr 24
                    if (alpha == 0) continue
                    val fx = ((x + 0.5f) * size / w - 0.5f).coerceIn(0f, size - 1f)
                    val ix = fx.toInt().coerceAtMost(size - 2).coerceAtLeast(0); val tx = (fx - ix).coerceIn(0f, 1f)
                    val ix1 = (ix + 1).coerceAtMost(size - 1)
                    fun bil(off: Int): Float {
                        val a00 = ab[off + iy * size + ix]; val a01 = ab[off + iy * size + ix1]
                        val a10 = ab[off + iy1 * size + ix]; val a11 = ab[off + iy1 * size + ix1]
                        val top = a00 + (a01 - a00) * tx; val bot = a10 + (a11 - a10) * tx
                        return top + (bot - top) * ty
                    }
                    val a = bil(0) * k; val b = bil(n2) * k
                    px[i] = (alpha shl 24) or labToRgb(lightness(c), a, b)
                }
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }

    /** Colorizes [src] with the installed model (see [ModelStore]). Runs on the calling thread. */
    fun colorize(context: Context, src: Bitmap, intensity: Float): Bitmap {
        val manifest = ModelStore.installed(context, MODEL) ?: throw IllegalStateException("Model not installed")
        val size = manifest.inputSize
        val interpreter = org.tensorflow.lite.Interpreter(
            File(ModelStore.modelFile(context, MODEL).absolutePath),
            org.tensorflow.lite.Interpreter.Options().setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        )
        try {
            val inShape = interpreter.getInputTensor(0).shape()
            val outShape = interpreter.getOutputTensor(0).shape()
            // Channels last (NHWC, from the TFLite converter) or first (NCHW).
            val outChannelsLast = outShape.size == 4 && outShape[3] == 2
            return colorizeWith(src, size, intensity) { l ->
                val input = ByteBuffer.allocateDirect(4 * l.size).order(ByteOrder.nativeOrder())
                // One channel: the same layout either way.
                for (v in l) input.putFloat(v)
                input.rewind()
                val out = ByteBuffer.allocateDirect(4 * 2 * l.size).order(ByteOrder.nativeOrder())
                if (inShape.size != 4) throw IllegalStateException("Unexpected model input")
                interpreter.run(input, out)
                out.rewind()
                val fb = out.asFloatBuffer()
                val n = l.size
                val ab = FloatArray(2 * n)
                if (outChannelsLast) {
                    for (i in 0 until n) { ab[i] = fb.get(2 * i); ab[n + i] = fb.get(2 * i + 1) }
                } else {
                    fb.get(ab)
                }
                ab
            }
        } finally {
            interpreter.close()
        }
    }
}
