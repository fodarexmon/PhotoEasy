package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.example.data.model.ColorAdjustments
import com.example.data.model.FilterType
import com.example.data.model.ProFilterType
import android.graphics.Color
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException

object ImageProcessor {

    /**
     * Retrieves the true original full dimensions (width, height) of an image with EXIF rotation taken into account.
     */
    fun getImageOriginalDimensions(context: Context, uri: Uri): Pair<Int, Int> {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            var w = options.outWidth
            var h = options.outHeight

            // Check EXIF rotation to swap dimensions if 90 or 270 degrees
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    val orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                    if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                        orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                        orientation == ExifInterface.ORIENTATION_TRANSVERSE
                    ) {
                        val tmp = w
                        w = h
                        h = tmp
                    }
                }
            } catch (_: Exception) {}

            if (w > 0 && h > 0) Pair(w, h) else Pair(1080, 1080)
        } catch (e: Exception) {
            Pair(1080, 1080)
        }
    }

    /**
     * Safely copies an external Uri media stream to a destination file without downsampling or loss.
     */
    fun copyUriToFile(context: Context, uri: Uri, destFile: File): Boolean {
        return try {
            destFile.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            destFile.exists() && destFile.length() > 0
        } catch (e: Exception) {
            Log.e("ImageProcessor", "Failed to copy Uri to file: ${e.message}")
            false
        }
    }

    /**
     * Safely decodes a downsampled Bitmap from Uri with EXIF orientation correction.
     * Guaranteed to close all streams and cleanup temporary file resources to prevent resource leaks.
     * Supports modern formats (JPEG, PNG, WebP, HEIF) via ImageDecoder on API 28+ with robust
     * BitmapFactory fallback.
     */
    fun decodeSampledBitmapFromUriWithExif(
        context: Context,
        uri: Uri,
        maxDim: Int = 2048
    ): Bitmap? {
        val tempFile = try {
            File.createTempFile("import_img_", ".tmp", context.cacheDir)
        } catch (e: Exception) {
            File(context.cacheDir, "import_fallback_${System.currentTimeMillis()}.tmp")
        }

        return try {
            var copied = false
            // 1. Try copying via contentResolver.openInputStream
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                    copied = tempFile.length() > 0
                }
            } catch (e: Exception) {
                Log.w("ImageProcessor", "openInputStream failed: ${e.message}")
            }

            // 2. Fallback: Try openFileDescriptor if direct stream failed
            if (!copied) {
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        copied = tempFile.length() > 0
                    }
                } catch (e: Exception) {
                    Log.w("ImageProcessor", "openFileDescriptor failed: ${e.message}")
                }
            }

            val fileToUse = if (copied && tempFile.exists() && tempFile.length() > 0) tempFile else null

            if (fileToUse != null) {
                // Modern ImageDecoder on API 28+ (handles EXIF orientation and modern image formats natively)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        val source = ImageDecoder.createSource(fileToUse)
                        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                            val origW = info.size.width
                            val origH = info.size.height
                            if (origW > maxDim || origH > maxDim) {
                                val ratio = maxDim.toFloat() / max(origW, origH).toFloat()
                                val targetW = (origW * ratio).toInt().coerceAtLeast(1)
                                val targetH = (origH * ratio).toInt().coerceAtLeast(1)
                                decoder.setTargetSize(targetW, targetH)
                            }
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                        if (decoded != null && !decoded.isRecycled && decoded.width > 0 && decoded.height > 0) {
                            val mutableCopy = if (!decoded.isMutable) {
                                val copy = decoded.copy(Bitmap.Config.ARGB_8888, true)
                                if (copy != null && copy !== decoded) decoded.recycle()
                                copy ?: decoded
                            } else decoded
                            return mutableCopy
                        }
                    } catch (e: Exception) {
                        Log.w("ImageProcessor", "ImageDecoder decode failed, falling back to BitmapFactory: ${e.message}")
                    }
                }

                // Standard BitmapFactory fallback
                val boundsOptions = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(fileToUse.absolutePath, boundsOptions)
                if (boundsOptions.outWidth > 0 && boundsOptions.outHeight > 0) {
                    val sampleSize = calculateInSampleSize(boundsOptions, maxDim)

                    var exifOrientation = ExifInterface.ORIENTATION_NORMAL
                    try {
                        val exif = ExifInterface(fileToUse.absolutePath)
                        exifOrientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    } catch (e: Exception) {
                        Log.w("ImageProcessor", "Exif read failed: ${e.message}")
                    }

                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inMutable = true
                    }
                    val rawBitmap = BitmapFactory.decodeFile(fileToUse.absolutePath, decodeOptions)
                    if (rawBitmap != null) {
                        return applyExifMatrix(rawBitmap, exifOrientation)
                    }
                }
            }

            // Direct stream fallback as last resort
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            } ?: return null

            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

            val sampleSize = calculateInSampleSize(boundsOptions, maxDim)
            var exifOrientation = ExifInterface.ORIENTATION_NORMAL
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    exifOrientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            } catch (e: Exception) {
                Log.w("ImageProcessor", "Exif stream failed: ${e.message}")
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            applyExifMatrix(rawBitmap, exifOrientation)
        } catch (e: Exception) {
            Log.e("ImageProcessor", "decodeSampledBitmapFromUriWithExif error: ${e.message}", e)
            null
        } finally {
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (_: Exception) {}
        }
    }

    fun applyExifMatrix(rawBitmap: Bitmap, exifOrientation: Int): Bitmap {
        val matrix = Matrix()
        when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }

        return if (!matrix.isIdentity) {
            val transformed = Bitmap.createBitmap(
                rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
            )
            if (transformed != rawBitmap) {
                rawBitmap.recycle()
            }
            transformed
        } else {
            rawBitmap
        }
    }

    fun decodeFileWithExifSampled(file: File, maxDim: Int): Bitmap? {
        if (!file.exists()) return null
        val safeMax = maxDim.coerceIn(64, 4096)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val src = ImageDecoder.createSource(file)
                val b = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    val ow = info.size.width; val oh = info.size.height
                    if (max(ow, oh) > safeMax) {
                        val r = safeMax.toFloat() / max(ow, oh).toFloat()
                        dec.setTargetSize((ow*r).toInt().coerceAtLeast(1), (oh*r).toInt().coerceAtLeast(1))
                    }
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                if (b.isMutable) b else { val copy=b.copy(Bitmap.Config.ARGB_8888,true); if(copy!=null && copy!==b)b.recycle(); copy?:b }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(bounds, safeMax)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                }
                val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
                val orientation = try { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
                applyExifMatrix(raw, orientation)
            }
        } catch (e: Exception) { Log.e("ImageProcessor", "sampled decode failed", e); null }
    }

    fun scaleBitmapToMaxDimension(source: Bitmap, maxDim: Int = 2048): Bitmap {
        if (source.width <= maxDim && source.height <= maxDim) return source.copy(Bitmap.Config.ARGB_8888, true)
        val r = maxDim.toFloat() / max(source.width, source.height)
        return Bitmap.createScaledBitmap(source, (source.width*r).toInt().coerceAtLeast(1), (source.height*r).toInt().coerceAtLeast(1), true)
    }

    fun saveLosslessBitmap(bitmap: Bitmap, file: File): Boolean = try {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out) else bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush(); out.fd.sync(); ok
        }
    } catch (e: Exception) { Log.e("ImageProcessor", "lossless save failed", e); false }

    /**
     * Calculates inSampleSize for BitmapFactory.Options to bound max image dimensions
     * and prevent OutOfMemoryError on high-res camera photos.
     */
    fun calculateInSampleSize(options: BitmapFactory.Options, maxDim: Int = 2048): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > maxDim || width > maxDim) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while ((halfHeight / inSampleSize) >= maxDim || (halfWidth / inSampleSize) >= maxDim) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * Applies color adjustments (brightness, contrast, saturation, temperature, exposure, highlights, shadows, sharpness, vignette)
     */
    fun applyAdjustments(
        source: Bitmap,
        adj: ColorAdjustments,
        isCancelled: (() -> Boolean)? = null
    ): Bitmap {
        if (adj.isIdentity()) return source
        val w = source.width
        val h = source.height
        val n = w * h
        if (n == 0) return source
        val px = IntArray(n)
        source.getPixels(px, 0, w, 0, 0, w, h)
        fun checkCancel() {
            if (isCancelled?.invoke() == true) throw CancellationException("Color adjustments cancelled")
        }

        // 1. Per-channel tone curves (white balance, exposure, white/black points, contrast,
        //    brightness), built once as float LUTs so nothing is clipped to 8 bits between steps.
        val lutR = FloatArray(256)
        val lutG = FloatArray(256)
        val lutB = FloatArray(256)
        run {
            val temp = adj.temperature / 100f
            val tint = adj.tint / 100f
            var gr = 1f + 0.18f * temp
            var gb = 1f - 0.18f * temp
            var gg = 1f + 0.12f * tint
            val norm = 0.2126f * gr + 0.7152f * gg + 0.0722f * gb // keep overall brightness
            gr /= norm; gg /= norm; gb /= norm
            val expScale = 2f.pow(adj.exposure / 50f)
            val bp = (-adj.blacks * 0.25f) / 255f
            val wp = 1f - (adj.whites * 0.25f) / 255f
            val contrastGain = 2f.pow(adj.contrast / 100f * 1.2f)
            val gamma = 2f.pow(-adj.brightness / 100f * 0.9f)
            for (v in 0 until 256) {
                val lin = srgbToLinearF(v / 255f)
                lutR[v] = toneCurve(lin * gr * expScale, bp, wp, contrastGain, gamma)
                lutG[v] = toneCurve(lin * gg * expScale, bp, wp, contrastGain, gamma)
                lutB[v] = toneCurve(lin * gb * expScale, bp, wp, contrastGain, gamma)
            }
        }

        // 2. Local luminance maps on a small grid (shadows / highlights mask, clarity base, haze).
        val needLocal = adj.shadows != 0f || adj.highlights != 0f || adj.clarity != 0f
        val step = max(1, kotlin.math.ceil(max(w, h) / 384.0).toInt())
        val gw = (w + step - 1) / step
        val gh = (h + step - 1) / step
        var toneGrid: FloatArray? = null
        var clarityGrid: FloatArray? = null
        var haze = 0f
        if (needLocal || adj.dehaze != 0f) {
            val lum = FloatArray(gw * gh)
            val minC = FloatArray(gw * gh)
            val cnt = IntArray(gw * gh)
            for (y in 0 until h) {
                if ((y and 63) == 0) checkCancel()
                val gy = (y / step) * gw
                val row = y * w
                for (x in 0 until w) {
                    val p = px[row + x]
                    if ((p ushr 24) == 0) continue
                    val r = lutR[(p shr 16) and 0xFF]
                    val g = lutG[(p shr 8) and 0xFF]
                    val b = lutB[p and 0xFF]
                    val gi = gy + x / step
                    lum[gi] += 0.2126f * r + 0.7152f * g + 0.0722f * b
                    minC[gi] += min(r, min(g, b))
                    cnt[gi]++
                }
            }
            for (i in lum.indices) if (cnt[i] > 0) { lum[i] /= cnt[i]; minC[i] /= cnt[i] }
            if (needLocal) {
                toneGrid = blurGrid(lum, gw, gh, max(2, max(gw, gh) / 28))
                if (adj.clarity != 0f) clarityGrid = blurGrid(lum, gw, gh, max(1, max(gw, gh) / 90))
            }
            if (adj.dehaze != 0f) {
                // Haze = the level the darkest parts of the scene are lifted to (dark channel, 10th percentile).
                val sorted = minC.filterIndexed { i, _ -> cnt[i] > 0 }.sorted()
                haze = if (sorted.isEmpty()) 0f else sorted[(sorted.size * 0.1f).toInt().coerceIn(0, sorted.size - 1)].coerceIn(0f, 0.4f)
            }
        }
        fun sampleGrid(grid: FloatArray, x: Int, y: Int): Float {
            val fx = ((x + 0.5f) / step - 0.5f).coerceIn(0f, (gw - 1).toFloat())
            val fy = ((y + 0.5f) / step - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val x1 = min(x0 + 1, gw - 1); val y1 = min(y0 + 1, gh - 1)
            val tx = fx - x0; val ty = fy - y0
            val a = grid[y0 * gw + x0] + (grid[y0 * gw + x1] - grid[y0 * gw + x0]) * tx
            val b = grid[y1 * gw + x0] + (grid[y1 * gw + x1] - grid[y1 * gw + x0]) * tx
            return a + (b - a) * ty
        }

        val sh = adj.shadows / 100f
        val hi = adj.highlights / 100f
        val cl = adj.clarity / 100f
        val dh = adj.dehaze / 100f
        val sat = adj.saturation / 100f + (if (dh > 0f) dh * 0.25f else dh * 0.15f)
        val vib = adj.vibrance / 100f
        val curves = if (adj.hasCurves()) arrayOf(
            curveValues(adj.curvesRgb), curveValues(adj.curvesRed), curveValues(adj.curvesGreen), curveValues(adj.curvesBlue)
        ) else null
        val bands = adj.effectiveHslBands().filterValues { !it.isIdentity() }
        val bandValues = com.example.data.model.HslRanges.keys.map { bands[it] }
        val bandWidths = FloatArray(bandValues.size) { (bandValues[it]?.width ?: 1f).coerceIn(0.3f, 3f) }
        val anyRangeBand = bandValues.any { it != null }
        val allBand = bands["ALL"]
        // Colorize target in Lab: hue angle of the chosen colour, chroma from the strength.
        val colorizeLab = FloatArray(3).also {
            val c = Color.HSVToColor(floatArrayOf(((adj.colorizeHue % 360f) + 360f) % 360f, 1f, 1f))
            rgbToLabF(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, it)
        }
        val colorizeAngle = kotlin.math.atan2(colorizeLab[2], colorizeLab[1])
        val colorizeChroma = adj.colorizeSaturation.coerceIn(0f, 100f) / 100f * 70f
        val hasHsl = bands.isNotEmpty() || adj.colorizeEnabled
        // Colour replacements, matched and transferred in CIE Lab (perceptual).
        val replaceSpecs = adj.effectiveReplacements().filter { it.enabled }.map { ReplaceSpec.of(it) }
        val previewMask = adj.replacePreviewMask
        val lab = FloatArray(3)
        val hsv = FloatArray(3)
        val rgb = FloatArray(3)

        for (y in 0 until h) {
            if ((y and 31) == 0) checkCancel()
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val p = px[i]
                val a = p ushr 24
                if (a == 0) continue
                var r = lutR[(p shr 16) and 0xFF]
                var g = lutG[(p shr 8) and 0xFF]
                var b = lutB[p and 0xFF]

                // Shadows / highlights / clarity work on luminance and keep the colour.
                if (needLocal) {
                    val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    var nl = l
                    val base = toneGrid?.let { sampleGrid(it, x, y) } ?: l
                    if (sh != 0f) {
                        val ws = 1f - smoothstepF(0f, 0.55f, base)
                        nl += sh * 0.6f * ws * (1f - nl) * sqrt(nl.coerceAtLeast(0f))
                    }
                    if (hi != 0f) {
                        val wh = smoothstepF(0.4f, 1f, base)
                        nl += hi * 0.6f * wh * nl * sqrt((1f - nl).coerceAtLeast(0f) + 0.05f)
                    }
                    if (cl != 0f) {
                        val detail = l - (clarityGrid?.let { sampleGrid(it, x, y) } ?: l)
                        nl += cl * 1.2f * detail * (4f * nl * (1f - nl)).coerceIn(0f, 1f)
                    }
                    nl = nl.coerceIn(0f, 1f)
                    if (l > 0.02f) {
                        val k = nl / l
                        r *= k; g *= k; b *= k
                    } else {
                        val d = nl - l
                        r += d; g += d; b += d
                    }
                }

                if (dh > 0f && haze > 0f) {
                    val hz = dh * haze
                    val inv = 1f / (1f - hz)
                    r = (r - hz) * inv; g = (g - hz) * inv; b = (b - hz) * inv
                } else if (dh < 0f) {
                    val k = -dh * 0.3f
                    r += (0.85f - r) * k; g += (0.85f - g) * k; b += (0.85f - b) * k
                }

                // Saturation (luminance preserving) and vibrance (boosts muted colours, protects skin).
                if (sat != 0f || vib != 0f) {
                    val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    var k = 1f + sat
                    if (vib != 0f) {
                        val mx = max(r, max(g, b))
                        val mn = min(r, min(g, b))
                        val chroma = (mx - mn).coerceIn(0f, 1f)
                        var vw = (1f - chroma).let { it * it }
                        if (vib > 0f && r >= g && g >= b && chroma > 0.05f) {
                            // Skin tones (orange hues): gentler.
                            val hue = 60f * (g - b) / (mx - mn + 1e-6f)
                            if (hue < 50f) vw *= 0.45f
                        }
                        k += vib * vw
                    }
                    k = k.coerceAtLeast(0f)
                    r = l + (r - l) * k; g = l + (g - l) * k; b = l + (b - l) * k
                }

                r = r.coerceIn(0f, 1f); g = g.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)

                if (curves != null) {
                    r = curveAt(curves[1], curveAt(curves[0], r))
                    g = curveAt(curves[2], curveAt(curves[0], g))
                    b = curveAt(curves[3], curveAt(curves[0], b))
                }

                if (hasHsl) {
                    rgbToHsvF(r, g, b, hsv)
                    var lightT = 0f
                    var hueT = 0f
                    var satT = 0f
                    if (anyRangeBand) {
                        // Eight ranges with smooth (partition-of-unity) weights; greys are left alone.
                        val chromaW = smoothstepF(0.02f, 0.18f, hsv[1])
                        if (chromaW > 0f) {
                            val ws = com.example.data.model.HslRanges.weights(hsv[0], bandWidths)
                            for (k in ws.indices) {
                                val band = bandValues[k] ?: continue
                                val wgt = ws[k] * chromaW
                                if (wgt <= 0f) continue
                                // Range hue shifts are limited so neighbouring tones never tear apart.
                                hueT += band.hue.coerceIn(-90f, 90f) * wgt
                                satT += band.saturation / 100f * wgt
                                lightT += band.lightness / 100f * wgt
                            }
                        }
                    }
                    allBand?.let {
                        hueT += it.hue
                        satT += it.saturation / 100f
                        lightT += it.lightness / 100f
                    }
                    if (hueT != 0f || satT != 0f) {
                        hsv[0] = ((hsv[0] + hueT) % 360f + 360f) % 360f
                        hsv[1] = (hsv[1] * (1f + satT)).coerceIn(0f, 1f)
                        hsvToRgbF(hsv[0], hsv[1], hsv[2], rgb)
                        r = rgb[0]; g = rgb[1]; b = rgb[2]
                    }
                    if (lightT != 0f || adj.colorizeEnabled) {
                        // Lightness changes perceived brightness only (Lab L), keeping hue and
                        // colourfulness: a darker blue sky stays blue instead of turning grey.
                        rgbToLabF(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), lab)
                        var l = lab[0]
                        var la = lab[1]
                        var lb = lab[2]
                        if (lightT != 0f) {
                            val lt = lightT.coerceIn(-1f, 1f)
                            l = if (lt > 0f) l + (100f - l) * lt * 0.6f else l * (1f + lt * 0.6f)
                        }
                        if (adj.colorizeEnabled) {
                            la = colorizeChroma * kotlin.math.cos(colorizeAngle)
                            lb = colorizeChroma * kotlin.math.sin(colorizeAngle)
                            val cl = adj.colorizeLightness / 100f
                            l = if (cl > 0f) l + (100f - l) * cl * 0.6f else l * (1f + cl * 0.6f)
                        }
                        labToRgbF(l.coerceIn(0f, 100f), la, lb, rgb)
                        r = rgb[0]; g = rgb[1]; b = rgb[2]
                    }
                }

                if (replaceSpecs.isNotEmpty() || previewMask) {
                    r = r.coerceIn(0f, 1f); g = g.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
                    rgbToLabF(r, g, b, lab)
                    val pl = lab[0]
                    val pa = lab[1]
                    val pb = lab[2]
                    val pc = sqrt(pa * pa + pb * pb)
                    var nl = pl
                    var na = pa
                    var nb = pb
                    var maxW = 0f
                    for (spec in replaceSpecs) {
                        // Lightness counts half: the same fabric in light and shadow still matches.
                        val dl = (pl - spec.l) * 0.5f
                        val da = pa - spec.a
                        val db = pb - spec.b
                        val d = sqrt(dl * dl + da * da + db * db)
                        var wgt = 1f - smoothstepF(spec.inner, spec.outer, d)
                        // Greys and near-blacks have no reliable hue: leave them alone (unless a grey was picked).
                        if (spec.protectGray) wgt *= smoothstepF(3f, 12f, pc)
                        if (wgt <= 0.001f) continue
                        if (wgt > maxW) maxW = wgt
                        // Keep the pixel's own shading and colourfulness relative to the source,
                        // take the target's hue / colourfulness and its brightness offset.
                        val k = if (spec.srcChroma > 2f) (pc / spec.srcChroma).coerceIn(0f, 2.5f) else 1f
                        val ta = spec.ta * k * spec.satK
                        val tb = spec.tb * k * spec.satK
                        val tl = (pl + (spec.tl - spec.l) + spec.lightAdd).coerceIn(0f, 100f)
                        nl += (tl - nl) * wgt
                        na += (ta - na) * wgt
                        nb += (tb - nb) * wgt
                    }
                    if (previewMask) {
                        r = maxW; g = maxW; b = maxW
                    } else if (maxW > 0f) {
                        labToRgbF(nl, na, nb, rgb)
                        r = rgb[0]; g = rgb[1]; b = rgb[2]
                    }
                }

                val ri = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
                val gi = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bi = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
                px[i] = (a shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }

        if (adj.sharpness > 0f) sharpenLuminance(px, w, h, adj.sharpness, ::checkCancel)
        if (adj.vignette != 0f) applyVignettePixels(px, w, h, adj.vignette, ::checkCancel)

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        output.setPixels(px, 0, w, 0, 0, w, h)
        return output
    }

    /** HSL range weights for a colour (targeted adjustment): keys of [com.example.data.model.HslRanges]. */
    fun hslRangeWeights(color: Int): Map<String, Float> {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[1] < 0.05f) return emptyMap()
        val ws = com.example.data.model.HslRanges.weights(hsv[0])
        return com.example.data.model.HslRanges.keys.indices.filter { ws[it] > 0.01f }
            .associate { com.example.data.model.HslRanges.keys[it] to ws[it] }
    }

    /** One colour replacement prepared for the pixel loop (Lab values, thresholds). */
    private class ReplaceSpec(
        val l: Float, val a: Float, val b: Float, val srcChroma: Float,
        val tl: Float, val ta: Float, val tb: Float,
        val inner: Float, val outer: Float, val protectGray: Boolean,
        val satK: Float, val lightAdd: Float
    ) {
        companion object {
            fun of(c: com.example.data.model.ColorReplacement): ReplaceSpec {
                val src = FloatArray(3)
                val tgt = FloatArray(3)
                ImageProcessor.rgbToLabF(((c.sourceColor shr 16) and 0xFF) / 255f, ((c.sourceColor shr 8) and 0xFF) / 255f, (c.sourceColor and 0xFF) / 255f, src)
                ImageProcessor.rgbToLabF(((c.targetColor shr 16) and 0xFF) / 255f, ((c.targetColor shr 8) and 0xFF) / 255f, (c.targetColor and 0xFF) / 255f, tgt)
                val t = c.tolerance.coerceIn(1f, 100f) * 1.2f
                val soft = 0.1f + c.softness.coerceIn(0f, 100f) / 100f * 0.8f
                val srcChroma = sqrt(src[1] * src[1] + src[2] * src[2])
                return ReplaceSpec(
                    src[0], src[1], src[2], srcChroma,
                    tgt[0], tgt[1], tgt[2],
                    t * (1f - soft), t * (1f + soft), srcChroma > 8f,
                    (1f + c.saturation / 100f).coerceAtLeast(0f), c.lightness / 100f * 40f
                )
            }
        }
    }

    private val srgbToLinearLut = FloatArray(1025) { i ->
        val c = i / 1024f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    private fun srgbToLinearFast(c: Float): Float {
        val x = c.coerceIn(0f, 1f) * 1024f
        val i = x.toInt().coerceAtMost(1023)
        return srgbToLinearLut[i] + (srgbToLinearLut[i + 1] - srgbToLinearLut[i]) * (x - i)
    }

    private fun labFwd(t: Float): Float = if (t > 0.008856f) Math.cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f
    private fun labInv(t: Float): Float { val t3 = t * t * t; return if (t3 > 0.008856f) t3 else (t - 16f / 116f) / 7.787f }

    /** sRGB (0..1) to CIE Lab (D65): L 0..100, a / b about -128..127. */
    internal fun rgbToLabF(r: Float, g: Float, b: Float, out: FloatArray) {
        val rl = srgbToLinearFast(r)
        val gl = srgbToLinearFast(g)
        val bl = srgbToLinearFast(b)
        val fx = labFwd((0.4124f * rl + 0.3576f * gl + 0.1805f * bl) / 0.95047f)
        val fy = labFwd(0.2126f * rl + 0.7152f * gl + 0.0722f * bl)
        val fz = labFwd((0.0193f * rl + 0.1192f * gl + 0.9505f * bl) / 1.08883f)
        out[0] = 116f * fy - 16f
        out[1] = 500f * (fx - fy)
        out[2] = 200f * (fy - fz)
    }

    /** CIE Lab (D65) back to sRGB 0..1 (clamped). */
    internal fun labToRgbF(l: Float, a: Float, b: Float, out: FloatArray) {
        val fy = (l + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f
        val x = 0.95047f * labInv(fx)
        val y = labInv(fy)
        val z = 1.08883f * labInv(fz)
        out[0] = linearToSrgbF(3.2406f * x - 1.5372f * y - 0.4986f * z)
        out[1] = linearToSrgbF(-0.9689f * x + 1.8758f * y + 0.0415f * z)
        out[2] = linearToSrgbF(0.0557f * x - 0.2040f * y + 1.0570f * z)
    }

    private fun srgbToLinearF(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    private fun linearToSrgbF(c: Float): Float {
        val v = c.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) v * 12.92f else (1.055 * v.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()
    }

    private fun smoothstepF(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * One channel of the tone pipeline: linear value -> sRGB -> white/black points -> contrast
     * S-curve (end points fixed) -> brightness as a mid-tone gamma (black and white unchanged).
     */
    private fun toneCurve(linear: Float, bp: Float, wp: Float, contrastGain: Float, gamma: Float): Float {
        var x = linearToSrgbF(linear)
        x = ((x - bp) / (wp - bp).coerceAtLeast(0.05f)).coerceIn(0f, 1f)
        if (contrastGain != 1f) {
            x = if (x < 0.5f) 0.5f * (2f * x).pow(contrastGain) else 1f - 0.5f * (2f - 2f * x).pow(contrastGain)
        }
        if (gamma != 1f) x = x.pow(gamma)
        return x.coerceIn(0f, 1f)
    }

    private fun curveAt(lut: FloatArray, v: Float): Float {
        val f = v.coerceIn(0f, 1f) * 255f
        val i = f.toInt().coerceAtMost(254)
        val t = f - i
        return (lut[i] + (lut[i + 1] - lut[i]) * t) / 255f
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    private fun rgbToHsvF(r: Float, g: Float, b: Float, out: FloatArray) {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        var hue = 0f
        if (d > 1e-6f) {
            hue = when (mx) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            if (hue < 0f) hue += 360f
        }
        out[0] = hue
        out[1] = if (mx > 1e-6f) d / mx else 0f
        out[2] = mx
    }

    private fun hsvToRgbF(hue: Float, s: Float, v: Float, out: FloatArray) {
        val c = v * s
        val hp = (hue % 360f) / 60f
        val xx = c * (1f - abs(hp % 2f - 1f))
        val m = v - c
        val (r, g, b) = when (hp.toInt()) {
            0 -> Triple(c, xx, 0f)
            1 -> Triple(xx, c, 0f)
            2 -> Triple(0f, c, xx)
            3 -> Triple(0f, xx, c)
            4 -> Triple(xx, 0f, c)
            else -> Triple(c, 0f, xx)
        }
        out[0] = r + m; out[1] = g + m; out[2] = b + m
    }

    /** Separable box blur (3 passes ~ Gaussian) of a small float grid. */
    private fun blurGrid(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        var a = src.copyOf()
        var b = FloatArray(a.size)
        repeat(3) {
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    var sum = 0f
                    var c = 0
                    for (k in -radius..radius) {
                        val xx = x + k
                        if (xx in 0 until w) { sum += a[row + xx]; c++ }
                    }
                    b[row + x] = sum / c
                }
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0f
                    var c = 0
                    for (k in -radius..radius) {
                        val yy = y + k
                        if (yy in 0 until h) { sum += b[yy * w + x]; c++ }
                    }
                    a[y * w + x] = sum / c
                }
            }
        }
        return a
    }

    /**
     * Unsharp mask on luminance only (no colour fringes). The radius scales with the image so the
     * downscaled preview looks like the full-resolution export; a small threshold keeps flat areas
     * (sky, skin) from turning noisy.
     */
    private fun sharpenLuminance(px: IntArray, w: Int, h: Int, sharpness: Float, checkCancel: () -> Unit) {
        val radius = max(1, (max(w, h) / 1600f).roundToIntCompat())
        val amount = (sharpness / 100f) * 1.6f
        val n = w * h
        val lum = IntArray(n)
        for (i in 0 until n) {
            val p = px[i]
            lum[i] = (((p shr 16) and 0xFF) * 54 + ((p shr 8) and 0xFF) * 183 + (p and 0xFF) * 19) shr 8
        }
        val tmp = IntArray(n)
        val blur = IntArray(n)
        // Horizontal then vertical box blur.
        for (y in 0 until h) {
            if ((y and 63) == 0) checkCancel()
            val row = y * w
            for (x in 0 until w) {
                var s = 0; var c = 0
                for (k in -radius..radius) { val xx = x + k; if (xx in 0 until w) { s += lum[row + xx]; c++ } }
                tmp[row + x] = s / c
            }
        }
        for (y in 0 until h) {
            if ((y and 63) == 0) checkCancel()
            for (x in 0 until w) {
                var s = 0; var c = 0
                for (k in -radius..radius) { val yy = y + k; if (yy in 0 until h) { s += tmp[yy * w + x]; c++ } }
                blur[y * w + x] = s / c
            }
        }
        val threshold = 2
        for (i in 0 until n) {
            val p = px[i]
            if ((p ushr 24) == 0) continue
            val d = lum[i] - blur[i]
            if (abs(d) <= threshold) continue
            val add = (d * amount).toInt()
            val r = (((p shr 16) and 0xFF) + add).coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) + add).coerceIn(0, 255)
            val b = ((p and 0xFF) + add).coerceIn(0, 255)
            px[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun Float.roundToIntCompat(): Int = kotlin.math.round(this).toInt()

    /**
     * Elliptical vignette centred on the image. Positive [amount] darkens the edges, negative
     * lightens them; the centre is untouched and the transition is smooth.
     */
    private fun applyVignettePixels(px: IntArray, w: Int, h: Int, amount: Float, checkCancel: () -> Unit) {
        val cx = w / 2f
        val cy = h / 2f
        val strength = (amount / 100f).coerceIn(-1f, 1f) * 0.9f
        for (y in 0 until h) {
            if ((y and 63) == 0) checkCancel()
            val dy = (y + 0.5f - cy) / cy
            for (x in 0 until w) {
                val i = y * w + x
                val p = px[i]
                if ((p ushr 24) == 0) continue
                val dx = (x + 0.5f - cx) / cx
                val e = sqrt(dx * dx + dy * dy)
                val wgt = smoothstepF(0.45f, 1.45f, e)
                if (wgt <= 0f) continue
                var r = ((p shr 16) and 0xFF) / 255f
                var g = ((p shr 8) and 0xFF) / 255f
                var b = (p and 0xFF) / 255f
                if (strength > 0f) {
                    val k = 1f - strength * wgt
                    r *= k; g *= k; b *= k
                } else {
                    val k = -strength * wgt
                    r += (1f - r) * k; g += (1f - g) * k; b += (1f - b) * k
                }
                px[i] = (p and 0xFF000000.toInt()) or
                    ((r * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    ((g * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                    (b * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
        }
    }

    /**
     * Smart Auto: analyses the image AS IT LOOKS NOW (with [current] adjustments) and returns the
     * extra corrections it still needs, as deltas to add to [current]. Measures the histogram
     * (black / white points, median, spread, clipping), white balance from neutral-ish pixels,
     * colourfulness and noise, and only corrects what is actually off. Transparent pixels are
     * ignored. Running it again on a corrected image changes little.
     */
    fun computeSmartAutoAdjustments(source: Bitmap, current: ColorAdjustments = ColorAdjustments()): ColorAdjustments {
        val thumb = scaleBitmapToMaxDimension(source, 512)
        val look = applyAdjustments(thumb, current)
        val w = look.width
        val h = look.height
        val px = IntArray(w * h)
        look.getPixels(px, 0, w, 0, 0, w, h)
        if (look !== thumb && !look.isRecycled) look.recycle()
        if (thumb !== source && !thumb.isRecycled) thumb.recycle()

        val hist = IntArray(256)
        val luma = IntArray(px.size) { -1 }
        var count = 0
        var sumL = 0.0
        var sumL2 = 0.0
        var nR = 0.0; var nG = 0.0; var nB = 0.0; var nCount = 0
        var chromaSum = 0.0
        for (i in px.indices) {
            val p = px[i]
            if (((p ushr 24) and 0xFF) < 128) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = (r * 54 + g * 183 + b * 19) shr 8
            luma[i] = l
            hist[l]++
            count++
            sumL += l
            sumL2 += l.toDouble() * l
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            chromaSum += (mx - mn) / 255.0
            // Neutral candidates for white balance: mid-tones that are not strongly coloured.
            if (l in 40..225 && (mx - mn) < 70) {
                nR += r; nG += g; nB += b; nCount++
            }
        }
        if (count < 16) return ColorAdjustments()

        fun percentile(q: Double): Int {
            val target = (count * q).toLong()
            var acc = 0L
            for (v in 0 until 256) {
                acc += hist[v]
                if (acc > target) return v
            }
            return 255
        }
        val p005 = percentile(0.005)
        val p995 = percentile(0.995)
        val median = percentile(0.5).coerceAtLeast(1)
        val mean = sumL / count
        val std = sqrt((sumL2 / count - mean * mean).coerceAtLeast(0.0)).toFloat()
        val meanChroma = (chromaSum / count).toFloat()
        val clippedHigh = hist[255].toFloat() / count + hist[254].toFloat() / count
        val clippedLow = hist[0].toFloat() / count + hist[1].toFloat() / count
        val brightFrac = (230..255).sumOf { hist[it] }.toFloat() / count
        val darkFrac = (0..35).sumOf { hist[it] }.toFloat() / count

        // Noise: average absolute Laplacian of luminance in flat-ish areas.
        var noiseSum = 0.0
        var noiseN = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val c = luma[i]
                if (c < 0) continue
                val l = luma[i - 1]; val r = luma[i + 1]; val t = luma[i - w]; val b = luma[i + w]
                if (l < 0 || r < 0 || t < 0 || b < 0) continue
                val lap = abs(4 * c - l - r - t - b)
                if (lap < 40) { noiseSum += lap; noiseN++ }
            }
        }
        val noise = if (noiseN > 0) (noiseSum / noiseN).toFloat() else 0f

        // Exposure: bring the median towards a pleasant mid-grey without clipping highlights.
        var ev = 0f
        val ratio = 118f / median
        if (ratio > 1.15f || ratio < 0.87f) {
            ev = (kotlin.math.ln(ratio.toDouble()) / kotlin.math.ln(2.0)).toFloat() * 0.7f
            if (ev > 0f && p995 > 0) ev = min(ev, (kotlin.math.ln(250.0 / p995) / kotlin.math.ln(2.0)).toFloat().coerceAtLeast(0f) + 0.15f)
            ev = ev.coerceIn(-1f, 1.2f)
        }
        val exposure = (ev * 50f).coerceIn(-60f, 60f)

        // Black / white points.
        val blacks = when {
            p005 > 10 -> -((p005 - 4) * 4f).coerceAtMost(60f)
            clippedLow > 0.03f -> 15f
            else -> 0f
        }
        val whites = when {
            p995 < 240 && ev <= 0.2f -> ((248 - p995) * 3f).coerceIn(0f, 50f)
            clippedHigh > 0.03f -> -15f
            else -> 0f
        }

        // Contrast from the spread of tones.
        val contrast = when {
            std < 42f -> ((48f - std) * 1.1f).coerceIn(0f, 28f)
            std > 75f -> -((std - 75f) * 0.6f).coerceAtMost(15f)
            else -> 0f
        }
        val highlights = if (brightFrac > 0.08f) -(brightFrac * 220f).coerceAtMost(45f) else 0f
        val shadows = if (darkFrac > 0.15f) (darkFrac * 120f).coerceAtMost(40f) else 0f

        // White balance from neutral-ish pixels (60% strength keeps intentional mood).
        var temperature = 0f
        var tint = 0f
        if (nCount > count * 0.05f) {
            val ar = (nR / nCount).toFloat().coerceAtLeast(1f)
            val ag = (nG / nCount).toFloat().coerceAtLeast(1f)
            val ab = (nB / nCount).toFloat().coerceAtLeast(1f)
            val q = ab / ar
            val x = (q - 1f) / (q + 1f)
            temperature = (x / 0.18f * 100f * 0.6f).coerceIn(-40f, 40f)
            val gg = ((ar + ab) / 2f) / ag
            tint = ((gg - 1f) / 0.12f * 100f * 0.6f).coerceIn(-30f, 30f)
            if (abs(temperature) < 3f) temperature = 0f
            if (abs(tint) < 3f) tint = 0f
        }

        // Colour: only muted images get vibrance; already colourful ones are left alone.
        val vibrance = if (meanChroma < 0.2f) ((0.24f - meanChroma) * 120f).coerceIn(0f, 30f) else 0f
        // Haze: lifted blacks together with weak colour.
        val dehaze = if (p005 > 35 && meanChroma < 0.18f) ((p005 - 30) * 0.8f).coerceIn(0f, 30f) else 0f
        // Detail: sharpen / clarity only when the image is clean.
        val sharpness = when {
            noise < 3f -> 20f
            noise < 6f -> 10f
            else -> 0f
        }
        val clarity = if (noise < 4f && std < 60f) 10f else 0f

        return ColorAdjustments(
            exposure = exposure,
            contrast = contrast,
            highlights = highlights,
            shadows = shadows,
            whites = whites,
            blacks = blacks,
            temperature = temperature,
            tint = tint,
            vibrance = vibrance,
            dehaze = dehaze,
            sharpness = sharpness,
            clarity = clarity
        )
    }

    /** [base] plus [delta] x [strength] for the fields Smart Auto sets (curves / HSL untouched). */
    fun combineSmartAuto(base: ColorAdjustments, delta: ColorAdjustments, strength: Float): ColorAdjustments {
        // Signed: a negative strength change removes part of an earlier Smart Auto.
        val s = strength.coerceIn(-1.5f, 1.5f)
        fun mix(b: Float, d: Float, lo: Float = -100f, hi: Float = 100f) = (b + d * s).coerceIn(lo, hi)
        return base.copy(
            exposure = mix(base.exposure, delta.exposure),
            contrast = mix(base.contrast, delta.contrast),
            highlights = mix(base.highlights, delta.highlights),
            shadows = mix(base.shadows, delta.shadows),
            whites = mix(base.whites, delta.whites),
            blacks = mix(base.blacks, delta.blacks),
            temperature = mix(base.temperature, delta.temperature),
            tint = mix(base.tint, delta.tint),
            vibrance = mix(base.vibrance, delta.vibrance),
            dehaze = mix(base.dehaze, delta.dehaze),
            clarity = mix(base.clarity, delta.clarity),
            sharpness = mix(base.sharpness, delta.sharpness, 0f, 100f)
        )
    }

    /**
     * Applies preset filters with variable intensity (0.0 to 1.0)
     */
    fun applyFilter(
        source: Bitmap,
        filter: FilterType,
        intensity: Float = 1.0f,
        isCancelled: (() -> Boolean)? = null
    ): Bitmap {
        if (filter == FilterType.NONE || intensity <= 0.001f) return source
        val rawFiltered = applyFilterFull(source, filter, isCancelled)
        return mixWithOriginal(source, rawFiltered, intensity)
    }

    /** Applies a 3D LUT (.cube) with the given intensity. */
    fun applyLut(source: Bitmap, lut: CubeLut, intensity: Float = 1f, isCancelled: (() -> Boolean)? = null): Bitmap {
        if (intensity <= 0.001f) return source
        val w = source.width
        val h = source.height
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, 0, 0, w, h)
        lut.applyInPlace(px, isCancelled)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return mixWithOriginal(source, out, intensity)
    }

    /**
     * Intensity mix: per-pixel interpolation of the colour channels, keeping the ORIGINAL alpha.
     * (Drawing the filtered image with partial opacity over the original made semi-transparent
     * layers more opaque.) Recycles [filtered] when a new bitmap is returned.
     */
    private fun mixWithOriginal(source: Bitmap, filtered: Bitmap, intensity: Float): Bitmap {
        val t = intensity.coerceIn(0f, 1f)
        if (filtered === source) return source
        val w = source.width
        val h = source.height
        val sp = IntArray(w * h)
        val fp = IntArray(w * h)
        source.getPixels(sp, 0, w, 0, 0, w, h)
        filtered.getPixels(fp, 0, w, 0, 0, w, h)
        val inv = 1f - t
        for (i in sp.indices) {
            val s = sp[i]
            val f = fp[i]
            val a = (s ushr 24) and 0xFF
            if (t >= 0.999f) {
                sp[i] = (a shl 24) or (f and 0x00FFFFFF)
                continue
            }
            val r = (((s shr 16) and 0xFF) * inv + ((f shr 16) and 0xFF) * t).toInt()
            val g = (((s shr 8) and 0xFF) * inv + ((f shr 8) and 0xFF) * t).toInt()
            val b = ((s and 0xFF) * inv + (f and 0xFF) * t).toInt()
            sp[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = if (filtered.isMutable && filtered.width == w && filtered.height == h && filtered.config == Bitmap.Config.ARGB_8888) {
            filtered
        } else {
            if (!filtered.isRecycled) filtered.recycle()
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        out.setPixels(sp, 0, w, 0, 0, w, h)
        return out
    }

    private fun applyFilterFull(
        source: Bitmap,
        filter: FilterType,
        isCancelled: (() -> Boolean)? = null
    ): Bitmap {
        if (filter == FilterType.NONE) return source
        // Looks built from tone curves / split toning / grain (see FilterRecipes).
        FilterRecipes.recipeFor(filter)?.let { return FilterRecipes.render(source, it, isCancelled) }

        val width = source.width
        val height = source.height
        val maxDim = max(width, height)
        return when (filter) {
            FilterType.PIXELATE -> {
                // Block size relative to the image: preview and export show the same mosaic.
                val pixelSize = max(2, (maxDim / 90f).toInt())
                val smallW = max(1, width / pixelSize)
                val smallH = max(1, height / pixelSize)
                val small = Bitmap.createScaledBitmap(source, smallW, smallH, false)
                val big = Bitmap.createScaledBitmap(small, width, height, false)
                if (small !== big && !small.isRecycled) small.recycle()
                big
            }
            FilterType.EDGES -> {
                val pixels = IntArray(width * height)
                val outPixels = IntArray(width * height)
                source.getPixels(pixels, 0, width, 0, 0, width, height)
                // Sobel on luminance, sampled with a step relative to the image size and normalised,
                // so line weight and strength match between preview and export.
                val step = max(1, (maxDim / 1400f).toInt())
                val lum = FloatArray(width * height)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    lum[i] = 0.2126f * ((p ushr 16) and 0xFF) + 0.7152f * ((p ushr 8) and 0xFF) + 0.0722f * (p and 0xFF)
                }
                for (y in 0 until height) {
                    if (y % 32 == 0 && isCancelled?.invoke() == true) throw CancellationException("Edges filter cancelled")
                    for (x in 0 until width) {
                        val idx = y * width + x
                        val origA = (pixels[idx] ushr 24) and 0xFF
                        if (origA == 0) continue
                        val x0 = max(0, x - step); val x1 = min(width - 1, x + step)
                        val y0 = max(0, y - step); val y1 = min(height - 1, y + step)
                        val p00 = lum[y0 * width + x0]; val p01 = lum[y0 * width + x]; val p02 = lum[y0 * width + x1]
                        val p10 = lum[y * width + x0]; val p12 = lum[y * width + x1]
                        val p20 = lum[y1 * width + x0]; val p21 = lum[y1 * width + x]; val p22 = lum[y1 * width + x1]
                        val gx = (p02 + 2f * p12 + p22) - (p00 + 2f * p10 + p20)
                        val gy = (p20 + 2f * p21 + p22) - (p00 + 2f * p01 + p02)
                        val edgeVal = (sqrt(gx * gx + gy * gy) * 0.6f).toInt().coerceIn(0, 255)
                        outPixels[idx] = (origA shl 24) or (edgeVal shl 16) or (edgeVal shl 8) or edgeVal
                    }
                }
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.setPixels(outPixels, 0, width, 0, 0, width, height) }
            }
            FilterType.GLITCH -> {
                val offset = max(1, (width * 0.02f).toInt())
                val band = max(2, (maxDim / 160f).toInt())
                val glitchPixels = IntArray(width * height)
                source.getPixels(glitchPixels, 0, width, 0, 0, width, height)
                val outPixels = glitchPixels.copyOf()
                var y = 0
                while (y < height) {
                    if (isCancelled?.invoke() == true) throw CancellationException("Glitch filter cancelled")
                    val rowShift = if ((y / band) % 2 == 0) offset else -offset
                    val rowHeight = min(band * 3 / 4, height - y)
                    for (ry in y until (y + rowHeight)) {
                        for (x in 0 until width) {
                            val origColor = glitchPixels[ry * width + x]
                            val origA = (origColor ushr 24) and 0xFF
                            if (origA == 0) continue
                            val shiftedColor = glitchPixels[ry * width + (x + rowShift).coerceIn(0, width - 1)]
                            outPixels[ry * width + x] = (origA shl 24) or (((shiftedColor shr 16) and 0xFF) shl 16) or (origColor and 0x0000FFFF)
                        }
                    }
                    y += band
                }
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.setPixels(outPixels, 0, width, 0, 0, width, height) }
            }
            FilterType.SOLARIZE -> {
                // Classic Sabattier solarisation: tones above mid-grey are inverted, smoothly
                // (no discontinuity at 128 like the previous formula).
                val pixels = IntArray(width * height)
                source.getPixels(pixels, 0, width, 0, 0, width, height)
                val lut = IntArray(256) { v -> if (v <= 128) v else 255 - v + (v - 128) / 8 }
                for (i in pixels.indices) {
                    val p = pixels[i]
                    val a = (p ushr 24) and 0xFF
                    if (a == 0) continue
                    pixels[i] = (a shl 24) or (lut[(p shr 16) and 0xFF] shl 16) or (lut[(p shr 8) and 0xFF] shl 8) or lut[p and 0xFF]
                }
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.setPixels(pixels, 0, width, 0, 0, width, height) }
            }
            else -> source
        }
    }

    /**
     * Unwarps a perspective quadrilateral into a flat rectangular bitmap.
     * Takes 4 source corners [top-left, top-right, bottom-right, bottom-left]
     * and maps them to a rectangular output of size destWidth x destHeight.
     */
    // ---------------------------------------------------------------------------------------------
    // Perspective helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Real width / height ratio of the rectangle photographed as the quad [corners] (TL, TR, BR, BL,
     * document pixels), assuming the optical centre is the image centre (Zhang & He, "Whiteboard
     * scanning"). Taking the longest sides instead stretches or squashes documents shot at an
     * angle. Returns null when the quad is degenerate.
     */
    fun estimateRectAspect(corners: List<PointF>, docW: Int, docH: Int): Float? {
        if (corners.size < 4) return null
        val u0 = docW / 2.0
        val v0 = docH / 2.0
        // m1 = TL, m2 = TR, m3 = BL, m4 = BR
        val x1 = corners[0].x.toDouble(); val y1 = corners[0].y.toDouble()
        val x2 = corners[1].x.toDouble(); val y2 = corners[1].y.toDouble()
        val x3 = corners[3].x.toDouble(); val y3 = corners[3].y.toDouble()
        val x4 = corners[2].x.toDouble(); val y4 = corners[2].y.toDouble()
        val d2 = (y2 - y4) * x3 - (x2 - x4) * y3 + x2 * y4 - y2 * x4
        val d3 = (y3 - y4) * x2 - (x3 - x4) * y2 + x3 * y4 - y3 * x4
        if (kotlin.math.abs(d2) < 1e-9 || kotlin.math.abs(d3) < 1e-9) return null
        val k2 = ((y1 - y4) * x3 - (x1 - x4) * y3 + x1 * y4 - y1 * x4) / d2
        val k3 = ((y1 - y4) * x2 - (x1 - x4) * y2 + x1 * y4 - y1 * x4) / d3
        val n21 = k2 * x2 - x1; val n22 = k2 * y2 - y1; val n23 = k2 - 1.0
        val n31 = k3 * x3 - x1; val n32 = k3 * y3 - y1; val n33 = k3 - 1.0
        val ratio: Double
        val denom = n23 * n33
        val f2 = if (kotlin.math.abs(denom) < 1e-9) -1.0 else -(
            (n21 * n31 - (n21 * n33 + n23 * n31) * u0 + n23 * n33 * u0 * u0) +
                (n22 * n32 - (n22 * n33 + n23 * n32) * v0 + n23 * n33 * v0 * v0)
            ) / denom
        ratio = if (f2 > 1.0) {
            val f = kotlin.math.sqrt(f2)
            fun norm2(a: Double, b: Double, c: Double): Double {
                val p = (a - u0 * c) / f
                val q = (b - v0 * c) / f
                return p * p + q * q + c * c
            }
            kotlin.math.sqrt(norm2(n21, n22, n23) / norm2(n31, n32, n33))
        } else {
            // Opposite sides (almost) parallel: trapezoid / rectangle geometry.
            val top = kotlin.math.hypot(x2 - x1, y2 - y1)
            val bot = kotlin.math.hypot(x4 - x3, y4 - y3)
            val dx12 = x2 - x1; val dy12 = y2 - y1
            val l12 = kotlin.math.hypot(dx12, dy12)
            val hDist = if (l12 > 1e-6) (
                kotlin.math.abs(dx12 * (y1 - y3) - (x1 - x3) * dy12) +
                kotlin.math.abs(dx12 * (y1 - y4) - (x1 - x4) * dy12)
            ) / (2.0 * l12) else 0.0
            val avgW = (top + bot) / 2.0
            if (hDist > 1e-6) avgW / hDist else kotlin.math.sqrt((n21 * n21 + n22 * n22) / (n31 * n31 + n32 * n32))
        }
        if (!ratio.isFinite() || ratio <= 0.0) return null
        return ratio.toFloat().coerceIn(0.1f, 10f)
    }

    /** Output size of a perspective crop: real proportions (or [aspect] when given), longest side kept. */
    fun perspectiveOutputSize(corners: List<PointF>, docW: Int, docH: Int, aspect: Float?): Pair<Int, Int> {
        if (corners.size < 4) return docW to docH
        fun len(a: PointF, b: PointF) = kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
        val wSide = max(len(corners[0], corners[1]), len(corners[3], corners[2]))
        val hSide = max(len(corners[0], corners[3]), len(corners[1], corners[2]))
        val ratio = aspect?.takeIf { it > 0f } ?: estimateRectAspect(corners, docW, docH) ?: (wSide / hSide.coerceAtLeast(1f))
        val longest = max(wSide, hSide).coerceAtLeast(50f)
        val (w, h) = if (ratio >= 1f) longest to longest / ratio else longest * ratio to longest
        return w.roundToIntSafe().coerceIn(50, 8192) to h.roundToIntSafe().coerceIn(50, 8192)
    }

    private fun Float.roundToIntSafe(): Int = if (isFinite()) kotlin.math.round(this).toInt() else 0

    /**
     * Finds a document / poster / screen in the photo: the largest region whose colour differs
     * from the background sampled along the image border, then its four extreme corners.
     * Returns TL, TR, BR, BL in [bitmap] pixels, or null when nothing convincing is found.
     */
    fun detectDocumentCorners(bitmap: Bitmap): List<PointF>? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 16 || h < 16) return null
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        // Background = median colour of the border.
        val border = ArrayList<Int>()
        for (x in 0 until w) { border.add(px[x]); border.add(px[(h - 1) * w + x]) }
        for (y in 0 until h) { border.add(px[y * w]); border.add(px[y * w + w - 1]) }
        fun median(sel: (Int) -> Int): Int = border.map(sel).sorted()[border.size / 2]
        val br = median { (it shr 16) and 0xFF }
        val bg = median { (it shr 8) and 0xFF }
        val bb = median { it and 0xFF }
        val diff = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val dr = ((p shr 16) and 0xFF) - br
            val dg = ((p shr 8) and 0xFF) - bg
            val db = (p and 0xFF) - bb
            diff[i] = kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toInt()
        }
        // Threshold between background and the rest (Otsu on the difference).
        val hist = IntArray(442)
        diff.forEach { hist[it.coerceIn(0, 441)]++ }
        var sumAll = 0.0
        for (t in hist.indices) sumAll += t * hist[t].toDouble()
        var sumB = 0.0; var wB = 0; var best = 0.0; var threshold = 40
        for (t in hist.indices) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = diff.size - wB
            if (wF == 0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t }
        }
        threshold = threshold.coerceAtLeast(25)
        val fg = BooleanArray(w * h) { diff[it] > threshold }
        // Largest 4-connected foreground component.
        val label = IntArray(w * h) { -1 }
        var bestLabel = -1
        var bestSize = 0
        var stack = IntArray(4096)
        var next = 0
        for (s in fg.indices) {
            if (!fg[s] || label[s] >= 0) continue
            var sp = 0
            var size = 0
            label[s] = next
            stack[sp++] = s
            while (sp > 0) {
                val i = stack[--sp]
                size++
                val x = i % w
                val y = i / w
                fun push(j: Int) {
                    if (fg[j] && label[j] < 0) {
                        label[j] = next
                        if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
                        stack[sp++] = j
                    }
                }
                if (x > 0) push(i - 1)
                if (x < w - 1) push(i + 1)
                if (y > 0) push(i - w)
                if (y < h - 1) push(i + w)
            }
            if (size > bestSize) { bestSize = size; bestLabel = next }
            next++
        }
        val total = w * h
        if (bestLabel < 0 || bestSize < total * 0.08f || bestSize > total * 0.97f) return null
        // Extreme points along the diagonals = the quad's corners.
        var tl = 0; var tr = 0; var brI = 0; var bl = 0
        var minSum = Int.MAX_VALUE; var maxSum = Int.MIN_VALUE; var minDiff = Int.MAX_VALUE; var maxDiff = Int.MIN_VALUE
        for (i in label.indices) {
            if (label[i] != bestLabel) continue
            val x = i % w
            val y = i / w
            val sum = x + y
            val d = x - y
            if (sum < minSum) { minSum = sum; tl = i }
            if (sum > maxSum) { maxSum = sum; brI = i }
            if (d > maxDiff) { maxDiff = d; tr = i }
            if (d < minDiff) { minDiff = d; bl = i }
        }
        fun pt(i: Int) = PointF((i % w).toFloat(), (i / w).toFloat())
        val quad = listOf(pt(tl), pt(tr), pt(brI), pt(bl))
        // Reject slivers.
        val area = kotlin.math.abs(
            (quad[0].x * quad[1].y - quad[1].x * quad[0].y) + (quad[1].x * quad[2].y - quad[2].x * quad[1].y) +
                (quad[2].x * quad[3].y - quad[3].x * quad[2].y) + (quad[3].x * quad[0].y - quad[0].x * quad[3].y)
        ) / 2f
        if (area < total * 0.06f) return null
        return quad
    }

    /**
     * Unwarps a perspective quadrilateral into a flat rectangular bitmap.
     * Takes 4 source corners [top-left, top-right, bottom-right, bottom-left]
     * and maps them to a rectangular output of size destWidth x destHeight using closed-form Heckbert homography.
     */
    fun transformPerspective(
        source: Bitmap,
        srcPoints: FloatArray,
        dstPoints: FloatArray,
        destWidth: Int,
        destHeight: Int
    ): Bitmap {
        if (srcPoints.size < 8 || dstPoints.size < 8) return source
        val w = destWidth.coerceIn(1, 8192)
        val h = destHeight.coerceIn(1, 8192)

        val corners = listOf(
            PointF(srcPoints[0], srcPoints[1]),
            PointF(srcPoints[2], srcPoints[3]),
            PointF(srcPoints[4], srcPoints[5]),
            PointF(srcPoints[6], srcPoints[7])
        )
        return transformPerspective(source, corners, w, h)
    }

    fun transformPerspective(source: Bitmap, srcCorners: List<PointF>, destWidth: Int, destHeight: Int): Bitmap {
        if (srcCorners.size < 4) return source
        val w = destWidth.coerceIn(1, 8192)
        val h = destHeight.coerceIn(1, 8192)

        val c0 = srcCorners[0]
        val c1 = srcCorners[1]
        val c2 = srcCorners[2]
        val c3 = srcCorners[3]

        // Compute direct closed-form mapping from destination rectangle (0,0)..(w,h) to source quadrilateral
        val coeffs = computeQuadToSquareCoefficients(
            c0.x, c0.y,
            c1.x, c1.y,
            c2.x, c2.y,
            c3.x, c3.y,
            w.toFloat(), h.toFloat()
        )

        if (coeffs != null) {
            return warpPerspectiveBilinearCoeffs(source, coeffs, w, h)
        }

        // Fallback: Android Matrix setPolyToPoly
        val srcPts = floatArrayOf(c0.x, c0.y, c1.x, c1.y, c2.x, c2.y, c3.x, c3.y)
        val dstPts = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val matrix = Matrix()
        if (matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) {
            val invMatrix = Matrix()
            if (matrix.invert(invMatrix)) {
                return warpPerspectiveBilinearMatrix(source, invMatrix, w, h)
            }
        }

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(source, 0f, 0f, null)
        return output
    }

    fun transformPerspectiveAlpha(source: Bitmap, srcCorners: List<PointF>, destWidth: Int, destHeight: Int): Bitmap {
        if (srcCorners.size < 4) return source
        val w = destWidth.coerceIn(1, 8192)
        val h = destHeight.coerceIn(1, 8192)

        val c0 = srcCorners[0]
        val c1 = srcCorners[1]
        val c2 = srcCorners[2]
        val c3 = srcCorners[3]

        val coeffs = computeQuadToSquareCoefficients(
            c0.x, c0.y,
            c1.x, c1.y,
            c2.x, c2.y,
            c3.x, c3.y,
            w.toFloat(), h.toFloat()
        ) ?: return Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)

        val srcW = source.width
        val srcH = source.height
        val srcPixels = IntArray(srcW * srcH)
        source.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        val dstPixels = IntArray(w * h)
        val c00 = coeffs[0]; val c01 = coeffs[1]; val c02 = coeffs[2]
        val c10 = coeffs[3]; val c11 = coeffs[4]; val c12 = coeffs[5]
        val c20 = coeffs[6]; val c21 = coeffs[7]

        for (v in 0 until h) {
            val vOffset = v * w
            val rowX = c01 * v + c02
            val rowY = c11 * v + c12
            val rowW = c21 * v + 1f

            for (u in 0 until w) {
                val denom = c20 * u + rowW
                if (denom <= 0.000001f) continue
                val invDenom = 1f / denom
                val srcX = (c00 * u + rowX) * invDenom
                val srcY = (c10 * u + rowY) * invDenom

                if (srcX >= 0f && srcX < srcW && srcY >= 0f && srcY < srcH) {
                    val x0 = srcX.toInt().coerceIn(0, srcW - 1)
                    val y0 = srcY.toInt().coerceIn(0, srcH - 1)
                    val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                    val y1 = (y0 + 1).coerceAtMost(srcH - 1)

                    val dx = srcX - x0
                    val dy = srcY - y0
                    val invDx = 1f - dx
                    val invDy = 1f - dy

                    val a00 = (srcPixels[y0 * srcW + x0] ushr 24) and 0xFF
                    val a10 = (srcPixels[y0 * srcW + x1] ushr 24) and 0xFF
                    val a01 = (srcPixels[y1 * srcW + x0] ushr 24) and 0xFF
                    val a11 = (srcPixels[y1 * srcW + x1] ushr 24) and 0xFF

                    val a = (invDx * invDy * a00 + dx * invDy * a10 + invDx * dy * a01 + dx * dy * a11).toInt().coerceIn(0, 255)
                    dstPixels[vOffset + u] = a shl 24
                }
            }
        }

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        output.setPixels(dstPixels, 0, w, 0, 0, w, h)
        return output
    }

    private fun computeQuadToSquareCoefficients(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float,
        destW: Float, destH: Float
    ): FloatArray? {
        val dx1 = x1 - x2
        val dx2 = x3 - x2
        val dx3 = x0 - x1 + x2 - x3
        val dy1 = y1 - y2
        val dy2 = y3 - y2
        val dy3 = y0 - y1 + y2 - y3

        if (dx3 == 0f && dy3 == 0f) {
            // Affine parallelogram mapping
            return floatArrayOf(
                (x1 - x0) / destW, (x3 - x0) / destH, x0,
                (y1 - y0) / destW, (y3 - y0) / destH, y0,
                0f, 0f, 1f
            )
        }

        val det = dx1 * dy2 - dx2 * dy1
        if (kotlin.math.abs(det) < 1e-7f) return null

        val a31 = (dx3 * dy2 - dx2 * dy3) / det
        val a32 = (dx1 * dy3 - dx3 * dy1) / det
        val a11 = x1 - x0 + a31 * x1
        val a12 = x3 - x0 + a32 * x3
        val a13 = x0
        val a21 = y1 - y0 + a31 * y1
        val a22 = y3 - y0 + a32 * y3
        val a23 = y0

        return floatArrayOf(
            a11 / destW, a12 / destH, a13,
            a21 / destW, a22 / destH, a23,
            a31 / destW, a32 / destH, 1f
        )
    }

    private fun warpPerspectiveBilinearCoeffs(
        source: Bitmap,
        coeffs: FloatArray,
        destWidth: Int,
        destHeight: Int
    ): Bitmap {
        val srcW = source.width
        val srcH = source.height
        val srcPixels = IntArray(srcW * srcH)
        source.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        val dstPixels = IntArray(destWidth * destHeight)

        val c00 = coeffs[0]; val c01 = coeffs[1]; val c02 = coeffs[2]
        val c10 = coeffs[3]; val c11 = coeffs[4]; val c12 = coeffs[5]
        val c20 = coeffs[6]; val c21 = coeffs[7]

        for (v in 0 until destHeight) {
            val vOffset = v * destWidth
            val rowX = c01 * v + c02
            val rowY = c11 * v + c12
            val rowW = c21 * v + 1f

            for (u in 0 until destWidth) {
                val denom = c20 * u + rowW
                if (denom <= 0.000001f) continue
                val invDenom = 1f / denom
                val srcX = (c00 * u + rowX) * invDenom
                val srcY = (c10 * u + rowY) * invDenom

                if (srcX >= 0f && srcX < srcW && srcY >= 0f && srcY < srcH) {
                    val x0 = srcX.toInt().coerceIn(0, srcW - 1)
                    val y0 = srcY.toInt().coerceIn(0, srcH - 1)
                    val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                    val y1 = (y0 + 1).coerceAtMost(srcH - 1)

                    val dx = srcX - x0
                    val dy = srcY - y0
                    val invDx = 1f - dx
                    val invDy = 1f - dy

                    val p00 = srcPixels[y0 * srcW + x0]
                    val p10 = srcPixels[y0 * srcW + x1]
                    val p01 = srcPixels[y1 * srcW + x0]
                    val p11 = srcPixels[y1 * srcW + x1]

                    val a00 = (p00 ushr 24) and 0xFF
                    val r00 = (p00 shr 16) and 0xFF
                    val g00 = (p00 shr 8) and 0xFF
                    val b00 = p00 and 0xFF

                    val a10 = (p10 ushr 24) and 0xFF
                    val r10 = (p10 shr 16) and 0xFF
                    val g10 = (p10 shr 8) and 0xFF
                    val b10 = p10 and 0xFF

                    val a01 = (p01 ushr 24) and 0xFF
                    val r01 = (p01 shr 16) and 0xFF
                    val g01 = (p01 shr 8) and 0xFF
                    val b01 = p01 and 0xFF

                    val a11 = (p11 ushr 24) and 0xFF
                    val r11 = (p11 shr 16) and 0xFF
                    val g11 = (p11 shr 8) and 0xFF
                    val b11 = p11 and 0xFF

                    val a = (invDx * invDy * a00 + dx * invDy * a10 + invDx * dy * a01 + dx * dy * a11).toInt().coerceIn(0, 255)
                    val r = (invDx * invDy * r00 + dx * invDy * r10 + invDx * dy * r01 + dx * dy * r11).toInt().coerceIn(0, 255)
                    val g = (invDx * invDy * g00 + dx * invDy * g10 + invDx * dy * g01 + dx * dy * g11).toInt().coerceIn(0, 255)
                    val b = (invDx * invDy * b00 + dx * invDy * b10 + invDx * dy * b01 + dx * dy * b11).toInt().coerceIn(0, 255)

                    dstPixels[vOffset + u] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        val output = Bitmap.createBitmap(destWidth, destHeight, Bitmap.Config.ARGB_8888)
        output.setPixels(dstPixels, 0, destWidth, 0, 0, destWidth, destHeight)
        return output
    }

    private fun warpPerspectiveBilinearMatrix(
        source: Bitmap,
        invMatrix: Matrix,
        destWidth: Int,
        destHeight: Int
    ): Bitmap {
        val srcW = source.width
        val srcH = source.height
        val srcPixels = IntArray(srcW * srcH)
        source.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        val dstPixels = IntArray(destWidth * destHeight)
        val matrixValues = FloatArray(9)
        invMatrix.getValues(matrixValues)

        val m00 = matrixValues[Matrix.MSCALE_X]
        val m01 = matrixValues[Matrix.MSKEW_X]
        val m02 = matrixValues[Matrix.MTRANS_X]

        val m10 = matrixValues[Matrix.MSKEW_Y]
        val m11 = matrixValues[Matrix.MSCALE_Y]
        val m12 = matrixValues[Matrix.MTRANS_Y]

        val m20 = matrixValues[Matrix.MPERSP_0]
        val m21 = matrixValues[Matrix.MPERSP_1]
        val m22 = matrixValues[Matrix.MPERSP_2]

        for (v in 0 until destHeight) {
            val vOffset = v * destWidth
            val uStep0 = m01 * v + m02
            val uStep1 = m11 * v + m12
            val uStep2 = m21 * v + m22

            for (u in 0 until destWidth) {
                val denom = m20 * u + uStep2
                if (denom <= 0.000001f) continue
                val invDenom = 1f / denom
                val srcX = (m00 * u + uStep0) * invDenom
                val srcY = (m10 * u + uStep1) * invDenom

                if (srcX >= 0f && srcX < srcW && srcY >= 0f && srcY < srcH) {
                    val x0 = srcX.toInt().coerceIn(0, srcW - 1)
                    val y0 = srcY.toInt().coerceIn(0, srcH - 1)
                    val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                    val y1 = (y0 + 1).coerceAtMost(srcH - 1)

                    val dx = srcX - x0
                    val dy = srcY - y0
                    val invDx = 1f - dx
                    val invDy = 1f - dy

                    val p00 = srcPixels[y0 * srcW + x0]
                    val p10 = srcPixels[y0 * srcW + x1]
                    val p01 = srcPixels[y1 * srcW + x0]
                    val p11 = srcPixels[y1 * srcW + x1]

                    val a00 = (p00 ushr 24) and 0xFF
                    val r00 = (p00 shr 16) and 0xFF
                    val g00 = (p00 shr 8) and 0xFF
                    val b00 = p00 and 0xFF

                    val a10 = (p10 ushr 24) and 0xFF
                    val r10 = (p10 shr 16) and 0xFF
                    val g10 = (p10 shr 8) and 0xFF
                    val b10 = p10 and 0xFF

                    val a01 = (p01 ushr 24) and 0xFF
                    val r01 = (p01 shr 16) and 0xFF
                    val g01 = (p01 shr 8) and 0xFF
                    val b01 = p01 and 0xFF

                    val a11 = (p11 ushr 24) and 0xFF
                    val r11 = (p11 shr 16) and 0xFF
                    val g11 = (p11 shr 8) and 0xFF
                    val b11 = p11 and 0xFF

                    val a = (invDx * invDy * a00 + dx * invDy * a10 + invDx * dy * a01 + dx * dy * a11).toInt().coerceIn(0, 255)
                    val r = (invDx * invDy * r00 + dx * invDy * r10 + invDx * dy * r01 + dx * dy * r11).toInt().coerceIn(0, 255)
                    val g = (invDx * invDy * g00 + dx * invDy * g10 + invDx * dy * g01 + dx * dy * g11).toInt().coerceIn(0, 255)
                    val b = (invDx * invDy * b00 + dx * invDy * b10 + invDx * dy * b01 + dx * dy * b11).toInt().coerceIn(0, 255)

                    dstPixels[vOffset + u] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        val output = Bitmap.createBitmap(destWidth, destHeight, Bitmap.Config.ARGB_8888)
        output.setPixels(dstPixels, 0, destWidth, 0, 0, destWidth, destHeight)
        return output
    }

    /**
     * Content-aware object removal (see [InpaintEngine]): multi-scale PatchMatch completion or
     * Telea fast marching. [radiusPx] is the fast-marching sampling radius; [featherPx] is the width
     * of the seamless cross-fade ring around the removed area.
     */
    fun inpaintObjectRemoval(
        source: Bitmap,
        maskBitmap: Bitmap,
        radiusPx: Double = 5.0,
        featherPx: Int = 4,
        mode: InpaintEngineMode = InpaintEngineMode.SMART,
        smartEdgeSnap: Boolean = true,
        seamlessBlend: Boolean = true,
        protectMask: Bitmap? = null,
        seed: Int = 0x5EED
    ): Bitmap {
        require(source.width == maskBitmap.width && source.height == maskBitmap.height) { "Mask size must match source" }
        return InpaintEngine.inpaint(
            source = source,
            maskBitmap = maskBitmap,
            mode = mode,
            smartEdgeSnap = smartEdgeSnap,
            seamlessBlend = seamlessBlend,
            featherPx = featherPx,
            radiusPx = radiusPx,
            protectMask = protectMask,
            seed = seed
        )
    }


    /**
     * Fast multi-pass Horizontal/Vertical Box Blur (approximates Gaussian Blur)
     */
    private fun fastBoxBlur(
        source: Bitmap,
        radius: Int,
        passes: Int = 3,
        isCancelled: (() -> Boolean)? = null
    ): Bitmap {
        val current = source.copy(Bitmap.Config.ARGB_8888, true)
        val w = current.width
        val h = current.height
        val pixels = IntArray(w * h)
        val temp = IntArray(w * h)

        val r = radius.coerceAtLeast(1)

        repeat(passes) {
            current.getPixels(pixels, 0, w, 0, 0, w, h)

            // Horizontal Pass
            for (y in 0 until h) {
                if (y % 32 == 0 && isCancelled?.invoke() == true) {
                    throw CancellationException("Blur cancelled")
                }
                val rowOffset = y * w
                var sumA = 0; var sumR = 0; var sumG = 0; var sumB = 0
                val windowSize = r * 2 + 1

                for (x in -r..r) {
                    val px = x.coerceIn(0, w - 1)
                    val color = pixels[rowOffset + px]
                    sumA += (color ushr 24) and 0xFF
                    sumR += (color ushr 16) and 0xFF
                    sumG += (color ushr 8) and 0xFF
                    sumB += color and 0xFF
                }

                for (x in 0 until w) {
                    temp[rowOffset + x] = ((sumA / windowSize) shl 24) or
                            ((sumR / windowSize) shl 16) or
                            ((sumG / windowSize) shl 8) or
                            (sumB / windowSize)

                    val removePx = (x - r).coerceIn(0, w - 1)
                    val addPx = (x + r + 1).coerceIn(0, w - 1)

                    val cRemove = pixels[rowOffset + removePx]
                    val cAdd = pixels[rowOffset + addPx]

                    sumA += ((cAdd ushr 24) and 0xFF) - ((cRemove ushr 24) and 0xFF)
                    sumR += ((cAdd ushr 16) and 0xFF) - ((cRemove ushr 16) and 0xFF)
                    sumG += ((cAdd ushr 8) and 0xFF) - ((cRemove ushr 8) and 0xFF)
                    sumB += (cAdd and 0xFF) - (cRemove and 0xFF)
                }
            }

            // Vertical Pass
            for (x in 0 until w) {
                if (x % 32 == 0 && isCancelled?.invoke() == true) {
                    throw CancellationException("Blur cancelled")
                }
                var sumA = 0; var sumR = 0; var sumG = 0; var sumB = 0
                val windowSize = r * 2 + 1

                for (y in -r..r) {
                    val py = y.coerceIn(0, h - 1)
                    val color = temp[py * w + x]
                    sumA += (color ushr 24) and 0xFF
                    sumR += (color ushr 16) and 0xFF
                    sumG += (color ushr 8) and 0xFF
                    sumB += color and 0xFF
                }

                for (y in 0 until h) {
                    pixels[y * w + x] = ((sumA / windowSize) shl 24) or
                            ((sumR / windowSize) shl 16) or
                            ((sumG / windowSize) shl 8) or
                            (sumB / windowSize)

                    val removePy = (y - r).coerceIn(0, h - 1)
                    val addPy = (y + r + 1).coerceIn(0, h - 1)

                    val cRemove = temp[removePy * w + x]
                    val cAdd = temp[addPy * w + x]

                    sumA += ((cAdd ushr 24) and 0xFF) - ((cRemove ushr 24) and 0xFF)
                    sumR += ((cAdd ushr 16) and 0xFF) - ((cRemove ushr 16) and 0xFF)
                    sumG += ((cAdd ushr 8) and 0xFF) - ((cRemove ushr 8) and 0xFF)
                    sumB += (cAdd and 0xFF) - (cRemove and 0xFF)
                }
            }

            current.setPixels(pixels, 0, w, 0, 0, w, h)
        }

        return current
    }

    /**
     * Tone curve as a 256-entry LUT using a monotone cubic spline (Fritsch-Carlson) through the
     * control points: smooth, respects uneven point spacing and never overshoots, so a rising
     * curve never inverts tones. Before the first / after the last point the curve is flat
     * (moving an end point sideways sets the input black / white point).
     */
    fun buildCurveLut(points: List<PointF>): IntArray {
        val lut = IntArray(256)
        val f = curveValues(points)
        for (i in 0..255) lut[i] = (f[i] + 0.5f).toInt().coerceIn(0, 255)
        return lut
    }

    /** The same curve as [buildCurveLut] with sub-level precision (0..255 floats). */
    fun curveValues(points: List<PointF>): FloatArray {
        val out = FloatArray(256)
        // Sorted, one point per input level (a later duplicate wins), clamped to the range.
        val byX = java.util.TreeMap<Float, Float>()
        points.forEach { byX[it.x.coerceIn(0f, 255f)] = it.y.coerceIn(0f, 255f) }
        if (byX.isEmpty()) { for (i in 0..255) out[i] = i.toFloat(); return out }
        val xs = byX.keys.toFloatArray()
        val ys = byX.values.toFloatArray()
        val n = xs.size
        if (n == 1) { java.util.Arrays.fill(out, ys[0]); return out }

        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]).coerceAtLeast(1e-4f) }
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (k in 1 until n - 1) {
            m[k] = if (d[k - 1] * d[k] <= 0f) 0f else (d[k - 1] + d[k]) / 2f
        }
        for (k in 0 until n - 1) {
            if (d[k] == 0f) { m[k] = 0f; m[k + 1] = 0f; continue }
            val a = m[k] / d[k]
            val b = m[k + 1] / d[k]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[k] = t * a * d[k]
                m[k + 1] = t * b * d[k]
            }
        }
        var seg = 0
        for (i in 0..255) {
            val x = i.toFloat()
            out[i] = when {
                x <= xs[0] -> ys[0]
                x >= xs[n - 1] -> ys[n - 1]
                else -> {
                    while (seg < n - 2 && x > xs[seg + 1]) seg++
                    val h = (xs[seg + 1] - xs[seg]).coerceAtLeast(1e-4f)
                    val t = (x - xs[seg]) / h
                    val t2 = t * t
                    val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * ys[seg] + (t3 - 2 * t2 + t) * h * m[seg] +
                        (-2 * t3 + 3 * t2) * ys[seg + 1] + (t3 - t2) * h * m[seg + 1]
                }
            }.coerceIn(0f, 255f)
        }
        return out
    }

    /** Gaussian blur, radius = sigma in px (fractional), premultiplied (no dark halos at transparency). */
    fun applyGaussianBlur(
        source: Bitmap,
        radiusPx: Float,
        isCancelled: (() -> Boolean)? = null
    ): Bitmap = ProEffects.gaussian(source, radiusPx, isCancelled)

    /** Dispatcher for all Pro blur & effect filters (see [ProEffects]). */
    fun applyProEffect(
        source: Bitmap,
        type: ProFilterType,
        params: ProEffectParams,
        isCancelled: (() -> Boolean)? = null,
        onProgress: ((Float) -> Unit)? = null
    ): Bitmap = ProEffects.apply(source, type, params, isCancelled, onProgress)
}
