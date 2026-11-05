package com.example.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
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

    private fun pointsToJson(points: List<PointF>): JSONArray = JSONArray().apply {
        points.forEach { p -> put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble())) }
    }

    private fun jsonToPoints(arr: JSONArray?): List<PointF>? {
        if (arr == null) return null
        val list = mutableListOf<PointF>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONArray(i) ?: continue
            list.add(PointF(p.optDouble(0, 0.0).toFloat(), p.optDouble(1, 0.0).toFloat()))
        }
        return if (list.size >= 2) list else null
    }

    /**
     * Masks are persisted as opaque grayscale PNG (white = reveal). Encoding ALPHA_8 bitmaps
     * directly is codec-dependent, so the alpha channel is written into RGB explicitly.
     */
    private fun encodeMaskAsGray(mask: Bitmap): Bitmap {
        val w = mask.width
        val h = mask.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            mask.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val a = (row[x] ushr 24) and 0xFF
                row[x] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    /**
     * Decodes a persisted mask into a MUTABLE ALPHA_8 bitmap so it can be edited after reopening.
     * New files store the mask in the gray channel; legacy files may store it in alpha.
     */
    private fun decodeMask(file: File, storedAsGray: Boolean): Bitmap? {
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        ) ?: return null
        val useGray = storedAsGray || !decoded.hasAlpha()
        val w = decoded.width
        val h = decoded.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val row = IntArray(w)
        for (y in 0 until h) {
            decoded.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val v = if (useGray) (p shr 16) and 0xFF else (p ushr 24) and 0xFF
                row[x] = v shl 24
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        decoded.recycle()
        return out
    }

    /**
     * Content hash of each mask as last written to (or read from) disk, keyed by "projectId/layerId".
     * Lets autosave skip re-encoding unchanged masks, which is the most expensive part of a save.
     */
    private val savedMaskHashes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun maskHash(mask: Bitmap): Long? = try {
        val bytes = ByteArray(mask.rowBytes * mask.height)
        mask.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(bytes))
        val crc = java.util.zip.CRC32()
        crc.update(bytes)
        crc.value xor (mask.width.toLong() shl 40) xor (mask.height.toLong() shl 20)
    } catch (_: Throwable) {
        null
    }

    private fun commitTmpFile(tmpFile: File, target: File): Boolean =
        tmpFile.renameTo(target) || run { if (target.exists() && !target.delete()) false else tmpFile.renameTo(target) }

    /**
     * @param protectedFileNames file names inside the project directory that must survive orphan
     * cleanup even though the current layer stack does not reference them (e.g. undo history).
     */
    suspend fun saveProject(project: Project, protectedFileNames: Set<String> = emptySet()): Boolean = withContext(Dispatchers.IO) {
        try {
            val projDir = File(projectsDir, project.id).apply { if (!exists()) mkdirs() }
            project.updatedAt = System.currentTimeMillis()

            val activeLayerFiles = mutableSetOf<String>()

            // Save layer bitmaps & build JSON
            val jsonObj = JSONObject().apply {
                put("schemaVersion", 5)
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
                        put("filterIntensity", layer.filterIntensity.toDouble())

                        // Save adjustments config
                        val adj = layer.adjustments
                        val adjObj = JSONObject().apply {
                            put("brightness", adj.brightness.toDouble())
                            put("contrast", adj.contrast.toDouble())
                            put("saturation", adj.saturation.toDouble())
                            put("temperature", adj.temperature.toDouble())
                            put("tint", adj.tint.toDouble())
                            put("exposure", adj.exposure.toDouble())
                            put("highlights", adj.highlights.toDouble())
                            put("shadows", adj.shadows.toDouble())
                            put("sharpness", adj.sharpness.toDouble())
                            put("vignette", adj.vignette.toDouble())
                            put("curvesRgb", pointsToJson(adj.curvesRgb))
                            put("curvesRed", pointsToJson(adj.curvesRed))
                            put("curvesGreen", pointsToJson(adj.curvesGreen))
                            put("curvesBlue", pointsToJson(adj.curvesBlue))
                            put("hueShift", adj.hueShift.toDouble())
                            put("hslSaturation", adj.hslSaturation.toDouble())
                            put("hslLightness", adj.hslLightness.toDouble())
                            put("hslColorRange", adj.hslColorRange)
                            put("replaceColorEnabled", adj.replaceColorEnabled)
                            put("replaceSourceColorHex", adj.replaceSourceColorHex)
                            put("replaceTolerance", adj.replaceTolerance.toDouble())
                            put("replaceTargetHue", adj.replaceTargetHue.toDouble())
                            put("replaceTargetSaturation", adj.replaceTargetSaturation.toDouble())
                            put("replaceTargetLightness", adj.replaceTargetLightness.toDouble())
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
                            put("shadowDxDp", layer.textConfig.shadowDxDp.toDouble())
                            put("shadowDyDp", layer.textConfig.shadowDyDp.toDouble())
                            put("outerGlowColorHex", layer.textConfig.outerGlowColorHex)
                            put("outerGlowRadiusDp", layer.textConfig.outerGlowRadiusDp.toDouble())
                            put("lineSpacingMultiplier", layer.textConfig.lineSpacingMultiplier.toDouble())
                            put("letterSpacingEm", layer.textConfig.letterSpacingEm.toDouble())
                            put("maxLineWidthPercent", layer.textConfig.maxLineWidthPercent.toDouble())
                        }
                        put("textConfig", textObj)

                        // Save bitmap if present (using dirty flag, transactional save & WebP format)
                        layer.bitmap?.let { bmp ->
                            // Lossless only: WEBP_LOSSLESS on API 30+, PNG below (the old lossy WEBP
                            // fallback degraded the layer a little more on every save).
                            val losslessWebp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            val preferredFileName = if (losslessWebp) "layer_${layer.id}.webp" else "layer_${layer.id}.png"
                            val legacyFileName = if (losslessWebp) "layer_${layer.id}.png" else "layer_${layer.id}.webp"
                            val preferredFile = File(projDir, preferredFileName)
                            val legacyFile = File(projDir, legacyFileName)

                            val actualFileName = if (preferredFile.exists() && !layer.isBitmapDirty) {
                                preferredFileName
                            } else if (legacyFile.exists() && !layer.isBitmapDirty) {
                                legacyFileName
                            } else {
                                val tmpFileName = "$preferredFileName.tmp"
                                val tmpFile = File(projDir, tmpFileName)
                                val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
                                FileOutputStream(tmpFile).use { out ->
                                    if (!bmp.compress(compressFormat, 100, out)) throw java.io.IOException("Layer encoding failed")
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
                        put("maskDensity", layer.maskDensity.toDouble())
                        put("maskFeather", layer.maskFeather.toDouble())
                        layer.maskBitmap?.let { maskBmp ->
                            if (!maskBmp.isRecycled) {
                                val maskFileName = "mask_${layer.id}.png"
                                val maskFile = File(projDir, maskFileName)
                                val tmpMaskFile = File(projDir, "$maskFileName.tmp")
                                val hashKey = "${project.id}/${layer.id}"
                                val currentHash = maskHash(maskBmp)
                                val unchanged = currentHash != null && savedMaskHashes[hashKey] == currentHash && maskFile.exists()
                                if (!unchanged) {
                                    // Any failure aborts the whole save: the previous manifest and its
                                    // mask file stay intact instead of being orphan-cleaned.
                                    val grayMask = encodeMaskAsGray(maskBmp)
                                    try {
                                        FileOutputStream(tmpMaskFile).use { out ->
                                            if (!grayMask.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                                                throw java.io.IOException("Mask encoding failed")
                                            }
                                            out.flush(); out.fd.sync()
                                        }
                                    } finally {
                                        grayMask.recycle()
                                    }
                                    if (!commitTmpFile(tmpMaskFile, maskFile)) throw java.io.IOException("Mask file commit failed")
                                    if (currentHash != null) savedMaskHashes[hashKey] = currentHash else savedMaskHashes.remove(hashKey)
                                }
                                activeLayerFiles.add(maskFileName)
                                put("maskFile", maskFileName)
                                put("maskFormat", "gray")
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
            val now = System.currentTimeMillis()
            projDir.listFiles()?.forEach { file ->
                val name = file.name
                val isLayerAsset = (name.startsWith("layer_") || name.startsWith("mask_") || name.startsWith("source_")) && !name.endsWith(".tmp")
                if (isLayerAsset && name !in activeLayerFiles && name !in protectedFileNames) {
                    // Source assets may be written by an in-flight destructive edit that is not yet
                    // referenced by any layer; only delete them once they are clearly stale.
                    if (!name.startsWith("source_") || now - file.lastModified() > 60000) {
                        file.delete()
                    }
                } else if (name.endsWith(".tmp") && now - file.lastModified() > 60000) {
                    file.delete()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Persists the active selection next to the project (selection.png, gray = selected) so it
     * survives closing the app. An empty selection removes the file. Unchanged masks are skipped.
     */
    suspend fun saveSelection(projectId: String, mask: Bitmap?): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(projectsDir, projectId)
            if (!dir.exists()) return@withContext false
            val file = File(dir, "selection.png")
            val key = "$projectId/__selection__"
            if (mask == null || mask.isRecycled || !com.example.engine.SelectionEngine.hasSelection(mask)) {
                if (file.exists()) file.delete()
                savedMaskHashes.remove(key)
                return@withContext true
            }
            val hash = maskHash(mask)
            if (hash != null && savedMaskHashes[key] == hash && file.exists()) return@withContext true
            val tmp = File(dir, "selection.png.tmp")
            val gray = encodeMaskAsGray(mask)
            try {
                FileOutputStream(tmp).use { out ->
                    if (!gray.compress(Bitmap.CompressFormat.PNG, 100, out)) throw java.io.IOException("Selection encoding failed")
                    out.flush(); out.fd.sync()
                }
            } finally {
                gray.recycle()
            }
            val ok = commitTmpFile(tmp, file)
            if (ok && hash != null) savedMaskHashes[key] = hash
            ok
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Loads the saved selection if it matches the canvas size, otherwise null. */
    suspend fun loadSelection(projectId: String, width: Int, height: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val file = File(File(projectsDir, projectId), "selection.png")
            if (!file.exists()) return@withContext null
            val mask = decodeMask(file, storedAsGray = true) ?: return@withContext null
            if (mask.width != width || mask.height != height) {
                mask.recycle()
                return@withContext null
            }
            maskHash(mask)?.let { savedMaskHashes["$projectId/__selection__"] = it }
            mask
        } catch (e: Exception) {
            null
        }
    }

    /** Renames a project that is not open, rewriting only its manifest (no layer re-encoding). */
    suspend fun renameProject(projectId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(projectsDir, projectId)
            val manifest = File(dir, "project.json")
            if (!manifest.exists()) return@withContext false
            val obj = JSONObject(manifest.readText())
            obj.put("name", newName)
            obj.put("updatedAt", System.currentTimeMillis())
            val tmp = File(dir, "project.json.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(obj.toString(2).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            commitTmpFile(tmp, manifest)
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
                            val isGray = layerObj.optString("maskFormat", "") == "gray"
                            maskBmp = decodeMask(maskFile, storedAsGray = isGray)
                            // Only the current gray PNG format is reusable as-is on the next save.
                            if (isGray && maskFileName.endsWith(".png")) {
                                maskBmp?.let { m -> maskHash(m)?.let { savedMaskHashes["${proj.id}/$layerId"] = it } }
                            }
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
                        shadowDxDp = textObj.optDouble("shadowDxDp", 4.0).toFloat(),
                        shadowDyDp = textObj.optDouble("shadowDyDp", 6.0).toFloat(),
                        outerGlowColorHex = textObj.optInt("outerGlowColorHex", 0xFF00E5FF.toInt()),
                        outerGlowRadiusDp = textObj.optDouble("outerGlowRadiusDp", 0.0).toFloat(),
                        lineSpacingMultiplier = textObj.optDouble("lineSpacingMultiplier", 1.0).toFloat(),
                        letterSpacingEm = textObj.optDouble("letterSpacingEm", 0.0).toFloat(),
                        maxLineWidthPercent = textObj.optDouble("maxLineWidthPercent", 80.0).toFloat()
                    )
                } else TextLayerConfig()

                val adjObj = layerObj.optJSONObject("adjustments")
                val defaults = ColorAdjustments()
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
                        vignette = adjObj.optDouble("vignette", 0.0).toFloat(),
                        curvesRgb = jsonToPoints(adjObj.optJSONArray("curvesRgb")) ?: defaults.curvesRgb,
                        curvesRed = jsonToPoints(adjObj.optJSONArray("curvesRed")) ?: defaults.curvesRed,
                        curvesGreen = jsonToPoints(adjObj.optJSONArray("curvesGreen")) ?: defaults.curvesGreen,
                        curvesBlue = jsonToPoints(adjObj.optJSONArray("curvesBlue")) ?: defaults.curvesBlue,
                        hueShift = adjObj.optDouble("hueShift", 0.0).toFloat(),
                        hslSaturation = adjObj.optDouble("hslSaturation", 0.0).toFloat(),
                        hslLightness = adjObj.optDouble("hslLightness", 0.0).toFloat(),
                        hslColorRange = adjObj.optString("hslColorRange", defaults.hslColorRange),
                        replaceColorEnabled = adjObj.optBoolean("replaceColorEnabled", false),
                        replaceSourceColorHex = adjObj.optInt("replaceSourceColorHex", defaults.replaceSourceColorHex),
                        replaceTolerance = adjObj.optDouble("replaceTolerance", defaults.replaceTolerance.toDouble()).toFloat(),
                        replaceTargetHue = adjObj.optDouble("replaceTargetHue", defaults.replaceTargetHue.toDouble()).toFloat(),
                        replaceTargetSaturation = adjObj.optDouble("replaceTargetSaturation", 0.0).toFloat(),
                        replaceTargetLightness = adjObj.optDouble("replaceTargetLightness", 0.0).toFloat()
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
                    maskDensity = layerObj.optDouble("maskDensity", 1.0).toFloat().coerceIn(0f, 1f),
                    maskFeather = layerObj.optDouble("maskFeather", 0.0).toFloat().coerceAtLeast(0f),
                    opacity =layerObj.optDouble("opacity", 1.0).toFloat(),
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
                    filterIntensity = layerObj.optDouble("filterIntensity", 1.0).toFloat().coerceIn(0f, 1f),
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

