package com.example.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.R
import com.example.data.model.BlendModeType
import com.example.data.model.ColorAdjustments
import com.example.data.model.FilterType
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Project
import com.example.data.model.TextAlignment
import com.example.data.model.TextLayerConfig
import com.example.engine.LayerRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

import com.example.data.model.ProjectSummary

class ProjectRepository(private val context: Context) {

    val projectsDir: File
        get() = File(context.filesDir, "projects").apply { if (!exists()) mkdirs() }

    fun getProjectDir(projectId: String): File =
        File(projectsDir, projectId).apply { if (!exists()) mkdirs() }

    private inline fun <reified T : Enum<T>> safeValueOf(value: String?, default: T): T {
        if (value.isNullOrEmpty()) return default
        return try {
            enumValueOf<T>(value)
        } catch (e: Exception) {
            default
        }
    }

    suspend fun getProjectSummaries(): List<ProjectSummary> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ProjectSummary>()
        val dirs = projectsDir.listFiles { file -> file.isDirectory } ?: return@withContext emptyList()

        for (dir in dirs) {
            val projectFile = File(dir, "project.json")
            val tmpFile = File(dir, "project.json.tmp")
            val targetFile = when {
                projectFile.exists() -> projectFile
                tmpFile.exists() -> tmpFile
                else -> null
            }
            if (targetFile != null) {
                try {
                    val jsonStr = targetFile.readText()
                    val summary = parseProjectSummaryJson(jsonStr)
                    list.add(summary)
                } catch (e: Exception) {
                    // Try fallback if primary was corrupt
                    if (targetFile == projectFile && tmpFile.exists()) {
                        try {
                            val summary = parseProjectSummaryJson(tmpFile.readText())
                            list.add(summary)
                        } catch (e2: Exception) {
                            e2.printStackTrace()
                        }
                    } else {
                        e.printStackTrace()
                    }
                }
            }
        }

        list.sortedByDescending { it.updatedAt }
    }

    suspend fun getProjectById(projectId: String): Project? = withContext(Dispatchers.IO) {
        val dir = File(projectsDir, projectId)
        val projectFile = File(dir, "project.json")
        val tmpFile = File(dir, "project.json.tmp")
        val targetFile = when {
            projectFile.exists() -> projectFile
            tmpFile.exists() -> tmpFile
            else -> null
        } ?: return@withContext null

        try {
            val jsonStr = targetFile.readText()
            parseProjectJson(jsonStr, dir)
        } catch (e: Exception) {
            if (targetFile == projectFile && tmpFile.exists()) {
                try {
                    parseProjectJson(tmpFile.readText(), dir)
                } catch (e2: Exception) {
                    e2.printStackTrace()
                    null
                }
            } else {
                e.printStackTrace()
                null
            }
        }
    }

    suspend fun saveProject(project: Project): Boolean = withContext(Dispatchers.IO) {
        try {
            val projDir = File(projectsDir, project.id).apply { if (!exists()) mkdirs() }
            project.updatedAt = System.currentTimeMillis()

            val activeLayerFiles = mutableSetOf<String>()

            // Save layer bitmaps & build JSON
            val jsonObj = JSONObject().apply {
                put("schemaVersion", 4)
                put("id", project.id)
                put("name", project.name)
                put("width", project.width)
                put("height", project.height)
                put("createdAt", project.createdAt)
                put("updatedAt", project.updatedAt)

                val layersArray = JSONArray()
                for (layer in project.layers) {
                    val layerObj = JSONObject().apply {
                        put("id", layer.id)
                        put("name", layer.name)
                        put("type", layer.type.name)
                        put("opacity", layer.opacity.toDouble())
                        put("isVisible", layer.isVisible)
                        put("isLocked", layer.isLocked)
                        put("blendMode", layer.blendMode.name)
                        put("positionX", layer.positionX.toDouble())
                        put("positionY", layer.positionY.toDouble())
                        put("scaleX", layer.scaleX.toDouble())
                        put("scaleY", layer.scaleY.toDouble())
                        put("rotation", layer.rotation.toDouble())
                        put("colorFillHex", layer.colorFillHex)
                        put("filter", layer.filter.name)

                        // Save adjustments config
                        val adjObj = JSONObject().apply {
                            put("brightness", layer.adjustments.brightness.toDouble())
                            put("contrast", layer.adjustments.contrast.toDouble())
                            put("saturation", layer.adjustments.saturation.toDouble())
                            put("temperature", layer.adjustments.temperature.toDouble())
                            put("tint", layer.adjustments.tint.toDouble())
                            put("exposure", layer.adjustments.exposure.toDouble())
                            put("highlights", layer.adjustments.highlights.toDouble())
                            put("shadows", layer.adjustments.shadows.toDouble())
                            put("sharpness", layer.adjustments.sharpness.toDouble())
                            put("vignette", layer.adjustments.vignette.toDouble())
                        }
                        put("adjustments", adjObj)

                        // Save text config
                        val textObj = JSONObject().apply {
                            put("text", layer.textConfig.text)
                            put("fontName", layer.textConfig.fontName)
                            put("fontSizeSp", layer.textConfig.fontSizeSp.toDouble())
                            put("colorHex", layer.textConfig.colorHex)
                            put("strokeColorHex", layer.textConfig.strokeColorHex)
                            put("strokeWidthDp", layer.textConfig.strokeWidthDp.toDouble())
                            put("alignment", layer.textConfig.alignment.name)
                            put("shadowColorHex", layer.textConfig.shadowColorHex)
                            put("shadowRadiusDp", layer.textConfig.shadowRadiusDp.toDouble())
                            put("lineSpacingMultiplier", layer.textConfig.lineSpacingMultiplier.toDouble())
                            put("letterSpacingEm", layer.textConfig.letterSpacingEm.toDouble())
                            put("maxLineWidthPercent", layer.textConfig.maxLineWidthPercent.toDouble())
                        }
                        put("textConfig", textObj)

                        // Save bitmap if present (using dirty flag, transactional save & WebP format)
                        layer.bitmap?.let { bmp ->
                            val preferredFileName = "layer_${layer.id}.webp"
                            val legacyFileName = "layer_${layer.id}.png"
                            val preferredFile = File(projDir, preferredFileName)
                            val legacyFile = File(projDir, legacyFileName)

                            val actualFileName = if (preferredFile.exists() && !layer.isBitmapDirty) {
                                preferredFileName
                            } else if (legacyFile.exists() && !layer.isBitmapDirty) {
                                legacyFileName
                            } else {
                                val tmpFileName = "$preferredFileName.tmp"
                                val tmpFile = File(projDir, tmpFileName)
                                val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    Bitmap.CompressFormat.WEBP_LOSSLESS
                                } else {
                                    @Suppress("DEPRECATION")
                                    Bitmap.CompressFormat.WEBP
                                }
                                FileOutputStream(tmpFile).use { out ->
                                    if (!bmp.compress(compressFormat, 95, out)) throw java.io.IOException("Layer encoding failed")
                                    out.flush(); out.fd.sync()
                                }
                                val committed = tmpFile.renameTo(preferredFile) || run { if (preferredFile.exists() && !preferredFile.delete()) false else tmpFile.renameTo(preferredFile) }
                                if (!committed) throw java.io.IOException("Layer file commit failed")
                                layer.isBitmapDirty = false
                                preferredFileName
                            }

                            activeLayerFiles.add(actualFileName)
                            put("bitmapFile", actualFileName)
                        }

                        layer.sourceAssetPath?.let { sourcePath ->
                            put("sourceAssetPath", sourcePath)
                            put("originalWidth", layer.originalWidth)
                            put("originalHeight", layer.originalHeight)
                            put("contentWidth", layer.intrinsicDocumentWidth().toDouble())
                            put("contentHeight", layer.intrinsicDocumentHeight().toDouble())
                            // Mark source asset as active if it's stored inside project dir
                            val sourceFileName = File(sourcePath).name
                            activeLayerFiles.add(sourceFileName)
                        }

                        put("isMaskEnabled", layer.isMaskEnabled)
                        put("isEditingMask", layer.isEditingMask)
                        layer.maskBitmap?.let { maskBmp ->
                            if (!maskBmp.isRecycled) {
                                val maskFileName = "mask_${layer.id}.webp"
                                val maskFile = File(projDir, maskFileName)
                                val tmpMaskFile = File(projDir, "$maskFileName.tmp")
                                val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    Bitmap.CompressFormat.WEBP_LOSSLESS
                                } else {
                                    @Suppress("DEPRECATION")
                                    Bitmap.CompressFormat.WEBP
                                }
                                try {
                                    FileOutputStream(tmpMaskFile).use { out ->
                                        maskBmp.compress(compressFormat, 95, out)
                                        out.flush(); out.fd.sync()
                                    }
                                    tmpMaskFile.renameTo(maskFile)
                                    activeLayerFiles.add(maskFileName)
                                    put("maskFile", maskFileName)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    layersArray.put(layerObj)
                }
                put("layers", layersArray)
            }

            // Render thumbnail preview directly at 320px target size
            val thumbBmp = LayerRenderer.renderThumbnail(context, project.layers, project.width, project.height, 320)
            val thumbFile = File(projDir, "thumbnail.jpg")
            val tmpThumbFile = File(projDir, "thumbnail.jpg.tmp")
            FileOutputStream(tmpThumbFile).use { out ->
                if (!thumbBmp.compress(Bitmap.CompressFormat.JPEG, 85, out)) throw java.io.IOException("Thumbnail encoding failed")
                out.flush(); out.fd.sync()
            }
            if (!thumbBmp.isRecycled) {
                thumbBmp.recycle()
            }
            val thumbCommitted = tmpThumbFile.renameTo(thumbFile) || run { if (thumbFile.exists() && !thumbFile.delete()) false else tmpThumbFile.renameTo(thumbFile) }
            if (!thumbCommitted) throw java.io.IOException("Thumbnail commit failed")
            jsonObj.put("thumbnailPath", thumbFile.absolutePath)
            project.thumbnailPath = thumbFile.absolutePath

            // Write JSON atomically with fsync via temporary file
            val tmpJsonFile = File(projDir, "project.json.tmp")
            val targetJsonFile = File(projDir, "project.json")
            FileOutputStream(tmpJsonFile).use { out ->
                out.write(jsonObj.toString(2).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            val manifestCommitted = tmpJsonFile.renameTo(targetJsonFile) || run { if (targetJsonFile.exists() && !targetJsonFile.delete()) false else tmpJsonFile.renameTo(targetJsonFile) }
            if (!manifestCommitted) throw java.io.IOException("Project manifest commit failed")

            // CRITICAL: Cleanup orphaned files ONLY AFTER new manifest has been successfully committed
            projDir.listFiles()?.forEach { file ->
                if ((file.name.startsWith("layer_") || file.name.startsWith("mask_")) && !file.name.endsWith(".tmp") && !activeLayerFiles.contains(file.name)) {
                    file.delete()
                } else if (file.name.endsWith(".tmp") && System.currentTimeMillis() - file.lastModified() > 60000) {
                    file.delete()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun deleteProject(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(projectsDir, projectId)
        if (dir.exists()) {
            dir.deleteRecursively()
        } else false
    }

    suspend fun createSampleProject(): Project = withContext(Dispatchers.IO) {
        val width = 1080
        val height = 1080
        val proj = Project(
            name = context.getString(R.string.sample_project_name),
            width = width,
            height = height
        )

        // Background Color Layer
        val bgLayer = Layer(
            id = "layer_bg",
            name = context.getString(R.string.layer_name_background),
            type = LayerType.COLOR_FILL,
            colorFillHex = 0xFF12131C.toInt()
        )

        // Sample Photo Graphics Layer
        val sampleBmp = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sampleBmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Draw sunset gradient sample circle
        paint.color = 0xFF6366F1.toInt()
        canvas.drawCircle(400f, 400f, 320f, paint)

        paint.color = 0xFF06B6D4.toInt()
        canvas.drawCircle(360f, 360f, 220f, paint)

        paint.color = 0xFFF59E0B.toInt()
        canvas.drawCircle(300f, 300f, 120f, paint)

        val imageLayer = Layer(
            id = "layer_sample_photo",
            name = context.getString(R.string.sample_layer_art),
            type = LayerType.IMAGE,
            bitmap = sampleBmp,
            scaleX = 1f,
            scaleY = 1f
        )

        // Text Layer in Arabic and English
        val textLayer = Layer(
            id = "layer_sample_text",
            name = context.getString(R.string.sample_layer_title),
            type = LayerType.TEXT,
            textConfig = TextLayerConfig(
                text = context.getString(R.string.sample_title_text),
                fontName = "Cairo",
                fontSizeSp = 36f,
                colorHex = 0xFFFFFFFF.toInt(),
                strokeColorHex = 0xFF6366F1.toInt(),
                strokeWidthDp = 2f,
                alignment = TextAlignment.CENTER
            ),
            positionY = 280f
        )

        proj.layers.addAll(listOf(bgLayer, imageLayer, textLayer))
        saveProject(proj)
        proj
    }

    /**
     * Exports canvas bitmap to user gallery / MediaStore as JPG or PNG
     */
    suspend fun exportToGallery(
        compositeBitmap: Bitmap,
        filename: String,
        isPng: Boolean,
        jpegQuality: Int
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val formatStr = if (isPng) "png" else "jpg"
            val mimeType = if (isPng) "image/png" else "image/jpeg"
            val displayName = "${filename}_${System.currentTimeMillis()}.$formatStr"

            var exportSuccessUri: Uri? = null

            // 1. First Attempt: Standard MediaStore (modern scoped storage on API 29+, or MediaStore provider on API 24-28 if permitted)
            try {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PhotoEasy")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (imageUri != null) {
                    var writeSuccess = false
                    try {
                        resolver.openOutputStream(imageUri)?.use { stream ->
                            writeSuccess = compressBitmap(compositeBitmap, isPng, jpegQuality, stream)
                        }
                        if (writeSuccess) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                contentValues.clear()
                                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                resolver.update(imageUri, contentValues, null, null)
                            }
                            exportSuccessUri = imageUri
                        } else {
                            resolver.delete(imageUri, null, null)
                        }
                    } catch (e: Exception) {
                        try { resolver.delete(imageUri, null, null) } catch (_: Exception) {}
                    }
                }
            } catch (secEx: SecurityException) {
                // Expected on API 24-28 when runtime WRITE_EXTERNAL_STORAGE is not granted
                exportSuccessUri = null
            } catch (e: Exception) {
                exportSuccessUri = null
            }

            if (exportSuccessUri != null) {
                return@withContext exportSuccessUri
            }

            // 2. Fallback for API 24-28 (Android 7–9):
            // Try public directory if permission is granted, otherwise use app-specific external files directory
            // (context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)) which NEVER requires runtime permission on Android 4.4+ (API 19+)
            val targetDir: File = try {
                val publicPicturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val photoEasyDir = File(publicPicturesDir, "PhotoEasy")
                if (photoEasyDir.exists() || photoEasyDir.mkdirs()) photoEasyDir else context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
            } catch (e: Exception) {
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
            }

            val imageFile = File(targetDir, displayName)
            var writeSuccess = false
            try {
                FileOutputStream(imageFile).use { outStream ->
                    writeSuccess = compressBitmap(compositeBitmap, isPng, jpegQuality, outStream)
                }
                if (writeSuccess) {
                    try {
                        android.media.MediaScannerConnection.scanFile(
                            context,
                            arrayOf(imageFile.absolutePath),
                            arrayOf(mimeType),
                            null
                        )
                    } catch (scanEx: Exception) {
                        scanEx.printStackTrace()
                    }

                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        imageFile
                    )
                } else {
                    if (imageFile.exists()) imageFile.delete()
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (imageFile.exists()) imageFile.delete()
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun compressBitmap(bmp: Bitmap, isPng: Boolean, quality: Int, outStream: OutputStream): Boolean {
        return try {
            val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val qual = if (isPng) 100 else quality.coerceIn(10, 100)
            val success = bmp.compress(format, qual, outStream)
            outStream.flush()
            if (outStream is FileOutputStream) {
                outStream.fd.sync()
            }
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun parseProjectSummaryJson(jsonStr: String): ProjectSummary {
        val obj = JSONObject(jsonStr)
        return ProjectSummary(
            id = obj.getString("id"),
            name = obj.optString("name", "Untitled Project"),
            width = obj.optInt("width", 1080),
            height = obj.optInt("height", 1080),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
            thumbnailPath = obj.optString("thumbnailPath", null)
        )
    }

    private fun parseProjectJson(jsonStr: String, projDir: File): Project {
        val obj = JSONObject(jsonStr)
        val schemaVersion = obj.optInt("schemaVersion", 1)
        val proj = Project(
            id = obj.getString("id"),
            name = obj.optString("name", "Untitled Project"),
            width = obj.optInt("width", 1080),
            height = obj.optInt("height", 1080),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
            thumbnailPath = obj.optString("thumbnailPath", null)
        )

        val layersArray = obj.optJSONArray("layers")
        if (layersArray != null) {
            for (i in 0 until layersArray.length()) {
                val layerObj = layersArray.getJSONObject(i)
                val layerId = layerObj.getString("id")
                val layerType = safeValueOf(layerObj.optString("type", ""), LayerType.IMAGE)

                var layerBmp: Bitmap? = null
                val bmpFileName = layerObj.optString("bitmapFile", "")
                if (bmpFileName.isNotEmpty()) {
                    val bmpFile = File(projDir, bmpFileName)
                    if (bmpFile.exists()) {
                        val options = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeFile(bmpFile.absolutePath, options)
                        options.inSampleSize = com.example.engine.ImageProcessor.calculateInSampleSize(options, 2048)
                        options.inJustDecodeBounds = false
                        options.inPreferredConfig = Bitmap.Config.ARGB_8888
                        layerBmp = BitmapFactory.decodeFile(bmpFile.absolutePath, options)
                    }
                }

                val isMaskEnabled = layerObj.optBoolean("isMaskEnabled", true)
                val isEditingMask = layerObj.optBoolean("isEditingMask", false)
                var maskBmp: Bitmap? = null
                val maskFileName = layerObj.optString("maskFile", "")
                if (maskFileName.isNotEmpty()) {
                    val maskFile = File(projDir, maskFileName)
                    if (maskFile.exists()) {
                        try {
                            maskBmp = BitmapFactory.decodeFile(maskFile.absolutePath)
                        } catch (_: Exception) {}
                    }
                }

                val textObj = layerObj.optJSONObject("textConfig")
                val textCfg = if (textObj != null) {
                    TextLayerConfig(
                        text = textObj.optString("text", "Text"),
                        fontName = textObj.optString("fontName", "Cairo"),
                        fontSizeSp = textObj.optDouble("fontSizeSp", 36.0).toFloat(),
                        colorHex = textObj.optInt("colorHex", 0xFFFFFFFF.toInt()),
                        strokeColorHex = textObj.optInt("strokeColorHex", 0xFF000000.toInt()),
                        strokeWidthDp = textObj.optDouble("strokeWidthDp", 0.0).toFloat(),
                        alignment = safeValueOf(textObj.optString("alignment", ""), TextAlignment.CENTER),
                        shadowColorHex = textObj.optInt("shadowColorHex", 0x80000000.toInt()),
                        shadowRadiusDp = textObj.optDouble("shadowRadiusDp", 0.0).toFloat(),
                        lineSpacingMultiplier = textObj.optDouble("lineSpacingMultiplier", 1.0).toFloat(),
                        letterSpacingEm = textObj.optDouble("letterSpacingEm", 0.0).toFloat(),
                        maxLineWidthPercent = textObj.optDouble("maxLineWidthPercent", 80.0).toFloat()
                    )
                } else TextLayerConfig()

                val adjObj = layerObj.optJSONObject("adjustments")
                val colorAdjustments = if (adjObj != null) {
                    ColorAdjustments(
                        brightness = adjObj.optDouble("brightness", 0.0).toFloat(),
                        contrast = adjObj.optDouble("contrast", 0.0).toFloat(),
                        saturation = adjObj.optDouble("saturation", 0.0).toFloat(),
                        temperature = adjObj.optDouble("temperature", 0.0).toFloat(),
                        tint = adjObj.optDouble("tint", 0.0).toFloat(),
                        exposure = adjObj.optDouble("exposure", 0.0).toFloat(),
                        highlights = adjObj.optDouble("highlights", 0.0).toFloat(),
                        shadows = adjObj.optDouble("shadows", 0.0).toFloat(),
                        sharpness = adjObj.optDouble("sharpness", 0.0).toFloat(),
                        vignette = adjObj.optDouble("vignette", 0.0).toFloat()
                    )
                } else ColorAdjustments()

                val layer = Layer(
                    id = layerId,
                    name = layerObj.optString("name", "Layer $i"),
                    type = layerType,
                    bitmap = layerBmp,
                    maskBitmap = maskBmp,
                    isMaskEnabled = isMaskEnabled,
                    isEditingMask = isEditingMask,
                    opacity = layerObj.optDouble("opacity", 1.0).toFloat(),
                    isVisible = layerObj.optBoolean("isVisible", true),
                    isLocked = layerObj.optBoolean("isLocked", false),
                    blendMode = safeValueOf(layerObj.optString("blendMode", ""), BlendModeType.NORMAL),
                    positionX = layerObj.optDouble("positionX", 0.0).toFloat(),
                    positionY = layerObj.optDouble("positionY", 0.0).toFloat(),
                    scaleX = layerObj.optDouble("scaleX", 1.0).toFloat(),
                    scaleY = layerObj.optDouble("scaleY", 1.0).toFloat(),
                    rotation = layerObj.optDouble("rotation", 0.0).toFloat(),
                    colorFillHex = layerObj.optInt("colorFillHex", 0xFF12131C.toInt()),
                    filter = safeValueOf(layerObj.optString("filter", ""), FilterType.NONE),
                    textConfig = textCfg,
                    adjustments = colorAdjustments,
                    isBitmapDirty = false,
                    sourceAssetPath = layerObj.optString("sourceAssetPath", null.toString()).takeIf { it != "null" && it.isNotEmpty() },
                    originalWidth = layerObj.optInt("originalWidth", 0),
                    originalHeight = layerObj.optInt("originalHeight", 0),
                    contentWidth = layerObj.optDouble("contentWidth", layerBmp?.width?.toDouble() ?: 0.0).toFloat(),
                    contentHeight = layerObj.optDouble("contentHeight", layerBmp?.height?.toDouble() ?: 0.0).toFloat()
                )
                proj.layers.add(layer)
            }
        }

        return proj
    }
}

