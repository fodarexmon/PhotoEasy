package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.graphics.Color
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

@Composable
fun RefineEdgeDialog(
    initialConfig: RefineEdgeConfig = RefineEdgeConfig(),
    onDismiss: () -> Unit,
    onApply: (RefineEdgeConfig) -> Unit,
    /** Live preview of the refined selection (null while computing / unavailable). */
    preview: android.graphics.Bitmap? = null,
    previewMode: com.example.ui.viewmodel.RefinePreviewMode = com.example.ui.viewmodel.RefinePreviewMode.OVERLAY,
    onPreviewModeChange: (com.example.ui.viewmodel.RefinePreviewMode) -> Unit = {},
    onRequestPreview: (RefineEdgeConfig) -> Unit = {}
) {
    var radiusPx by remember { mutableFloatStateOf(initialConfig.radiusPx.toFloat()) }
    var smartRadius by remember { mutableStateOf(initialConfig.smartRadius) }
    var hairRefine by remember { mutableStateOf(initialConfig.hairRefine) }
    var smoothPx by remember { mutableFloatStateOf(initialConfig.smoothPx.toFloat()) }
    var featherPx by remember { mutableFloatStateOf(initialConfig.featherPx) }
    var contrastPercent by remember { mutableFloatStateOf(initialConfig.contrastPercent) }
    var shiftEdgePercent by remember { mutableFloatStateOf(initialConfig.shiftEdgePercent) }
    var decontaminate by remember { mutableStateOf(initialConfig.decontaminateColors) }
    var decontamAmount by remember { mutableFloatStateOf(initialConfig.decontaminateAmount) }

    fun currentConfig() = RefineEdgeConfig(
        radiusPx = radiusPx.toInt(),
        smartRadius = smartRadius,
        hairRefine = hairRefine,
        smoothPx = smoothPx.toInt(),
        featherPx = featherPx,
        contrastPercent = contrastPercent,
        shiftEdgePercent = shiftEdgePercent,
        decontaminateColors = decontaminate,
        decontaminateAmount = decontamAmount
    )

    // Every change re-renders the preview (the ViewModel debounces).
    LaunchedEffect(radiusPx, smartRadius, hairRefine, smoothPx, featherPx, contrastPercent, shiftEdgePercent, decontaminate, decontamAmount, previewMode) {
        onRequestPreview(currentConfig())
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
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
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
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel), tint = Color.LightGray)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Live preview of the selected area with the current settings
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (preview != null && !preview.isRecycled) {
                        androidx.compose.foundation.Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = stringResource(R.string.refine_preview),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(4.dp)
                        )
                    } else {
                        CircularProgressIndicator(color = IndigoPrimary, modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
                            label = { Text(stringResource(label), fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Scrollable Controls
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Edge Detection & Hair Extraction Card
                    Surface(
                        color = StudioSurfaceVariant.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.refine_radius) + ": ${radiusPx.toInt()} px",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Slider(
                                value = radiusPx,
                                onValueChange = { radiusPx = it },
                                valueRange = 1f..40f,
                                colors = SliderDefaults.colors(thumbColor = IndigoPrimary, activeTrackColor = IndigoPrimary)
                            )

                            // Smart Radius Switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.refine_smart_radius), color = Color.White, fontSize = 12.sp)
                                Switch(
                                    checked = smartRadius,
                                    onCheckedChange = { smartRadius = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = IndigoPrimary)
                                )
                            }

                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                            // Hair Refine Switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = PinkAccent, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.refine_hair), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Text(stringResource(R.string.refine_hair_desc), color = Color.Gray, fontSize = 10.sp)
                                }
                                Switch(
                                    checked = hairRefine,
                                    onCheckedChange = { hairRefine = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = PinkAccent, checkedTrackColor = PinkAccent.copy(alpha = 0.4f))
                                )
                            }
                        }
                    }

                    // 2. Global Edge Polish Card
                    Surface(
                        color = StudioSurfaceVariant.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.refine_global_adjustments), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)

                            // Smooth
                            RefineSliderItem(
                                label = stringResource(R.string.refine_smooth),
                                valueLabel = "${smoothPx.toInt()} px",
                                value = smoothPx,
                                range = 0f..20f,
                                onValueChange = { smoothPx = it }
                            )

                            // Feather
                            RefineSliderItem(
                                label = stringResource(R.string.refine_feather),
                                valueLabel = "${featherPx.toInt()} px",
                                value = featherPx,
                                range = 0f..20f,
                                onValueChange = { featherPx = it }
                            )

                            // Contrast
                            RefineSliderItem(
                                label = stringResource(R.string.refine_contrast),
                                valueLabel = "${contrastPercent.toInt()}%",
                                value = contrastPercent,
                                range = 0f..100f,
                                onValueChange = { contrastPercent = it }
                            )

                            // Shift Edge
                            RefineSliderItem(
                                label = stringResource(R.string.refine_shift_edge),
                                valueLabel = "${shiftEdgePercent.toInt()}%",
                                value = shiftEdgePercent,
                                range = -100f..100f,
                                onValueChange = { shiftEdgePercent = it }
                            )
                        }
                    }

                    // 3. Decontaminate Colors Card
                    Surface(
                        color = StudioSurfaceVariant.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.refine_decontaminate), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(stringResource(R.string.refine_decontaminate_desc), color = Color.Gray, fontSize = 10.sp)
                                }
                                Switch(
                                    checked = decontaminate,
                                    onCheckedChange = { decontaminate = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = CyanAccent, checkedTrackColor = CyanAccent.copy(alpha = 0.4f))
                                )
                            }

                            if (decontaminate) {
                                RefineSliderItem(
                                    label = stringResource(R.string.refine_decontaminate_amount),
                                    valueLabel = "${(decontamAmount * 100).toInt()}%",
                                    value = decontamAmount,
                                    range = 0.1f..1.0f,
                                    onValueChange = { decontamAmount = it }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Bottom Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.action_cancel), fontSize = 12.sp)
                    }
                    Button(
                        onClick = { onApply(currentConfig()) },
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("btn_apply_refine_edge")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_done), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
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
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = IndigoPrimary, activeTrackColor = IndigoPrimary)
        )
    }
}
