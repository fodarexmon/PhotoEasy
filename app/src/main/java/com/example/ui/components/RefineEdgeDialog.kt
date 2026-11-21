package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.engine.RefineEdgeConfig
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.PinkAccent
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import kotlin.math.max
import kotlin.math.round

/**
 * The single selection-refine workspace ("Select and Mask"): edge detection and hair matting,
 * global refinements (smooth, feather, contrast, shift edge), clean-up and border, colour
 * decontamination. Every change is previewed instantly (a fast pass, then a detailed one);
 * the preview can be zoomed (pinch, double-tap resets) and compared with the original (hold Before).
 */
@Composable
fun RefineEdgeDialog(
    initialConfig: RefineEdgeConfig = RefineEdgeConfig(),
    onDismiss: () -> Unit,
    onApply: (RefineEdgeConfig, List<com.example.ui.viewmodel.RefineStroke>) -> Unit,
    /** Live preview of the refined selection (null while computing / unavailable). */
    preview: android.graphics.Bitmap? = null,
    /** The same area before refinement (shown while Before is held). */
    previewOriginal: android.graphics.Bitmap? = null,
    previewMode: com.example.ui.viewmodel.RefinePreviewMode = com.example.ui.viewmodel.RefinePreviewMode.OVERLAY,
    onPreviewModeChange: (com.example.ui.viewmodel.RefinePreviewMode) -> Unit = {},
    onRequestPreview: (RefineEdgeConfig, List<com.example.ui.viewmodel.RefineStroke>) -> Unit = { _, _ -> },
    /** How the preview's pixels map back to the layer (needed to paint with the refine brushes). */
    previewGeom: com.example.ui.viewmodel.RefinePreviewGeom? = null,
    /** Longest side of the image: slider ranges scale with it. */
    documentMaxDim: Int = 2000
) {
    var cfg by remember { mutableStateOf(initialConfig) }
    val maxPx = max(20f, documentMaxDim / 25f)

    // Refine brushes: strokes in layer-normalised coordinates; null mode = move / zoom.
    val strokes = remember { mutableStateListOf<com.example.ui.viewmodel.RefineStroke>() }
    var brushMode by remember { mutableStateOf<com.example.ui.viewmodel.RefineBrushMode?>(null) }
    var brushDp by remember { mutableFloatStateOf(28f) }
    var livePath by remember { mutableStateOf<List<Offset>?>(null) }
    var boxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    // Every change re-renders the preview (the ViewModel debounces briefly).
    LaunchedEffect(cfg, previewMode, strokes.size) { onRequestPreview(cfg, strokes.toList()) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var showBefore by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    /** Screen point in the preview box -> layer-normalised point, or null outside the image. */
    fun toLayer(p: Offset): android.graphics.PointF? {
        val g = previewGeom ?: return null
        val pad = with(density) { 4.dp.toPx() }
        val innerW = boxSize.width - 2 * pad; val innerH = boxSize.height - 2 * pad
        if (innerW <= 0f || innerH <= 0f) return null
        val fit = minOf(innerW / g.w, innerH / g.h)
        val cx = boxSize.width / 2f; val cy = boxSize.height / 2f
        // Undo the zoom / pan (graphicsLayer scales around the centre), then the Fit placement.
        val lx = (p.x - cx - pan.x) / zoom + cx; val ly = (p.y - cy - pan.y) / zoom + cy
        val bx = (lx - (cx - g.w * fit / 2f)) / fit; val by = (ly - (cy - g.h * fit / 2f)) / fit
        val u = (g.crop.left + bx * g.crop.width() / g.w) / g.proxyW
        val v = (g.crop.top + by * g.crop.height() / g.h) / g.proxyH
        return android.graphics.PointF(u, v)
    }

    /** Brush radius (screen dp) as a fraction of the layer's longer side. */
    fun brushRadius(): Float {
        val g = previewGeom ?: return 0.01f
        val pad = with(density) { 4.dp.toPx() }
        val fit = minOf((boxSize.width - 2 * pad) / g.w, (boxSize.height - 2 * pad) / g.h).coerceAtLeast(1e-3f)
        val px = with(density) { brushDp.dp.toPx() } / 2f
        val previewPx = px / (fit * zoom)
        val proxyPx = previewPx * g.crop.width() / g.w
        return proxyPx / maxOf(g.proxyW, g.proxyH)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = StudioSurface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .padding(6.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ContentCut, contentDescription = null, tint = IndigoPrimary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.refine_edge_dialog_title),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel), tint = Color.LightGray)
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Large, zoomable live preview.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.95f)
                        .clipToBounds()
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .onSizeChanged { boxSize = it }
                        .pointerInput(brushMode, previewGeom) {
                            // One finger paints with a refine brush (or moves the view); two fingers zoom.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val mode = brushMode
                                val pts = mutableListOf(down.position)
                                var multi = false
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val pressed = ev.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size > 1) {
                                        multi = true
                                        livePath = null
                                        zoom = (zoom * ev.calculateZoom()).coerceIn(1f, 8f)
                                        pan = if (zoom <= 1.001f) Offset.Zero else pan + ev.calculatePan()
                                        ev.changes.forEach { it.consume() }
                                    } else if (!multi && mode != null) {
                                        pts.add(pressed[0].position)
                                        livePath = pts.toList()
                                        pressed[0].consume()
                                    } else if (!multi && zoom > 1.001f) {
                                        val c = pressed[0]
                                        pan += c.position - c.previousPosition
                                        c.consume()
                                    }
                                }
                                if (!multi && mode != null) {
                                    val mapped = pts.mapNotNull { toLayer(it) }
                                    if (mapped.isNotEmpty()) strokes.add(com.example.ui.viewmodel.RefineStroke(mode, mapped, brushRadius()))
                                }
                                livePath = null
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero })
                        }
                        .testTag("refine_preview"),
                    contentAlignment = Alignment.Center
                ) {
                    val shown = if (showBefore) previewOriginal ?: preview else preview
                    if (shown != null && !shown.isRecycled) {
                        androidx.compose.foundation.Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = stringResource(R.string.refine_preview),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp)
                                .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y)
                        )
                    } else {
                        CircularProgressIndicator(color = IndigoPrimary, modifier = Modifier.size(28.dp))
                    }
                    livePath?.let { path ->
                        val col = when (brushMode) {
                            com.example.ui.viewmodel.RefineBrushMode.ADD -> Color(0xFF34D399)
                            com.example.ui.viewmodel.RefineBrushMode.SUBTRACT -> Color(0xFFF87171)
                            else -> CyanAccent
                        }
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val pth = androidx.compose.ui.graphics.Path()
                            path.forEachIndexed { i, o -> if (i == 0) pth.moveTo(o.x, o.y) else pth.lineTo(o.x, o.y) }
                            drawPath(pth, col.copy(alpha = 0.45f), style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = brushDp.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                        }
                    }
                    // Hold to compare with the selection before refinement.
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .heightIn(min = 36.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    showBefore = true
                                    tryAwaitRelease()
                                    showBefore = false
                                })
                            }
                            .testTag("btn_refine_before")
                    ) {
                        Text(
                            stringResource(R.string.refine_before),
                            color = if (showBefore) CyanAccent else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)
                        )
                    }
                    if (zoom > 1.01f) {
                        Text(
                            "${round(zoom * 100f).toInt()}%",
                            color = Color.White,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    stringResource(R.string.refine_zoom_hint),
                    color = Color.Gray,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        com.example.ui.viewmodel.RefinePreviewMode.OVERLAY to R.string.refine_view_overlay,
                        com.example.ui.viewmodel.RefinePreviewMode.ON_BLACK to R.string.refine_view_black,
                        com.example.ui.viewmodel.RefinePreviewMode.ON_WHITE to R.string.refine_view_white,
                        com.example.ui.viewmodel.RefinePreviewMode.MASK to R.string.refine_view_mask
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = previewMode == mode,
                            onClick = { onPreviewModeChange(mode) },
                            label = { Text(stringResource(label), fontSize = 11.sp) },
                            modifier = Modifier.heightIn(min = 36.dp)
                        )
                    }
                }

                // Refine brushes (Photoshop's Refine Edge Brush, Brush to add / subtract).
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        null to R.string.refine_brush_move,
                        com.example.ui.viewmodel.RefineBrushMode.REFINE to R.string.refine_brush_refine,
                        com.example.ui.viewmodel.RefineBrushMode.ADD to R.string.refine_brush_add,
                        com.example.ui.viewmodel.RefineBrushMode.SUBTRACT to R.string.refine_brush_subtract
                    ).forEach { (m, label) ->
                        FilterChip(
                            selected = brushMode == m,
                            onClick = { brushMode = m },
                            label = { Text(stringResource(label), fontSize = 11.sp) },
                            modifier = Modifier.heightIn(min = 36.dp).testTag("refine_brush_${m?.name?.lowercase() ?: "move"}")
                        )
                    }
                    TextButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1) }, enabled = strokes.isNotEmpty()) {
                        Text(stringResource(R.string.refine_brush_undo), fontSize = 11.sp)
                    }
                    TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) {
                        Text(stringResource(R.string.refine_brush_clear), fontSize = 11.sp)
                    }
                }
                if (brushMode != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.refine_brush_size), color = Color.LightGray, fontSize = 11.sp)
                        Slider(value = brushDp, onValueChange = { brushDp = it }, valueRange = 6f..120f, modifier = Modifier.weight(1f))
                        Text("${brushDp.toInt()} dp", color = IndigoPrimary, fontSize = 11.sp)
                    }
                    Text(
                        stringResource(
                            when (brushMode) {
                                com.example.ui.viewmodel.RefineBrushMode.REFINE -> R.string.refine_brush_refine_hint
                                com.example.ui.viewmodel.RefineBrushMode.ADD -> R.string.refine_brush_add_hint
                                else -> R.string.refine_brush_subtract_hint
                            }
                        ),
                        color = Color.Gray, fontSize = 10.sp
                    )
                }

                // Controls
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Edge detection & hair
                    RefineCard {
                        Text(stringResource(R.string.refine_edge_detection), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        RefineSliderItem(
                            label = stringResource(R.string.refine_radius),
                            valueLabel = if (cfg.radiusPx <= 0) stringResource(R.string.refine_off) else "${cfg.radiusPx} px",
                            value = cfg.radiusPx.toFloat(),
                            range = 0f..64f,
                            onValueChange = { cfg = cfg.copy(radiusPx = round(it).toInt()) }
                        )
                        RefineSwitchRow(
                            title = stringResource(R.string.refine_smart_radius),
                            description = null,
                            checked = cfg.smartRadius,
                            enabled = cfg.radiusPx > 0,
                            color = IndigoPrimary
                        ) { cfg = cfg.copy(smartRadius = it) }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                        RefineSwitchRow(
                            title = stringResource(R.string.refine_hair),
                            description = stringResource(R.string.refine_hair_desc),
                            checked = cfg.hairRefine,
                            enabled = cfg.radiusPx > 0,
                            color = PinkAccent,
                            icon = true
                        ) { cfg = cfg.copy(hairRefine = it) }
                        RefineSwitchRow(
                            title = stringResource(R.string.refine_ai_assist),
                            description = stringResource(R.string.refine_ai_assist_desc),
                            checked = cfg.aiAssist,
                            color = PinkAccent,
                            icon = true
                        ) { cfg = cfg.copy(aiAssist = it) }
                    }

                    // 2. Global refinements
                    RefineCard {
                        Text(stringResource(R.string.refine_global_adjustments), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        RefineSliderItem(
                            label = stringResource(R.string.refine_smooth),
                            valueLabel = pxLabel(cfg.smoothPx),
                            value = cfg.smoothPx,
                            range = 0f..(maxPx / 2f),
                            onValueChange = { cfg = cfg.copy(smoothPx = it) }
                        )
                        RefineSliderItem(
                            label = stringResource(R.string.refine_feather),
                            valueLabel = pxLabel(cfg.featherPx),
                            value = cfg.featherPx,
                            range = 0f..maxPx,
                            onValueChange = { cfg = cfg.copy(featherPx = it) }
                        )
                        RefineSliderItem(
                            label = stringResource(R.string.refine_contrast),
                            valueLabel = "${round(cfg.contrastPercent).toInt()}%",
                            value = cfg.contrastPercent,
                            range = 0f..100f,
                            onValueChange = { cfg = cfg.copy(contrastPercent = it) }
                        )
                        RefineSliderItem(
                            label = stringResource(R.string.refine_shift_edge),
                            valueLabel = (if (cfg.shiftEdgePx > 0.05f) "+" else "") + pxLabel(cfg.shiftEdgePx),
                            value = cfg.shiftEdgePx,
                            range = -maxPx..maxPx,
                            onValueChange = { cfg = cfg.copy(shiftEdgePx = it) }
                        )
                    }

                    // 3. Clean-up & output
                    RefineCard {
                        Text(stringResource(R.string.refine_output_section), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        RefineSwitchRow(
                            title = stringResource(R.string.select_cleanup),
                            description = null,
                            checked = cfg.removeSpecks,
                            color = IndigoPrimary
                        ) { cfg = cfg.copy(removeSpecks = it) }
                        RefineSliderItem(
                            label = stringResource(R.string.select_border),
                            valueLabel = if (cfg.borderPx < 1f) stringResource(R.string.refine_off) else pxLabel(cfg.borderPx),
                            value = cfg.borderPx,
                            range = 0f..maxPx,
                            onValueChange = { cfg = cfg.copy(borderPx = if (it < 1f) 0f else it) }
                        )
                    }

                    // 4. Output To
                    RefineCard {
                        Text(stringResource(R.string.refine_output_to), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                com.example.engine.RefineOutput.SELECTION to R.string.refine_out_selection,
                                com.example.engine.RefineOutput.LAYER_MASK to R.string.refine_out_layer_mask,
                                com.example.engine.RefineOutput.NEW_LAYER to R.string.refine_out_new_layer,
                                com.example.engine.RefineOutput.NEW_LAYER_WITH_MASK to R.string.refine_out_new_layer_mask
                            ).forEach { (o, label) ->
                                FilterChip(selected = cfg.output == o, onClick = { cfg = cfg.copy(output = o) }, label = { Text(stringResource(label), fontSize = 11.sp) })
                            }
                        }
                    }

                    // 5. Decontaminate colours
                    RefineCard {
                        RefineSwitchRow(
                            title = stringResource(R.string.refine_decontaminate),
                            description = stringResource(R.string.refine_decontaminate_desc),
                            checked = cfg.decontaminateColors,
                            enabled = cfg.radiusPx > 0,
                            color = CyanAccent
                        ) { cfg = cfg.copy(decontaminateColors = it) }
                        if (cfg.decontaminateColors && cfg.radiusPx > 0) {
                            RefineSliderItem(
                                label = stringResource(R.string.refine_decontaminate_amount),
                                valueLabel = "${(cfg.decontaminateAmount * 100).toInt()}%",
                                value = cfg.decontaminateAmount,
                                range = 0.1f..1.0f,
                                onValueChange = { cfg = cfg.copy(decontaminateAmount = it) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Bottom actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { cfg = RefineEdgeConfig(); strokes.clear() },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("btn_reset_refine_edge")
                    ) {
                        Text(stringResource(R.string.action_reset), fontSize = 12.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.action_cancel), fontSize = 12.sp, maxLines = 1)
                    }
                    Button(
                        onClick = { onApply(cfg, strokes.toList()) },
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                        modifier = Modifier.weight(1.3f).heightIn(min = 48.dp).testTag("btn_apply_refine_edge")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_done), fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun pxLabel(v: Float): String {
    val a = kotlin.math.abs(v)
    val shown = if (a < 10f) (round(v * 10f) / 10f).toString() else round(v).toInt().toString()
    return "$shown px"
}

@Composable
private fun RefineCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = StudioSurfaceVariant.copy(alpha = 0.65f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun RefineSwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean = true,
    color: Color,
    icon: Boolean = false,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(title, color = if (enabled) Color.White else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (description != null) Text(description, color = Color.Gray, fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = color, checkedTrackColor = color.copy(alpha = 0.4f))
        )
    }
}

@Composable
private fun RefineSliderItem(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 11.sp)
            Text(valueLabel, color = IndigoPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = IndigoPrimary, activeTrackColor = IndigoPrimary)
        )
    }
}
