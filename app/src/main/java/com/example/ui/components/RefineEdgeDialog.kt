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
    onApply: (RefineEdgeConfig) -> Unit,
    /** Live preview of the refined selection (null while computing / unavailable). */
    preview: android.graphics.Bitmap? = null,
    /** The same area before refinement (shown while Before is held). */
    previewOriginal: android.graphics.Bitmap? = null,
    previewMode: com.example.ui.viewmodel.RefinePreviewMode = com.example.ui.viewmodel.RefinePreviewMode.OVERLAY,
    onPreviewModeChange: (com.example.ui.viewmodel.RefinePreviewMode) -> Unit = {},
    onRequestPreview: (RefineEdgeConfig) -> Unit = {},
    /** Longest side of the image: slider ranges scale with it. */
    documentMaxDim: Int = 2000
) {
    var cfg by remember { mutableStateOf(initialConfig) }
    val maxPx = max(20f, documentMaxDim / 25f)

    // Every change re-renders the preview (the ViewModel debounces briefly).
    LaunchedEffect(cfg, previewMode) { onRequestPreview(cfg) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var showBefore by remember { mutableStateOf(false) }

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
                        .pointerInput(Unit) {
                            detectTransformGestures { _, panChange, zoomChange, _ ->
                                zoom = (zoom * zoomChange).coerceIn(1f, 8f)
                                pan = if (zoom <= 1.001f) Offset.Zero else pan + panChange
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

                    // 4. Decontaminate colours
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
                        onClick = { cfg = RefineEdgeConfig() },
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
                        onClick = { onApply(cfg) },
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
