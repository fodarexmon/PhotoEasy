package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.example.data.model.Layer
import com.example.data.model.LayerType
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Output file formats. */
enum class ExportFormat(val extension: String, val mimeType: String, val supportsAlpha: Boolean, val lossy: Boolean) {
    JPEG("jpg", "image/jpeg", false, true),
    PNG("png", "image/png", true, false),
    WEBP("webp", "image/webp", true, true),
    WEBP_LOSSLESS("webp", "image/webp", true, false);

    companion object {
        /** Lossless WebP needs Android 10+ (quality 100 = lossless) or 11+ (WEBP_LOSSLESS). */
        fun available(): List<ExportFormat> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) values().toList() else listOf(JPEG, PNG, WEBP)
    }
}

enum class ExportSizeMode { ORIGINAL, PERCENT, LONG_EDGE }

/** What happens to the photo's metadata (camera, date, copyright, location). */
enum class ExportMetadata { KEEP_ALL, NO_LOCATION, NONE }

/** What is exported. */
enum class ExportScope { WHOLE, SELECTION, LAYERS }

enum class ExportDestination { GALLERY, FILES }

/** All export choices; remembered between exports. */
data class ExportSettings(
    val format: ExportFormat = ExportFormat.JPEG,
    val quality: Int = 92,
    val sizeMode: ExportSizeMode = ExportSizeMode.ORIGINAL,
    val percent: Int = 100,
    val longEdge: Int = 2048,
    /** Light output sharpening after downscaling (recovers the crispness lost by resampling). */
    val sharpenOnResize: Boolean = true,
    /** Colour behind transparent areas for formats without transparency (JPEG). */
    val jpegBackground: Int = Color.WHITE,
    /** Hide the background fill layer(s) at the bottom so the result is transparent (PNG / WebP). */
    val transparentBackground: Boolean = true,
    val metadata: ExportMetadata = ExportMetadata.NO_LOCATION,
    val scope: ExportScope = ExportScope.WHOLE,
    val destination: ExportDestination = ExportDestination.GALLERY,
    val shareAfter: Boolean = false,
    /** Base file name without extension (empty = project name). */
    val fileName: String = ""
) {
    fun toJson(): String = JSONObject().apply {
        put("format", format.name); put("quality", quality); put("sizeMode", sizeMode.name)
        put("percent", percent); put("longEdge", longEdge); put("sharpen", sharpenOnResize)
        put("jpegBg", jpegBackground); put("transparent", transparentBackground)
        put("metadata", metadata.name); put("scope", scope.name); put("destination", destination.name)
        put("share", shareAfter)
    }.toString()

    companion object {
        fun fromJson(json: String?): ExportSettings {
            if (json.isNullOrBlank()) return ExportSettings()
            return try {
                val o = JSONObject(json)
                fun <T : Enum<T>> en(values: Array<T>, key: String, def: T): T =
                    values.firstOrNull { it.name == o.optString(key) } ?: def
                val d = ExportSettings()
                val fmt = en(ExportFormat.values(), "format", d.format).let { if (it in ExportFormat.available()) it else ExportFormat.WEBP }
                ExportSettings(
                    format = fmt,
                    quality = o.optInt("quality", d.quality).coerceIn(1, 100),
                    sizeMode = en(ExportSizeMode.values(), "sizeMode", d.sizeMode),
                    percent = o.optInt("percent", d.percent).coerceIn(5, 400),
                    longEdge = o.optInt("longEdge", d.longEdge).coerceIn(16, 16384),
                    sharpenOnResize = o.optBoolean("sharpen", d.sharpenOnResize),
                    jpegBackground = o.optInt("jpegBg", d.jpegBackground),
                    transparentBackground = o.optBoolean("transparent", d.transparentBackground),
                    metadata = en(ExportMetadata.values(), "metadata", d.metadata),
                    // A selection export only makes sense right now; start from the whole image.
                    scope = en(ExportScope.values(), "scope", d.scope).let { if (it == ExportScope.SELECTION) ExportScope.WHOLE else it },
                    destination = en(ExportDestination.values(), "destination", d.destination),
                    shareAfter = o.optBoolean("share", d.shareAfter)
                )
            } catch (e: Exception) {
                ExportSettings()
            }
        }
    }
}

object ExportEngine {

    /** The near-black fill an import used to add under every photo (also the "Black" preset). */
    const val LEGACY_AUTO_BACKGROUND = 0xFF12131C.toInt()

    /** Output size in pixels for a [w] x [h] source. */
    fun outputSize(w: Int, h: Int, s: ExportSettings): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 1 to 1
        val scale = when (s.sizeMode) {
            ExportSizeMode.ORIGINAL -> 1f
            ExportSizeMode.PERCENT -> s.percent.coerceIn(5, 400) / 100f
            ExportSizeMode.LONG_EDGE -> s.longEdge.coerceIn(16, 16384).toFloat() / max(w, h)
        }
        return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
    }

    /** Index range of the bottom background fill layers (visible colour fills under everything else). */
    private fun backgroundFillCount(layers: List<Layer>): Int {
        var n = 0
        while (n < layers.size && layers[n].type == LayerType.COLOR_FILL && !layers[n].isClipped) n++
        return n
    }

    /** True when the image has a background fill layer that a transparent export would hide. */
    fun hasBackgroundFill(layers: List<Layer>): Boolean =
        layers.take(backgroundFillCount(layers)).any { it.isVisible && (it.colorFillHex ushr 24) != 0 }

    /**
     * Default for "transparent background": on when the bottom fill is the dark colour an import
     * used to add (that layer was never the user's choice); a user-picked white / coloured
     * background is kept unless the user turns the option on.
     */
    fun defaultTransparent(layers: List<Layer>): Boolean =
        layers.take(backgroundFillCount(layers)).any { it.isVisible && it.colorFillHex == LEGACY_AUTO_BACKGROUND }

    /** The layers to render, without the bottom background fill when [transparent] is on. */
    fun layersForExport(layers: List<Layer>, transparent: Boolean): List<Layer> =
        if (transparent) layers.drop(backgroundFillCount(layers)) else layers

    /**
     * Top-level layer groups for "each layer separately": a base layer with its clipped layers.
     * Adjustment layers and background fills are skipped (they have no pixels of their own).
     */
    fun layerGroups(layers: List<Layer>): List<List<Layer>> {
        val out = ArrayList<List<Layer>>()
        var i = 0
        while (i < layers.size) {
            val base = layers[i]
            var j = i + 1
            while (j < layers.size && layers[j].isClipped) j++
            if (base.isVisible && base.type != LayerType.ADJUSTMENT && base.type != LayerType.COLOR_FILL) {
                out.add(layers.subList(i, j).toList())
            }
            i = j
        }
        return out
    }

    /**
     * High-quality resize: halving steps with filtering (no aliasing when shrinking a lot), a
     * final exact step, then optional light output sharpening. Returns [src] when no resize is needed.
     */
    fun resize(src: Bitmap, w: Int, h: Int, sharpen: Boolean, isCancelled: (() -> Boolean)? = null): Bitmap {
        if (src.width == w && src.height == h) return src
        var cur = src
        while (cur.width / 2 >= w && cur.height / 2 >= h && cur.width >= 4 && cur.height >= 4) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val sized = Bitmap.createScaledBitmap(cur, w, h, true)
        if (cur !== src && cur !== sized) cur.recycle()
        val downscaled = w < src.width * 0.85f
        if (!sharpen || !downscaled) return sized
        val sharp = ProEffects.apply(sized, com.example.data.model.ProFilterType.SHARPEN, ProEffectParams(35f, 0.6f, 2f), isCancelled)
        if (sharp !== sized) sized.recycle()
        return sharp
    }

    /** Masks [composite] by the document-space [docMask] and crops to the selection bounds. */
    fun selectionOnly(composite: Bitmap, docMask: Bitmap): Bitmap? {
        val bounds = SelectionEngine.selectionBounds(docMask) ?: return null
        val sx = composite.width.toFloat() / docMask.width
        val sy = composite.height.toFloat() / docMask.height
        val crop = Rect(
            (bounds.left * sx).toInt().coerceIn(0, composite.width - 1),
            (bounds.top * sy).toInt().coerceIn(0, composite.height - 1),
            kotlin.math.ceil(bounds.right * sx).toInt().coerceIn(1, composite.width),
            kotlin.math.ceil(bounds.bottom * sy).toInt().coerceIn(1, composite.height)
        )
        if (crop.width() <= 0 || crop.height() <= 0) return null
        val out = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(composite, crop, Rect(0, 0, crop.width(), crop.height()), null)
        // Keep only the selected pixels (soft edges stay soft).
        val m = android.graphics.Matrix().apply {
            postScale(sx, sy)
            postTranslate(-crop.left.toFloat(), -crop.top.toFloat())
        }
        c.drawBitmap(docMask, m, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        return out
    }

    /** Flattens [src] over [color] (JPEG has no transparency; Android would otherwise use black). */
    fun flatten(src: Bitmap, color: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        out.eraseColor(color or 0xFF000000.toInt())
        Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Encodes [bmp]; transparency is kept for PNG / WebP. */
    @Suppress("DEPRECATION")
    fun encode(bmp: Bitmap, format: ExportFormat, quality: Int, out: OutputStream): Boolean {
        val q = quality.coerceIn(1, 100)
        return when (format) {
            ExportFormat.JPEG -> bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
            ExportFormat.PNG -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            ExportFormat.WEBP ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, out)
                else bmp.compress(Bitmap.CompressFormat.WEBP, q.coerceAtMost(99), out)
            ExportFormat.WEBP_LOSSLESS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                else bmp.compress(Bitmap.CompressFormat.WEBP, 100, out)
        }
    }

    private val BASIC_TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_EXPOSURE_PROGRAM
    )
    private val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP
    )

    /**
     * Writes metadata into the encoded file [target]: the original photo's tags from [source]
     * (camera, date, copyright, and location only with [ExportMetadata.KEEP_ALL]), orientation reset
     * to normal (the pixels are already upright) and the software name. NONE writes nothing.
     */
    fun writeMetadata(source: File?, target: File, mode: ExportMetadata, width: Int, height: Int) {
        if (mode == ExportMetadata.NONE) return
        try {
            val out = ExifInterface(target.absolutePath)
            val src = source?.takeIf { it.exists() }?.let { f -> try { ExifInterface(f.absolutePath) } catch (e: Exception) { null } }
            if (src != null) {
                val tags = if (mode == ExportMetadata.KEEP_ALL) BASIC_TAGS + GPS_TAGS else BASIC_TAGS
                for (t in tags) src.getAttribute(t)?.let { out.setAttribute(t, it) }
            }
            out.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            out.setAttribute(ExifInterface.TAG_SOFTWARE, "PhotoEasy")
            out.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            out.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            out.saveAttributes()
        } catch (e: Exception) {
            // Metadata is optional: the image itself is already written.
        }
    }

    /** File names: letters / digits of any script, dash and underscore only. */
    fun safeFileName(name: String, fallback: String = "PhotoEasy"): String =
        name.trim().replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').ifEmpty { fallback }.take(60)

    /** "2.4 MB", "820 KB". */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "${(bytes / 1024.0).roundToInt()} KB"
        else -> "$bytes B"
    }

    /** Estimated file size of a [w] x [h] export from an encoded sample of [sampleW] x [sampleH] px. */
    fun estimateBytes(sampleBytes: Long, sampleW: Int, sampleH: Int, w: Int, h: Int): Long {
        val ratio = (w.toDouble() * h) / max(1.0, sampleW.toDouble() * sampleH)
        return (sampleBytes * ratio).toLong().coerceAtLeast(1L)
    }

    /** A 1:1 crop of the image centre as it will look after compression (shows JPEG / WebP artefacts). */
    fun compressionPreview(src: Bitmap, format: ExportFormat, quality: Int, size: Int, background: Int): Bitmap? {
        val cw = min(size, src.width)
        val ch = min(size, src.height)
        if (cw <= 0 || ch <= 0) return null
        val crop = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
        val input = if (format.supportsAlpha) crop else flatten(crop, background)
        val bytes = java.io.ByteArrayOutputStream()
        if (!encode(input, format, quality, bytes)) return null
        if (input !== crop) input.recycle()
        if (crop !== src) crop.recycle()
        val data = bytes.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
    }
}
