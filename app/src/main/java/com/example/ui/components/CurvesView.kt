package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.ColorAdjustments
import com.example.engine.ImageProcessor
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurfaceVariant
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class CurveChannel(val labelRes: Int, val color: Color) {
    RGB(R.string.curves_channel_rgb, Color.White),
    RED(R.string.curves_channel_red, Color(0xFFEF5350)),
    GREEN(R.string.curves_channel_green, Color(0xFF66BB6A)),
    BLUE(R.string.curves_channel_blue, Color(0xFF42A5F5))
}

/** Tapping the image while one of these is active: add a curve point there, or set a point. */
enum class CurvePickMode { POINT, BLACK, GRAY, WHITE }

/** A colour sampled from the image (as it enters the curves), [seq] makes repeats distinct. */
data class CurveSample(val color: Int, val seq: Long)

private val IDENTITY = listOf(PointF(0f, 0f), PointF(255f, 255f))

private fun isIdentityCurve(points: List<PointF>): Boolean =
    points.size == 2 && points[0].x == 0f && points[0].y == 0f && points[1].x == 255f && points[1].y == 255f

private fun ColorAdjustments.pointsFor(channel: CurveChannel): List<PointF> = when (channel) {
    CurveChannel.RGB -> curvesRgb
    CurveChannel.RED -> curvesRed
    CurveChannel.GREEN -> curvesGreen
    CurveChannel.BLUE -> curvesBlue
}

private fun ColorAdjustments.withPoints(channel: CurveChannel, points: List<PointF>): ColorAdjustments {
    val sorted = points.sortedBy { it.x }
    return when (channel) {
        CurveChannel.RGB -> copy(curvesRgb = sorted)
        CurveChannel.RED -> copy(curvesRed = sorted)
        CurveChannel.GREEN -> copy(curvesGreen = sorted)
        CurveChannel.BLUE -> copy(curvesBlue = sorted)
    }
}

/** Sets an input level -> output level point on a curve, replacing points too close to it. */
private fun setLevelPoint(points: List<PointF>, x: Float, y: Float): List<PointF> {
    val kept = points.filter { abs(it.x - x) > 3f }
    return (kept + PointF(x, y)).sortedBy { it.x }
}

@Composable
fun CurvesView(
    adjustments: ColorAdjustments,
    onAdjustmentsChange: (ColorAdjustments) -> Unit,
    onAdjustmentsCommit: () -> Unit,
    modifier: Modifier = Modifier,
    /** Image the curves are applied to, for the histogram behind the graph. */
    sourceBitmap: Bitmap? = null,
    pickMode: CurvePickMode? = null,
    onPickModeChange: (CurvePickMode?) -> Unit = {},
    /** Latest colour picked on the image (see [pickMode]). */
    pickedSample: CurveSample? = null,
    /** Called once [pickedSample] has been applied, so it is never applied twice. */
    onSampleConsumed: () -> Unit = {}
) {
    var selectedChannel by remember { mutableStateOf(CurveChannel.RGB) }
    var selectedPointIndex by remember { mutableStateOf<Int?>(null) }
    val latestAdjustments by rememberUpdatedState(adjustments)
    val latestChannel by rememberUpdatedState(selectedChannel)
    val latestChange by rememberUpdatedState(onAdjustmentsChange)
    val latestCommit by rememberUpdatedState(onAdjustmentsCommit)

    val currentPoints = adjustments.pointsFor(selectedChannel)

    fun updatePoints(newPoints: List<PointF>) {
        latestChange(latestAdjustments.withPoints(latestChannel, newPoints))
    }

    // Histogram of the image as it enters the curves (other adjustments applied, curves not).
    val histogram by produceState<Array<FloatArray>?>(null, sourceBitmap, adjustments.copy(curvesRgb = IDENTITY, curvesRed = IDENTITY, curvesGreen = IDENTITY, curvesBlue = IDENTITY)) {
        val src = sourceBitmap ?: run { value = null; return@produceState }
        delay(150)
        value = withContext(Dispatchers.Default) {
            try {
                val thumb = ImageProcessor.scaleBitmapToMaxDimension(src, 256)
                val base = adjustments.copy(curvesRgb = IDENTITY, curvesRed = IDENTITY, curvesGreen = IDENTITY, curvesBlue = IDENTITY)
                val img = ImageProcessor.applyAdjustments(thumb, base)
                val px = IntArray(img.width * img.height)
                img.getPixels(px, 0, img.width, 0, 0, img.width, img.height)
                if (img !== thumb && !img.isRecycled) img.recycle()
                if (thumb !== src && !thumb.isRecycled) thumb.recycle()
                val h = Array(4) { FloatArray(256) }
                for (p in px) {
                    if ((p ushr 24) < 128) continue
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    h[0][(r * 54 + g * 183 + b * 19) shr 8]++
                    h[1][r]++; h[2][g]++; h[3][b]++
                }
                // Square-root scale so small populations stay visible; normalise each channel.
                for (c in 0 until 4) {
                    var mx = 0f
                    for (i in 0..255) { h[c][i] = sqrt(h[c][i]); if (h[c][i] > mx) mx = h[c][i] }
                    if (mx > 0f) for (i in 0..255) h[c][i] /= mx
                }
                h
            } catch (e: Throwable) {
                null
            }
        }
    }

    // Colour picked on the image: add a point at its tone, or set black / grey / white points.
    LaunchedEffect(pickedSample?.seq) {
        val sample = pickedSample ?: return@LaunchedEffect
        val mode = pickMode ?: run { onSampleConsumed(); return@LaunchedEffect }
        val r = ((sample.color shr 16) and 0xFF).toFloat()
        val g = ((sample.color shr 8) and 0xFF).toFloat()
        val b = (sample.color and 0xFF).toFloat()
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val adj = latestAdjustments
        when (mode) {
            CurvePickMode.POINT -> {
                val ch = latestChannel
                val x = when (ch) { CurveChannel.RGB -> luma; CurveChannel.RED -> r; CurveChannel.GREEN -> g; CurveChannel.BLUE -> b }
                val pts = adj.pointsFor(ch)
                val y = ImageProcessor.curveValues(pts)[x.roundToInt().coerceIn(0, 255)]
                val updated = setLevelPoint(pts, x, y)
                latestChange(adj.withPoints(ch, updated))
                selectedPointIndex = updated.indexOfFirst { it.x == x }
            }
            CurvePickMode.BLACK, CurvePickMode.WHITE, CurvePickMode.GRAY -> {
                // Per-channel points so the picked colour becomes neutral black / grey / white.
                fun apply(pts: List<PointF>, v: Float): List<PointF> = when (mode) {
                    CurvePickMode.BLACK -> (pts.filter { it.x > v + 3f } + PointF(v.coerceAtMost(250f), 0f)).sortedBy { it.x }
                    CurvePickMode.WHITE -> (pts.filter { it.x < v - 3f } + PointF(v.coerceAtLeast(5f), 255f)).sortedBy { it.x }
                    else -> setLevelPoint(pts, v, luma)
                }
                latestChange(
                    adj.copy(
                        curvesRed = apply(adj.curvesRed, r),
                        curvesGreen = apply(adj.curvesGreen, g),
                        curvesBlue = apply(adj.curvesBlue, b)
                    )
                )
                onPickModeChange(null)
            }
        }
        latestCommit()
        onSampleConsumed()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Channel chips (dot = channel has a curve).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CurveChannel.values().forEach { channel ->
                val isSelected = channel == selectedChannel
                val modified = !isIdentityCurve(adjustments.pointsFor(channel))
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        selectedChannel = channel
                        selectedPointIndex = null
                    },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(channel.labelRes),
                                color = if (isSelected) channel.color else Color.LightGray,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp
                            )
                            if (modified) {
                                Spacer(Modifier.width(4.dp))
                                Box(Modifier.size(6.dp).clip(CircleShape).background(channel.color))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp).testTag("chip_curve_${channel.name.lowercase()}")
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Square graph that adapts to the available width.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, 340.dp)
            Box(
                modifier = Modifier
                    .size(side)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF14161C))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            ) {
                var removalPreview by remember { mutableStateOf<Int?>(null) }
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("curves_canvas")
                        .pointerInput(selectedChannel) {
                            val touchRadius = 24.dp.toPx()
                            val removeDistance = 28.dp.toPx()
                            var lastTapTime = 0L
                            var lastTapIndex = -1
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                fun toX(px: Float) = (px / size.width * 255f).coerceIn(0f, 255f)
                                fun toY(py: Float) = ((1f - py / size.height) * 255f).coerceIn(0f, 255f)
                                fun ptPx(p: PointF) = Offset(p.x / 255f * size.width, (1f - p.y / 255f) * size.height)

                                val working = latestAdjustments.pointsFor(latestChannel).sortedBy { it.x }.toMutableList()
                                var index = working.indices.minByOrNull { hypot(down.position.x - ptPx(working[it]).x, down.position.y - ptPx(working[it]).y) }
                                    ?.takeIf { hypot(down.position.x - ptPx(working[it]).x, down.position.y - ptPx(working[it]).y) <= touchRadius }
                                var added = false
                                if (index == null) {
                                    // New point ON the curve at this input level (no jump), then drag it.
                                    val x = toX(down.position.x)
                                    val near = working.indexOfFirst { abs(it.x - x) < 3f }
                                    if (near >= 0) {
                                        index = near
                                    } else {
                                        val y = ImageProcessor.curveValues(working)[x.roundToInt().coerceIn(0, 255)]
                                        working.add(PointF(x, y))
                                        working.sortBy { it.x }
                                        index = working.indexOfFirst { it.x == x }
                                        added = true
                                        updatePoints(working)
                                    }
                                }
                                val idx = index ?: return@awaitEachGesture
                                selectedPointIndex = idx
                                val slop = viewConfiguration.touchSlop
                                var moved = false
                                var removing = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    if (!moved && (change.position - down.position).getDistance() > slop) moved = true
                                    if (!moved) continue
                                    change.consume()
                                    val pos = change.position
                                    val isEnd = idx == 0 || idx == working.size - 1
                                    // Dragging an inner point well outside the graph removes it.
                                    val outside = pos.x < -removeDistance || pos.x > size.width + removeDistance ||
                                        pos.y < -removeDistance || pos.y > size.height + removeDistance
                                    if (!isEnd && outside) {
                                        if (!removing) { removing = true; removalPreview = idx }
                                        continue
                                    } else if (removing) {
                                        removing = false; removalPreview = null
                                    }
                                    // Stay between the neighbours so points never swap.
                                    val minX = if (idx > 0) working[idx - 1].x + 1f else 0f
                                    val maxX = if (idx < working.size - 1) working[idx + 1].x - 1f else 255f
                                    working[idx] = PointF(toX(pos.x).coerceIn(minX, maxX.coerceAtLeast(minX)), toY(pos.y))
                                    updatePoints(working)
                                }
                                removalPreview = null
                                when {
                                    removing -> {
                                        working.removeAt(idx)
                                        selectedPointIndex = null
                                        updatePoints(working)
                                        latestCommit()
                                    }
                                    moved || added -> latestCommit()
                                    else -> {
                                        // Double tap an inner point to delete it.
                                        val now = System.currentTimeMillis()
                                        if (idx == lastTapIndex && now - lastTapTime < 350L && idx in 1 until working.size - 1) {
                                            working.removeAt(idx)
                                            selectedPointIndex = null
                                            updatePoints(working)
                                            latestCommit()
                                            lastTapIndex = -1
                                        } else {
                                            lastTapIndex = idx
                                            lastTapTime = now
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    val canvasW = size.width
                    val canvasH = size.height

                    // Histogram (luminance for RGB, the channel otherwise).
                    histogram?.let { h ->
                        val data = h[selectedChannel.ordinal]
                        val hp = Path()
                        hp.moveTo(0f, canvasH)
                        for (i in 0..255) hp.lineTo(i / 255f * canvasW, canvasH - data[i] * canvasH * 0.9f)
                        hp.lineTo(canvasW, canvasH)
                        hp.close()
                        drawPath(hp, color = (if (selectedChannel == CurveChannel.RGB) Color.White else selectedChannel.color).copy(alpha = 0.16f))
                    }

                    // Grid (quarters) and the identity diagonal.
                    val gridEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    for (i in 1..3) {
                        val x = canvasW * (i / 4f)
                        val y = canvasH * (i / 4f)
                        drawLine(Color.White.copy(alpha = 0.08f), Offset(x, 0f), Offset(x, canvasH), strokeWidth = 1f, pathEffect = gridEffect)
                        drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, y), Offset(canvasW, y), strokeWidth = 1f, pathEffect = gridEffect)
                    }
                    drawLine(Color.White.copy(alpha = 0.20f), Offset(0f, canvasH), Offset(canvasW, 0f), strokeWidth = 1.5.dp.toPx())

                    // Other channels' curves faintly, so their effect stays visible.
                    CurveChannel.values().filter { it != selectedChannel }.forEach { ch ->
                        val pts = adjustments.pointsFor(ch)
                        if (!isIdentityCurve(pts)) {
                            val v = ImageProcessor.curveValues(pts)
                            val p = Path()
                            p.moveTo(0f, canvasH - v[0] / 255f * canvasH)
                            for (x in 1..255) p.lineTo(x / 255f * canvasW, canvasH - v[x] / 255f * canvasH)
                            drawPath(p, color = ch.color.copy(alpha = 0.35f), style = Stroke(width = 1.dp.toPx()))
                        }
                    }

                    val shown = currentPoints.filterIndexed { i, _ -> i != removalPreview }
                    val values = ImageProcessor.curveValues(shown)
                    val curvePath = Path()
                    curvePath.moveTo(0f, canvasH - values[0] / 255f * canvasH)
                    for (x in 1..255) curvePath.lineTo(x / 255f * canvasW, canvasH - values[x] / 255f * canvasH)
                    drawPath(curvePath, color = selectedChannel.color, style = Stroke(width = 2.5.dp.toPx()))

                    currentPoints.forEachIndexed { index, pt ->
                        if (index == removalPreview) return@forEachIndexed
                        val c = Offset(pt.x / 255f * canvasW, (1f - pt.y / 255f) * canvasH)
                        if (index == selectedPointIndex) {
                            drawCircle(color = selectedChannel.color.copy(alpha = 0.35f), radius = 14.dp.toPx(), center = c)
                        }
                        drawCircle(color = selectedChannel.color, radius = 7.dp.toPx(), center = c)
                        drawCircle(color = Color.Black, radius = 3.dp.toPx(), center = c)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Selected point: values, fine nudges, delete.
        val pt = selectedPointIndex?.let { currentPoints.getOrNull(it) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (pt != null) {
                Text(
                    text = stringResource(R.string.curves_point_in_out, pt.x.roundToInt(), pt.y.roundToInt()),
                    color = selectedChannel.color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            } else {
                Text(stringResource(R.string.curves_hint), color = Color.Gray, fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
            if (pt != null) {
                val idx = selectedPointIndex!!
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    fun nudge(dx: Float, dy: Float) {
                        val pts = currentPoints.toMutableList()
                        val minX = if (idx > 0) pts[idx - 1].x + 1f else 0f
                        val maxX = if (idx < pts.size - 1) pts[idx + 1].x - 1f else 255f
                        val p = pts[idx]
                        pts[idx] = PointF((p.x + dx).coerceIn(minX, maxX.coerceAtLeast(minX)), (p.y + dy).coerceIn(0f, 255f))
                        updatePoints(pts)
                        onAdjustmentsCommit()
                    }
                    NudgeButton(stringResource(R.string.curves_input_minus), "◀") { nudge(-1f, 0f) }
                    NudgeButton(stringResource(R.string.curves_input_plus), "▶") { nudge(1f, 0f) }
                    NudgeButton(stringResource(R.string.curves_output_minus), "▼") { nudge(0f, -1f) }
                    NudgeButton(stringResource(R.string.curves_output_plus), "▲") { nudge(0f, 1f) }
                    if (idx != 0 && idx != currentPoints.size - 1) {
                        IconButton(
                            onClick = {
                                val pts = currentPoints.toMutableList()
                                if (idx in 1 until pts.size - 1) {
                                    pts.removeAt(idx)
                                    selectedPointIndex = null
                                    updatePoints(pts)
                                    onAdjustmentsCommit()
                                }
                            },
                            modifier = Modifier.size(40.dp).testTag("btn_delete_curve_point")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.curves_delete_point), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Pick on the image: target point, black / grey / white points.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PickChip(stringResource(R.string.curves_pick_point), Icons.Default.TouchApp, Color.White, pickMode == CurvePickMode.POINT) {
                onPickModeChange(if (pickMode == CurvePickMode.POINT) null else CurvePickMode.POINT)
            }
            PickChip(stringResource(R.string.curves_pick_black), Icons.Default.Colorize, Color(0xFF555555), pickMode == CurvePickMode.BLACK) {
                onPickModeChange(if (pickMode == CurvePickMode.BLACK) null else CurvePickMode.BLACK)
            }
            PickChip(stringResource(R.string.curves_pick_gray), Icons.Default.Colorize, Color(0xFF9E9E9E), pickMode == CurvePickMode.GRAY) {
                onPickModeChange(if (pickMode == CurvePickMode.GRAY) null else CurvePickMode.GRAY)
            }
            PickChip(stringResource(R.string.curves_pick_white), Icons.Default.Colorize, Color.White, pickMode == CurvePickMode.WHITE) {
                onPickModeChange(if (pickMode == CurvePickMode.WHITE) null else CurvePickMode.WHITE)
            }
        }
        if (pickMode != null) {
            Text(
                stringResource(R.string.curves_pick_hint),
                color = AmberAccent,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)
            )
        }

        Spacer(Modifier.height(6.dp))

        // Presets for the current channel.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val presets = listOf(
                R.string.curves_preset_linear to IDENTITY,
                R.string.curves_preset_s_curve to listOf(PointF(0f, 0f), PointF(64f, 50f), PointF(192f, 206f), PointF(255f, 255f)),
                R.string.curves_preset_strong_contrast to listOf(PointF(0f, 0f), PointF(64f, 38f), PointF(192f, 218f), PointF(255f, 255f)),
                R.string.curves_preset_lift_shadows to listOf(PointF(0f, 38f), PointF(128f, 148f), PointF(255f, 255f)),
                R.string.curves_preset_deep_blacks to listOf(PointF(20f, 0f), PointF(128f, 112f), PointF(255f, 255f)),
                R.string.curves_preset_brighten to listOf(PointF(0f, 0f), PointF(110f, 145f), PointF(255f, 255f)),
                R.string.curves_preset_darken to listOf(PointF(0f, 0f), PointF(145f, 110f), PointF(255f, 255f)),
                R.string.curves_preset_matte to listOf(PointF(0f, 30f), PointF(64f, 64f), PointF(192f, 196f), PointF(255f, 235f))
            )
            presets.forEach { (label, pts) ->
                CurvePresetButton(stringResource(label)) {
                    updatePoints(pts.map { PointF(it.x, it.y) })
                    selectedPointIndex = null
                    onAdjustmentsCommit()
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    updatePoints(IDENTITY)
                    selectedPointIndex = null
                    onAdjustmentsCommit()
                },
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.curves_reset_channel), fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = {
                    onAdjustmentsChange(adjustments.copy(curvesRgb = IDENTITY, curvesRed = IDENTITY, curvesGreen = IDENTITY, curvesBlue = IDENTITY))
                    selectedPointIndex = null
                    onAdjustmentsCommit()
                },
                modifier = Modifier.weight(1f).heightIn(min = 40.dp).testTag("btn_reset_all_curves"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(stringResource(R.string.curves_reset_all), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun NudgeButton(description: String, symbol: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp).semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(symbol, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun PickChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp)) },
        label = { Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = IndigoPrimary.copy(alpha = 0.35f)),
        modifier = Modifier.heightIn(min = 40.dp)
    )
}

@Composable
private fun CurvePresetButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = StudioSurfaceVariant)
    ) {
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}
