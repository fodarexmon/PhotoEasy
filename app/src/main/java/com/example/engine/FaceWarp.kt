package com.example.engine

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A face found in the image, in the image's pixel coordinates. [ax] points from the face's
 * image-left eye to its image-right eye, [ay] "down" the face (so tilted faces work too).
 */
data class FaceGeometry(
    val box: RectF,
    val center: PointF,
    val width: Float,
    val height: Float,
    val ax: PointF,
    val ay: PointF,
    val eye1: PointF,
    val eye2: PointF,
    val eyeW: Float,
    val eyeH: Float,
    val noseBase: PointF,
    val noseW: Float,
    val noseLen: Float,
    val mouthLeft: PointF,
    val mouthRight: PointF,
    val mouthCenter: PointF,
    val mouthH: Float,
    val chin: PointF,
    val forehead: PointF,
    /** The face outline (ML Kit's face contour, ~36 points) when known: jaw and width follow it. */
    val oval: List<PointF>? = null
)

/**
 * Face-Aware Liquify settings for one face, -100..100 (0 = unchanged), like Photoshop's
 * Face-Aware Liquify, plus eye brightness (0..100) and head size.
 */
data class FaceAdjust(
    val eyeSize: Float = 0f,
    val eyeHeight: Float = 0f,
    val eyeWidth: Float = 0f,
    val eyeTilt: Float = 0f,
    val eyeDistance: Float = 0f,
    val eyeBrightness: Float = 0f,
    val noseHeight: Float = 0f,
    val noseWidth: Float = 0f,
    val smile: Float = 0f,
    val upperLip: Float = 0f,
    val lowerLip: Float = 0f,
    val mouthWidth: Float = 0f,
    val mouthHeight: Float = 0f,
    val forehead: Float = 0f,
    val chinHeight: Float = 0f,
    val jawline: Float = 0f,
    val faceWidth: Float = 0f,
    val headSize: Float = 0f,
    /** Skin (0..100): blemish removal, dark circles under the eyes, smoothing. */
    val blemishes: Float = 0f,
    val darkCircles: Float = 0f,
    val skinSmooth: Float = 0f,
    /**
     * Photoshop's eye link: when true the eye settings above apply to both eyes; when false they
     * apply to the image-left eye and the eye2* values to the image-right eye.
     */
    val eyesLinked: Boolean = true,
    val eye2Size: Float = 0f,
    val eye2Height: Float = 0f,
    val eye2Width: Float = 0f,
    val eye2Tilt: Float = 0f,
    val eye2Distance: Float = 0f
) {
    val isNeutral: Boolean get() = copy(eyesLinked = true) == FaceAdjust()
    val hasWarp: Boolean get() = copy(eyeBrightness = 0f, blemishes = 0f, darkCircles = 0f, skinSmooth = 0f, eyesLinked = true) != FaceAdjust() ||
        (!eyesLinked && (eye2Size != 0f || eye2Height != 0f || eye2Width != 0f || eye2Tilt != 0f || eye2Distance != 0f))

    /** Size, height, width, tilt, distance of eye [i] (0 = image-left, 1 = image-right). */
    fun eye(i: Int): FloatArray =
        if (eyesLinked || i == 0) floatArrayOf(eyeSize, eyeHeight, eyeWidth, eyeTilt, eyeDistance)
        else floatArrayOf(eye2Size, eye2Height, eye2Width, eye2Tilt, eye2Distance)

    /** Unlinks the eyes, the right eye starting from the current (shared) values. */
    fun unlinked(): FaceAdjust = if (!eyesLinked) this else copy(
        eyesLinked = false, eye2Size = eyeSize, eye2Height = eyeHeight, eye2Width = eyeWidth, eye2Tilt = eyeTilt, eye2Distance = eyeDistance
    )
    /** The skin settings only (to know when the slow skin pass must be redone). */
    val skinKey: Triple<Float, Float, Float> get() = Triple(blemishes, darkCircles, skinSmooth)
}

object FaceWarp {

    // --- Detection ---------------------------------------------------------------------------

    /**
     * Finds faces in [bitmap] with ML Kit's on-device detector (bundled model, works offline).
     * Landmarks are used for every face; the most prominent face also gets contours for exact
     * eyes, nose, lips and jaw. Returns an empty list when none is found or detection fails.
     */
    suspend fun detect(bitmap: Bitmap): List<FaceGeometry> {
        if (bitmap.isRecycled || bitmap.width < 32 || bitmap.height < 32) return emptyList()
        val input = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return emptyList()
        try {
            val landmarks = run(input, contours = false)
            val contoured = run(input, contours = true).firstOrNull()
            val faces = landmarks.ifEmpty { listOfNotNull(contoured) }
            return faces.take(MAX_FACES).mapNotNull { f ->
                // Use the contoured result for the same face (boxes overlap a lot). ML Kit gives
                // contours for the most prominent face only, so every other face is detected again
                // on a crop around it, where it is the prominent one.
                val c = contoured?.takeIf { iou(it.boundingBox, f.boundingBox) > 0.5f }
                if (c != null) geometry(f, c, 0f, 0f)
                else {
                    val crop = cropAround(input, f.boundingBox)
                    if (crop == null) geometry(f, null, 0f, 0f)
                    else try {
                        val cc = run(crop.first, contours = true).firstOrNull()
                        geometry(f, cc, crop.second.toFloat(), crop.third.toFloat())
                    } finally { crop.first.recycle() }
                }
            }.sortedByDescending { it.width * it.height }
        } catch (t: Throwable) {
            return emptyList()
        } finally {
            if (input !== bitmap && !input.isRecycled) input.recycle()
        }
    }

    private suspend fun run(input: Bitmap, contours: Boolean): List<Face> = suspendCancellableCoroutine { cont ->
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(if (contours) FaceDetectorOptions.LANDMARK_MODE_NONE else FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(if (contours) FaceDetectorOptions.CONTOUR_MODE_ALL else FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.08f)
            .build()
        val detector = try { FaceDetection.getClient(options) } catch (t: Throwable) {
            cont.resume(emptyList()); return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { runCatching { detector.close() } }
        try {
            detector.process(InputImage.fromBitmap(input, 0))
                .addOnSuccessListener { faces -> runCatching { detector.close() }; if (cont.isActive) cont.resume(faces) }
                .addOnFailureListener { runCatching { detector.close() }; if (cont.isActive) cont.resume(emptyList()) }
        } catch (t: Throwable) {
            runCatching { detector.close() }
            if (cont.isActive) cont.resume(emptyList())
        }
    }

    private const val MAX_FACES = 8

    /** [src] around [box] grown by 35 % per side (and its offset), or null when too small. */
    private fun cropAround(src: Bitmap, box: android.graphics.Rect): Triple<Bitmap, Int, Int>? {
        val gx = (box.width() * 0.35f).toInt(); val gy = (box.height() * 0.35f).toInt()
        val l = max(0, box.left - gx); val t = max(0, box.top - gy)
        val r = min(src.width, box.right + gx); val b = min(src.height, box.bottom + gy)
        if (r - l < 48 || b - t < 48) return null
        return try { Triple(Bitmap.createBitmap(src, l, t, r - l, b - t), l, t) } catch (e: Throwable) { null }
    }

    private fun iou(a: android.graphics.Rect, b: android.graphics.Rect): Float {
        val i = android.graphics.Rect()
        if (!i.setIntersect(a, b)) return 0f
        val inter = i.width().toFloat() * i.height()
        val union = a.width().toFloat() * a.height() + b.width().toFloat() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun centroid(pts: List<PointF>): PointF = PointF(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat())

    /** Builds the geometry from landmarks ([f]) refined by contours ([c]) when available. */
    private fun geometry(f: Face, c: Face?, cox: Float, coy: Float): FaceGeometry? {
        val b = f.boundingBox
        if (b.width() < 8 || b.height() < 8) return null
        val box = RectF(b)
        fun lm(type: Int): PointF? = f.getLandmark(type)?.position
        // Contour points, moved from the crop they were found in back to the image.
        fun ct(type: Int): List<PointF>? = c?.getContour(type)?.points?.takeIf { it.size >= 3 }?.map { PointF(it.x + cox, it.y + coy) }

        val leftEyeC = ct(FaceContour.LEFT_EYE); val rightEyeC = ct(FaceContour.RIGHT_EYE)
        val foundA = leftEyeC?.let { centroid(it) } ?: lm(FaceLandmark.LEFT_EYE)
        val foundB = rightEyeC?.let { centroid(it) } ?: lm(FaceLandmark.RIGHT_EYE)
        // Rough eye positions from the box when the landmarks are missing.
        val ea: PointF = if (foundA != null && foundB != null) foundA else PointF(box.left + box.width() * 0.32f, box.top + box.height() * 0.40f)
        val eb: PointF = if (foundA != null && foundB != null) foundB else PointF(box.left + box.width() * 0.68f, box.top + box.height() * 0.40f)
        val e1 = if (ea.x <= eb.x) ea else eb
        val e2 = if (ea.x <= eb.x) eb else ea
        val eyeDist = hypot(e2.x - e1.x, e2.y - e1.y).coerceAtLeast(box.width() * 0.2f)
        val ax = PointF((e2.x - e1.x) / eyeDist, (e2.y - e1.y) / eyeDist)
        val ay = PointF(-ax.y, ax.x)
        fun along(p: PointF, o: PointF, v: PointF) = (p.x - o.x) * v.x + (p.y - o.y) * v.y

        val eyeContour = leftEyeC ?: rightEyeC
        val eyeW = eyeContour?.let { pts -> pts.maxOf { along(it, pts[0], ax) } - pts.minOf { along(it, pts[0], ax) } } ?: (eyeDist * 0.42f)
        val eyeH = eyeContour?.let { pts -> pts.maxOf { along(it, pts[0], ay) } - pts.minOf { along(it, pts[0], ay) } } ?: (eyeW * 0.45f)
        val eyeMid = PointF((e1.x + e2.x) / 2f, (e1.y + e2.y) / 2f)

        val noseBottom = ct(FaceContour.NOSE_BOTTOM)
        val noseBase = noseBottom?.let { centroid(it) } ?: lm(FaceLandmark.NOSE_BASE)
            ?: PointF(eyeMid.x + ay.x * eyeDist * 0.7f, eyeMid.y + ay.y * eyeDist * 0.7f)
        val noseW = noseBottom?.let { pts -> pts.maxOf { along(it, pts[0], ax) } - pts.minOf { along(it, pts[0], ax) } }?.takeIf { it > 2f }
            ?: (eyeDist * 0.48f)
        val noseLen = along(noseBase, eyeMid, ay).coerceAtLeast(eyeDist * 0.3f)

        val upperTop = ct(FaceContour.UPPER_LIP_TOP); val lowerBottom = ct(FaceContour.LOWER_LIP_BOTTOM)
        val foundMl = upperTop?.minByOrNull { along(it, eyeMid, ax) } ?: lm(FaceLandmark.MOUTH_LEFT)
        val foundMr = upperTop?.maxByOrNull { along(it, eyeMid, ax) } ?: lm(FaceLandmark.MOUTH_RIGHT)
        val ma: PointF = if (foundMl != null && foundMr != null) foundMl
            else PointF(noseBase.x - ax.x * eyeDist * 0.4f + ay.x * eyeDist * 0.5f, noseBase.y - ax.y * eyeDist * 0.4f + ay.y * eyeDist * 0.5f)
        val mb: PointF = if (foundMl != null && foundMr != null) foundMr
            else PointF(noseBase.x + ax.x * eyeDist * 0.4f + ay.x * eyeDist * 0.5f, noseBase.y + ax.y * eyeDist * 0.4f + ay.y * eyeDist * 0.5f)
        val ml = if (along(ma, eyeMid, ax) <= along(mb, eyeMid, ax)) ma else mb
        val mr = if (ml === ma) mb else ma
        val mouthW = hypot(mr.x - ml.x, mr.y - ml.y).coerceAtLeast(eyeDist * 0.4f)
        val mouthMid = PointF((ml.x + mr.x) / 2f, (ml.y + mr.y) / 2f)
        val mouthH = if (upperTop != null && lowerBottom != null) {
            (lowerBottom.maxOf { along(it, mouthMid, ay) } - upperTop.minOf { along(it, mouthMid, ay) }).coerceAtLeast(mouthW * 0.15f)
        } else {
            lm(FaceLandmark.MOUTH_BOTTOM)?.let { (along(it, mouthMid, ay) * 2f).coerceAtLeast(mouthW * 0.2f) } ?: (mouthW * 0.35f)
        }
        val mouthCenter = PointF(mouthMid.x + ay.x * mouthH * 0.1f, mouthMid.y + ay.y * mouthH * 0.1f)

        val center = PointF(box.centerX(), box.centerY())
        val faceOval = ct(FaceContour.FACE)
        val width = faceOval?.let { pts -> pts.maxOf { along(it, center, ax) } - pts.minOf { along(it, center, ax) } } ?: box.width()
        val height = faceOval?.let { pts -> pts.maxOf { along(it, center, ay) } - pts.minOf { along(it, center, ay) } } ?: box.height()
        val chin = faceOval?.maxByOrNull { along(it, center, ay) }
            ?: PointF(center.x + ay.x * height * 0.47f, center.y + ay.y * height * 0.47f)
        val forehead = PointF(eyeMid.x - ay.x * height * 0.33f, eyeMid.y - ay.y * height * 0.33f)

        return FaceGeometry(
            box, center, width, height, ax, ay, e1, e2, eyeW, eyeH,
            noseBase, noseW, noseLen, ml, mr, mouthCenter, mouthH, chin, forehead, faceOval?.takeIf { it.size >= 12 }
        )
    }

    // --- Warps -------------------------------------------------------------------------------

    /**
     * Computes the backward displacement field (each output pixel samples the source at p + d) of
     * all [faces] with their settings, for an image of [w] x [h]. Returns null when nothing warps.
     */
    fun field(faces: List<Pair<FaceGeometry, FaceAdjust>>, w: Int, h: Int, isCancelled: () -> Boolean = { false }): Pair<FloatArray, FloatArray>? {
        val active = faces.filter { it.second.hasWarp }
        if (active.isEmpty()) return null
        val dx = FloatArray(w * h); val dy = FloatArray(w * h)
        val acc = FloatArray(2)
        for ((g, a) in active) {
            val reach = max(g.width, g.height) * (if (a.headSize != 0f) 2.4f else 1.3f)
            val l = max(0, (g.center.x - reach).toInt()); val r = min(w - 1, (g.center.x + reach).toInt())
            val t = max(0, (g.center.y - reach).toInt()); val b = min(h - 1, (g.center.y + reach).toInt())
            val ops = buildOps(g, a)
            for (y in t..b) {
                if ((y and 63) == 0 && isCancelled()) return null
                for (x in l..r) {
                    acc[0] = 0f; acc[1] = 0f
                    for (op in ops) op.add(x.toFloat(), y.toFloat(), g.ax, g.ay, acc)
                    if (acc[0] != 0f || acc[1] != 0f) {
                        val i = y * w + x
                        dx[i] += acc[0]; dy[i] += acc[1]
                    }
                }
            }
        }
        return dx to dy
    }

    /** A local warp around [c] inside an ellipse with radii [rx] (along ax) and [ry] (along ay). */
    private sealed class Op(val c: PointF, val rx: Float, val ry: Float, val core: Float = -1f) {
        /** Smooth weight 1 at the centre to 0 at the ellipse edge; also returns local coords. */
        protected fun local(x: Float, y: Float, ax: PointF, ay: PointF, out: FloatArray): Float {
            val ox = x - c.x; val oy = y - c.y
            val lx = ox * ax.x + oy * ax.y; val ly = ox * ay.x + oy * ay.y
            val rho = (lx / rx) * (lx / rx) + (ly / ry) * (ly / ry)
            if (rho >= 1f) return 0f
            out[0] = lx; out[1] = ly
            if (core >= 0f) {
                // Flat inside the core (an even scale), smooth to 0 at the edge.
                val r = sqrt(rho)
                if (r <= core) return 1f
                val t = (r - core) / (1f - core)
                return 1f - t * t * (3f - 2f * t)
            }
            val k = 1f - rho
            return k * k
        }
        protected fun addLocal(dlx: Float, dly: Float, ax: PointF, ay: PointF, acc: FloatArray) {
            acc[0] += dlx * ax.x + dly * ay.x
            acc[1] += dlx * ax.y + dly * ay.y
        }
        private val tmp = FloatArray(2)
        open fun add(x: Float, y: Float, ax: PointF, ay: PointF, acc: FloatArray) {
            val wgt = local(x, y, ax, ay, tmp)
            if (wgt > 0f) apply(tmp[0], tmp[1], wgt, ax, ay, acc)
        }
        abstract fun apply(lx: Float, ly: Float, w: Float, ax: PointF, ay: PointF, acc: FloatArray)

        /** Enlarges (s > 1) or shrinks the content around [c]. */
        class Scale(c: PointF, rx: Float, ry: Float, val sx: Float, val sy: Float, core: Float = -1f) : Op(c, rx, ry, core) {
            override fun apply(lx: Float, ly: Float, w: Float, ax: PointF, ay: PointF, acc: FloatArray) {
                val ex = 1f + (sx - 1f) * w; val ey = 1f + (sy - 1f) * w
                addLocal(lx / ex - lx, ly / ey - ly, ax, ay, acc)
            }
        }

        /** Moves the content by (tx, ty) (local axes) at the centre, fading out. */
        class Move(c: PointF, rx: Float, ry: Float, val tx: Float, val ty: Float) : Op(c, rx, ry) {
            override fun apply(lx: Float, ly: Float, w: Float, ax: PointF, ay: PointF, acc: FloatArray) =
                addLocal(-tx * w, -ty * w, ax, ay, acc)
        }

        /**
         * Moves the face outline: control points [pts] (x, y pairs) move by [disp] (image dx, dy).
         * Around them the motion is interpolated (inverse distance, so neighbours never add up)
         * and fades out within [radius] of the outline.
         */
        class Contour(val pts: FloatArray, val disp: FloatArray, val radius: Float) : Op(PointF(0f, 0f), 1f, 1f) {
            private val r2 = radius * radius
            private val minX = (0 until pts.size / 2).minOf { pts[it * 2] } - radius
            private val maxX = (0 until pts.size / 2).maxOf { pts[it * 2] } + radius
            private val minY = (0 until pts.size / 2).minOf { pts[it * 2 + 1] } - radius
            private val maxY = (0 until pts.size / 2).maxOf { pts[it * 2 + 1] } + radius
            override fun add(x: Float, y: Float, ax: PointF, ay: PointF, acc: FloatArray) {
                if (x < minX || x > maxX || y < minY || y > maxY) return
                var dmin = Float.MAX_VALUE; var sw = 0f; var sx = 0f; var sy = 0f
                var i = 0
                while (i < pts.size) {
                    val dx = x - pts[i]; val dy = y - pts[i + 1]
                    val d2 = dx * dx + dy * dy
                    if (d2 < dmin) dmin = d2
                    if (d2 < r2) {
                        val w = 1f / (d2 + 4f)
                        sw += w; sx += w * disp[i]; sy += w * disp[i + 1]
                    }
                    i += 2
                }
                if (sw <= 0f) return
                val f = (1f - dmin / r2).coerceIn(0f, 1f).let { it * it }
                acc[0] -= sx / sw * f; acc[1] -= sy / sw * f
            }
            override fun apply(lx: Float, ly: Float, w: Float, ax: PointF, ay: PointF, acc: FloatArray) = Unit
        }

        /** Rotates the content by [angle] radians around [c]. */
        class Rotate(c: PointF, rx: Float, ry: Float, val angle: Float) : Op(c, rx, ry) {
            override fun apply(lx: Float, ly: Float, w: Float, ax: PointF, ay: PointF, acc: FloatArray) {
                val a = -angle * w
                val cs = cos(a); val sn = sin(a)
                addLocal(lx * cs - ly * sn - lx, lx * sn + ly * cs - ly, ax, ay, acc)
            }
        }
    }

    private fun p(base: PointF, g: FaceGeometry, alongX: Float, alongY: Float) =
        PointF(base.x + g.ax.x * alongX + g.ay.x * alongY, base.y + g.ax.y * alongX + g.ay.y * alongY)

    private fun buildOps(g: FaceGeometry, a: FaceAdjust): List<Op> {
        val ops = ArrayList<Op>()
        fun k(v: Float) = (v / 100f).coerceIn(-1f, 1f)
        val eyeDist = hypot(g.eye2.x - g.eye1.x, g.eye2.y - g.eye1.y)
        val eyeRx = g.eyeW * 0.95f
        val eyeRy = max(g.eyeH * 2.4f, g.eyeW * 0.65f)

        // Head and face shape first (large areas), then the features on top.
        if (a.headSize != 0f) {
            // The whole head (hair included) is scaled evenly; only the band around it bends.
            val c = p(g.center, g, 0f, -g.height * 0.16f)
            val s = 1f + 0.3f * k(a.headSize)
            ops += Op.Scale(c, g.width * 1.05f / 0.6f, g.height * 1.25f / 0.6f, s, s, core = 0.6f)
        }
        val oval = g.oval
        if (oval != null && (a.faceWidth != 0f || a.jawline != 0f)) {
            // Follow the real outline: each outline point moves sideways (along the face's own
            // horizontal axis) in proportion to its distance from the centre line.
            fun along(q: PointF, v: PointF) = (q.x - g.center.x) * v.x + (q.y - g.center.y) * v.y
            val eyeLevel = along(PointF((g.eye1.x + g.eye2.x) / 2f, (g.eye1.y + g.eye2.y) / 2f), g.ay)
            val noseLevel = along(g.noseBase, g.ay)
            val chinLevel = along(g.chin, g.ay)
            val mouthLevel = along(g.mouthCenter, g.ay)
            val top = eyeLevel - g.height * 0.15f
            fun smooth(e0: Float, e1: Float, v: Float): Float { val t = ((v - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
            val pts = FloatArray(oval.size * 2); val disp = FloatArray(oval.size * 2)
            for ((i, q) in oval.withIndex()) {
                val lx = along(q, g.ax); val ly = along(q, g.ay)
                var shift = 0f
                if (a.faceWidth != 0f && ly > top) {
                    val t = sin(PI.toFloat() * ((ly - top) / (chinLevel - top)).coerceIn(0f, 1f))
                    shift += lx * 0.16f * k(a.faceWidth) * t
                }
                if (a.jawline != 0f) shift += lx * 0.16f * k(a.jawline) * smooth(noseLevel, (mouthLevel + chinLevel) / 2f, ly)
                pts[i * 2] = q.x; pts[i * 2 + 1] = q.y
                disp[i * 2] = g.ax.x * shift; disp[i * 2 + 1] = g.ax.y * shift
            }
            ops += Op.Contour(pts, disp, g.width * 0.24f)
        } else if (a.faceWidth != 0f) ops += Op.Scale(p(g.center, g, 0f, g.height * 0.08f), g.width * 0.78f, g.height * 0.72f, 1f + 0.16f * k(a.faceWidth), 1f)
        if (a.forehead != 0f) ops += Op.Move(g.forehead, g.width * 0.55f, g.height * 0.3f, 0f, -0.07f * g.height * k(a.forehead))
        if (a.chinHeight != 0f) ops += Op.Move(p(g.chin, g, 0f, -g.height * 0.04f), g.width * 0.36f, g.height * 0.26f, 0f, 0.07f * g.height * k(a.chinHeight))
        if (a.jawline != 0f && oval == null) {
            for (side in listOf(-1f, 1f)) {
                val c = p(g.center, g, side * g.width * 0.36f, g.height * 0.26f)
                ops += Op.Move(c, g.width * 0.28f, g.height * 0.32f, side * 0.05f * g.width * k(a.jawline), 0f)
            }
        }
        for ((i, eye) in listOf(g.eye1, g.eye2).withIndex()) {
            val side = if (i == 0) -1f else 1f
            // Each eye's own values when the eyes are unlinked.
            val (size, height, width, tilt, distance) = a.eye(i).let { listOf(it[0], it[1], it[2], it[3], it[4]) }
            if (size != 0f || width != 0f || height != 0f) {
                val base = 0.3f * k(size)
                ops += Op.Scale(eye, eyeRx, eyeRy, 1f + base + 0.3f * k(width), 1f + base + 0.35f * k(height))
            }
            if (tilt != 0f) ops += Op.Rotate(eye, eyeRx, eyeRy, -side * (12f * PI.toFloat() / 180f) * k(tilt))
            if (distance != 0f) ops += Op.Move(eye, eyeRx * 1.1f, eyeRy, side * 0.1f * eyeDist * k(distance), 0f)
        }
        if (a.noseWidth != 0f) ops += Op.Scale(p(g.noseBase, g, 0f, -g.noseLen * 0.1f), g.noseW * 1.25f, g.noseLen * 0.62f, 1f + 0.3f * k(a.noseWidth), 1f)
        if (a.noseHeight != 0f) ops += Op.Move(g.noseBase, g.noseW * 1.2f, g.noseLen * 0.55f, 0f, 0.16f * g.noseLen * k(a.noseHeight))
        val mouthW = hypot(g.mouthRight.x - g.mouthLeft.x, g.mouthRight.y - g.mouthLeft.y)
        if (a.mouthWidth != 0f || a.mouthHeight != 0f) {
            ops += Op.Scale(g.mouthCenter, mouthW * 0.85f, g.mouthH * 1.4f + g.height * 0.05f, 1f + 0.25f * k(a.mouthWidth), 1f + 0.35f * k(a.mouthHeight))
        }
        if (a.smile != 0f) {
            for ((corner, side) in listOf(g.mouthLeft to -1f, g.mouthRight to 1f)) {
                ops += Op.Move(corner, mouthW * 0.32f, mouthW * 0.3f, side * 0.04f * mouthW * k(a.smile), -0.12f * mouthW * k(a.smile))
            }
        }
        if (a.upperLip != 0f) ops += Op.Move(p(g.mouthCenter, g, 0f, -g.mouthH * 0.45f), mouthW * 0.55f, g.mouthH * 0.6f, 0f, -0.25f * g.mouthH * k(a.upperLip))
        if (a.lowerLip != 0f) ops += Op.Move(p(g.mouthCenter, g, 0f, g.mouthH * 0.5f), mouthW * 0.55f, g.mouthH * 0.6f, 0f, 0.25f * g.mouthH * k(a.lowerLip))
        return ops
    }

    /**
     * Colour step for brighter eyes: lifts the eyes (soft ellipse around each eye in source
     * coordinates, so it follows the warped eye). Null when no face uses it.
     */
    fun eyeBrightener(faces: List<Pair<FaceGeometry, FaceAdjust>>): ((Float, Float, Int) -> Int)? {
        val active = faces.filter { it.second.eyeBrightness > 0f }
        if (active.isEmpty()) return null
        data class E(val c: PointF, val rx: Float, val ry: Float, val ax: PointF, val ay: PointF, val amount: Float)
        val eyes = active.flatMap { (g, a) ->
            listOf(g.eye1, g.eye2).map { E(it, g.eyeW * 0.62f, g.eyeH * 0.85f + 1f, g.ax, g.ay, a.eyeBrightness / 100f) }
        }
        return { x, y, c ->
            var out = c
            for (e in eyes) {
                val ox = x - e.c.x; val oy = y - e.c.y
                val reach = max(e.rx, e.ry) * 1.05f
                if (abs(ox) > reach || abs(oy) > reach) continue
                val lx = ox * e.ax.x + oy * e.ax.y; val ly = ox * e.ay.x + oy * e.ay.y
                val rho = (lx / e.rx) * (lx / e.rx) + (ly / e.ry) * (ly / e.ry)
                if (rho >= 1f) continue
                val w = (1f - rho).let { it * it } * e.amount
                // Lighter and slightly clearer: a gamma lift that keeps the colour.
                val g = 1f / (1f + 0.9f * w)
                val a = out ushr 24
                fun ch(sh: Int): Int = (255f * (((out shr sh) and 255) / 255f).pow(g)).toInt().coerceIn(0, 255)
                out = (a shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
            out
        }
    }
}
