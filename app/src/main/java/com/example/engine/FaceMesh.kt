package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One eye, in image pixels. Corners: [inner] is the one towards the nose. */
data class EyeShape(
    /** Closed outline of the eye opening. */
    val contour: List<PointF>,
    /** Upper lid edge from the inner corner to the outer corner. */
    val upperLid: List<PointF>,
    /** Lower lid edge from the inner corner to the outer corner. */
    val lowerLid: List<PointF>,
    val inner: PointF,
    val outer: PointF,
    /** Eyebrow outline (closed). */
    val brow: List<PointF>,
    /** Iris centre and radius (exact with MediaPipe, estimated from the pixels otherwise). */
    val iris: PointF,
    val irisRadius: Float
) {
    val center: PointF get() = centroidOf(contour)
    val width: Float get() = hypot(outer.x - inner.x, outer.y - inner.y)
}

/**
 * A face with the detail makeup needs, in image pixels. [eyes] are image-left first.
 * [precise] is true for MediaPipe's 478-point mesh, false for the ML Kit fallback.
 */
data class MakeupFace(
    val box: RectF,
    val oval: List<PointF>,
    val lipsOuter: List<PointF>,
    val lipsInner: List<PointF>,
    val eyes: List<EyeShape>,
    /** Cheek "apples", image-left first. */
    val cheeks: List<PointF>,
    /** Top of the cheekbones (for highlight), image-left first. */
    val cheekbones: List<PointF>,
    /** Face sides at ear level, image-left first. */
    val earSides: List<PointF>,
    val noseTop: PointF,
    val noseTip: PointF,
    /** Nose wings, image-left first. */
    val noseWings: List<PointF>,
    val forehead: PointF,
    val chin: PointF,
    val mouthCorners: List<PointF>,
    val precise: Boolean
) {
    val width: Float get() = max(box.width(), 1f)
    val height: Float get() = max(box.height(), 1f)

    /** The same face in an image [s] times larger. */
    fun scaled(s: Float): MakeupFace {
        fun p(v: PointF) = PointF(v.x * s, v.y * s)
        fun l(v: List<PointF>) = v.map { p(it) }
        return copy(
            box = RectF(box.left * s, box.top * s, box.right * s, box.bottom * s),
            oval = l(oval), lipsOuter = l(lipsOuter), lipsInner = l(lipsInner),
            eyes = eyes.map { e ->
                e.copy(contour = l(e.contour), upperLid = l(e.upperLid), lowerLid = l(e.lowerLid), inner = p(e.inner), outer = p(e.outer),
                    brow = l(e.brow), iris = p(e.iris), irisRadius = e.irisRadius * s)
            },
            cheeks = l(cheeks), cheekbones = l(cheekbones), earSides = l(earSides), noseTop = p(noseTop), noseTip = p(noseTip),
            noseWings = l(noseWings), forehead = p(forehead), chin = p(chin), mouthCorners = l(mouthCorners)
        )
    }
}

internal fun centroidOf(pts: List<PointF>): PointF {
    var x = 0f; var y = 0f
    pts.forEach { x += it.x; y += it.y }
    val n = max(1, pts.size)
    return PointF(x / n, y / n)
}

/**
 * Finds faces for makeup: MediaPipe's Face Landmarker (478 points with irises) when its model is
 * in the app, ML Kit's contours otherwise (irises then estimated from the pixels).
 */
object FaceMesh {

    const val MODEL_ASSET = "face_landmarker.task"
    private const val MAX_FACES = 6

    fun hasMediaPipe(context: Context): Boolean =
        try { context.assets.open(MODEL_ASSET).use { true } } catch (e: Exception) { false }

    suspend fun detect(context: Context, bitmap: Bitmap): List<MakeupFace> {
        if (bitmap.isRecycled || bitmap.width < 32 || bitmap.height < 32) return emptyList()
        val input = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return emptyList()
        try {
            val mp = if (hasMediaPipe(context)) {
                try { detectMediaPipe(context, input) } catch (t: Throwable) { emptyList() }
            } else emptyList()
            if (mp.isNotEmpty()) return mp
            return try { detectMlKit(input) } catch (t: Throwable) { emptyList() }
        } finally {
            if (input !== bitmap && !input.isRecycled) input.recycle()
        }
    }

    // --- MediaPipe -----------------------------------------------------------------------------

    private val LIPS_OUTER = intArrayOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185)
    private val LIPS_INNER = intArrayOf(78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191)
    private val OVAL = intArrayOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148,
        176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109)

    /** The two eyes of the mesh: upper lid and lower lid from the outer corner, the brow, the cheek points. */
    private class MeshEye(val upper: IntArray, val lower: IntArray, val brow: IntArray, val cheek: Int, val cheekbone: Int, val ear: Int, val wing: Int)
    private val EYE_A = MeshEye(
        upper = intArrayOf(33, 246, 161, 160, 159, 158, 157, 173, 133),
        lower = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133),
        brow = intArrayOf(70, 63, 105, 66, 107, 55, 65, 52, 53, 46),
        cheek = 50, cheekbone = 116, ear = 234, wing = 48
    )
    private val EYE_B = MeshEye(
        upper = intArrayOf(263, 466, 388, 387, 386, 385, 384, 398, 362),
        lower = intArrayOf(263, 249, 390, 373, 374, 380, 381, 382, 362),
        brow = intArrayOf(300, 293, 334, 296, 336, 285, 295, 282, 283, 276),
        cheek = 280, cheekbone = 345, ear = 454, wing = 278
    )

    private fun detectMediaPipe(context: Context, input: Bitmap): List<MakeupFace> {
        val base = com.google.mediapipe.tasks.core.BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
        val options = com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.IMAGE)
            .setNumFaces(MAX_FACES)
            .setMinFaceDetectionConfidence(0.35f)
            .setMinFacePresenceConfidence(0.35f)
            .setMinTrackingConfidence(0.35f)
            .build()
        val landmarker = com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.createFromOptions(context, options)
        try {
            val image = com.google.mediapipe.framework.image.BitmapImageBuilder(input).build()
            val result = landmarker.detect(image)
            val w = input.width.toFloat(); val h = input.height.toFloat()
            return result.faceLandmarks().mapNotNull { lms ->
                if (lms.size < 468) return@mapNotNull null
                val pts = lms.map { PointF(it.x() * w, it.y() * h) }
                fromMesh(pts)
            }.sortedByDescending { it.width * it.height }
        } finally {
            runCatching { landmarker.close() }
        }
    }

    /** A face from the 478 mesh points (468 without irises also works: irises are then estimated). */
    internal fun fromMesh(p: List<PointF>): MakeupFace? {
        fun at(i: IntArray) = i.map { p[it] }
        val oval = at(OVAL)
        val box = boundsOf(oval) ?: return null
        val eyesRaw = listOf(EYE_A, EYE_B).map { m ->
            val upper = at(m.upper).reversed() // inner -> outer
            val lower = at(m.lower).reversed()
            val contour = at(m.upper) + at(m.lower).reversed().drop(1).dropLast(1)
            val c = centroidOf(contour)
            // Irises: 468..472 and 473..477, matched to the nearer eye.
            val iris = if (p.size >= 478) {
                listOf(468, 473).map { start -> start to p[start] }.minByOrNull { (_, q) -> hypot(q.x - c.x, q.y - c.y) }!!.let { (start, centre) ->
                    val r = (1..4).map { k -> hypot(p[start + k].x - centre.x, p[start + k].y - centre.y) }.average().toFloat()
                    centre to r
                }
            } else null
            Triple(m, EyeShape(contour, upper, lower, upper.first(), upper.last(), at(m.brow),
                iris?.first ?: c, iris?.second ?: (hypot(upper.last().x - upper.first().x, upper.last().y - upper.first().y) * 0.22f)), c)
        }.sortedBy { it.third.x }
        val eyes = eyesRaw.map { it.second }
        val sides = eyesRaw.map { it.first }
        return MakeupFace(
            box = box, oval = oval, lipsOuter = at(LIPS_OUTER), lipsInner = at(LIPS_INNER), eyes = eyes,
            cheeks = sides.map { p[it.cheek] }, cheekbones = sides.map { p[it.cheekbone] }, earSides = sides.map { p[it.ear] },
            noseTop = p[168], noseTip = p[1], noseWings = sides.map { p[it.wing] },
            forehead = p[151], chin = p[152],
            mouthCorners = listOf(p[61], p[291]).sortedBy { it.x },
            precise = p.size >= 478
        )
    }

    // --- ML Kit fallback -----------------------------------------------------------------------

    private suspend fun detectMlKit(input: Bitmap): List<MakeupFace> {
        val faces: List<Face> = suspendCancellableCoroutine { cont ->
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setMinFaceSize(0.08f)
                .build()
            val detector = try { FaceDetection.getClient(options) } catch (t: Throwable) {
                cont.resume(emptyList()); return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { runCatching { detector.close() } }
            try {
                detector.process(InputImage.fromBitmap(input, 0))
                    .addOnSuccessListener { f -> runCatching { detector.close() }; if (cont.isActive) cont.resume(f) }
                    .addOnFailureListener { runCatching { detector.close() }; if (cont.isActive) cont.resume(emptyList()) }
            } catch (t: Throwable) {
                runCatching { detector.close() }
                if (cont.isActive) cont.resume(emptyList())
            }
        }
        // ML Kit gives contours for the most prominent face only.
        return faces.mapNotNull { fromMlKit(it, input) }.take(1)
    }

    private fun fromMlKit(f: Face, img: Bitmap): MakeupFace? {
        fun ct(t: Int): List<PointF>? = f.getContour(t)?.points?.takeIf { it.size >= 3 }?.map { PointF(it.x, it.y) }
        val oval = ct(FaceContour.FACE) ?: return null
        val box = boundsOf(oval) ?: return null
        val upperTop = ct(FaceContour.UPPER_LIP_TOP) ?: return null
        val upperBottom = ct(FaceContour.UPPER_LIP_BOTTOM) ?: return null
        val lowerTop = ct(FaceContour.LOWER_LIP_TOP) ?: return null
        val lowerBottom = ct(FaceContour.LOWER_LIP_BOTTOM) ?: return null
        val lipsOuter = sortAround(upperTop + lowerBottom)
        val lipsInner = sortAround(upperBottom + lowerTop)
        val eyeContours = listOfNotNull(ct(FaceContour.LEFT_EYE), ct(FaceContour.RIGHT_EYE))
        if (eyeContours.size < 2) return null
        val brows = listOf(
            listOfNotNull(ct(FaceContour.LEFT_EYEBROW_TOP), ct(FaceContour.LEFT_EYEBROW_BOTTOM)).flatten(),
            listOfNotNull(ct(FaceContour.RIGHT_EYEBROW_TOP), ct(FaceContour.RIGHT_EYEBROW_BOTTOM)).flatten()
        ).filter { it.size >= 4 }.map { sortAround(it) }
        val ec = eyeContours.map { centroidOf(it) }
        val faceMid = PointF((ec[0].x + ec[1].x) / 2f, (ec[0].y + ec[1].y) / 2f)
        val eyes = eyeContours.map { contour ->
            val c = centroidOf(contour)
            val brow = brows.minByOrNull { b -> centroidOf(b).let { hypot(it.x - c.x, it.y - c.y) } } ?: emptyList()
            eyeFromContour(contour, faceMid, brow, img)
        }.sortedBy { it.center.x }
        val noseBridge = ct(FaceContour.NOSE_BRIDGE)
        val noseBottom = ct(FaceContour.NOSE_BOTTOM)
        val cheeks = listOfNotNull(ct(FaceContour.LEFT_CHEEK)?.first(), ct(FaceContour.RIGHT_CHEEK)?.first()).sortedBy { it.x }
        val eyeDist = hypot(ec[1].x - ec[0].x, ec[1].y - ec[0].y)
        val down = PointF(-(ec[1].y - ec[0].y) / eyeDist, (ec[1].x - ec[0].x) / eyeDist).let { if (it.y < 0) PointF(-it.x, -it.y) else it }
        val sortedEc = ec.sortedBy { it.x }
        val cheekPts = if (cheeks.size == 2) cheeks else sortedEc.map { PointF(it.x + down.x * eyeDist * 0.55f, it.y + down.y * eyeDist * 0.55f) }
        val cheekbones = sortedEc.mapIndexed { i, e ->
            val out = if (i == 0) -1f else 1f
            PointF(e.x + down.x * eyeDist * 0.32f + out * eyeDist * 0.18f, e.y + down.y * eyeDist * 0.32f)
        }
        val ovalSorted = oval
        val earSides = sortedEc.map { e -> ovalSorted.filter { abs(it.y - e.y) < box.height() * 0.15f }.let { near ->
            if (near.isEmpty()) PointF(if (e.x < faceMid.x) box.left else box.right, e.y)
            else if (e.x < faceMid.x) near.minByOrNull { it.x }!! else near.maxByOrNull { it.x }!!
        } }
        val mouthCorners = listOf(upperTop.minByOrNull { it.x }!!, upperTop.maxByOrNull { it.x }!!)
        val tip = noseBottom?.let { it[it.size / 2] } ?: noseBridge?.last() ?: PointF(faceMid.x + down.x * eyeDist * 0.7f, faceMid.y + down.y * eyeDist * 0.7f)
        return MakeupFace(
            box = box, oval = oval, lipsOuter = lipsOuter, lipsInner = lipsInner, eyes = eyes,
            cheeks = cheekPts, cheekbones = cheekbones, earSides = earSides,
            noseTop = noseBridge?.first() ?: faceMid, noseTip = tip,
            noseWings = noseBottom?.let { listOf(it.first(), it.last()).sortedBy { q -> q.x } } ?: listOf(PointF(tip.x - eyeDist * 0.2f, tip.y), PointF(tip.x + eyeDist * 0.2f, tip.y)),
            forehead = PointF(faceMid.x - down.x * eyeDist * 0.75f, faceMid.y - down.y * eyeDist * 0.75f),
            chin = oval.maxByOrNull { (it.x - faceMid.x) * down.x + (it.y - faceMid.y) * down.y } ?: PointF(box.centerX(), box.bottom),
            mouthCorners = mouthCorners, precise = false
        )
    }

    private fun abs(v: Float) = kotlin.math.abs(v)

    /** Splits a closed eye outline at its corners into lids; the iris is looked for in the pixels. */
    internal fun eyeFromContour(contour: List<PointF>, faceMid: PointF, brow: List<PointF>, img: Bitmap?): EyeShape {
        val iA = contour.indices.minByOrNull { contour[it].x }!!
        val iB = contour.indices.maxByOrNull { contour[it].x }!!
        fun arc(from: Int, to: Int): List<PointF> {
            val out = ArrayList<PointF>(); var i = from
            while (true) { out.add(contour[i]); if (i == to) break; i = (i + 1) % contour.size }
            return out
        }
        val arc1 = arc(iA, iB); val arc2 = arc(iB, iA).reversed()
        val upper0 = if (arc1.map { it.y }.average() < arc2.map { it.y }.average()) arc1 else arc2
        val lower0 = if (upper0 === arc1) arc2 else arc1
        // Inner corner: the one nearer the middle of the face.
        val aIsInner = hypot(contour[iA].x - faceMid.x, contour[iA].y - faceMid.y) < hypot(contour[iB].x - faceMid.x, contour[iB].y - faceMid.y)
        val upper = if (aIsInner) upper0 else upper0.reversed()
        val lower = if (aIsInner) lower0 else lower0.reversed()
        val inner = upper.first(); val outer = upper.last()
        val eyeW = hypot(outer.x - inner.x, outer.y - inner.y)
        val (iris, r) = img?.let { findIris(it, contour, eyeW) } ?: (centroidOf(contour) to eyeW * 0.22f)
        return EyeShape(contour, upper, lower, inner, outer, brow, iris, r)
    }

    /** The darkest disc inside the eye opening (the iris), searched on a small grid. */
    internal fun findIris(img: Bitmap, contour: List<PointF>, eyeW: Float): Pair<PointF, Float>? {
        val b = boundsOf(contour) ?: return null
        val eyeH = b.height()
        val r = min(eyeW * 0.22f, max(eyeH * 0.55f, eyeW * 0.15f)).coerceAtLeast(2f)
        val l = b.left.toInt().coerceAtLeast(0); val t = b.top.toInt().coerceAtLeast(0)
        val rr = b.right.toInt().coerceAtMost(img.width - 1); val bb = b.bottom.toInt().coerceAtMost(img.height - 1)
        if (rr - l < 4 || bb - t < 2) return null
        val w = rr - l + 1; val h = bb - t + 1
        val px = IntArray(w * h)
        img.getPixels(px, 0, w, l, t, w, h)
        fun lum(x: Int, y: Int): Float {
            val c = px[(y - t).coerceIn(0, h - 1) * w + (x - l).coerceIn(0, w - 1)]
            return 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }
        var best: PointF? = null; var bestScore = Float.MAX_VALUE
        val step = max(1f, eyeW / 24f)
        var cx = b.left + r * 0.6f
        while (cx <= b.right - r * 0.6f) {
            var cy = b.top + eyeH * 0.25f
            while (cy <= b.bottom - eyeH * 0.25f + 0.01f) {
                var s = 0f; var n = 0
                val k = max(1f, r / 4f)
                var dy = -r
                while (dy <= r) {
                    var dx = -r
                    while (dx <= r) {
                        if (dx * dx + dy * dy <= r * r && insidePolygon(contour, cx + dx, cy + dy)) { s += lum((cx + dx).toInt(), (cy + dy).toInt()); n++ }
                        dx += k
                    }
                    dy += k
                }
                if (n >= 4) {
                    val score = s / n
                    if (score < bestScore) { bestScore = score; best = PointF(cx, cy) }
                }
                cy += step
            }
            cx += step
        }
        return best?.let { it to r }
    }

    // --- Geometry helpers ----------------------------------------------------------------------

    internal fun boundsOf(pts: List<PointF>): RectF? {
        if (pts.isEmpty()) return null
        return RectF(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }

    internal fun sortAround(pts: List<PointF>): List<PointF> {
        val c = centroidOf(pts)
        return pts.sortedBy { atan2(it.y - c.y, it.x - c.x) }
    }

    internal fun insidePolygon(poly: List<PointF>, x: Float, y: Float): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y + 1e-6f) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    @Suppress("unused")
    private fun len(a: PointF, b: PointF) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
}
