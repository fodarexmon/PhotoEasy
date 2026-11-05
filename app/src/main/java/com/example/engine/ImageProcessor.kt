package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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

    /**
     * Decodes a stored original full-resolution asset file taking EXIF into account.
     */
    fun decodeFileWithExifFullRes(file: File): Bitmap? {
        if (!file.exists()) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val source = ImageDecoder.createSource(file)
                    val decoded = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    if (!decoded.isMutable) {
                        val copy = decoded.copy(Bitmap.Config.ARGB_8888, true)
                        if (copy != null && copy !== decoded) decoded.recycle()
                        copy ?: decoded
                    } else decoded
                } catch (e: Exception) {
                    val exif = ExifInterface(file.absolutePath)
                    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    val raw = BitmapFactory.decodeFile(file.absolutePath) ?: return null
                    applyExifMatrix(raw, orientation)
                }
            } else {
                val exif = ExifInterface(file.absolutePath)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val raw = BitmapFactory.decodeFile(file.absolutePath) ?: return null
                applyExifMatrix(raw, orientation)
            }
        } catch (e: Exception) {
            Log.e("ImageProcessor", "decodeFileWithExifFullRes error: ${e.message}")
            null
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

        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, source.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val cm = ColorMatrix()

        // Exposure (EV stops: multiplicative scaling) & Brightness (additive offset)
        if (adj.exposure != 0f) {
            val ev = adj.exposure / 50.0f // -100 to 100 maps to -2 EV to +2 EV
            val expScale = 2.0f.pow(ev)
            val expMatrix = ColorMatrix(
                floatArrayOf(
                    expScale, 0f, 0f, 0f, 0f,
                    0f, expScale, 0f, 0f, 0f,
                    0f, 0f, expScale, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(expMatrix)
        }

        if (adj.brightness != 0f) {
            val b = adj.brightness * 2.55f
            val brightnessMatrix = ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, b,
                    0f, 1f, 0f, 0f, b,
                    0f, 0f, 1f, 0f, b,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(brightnessMatrix)
        }

        // Contrast
        val c = (adj.contrast + 100f) / 100f
        val contrastScale = c * c
        val contrastTranslate = 0.5f * (1f - contrastScale) * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrastScale, 0f, 0f, 0f, contrastTranslate,
                0f, contrastScale, 0f, 0f, contrastTranslate,
                0f, 0f, contrastScale, 0f, contrastTranslate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(contrastMatrix)

        // Saturation
        val sat = (adj.saturation + 100f) / 100f
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(sat)
        cm.postConcat(satMatrix)

        // Temperature (Warm/Cool) & Tint
        val temp = adj.temperature * 1.2f
        val tint = adj.tint * 1.2f
        val tempMatrix = ColorMatrix(
            floatArrayOf(
                1f + (temp / 250f), 0f, 0f, 0f, 0f,
                0f, 1f + (tint / 250f), 0f, 0f, 0f,
                0f, 0f, 1f - (temp / 250f), 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(tempMatrix)

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(source, 0f, 0f, paint)

        // Highlights & Shadows LUT mapping
        if (adj.highlights != 0f || adj.shadows != 0f) {
            val lut = IntArray(256) { v ->
                val x = v / 255.0f

                // Shadow lift/push (strongest in range 0..128)
                val shadowWeight = (1.0f - x) * (1.0f - x)
                val shadowDelta = (adj.shadows / 100.0f) * shadowWeight * 80.0f

                // Highlight dim/boost (strongest in range 128..255)
                val highlightWeight = x * x
                val highlightDelta = (adj.highlights / 100.0f) * highlightWeight * 80.0f

                (v + shadowDelta + highlightDelta).toInt().coerceIn(0, 255)
            }

            val pixels = IntArray(width * height)
            output.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                if (i % 32768 == 0 && isCancelled?.invoke() == true) {
                    throw CancellationException("Color adjustments cancelled")
                }
                val p = pixels[i]
                val a = (p ushr 24) and 0xFF
                val r = lut[(p ushr 16) and 0xFF]
                val g = lut[(p ushr 8) and 0xFF]
                val b = lut[p and 0xFF]
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            output.setPixels(pixels, 0, width, 0, 0, width, height)
        }

        // Tone Curves Mapping
        if (adj.hasCurves()) {
            val lutRgb = buildCurveLut(adj.curvesRgb)
            val lutRed = buildCurveLut(adj.curvesRed)
            val lutGreen = buildCurveLut(adj.curvesGreen)
            val lutBlue = buildCurveLut(adj.curvesBlue)

            val pixels = IntArray(width * height)
            output.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                if (i % 32768 == 0 && isCancelled?.invoke() == true) {
                    throw CancellationException("Curves adjustment cancelled")
                }
                val p = pixels[i]
                val a = (p ushr 24) and 0xFF
                val r0 = lutRgb[(p ushr 16) and 0xFF]
                val g0 = lutRgb[(p ushr 8) and 0xFF]
                val b0 = lutRgb[p and 0xFF]
                val r = lutRed[r0]
                val g = lutGreen[g0]
                val b = lutBlue[b0]
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            output.setPixels(pixels, 0, width, 0, 0, width, height)
        }

        // Hue / Saturation / Lightness (HSL)
        if (adj.hueShift != 0f || adj.hslSaturation != 0f || adj.hslLightness != 0f) {
            val pixels = IntArray(width * height)
            output.getPixels(pixels, 0, width, 0, 0, width, height)
            val hsv = FloatArray(3)
            val shift = adj.hueShift
            val satDelta = adj.hslSaturation / 100f
            val valDelta = adj.hslLightness / 100f
            val targetRange = adj.hslColorRange

            for (i in pixels.indices) {
                if (i % 32768 == 0 && isCancelled?.invoke() == true) {
                    throw CancellationException("HSL adjustment cancelled")
                }
                val p = pixels[i]
                val a = (p ushr 24) and 0xFF
                if (a == 0) continue

                Color.colorToHSV(p, hsv)
                val hue = hsv[0]
                val inRange = when (targetRange) {
                    "REDS" -> hue >= 330f || hue <= 30f
                    "YELLOWS" -> hue in 30f..90f
                    "GREENS" -> hue in 90f..150f
                    "CYANS" -> hue in 150f..210f
                    "BLUES" -> hue in 210f..270f
                    "MAGENTAS" -> hue in 270f..330f
                    else -> true
                }

                if (inRange) {
                    hsv[0] = ((hsv[0] + shift) % 360f + 360f) % 360f
                    hsv[1] = (hsv[1] + satDelta).coerceIn(0f, 1f)
                    hsv[2] = (hsv[2] + valDelta).coerceIn(0f, 1f)
                    pixels[i] = Color.HSVToColor(a, hsv)
                }
            }
            output.setPixels(pixels, 0, width, 0, 0, width, height)
        }

        // Selective Color Replacement
        if (adj.replaceColorEnabled) {
            val pixels = IntArray(width * height)
            output.getPixels(pixels, 0, width, 0, 0, width, height)
            val srcHsv = FloatArray(3)
            val pxHsv = FloatArray(3)
            Color.colorToHSV(adj.replaceSourceColorHex, srcHsv)

            val maxTolerance = (adj.replaceTolerance / 100f) * 1.5f
            val targetHue = adj.replaceTargetHue
            val targetSatDelta = adj.replaceTargetSaturation / 100f
            val targetValDelta = adj.replaceTargetLightness / 100f

            for (i in pixels.indices) {
                if (i % 32768 == 0 && isCancelled?.invoke() == true) {
                    throw CancellationException("Color replacement cancelled")
                }
                val p = pixels[i]
                val a = (p ushr 24) and 0xFF
                if (a == 0) continue

                Color.colorToHSV(p, pxHsv)
                val dHue = min(abs(pxHsv[0] - srcHsv[0]), 360f - abs(pxHsv[0] - srcHsv[0])) / 180f
                val dSat = abs(pxHsv[1] - srcHsv[1])
                val dVal = abs(pxHsv[2] - srcHsv[2])
                val dist = sqrt(dHue * dHue + dSat * dSat + dVal * dVal)

                if (dist < maxTolerance) {
                    val weight = (1f - dist / maxTolerance).coerceIn(0f, 1f)
                    pxHsv[0] = (pxHsv[0] * (1f - weight) + targetHue * weight) % 360f
                    pxHsv[1] = (pxHsv[1] + targetSatDelta * weight).coerceIn(0f, 1f)
                    pxHsv[2] = (pxHsv[2] + targetValDelta * weight).coerceIn(0f, 1f)
                    pixels[i] = Color.HSVToColor(a, pxHsv)
                }
            }
            output.setPixels(pixels, 0, width, 0, 0, width, height)
        }

        // Sharpness Filter (Unsharp Mask / Laplacian High-Pass)
        if (adj.sharpness > 0f) {
            val amount = (adj.sharpness / 100.0f).coerceIn(0f, 2.0f)
            val pixels = IntArray(width * height)
            val sharpPixels = IntArray(width * height)
            output.getPixels(pixels, 0, width, 0, 0, width, height)

            for (y in 0 until height) {
                if (y % 32 == 0 && isCancelled?.invoke() == true) {
                    throw CancellationException("Sharpness adjustment cancelled")
                }
                for (x in 0 until width) {
                    val idx = y * width + x
                    val p = pixels[idx]
                    val a = (p ushr 24) and 0xFF

                    if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                        sharpPixels[idx] = p
                        continue
                    }

                    val r = (p ushr 16) and 0xFF
                    val g = (p ushr 8) and 0xFF
                    val b = p and 0xFF

                    val nR = ((pixels[(y - 1) * width + x] ushr 16) and 0xFF) +
                            ((pixels[(y + 1) * width + x] ushr 16) and 0xFF) +
                            ((pixels[y * width + (x - 1)] ushr 16) and 0xFF) +
                            ((pixels[y * width + (x + 1)] ushr 16) and 0xFF)
                    val nG = ((pixels[(y - 1) * width + x] ushr 8) and 0xFF) +
                            ((pixels[(y + 1) * width + x] ushr 8) and 0xFF) +
                            ((pixels[y * width + (x - 1)] ushr 8) and 0xFF) +
                            ((pixels[y * width + (x + 1)] ushr 8) and 0xFF)
                    val nB = (pixels[(y - 1) * width + x] and 0xFF) +
                            (pixels[(y + 1) * width + x] and 0xFF) +
                            (pixels[y * width + (x - 1)] and 0xFF) +
                            (pixels[y * width + (x + 1)] and 0xFF)

                    val sharpR = (r + amount * (4 * r - nR)).toInt().coerceIn(0, 255)
                    val sharpG = (g + amount * (4 * g - nG)).toInt().coerceIn(0, 255)
                    val sharpB = (b + amount * (4 * b - nB)).toInt().coerceIn(0, 255)

                    sharpPixels[idx] = (a shl 24) or (sharpR shl 16) or (sharpG shl 8) or sharpB
                }
            }
            output.setPixels(sharpPixels, 0, width, 0, 0, width, height)
        }

        // Apply Vignette if specified
        if (adj.vignette > 0f) {
            applyVignetteEffect(output, adj.vignette, isCancelled)
        }

        return output
    }

    /**
     * Computes intelligent smart auto-corrections: contrast, brightness, white-balance, highlights & shadows.
     * Returns a non-destructive ColorAdjustments model so sliders in the UI move dynamically.
     */
    fun computeSmartAutoAdjustments(source: Bitmap): ColorAdjustments {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        var totalR = 0L
        var totalG = 0L
        var totalB = 0L

        // Sample 1 in 4 pixels for high speed calculation
        val step = 4
        var sampledCount = 0
        for (i in pixels.indices step step) {
            val p = pixels[i]
            totalR += (p shr 16) and 0xFF
            totalG += (p shr 8) and 0xFF
            totalB += p and 0xFF
            sampledCount++
        }

        if (sampledCount == 0) return ColorAdjustments()

        val avgR = (totalR / sampledCount).toFloat()
        val avgG = (totalG / sampledCount).toFloat()
        val avgB = (totalB / sampledCount).toFloat()
        val avgGray = (avgR + avgG + avgB) / 3f

        // Calculate white balance gains
        val gainR = if (avgR > 1f) (avgGray / avgR).coerceIn(0.8f, 1.25f) else 1f
        val gainG = if (avgG > 1f) (avgGray / avgG).coerceIn(0.8f, 1.25f) else 1f
        val gainB = if (avgB > 1f) (avgGray / avgB).coerceIn(0.8f, 1.25f) else 1f

        return ColorAdjustments(
            brightness = if (avgGray < 110f) 14f else if (avgGray > 170f) -10f else 6f,
            contrast = 18f,
            saturation = 14f,
            temperature = ((gainR - gainB) * 40f).coerceIn(-20f, 20f),
            tint = ((gainG - avgGray / 255f) * 20f).coerceIn(-10f, 10f),
            highlights = -12f,
            shadows = 16f,
            sharpness = 15f
        )
    }

    /**
     * Smart Auto Correction: Automatically computes auto-contrast, white balance and clarity!
     */
    fun smartAutoFix(source: Bitmap): Bitmap {
        val autoAdj = computeSmartAutoAdjustments(source)
        return applyAdjustments(source, autoAdj)
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
        val clampedIntensity = intensity.coerceIn(0f, 1f)
        val rawFiltered = applyFilterFull(source, filter, isCancelled)
        if (clampedIntensity >= 0.999f || rawFiltered === source) {
            return rawFiltered
        }

        val blended = Bitmap.createBitmap(source.width, source.height, source.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(blended)
        canvas.drawBitmap(source, 0f, 0f, null)
        val blendPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (clampedIntensity * 255f).toInt()
        }
        canvas.drawBitmap(rawFiltered, 0f, 0f, blendPaint)
        if (rawFiltered !== source && !rawFiltered.isRecycled) {
            rawFiltered.recycle()
        }
        return blended
    }

    private fun applyFilterFull(
        source: Bitmap,
        filter: FilterType,
        isCancelled: (() -> Boolean)? = null
    ): Bitmap {
        if (filter == FilterType.NONE) return source

        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, source.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        when (filter) {
            FilterType.SEPIA -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        0.393f, 0.769f, 0.189f, 0f, 0f,
                        0.349f, 0.686f, 0.168f, 0f, 0f,
                        0.272f, 0.534f, 0.131f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
            FilterType.GRAYSCALE -> {
                val cm = ColorMatrix()
                cm.setSaturation(0f)
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
            FilterType.VINTAGE -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        0.9f, 0.1f, 0.1f, 0f, 20f,
                        0.1f, 0.8f, 0.1f, 0f, 15f,
                        0.1f, 0.1f, 0.6f, 0f, 30f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
                applyVignetteEffect(output, 25f, isCancelled)
            }
            FilterType.CYBERPUNK -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        1.2f, 0f, 0.4f, 0f, 10f,
                        0.1f, 0.9f, 0.2f, 0f, -10f,
                        0.3f, 0.2f, 1.4f, 0f, 30f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
            FilterType.CINEMATIC -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        1.1f, 0f, 0f, 0f, -5f,
                        0f, 1.0f, 0.1f, 0f, 5f,
                        0f, 0.2f, 1.2f, 0f, 15f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
                applyVignetteEffect(output, 35f, isCancelled)
            }
            FilterType.HDR -> {
                val adj = ColorAdjustments(
                    contrast = 25f,
                    saturation = 20f,
                    highlights = -20f,
                    shadows = 30f,
                    sharpness = 35f
                )
                return applyAdjustments(source, adj, isCancelled)
            }
            FilterType.WARM -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        1.2f, 0f, 0f, 0f, 20f,
                        0f, 1.1f, 0f, 0f, 10f,
                        0f, 0f, 0.85f, 0f, -10f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
            FilterType.COOL -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        0.85f, 0f, 0f, 0f, -10f,
                        0f, 1.0f, 0f, 0f, 5f,
                        0f, 0f, 1.25f, 0f, 25f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
            FilterType.GLOW -> {
                // 1. Draw base image
                canvas.drawBitmap(source, 0f, 0f, paint)

                // 2. Extract specular highlights to create bloom layer
                val halfW = max(1, width / 2)
                val halfH = max(1, height / 2)
                val brightPixels = IntArray(halfW * halfH)
                val srcPixels = IntArray(width * height)
                source.getPixels(srcPixels, 0, width, 0, 0, width, height)

                val scaleX = width.toFloat() / halfW
                val scaleY = height.toFloat() / halfH

                for (y in 0 until halfH) {
                    if (y % 16 == 0 && isCancelled?.invoke() == true) {
                        throw CancellationException("Glow filter cancelled")
                    }
                    val srcY = (y * scaleY).toInt().coerceIn(0, height - 1)
                    for (x in 0 until halfW) {
                        val srcX = (x * scaleX).toInt().coerceIn(0, width - 1)
                        val p = srcPixels[srcY * width + srcX]
                        val a = (p ushr 24) and 0xFF
                        val r = (p ushr 16) and 0xFF
                        val g = (p ushr 8) and 0xFF
                        val b = p and 0xFF

                        val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255.0f
                        // Highlight threshold curve: emphasizes brighter pixels
                        val factor = (lum * lum).coerceIn(0f, 1f)

                        val bloomR = (r * factor).toInt().coerceIn(0, 255)
                        val bloomG = (g * factor).toInt().coerceIn(0, 255)
                        val bloomB = (b * factor).toInt().coerceIn(0, 255)

                        brightPixels[y * halfW + x] = (a shl 24) or (bloomR shl 16) or (bloomG shl 8) or bloomB
                    }
                }

                val highlightBmp = Bitmap.createBitmap(halfW, halfH, Bitmap.Config.ARGB_8888)
                highlightBmp.setPixels(brightPixels, 0, halfW, 0, 0, halfW, halfH)

                // 3. Multi-pass Gaussian approximation box blur for broad, soft bloom
                val blurredBloom = fastBoxBlur(highlightBmp, radius = 8, passes = 3, isCancelled = isCancelled)
                val fullBloom = Bitmap.createScaledBitmap(blurredBloom, width, height, true)

                // 4. Blend bloom using SCREEN or ADD mode for vibrant glow
                val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    bloomPaint.blendMode = android.graphics.BlendMode.SCREEN
                } else {
                    bloomPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                }
                bloomPaint.alpha = 200

                canvas.drawBitmap(fullBloom, 0f, 0f, bloomPaint)
            }
            FilterType.PIXELATE -> {
                val pixelSize = 16
                val smallW = max(1, width / pixelSize)
                val smallH = max(1, height / pixelSize)
                val small = Bitmap.createScaledBitmap(source, smallW, smallH, false)
                return Bitmap.createScaledBitmap(small, width, height, false)
            }
            FilterType.EDGES -> {
                val pixels = IntArray(width * height)
                val outPixels = IntArray(width * height)
                source.getPixels(pixels, 0, width, 0, 0, width, height)

                // 1. Calculate luminance (BT.709) for all pixels weighted by alpha
                val lum = FloatArray(width * height)
                for (i in pixels.indices) {
                    if (i % 32768 == 0 && isCancelled?.invoke() == true) {
                        throw CancellationException("Edges filter cancelled")
                    }
                    val p = pixels[i]
                    val a = (p ushr 24) and 0xFF
                    if (a == 0) {
                        lum[i] = 0f
                    } else {
                        val r = (p ushr 16) and 0xFF
                        val g = (p ushr 8) and 0xFF
                        val b = p and 0xFF
                        lum[i] = (0.2126f * r + 0.7152f * g + 0.0722f * b) * (a / 255f)
                    }
                }

                // 2. Sobel edge detection on luminance preserving sourceAlpha
                for (y in 0 until height) {
                    if (y % 32 == 0 && isCancelled?.invoke() == true) {
                        throw CancellationException("Edges filter cancelled")
                    }
                    for (x in 0 until width) {
                        val idx = y * width + x
                        val origA = (pixels[idx] ushr 24) and 0xFF

                        if (origA == 0) {
                            outPixels[idx] = 0
                            continue
                        }

                        if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                            outPixels[idx] = (origA shl 24) // preserve alpha with black edge
                            continue
                        }

                        val p00 = lum[(y - 1) * width + (x - 1)]
                        val p01 = lum[(y - 1) * width + x]
                        val p02 = lum[(y - 1) * width + (x + 1)]

                        val p10 = lum[y * width + (x - 1)]
                        val p12 = lum[y * width + (x + 1)]

                        val p20 = lum[(y + 1) * width + (x - 1)]
                        val p21 = lum[(y + 1) * width + x]
                        val p22 = lum[(y + 1) * width + (x + 1)]

                        val gx = (p02 + 2f * p12 + p22) - (p00 + 2f * p10 + p20)
                        val gy = (p20 + 2f * p21 + p22) - (p00 + 2f * p01 + p02)

                        val gMag = sqrt(gx * gx + gy * gy)
                        val edgeVal = gMag.toInt().coerceIn(0, 255)

                        outPixels[idx] = (origA shl 24) or (edgeVal shl 16) or (edgeVal shl 8) or edgeVal
                    }
                }
                output.setPixels(outPixels, 0, width, 0, 0, width, height)
            }
            FilterType.GLITCH -> {
                canvas.drawBitmap(source, 0f, 0f, paint)
                val offset = (width * 0.02f).toInt()
                val glitchPixels = IntArray(width * height)
                val outPixels = IntArray(width * height)
                source.getPixels(glitchPixels, 0, width, 0, 0, width, height)
                source.getPixels(outPixels, 0, width, 0, 0, width, height)

                for (y in 0 until height step 8) {
                    if (isCancelled?.invoke() == true) {
                        throw CancellationException("Glitch filter cancelled")
                    }
                    val rowShift = if ((y / 8) % 2 == 0) offset else -offset
                    val rowHeight = min(6, height - y)
                    for (ry in y until (y + rowHeight)) {
                        for (x in 0 until width) {
                            val srcX = (x + rowShift).coerceIn(0, width - 1)
                            val origColor = glitchPixels[ry * width + x]
                            val origA = (origColor ushr 24) and 0xFF
                            if (origA == 0) continue

                            val shiftedColor = glitchPixels[ry * width + srcX]
                            val newR = (shiftedColor shr 16) and 0xFF
                            val newG = (origColor shr 8) and 0xFF
                            val newB = origColor and 0xFF
                            outPixels[ry * width + x] = (origA shl 24) or (newR shl 16) or (newG shl 8) or newB
                        }
                    }
                }
                output.setPixels(outPixels, 0, width, 0, 0, width, height)
            }
            FilterType.ORANGE_TEAL -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        1.22f, 0.05f, -0.18f, 0f, 18f,
                        -0.08f, 1.12f, 0.08f, 0f, -4f,
                        -0.28f, 0.18f, 1.30f, 0f, 22f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
                applyVignetteEffect(output, 20f, isCancelled)
            }
            FilterType.MOODY_DARK -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        0.88f, 0.08f, 0.04f, 0f, -22f,
                        0.04f, 0.88f, 0.08f, 0f, -18f,
                        0.04f, 0.12f, 0.98f, 0f, -8f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
                applyVignetteEffect(output, 38f, isCancelled)
            }
            FilterType.VAPORWAVE -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        1.40f, -0.10f, 0.25f, 0f, 30f,
                        -0.15f, 0.95f, 0.40f, 0f, 12f,
                        0.35f, 0.10f, 1.50f, 0f, 40f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
            FilterType.FILM_NOIR -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        0.55f, 0.80f, 0.15f, 0f, -35f,
                        0.55f, 0.80f, 0.15f, 0f, -35f,
                        0.55f, 0.80f, 0.15f, 0f, -35f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
                applyVignetteEffect(output, 55f, isCancelled)
            }
            FilterType.KODACHROME -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        1.28f, -0.02f, -0.06f, 0f, 12f,
                        -0.02f, 1.18f, -0.04f, 0f, 6f,
                        -0.12f, -0.02f, 1.08f, 0f, -12f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
            FilterType.PASTEL_DREAM -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        0.82f, 0.06f, 0.06f, 0f, 38f,
                        0.06f, 0.82f, 0.06f, 0f, 34f,
                        0.06f, 0.06f, 0.88f, 0f, 42f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
            FilterType.DUOTONE_VIOLET -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        0.85f, 0.35f, 0.10f, 0f, 28f,
                        0.15f, 0.25f, 0.45f, 0f, -22f,
                        0.30f, 0.10f, 0.90f, 0f, 48f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
            FilterType.EMERALD_MINT -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        0.76f, 0.12f, -0.06f, 0f, -12f,
                        0.12f, 1.28f, 0.18f, 0f, 18f,
                        -0.06f, 0.22f, 1.15f, 0f, 12f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
            FilterType.GOLDEN_HOUR -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        1.35f, 0.12f, -0.12f, 0f, 28f,
                        0.06f, 1.18f, -0.12f, 0f, 16f,
                        -0.18f, -0.12f, 0.72f, 0f, -22f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
                applyVignetteEffect(output, 22f, isCancelled)
            }
            FilterType.SOLARIZE -> {
                val pixels = IntArray(width * height)
                source.getPixels(pixels, 0, width, 0, 0, width, height)
                for (i in pixels.indices) {
                    if (i % 65536 == 0 && isCancelled?.invoke() == true) {
                        throw CancellationException("Solarize filter cancelled")
                    }
                    val p = pixels[i]
                    val a = (p ushr 24) and 0xFF
                    if (a == 0) continue
                    var r = (p ushr 16) and 0xFF
                    var g = (p ushr 8) and 0xFF
                    var b = p and 0xFF
                    r = if (r > 128) 255 - r else r * 2
                    g = if (g > 128) 255 - g else g * 2
                    b = if (b > 128) 255 - b else b * 2
                    pixels[i] = (a shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
                }
                output.setPixels(pixels, 0, width, 0, 0, width, height)
            }
            FilterType.BLEACH_BYPASS -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        1.25f, 0.22f, 0.12f, 0f, -32f,
                        0.22f, 1.25f, 0.12f, 0f, -32f,
                        0.22f, 0.22f, 1.35f, 0f, -28f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                val satMatrix = ColorMatrix()
                satMatrix.setSaturation(0.38f)
                cm.postConcat(satMatrix)
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
            FilterType.CROSS_PROCESS -> {
                val cm = ColorMatrix(
                    floatArrayOf(
                        1.32f, -0.06f, 0.06f, 0f, 18f,
                        0.06f, 1.22f, -0.12f, 0f, 12f,
                        -0.12f, 0.18f, 1.28f, 0f, -26f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
                applyVignetteEffect(output, 24f, isCancelled)
            }
            FilterType.NONE -> {}
        }

        return output
    }

    /**
     * Unwarps a perspective quadrilateral into a flat rectangular bitmap.
     * Takes 4 source corners [top-left, top-right, bottom-right, bottom-left]
     * and maps them to a rectangular output of size destWidth x destHeight.
     */
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
     * Advanced Inpainting Algorithm:
     * Supports AI Exemplar Patch Synthesis (Criminisi) for realistic texture preservation
     * or Fast Marching (Telea), with Smart Edge Snapping and Seamless Poisson Blending.
     */
    fun inpaintObjectRemoval(
        source: Bitmap,
        maskBitmap: Bitmap,
        radiusPx: Double = 5.0,
        featherPx: Int = 4,
        mode: InpaintEngineMode = InpaintEngineMode.AI_PATCH_SYNTHESIS,
        smartEdgeSnap: Boolean = true,
        seamlessBlend: Boolean = true
    ): Bitmap {
        require(source.width == maskBitmap.width && source.height == maskBitmap.height) { "Mask size must match source" }
        return InpaintEngine.inpaint(source, maskBitmap, mode, smartEdgeSnap, seamlessBlend)
    }

    private fun inpaintObjectRemovalFallback(source: Bitmap, maskBitmap: Bitmap): Bitmap {
        val width = source.width
        val height = source.height

        val sourcePixels = IntArray(width * height)
        val maskPixels = IntArray(width * height)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        maskBitmap.getPixels(maskPixels, 0, width, 0, 0, width, height)

        // Find bounding box of masked pixels for sub-region performance optimization
        var minX = width
        var maxX = -1
        var minY = height
        var maxY = -1

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

        if (maxX < minX || maxY < minY) return source // No mask present

        // Expand bounding box by margin
        val margin = 16
        val cropX0 = (minX - margin).coerceIn(0, width - 1)
        val cropY0 = (minY - margin).coerceIn(0, height - 1)
        val cropX1 = (maxX + margin).coerceIn(0, width - 1)
        val cropY1 = (maxY + margin).coerceIn(0, height - 1)
        val cropW = cropX1 - cropX0 + 1
        val cropH = cropY1 - cropY0 + 1

        val totalArea = cropW * cropH
        val flag = ByteArray(totalArea) // 0 = KNOWN, 1 = BAND, 2 = INSIDE
        val dist = FloatArray(totalArea)
        val red = FloatArray(totalArea)
        val green = FloatArray(totalArea)
        val blue = FloatArray(totalArea)

        val KNOWN: Byte = 0
        val BAND: Byte = 1
        val INSIDE: Byte = 2

        // Extract binary mask and apply 2-pixel dilation to capture object fringe and edge halos
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
                } else {
                    red[cIdx] = 0f
                    green[cIdx] = 0f
                    blue[cIdx] = 0f
                }
            }
        }

        val dilatedMask = BooleanArray(totalArea)
        val dilationRadius = 2
        for (cy in 0 until cropH) {
            val cRow = cy * cropW
            for (cx in 0 until cropW) {
                val cIdx = cRow + cx
                if (rawMask[cIdx]) {
                    dilatedMask[cIdx] = true
                    for (dy in -dilationRadius..dilationRadius) {
                        val ny = cy + dy
                        if (ny in 0 until cropH) {
                            val nRow = ny * cropW
                            for (dx in -dilationRadius..dilationRadius) {
                                val nx = cx + dx
                                if (nx in 0 until cropW && (dx * dx + dy * dy <= dilationRadius * dilationRadius)) {
                                    dilatedMask[nRow + nx] = true
                                }
                            }
                        }
                    }
                }
            }
        }

        for (cIdx in 0 until totalArea) {
            if (dilatedMask[cIdx]) {
                flag[cIdx] = INSIDE
                dist[cIdx] = 1e9f
            } else {
                flag[cIdx] = KNOWN
                dist[cIdx] = 0f
            }
        }

        // Priority queue for Fast Marching
        val narrowBand = java.util.PriorityQueue<Int>(1024) { a, b ->
            dist[a].compareTo(dist[b])
        }

        // Find initial BAND pixels
        val dx4 = intArrayOf(1, -1, 0, 0)
        val dy4 = intArrayOf(0, 0, 1, -1)

        for (cy in 0 until cropH) {
            val cRow = cy * cropW
            for (cx in 0 until cropW) {
                val cIdx = cRow + cx
                if (flag[cIdx] == INSIDE) {
                    var isNeighborKnown = false
                    for (k in 0 until 4) {
                        val nx = cx + dx4[k]
                        val ny = cy + dy4[k]
                        if (nx in 0 until cropW && ny in 0 until cropH) {
                            if (flag[ny * cropW + nx] == KNOWN) {
                                isNeighborKnown = true
                                break
                            }
                        }
                    }
                    if (isNeighborKnown) {
                        flag[cIdx] = BAND
                        dist[cIdx] = 1f
                        narrowBand.add(cIdx)
                    }
                }
            }
        }

        // Inpainting neighborhood search radius
        val radius = 5
        val maxDist = radius.toFloat()

        // Marching loop
        while (!narrowBand.isEmpty()) {
            val p = narrowBand.poll() ?: break
            if (flag[p] == KNOWN) continue // Lazy update skip

            flag[p] = KNOWN
            val px = p % cropW
            val py = p / cropW

            // Compute color for p using Telea's weighting formula
            var sumR = 0f
            var sumG = 0f
            var sumB = 0f
            var sumW = 0f

            // Distance gradient at p
            var gradTx = 0f
            var gradTy = 0f

            val rightP = if (px < cropW - 1) p + 1 else p
            val leftP = if (px > 0) p - 1 else p
            val downP = if (py < cropH - 1) p + cropW else p
            val upP = if (py > 0) p - cropW else p

            if (flag[rightP] == KNOWN && flag[leftP] == KNOWN) gradTx = (dist[rightP] - dist[leftP]) / 2f
            else if (flag[rightP] == KNOWN) gradTx = dist[rightP] - dist[p]
            else if (flag[leftP] == KNOWN) gradTx = dist[p] - dist[leftP]

            if (flag[downP] == KNOWN && flag[upP] == KNOWN) gradTy = (dist[downP] - dist[upP]) / 2f
            else if (flag[downP] == KNOWN) gradTy = dist[downP] - dist[p]
            else if (flag[upP] == KNOWN) gradTy = dist[p] - dist[upP]

            val gradTNorm = sqrt(gradTx * gradTx + gradTy * gradTy)

            for (dy in -radius..radius) {
                val qy = py + dy
                if (qy !in 0 until cropH) continue
                val qRow = qy * cropW

                for (dx in -radius..radius) {
                    if (dx == 0 && dy == 0) continue
                    val qx = px + dx
                    if (qx !in 0 until cropW) continue

                    val q = qRow + qx
                    if (flag[q] == KNOWN) {
                        val d = sqrt((dx * dx + dy * dy).toFloat())
                        if (d > maxDist || d < 0.1f) continue

                        // Directional weight wDir
                        val dir = if (gradTNorm > 1e-5f) {
                            abs(dx * gradTx + dy * gradTy) / (d * gradTNorm)
                        } else 1.0f

                        // Distance weight wDst
                        val wDst = 1.0f / (d * d)

                        // Level set weight wLev
                        val wLev = 1.0f / (1.0f + abs(dist[p] - dist[q]))

                        val w = dir * wDst * wLev + 1e-6f

                        val estR = red[q]
                        val estG = green[q]
                        val estB = blue[q]

                        sumR += estR * w
                        sumG += estG * w
                        sumB += estB * w
                        sumW += w
                    }
                }
            }

            if (sumW > 0f) {
                red[p] = sumR / sumW
                green[p] = sumG / sumW
                blue[p] = sumB / sumW
            }

            // Update 4-neighbors
            for (k in 0 until 4) {
                val nx = px + dx4[k]
                val ny = py + dy4[k]
                if (nx in 0 until cropW && ny in 0 until cropH) {
                    val n = ny * cropW + nx
                    if (flag[n] != KNOWN) {
                        val newDist = solveEikonal(nx, ny, dist, flag, cropW, cropH, KNOWN)
                        if (flag[n] == INSIDE) {
                            flag[n] = BAND
                            dist[n] = newDist
                            narrowBand.add(n)
                        } else if (flag[n] == BAND && newDist < dist[n]) {
                            dist[n] = newDist
                            narrowBand.add(n) // Lazy update
                        }
                    }
                }
            }
        }

        // Write back inpainted pixels to result bitmap
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val resultPixels = sourcePixels.clone()

        for (cy in 0 until cropH) {
            val sy = cropY0 + cy
            val sRow = sy * width
            val cRow = cy * cropW

            for (cx in 0 until cropW) {
                val cIdx = cRow + cx
                // Only touch pixels that were part of the inpaint region! Leave known pixels 100% pristine!
                if (!dilatedMask[cIdx]) continue

                val sx = cropX0 + cx
                val sIdx = sRow + sx

                val r = red[cIdx].toInt().coerceIn(0, 255)
                val g = green[cIdx].toInt().coerceIn(0, 255)
                val b = blue[cIdx].toInt().coerceIn(0, 255)

                if (!rawMask[cIdx]) {
                    // Outer 2-pixel dilation margin: blend 50/50 with original background for seamless transition
                    val orig = sourcePixels[sIdx]
                    val origR = (orig ushr 16) and 0xFF
                    val origG = (orig ushr 8) and 0xFF
                    val origB = orig and 0xFF
                    val blendR = ((r + origR) shr 1)
                    val blendG = ((g + origG) shr 1)
                    val blendB = ((b + origB) shr 1)
                    resultPixels[sIdx] = (0xFF shl 24) or (blendR shl 16) or (blendG shl 8) or blendB
                } else {
                    resultPixels[sIdx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun solveEikonal(
        x: Int,
        y: Int,
        dist: FloatArray,
        flag: ByteArray,
        width: Int,
        height: Int,
        KNOWN: Byte
    ): Float {
        var tx = 1e9f
        if (x > 0 && flag[y * width + (x - 1)] == KNOWN) tx = min(tx, dist[y * width + (x - 1)])
        if (x < width - 1 && flag[y * width + (x + 1)] == KNOWN) tx = min(tx, dist[y * width + (x + 1)])

        var ty = 1e9f
        if (y > 0 && flag[(y - 1) * width + x] == KNOWN) ty = min(ty, dist[(y - 1) * width + x])
        if (y < height - 1 && flag[(y + 1) * width + x] == KNOWN) ty = min(ty, dist[(y + 1) * width + x])

        if (tx < 1e8f && ty < 1e8f) {
            val d = abs(tx - ty)
            return if (d < 1.0f) {
                (tx + ty + sqrt(2.0f - d * d)) / 2.0f
            } else {
                min(tx, ty) + 1.0f
            }
        } else if (tx < 1e8f) {
            return tx + 1.0f
        } else if (ty < 1e8f) {
            return ty + 1.0f
        }
        return 1e9f
    }

    private fun applyVignetteEffect(
        bitmap: Bitmap,
        intensity: Float,
        isCancelled: (() -> Boolean)? = null
    ) {
        val width = bitmap.width
        val height = bitmap.height
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = sqrt((cx * cx + cy * cy).toDouble()).toFloat()

        val alphaFactor = (intensity / 100f).coerceIn(0f, 1f)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            if (y % 32 == 0 && isCancelled?.invoke() == true) {
                throw CancellationException("Vignette cancelled")
            }
            for (x in 0 until width) {
                val dx = x - cx
                val dy = y - cy
                val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val distNormalized = (dist / maxRadius).coerceIn(0f, 1f)
                val factor = 1.0f - (distNormalized.pow(2.0f) * alphaFactor)

                val idx = y * width + x
                val c = pixels[idx]
                val a = (c shr 24) and 0xFF
                val r = (((c shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
                val b = ((c and 0xFF) * factor).toInt().coerceIn(0, 255)

                pixels[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
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
     * Builds a 256-element Look-Up Table (LUT) from tone curve control points using cubic spline interpolation.
     */
    fun buildCurveLut(points: List<PointF>): IntArray {
        val lut = IntArray(256)
        if (points.isEmpty()) {
            for (i in 0..255) lut[i] = i
            return lut
        }
        val sorted = points.sortedBy { it.x }
        if (sorted.size == 1) {
            val y = sorted[0].y.toInt().coerceIn(0, 255)
            for (i in 0..255) lut[i] = y
            return lut
        }

        var pIdx = 0
        for (i in 0..255) {
            val x = i.toFloat()
            while (pIdx < sorted.size - 2 && sorted[pIdx + 1].x < x) {
                pIdx++
            }
            val p0 = sorted[max(0, pIdx - 1)]
            val p1 = sorted[pIdx]
            val p2 = sorted[min(sorted.size - 1, pIdx + 1)]
            val p3 = sorted[min(sorted.size - 1, pIdx + 2)]

            val dx = (p2.x - p1.x).coerceAtLeast(0.001f)
            val t = ((x - p1.x) / dx).coerceIn(0f, 1f)

            val t2 = t * t
            val t3 = t2 * t
            val y = 0.5f * (
                (2f * p1.y) +
                (-p0.y + p2.y) * t +
                (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2 +
                (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * t3
            )
            lut[i] = y.toInt().coerceIn(0, 255)
        }
        return lut
    }

    /**
     * Professional Gaussian Blur with adjustable radius.
     */
    fun applyGaussianBlur(
        source: Bitmap,
        radiusPx: Float,
        isCancelled: (() -> Boolean)? = null
    ): Bitmap {
        val r = radiusPx.toInt().coerceIn(1, 80)
        return fastBoxBlur(source, r, 3, isCancelled)
    }

    /**
     * Professional Surface Blur (Bilateral Edge-Preserving Filter).
     * Smooths flat regions while preserving sharp contrast edges.
     */
    fun applySurfaceBlur(
        source: Bitmap,
        radius: Int,
        threshold: Int,
        isCancelled: (() -> Boolean)? = null
    ): Bitmap {
        val w = source.width
        val h = source.height
        val r = radius.coerceIn(1, 20)
        val thresh = threshold.coerceIn(2, 100)

        val srcPixels = IntArray(w * h)
        val dstPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)

        for (y in 0 until h) {
            if (y % 16 == 0 && isCancelled?.invoke() == true) {
                throw CancellationException("Surface blur cancelled")
            }
            for (x in 0 until w) {
                val idx = y * w + x
                val centerColor = srcPixels[idx]
                val ca = (centerColor ushr 24) and 0xFF
                if (ca == 0) {
                    dstPixels[idx] = 0
                    continue
                }
                val cr = (centerColor ushr 16) and 0xFF
                val cg = (centerColor ushr 8) and 0xFF
                val cb = centerColor and 0xFF

                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var sumW = 0f

                val minY = max(0, y - r)
                val maxY = min(h - 1, y + r)
                val minX = max(0, x - r)
                val maxX = min(w - 1, x + r)

                for (ny in minY..maxY) {
                    val rowOffset = ny * w
                    for (nx in minX..maxX) {
                        val neighborColor = srcPixels[rowOffset + nx]
                        val na = (neighborColor ushr 24) and 0xFF
                        if (na == 0) continue

                        val nr = (neighborColor ushr 16) and 0xFF
                        val ng = (neighborColor ushr 8) and 0xFF
                        val nb = neighborColor and 0xFF

                        val diff = max(abs(cr - nr), max(abs(cg - ng), abs(cb - nb)))
                        if (diff <= thresh) {
                            val wDiff = 1f - (diff.toFloat() / thresh)
                            val distSq = (nx - x) * (nx - x) + (ny - y) * (ny - y)
                            val wDist = 1f / (1f + distSq * 0.1f)
                            val weight = wDiff * wDist

                            sumR += nr * weight
                            sumG += ng * weight
                            sumB += nb * weight
                            sumW += weight
                        }
                    }
                }

                if (sumW > 0.001f) {
                    val outR = (sumR / sumW).toInt().coerceIn(0, 255)
                    val outG = (sumG / sumW).toInt().coerceIn(0, 255)
                    val outB = (sumB / sumW).toInt().coerceIn(0, 255)
                    dstPixels[idx] = (ca shl 24) or (outR shl 16) or (outG shl 8) or outB
                } else {
                    dstPixels[idx] = centerColor
                }
            }
        }

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        output.setPixels(dstPixels, 0, w, 0, 0, w, h)
        return output
    }

    /**
     * Professional Oil Painting artistic effect.
     * Quantizes intensities in neighborhood and assigns dominant bin average color.
     */
    fun applyOilPaint(
        source: Bitmap,
        radius: Int,
        levels: Int,
        isCancelled: (() -> Boolean)? = null
    ): Bitmap {
        val w = source.width
        val h = source.height
        val r = radius.coerceIn(1, 8)
        val numLevels = levels.coerceIn(5, 30)

        val srcPixels = IntArray(w * h)
        val dstPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)

        val intensityCount = IntArray(numLevels)
        val sumR = IntArray(numLevels)
        val sumG = IntArray(numLevels)
        val sumB = IntArray(numLevels)

        for (y in 0 until h) {
            if (y % 16 == 0 && isCancelled?.invoke() == true) {
                throw CancellationException("Oil paint cancelled")
            }
            for (x in 0 until w) {
                val centerIdx = y * w + x
                val ca = (srcPixels[centerIdx] ushr 24) and 0xFF
                if (ca == 0) {
                    dstPixels[centerIdx] = 0
                    continue
                }

                intensityCount.fill(0)
                sumR.fill(0)
                sumG.fill(0)
                sumB.fill(0)

                val minY = max(0, y - r)
                val maxY = min(h - 1, y + r)
                val minX = max(0, x - r)
                val maxX = min(w - 1, x + r)

                for (ny in minY..maxY) {
                    val row = ny * w
                    for (nx in minX..maxX) {
                        val p = srcPixels[row + nx]
                        val pa = (p ushr 24) and 0xFF
                        if (pa == 0) continue

                        val pr = (p ushr 16) and 0xFF
                        val pg = (p ushr 8) and 0xFF
                        val pb = p and 0xFF

                        val curIntensity = ((pr + pg + pb) / 3f)
                        val lvl = ((curIntensity * (numLevels - 1)) / 255f).toInt().coerceIn(0, numLevels - 1)

                        intensityCount[lvl]++
                        sumR[lvl] += pr
                        sumG[lvl] += pg
                        sumB[lvl] += pb
                    }
                }

                var maxCount = 0
                var maxIndex = 0
                for (l in 0 until numLevels) {
                    if (intensityCount[l] > maxCount) {
                        maxCount = intensityCount[l]
                        maxIndex = l
                    }
                }

                if (maxCount > 0) {
                    val finalR = (sumR[maxIndex] / maxCount).coerceIn(0, 255)
                    val finalG = (sumG[maxIndex] / maxCount).coerceIn(0, 255)
                    val finalB = (sumB[maxIndex] / maxCount).coerceIn(0, 255)
                    dstPixels[centerIdx] = (ca shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
                } else {
                    dstPixels[centerIdx] = srcPixels[centerIdx]
                }
            }
        }

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        output.setPixels(dstPixels, 0, w, 0, 0, w, h)
        return output
    }

    /**
     * Professional Sharpening (Unsharp Masking).
     */
    fun applySharpen(
        source: Bitmap,
        amount: Float,
        radius: Float,
        isCancelled: (() -> Boolean)? = null
    ): Bitmap {
        val r = radius.toInt().coerceIn(1, 10)
        val blurred = fastBoxBlur(source, r, 2, isCancelled)
        val w = source.width
        val h = source.height

        val srcPixels = IntArray(w * h)
        val blurPixels = IntArray(w * h)
        val outPixels = IntArray(w * h)

        source.getPixels(srcPixels, 0, w, 0, 0, w, h)
        blurred.getPixels(blurPixels, 0, w, 0, 0, w, h)
        if (!blurred.isRecycled) blurred.recycle()

        val amt = amount.coerceIn(0.1f, 3.0f)
        for (i in srcPixels.indices) {
            if (i % 32768 == 0 && isCancelled?.invoke() == true) {
                throw CancellationException("Sharpen cancelled")
            }
            val sp = srcPixels[i]
            val bp = blurPixels[i]
            val a = (sp ushr 24) and 0xFF
            if (a == 0) {
                outPixels[i] = 0
                continue
            }

            val sr = (sp ushr 16) and 0xFF
            val sg = (sp ushr 8) and 0xFF
            val sb = sp and 0xFF

            val br = (bp ushr 16) and 0xFF
            val bg = (bp ushr 8) and 0xFF
            val bb = bp and 0xFF

            val nr = (sr + amt * (sr - br)).toInt().coerceIn(0, 255)
            val ng = (sg + amt * (sg - bg)).toInt().coerceIn(0, 255)
            val nb = (sb + amt * (sb - bb)).toInt().coerceIn(0, 255)

            outPixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, w, 0, 0, w, h)
        return output
    }

    /**
     * Professional Noise Reduction (Adaptive local smoothing).
     */
    fun applyNoiseReduction(
        source: Bitmap,
        strength: Float,
        isCancelled: (() -> Boolean)? = null
    ): Bitmap {
        val str = strength.coerceIn(0.1f, 1.0f)
        val r = if (str > 0.6f) 2 else 1
        val thresh = (str * 45f).toInt()
        return applySurfaceBlur(source, r, thresh, isCancelled)
    }

    /**
     * Professional Directional Motion Blur.
     */
    fun applyMotionBlur(
        source: Bitmap,
        distance: Int,
        angleDegrees: Float,
        isCancelled: (() -> Boolean)? = null
    ): Bitmap {
        val dist = distance.coerceIn(1, 50)
        val rad = Math.toRadians(angleDegrees.toDouble())
        val cosA = Math.cos(rad).toFloat()
        val sinA = Math.sin(rad).toFloat()

        val w = source.width
        val h = source.height
        val srcPixels = IntArray(w * h)
        val outPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)

        val halfDist = dist / 2
        val stepCount = dist.coerceAtLeast(1)

        for (y in 0 until h) {
            if (y % 16 == 0 && isCancelled?.invoke() == true) {
                throw CancellationException("Motion blur cancelled")
            }
            for (x in 0 until w) {
                var sumA = 0
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var validSamples = 0

                for (s in -halfDist..halfDist) {
                    val sx = (x + s * cosA).toInt().coerceIn(0, w - 1)
                    val sy = (y + s * sinA).toInt().coerceIn(0, h - 1)
                    val p = srcPixels[sy * w + sx]
                    val a = (p ushr 24) and 0xFF
                    sumA += a
                    sumR += (p ushr 16) and 0xFF
                    sumG += (p ushr 8) and 0xFF
                    sumB += p and 0xFF
                    validSamples++
                }

                val outA = (sumA / validSamples).coerceIn(0, 255)
                val outR = (sumR / validSamples).coerceIn(0, 255)
                val outG = (sumG / validSamples).coerceIn(0, 255)
                val outB = (sumB / validSamples).coerceIn(0, 255)
                outPixels[y * w + x] = (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, w, 0, 0, w, h)
        return output
    }

    /**
     * Professional Radial / Zoom Blur outward from canvas center.
     */
    fun applyRadialBlur(
        source: Bitmap,
        amount: Float,
        isCancelled: (() -> Boolean)? = null
    ): Bitmap {
        val w = source.width
        val h = source.height
        val cx = w / 2f
        val cy = h / 2f
        val strength = (amount / 100f).coerceIn(0.01f, 0.4f)
        val samples = 10

        val srcPixels = IntArray(w * h)
        val outPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)

        for (y in 0 until h) {
            if (y % 16 == 0 && isCancelled?.invoke() == true) {
                throw CancellationException("Radial blur cancelled")
            }
            val dy = y - cy
            for (x in 0 until w) {
                val dx = x - cx
                var sumA = 0
                var sumR = 0
                var sumG = 0
                var sumB = 0

                for (s in 0 until samples) {
                    val scale = 1f - strength * (s.toFloat() / samples)
                    val sx = (cx + dx * scale).toInt().coerceIn(0, w - 1)
                    val sy = (cy + dy * scale).toInt().coerceIn(0, h - 1)
                    val p = srcPixels[sy * w + sx]
                    sumA += (p ushr 24) and 0xFF
                    sumR += (p ushr 16) and 0xFF
                    sumG += (p ushr 8) and 0xFF
                    sumB += p and 0xFF
                }

                val outA = (sumA / samples).coerceIn(0, 255)
                val outR = (sumR / samples).coerceIn(0, 255)
                val outG = (sumG / samples).coerceIn(0, 255)
                val outB = (sumB / samples).coerceIn(0, 255)
                outPixels[y * w + x] = (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, w, 0, 0, w, h)
        return output
    }

    /**
     * Dispatcher for all Pro Blur & Effect filters.
     */
    fun applyProEffect(
        source: Bitmap,
        type: ProFilterType,
        param1: Float,
        param2: Float = 0f,
        isCancelled: (() -> Boolean)? = null
    ): Bitmap {
        return when (type) {
            ProFilterType.GAUSSIAN_BLUR -> applyGaussianBlur(source, param1, isCancelled)
            ProFilterType.SURFACE_BLUR -> applySurfaceBlur(source, param1.toInt(), param2.toInt(), isCancelled)
            ProFilterType.OIL_PAINT -> applyOilPaint(source, param1.toInt(), param2.toInt(), isCancelled)
            ProFilterType.SHARPEN -> applySharpen(source, param1, param2, isCancelled)
            ProFilterType.NOISE_REDUCTION -> applyNoiseReduction(source, param1, isCancelled)
            ProFilterType.MOTION_BLUR -> applyMotionBlur(source, param1.toInt(), param2, isCancelled)
            ProFilterType.RADIAL_BLUR -> applyRadialBlur(source, param1, isCancelled)
        }
    }
}

