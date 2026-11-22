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
    /** Face sliders changed; only the newest one ([seq]) is rendered. */
    class FaceChanged(val seq: Int, val faces: List<Pair<com.example.engine.FaceGeometry, com.example.engine.FaceAdjust>>) : LiquifyEvent()
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
    onApply: (LiquifyEngine) -> Unit,
    /** Open on the Face-Aware Liquify tab. */
    startOnFaceTab: Boolean = false,
    /** The selection on [source]'s pixels (ALPHA_8, same size): everything outside it is frozen. */
    selection: Bitmap? = null
) {
    // Working copy small enough for live editing; Apply re-samples the field at full resolution.
    val engine by produceState<LiquifyEngine?>(null, source) {
        value = withContext(Dispatchers.Default) {
            val maxDim = 1280
            val s = min(1f, maxDim.toFloat() / maxOf(source.width, source.height))
            val work = if (s < 1f) Bitmap.createScaledBitmap(source, (source.width * s).roundToInt().coerceAtLeast(1), (source.height * s).roundToInt().coerceAtLeast(1), true)
            else source.copy(Bitmap.Config.ARGB_8888, false)
            LiquifyEngine(work).also { e ->
                if (work !== source) work.recycle()
                selection?.takeIf { !it.isRecycled }?.let { sel ->
                    val m = Bitmap.createBitmap(e.width, e.height, Bitmap.Config.ALPHA_8)
                    android.graphics.Canvas(m).drawBitmap(sel, android.graphics.Rect(0, 0, sel.width, sel.height),
                        android.graphics.Rect(0, 0, e.width, e.height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    val (bytes, stride) = com.example.engine.SelectionEngine.readAlphaBytes(m)
                    m.recycle()
                    e.freezeOutside(bytes, stride)
                }
            }
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
    // Face-Aware Liquify
    var faceTab by remember { mutableStateOf(startOnFaceTab) }
    var faces by remember { mutableStateOf<List<com.example.engine.FaceGeometry>?>(null) }
    val faceAdjust = remember { mutableStateListOf<com.example.engine.FaceAdjust>() }
    var selectedFace by remember { mutableIntStateOf(0) }
    /** The feature being dragged on the image (highlighted). */
    var hoverRegion by remember { mutableStateOf<FaceDrag?>(null) }
    val faceSeq = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    fun pushFaces() {
        val list = faces ?: return
        val seq = faceSeq.incrementAndGet()
        events.trySend(LiquifyEvent.FaceChanged(seq, list.indices.map { list[it] to faceAdjust.getOrElse(it) { com.example.engine.FaceAdjust() } }))
    }

    // Display bitmaps once the engine is ready.
    LaunchedEffect(engine) {
        val e = engine ?: return@LaunchedEffect
        display = Bitmap.createBitmap(e.width, e.height, Bitmap.Config.ARGB_8888).apply { setPixels(e.output, 0, e.width, 0, 0, e.width, e.height) }
        maskOverlay = Bitmap.createBitmap(e.width, e.height, Bitmap.Config.ARGB_8888).also { m ->
            // Show what the selection froze.
            if (selection != null) {
                val row = IntArray(e.width)
                for (y in 0 until e.height) {
                    for (x in 0 until e.width) row[x] = ((e.frozenAt(y * e.width + x) * 110f).toInt() shl 24) or 0xFF2040
                    m.setPixels(row, 0, e.width, 0, y, e.width, 1)
                }
            }
        }
        version++
        // Find the faces on the untouched image (on-device, may take a moment).
        val probe = Bitmap.createBitmap(e.width, e.height, Bitmap.Config.ARGB_8888).apply { setPixels(e.output, 0, e.width, 0, 0, e.width, e.height) }
        val found = try { com.example.engine.FaceWarp.detect(probe) } finally { probe.recycle() }
        faceAdjust.clear()
        repeat(found.size) { faceAdjust.add(com.example.engine.FaceAdjust()) }
        selectedFace = 0
        faces = found
    }

    // Edits run one after another on a worker thread; changed areas are copied to the display.
    LaunchedEffect(engine) {
        val e = engine ?: return@LaunchedEffect
        // Skin settings last applied (the skin pass is slow: only redone when they change).
        var lastSkin: List<Triple<Float, Float, Float>> = emptyList()
        for (ev in events) {
            val rect: android.graphics.Rect? = withContext(worker) {
                when (ev) {
                    is LiquifyEvent.Begin -> { e.beginStroke(); null }
                    is LiquifyEvent.Undo -> e.undoStroke()
                    is LiquifyEvent.RestoreAll -> e.restoreAll()
                    is LiquifyEvent.Dab -> e.dab(latestTool, ev.x, ev.y, ev.vx, ev.vy, latestBrush)
                    is LiquifyEvent.FaceChanged -> {
                        if (ev.seq != faceSeq.get()) null // a newer slider value is queued
                        else {
                            val field = com.example.engine.FaceWarp.field(ev.faces, e.width, e.height) { ev.seq != faceSeq.get() }
                            if (ev.seq != faceSeq.get()) null
                            else {
                                val skin = ev.faces.map { it.second.skinKey }
                                if (skin != lastSkin) {
                                    lastSkin = skin
                                    e.setSkinFix(com.example.engine.FaceSkin.fixer(ev.faces))
                                }
                                e.setFaceField(field?.first, field?.second, com.example.engine.FaceWarp.eyeBrightener(ev.faces))
                            }
                        }
                    }
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

    val barsPadding = hostSystemBarsPadding()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = StudioSurface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            modifier = Modifier.padding(barsPadding).fillMaxSize().padding(6.dp)
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
                                // On the Face tab one finger drags a feature of the selected face (like
                                // Photoshop's on-image handles); two fingers still zoom.
                                var viewOnly = faceTab
                                val faceList = faces
                                var drag: FaceDrag? = null
                                var dragStart: com.example.engine.FaceAdjust? = null
                                var dragFace = -1
                                if (faceTab && faceList != null && faceList.isNotEmpty()) {
                                    val ip = toImage(down.position)
                                    // A touch inside another face's box selects that face.
                                    val hitFace = faceList.indices.firstOrNull { i -> faceList[i].box.contains(ip.x, ip.y) && i != selectedFace }
                                    if (hitFace != null && !faceList[selectedFace.coerceIn(0, faceList.size - 1)].box.contains(ip.x, ip.y)) selectedFace = hitFace
                                    dragFace = selectedFace.coerceIn(0, faceList.size - 1)
                                    drag = faceRegion(faceList[dragFace], ip.x, ip.y)
                                    dragStart = faceAdjust.getOrNull(dragFace)
                                    hoverRegion = drag
                                }
                                if (!viewOnly) {
                                    cursor = last
                                    events.trySend(LiquifyEvent.Begin)
                                    val p0 = toImage(last)
                                    events.trySend(LiquifyEvent.Dab(p0.x, p0.y, 0f, 0f))
                                    holding = true
                                }
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
                                    } else if (!viewMode && viewOnly && drag != null && dragStart != null && faceList != null) {
                                        // Feature drag: the finger's movement in the face's own axes.
                                        val g = faceList[dragFace]
                                        val a0 = toImage(down.position); val a1 = toImage(pressed[0].position)
                                        val dx = a1.x - a0.x; val dy = a1.y - a0.y
                                        val dlx = dx * g.ax.x + dy * g.ax.y; val dly = dx * g.ay.x + dy * g.ay.y
                                        if (dragFace in faceAdjust.indices) {
                                            faceAdjust[dragFace] = applyFaceDrag(dragStart!!, drag!!, g, a0.x, a0.y, dlx, dly)
                                            pushFaces()
                                        }
                                        pressed[0].consume()
                                    } else if (!viewMode && !viewOnly) {
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
                                hoverRegion = null
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
                        if (faceTab) {
                            faces?.forEachIndexed { idx, g ->
                                val sel = idx == selectedFace
                                val col = if (sel) AmberAccent else Color.White.copy(alpha = 0.5f)
                                fun sp(p: android.graphics.PointF) = Offset(o.x + p.x * s, o.y + p.y * s)
                                drawRect(col, Offset(o.x + g.box.left * s, o.y + g.box.top * s),
                                    androidx.compose.ui.geometry.Size(g.box.width() * s, g.box.height() * s), style = Stroke(if (sel) 2f else 1f))
                                if (sel) {
                                    // Feature outlines (the areas that can be dragged).
                                    val hi = hoverRegion
                                    fun stroke(region: FaceDrag) = if (hi == region) Color(0xFF22D3EE) else Color.White.copy(alpha = 0.55f)
                                    g.oval?.let { pts ->
                                        val path = androidx.compose.ui.graphics.Path()
                                        pts.forEachIndexed { i, q -> val o2 = sp(q); if (i == 0) path.moveTo(o2.x, o2.y) else path.lineTo(o2.x, o2.y) }
                                        path.close()
                                        drawPath(path, Color.Black.copy(alpha = 0.4f), style = Stroke(3f))
                                        drawPath(path, if (hi == FaceDrag.JAW || hi == FaceDrag.FACE_WIDTH) Color(0xFF22D3EE) else Color.White.copy(alpha = 0.55f), style = Stroke(1.5f))
                                    }
                                    listOf(g.eye1 to FaceDrag.EYE_LEFT, g.eye2 to FaceDrag.EYE_RIGHT).forEach { (e, r) ->
                                        drawCircle(stroke(r), g.eyeW * 0.6f * s, sp(e), style = Stroke(1.5f))
                                    }
                                    drawCircle(stroke(FaceDrag.NOSE), g.noseW * 0.6f * s, sp(g.noseBase), style = Stroke(1.5f))
                                    drawLine(stroke(FaceDrag.MOUTH), sp(g.mouthLeft), sp(g.mouthRight), 2f)
                                    listOf(g.eye1, g.eye2, g.noseBase, g.mouthLeft, g.mouthRight, g.chin, g.forehead).forEach {
                                        drawCircle(Color.Black.copy(alpha = 0.6f), 5f, sp(it)); drawCircle(col, 3.5f, sp(it))
                                    }
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

                Spacer(Modifier.height(6.dp))
                TabRow(selectedTabIndex = if (faceTab) 1 else 0, containerColor = StudioSurfaceVariant, contentColor = Color.White) {
                    Tab(selected = !faceTab, onClick = { faceTab = false }, text = { Text(stringResource(R.string.liq_tab_brush), fontSize = 12.sp) })
                    Tab(selected = faceTab, onClick = { faceTab = true }, text = { Text(stringResource(R.string.liq_tab_face), fontSize = 12.sp) },
                        modifier = Modifier.testTag("liq_tab_face"))
                }
                Spacer(Modifier.height(6.dp))
                if (faceTab) {
                    FaceAwarePanel(
                        faces = faces,
                        adjust = faceAdjust,
                        selected = selectedFace,
                        onSelect = { selectedFace = it },
                        onChange = { i, a -> if (i in faceAdjust.indices) { faceAdjust[i] = a; pushFaces() } },
                        onResetAll = { for (i in faceAdjust.indices) faceAdjust[i] = com.example.engine.FaceAdjust(); pushFaces() }
                    )
                } else {
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
}

@Composable
private fun LiqSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.width(78.dp))
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValue, valueRange = range, modifier = Modifier.weight(1f))
        Text("${value.roundToInt()}", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
    }
}

/** Face-Aware Liquify sliders for the selected face (Photoshop's groups: eyes, nose, mouth, face shape). */
@Composable
private fun FaceAwarePanel(
    faces: List<com.example.engine.FaceGeometry>?,
    adjust: List<com.example.engine.FaceAdjust>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onChange: (Int, com.example.engine.FaceAdjust) -> Unit,
    onResetAll: () -> Unit
) {
    when {
        faces == null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = IndigoPrimary)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.face_detecting), color = Color.LightGray, fontSize = 12.sp)
        }
        faces.isEmpty() -> Text(stringResource(R.string.face_none_found), color = AmberAccent, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        else -> {
            val idx = selected.coerceIn(0, faces.size - 1)
            val a = adjust.getOrElse(idx) { com.example.engine.FaceAdjust() }
            fun set(f: com.example.engine.FaceAdjust) = onChange(idx, f)
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    if (faces.size > 1) faces.indices.forEach { i ->
                        FilterChip(selected = i == idx, onClick = { onSelect(i) }, label = { Text(stringResource(R.string.face_n, i + 1), fontSize = 12.sp) })
                        Spacer(Modifier.width(6.dp))
                    }
                    TextButton(onClick = { set(com.example.engine.FaceAdjust()) }) { Text(stringResource(R.string.face_reset), color = AmberAccent, fontSize = 12.sp) }
                    if (faces.size > 1) TextButton(onClick = onResetAll) { Text(stringResource(R.string.face_reset_all), color = AmberAccent, fontSize = 12.sp) }
                }
                FaceSection(R.string.face_sec_eyes)
                Text(stringResource(R.string.face_drag_hint), color = Color.Gray, fontSize = 10.sp)
                // Photoshop's eye link: both eyes together, or each one on its own.
                var eyeIdx by remember { mutableIntStateOf(0) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(
                        selected = a.eyesLinked,
                        onClick = { set(if (a.eyesLinked) a.unlinked() else a.copy(eyesLinked = true)) },
                        label = { Text(stringResource(if (a.eyesLinked) R.string.face_eyes_linked else R.string.face_eyes_unlinked), fontSize = 12.sp) },
                        leadingIcon = { Icon(if (a.eyesLinked) Icons.Default.Link else Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("face_eye_link")
                    )
                    if (!a.eyesLinked) {
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = eyeIdx == 0, onClick = { eyeIdx = 0 }, label = { Text(stringResource(R.string.face_eye_left), fontSize = 12.sp) })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = eyeIdx == 1, onClick = { eyeIdx = 1 }, label = { Text(stringResource(R.string.face_eye_right), fontSize = 12.sp) })
                    }
                }
                val e = if (a.eyesLinked) 0 else eyeIdx
                val ev = a.eye(e)
                fun setEye(slot: Int, v: Float) = set(withEyeValue(a, e, slot, v))
                FaceSlider(R.string.face_eye_size, ev[0]) { setEye(0, it) }
                FaceSlider(R.string.face_eye_height, ev[1]) { setEye(1, it) }
                FaceSlider(R.string.face_eye_width, ev[2]) { setEye(2, it) }
                FaceSlider(R.string.face_eye_tilt, ev[3]) { setEye(3, it) }
                FaceSlider(R.string.face_eye_distance, ev[4]) { setEye(4, it) }
                FaceSlider(R.string.face_eye_brightness, a.eyeBrightness, 0f..100f) { set(a.copy(eyeBrightness = it)) }
                FaceSection(R.string.face_sec_nose)
                FaceSlider(R.string.face_nose_height, a.noseHeight) { set(a.copy(noseHeight = it)) }
                FaceSlider(R.string.face_nose_width, a.noseWidth) { set(a.copy(noseWidth = it)) }
                FaceSection(R.string.face_sec_mouth)
                FaceSlider(R.string.face_smile, a.smile) { set(a.copy(smile = it)) }
                FaceSlider(R.string.face_upper_lip, a.upperLip) { set(a.copy(upperLip = it)) }
                FaceSlider(R.string.face_lower_lip, a.lowerLip) { set(a.copy(lowerLip = it)) }
                FaceSlider(R.string.face_mouth_width, a.mouthWidth) { set(a.copy(mouthWidth = it)) }
                FaceSlider(R.string.face_mouth_height, a.mouthHeight) { set(a.copy(mouthHeight = it)) }
                FaceSection(R.string.face_sec_shape)
                FaceSlider(R.string.face_forehead, a.forehead) { set(a.copy(forehead = it)) }
                FaceSlider(R.string.face_chin_height, a.chinHeight) { set(a.copy(chinHeight = it)) }
                FaceSlider(R.string.face_jawline, a.jawline) { set(a.copy(jawline = it)) }
                FaceSlider(R.string.face_face_width, a.faceWidth) { set(a.copy(faceWidth = it)) }
                FaceSlider(R.string.face_head_size, a.headSize) { set(a.copy(headSize = it)) }
                FaceSection(R.string.face_sec_skin)
                FaceSlider(R.string.face_blemishes, a.blemishes, 0f..100f) { set(a.copy(blemishes = it)) }
                FaceSlider(R.string.face_dark_circles, a.darkCircles, 0f..100f) { set(a.copy(darkCircles = it)) }
                FaceSlider(R.string.face_skin_smooth, a.skinSmooth, 0f..100f) { set(a.copy(skinSmooth = it)) }
            }
        }
    }
}

@Composable
private fun FaceSection(label: Int) {
    Text(stringResource(label), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun FaceSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float> = -100f..100f, onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.width(96.dp))
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = { onValue(kotlin.math.round(it)) }, valueRange = range, modifier = Modifier.weight(1f))
        Text("${value.roundToInt()}", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
    }
}


/** Features of a face that can be dragged on the image (Photoshop's on-image face handles). */
enum class FaceDrag { EYE_LEFT, EYE_RIGHT, NOSE, MOUTH, MOUTH_CORNER, FOREHEAD, CHIN, JAW, FACE_WIDTH }

/** [a] with eye value [slot] (0 size, 1 height, 2 width, 3 tilt, 4 distance) of eye [eye] set to [v]. */
internal fun withEyeValue(a: com.example.engine.FaceAdjust, eye: Int, slot: Int, v: Float): com.example.engine.FaceAdjust =
    if (a.eyesLinked || eye == 0) when (slot) {
        0 -> a.copy(eyeSize = v); 1 -> a.copy(eyeHeight = v); 2 -> a.copy(eyeWidth = v); 3 -> a.copy(eyeTilt = v); else -> a.copy(eyeDistance = v)
    } else when (slot) {
        0 -> a.copy(eye2Size = v); 1 -> a.copy(eye2Height = v); 2 -> a.copy(eye2Width = v); 3 -> a.copy(eye2Tilt = v); else -> a.copy(eye2Distance = v)
    }

/** Which feature of [g] a touch at image point (x, y) grabs, or null when it is not on the face. */
internal fun faceRegion(g: com.example.engine.FaceGeometry, x: Float, y: Float): FaceDrag? {
    fun d(p: android.graphics.PointF) = kotlin.math.hypot(x - p.x, y - p.y)
    fun along(p: android.graphics.PointF, v: android.graphics.PointF) = (p.x - g.center.x) * v.x + (p.y - g.center.y) * v.y
    val here = android.graphics.PointF(x, y)
    val lx = along(here, g.ax); val ly = along(here, g.ay)
    if (kotlin.math.abs(lx) > g.width * 0.75f || ly < -g.height * 0.75f || ly > g.height * 0.7f) return null
    val mouthW = kotlin.math.hypot(g.mouthRight.x - g.mouthLeft.x, g.mouthRight.y - g.mouthLeft.y)
    return when {
        d(g.eye1) < g.eyeW * 0.9f -> FaceDrag.EYE_LEFT
        d(g.eye2) < g.eyeW * 0.9f -> FaceDrag.EYE_RIGHT
        d(g.mouthLeft) < mouthW * 0.22f || d(g.mouthRight) < mouthW * 0.22f -> FaceDrag.MOUTH_CORNER
        d(g.mouthCenter) < mouthW * 0.6f -> FaceDrag.MOUTH
        d(g.noseBase) < g.noseW * 0.9f -> FaceDrag.NOSE
        ly < along(g.eye1, g.ay) - g.eyeH * 2.5f -> FaceDrag.FOREHEAD
        ly > along(g.mouthCenter, g.ay) + g.mouthH && kotlin.math.abs(lx) < g.width * 0.22f -> FaceDrag.CHIN
        ly > along(g.noseBase, g.ay) -> FaceDrag.JAW
        else -> FaceDrag.FACE_WIDTH
    }
}

/**
 * [start] changed by a drag of (dlx, dly) image pixels in the face's own axes on [region] (touch
 * started at x0, y0). About 30 % of the face width covers the whole -100..100 range.
 */
internal fun applyFaceDrag(
    start: com.example.engine.FaceAdjust, region: FaceDrag, g: com.example.engine.FaceGeometry,
    x0: Float, y0: Float, dlx: Float, dly: Float
): com.example.engine.FaceAdjust {
    val unit = (g.width * 0.003f).coerceAtLeast(0.5f)
    fun v(base: Float, delta: Float) = (base + delta / unit).coerceIn(-100f, 100f).let { kotlin.math.round(it) }
    // Outward (away from the face's centre line) is positive for sideways drags.
    val side = if ((x0 - g.center.x) * g.ax.x + (y0 - g.center.y) * g.ax.y < 0f) -1f else 1f
    val out = dlx * side
    val vertical = kotlin.math.abs(dly) >= kotlin.math.abs(dlx)
    return when (region) {
        FaceDrag.EYE_LEFT, FaceDrag.EYE_RIGHT -> {
            val e = if (region == FaceDrag.EYE_LEFT) 0 else 1
            val ev = start.eye(e)
            if (vertical) withEyeValue(start, e, 0, v(ev[0], -dly)) else withEyeValue(start, e, 4, v(ev[4], out))
        }
        FaceDrag.NOSE -> if (vertical) start.copy(noseHeight = v(start.noseHeight, dly)) else start.copy(noseWidth = v(start.noseWidth, out))
        FaceDrag.MOUTH_CORNER -> if (vertical) start.copy(smile = v(start.smile, -dly)) else start.copy(mouthWidth = v(start.mouthWidth, out))
        FaceDrag.MOUTH -> if (vertical) start.copy(mouthHeight = v(start.mouthHeight, kotlin.math.abs(dly) * (if ((y0 - g.mouthCenter.y) * g.ay.y + (x0 - g.mouthCenter.x) * g.ay.x < 0f) -kotlin.math.sign(dly) else kotlin.math.sign(dly))))
            else start.copy(mouthWidth = v(start.mouthWidth, out))
        FaceDrag.FOREHEAD -> start.copy(forehead = v(start.forehead, -dly))
        FaceDrag.CHIN -> start.copy(chinHeight = v(start.chinHeight, dly))
        FaceDrag.JAW -> start.copy(jawline = v(start.jawline, out))
        FaceDrag.FACE_WIDTH -> start.copy(faceWidth = v(start.faceWidth, out))
    }
}
