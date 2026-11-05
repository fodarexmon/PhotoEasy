package com.example.engine

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * "Select Subject" using ML Kit on-device subject segmentation.
 *
 * Returns an ARGB mask the size of [bitmap] whose alpha is the foreground confidence (soft edges
 * included), or null when segmentation is not available (no Google Play services, the model is
 * still being downloaded, or any other failure) so the caller can fall back.
 */
object AiSubjectSegmenter {

    suspend fun foregroundMask(bitmap: Bitmap): Bitmap? {
        if (bitmap.isRecycled || bitmap.width < 2 || bitmap.height < 2) return null
        val input = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        return try {
            suspendCancellableCoroutine { cont ->
                val segmenter = try {
                    SubjectSegmentation.getClient(
                        SubjectSegmenterOptions.Builder()
                            .enableForegroundConfidenceMask()
                            .build()
                    )
                } catch (t: Throwable) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation { runCatching { segmenter.close() } }
                try {
                    segmenter.process(InputImage.fromBitmap(input, 0))
                        .addOnSuccessListener { result ->
                            val mask = runCatching {
                                val buffer = result.foregroundConfidenceMask ?: return@runCatching null
                                val w = input.width
                                val h = input.height
                                if (buffer.capacity() < w * h) return@runCatching null
                                buffer.rewind()
                                val pixels = IntArray(w * h)
                                for (i in 0 until w * h) {
                                    val c = buffer.get().coerceIn(0f, 1f)
                                    pixels[i] = ((c * 255f + 0.5f).toInt() shl 24) or 0x00FFFFFF
                                }
                                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                                    setPixels(pixels, 0, w, 0, 0, w, h)
                                }
                            }.getOrNull()
                            runCatching { segmenter.close() }
                            if (cont.isActive) cont.resume(mask)
                        }
                        .addOnFailureListener {
                            runCatching { segmenter.close() }
                            if (cont.isActive) cont.resume(null)
                        }
                } catch (t: Throwable) {
                    runCatching { segmenter.close() }
                    if (cont.isActive) cont.resume(null)
                }
            }
        } finally {
            if (input !== bitmap && !input.isRecycled) input.recycle()
        }
    }
}
