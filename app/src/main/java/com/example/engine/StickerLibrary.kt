package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.example.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class StickerCategory(val labelRes: Int) {
    EMOJI(R.string.sticker_cat_emoji),
    SHAPES(R.string.sticker_cat_shapes),
    ARROWS(R.string.sticker_cat_arrows),
    BADGES(R.string.sticker_cat_badges),
    SPEECH(R.string.sticker_cat_speech),
    DECOR(R.string.sticker_cat_decor)
}

/**
 * One sticker. Vector stickers are drawn in code on a [UNIT] x [UNIT] box, so they stay sharp at
 * any size and take the chosen colour ([tintable]); emoji stickers use the system colour emoji font.
 */
class StickerDef(
    val id: String,
    val category: StickerCategory,
    val emoji: String? = null,
    val tintable: Boolean = emoji == null,
    /** Default colour of a vector sticker. */
    val defaultColor: Int = 0xFFFFC107.toInt(),
    val draw: ((Canvas, Paint, Int) -> Unit)? = null
)

/** Appearance chosen in the stickers panel. */
data class StickerStyle(
    val color: Int? = null,
    /** White cut-out border, as on printed stickers. */
    val outline: Boolean = false,
    val outlineColor: Int = Color.WHITE,
    val shadow: Boolean = false
)

object StickerLibrary {
    const val UNIT = 1000f

    private val emojis = listOf(
        // faces
        "😀", "😂", "🤣", "😊", "😍", "🥰", "😘", "😎", "🤩", "🥳", "😇", "🤔", "😴", "😱", "😭", "😡", "🤯", "🥺", "😜", "🤗",
        "🙄", "😏", "😬", "🤫", "🤭", "😷", "🤠", "🤓", "👻", "💀", "🤖", "👽",
        // hearts and symbols
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💖", "💔", "💯", "✨", "⭐", "🌟", "🔥", "💥", "💫", "⚡", "✅", "❌",
        "❓", "❗", "💬", "💭", "🎵", "🎶", "💡", "📍", "🔔", "🏆",
        // hands
        "👍", "👎", "👏", "🙌", "🙏", "👌", "✌️", "🤞", "🤟", "👋", "💪", "👉", "👈", "👆", "👇", "✍️",
        // celebration and objects
        "🎉", "🎊", "🎁", "🎈", "🎂", "🍰", "🥂", "🍾", "🎀", "👑", "💍", "💎", "📸", "🎬", "🎮", "⚽", "🏀", "🚀", "✈️", "🚗",
        "⏰", "📅", "📌", "🛒", "💰", "🎯",
        // nature and food
        "🌸", "🌹", "🌻", "🌷", "🍀", "🌿", "🌴", "🌵", "🍁", "🌈", "☀️", "🌙", "⛅", "❄️", "🌊", "🐱", "🐶", "🦋", "🐝", "🦁",
        "🍕", "🍔", "🍟", "🍩", "🍦", "🍓", "🍉", "☕", "🧋", "🍿",
        // places / flags-like symbols
        "🕌", "🏠", "🏖️", "⛰️", "🗽", "🌍"
    )

    val all: List<StickerDef> by lazy { buildList {
        emojis.forEachIndexed { i, e -> add(StickerDef("emoji_$i", StickerCategory.EMOJI, emoji = e)) }
        addAll(shapes()); addAll(arrows()); addAll(badges()); addAll(speech()); addAll(decor())
    } }

    fun byCategory(c: StickerCategory): List<StickerDef> = all.filter { it.category == c }

    fun find(id: String): StickerDef? = all.firstOrNull { it.id == id }

    // --- Rendering ---------------------------------------------------------------------------

    /**
     * Renders [def] at [sizePx] (square) with [style]. Transparent around the sticker; the bitmap
     * is trimmed to the drawn pixels plus a small margin, so the layer box hugs the sticker.
     */
    fun render(context: Context, def: StickerDef, sizePx: Int, style: StickerStyle = StickerStyle()): Bitmap {
        prepare(context)
        val size = sizePx.coerceIn(32, 2048)
        // Room for the border and shadow around the artwork.
        val pad = (size * 0.08f).toInt()
        val art = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(art)
        val inner = size - 2 * pad
        if (def.emoji != null) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = inner * 0.82f
                textAlign = Paint.Align.CENTER
            }
            val fm = tp.fontMetrics
            val y = size / 2f - (fm.ascent + fm.descent) / 2f
            c.drawText(def.emoji, size / 2f, y, tp)
        } else {
            c.save()
            c.translate(pad.toFloat(), pad.toFloat())
            c.scale(inner / UNIT, inner / UNIT)
            val color = if (def.tintable) (style.color ?: def.defaultColor) else def.defaultColor
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style_fill() }
            def.draw?.invoke(c, p, color)
            c.restore()
        }
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val rc = Canvas(result)
        if (style.shadow) {
            val sh = art.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(size * 0.025f, BlurMaskFilter.Blur.NORMAL) }, null)
            rc.drawBitmap(sh, size * 0.012f, size * 0.03f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73000000 })
            sh.recycle()
        }
        if (style.outline) {
            // A white silhouette slightly larger than the artwork: the artwork stamped around a circle.
            val r = size * 0.022f
            val sil = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(style.outlineColor, PorterDuff.Mode.SRC_IN)
            }
            val steps = 24
            for (k in 0 until steps) {
                val a = 2.0 * PI * k / steps
                rc.drawBitmap(art, (cos(a) * r).toFloat(), (sin(a) * r).toFloat(), sil)
            }
            rc.drawBitmap(art, 0f, 0f, sil)
        }
        rc.drawBitmap(art, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        art.recycle()
        return trim(result, (size * 0.01f).toInt())
    }

    private fun Paint.style_fill() { style = Paint.Style.FILL }

    /** Crops [src] to its non-transparent pixels plus [margin]. Returns [src] when nothing to crop. */
    private fun trim(src: Bitmap, margin: Int): Bitmap {
        val w = src.width; val h = src.height
        val row = IntArray(w)
        var top = -1; var bottom = -1; var left = w; var right = -1
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            var any = false
            for (x in 0 until w) if ((row[x] ushr 24) > 8) { any = true; if (x < left) left = x; if (x > right) right = x }
            if (any) { if (top < 0) top = y; bottom = y }
        }
        if (top < 0) return src
        val l = max(0, left - margin); val t = max(0, top - margin)
        val r = min(w - 1, right + margin); val b = min(h - 1, bottom + margin)
        if (l == 0 && t == 0 && r == w - 1 && b == h - 1) return src
        val out = Bitmap.createBitmap(src, l, t, r - l + 1, b - t + 1)
        if (out !== src) src.recycle()
        return out
    }

    // --- Vector helpers (all coordinates on a 1000 x 1000 box) -------------------------------

    private fun star(points: Int, outer: Float, inner: Float, cx: Float = 500f, cy: Float = 500f, rot: Double = -PI / 2): Path = Path().apply {
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) outer else inner
            val a = rot + PI * i / points
            val x = cx + (r * cos(a)).toFloat(); val y = cy + (r * sin(a)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private fun polygon(sides: Int, r: Float, rot: Double = -PI / 2): Path = Path().apply {
        for (i in 0 until sides) {
            val a = rot + 2 * PI * i / sides
            val x = 500f + (r * cos(a)).toFloat(); val y = 500f + (r * sin(a)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private fun heart(): Path = Path().apply {
        moveTo(500f, 880f)
        cubicTo(120f, 640f, 40f, 420f, 120f, 260f)
        cubicTo(200f, 100f, 420f, 100f, 500f, 280f)
        cubicTo(580f, 100f, 800f, 100f, 880f, 260f)
        cubicTo(960f, 420f, 880f, 640f, 500f, 880f)
        close()
    }

    private fun cloud(): Path = Path().apply {
        addCircle(330f, 560f, 170f, Path.Direction.CW)
        addCircle(520f, 450f, 220f, Path.Direction.CW)
        addCircle(720f, 570f, 160f, Path.Direction.CW)
        addRoundRect(RectF(170f, 560f, 870f, 730f), 90f, 90f, Path.Direction.CW)
    }

    private fun fill(c: Canvas, p: Paint, path: Path) { p.style = Paint.Style.FILL; c.drawPath(path, p) }

    private fun stroke(c: Canvas, p: Paint, path: Path, w: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = w; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
    }

    /** Darker / lighter version of [color] for details. */
    private fun shade(color: Int, f: Float): Int {
        val r = (Color.red(color) * f).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * f).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * f).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), r, g, b)
    }

    private fun bestTextColor(bg: Int): Int {
        val l = 0.299f * Color.red(bg) + 0.587f * Color.green(bg) + 0.114f * Color.blue(bg)
        return if (l > 160f) 0xFF1A1A1A.toInt() else Color.WHITE
    }

    private var boldFace: Typeface? = null
    /** Bold face for badge words (Cairo covers Arabic and Latin); set by [prepare]. */
    fun prepare(context: Context) {
        if (boldFace == null) {
            boldFace = try {
                Typeface.create(ResourcesCompat.getFont(context, R.font.cairo), Typeface.BOLD)
            } catch (e: Exception) {
                Typeface.DEFAULT_BOLD
            }
        }
    }

    private fun drawWord(c: Canvas, word: String, cx: Float, cy: Float, maxW: Float, size: Float, color: Int) {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textAlign = Paint.Align.CENTER; typeface = boldFace ?: Typeface.DEFAULT_BOLD; textSize = size
        }
        val w = tp.measureText(word)
        if (w > maxW) tp.textSize = size * maxW / w
        val fm = tp.fontMetrics
        c.drawText(word, cx, cy - (fm.ascent + fm.descent) / 2f, tp)
    }

    // --- Categories --------------------------------------------------------------------------

    private fun shapes(): List<StickerDef> {
        val cat = StickerCategory.SHAPES
        return listOf(
            StickerDef("shape_circle", cat, defaultColor = 0xFFEF4444.toInt()) { c, p, _ -> c.drawCircle(500f, 500f, 450f, p) },
            StickerDef("shape_ring", cat, defaultColor = 0xFF3B82F6.toInt()) { c, p, _ -> stroke(c, p, Path().apply { addCircle(500f, 500f, 400f, Path.Direction.CW) }, 90f) },
            StickerDef("shape_square", cat, defaultColor = 0xFF22C55E.toInt()) { c, p, _ -> c.drawRoundRect(RectF(70f, 70f, 930f, 930f), 120f, 120f, p) },
            StickerDef("shape_star", cat, defaultColor = 0xFFFACC15.toInt()) { c, p, _ -> fill(c, p, star(5, 480f, 200f)) },
            StickerDef("shape_heart", cat, defaultColor = 0xFFEC4899.toInt()) { c, p, _ -> fill(c, p, heart()) },
            StickerDef("shape_triangle", cat, defaultColor = 0xFFF97316.toInt()) { c, p, _ -> fill(c, p, polygon(3, 480f)) },
            StickerDef("shape_hexagon", cat, defaultColor = 0xFF8B5CF6.toInt()) { c, p, _ -> fill(c, p, polygon(6, 470f)) },
            StickerDef("shape_diamond", cat, defaultColor = 0xFF06B6D4.toInt()) { c, p, _ -> fill(c, p, polygon(4, 470f)) },
            StickerDef("shape_burst", cat, defaultColor = 0xFFF59E0B.toInt()) { c, p, _ -> fill(c, p, star(16, 480f, 380f)) },
            StickerDef("shape_cloud", cat, defaultColor = 0xFF93C5FD.toInt()) { c, p, _ -> fill(c, p, cloud()) },
            StickerDef("shape_drop", cat, defaultColor = 0xFF0EA5E9.toInt()) { c, p, _ ->
                fill(c, p, Path().apply {
                    moveTo(500f, 60f); cubicTo(560f, 260f, 820f, 460f, 820f, 640f)
                    cubicTo(820f, 820f, 680f, 940f, 500f, 940f); cubicTo(320f, 940f, 180f, 820f, 180f, 640f)
                    cubicTo(180f, 460f, 440f, 260f, 500f, 60f); close()
                })
            },
            StickerDef("shape_moon", cat, defaultColor = 0xFFFDE047.toInt()) { c, p, _ ->
                val moon = Path().apply { addCircle(470f, 500f, 400f, Path.Direction.CW) }
                val cut = Path().apply { addCircle(650f, 400f, 340f, Path.Direction.CW) }
                moon.op(cut, Path.Op.DIFFERENCE); fill(c, p, moon)
            },
            StickerDef("shape_bolt", cat, defaultColor = 0xFFFACC15.toInt()) { c, p, _ ->
                fill(c, p, Path().apply { moveTo(580f, 40f); lineTo(200f, 560f); lineTo(470f, 560f); lineTo(400f, 960f); lineTo(800f, 400f); lineTo(520f, 400f); close() })
            },
            StickerDef("shape_check", cat, defaultColor = 0xFF22C55E.toInt()) { c, p, _ ->
                stroke(c, p, Path().apply { moveTo(150f, 520f); lineTo(400f, 770f); lineTo(860f, 240f) }, 150f)
            },
            StickerDef("shape_cross", cat, defaultColor = 0xFFEF4444.toInt()) { c, p, _ ->
                stroke(c, p, Path().apply { moveTo(200f, 200f); lineTo(800f, 800f); moveTo(800f, 200f); lineTo(200f, 800f) }, 150f)
            },
            StickerDef("shape_plus", cat, defaultColor = 0xFF3B82F6.toInt()) { c, p, _ ->
                stroke(c, p, Path().apply { moveTo(500f, 140f); lineTo(500f, 860f); moveTo(140f, 500f); lineTo(860f, 500f) }, 170f)
            },
            StickerDef("shape_octagon", cat, defaultColor = 0xFFDC2626.toInt()) { c, p, _ -> fill(c, p, polygon(8, 470f, -PI / 8)) },
            StickerDef("shape_pill", cat, defaultColor = 0xFF14B8A6.toInt()) { c, p, _ -> c.drawRoundRect(RectF(40f, 300f, 960f, 700f), 200f, 200f, p) }
        )
    }

    private fun arrowPath(): Path = Path().apply {
        moveTo(80f, 420f); lineTo(600f, 420f); lineTo(600f, 230f); lineTo(930f, 500f)
        lineTo(600f, 770f); lineTo(600f, 580f); lineTo(80f, 580f); close()
    }

    private fun arrows(): List<StickerDef> {
        val cat = StickerCategory.ARROWS
        fun rotated(id: String, deg: Float) = StickerDef(id, cat, defaultColor = 0xFFEF4444.toInt()) { c, p, _ ->
            c.save(); c.rotate(deg, 500f, 500f); fill(c, p, arrowPath()); c.restore()
        }
        return listOf(
            rotated("arrow_right", 0f), rotated("arrow_down", 90f), rotated("arrow_left", 180f), rotated("arrow_up", 270f),
            rotated("arrow_diag", -45f),
            StickerDef("arrow_double", cat, defaultColor = 0xFF3B82F6.toInt()) { c, p, _ ->
                fill(c, p, Path().apply {
                    moveTo(60f, 500f); lineTo(300f, 270f); lineTo(300f, 420f); lineTo(700f, 420f); lineTo(700f, 270f)
                    lineTo(940f, 500f); lineTo(700f, 730f); lineTo(700f, 580f); lineTo(300f, 580f); lineTo(300f, 730f); close()
                })
            },
            StickerDef("arrow_curved", cat, defaultColor = 0xFFF97316.toInt()) { c, p, _ ->
                stroke(c, p, Path().apply { moveTo(150f, 780f); cubicTo(150f, 380f, 420f, 220f, 760f, 260f) }, 90f)
                fill(c, p, Path().apply { moveTo(700f, 110f); lineTo(930f, 280f); lineTo(690f, 420f); close() })
            },
            StickerDef("arrow_hand", cat, defaultColor = 0xFF111827.toInt()) { c, p, _ ->
                stroke(c, p, Path().apply { moveTo(90f, 640f); cubicTo(260f, 360f, 520f, 760f, 830f, 360f) }, 45f)
                stroke(c, p, Path().apply { moveTo(640f, 360f); lineTo(840f, 350f); lineTo(860f, 560f) }, 45f)
            },
            StickerDef("arrow_circular", cat, defaultColor = 0xFF22C55E.toInt()) { c, p, _ ->
                stroke(c, p, Path().apply { addArc(RectF(170f, 170f, 830f, 830f), -60f, 290f) }, 90f)
                fill(c, p, Path().apply { moveTo(600f, 110f); lineTo(860f, 160f); lineTo(700f, 380f); close() })
            },
            StickerDef("arrow_chevron", cat, defaultColor = 0xFF8B5CF6.toInt()) { c, p, _ ->
                stroke(c, p, Path().apply { moveTo(250f, 150f); lineTo(600f, 500f); lineTo(250f, 850f) }, 140f)
            },
            StickerDef("arrow_chevrons", cat, defaultColor = 0xFFEC4899.toInt()) { c, p, _ ->
                stroke(c, p, Path().apply { moveTo(130f, 200f); lineTo(430f, 500f); lineTo(130f, 800f); moveTo(500f, 200f); lineTo(800f, 500f); lineTo(500f, 800f) }, 110f)
            },
            StickerDef("arrow_pointer", cat, defaultColor = 0xFFFFFFFF.toInt()) { c, p, color ->
                val path = Path().apply { moveTo(250f, 80f); lineTo(250f, 860f); lineTo(430f, 690f); lineTo(560f, 960f); lineTo(680f, 900f); lineTo(550f, 640f); lineTo(790f, 630f); close() }
                fill(c, p, path)
                p.color = bestTextColor(color); stroke(c, p, path, 30f); p.color = color
            }
        )
    }

    private fun badges(): List<StickerDef> {
        val cat = StickerCategory.BADGES
        fun pillBadge(id: String, word: String, color: Int) = StickerDef(id, cat, defaultColor = color) { c, p, col ->
            c.drawRoundRect(RectF(30f, 290f, 970f, 710f), 210f, 210f, p)
            drawWord(c, word, 500f, 500f, 760f, 300f, bestTextColor(col))
        }
        fun sealBadge(id: String, word: String, color: Int) = StickerDef(id, cat, defaultColor = color) { c, p, col ->
            fill(c, p, star(24, 490f, 440f))
            p.color = shade(col, 0.8f); stroke(c, p, Path().apply { addCircle(500f, 500f, 360f, Path.Direction.CW) }, 18f); p.color = col
            drawWord(c, word, 500f, 500f, 560f, 230f, bestTextColor(col))
        }
        fun ribbon(id: String, word: String, color: Int) = StickerDef(id, cat, defaultColor = color) { c, p, col ->
            p.color = shade(col, 0.7f)
            fill(c, p, Path().apply { moveTo(20f, 380f); lineTo(180f, 380f); lineTo(180f, 700f); lineTo(20f, 700f); lineTo(90f, 540f); close() })
            fill(c, p, Path().apply { moveTo(980f, 380f); lineTo(820f, 380f); lineTo(820f, 700f); lineTo(980f, 700f); lineTo(910f, 540f); close() })
            p.color = col
            c.drawRect(RectF(110f, 300f, 890f, 640f), p)
            drawWord(c, word, 500f, 470f, 700f, 220f, bestTextColor(col))
        }
        fun tag(id: String, word: String, color: Int) = StickerDef(id, cat, defaultColor = color) { c, p, col ->
            val path = Path().apply { moveTo(260f, 250f); lineTo(940f, 250f); lineTo(940f, 750f); lineTo(260f, 750f); lineTo(60f, 500f); close() }
            val hole = Path().apply { addCircle(250f, 500f, 50f, Path.Direction.CW) }
            path.op(hole, Path.Op.DIFFERENCE)
            fill(c, p, path)
            drawWord(c, word, 610f, 500f, 560f, 240f, bestTextColor(col))
        }
        return listOf(
            pillBadge("badge_new", "NEW", 0xFFEF4444.toInt()),
            pillBadge("badge_sale", "SALE", 0xFFF97316.toInt()),
            pillBadge("badge_hot", "HOT", 0xFFDC2626.toInt()),
            pillBadge("badge_free", "FREE", 0xFF22C55E.toInt()),
            pillBadge("badge_ar_new", "جديد", 0xFF8B5CF6.toInt()),
            pillBadge("badge_ar_offer", "عرض", 0xFFEC4899.toInt()),
            sealBadge("seal_50", "-50%", 0xFFEF4444.toInt()),
            sealBadge("seal_best", "BEST", 0xFFF59E0B.toInt()),
            sealBadge("seal_ar_discount", "خصم", 0xFF0EA5E9.toInt()),
            sealBadge("seal_top", "TOP", 0xFF16A34A.toInt()),
            ribbon("ribbon_wow", "WOW!", 0xFFEC4899.toInt()),
            ribbon("ribbon_ar_mabrook", "مبروك", 0xFFF59E0B.toInt()),
            tag("tag_sale", "SALE", 0xFFFACC15.toInt()),
            tag("tag_ar_sale", "تخفيض", 0xFF06B6D4.toInt())
        )
    }

    private fun speech(): List<StickerDef> {
        val cat = StickerCategory.SPEECH
        fun outlined(c: Canvas, p: Paint, color: Int, path: Path) {
            fill(c, p, path)
            p.color = 0xFF111827.toInt(); stroke(c, p, path, 26f); p.color = color
        }
        return listOf(
            StickerDef("speech_round", cat, defaultColor = Color.WHITE) { c, p, col ->
                val path = Path().apply { addOval(RectF(60f, 110f, 940f, 720f), Path.Direction.CW) }
                path.op(Path().apply { moveTo(250f, 600f); lineTo(170f, 920f); lineTo(450f, 680f); close() }, Path.Op.UNION)
                outlined(c, p, col, path)
            },
            StickerDef("speech_rect", cat, defaultColor = Color.WHITE) { c, p, col ->
                val path = Path().apply { addRoundRect(RectF(60f, 120f, 940f, 700f), 90f, 90f, Path.Direction.CW) }
                path.op(Path().apply { moveTo(640f, 640f); lineTo(800f, 920f); lineTo(800f, 640f); close() }, Path.Op.UNION)
                outlined(c, p, col, path)
            },
            StickerDef("speech_thought", cat, defaultColor = Color.WHITE) { c, p, col ->
                val path = Path().apply {
                    addCircle(300f, 380f, 190f, Path.Direction.CW); addCircle(500f, 300f, 220f, Path.Direction.CW)
                    addCircle(700f, 390f, 190f, Path.Direction.CW); addCircle(560f, 540f, 190f, Path.Direction.CW)
                    addCircle(340f, 540f, 170f, Path.Direction.CW)
                }
                path.op(Path(), Path.Op.UNION)
                outlined(c, p, col, path)
                outlined(c, p, col, Path().apply { addCircle(230f, 790f, 60f, Path.Direction.CW) })
                outlined(c, p, col, Path().apply { addCircle(140f, 910f, 36f, Path.Direction.CW) })
            },
            StickerDef("speech_shout", cat, defaultColor = 0xFFFDE047.toInt()) { c, p, col ->
                outlined(c, p, col, star(14, 480f, 330f))
            },
            StickerDef("speech_wide", cat, defaultColor = Color.WHITE) { c, p, col ->
                val path = Path().apply { addRoundRect(RectF(30f, 280f, 970f, 680f), 200f, 200f, Path.Direction.CW) }
                path.op(Path().apply { moveTo(200f, 600f); lineTo(120f, 820f); lineTo(380f, 660f); close() }, Path.Op.UNION)
                outlined(c, p, col, path)
            },
            StickerDef("speech_dots", cat, defaultColor = 0xFF3B82F6.toInt()) { c, p, col ->
                val path = Path().apply { addRoundRect(RectF(60f, 200f, 940f, 700f), 250f, 250f, Path.Direction.CW) }
                path.op(Path().apply { moveTo(220f, 620f); lineTo(140f, 880f); lineTo(420f, 680f); close() }, Path.Op.UNION)
                fill(c, p, path)
                p.color = bestTextColor(col)
                c.drawCircle(330f, 450f, 60f, p); c.drawCircle(500f, 450f, 60f, p); c.drawCircle(670f, 450f, 60f, p)
                p.color = col
            }
        )
    }

    private fun decor(): List<StickerDef> {
        val cat = StickerCategory.DECOR
        return listOf(
            StickerDef("decor_sparkle", cat, defaultColor = 0xFFFDE047.toInt()) { c, p, _ ->
                fill(c, p, Path().apply {
                    moveTo(500f, 40f); quadTo(540f, 460f, 960f, 500f); quadTo(540f, 540f, 500f, 960f)
                    quadTo(460f, 540f, 40f, 500f); quadTo(460f, 460f, 500f, 40f); close()
                })
            },
            StickerDef("decor_sparkles", cat, defaultColor = 0xFFFDE047.toInt()) { c, p, _ ->
                fun sp(cx: Float, cy: Float, r: Float) = Path().apply {
                    moveTo(cx, cy - r); quadTo(cx + r * 0.1f, cy - r * 0.1f, cx + r, cy); quadTo(cx + r * 0.1f, cy + r * 0.1f, cx, cy + r)
                    quadTo(cx - r * 0.1f, cy + r * 0.1f, cx - r, cy); quadTo(cx - r * 0.1f, cy - r * 0.1f, cx, cy - r); close()
                }
                fill(c, p, sp(420f, 560f, 380f)); fill(c, p, sp(800f, 220f, 180f)); fill(c, p, sp(820f, 760f, 120f))
            },
            StickerDef("decor_crown", cat, defaultColor = 0xFFF59E0B.toInt()) { c, p, col ->
                fill(c, p, Path().apply { moveTo(100f, 760f); lineTo(60f, 260f); lineTo(300f, 500f); lineTo(500f, 160f); lineTo(700f, 500f); lineTo(940f, 260f); lineTo(900f, 760f); close() })
                c.drawRoundRect(RectF(100f, 780f, 900f, 880f), 30f, 30f, p)
                p.color = shade(col, 0.75f)
                c.drawCircle(60f, 250f, 55f, p); c.drawCircle(500f, 150f, 60f, p); c.drawCircle(940f, 250f, 55f, p)
                p.color = col
            },
            StickerDef("decor_flower", cat, defaultColor = 0xFFF472B6.toInt()) { c, p, col ->
                for (k in 0 until 6) {
                    val a = 2 * PI * k / 6
                    c.drawCircle(500f + (230 * cos(a)).toFloat(), 500f + (230 * sin(a)).toFloat(), 200f, p)
                }
                p.color = 0xFFFDE047.toInt(); c.drawCircle(500f, 500f, 170f, p); p.color = col
            },
            StickerDef("decor_sun", cat, defaultColor = 0xFFFBBF24.toInt()) { c, p, _ ->
                c.drawCircle(500f, 500f, 250f, p)
                for (k in 0 until 12) {
                    val a = 2 * PI * k / 12
                    stroke(c, p, Path().apply {
                        moveTo(500f + (320 * cos(a)).toFloat(), 500f + (320 * sin(a)).toFloat())
                        lineTo(500f + (460 * cos(a)).toFloat(), 500f + (460 * sin(a)).toFloat())
                    }, 60f)
                }
            },
            StickerDef("decor_leaf", cat, defaultColor = 0xFF22C55E.toInt()) { c, p, col ->
                fill(c, p, Path().apply { moveTo(120f, 880f); cubicTo(80f, 380f, 420f, 80f, 900f, 100f); cubicTo(920f, 580f, 600f, 920f, 120f, 880f); close() })
                p.color = shade(col, 0.7f); stroke(c, p, Path().apply { moveTo(140f, 860f); quadTo(460f, 520f, 860f, 140f) }, 30f); p.color = col
            },
            StickerDef("decor_note", cat, defaultColor = 0xFF8B5CF6.toInt()) { c, p, _ ->
                c.drawOval(RectF(120f, 640f, 420f, 880f), p); c.drawOval(RectF(560f, 560f, 860f, 800f), p)
                c.drawRect(RectF(360f, 140f, 420f, 760f), p); c.drawRect(RectF(800f, 60f, 860f, 680f), p)
                fill(c, p, Path().apply { moveTo(360f, 140f); lineTo(860f, 60f); lineTo(860f, 200f); lineTo(360f, 280f); close() })
            },
            StickerDef("decor_snowflake", cat, defaultColor = 0xFF7DD3FC.toInt()) { c, p, _ ->
                for (k in 0 until 6) {
                    c.save(); c.rotate(60f * k, 500f, 500f)
                    stroke(c, p, Path().apply { moveTo(500f, 500f); lineTo(500f, 70f); moveTo(500f, 220f); lineTo(400f, 130f); moveTo(500f, 220f); lineTo(600f, 130f) }, 50f)
                    c.restore()
                }
            },
            StickerDef("decor_swoosh", cat, defaultColor = 0xFFEC4899.toInt()) { c, p, _ ->
                fill(c, p, Path().apply { moveTo(40f, 620f); cubicTo(300f, 420f, 700f, 380f, 960f, 460f); cubicTo(700f, 470f, 360f, 520f, 60f, 700f); close() })
            },
            StickerDef("decor_frame", cat, defaultColor = 0xFFFFFFFF.toInt()) { c, p, _ ->
                stroke(c, p, Path().apply { addRoundRect(RectF(60f, 60f, 940f, 940f), 60f, 60f, Path.Direction.CW) }, 40f)
                stroke(c, p, Path().apply { addRoundRect(RectF(130f, 130f, 870f, 870f), 30f, 30f, Path.Direction.CW) }, 12f)
            },
            StickerDef("decor_hearts", cat, defaultColor = 0xFFEF4444.toInt()) { c, p, _ ->
                fun h(s: Float, dx: Float, dy: Float) { c.save(); c.translate(dx, dy); c.scale(s, s); fill(c, p, heart()); c.restore() }
                h(0.62f, 40f, 300f); h(0.42f, 560f, 60f); h(0.32f, 640f, 600f)
            },
            StickerDef("decor_confetti", cat, defaultColor = 0xFFF97316.toInt()) { c, p, col ->
                val colors = intArrayOf(col, 0xFF3B82F6.toInt(), 0xFF22C55E.toInt(), 0xFFEC4899.toInt(), 0xFFFACC15.toInt(), 0xFF8B5CF6.toInt())
                val rnd = java.util.Random(7)
                for (k in 0 until 26) {
                    p.color = colors[k % colors.size]
                    val x = 80f + rnd.nextFloat() * 840f; val y = 80f + rnd.nextFloat() * 840f
                    c.save(); c.rotate(rnd.nextFloat() * 180f, x, y)
                    if (k % 3 == 0) c.drawCircle(x, y, 32f, p) else c.drawRoundRect(RectF(x - 45f, y - 18f, x + 45f, y + 18f), 10f, 10f, p)
                    c.restore()
                }
                p.color = col
            }
        )
    }

    /** Renders a small preview (thumbnail) of [def]. */
    fun preview(context: Context, def: StickerDef, sizePx: Int, color: Int?): Bitmap =
        render(context, def, sizePx, StickerStyle(color = color))
}
