package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.engine.LiquifyBrush
import com.example.engine.LiquifyEngine
import com.example.engine.LiquifyTool
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private sealed class LiquifyEvent {
    object Begin : LiquifyEvent()
    class Dab(val x: Float, val y: Float, val vx: Float, val vy: Float) : LiquifyEvent()
    object Undo : LiquifyEvent()
    object RestoreAll : LiquifyEvent()
}

private fun liquifyToolIcon(t: LiquifyTool): ImageVector = when (t) {
    LiquifyTool.FORWARD_WARP -> Icons.Default.PanTool
    LiquifyTool.RECONSTRUCT -> Icons.Default.Restore
    LiquifyTool.SMOOTH -> Icons.Default.Waves
    LiquifyTool.TWIRL_CW -> Icons.Default.RotateRight
    LiquifyTool.TWIRL_CCW -> Icons.Default.RotateLeft
    LiquifyTool.PUCKER -> Icons.Default.FilterCenterFocus
    LiquifyTool.BLOAT -> Icons.Default.ZoomOutMap
    LiquifyTool.PUSH_LEFT -> Icons.AutoMirrored.Filled.ArrowBack
    LiquifyTool.FREEZE -> Icons.Default.AcUnit
    LiquifyTool.THAW -> Icons.Default.WbSunny
}

private fun liquifyToolLabel(t: LiquifyTool): Int = when (t) {
    LiquifyTool.FORWARD_WARP -> R.string.liq_forward_warp
    LiquifyTool.RECONSTRUCT -> R.string.liq_reconstruct
    LiquifyTool.SMOOTH -> R.string.liq_smooth
    LiquifyTool.TWIRL_CW -> R.string.liq_twirl_cw
    LiquifyTool.TWIRL_CCW -> R.string.liq_twirl_ccw
    LiquifyTool.PUCKER -> R.string.liq_pucker
    LiquifyTool.BLOAT -> R.string.liq_bloat
    LiquifyTool.PUSH_LEFT -> R.string.liq_push_left
    LiquifyTool.FREEZE -> R.string.liq_freeze
    LiquifyTool.THAW -> R.string.liq_thaw
}

/** Tools that keep working while the finger stays still (like holding the mouse in Photoshop). */
private fun LiquifyTool.continuous() = this == LiquifyTool.TWIRL_CW || this == LiquifyTool.TWIRL_CCW ||
    this == LiquifyTool.PUCKER || this == LiquifyTool.BLOAT || this == LiquifyTool.RECONSTRUCT || this == LiquifyTool.SMOOTH

/**
 * Full-screen Liquify workspace (Photoshop's Liquify filter) for an image layer.
 * One finger uses the tool, two fingers zoom and pan. [onApply] receives the engine; the caller
 * warps the layer's full-resolution pixels with it.
 */
@Composable
fun LiquifyDialog(
    source: Bitmap,
    onDismiss: () -> Unit,
    onApply: (LiquifyEngine) -> Unit
) {
    // Working copy small enough for live editing; Apply re-samples the field at full resolution.
    val engine by produceState<LiquifyEngine?>(null, source) {
        value = withContext(Dispatchers.Default) {
            val maxDim = 1280
            val s = min(1f, maxDim.toFloat() / maxOf(source.width, source.height))
            val work = if (s < 1f) Bitmap.createScaledBitmap(source, (source.width * s).roundToInt().coerceAtLeast(1), (source.height * s).roundToInt().coerceAtLeast(1), true)
            else source.copy(Bitmap.Config.ARGB_8888, false)
            LiquifyEngine(work).also { if (work !== source) work.recycle() }
        }
    }
    var display by remember { mutableStateOf<Bitmap?>(null) }
    var maskOverlay by remember { mutableStateOf<Bitmap?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(LiquifyTool.FORWARD_WARP) }
    var brush by remember { mutableStateOf(LiquifyBrush()) }
    var showMesh by remember { mutableStateOf(false) }
    var showMask by remember { mutableStateOf(true) }
    var canUndo by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var viewScale by remember { mutableFloatStateOf(1f) }
    var viewOffset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val events = remember { Channel<LiquifyEvent>(Channel.UNLIMITED) }
    val worker = remember { java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
    DisposableEffect(Unit) { onDispose { worker.close() } }
    val latestTool by rememberUpdatedState(tool)
    val latestBrush by rememberUpdatedState(brush)

    // Display bitmaps once the engine is ready.
    LaunchedEffect(engine) {
        val e = engine ?: return@LaunchedEffect
        display = Bitmap.createBitmap(e.width, e.height, Bitmap.Config.ARGB_8888).apply { setPixels(e.output, 0, e.width, 0, 0, e.width, e.height) }
        maskOverlay = Bitmap.createBitmap(e.width, e.height, Bitmap.Config.ARGB_8888)
        version++
    }

    // Edits run one after another on a worker thread; changed areas are copied to the display.
    LaunchedEffect(engine) {
        val e = engine ?: return@LaunchedEffect
        for (ev in events) {
            val rect: android.graphics.Rect? = withContext(worker) {
                when (ev) {
                    is LiquifyEvent.Begin -> { e.beginStroke(); null }
                    is LiquifyEvent.Undo -> e.undoStroke()
                    is LiquifyEvent.RestoreAll -> e.restoreAll()
                    is LiquifyEvent.Dab -> e.dab(latestTool, ev.x, ev.y, ev.vx, ev.vy, latestBrush)
                }
            }
            val d = display ?: continue
            if (rect != null && !rect.isEmpty) {
                d.setPixels(e.output, rect.top * e.width + rect.left, e.width, rect.left, rect.top, rect.width(), rect.height())
                val t = latestTool
                if ((t == LiquifyTool.FREEZE || t == LiquifyTool.THAW) && ev is LiquifyEvent.Dab) {
                    maskOverlay?.let { m ->
                        val row = IntArray(rect.width())
                        for (y in rect.top until rect.bottom) {
                            for (x in rect.left until rect.right) {
                                val a = (e.frozenAt(y * e.width + x) * 110f).toInt()
                                row[x - rect.left] = (a shl 24) or 0xFF2040
                            }
                            m.setPixels(row, 0, rect.width(), rect.left, y, rect.width(), 1)
                        }
                    }
                }
                version++
            }
            canUndo = e.canUndo
        }
    }

    // Placement of the working image in the view box.
    fun fitScale(): Float {
        val e = engine ?: return 1f
        if (boxSize.width == 0 || boxSize.height == 0) return 1f
        return min(boxSize.width / e.width.toFloat(), boxSize.height / e.height.toFloat())
    }
    fun origin(): Offset {
        val e = engine ?: return Offset.Zero
        val s = fitScale() * viewScale
        return Offset((boxSize.width - e.width * s) / 2f, (boxSize.height - e.height * s) / 2f) + viewOffset
    }
    fun toImage(p: Offset): Offset { val s = fitScale() * viewScale; val o = origin(); return Offset((p.x - o.x) / s, (p.y - o.y) / s) }

    // Held-in-place tools keep acting while the finger rests.
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(holding) {
        while (holding && isActive) {
            delay(40)
            val c = cursor
            if (c != null && latestTool.continuous()) {
                val p = toImage(c)
                events.trySend(LiquifyEvent.Dab(p.x, p.y, 0f, 0f))
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = StudioSurface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            modifier = Modifier.fillMaxSize().padding(6.dp)
        ) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.action_cancel), tint = Color.LightGray) }
                    Text(stringResource(R.string.liq_title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { events.trySend(LiquifyEvent.Undo) }, enabled = canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.action_undo), tint = if (canUndo) Color.White else Color.Gray)
                    }
                    Button(
                        onClick = { engine?.let { onApply(it) } },
                        enabled = engine != null,
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                        modifier = Modifier.testTag("btn_liquify_apply")
                    ) { Text(stringResource(R.string.filter_apply), fontWeight = FontWeight.Bold) }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds()
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .onSizeChanged { boxSize = it }
                        .pointerInput(engine) {
                            if (engine == null) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                var viewMode = false
                                var last = down.position
                                cursor = last
                                events.trySend(LiquifyEvent.Begin)
                                val p0 = toImage(last)
                                events.trySend(LiquifyEvent.Dab(p0.x, p0.y, 0f, 0f))
                                holding = true
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val pressed = ev.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size >= 2) {
                                        // Two fingers: zoom / pan the view (the stroke so far stays; Undo removes it).
                                        if (!viewMode) { viewMode = true; holding = false; cursor = null }
                                        val zoom = ev.calculateZoom()
                                        val pan = ev.calculatePan()
                                        viewScale = (viewScale * zoom).coerceIn(1f, 12f)
                                        viewOffset += pan
                                        ev.changes.forEach { it.consume() }
                                    } else if (!viewMode) {
                                        val pos = pressed[0].position
                                        val a = toImage(last); val b = toImage(pos)
                                        val dist = (b - a).getDistance()
                                        // Dabs every ~1/6 of the brush along the finger path.
                                        val step = (latestBrush.size / 6f).coerceAtLeast(2f)
                                        if (dist >= step) {
                                            val nSteps = (dist / step).toInt().coerceAtLeast(1)
                                            for (k in 1..nSteps) {
                                                val t0 = (k - 1f) / nSteps; val t1 = k.toFloat() / nSteps
                                                val s0 = a + (b - a) * t0; val s1 = a + (b - a) * t1
                                                events.trySend(LiquifyEvent.Dab(s1.x, s1.y, s1.x - s0.x, s1.y - s0.y))
                                            }
                                            last = pos
                                        }
                                        cursor = pos
                                        pressed[0].consume()
                                    }
                                }
                                holding = false
                                cursor = null
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        if (version < 0) return@Canvas // read so every edit redraws
                        val e = engine
                        val d = display
                        if (e == null || d == null) return@Canvas
                        val s = fitScale() * viewScale
                        val o = origin()
                        val m = android.graphics.Matrix().apply { postScale(s, s); postTranslate(o.x, o.y) }
                        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                        drawContext.canvas.nativeCanvas.drawBitmap(d, m, paint)
                        if (showMask) maskOverlay?.let { drawContext.canvas.nativeCanvas.drawBitmap(it, m, paint) }
                        if (showMesh) {
                            val step = (e.width / 24).coerceAtLeast(8)
                            val tmp = FloatArray(2)
                            val meshColor = Color.White.copy(alpha = 0.35f)
                            for (gy in 0..e.height step step) for (gx in 0..e.width step step) {
                                e.displacement(gx.toFloat(), gy.toFloat(), tmp)
                                val p = Offset(o.x + (gx - tmp[0]) * s, o.y + (gy - tmp[1]) * s)
                                if (gx + step <= e.width) {
                                    e.displacement((gx + step).toFloat(), gy.toFloat(), tmp)
                                    drawLine(meshColor, p, Offset(o.x + (gx + step - tmp[0]) * s, o.y + (gy - tmp[1]) * s), 1f)
                                }
                                if (gy + step <= e.height) {
                                    e.displacement(gx.toFloat(), (gy + step).toFloat(), tmp)
                                    drawLine(meshColor, p, Offset(o.x + (gx - tmp[0]) * s, o.y + (gy + step - tmp[1]) * s), 1f)
                                }
                            }
                        }
                        cursor?.let { c ->
                            val r = brush.size / 2f * s
                            drawCircle(Color.White, r, c, style = Stroke(2f))
                            drawCircle(Color.Black.copy(alpha = 0.6f), r + 2f, c, style = Stroke(1f))
                            val core = r * (1f - brush.density / 100f).coerceIn(0f, 0.95f)
                            if (core > 3f) drawCircle(Color.White.copy(alpha = 0.5f), core, c, style = Stroke(1f))
                        }
                    }
                    if (engine == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = IndigoPrimary)
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LiquifyTool.values().forEach { t ->
                        val sel = t == tool
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(68.dp)
                                .background(if (sel) IndigoPrimary.copy(alpha = 0.3f) else StudioSurfaceVariant, RoundedCornerShape(8.dp))
                                .clickable { tool = t }
                                .padding(vertical = 6.dp)
                                .testTag("liq_tool_${t.name.lowercase()}")
                        ) {
                            Icon(liquifyToolIcon(t), contentDescription = null, tint = if (sel) Color.White else Color.LightGray, modifier = Modifier.size(20.dp))
                            Text(stringResource(liquifyToolLabel(t)), color = if (sel) Color.White else Color.LightGray, fontSize = 10.sp, maxLines = 2, lineHeight = 11.sp)
                        }
                    }
                }
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    LiqSlider(R.string.liq_size, brush.size, 6f..600f) { brush = brush.copy(size = it) }
                    LiqSlider(R.string.liq_pressure, brush.pressure, 1f..100f) { brush = brush.copy(pressure = it) }
                    LiqSlider(R.string.liq_density, brush.density, 0f..100f) { brush = brush.copy(density = it) }
                    if (tool.continuous()) LiqSlider(R.string.liq_rate, brush.rate, 1f..100f) { brush = brush.copy(rate = it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = showMesh, onClick = { showMesh = !showMesh }, label = { Text(stringResource(R.string.liq_show_mesh), fontSize = 12.sp) })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = showMask, onClick = { showMask = !showMask }, label = { Text(stringResource(R.string.liq_show_mask), fontSize = 12.sp) })
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { events.trySend(LiquifyEvent.RestoreAll) }) {
                            Text(stringResource(R.string.liq_restore_all), color = AmberAccent)
                        }
                    }
                    Text(stringResource(R.string.liq_hint), color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun LiqSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.width(78.dp))
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValue, valueRange = range, modifier = Modifier.weight(1f))
        Text("${value.roundToInt()}", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
    }
}
