package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.SelectionMode
import com.example.engine.InpaintEngineMode
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.PinkAccent
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import com.example.ui.viewmodel.InpaintSelectionMode

@Composable
fun InpaintPanel(
    brushSizeDp: Float,
    inpaintRadiusPx: Float,
    featherPx: Int,
    selectionMode: SelectionMode,
    inpaintSelectionMode: InpaintSelectionMode = InpaintSelectionMode.BRUSH,
    engineMode: InpaintEngineMode = InpaintEngineMode.AI_PATCH_SYNTHESIS,
    smartEdgeSnap: Boolean = true,
    seamlessBlend: Boolean = true,
    canUndoMask: Boolean,
    onBrushSizeChange: (Float) -> Unit,
    onRadiusChange: (Float) -> Unit,
    onFeatherChange: (Int) -> Unit,
    onMaskModeChange: (SelectionMode) -> Unit,
    onInpaintSelectionModeChange: (InpaintSelectionMode) -> Unit = {},
    onEngineModeChange: (InpaintEngineMode) -> Unit = {},
    onToggleSmartEdgeSnap: () -> Unit = {},
    onToggleSeamlessBlend: () -> Unit = {},
    onClearMask: () -> Unit,
    onUndoMask: () -> Unit,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .background(StudioSurface)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.inpaint_title),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    stringResource(R.string.inpaint_mode_hint),
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 1. Inpaint Algorithm Selector (AI Texture Synthesis vs Fast Marching)
        Text(
            stringResource(R.string.inpaint_engine_title),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = engineMode == InpaintEngineMode.AI_PATCH_SYNTHESIS,
                onClick = { onEngineModeChange(InpaintEngineMode.AI_PATCH_SYNTHESIS) },
                label = {
                    Text(
                        stringResource(R.string.inpaint_engine_ai),
                        fontSize = 11.sp,
                        fontWeight = if (engineMode == InpaintEngineMode.AI_PATCH_SYNTHESIS) FontWeight.Bold else FontWeight.Normal
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (engineMode == InpaintEngineMode.AI_PATCH_SYNTHESIS) PinkAccent else Color.Gray
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PinkAccent.copy(alpha = 0.2f),
                    selectedLabelColor = Color.White
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = engineMode == InpaintEngineMode.AI_PATCH_SYNTHESIS,
                    borderColor = if (engineMode == InpaintEngineMode.AI_PATCH_SYNTHESIS) PinkAccent else Color.DarkGray
                ),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("chip_inpaint_ai")
            )

            FilterChip(
                selected = engineMode == InpaintEngineMode.FAST_MARCHING,
                onClick = { onEngineModeChange(InpaintEngineMode.FAST_MARCHING) },
                label = {
                    Text(
                        stringResource(R.string.inpaint_engine_telea),
                        fontSize = 11.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (engineMode == InpaintEngineMode.FAST_MARCHING) CyanAccent else Color.Gray
                    )
                },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("chip_inpaint_telea")
            )
        }

        // Subtitle badge describing the active engine
        Surface(
            color = StudioSurfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) {
            Text(
                text = if (engineMode == InpaintEngineMode.AI_PATCH_SYNTHESIS) {
                    "✨ " + stringResource(R.string.inpaint_engine_desc_ai)
                } else {
                    "⚡ " + stringResource(R.string.inpaint_engine_desc_telea)
                },
                color = Color.LightGray,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        // 2. Intelligent Enhancements: Smart Edge Snapping & Seamless Poisson Blending
        Surface(
            color = StudioSurfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Smart Edge Snapping Switch Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.inpaint_smart_snap),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.inpaint_smart_snap_hint),
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = smartEdgeSnap,
                        onCheckedChange = { onToggleSmartEdgeSnap() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PinkAccent,
                            checkedTrackColor = PinkAccent.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.testTag("switch_smart_edge_snap")
                    )
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                // Seamless Poisson Blending Switch Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.inpaint_seamless_blend),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.inpaint_seamless_blend_hint),
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = seamlessBlend,
                        onCheckedChange = { onToggleSeamlessBlend() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyanAccent,
                            checkedTrackColor = CyanAccent.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.testTag("switch_seamless_blend")
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 3. Tool Selection Modes (Brush, Lasso, Rectangle)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = inpaintSelectionMode == InpaintSelectionMode.BRUSH,
                onClick = { onInpaintSelectionModeChange(InpaintSelectionMode.BRUSH) },
                label = { Text(stringResource(R.string.inpaint_mode_brush), fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(14.dp)) },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
            )
            FilterChip(
                selected = inpaintSelectionMode == InpaintSelectionMode.LASSO,
                onClick = { onInpaintSelectionModeChange(InpaintSelectionMode.LASSO) },
                label = { Text(stringResource(R.string.inpaint_mode_lasso), fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Gesture, contentDescription = null, modifier = Modifier.size(14.dp)) },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
            )
            FilterChip(
                selected = inpaintSelectionMode == InpaintSelectionMode.RECTANGLE,
                onClick = { onInpaintSelectionModeChange(InpaintSelectionMode.RECTANGLE) },
                label = { Text(stringResource(R.string.inpaint_mode_box), fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.CropSquare, contentDescription = null, modifier = Modifier.size(14.dp)) },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        // 4. Mask Operation (Add / Subtract / Undo / Clear)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectionMode != SelectionMode.SUBTRACT,
                onClick = { onMaskModeChange(SelectionMode.ADD) },
                label = { Text(stringResource(R.string.inpaint_add_mask), fontSize = 12.sp) },
                modifier = Modifier.heightIn(min = 44.dp)
            )
            FilterChip(
                selected = selectionMode == SelectionMode.SUBTRACT,
                onClick = { onMaskModeChange(SelectionMode.SUBTRACT) },
                label = { Text(stringResource(R.string.inpaint_erase_mask), fontSize = 12.sp) },
                modifier = Modifier.heightIn(min = 44.dp)
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onUndoMask, enabled = canUndoMask, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.inpaint_undo_mask), tint = if (canUndoMask) Color.White else Color.Gray)
            }
            IconButton(onClick = onClearMask, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.inpaint_clear_mask), tint = Color(0xFFEF4444))
            }
        }

        if (inpaintSelectionMode == InpaintSelectionMode.BRUSH) {
            InpaintSlider(stringResource(R.string.inpaint_brush), "${brushSizeDp.toInt()} dp", brushSizeDp, 8f..140f, onBrushSizeChange)
        }
        InpaintSlider(stringResource(R.string.inpaint_radius), "${inpaintRadiusPx.toInt()} px", inpaintRadiusPx, 2f..15f, onRadiusChange)
        InpaintSlider(stringResource(R.string.inpaint_feather), "$featherPx px", featherPx.toFloat(), 0f..24f) { onFeatherChange(it.toInt()) }

        Spacer(Modifier.height(12.dp))

        // High-contrast Prominent Remove Action Button
        Button(
            onClick = onRemoveClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("btn_inpaint_execute"),
            colors = ButtonDefaults.buttonColors(containerColor = PinkAccent)
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.inpaint_remove_now), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun InpaintSlider(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 12.sp)
            Text(valueLabel, color = PinkAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
