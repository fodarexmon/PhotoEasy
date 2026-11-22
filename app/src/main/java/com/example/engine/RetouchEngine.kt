package com.example.engine

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.sin

enum class RetouchTool { BRUSH, ERASER, HEAL, BLUR, SHARPEN, SMUDGE, DODGE, BURN, CLONE, PATCH, FILL }

/** Brush tip shapes of the paint brush and eraser. */
enum class BrushTip { ROUND, CALLIGRAPHY, SPRAY, CHALK }

/** What the Fill tool puts in: a colour, a gradient (drag) or a pattern. */
enum class FillMode { COLOR, GRADIENT, PATTERN }

enum class GradientType { LINEAR, RADIAL, REFLECTED }

enum class FillPattern { CHECKER, STRIPES, DOTS, GRID, BRICKS, WAVES, NOISE, MARBLE }

enum class ToneRange { SHADOWS, MIDTONES, HIGHLIGHTS }

/** Brush options of the retouch tools (strength / hardness 0..1). */
data class RetouchBrush(
    val hardness: Float = 0.5f,
    val strength: Float = 0.5f,
    val range: ToneRange = ToneRange.MIDTONES,
    /** Dodge / burn: change lightness only, keeping hue and colourfulness (Photoshop "Protect Tones"). */
    val protectTones: Boolean = true,
    /** Fill (paint bucket): colour, opacity 0..1, tolerance 0..255, contiguous, smooth edges. */
    val fillColor: Int = 0xFFE53935.toInt(),
    val fillOpacity: Float = 1f,
    val fillTolerance: Int = 32,
    val fillContiguous: Boolean = true,
    val fillAntiAlias: Boolean = true,
    /** Second colour of gradients and patterns. */
    val fillColor2: Int = 0xFFFFFFFF.toInt(),
    val fillMode: FillMode = FillMode.COLOR,
    val gradientType: GradientType = GradientType.LINEAR,
    /** Gradient from the colour to transparent instead of to the second colour. */
    val gradientToTransparent: Boolean = false,
    val pattern: FillPattern = FillPattern.CHECKER,
    /** Pattern size, 10..400 %. */
    val patternScale: Float = 100f,
    /** Paint brush / eraser (Photoshop): opacity caps a stroke, flow builds up per dab. */
    val brushOpacity: Float = 1f,
    val brushFlow: Float = 1f,
    /** Distance between dabs, fraction of the brush diameter (0.02..1). */
    val brushSpacing: Float = 0.12f,
    /** Stroke smoothing 0..1 (steadier lines). */
    val brushSmoothing: Float = 0.3f,
    val brushTip: BrushTip = BrushTip.ROUND
)

/**
 * Photoshop-style retouch brushes on a layer's own full-resolution pixels ([pix], ARGB, w x h).
 *
 * - Blur / Sharpen / Smudge build up with every dab, as in Photoshop.
 * - Dodge / Burn / Clone Stamp do not build up inside one stroke: each pixel gets at most the
 *   brush's strength per stroke (coverage), computed from the pixels as they were when the stroke
 *   started, so going over the same spot does not darken / duplicate it more and more.
 * - Patch replaces a selected area with texture from elsewhere and blends its colour seamlessly
 *   into the surroundings (membrane interpolation of the edge differences).
 *
 * Not thread-safe: use from one thread.
 */
class RetouchCanvas(val width: Int, val height: Int, val pix: IntArray) {

    // Pixels as they were at the start of the stroke, copied per 64 px tile on first change.
    private val tile = 64
    private val tilesX = (width + tile - 1) / tile
    // Per-tile stores as plain arrays (no hashing or boxing in the per-pixel loops); the tiles
    // touched by a stroke are listed so clearing them is cheap.
    private val tilesY = (height + tile - 1) / tile
    private val baseTiles = arrayOfNulls<IntArray>(tilesX * tilesY)
    private val coverage = arrayOfNulls<ByteArray>(tilesX * tilesY)
    private val usedTiles = IntArray(tilesX * tilesY)
    private var usedCount = 0
    /** Spare buffers from earlier strokes, reused instead of allocating new ones. */
    private val spareBase = ArrayDeque<IntArray>()
    private val spareCov = ArrayDeque<ByteArray>()

    private fun clearTiles() {
        for (k in 0 until usedCount) {
            val key = usedTiles[k]
            baseTiles[key]?.let { if (spareBase.size < 256) spareBase.addLast(it) }
            coverage[key]?.let { it.fill(0); if (spareCov.size < 256) spareCov.addLast(it) }
            baseTiles[key] = null; coverage[key] = null
        }
        usedCount = 0
    }

    private fun markUsed(key: Int) {
        if (baseTiles[key] == null && coverage[key] == null) usedTiles[usedCount++] = key
    }
    private var tool = RetouchTool.BLUR
    private var brush = RetouchBrush()
    private var cloneDx = 0f
    private var cloneDy = 0f
    private var smudgeBuf: IntArray? = null
    private var smudgeR = 0
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var smX = Float.NaN
    private var smY = Float.NaN
    private var dabIndex = 0

    /** Selection (alpha bytes, row stride [selStride]) the current stroke stays inside; null = everywhere. */
    private var sel: ByteArray? = null
    private var selStride = width

    /** Selection weight at (x, y): 1 without a selection, the soft-edged mask value inside one. */
    private fun selAt(x: Int, y: Int): Float {
        val m = sel ?: return 1f
        return (m[y * selStride + x].toInt() and 255) / 255f
    }

    /**
     * Starts a stroke. With a [selection] (alpha bytes, row stride [selectionStride]) every tool
     * only changes pixels inside it, softly at its feathered edge, as in Photoshop.
     */
    fun beginStroke(
        tool: RetouchTool, brush: RetouchBrush, cloneOffsetX: Float = 0f, cloneOffsetY: Float = 0f,
        selection: ByteArray? = null, selectionStride: Int = width
    ) {
        sel = selection; selStride = selectionStride
        this.tool = tool; this.brush = brush
        cloneDx = cloneOffsetX; cloneDy = cloneOffsetY
        clearTiles()
        smudgeBuf = null
        lastX = Float.NaN; lastY = Float.NaN
        smX = Float.NaN; smY = Float.NaN
        dabIndex = 0
    }

    fun endStroke() {
        clearTiles(); smudgeBuf = null; sel = null
    }

    private fun tileKey(x: Int, y: Int) = (y / tile) * tilesX + (x / tile)

    private fun ensureBase(l: Int, t: Int, r: Int, b: Int) {
        var ty = t / tile
        while (ty * tile <= b) {
            var tx = l / tile
            while (tx * tile <= r) {
                val key = ty * tilesX + tx
                if (baseTiles[key] == null) {
                    val x0 = tx * tile; val y0 = ty * tile
                    val tw = min(tile, width - x0); val th = min(tile, height - y0)
                    val copy = spareBase.removeLastOrNull() ?: IntArray(tile * tile)
                    for (yy in 0 until th) System.arraycopy(pix, (y0 + yy) * width + x0, copy, yy * tile, tw)
                    markUsed(key)
                    baseTiles[key] = copy
                }
                tx++
            }
            ty++
        }
    }

    /** Pixel at the start of the stroke. */
    private fun base(x: Int, y: Int): Int {
        val t = baseTiles[tileKey(x, y)] ?: return pix[y * width + x]
        return t[(y % tile) * tile + (x % tile)]
    }

    private fun cover(x: Int, y: Int, value: Float): Float {
        val key = tileKey(x, y)
        val arr = coverage[key] ?: (spareCov.removeLastOrNull() ?: ByteArray(tile * tile)).also { markUsed(key); coverage[key] = it }
        val i = (y % tile) * tile + (x % tile)
        val old = (arr[i].toInt() and 255) / 255f
        if (value > old) { arr[i] = (value * 255f).roundToInt().coerceIn(0, 255).toByte(); return value }
        return old
    }

    private fun weight(d: Float, r: Float): Float {
        if (d >= r) return 0f
        val core = r * brush.hardness.coerceIn(0f, 0.98f)
        if (d <= core) return 1f
        val t = ((d - core) / (r - core)).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    /**
     * Dabs along the polyline [pts] (x0, y0, x1, y1, ... in local pixels) with radius [r].
     * Returns the changed area, or null.
     */
    fun stroke(pts: FloatArray, r: Float): Rect? {
        if (pts.size < 2) return null
        val radius = r.coerceAtLeast(1f)
        val painting = tool == RetouchTool.BRUSH || tool == RetouchTool.ERASER
        val spacing = (radius * when {
            painting -> 2f * brush.brushSpacing.coerceIn(0.02f, 1f)
            tool == RetouchTool.SMUDGE || tool == RetouchTool.BLUR -> 0.15f
            else -> 0.25f
        }).coerceAtLeast(1f)
        var dirty: Rect? = null
        fun add(rr: Rect?) { if (rr != null) { if (dirty == null) dirty = Rect(rr) else dirty!!.union(rr) } }
        var i = 0
        while (i + 1 < pts.size) {
            var x = pts[i]; var y = pts[i + 1]
            if (painting && brush.brushSmoothing > 0f) {
                // Lazy-follow smoothing: the brush trails the finger a little for steady lines.
                if (smX.isNaN()) { smX = x; smY = y } else {
                    val k = 1f - brush.brushSmoothing.coerceIn(0f, 0.95f) * 0.9f
                    smX += (x - smX) * k; smY += (y - smY) * k
                }
                x = smX; y = smY
            }
            if (lastX.isNaN()) {
                add(dab(x, y, radius)); lastX = x; lastY = y
            } else {
                val dx = x - lastX; val dy = y - lastY
                val dist = sqrt(dx * dx + dy * dy)
                if (dist >= spacing) {
                    val n = (dist / spacing).toInt()
                    for (k in 1..n) {
                        val t = k * spacing / dist
                        add(dab(lastX + dx * t, lastY + dy * t, radius))
                    }
                    lastX += dx * (n * spacing / dist); lastY += dy * (n * spacing / dist)
                }
            }
            i += 2
        }
        return dirty
    }

    // Brush falloff by squared distance (no square root per pixel), rebuilt when size / hardness change.
    private val lut = FloatArray(1025)
    private var lutR = -1f
    private var lutHard = -1f
    private var invR2 = 0f

    private fun prepLut(r: Float) {
        if (r == lutR && brush.hardness == lutHard) return
        lutR = r; lutHard = brush.hardness
        invR2 = 1f / (r * r)
        for (i in 0..1024) lut[i] = weight(sqrt(i / 1024f) * r, r)
    }

    private fun wAt(x: Int, y: Int, cx: Float, cy: Float): Float {
        val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
        val d2 = (dx * dx + dy * dy) * invR2
        if (d2 >= 1f) return 0f
        val w = lut[(d2 * 1024f).toInt()]
        return if (sel == null) w else w * selAt(x, y)
    }

    private fun dab(cx: Float, cy: Float, r: Float): Rect? {
        prepLut(r)
        val l = max(0, (cx - r).toInt()); val t = max(0, (cy - r).toInt())
        val rr = min(width - 1, (cx + r).toInt() + 1); val b = min(height - 1, (cy + r).toInt() + 1)
        if (l > rr || t > b) return null
        when (tool) {
            RetouchTool.DODGE, RetouchTool.BURN -> { ensureBase(l, t, rr, b); toneDab(cx, cy, r, l, t, rr, b) }
            RetouchTool.CLONE -> { ensureBase(l, t, rr, b); cloneDab(cx, cy, r, l, t, rr, b) }
            RetouchTool.BLUR, RetouchTool.SHARPEN -> filterDab(cx, cy, r, l, t, rr, b)
            RetouchTool.SMUDGE -> smudgeDab(cx, cy, r, l, t, rr, b)
            RetouchTool.BRUSH, RetouchTool.ERASER -> paintDab(cx, cy, r, l, t, rr, b)
            RetouchTool.PATCH, RetouchTool.FILL, RetouchTool.HEAL -> return null
        }
        return Rect(l, t, rr + 1, b + 1)
    }

    // --- Paint brush / eraser ----------------------------------------------------------------

    /** Stroke coverage that builds up by [add] per dab (flow) but never above [cap] (opacity). */
    private fun accumulate(x: Int, y: Int, add: Float, cap: Float): Float {
        val key = tileKey(x, y)
        val arr = coverage[key] ?: (spareCov.removeLastOrNull() ?: ByteArray(tile * tile)).also { markUsed(key); coverage[key] = it }
        val i = (y % tile) * tile + (x % tile)
        val old = (arr[i].toInt() and 255) / 255f
        if (old >= cap) return old
        val nv = min(cap, old + (1f - old) * add)
        arr[i] = (nv * 255f).roundToInt().coerceIn(0, 255).toByte()
        return nv
    }

    private fun hash(x: Int, y: Int, s: Int): Float {
        var h = x * 374761393 + y * 668265263 + s * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777215f
    }

    /** Tip shape weight at pixel (x, y) for a dab at (cx, cy). */
    private fun tipWeight(x: Int, y: Int, cx: Float, cy: Float, r: Float): Float = when (brush.brushTip) {
        BrushTip.ROUND -> wAt(x, y, cx, cy)
        BrushTip.CALLIGRAPHY -> {
            // A flat nib at 45°: thin one way, wide the other.
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
            val u = (dx + dy) * 0.7071f; val v = (dy - dx) * 0.7071f
            weight(sqrt(u * u + (v / 0.28f) * (v / 0.28f)), r) * selAt(x, y)
        }
        BrushTip.SPRAY -> {
            val w = wAt(x, y, cx, cy)
            if (w > 0f && hash(x, y, dabIndex) < 0.07f + 0.1f * brush.hardness) selAt(x, y) else 0f
        }
        BrushTip.CHALK -> {
            // Fixed grain on the image: repeated dabs leave the gaps, like chalk on paper.
            val w = wAt(x, y, cx, cy)
            if (w <= 0f) 0f else w * (if (hash(x shr 1, y shr 1, 7) > 0.42f) 1f else 0.12f)
        }
    }

    private fun paintDab(cx: Float, cy: Float, r: Float, l: Int, t: Int, rr: Int, b: Int) {
        ensureBase(l, t, rr, b)
        dabIndex++
        val flow = brush.brushFlow.coerceIn(0.01f, 1f)
        val cap = brush.brushOpacity.coerceIn(0.01f, 1f)
        val erase = tool == RetouchTool.ERASER
        val color = brush.fillColor or (0xFF shl 24)
        for (y in t..b) for (x in l..rr) {
            val w = tipWeight(x, y, cx, cy, r)
            if (w <= 0f) continue
            val cov = accumulate(x, y, w * flow, cap)
            val c = base(x, y)
            pix[y * width + x] = if (erase) {
                val a = ((c ushr 24) * (1f - cov)).roundToInt()
                if (a <= 0) 0 else (a shl 24) or (c and 0xFFFFFF)
            } else over(c, color, cov)
        }
    }

    private fun rangeWeight(lum: Float): Float = when (brush.range) {
        ToneRange.SHADOWS -> (1f - lum) * (1f - lum)
        ToneRange.MIDTONES -> 4f * lum * (1f - lum)
        ToneRange.HIGHLIGHTS -> lum * lum
    }

    private fun toneDab(cx: Float, cy: Float, r: Float, l: Int, t: Int, rr: Int, b: Int) {
        val dodge = tool == RetouchTool.DODGE
        for (y in t..b) for (x in l..rr) {
            val w = wAt(x, y, cx, cy)
            if (w <= 0f) continue
            val cov = cover(x, y, w * brush.strength)
            val c = base(x, y)
            val a = c ushr 24
            if (a == 0) continue
            val rf = ((c shr 16) and 255) / 255f; val gf = ((c shr 8) and 255) / 255f; val bf = (c and 255) / 255f
            val lum = 0.299f * rf + 0.587f * gf + 0.114f * bf
            val k = 1.6f * cov * rangeWeight(lum)
            val gamma = if (dodge) 1f / (1f + k) else 1f + k
            val out = if (brush.protectTones) {
                // Change lightness only: scale towards the new luminance, keep hue and colourfulness.
                val nl = lum.coerceIn(1e-4f, 1f).pow(gamma)
                if (lum < 1e-4f) { val v = nl; pack(a, v, v, v) }
                else {
                    val s = nl / lum
                    var nr = rf * s; var ng = gf * s; var nb = bf * s
                    val mx = max(nr, max(ng, nb))
                    if (mx > 1f) {
                        // Over the top: blend towards white while keeping the luminance.
                        val over = (mx - 1f) / mx
                        nr = nr + (1f - nr) * over; ng = ng + (1f - ng) * over; nb = nb + (1f - nb) * over
                    }
                    pack(a, nr, ng, nb)
                }
            } else {
                pack(a, rf.pow(gamma), gf.pow(gamma), bf.pow(gamma))
            }
            pix[y * width + x] = out
        }
    }

    private fun cloneDab(cx: Float, cy: Float, r: Float, l: Int, t: Int, rr: Int, b: Int) {
        val op = brush.strength.coerceIn(0.01f, 1f)
        for (y in t..b) for (x in l..rr) {
            val w = wAt(x, y, cx, cy)
            if (w <= 0f) continue
            val sx = (x + cloneDx).roundToInt(); val sy = (y + cloneDy).roundToInt()
            if (sx < 0 || sy < 0 || sx >= width || sy >= height) continue
            val cov = cover(x, y, w * op)
            pix[y * width + x] = mix(base(x, y), base(sx, sy), cov)
        }
    }

    // Summed-area tables reused between dabs (premultiplied channels, alpha).
    private var satA = LongArray(0); private var satR = LongArray(0); private var satG = LongArray(0); private var satB = LongArray(0)

    private fun filterDab(cx: Float, cy: Float, r: Float, l: Int, t: Int, rr: Int, b: Int) {
        val k = (r / 10f).roundToInt().coerceIn(1, 6)
        val sharpen = tool == RetouchTool.SHARPEN
        val kk = if (sharpen) 1 else k
        // Box sums from a summed-area table of the area (+kk margin) as it was before this dab:
        // a constant cost per pixel whatever the blur size.
        val l2 = max(0, l - kk); val t2 = max(0, t - kk); val r2 = min(width - 1, rr + kk); val b2 = min(height - 1, b + kk)
        val w2 = r2 - l2 + 1; val h2 = b2 - t2 + 1
        val sw = w2 + 1
        val need = sw * (h2 + 1)
        if (satA.size < need) { satA = LongArray(need); satR = LongArray(need); satG = LongArray(need); satB = LongArray(need) }
        val A = satA; val R = satR; val G = satG; val B = satB
        for (i in 0 until sw) { A[i] = 0; R[i] = 0; G[i] = 0; B[i] = 0 }
        for (yy in 0 until h2) {
            var ra = 0L; var rr0 = 0L; var rg = 0L; var rb = 0L
            val row = (t2 + yy) * width + l2
            val o = (yy + 1) * sw; val up = yy * sw
            A[o] = 0; R[o] = 0; G[o] = 0; B[o] = 0
            for (xx in 0 until w2) {
                val c = pix[row + xx]
                val a = (c ushr 24).toLong()
                ra += a; rr0 += ((c shr 16) and 255) * a; rg += ((c shr 8) and 255) * a; rb += (c and 255) * a
                val j = o + xx + 1; val ju = up + xx + 1
                A[j] = A[ju] + ra; R[j] = R[ju] + rr0; G[j] = G[ju] + rg; B[j] = B[ju] + rb
            }
        }
        val s = brush.strength
        // The dab writes into pix while reading sums of the old pixels: keep the old centre row values.
        for (y in t..b) for (x in l..rr) {
            val wgt = wAt(x, y, cx, cy)
            if (wgt <= 0f) continue
            val x0 = max(l2, x - kk) - l2; val x1 = min(r2, x + kk) - l2 + 1
            val y0 = max(t2, y - kk) - t2; val y1 = min(b2, y + kk) - t2 + 1
            val i11 = y1 * sw + x1; val i01 = y0 * sw + x1; val i10 = y1 * sw + x0; val i00 = y0 * sw + x0
            val sa = A[i11] - A[i01] - A[i10] + A[i00]
            if (sa <= 0L) continue
            val n = (x1 - x0) * (y1 - y0)
            val br = ((R[i11] - R[i01] - R[i10] + R[i00]) / sa).toInt()
            val bg = ((G[i11] - G[i01] - G[i10] + G[i00]) / sa).toInt()
            val bb = ((B[i11] - B[i01] - B[i10] + B[i00]) / sa).toInt()
            val ba = (sa / n).toInt().coerceIn(0, 255)
            val i = y * width + x
            val cur = pix[i]
            if (sharpen) {
                val amt = 0.9f * s * wgt
                val cr = (cur shr 16) and 255; val cg = (cur shr 8) and 255; val cb = cur and 255
                val nr = (cr + (cr - br) * amt).roundToInt().coerceIn(0, 255)
                val ng = (cg + (cg - bg) * amt).roundToInt().coerceIn(0, 255)
                val nb = (cb + (cb - bb) * amt).roundToInt().coerceIn(0, 255)
                pix[i] = (cur and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
            } else {
                pix[i] = mix(cur, (ba shl 24) or (br.coerceIn(0, 255) shl 16) or (bg.coerceIn(0, 255) shl 8) or bb.coerceIn(0, 255), 0.65f * s * wgt)
            }
        }
    }

    private fun smudgeDab(cx: Float, cy: Float, r: Float, l: Int, t: Int, rr: Int, b: Int) {
        val ir = r.roundToInt().coerceAtLeast(1)
        val size = 2 * ir + 1
        val icx = cx.roundToInt(); val icy = cy.roundToInt()
        var buf = smudgeBuf
        if (buf == null || smudgeR != ir) {
            // Pick up the paint under the brush at the start of the stroke.
            buf = IntArray(size * size) { j ->
                val x = (icx - ir + j % size).coerceIn(0, width - 1); val y = (icy - ir + j / size).coerceIn(0, height - 1)
                pix[y * width + x]
            }
            smudgeBuf = buf; smudgeR = ir
            return
        }
        val s = brush.strength.coerceIn(0.01f, 0.98f)
        for (y in t..b) for (x in l..rr) {
            val w = wAt(x, y, cx, cy)
            if (w <= 0f) continue
            val bx = x - icx + ir; val by = y - icy + ir
            if (bx < 0 || by < 0 || bx >= size || by >= size) continue
            val j = by * size + bx
            val i = y * width + x
            val before = pix[i]
            pix[i] = mix(before, buf[j], s * w)
            // The brush slowly takes on the colour it passes over (less at high strength).
            buf[j] = mix(buf[j], before, (1f - s) * w)
        }
    }

    // --- Patch -------------------------------------------------------------------------------

    /**
     * Replaces the area selected in [mask] (alpha bytes, w x h, row stride [maskStride]) with the
     * pixels found [ox], [oy] away, and blends their colour into the surroundings so no seam shows.
     * Returns the changed area, or null when nothing is selected.
     */
    fun patch(mask: ByteArray, maskStride: Int, ox: Int, oy: Int, hint: Rect? = null): Rect? {
        var l = width; var t = height; var r = -1; var b = -1
        // [hint]: the mask is known to lie inside it (no full-image scan).
        val sy0 = hint?.top?.coerceIn(0, height) ?: 0; val sy1 = hint?.bottom?.coerceIn(0, height) ?: height
        val sx0 = hint?.left?.coerceIn(0, width) ?: 0; val sx1 = hint?.right?.coerceIn(0, width) ?: width
        for (y in sy0 until sy1) {
            val row = y * maskStride
            for (x in sx0 until sx1) if ((mask[row + x].toInt() and 255) > 8) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            }
        }
        if (r < 0) return null
        // Grid over the area plus a margin of known pixels, at most ~200 cells per side.
        val margin = max(4, (max(r - l, b - t) * 0.06f).toInt())
        val gl = max(0, l - margin); val gt = max(0, t - margin); val gr = min(width - 1, r + margin); val gb = min(height - 1, b + margin)
        val bw = gr - gl + 1; val bh = gb - gt + 1
        val cell = max(1, (max(bw, bh) + 199) / 200)
        val gw = (bw + cell - 1) / cell; val gh = (bh + cell - 1) / cell
        fun srcAt(x: Int, y: Int): Int = pix[(y + oy).coerceIn(0, height - 1) * width + (x + ox).coerceIn(0, width - 1)]
        // Edge differences (destination - source) on cells outside the selection; unknown inside.
        val known = BooleanArray(gw * gh)
        val dr = FloatArray(gw * gh); val dg = FloatArray(gw * gh); val db = FloatArray(gw * gh)
        var sumR = 0f; var sumG = 0f; var sumB = 0f; var cnt = 0
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var inside = false
            var ar = 0f; var ag = 0f; var ab = 0f; var n = 0
            for (yy in gt + gy * cell until min(gb + 1, gt + (gy + 1) * cell)) for (xx in gl + gx * cell until min(gr + 1, gl + (gx + 1) * cell)) {
                if ((mask[yy * maskStride + xx].toInt() and 255) > 8) inside = true
                val d = pix[yy * width + xx]; val s = srcAt(xx, yy)
                ar += ((d shr 16) and 255) - ((s shr 16) and 255)
                ag += ((d shr 8) and 255) - ((s shr 8) and 255)
                ab += (d and 255) - (s and 255); n++
            }
            val g = gy * gw + gx
            if (!inside && n > 0) {
                known[g] = true; dr[g] = ar / n; dg[g] = ag / n; db[g] = ab / n
                sumR += dr[g]; sumG += dg[g]; sumB += db[g]; cnt++
            }
        }
        if (cnt > 0) for (g in 0 until gw * gh) if (!known[g]) { dr[g] = sumR / cnt; dg[g] = sumG / cnt; db[g] = sumB / cnt }
        // Laplace smoothing of the unknown cells (Gauss-Seidel): a smooth colour membrane.
        repeat(400) {
            for (gy in 0 until gh) for (gx in 0 until gw) {
                val g = gy * gw + gx
                if (known[g]) continue
                var ar = 0f; var ag = 0f; var ab = 0f; var n = 0
                if (gx > 0) { ar += dr[g - 1]; ag += dg[g - 1]; ab += db[g - 1]; n++ }
                if (gx < gw - 1) { ar += dr[g + 1]; ag += dg[g + 1]; ab += db[g + 1]; n++ }
                if (gy > 0) { ar += dr[g - gw]; ag += dg[g - gw]; ab += db[g - gw]; n++ }
                if (gy < gh - 1) { ar += dr[g + gw]; ag += dg[g + gw]; ab += db[g + gw]; n++ }
                dr[g] = ar / n; dg[g] = ag / n; db[g] = ab / n
            }
        }
        // Source + interpolated difference, mixed in by the selection's own soft edge.
        val out = IntArray(bw * bh)
        for (y in gt..gb) for (x in gl..gr) {
            val m = (mask[y * maskStride + x].toInt() and 255) / 255f
            val i = y * width + x
            if (m <= 0f) { out[(y - gt) * bw + (x - gl)] = pix[i]; continue }
            val fx = ((x - gl) / cell.toFloat() - 0.5f).coerceIn(0f, (gw - 1).toFloat())
            val fy = ((y - gt) / cell.toFloat() - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(x0 + 1, gw - 1); val y1 = min(y0 + 1, gh - 1)
            val ax = fx - x0; val ay = fy - y0
            fun bil(a: FloatArray) = (a[y0 * gw + x0] * (1 - ax) + a[y0 * gw + x1] * ax) * (1 - ay) + (a[y1 * gw + x0] * (1 - ax) + a[y1 * gw + x1] * ax) * ay
            val s = srcAt(x, y)
            val patched = pack(
                pix[i] ushr 24,
                (((s shr 16) and 255) + bil(dr)) / 255f,
                (((s shr 8) and 255) + bil(dg)) / 255f,
                ((s and 255) + bil(db)) / 255f
            )
            out[(y - gt) * bw + (x - gl)] = mix(pix[i], patched, m)
        }
        for (y in gt..gb) System.arraycopy(out, (y - gt) * bw, pix, y * width + gl, bw)
        return Rect(gl, gt, gr + 1, gb + 1)
    }

    // --- Spot Healing ---------------------------------------------------------------------------

    /**
     * Photoshop's Spot Healing Brush: the area painted along [pts] (x0, y0, x1, y1, ... local
     * pixels) with radius [r] is replaced by texture from the best-matching clean area around it
     * (chosen automatically), its colour blended in seamlessly (see [patch]). Returns the changed
     * area, or null.
     */
    fun heal(pts: FloatArray, r: Float, selection: ByteArray? = null, selectionStride: Int = width): Rect? {
        if (pts.size < 2) return null
        val rad = r.coerceAtLeast(1.5f)
        // Painted area (soft edge), dabs every third of the radius along the path.
        var l = width; var t = height; var rr = -1; var b = -1
        val dabs = ArrayList<Float>()
        var i = 0
        while (i + 1 < pts.size) {
            val x = pts[i]; val y = pts[i + 1]
            if (dabs.isEmpty()) { dabs.add(x); dabs.add(y) } else {
                val px = dabs[dabs.size - 2]; val py = dabs[dabs.size - 1]
                val d = sqrt((x - px) * (x - px) + (y - py) * (y - py))
                val n = (d / (rad / 3f)).toInt()
                for (k in 1..n) { dabs.add(px + (x - px) * k / n); dabs.add(py + (y - py) * k / n) }
                if (n == 0 && d > 0.5f) { dabs.add(x); dabs.add(y) }
            }
            i += 2
        }
        val outer = rad * 1.15f
        var k = 0
        while (k < dabs.size) {
            l = min(l, (dabs[k] - outer).toInt()); rr = max(rr, (dabs[k] + outer).toInt() + 1)
            t = min(t, (dabs[k + 1] - outer).toInt()); b = max(b, (dabs[k + 1] + outer).toInt() + 1)
            k += 2
        }
        l = max(0, l); t = max(0, t); rr = min(width - 1, rr); b = min(height - 1, b)
        if (l > rr || t > b) return null
        val bw = rr - l + 1; val bh = b - t + 1
        val m = ByteArray(bw * bh)
        k = 0
        while (k < dabs.size) {
            val cx = dabs[k]; val cy = dabs[k + 1]
            val x0 = max(l, (cx - outer).toInt()); val x1 = min(rr, (cx + outer).toInt() + 1)
            val y0 = max(t, (cy - outer).toInt()); val y1 = min(b, (cy + outer).toInt() + 1)
            for (y in y0..y1) for (x in x0..x1) {
                val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
                val d = sqrt(dx * dx + dy * dy)
                val v = when { d <= rad * 0.85f -> 255; d >= outer -> 0; else -> (255 * (outer - d) / (outer - rad * 0.85f)).toInt() }
                val j = (y - t) * bw + (x - l)
                if (v > (m[j].toInt() and 255)) m[j] = v.toByte()
            }
            k += 2
        }
        // Only inside the selection (softly at its edge).
        if (selection != null) {
            var anyLeft = false
            for (y in 0 until bh) for (x in 0 until bw) {
                val j = y * bw + x
                val v = m[j].toInt() and 255
                if (v == 0) continue
                val sv = selection[(y + t) * selectionStride + x + l].toInt() and 255
                val nv = v * sv / 255
                m[j] = nv.toByte()
                if (nv > 8) anyLeft = true
            }
            if (!anyLeft) return null
        }
        val off = bestSource(m, l, t, bw, bh, rad) ?: return null
        // patch() works on a full-image mask: build it only inside the box.
        val full = ByteArray(width * height)
        for (y in 0 until bh) System.arraycopy(m, y * bw, full, (y + t) * width + l, bw)
        return patch(full, width, off.first, off.second, Rect(l, t, rr + 1, b + 1))
    }

    /**
     * Offset of the clean area to copy for the painted area [m] (bw x bh at l, t): among
     * candidates around it, the one whose surroundings match the painted area's surroundings best
     * and whose own pixels are even (no other blemish copied in).
     */
    internal fun bestSource(m: ByteArray, l: Int, t: Int, bw: Int, bh: Int, rad: Float): Pair<Int, Int>? {
        val ring = max(2, (rad * 0.6f).toInt())
        // Ring pixels (just outside the painted area) and inner pixels, sampled.
        val ringPts = ArrayList<Int>(); val innerPts = ArrayList<Int>()
        val step = max(1, (max(bw, bh) / 48))
        for (y in -ring until bh + ring step step) for (x in -ring until bw + ring step step) {
            val gx = l + x; val gy = t + y
            if (gx !in 0 until width || gy !in 0 until height) continue
            val inside = x in 0 until bw && y in 0 until bh && (m[y * bw + x].toInt() and 255) > 8
            if (inside) { innerPts.add(gy * width + gx); continue }
            // Within `ring` px of the painted area?
            var near = false
            var yy = max(0, y - ring)
            while (!near && yy <= min(bh - 1, y + ring)) {
                var xx = max(0, x - ring)
                while (xx <= min(bw - 1, x + ring)) { if ((m[yy * bw + xx].toInt() and 255) > 8) { near = true; break }; xx += max(1, ring / 2) }
                yy += max(1, ring / 2)
            }
            if (near) ringPts.add(gy * width + gx)
        }
        if (ringPts.isEmpty() || innerPts.isEmpty()) return null
        var ringLum = 0f
        for (q in ringPts) ringLum += lumOf(pix[q])
        ringLum /= ringPts.size
        val extent = max(bw, bh).toFloat()
        var best: Pair<Int, Int>? = null
        var bestScore = Float.MAX_VALUE
        for (f in floatArrayOf(1.1f, 1.6f, 2.4f)) for (a in 0 until 16) {
            val ang = a * (Math.PI / 8).toFloat()
            val ox = (kotlin.math.cos(ang) * extent * f).roundToInt(); val oy = (kotlin.math.sin(ang) * extent * f).roundToInt()
            if (l - ring + ox < 0 || t - ring + oy < 0 || l + bw + ring + ox > width || t + bh + ring + oy > height) continue
            // Surroundings alike: colour difference of the two rings.
            var ssd = 0f
            for (q in ringPts) {
                val c1 = pix[q]; val c2 = pix[q + oy * width + ox]
                val dr = ((c1 shr 16) and 255) - ((c2 shr 16) and 255); val dg = ((c1 shr 8) and 255) - ((c2 shr 8) and 255); val db = (c1 and 255) - (c2 and 255)
                ssd += (dr * dr + dg * dg + db * db).toFloat()
            }
            ssd /= ringPts.size
            // The source itself should look like clean surroundings (no spot, no edge).
            var dev = 0f; var srcA = 0
            for (q in innerPts) {
                val c = pix[q + oy * width + ox]
                if ((c ushr 24) < 200) srcA++
                val d = lumOf(c) - ringLum
                dev += d * d
            }
            dev /= innerPts.size
            val score = ssd + 1.5f * dev + if (srcA > innerPts.size / 10) 1e9f else 0f
            if (score < bestScore) { bestScore = score; best = ox to oy }
        }
        return best
    }

    private fun lumOf(c: Int): Float = 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)

    // --- Fill (paint bucket) ------------------------------------------------------------------

    /**
     * Photoshop's Paint Bucket at ([x], [y]): pixels whose colour (and alpha) differ from the
     * clicked pixel by at most [tolerance] per channel get [color] at [opacity]; only those
     * connected to it when [contiguous]. [antiAlias] softens the edge by one pixel. Limited to the
     * selection [mask] (alpha bytes, row stride [maskStride]) when given; a click outside it fills
     * nothing. Returns the changed area, or null.
     */
    fun fill(
        x: Int, y: Int, color: Int, opacity: Float, tolerance: Int, contiguous: Boolean, antiAlias: Boolean,
        mask: ByteArray? = null, maskStride: Int = width
    ): Rect? = fill(x, y, { _, _ -> color }, opacity, tolerance, contiguous, antiAlias, mask, maskStride)

    /** As [fill] with a colour per pixel ([paint], e.g. a gradient or a pattern). */
    fun fill(
        x: Int, y: Int, paint: (Int, Int) -> Int, opacity: Float, tolerance: Int, contiguous: Boolean, antiAlias: Boolean,
        mask: ByteArray? = null, maskStride: Int = width
    ): Rect? {
        if (x !in 0 until width || y !in 0 until height || opacity <= 0f) return null
        if (mask != null && (mask[y * maskStride + x].toInt() and 255) <= 8) return null
        // A click inside the previous fill (left untouched since) replaces it, like Photoshop:
        // the old colour or pattern comes off and the new one goes on the same area.
        lastFill?.let { lf ->
            if (x in lf.l until lf.l + lf.w && y in lf.t until lf.t + lf.h &&
                (lf.cov[(y - lf.t) * lf.w + (x - lf.l)].toInt() and 255) > 0 && rectHash(lf.l, lf.t, lf.w, lf.h) == lf.afterHash) {
                for (ry in 0 until lf.h) System.arraycopy(lf.before, ry * lf.w, pix, (lf.t + ry) * width + lf.l, lf.w)
                val changed = applyCoverage(lf.cov, lf.l, lf.t, lf.w, lf.h, paint, opacity.coerceIn(0f, 1f))
                lf.afterHash = rectHash(lf.l, lf.t, lf.w, lf.h)
                return Rect(lf.l, lf.t, lf.l + lf.w, lf.t + lf.h).takeIf { changed }
            }
        }
        lastFill = null
        val seed = pix[y * width + x]
        val tol = tolerance.coerceIn(0, 255)
        val sa = seed ushr 24; val sr = (seed shr 16) and 255; val sg = (seed shr 8) and 255; val sb = seed and 255
        fun matches(c: Int): Boolean {
            val a = c ushr 24
            // Fully transparent pixels match each other whatever their (meaningless) colour.
            if (a == 0 && sa == 0) return true
            return abs(a - sa) <= tol && abs(((c shr 16) and 255) - sr) <= tol &&
                abs(((c shr 8) and 255) - sg) <= tol && abs((c and 255) - sb) <= tol
        }
        val region = ByteArray(width * height)
        var l = width; var t = height; var r = -1; var b = -1
        fun mark(i: Int, px: Int, py: Int) {
            region[i] = 1
            if (px < l) l = px; if (px > r) r = px; if (py < t) t = py; if (py > b) b = py
        }
        if (contiguous) {
            // Scanline flood fill.
            var stack = IntArray(256); var sp = 0
            fun push(v: Int) { if (sp == stack.size) stack = stack.copyOf(stack.size * 2); stack[sp++] = v }
            push(y * width + x)
            while (sp > 0) {
                val i = stack[--sp]
                if (region[i].toInt() != 0) continue
                val py = i / width
                var lx = i % width
                while (lx > 0 && region[py * width + lx - 1].toInt() == 0 && matches(pix[py * width + lx - 1])) lx--
                var px = lx
                var upOpen = false; var downOpen = false
                while (px < width) {
                    val j = py * width + px
                    if (region[j].toInt() != 0 || !matches(pix[j])) break
                    mark(j, px, py)
                    if (py > 0) {
                        val u = j - width
                        val ok = region[u].toInt() == 0 && matches(pix[u])
                        if (ok && !upOpen) push(u)
                        upOpen = ok
                    }
                    if (py < height - 1) {
                        val d = j + width
                        val ok = region[d].toInt() == 0 && matches(pix[d])
                        if (ok && !downOpen) push(d)
                        downOpen = ok
                    }
                    px++
                }
            }
        } else {
            for (py in 0 until height) for (px in 0 until width) {
                val i = py * width + px
                if (matches(pix[i])) mark(i, px, py)
            }
        }
        if (r < 0) return null
        // One pixel of soft edge (anti-aliasing) around the region.
        val gl = if (antiAlias) max(0, l - 1) else l; val gt = if (antiAlias) max(0, t - 1) else t
        val gr = if (antiAlias) min(width - 1, r + 1) else r; val gb = if (antiAlias) min(height - 1, b + 1) else b
        val gw = gr - gl + 1; val gh = gb - gt + 1
        val covBytes = ByteArray(gw * gh)
        for (py in gt..gb) for (px in gl..gr) {
            val i = py * width + px
            var cov = if (region[i].toInt() != 0) 1f else 0f
            if (cov == 0f && antiAlias) {
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val qx = px + dx; val qy = py + dy
                    if (qx in 0 until width && qy in 0 until height && region[qy * width + qx].toInt() != 0) n++
                }
                cov = n / 9f
            }
            if (mask != null) cov *= (mask[py * maskStride + px].toInt() and 255) / 255f
            covBytes[(py - gt) * gw + (px - gl)] = (cov * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        // Keep what was under the fill so a later fill on it can replace it.
        val before = try {
            IntArray(gw * gh).also { bf -> for (ry in 0 until gh) System.arraycopy(pix, (gt + ry) * width + gl, bf, ry * gw, gw) }
        } catch (e: OutOfMemoryError) { null }
        val any = applyCoverage(covBytes, gl, gt, gw, gh, paint, opacity.coerceIn(0f, 1f))
        if (!any) return null
        if (before != null) lastFill = LastFill(gl, gt, gw, gh, covBytes, before, rectHash(gl, gt, gw, gh))
        return Rect(gl, gt, gr + 1, gb + 1)
    }

    /** The last fill: its area, coverage, the pixels it covered and a hash of the result. */
    private class LastFill(val l: Int, val t: Int, val w: Int, val h: Int, val cov: ByteArray, val before: IntArray, var afterHash: Long)

    private var lastFill: LastFill? = null

    /** Forgets the last fill (it can no longer be replaced). */
    fun forgetLastFill() { lastFill = null }

    private fun applyCoverage(cov: ByteArray, l: Int, t: Int, w: Int, h: Int, paint: (Int, Int) -> Int, op: Float): Boolean {
        var any = false
        for (ry in 0 until h) for (rx in 0 until w) {
            val c = (cov[ry * w + rx].toInt() and 255) / 255f
            if (c <= 0f) continue
            val i = (t + ry) * width + l + rx
            pix[i] = over(pix[i], paint(l + rx, t + ry), c * op)
            any = true
        }
        return any
    }

    private fun rectHash(l: Int, t: Int, w: Int, h: Int): Long {
        var hsh = 1125899906842597L
        for (ry in 0 until h) {
            val row = (t + ry) * width + l
            for (rx in 0 until w) hsh = 31 * hsh + pix[row + rx]
        }
        return hsh
    }

    // --- Preview -----------------------------------------------------------------------------

    /**
     * Updates [preview] (the layer's smaller preview of these same pixels) inside the full-res
     * area [area] with a box-filtered (premultiplied) downscale.
     */
    fun downsampleInto(preview: Bitmap, area: Rect) {
        val pw = preview.width; val ph = preview.height
        val sx = pw.toFloat() / width; val sy = ph.toFloat() / height
        val pl = max(0, (area.left * sx).toInt()); val pt = max(0, (area.top * sy).toInt())
        val pr = min(pw, kotlin.math.ceil(area.right * sx).toInt()); val pb = min(ph, kotlin.math.ceil(area.bottom * sy).toInt())
        if (pl >= pr || pt >= pb) return
        val rowOut = IntArray(pr - pl)
        for (py in pt until pb) {
            val fy0 = (py / sy).toInt(); val fy1 = min(height, max(fy0 + 1, ((py + 1) / sy).toInt()))
            for (px in pl until pr) {
                val fx0 = (px / sx).toInt(); val fx1 = min(width, max(fx0 + 1, ((px + 1) / sx).toInt()))
                var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
                for (y in fy0 until fy1) for (x in fx0 until fx1) {
                    val c = pix[y * width + x]; val a = (c ushr 24).toLong()
                    sa += a; sr += ((c shr 16) and 255) * a; sg += ((c shr 8) and 255) * a; sb += (c and 255) * a; n++
                }
                rowOut[px - pl] = if (sa == 0L || n == 0) 0 else
                    (((sa / n).toInt().coerceIn(0, 255)) shl 24) or ((sr / sa).toInt() shl 16) or ((sg / sa).toInt() shl 8) or (sb / sa).toInt()
            }
            preview.setPixels(rowOut, 0, pr - pl, pl, py, pr - pl, 1)
        }
    }

    // --- Helpers -----------------------------------------------------------------------------

    private fun dist(x: Int, y: Int, cx: Float, cy: Float): Float {
        val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
        return sqrt(dx * dx + dy * dy)
    }

    private fun pack(a: Int, r: Float, g: Float, b: Float): Int =
        (a shl 24) or ((r * 255f).roundToInt().coerceIn(0, 255) shl 16) or
            ((g * 255f).roundToInt().coerceIn(0, 255) shl 8) or (b * 255f).roundToInt().coerceIn(0, 255)

    companion object {
        /** [src] painted over [dst] at strength [k] (src alpha times k), "normal" blending. */
        fun over(dst: Int, src: Int, k: Float): Int {
            val sa = (src ushr 24) / 255f * (if (k < 0f) 0f else if (k > 1f) 1f else k)
            if (sa <= 0f) return dst
            val da = (dst ushr 24) / 255f
            val dw = da * (1f - sa)
            val oa = sa + dw
            if (oa <= 0f) return 0
            val inv = 1f / oa
            val r = ((((src shr 16) and 255) * sa + ((dst shr 16) and 255) * dw) * inv + 0.5f).toInt()
            val g = ((((src shr 8) and 255) * sa + ((dst shr 8) and 255) * dw) * inv + 0.5f).toInt()
            val b = (((src and 255) * sa + (dst and 255) * dw) * inv + 0.5f).toInt()
            val a = (oa * 255f + 0.5f).toInt()
            return (min(255, a) shl 24) or (min(255, r) shl 16) or (min(255, g) shl 8) or min(255, b)
        }

        /** Colour of a gradient from ([sx], [sy]) to ([ex], [ey]) at pixel (x, y). */
        fun gradientAt(b: RetouchBrush, sx: Float, sy: Float, ex: Float, ey: Float, x: Int, y: Int): Int {
            val vx = ex - sx; val vy = ey - sy
            val len2 = max(1e-3f, vx * vx + vy * vy)
            val px = x + 0.5f - sx; val py = y + 0.5f - sy
            val t = when (b.gradientType) {
                GradientType.LINEAR -> (px * vx + py * vy) / len2
                GradientType.RADIAL -> sqrt((px * px + py * py) / len2)
                GradientType.REFLECTED -> abs((px * vx + py * vy) / len2)
            }.coerceIn(0f, 1f)
            val c1 = b.fillColor or (0xFF shl 24)
            val c2 = if (b.gradientToTransparent) c1 and 0xFFFFFF else b.fillColor2 or (0xFF shl 24)
            return mix(c1, c2, t).let { if (b.gradientToTransparent && t >= 1f) c1 and 0xFFFFFF else it }
        }

        private fun h2(x: Int, y: Int): Float {
            var h = x * 374761393 + y * 668265263
            h = (h xor (h ushr 13)) * 1274126177
            return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777215f
        }

        private fun valueNoise(x: Float, y: Float): Float {
            val xi = kotlin.math.floor(x).toInt(); val yi = kotlin.math.floor(y).toInt()
            val tx = x - xi; val ty = y - yi
            val sx = tx * tx * (3 - 2 * tx); val sy = ty * ty * (3 - 2 * ty)
            val a = h2(xi, yi); val b = h2(xi + 1, yi); val c = h2(xi, yi + 1); val d = h2(xi + 1, yi + 1)
            return (a + (b - a) * sx) * (1 - sy) + (c + (d - c) * sx) * sy
        }

        /**
         * Colour of pattern [b].pattern at pixel (x, y) of an image whose shorter side is
         * [size] (the pattern scales with the image, so the preview matches the result).
         */
        fun patternAt(b: RetouchBrush, size: Int, x: Int, y: Int): Int {
            val c1 = b.fillColor or (0xFF shl 24); val c2 = b.fillColor2 or (0xFF shl 24)
            val cell = max(2f, size / 16f * (b.patternScale / 100f).coerceIn(0.1f, 4f))
            val u = x / cell; val v = y / cell
            fun frac(f: Float) = f - kotlin.math.floor(f)
            val t: Float = when (b.pattern) {
                FillPattern.CHECKER -> if (((kotlin.math.floor(u).toInt() + kotlin.math.floor(v).toInt()) and 1) == 0) 0f else 1f
                FillPattern.STRIPES -> if (frac((u + v) * 0.5f) < 0.5f) 0f else 1f
                FillPattern.DOTS -> {
                    val dx = frac(u) - 0.5f; val dy = frac(v) - 0.5f
                    val d = sqrt(dx * dx + dy * dy)
                    ((d - 0.28f) / 0.04f).coerceIn(0f, 1f)
                }
                FillPattern.GRID -> {
                    val lw = 0.08f
                    if (frac(u) < lw || frac(v) < lw) 0f else 1f
                }
                FillPattern.BRICKS -> {
                    val bv = v * 2f
                    val row = kotlin.math.floor(bv).toInt()
                    val bu = u + if (row and 1 == 1) 0.5f else 0f
                    if (frac(bv) < 0.1f || frac(bu) < 0.05f) 0f else 1f
                }
                FillPattern.WAVES -> {
                    val w = sin((u * 1.5f + sin(v * 0.9f) * 0.8f) * 3.14159f)
                    (w * 0.5f + 0.5f)
                }
                FillPattern.NOISE -> {
                    val n = valueNoise(u * 4f, v * 4f) * 0.6f + valueNoise(u * 9f, v * 9f) * 0.4f
                    ((n - 0.2f) / 0.6f).coerceIn(0f, 1f)
                }
                FillPattern.MARBLE -> {
                    var n = 0f; var amp = 0.5f; var fq = 1f
                    repeat(4) { n += valueNoise(u * fq, v * fq) * amp; amp *= 0.5f; fq *= 2f }
                    val w = sin((u + v + n * 5f) * 3.14159f * 0.6f)
                    (w * 0.5f + 0.5f).let { it * it }
                }
            }
            return mix(c1, c2, t)
        }

        /** Premultiplied mix of two ARGB colours: [t] = 0 gives [a], 1 gives [b]. */
        fun mix(a: Int, b: Int, t: Float): Int {
            if (t <= 0f) return a
            if (t >= 1f) return b
            val aa = (a ushr 24).toFloat(); val ba = (b ushr 24).toFloat()
            val oa = aa + (ba - aa) * t
            if (oa < 0.5f) return 0
            val wa = aa * (1f - t); val wb = ba * t
            val inv = 1f / oa
            val r = ((((a shr 16) and 255) * wa + ((b shr 16) and 255) * wb) * inv + 0.5f).toInt()
            val g = ((((a shr 8) and 255) * wa + ((b shr 8) and 255) * wb) * inv + 0.5f).toInt()
            val bl = (((a and 255) * wa + (b and 255) * wb) * inv + 0.5f).toInt()
            val o = (oa + 0.5f).toInt()
            return (min(255, o) shl 24) or (min(255, r) shl 16) or (min(255, g) shl 8) or min(255, bl)
        }
    }
}
