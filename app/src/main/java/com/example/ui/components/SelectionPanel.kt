package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ExpandMore
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
import com.example.R
import com.example.data.model.SelectionMode
import com.example.data.model.SelectionToolType
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.PinkAccent
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant

@Composable
fun SelectionPanel(
    selectedTool: SelectionToolType,
    selectionMode: SelectionMode,
    brushSizeDp: Float,
    tolerance: Int,
    isContiguous: Boolean = true,
    isQuickAutoEnhance: Boolean = true,
    onToggleQuickAutoEnhance: (Boolean) -> Unit = {},
    isQuickSampleAll: Boolean = false,
    onToggleQuickSampleAll: (Boolean) -> Unit = {},
    canUndoSelection: Boolean,
    canRedoSelection: Boolean,
    onToolSelect: (SelectionToolType) -> Unit,
    onModeSelect: (SelectionMode) -> Unit,
    onBrushSizeChange: (Float) -> Unit,
    onToleranceChange: (Int) -> Unit,
    onToggleContiguous: (Boolean) -> Unit = {},
    onFeatherClick: (Float) -> Unit,
    onGrowClick: (Int) -> Unit,
    onShrinkClick: (Int) -> Unit,
    onSmoothClick: (Int) -> Unit,
    onRefineClick: () -> Unit,
    onSelectAllClick: () -> Unit,
    onInvertClick: () -> Unit,
    onClearClick: () -> Unit,
    onCopyLayerClick: () -> Unit,
    onUndoSelection: () -> Unit,
    onRedoSelection: () -> Unit,
    onSelectSubject: () -> Unit = {},
    onOpenRefineEdgeDialog: () -> Unit = {},
    isMagneticSnap: Boolean = false,
    onToggleMagneticSnap: () -> Unit = {},
    isQuickMaskView: Boolean = false,
    onToggleQuickMask: () -> Unit = {},
    shapeFromCenter: Boolean = false,
    onToggleShapeFromCenter: (Boolean) -> Unit = {},
    shapeLockSquare: Boolean = false,
    onToggleShapeLockSquare: (Boolean) -> Unit = {},
    modifierOp: com.example.ui.viewmodel.SelectionModifierOp = com.example.ui.viewmodel.SelectionModifierOp.FEATHER,
    modifierAmount: Float = 5f,
    onModifierPreview: (com.example.ui.viewmodel.SelectionModifierOp, Float) -> Unit = { _, _ -> },
    onModifierCommit: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showRefine by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .background(StudioSurface)
            .padding(vertical = 10.dp)
    ) {
        // --- 1. Intelligent AI Selection & Hair Refine Super Bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // One-Tap Intelligent Subject Detection
            Button(
                onClick = onSelectSubject,
                colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .testTag("btn_select_subject")
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.select_subject),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Professional Edge & Hair Matting Dialog trigger
            Button(
                onClick = onOpenRefineEdgeDialog,
                colors = ButtonDefaults.buttonColors(containerColor = PinkAccent),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .testTag("btn_open_refine_edge")
            ) {
                Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.select_refine_advanced),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // --- 2. Tool Type Selection Bar ---
        LazyToolRow {
            SelectionToolChip(stringResource(R.string.select_quick_selection_short), selectedTool == SelectionToolType.QUICK_SELECTION) { onToolSelect(SelectionToolType.QUICK_SELECTION) }
            SelectionToolChip(stringResource(R.string.select_brush), selectedTool == SelectionToolType.QUICK_BRUSH) { onToolSelect(SelectionToolType.QUICK_BRUSH) }
            SelectionToolChip(stringResource(R.string.select_rectangle), selectedTool == SelectionToolType.RECTANGLE) { onToolSelect(SelectionToolType.RECTANGLE) }
            SelectionToolChip(stringResource(R.string.select_ellipse), selectedTool == SelectionToolType.ELLIPSE) { onToolSelect(SelectionToolType.ELLIPSE) }
            SelectionToolChip(stringResource(R.string.select_lasso_short), selectedTool == SelectionToolType.LASSO) { onToolSelect(SelectionToolType.LASSO) }
            SelectionToolChip(stringResource(R.string.select_polygon_short), selectedTool == SelectionToolType.POLYGON) { onToolSelect(SelectionToolType.POLYGON) }
            SelectionToolChip(stringResource(R.string.select_wand_short), selectedTool == SelectionToolType.MAGIC_WAND) { onToolSelect(SelectionToolType.MAGIC_WAND) }
            SelectionToolChip(stringResource(R.string.select_move_selection), selectedTool == SelectionToolType.MOVE_SELECTION) { onToolSelect(SelectionToolType.MOVE_SELECTION) }
        }

        Spacer(Modifier.height(6.dp))

        // --- 3. Selection Mode (Replace, Add, Subtract, Intersect) ---
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SelectionMode.values().forEach { mode ->
                FilterChip(
                    selected = mode == selectionMode,
                    onClick = { onModeSelect(mode) },
                    label = {
                        Text(
                            text = when (mode) {
                                SelectionMode.REPLACE -> stringResource(R.string.select_mode_replace)
                                SelectionMode.ADD -> stringResource(R.string.select_mode_add)
                                SelectionMode.SUBTRACT -> stringResource(R.string.select_mode_subtract)
                                SelectionMode.INTERSECT -> stringResource(R.string.select_mode_intersect)
                            },
                            fontSize = 12.sp
                        )
                    }
                )
            }
        }

        // --- 4. Tool-Specific Controls ---
        if (selectedTool == SelectionToolType.QUICK_SELECTION) {
            Text(
                stringResource(R.string.select_quick_mode_hint),
                color = Color.LightGray,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
            SelectionSlider(
                label = stringResource(R.string.select_brush_size),
                valueLabel = brushSizeDp.toInt().toString(),
                value = brushSizeDp,
                valueRange = 8f..140f,
                onValueChange = onBrushSizeChange
            )
            SelectionSlider(
                label = stringResource(R.string.select_tolerance),
                valueLabel = tolerance.toString(),
                value = tolerance.toFloat(),
                valueRange = 5f..100f,
                onValueChange = { onToleranceChange(it.toInt()) }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = isQuickAutoEnhance,
                    onClick = { onToggleQuickAutoEnhance(!isQuickAutoEnhance) },
                    label = { Text(stringResource(R.string.select_auto_enhance), fontSize = 12.sp) }
                )
                FilterChip(
                    selected = isQuickSampleAll,
                    onClick = { onToggleQuickSampleAll(!isQuickSampleAll) },
                    label = { Text(stringResource(R.string.select_sample_all_layers), fontSize = 12.sp) }
                )
            }
        }

        if (selectedTool == SelectionToolType.QUICK_BRUSH) {
            SelectionSlider(
                label = stringResource(R.string.select_brush_size),
                valueLabel = brushSizeDp.toInt().toString(),
                value = brushSizeDp,
                valueRange = 4f..120f,
                onValueChange = onBrushSizeChange
            )
        }

        if (selectedTool == SelectionToolType.RECTANGLE || selectedTool == SelectionToolType.ELLIPSE) {
            Text(
                stringResource(R.string.select_shape_hint),
                color = Color.LightGray,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = shapeFromCenter,
                    onClick = { onToggleShapeFromCenter(!shapeFromCenter) },
                    label = { Text(stringResource(R.string.select_shape_from_center), fontSize = 12.sp) }
                )
                FilterChip(
                    selected = shapeLockSquare,
                    onClick = { onToggleShapeLockSquare(!shapeLockSquare) },
                    label = {
                        Text(
                            stringResource(if (selectedTool == SelectionToolType.ELLIPSE) R.string.select_shape_circle else R.string.select_shape_square),
                            fontSize = 12.sp
                        )
                    }
                )
            }
        }

        if (selectedTool == SelectionToolType.POLYGON) {
            Text(
                stringResource(R.string.select_polygon_hint),
                color = Color.LightGray,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
        }

        if (selectedTool == SelectionToolType.MOVE_SELECTION) {
            Text(
                stringResource(R.string.select_move_hint),
                color = Color.LightGray,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
        }

        if (selectedTool == SelectionToolType.LASSO || selectedTool == SelectionToolType.POLYGON) {
            // Magnetic Edge Snap toggle (points snap live to nearby edges while drawing)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.refine_magnetic_snap), color = Color.White, fontSize = 12.sp)
                Switch(
                    checked = isMagneticSnap,
                    onCheckedChange = { onToggleMagneticSnap() },
                    colors = SwitchDefaults.colors(checkedThumbColor = CyanAccent)
                )
            }
        }

        if (selectedTool == SelectionToolType.MAGIC_WAND) {
            Text(
                stringResource(R.string.select_wand_drag_hint),
                color = Color.LightGray,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
            SelectionSlider(
                label = stringResource(R.string.select_tolerance),
                valueLabel = tolerance.toString(),
                value = tolerance.toFloat(),
                valueRange = 1f..100f,
                onValueChange = { onToleranceChange(it.toInt()) }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = isContiguous,
                    onClick = { onToggleContiguous(true) },
                    label = { Text(stringResource(R.string.select_wand_contiguous), fontSize = 12.sp) }
                )
                FilterChip(
                    selected = !isContiguous,
                    onClick = { onToggleContiguous(false) },
                    label = { Text(stringResource(R.string.select_wand_all_colors), fontSize = 12.sp) }
                )
            }
        }

        // Quick Refine & Undo/Redo Action Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { showRefine = !showRefine },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
            ) {
                Text(stringResource(R.string.selection_refine_group), fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ExpandMore, contentDescription = null)
            }
            IconButton(onClick = onUndoSelection, enabled = canUndoSelection, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.selection_undo))
            }
            IconButton(onClick = onRedoSelection, enabled = canRedoSelection, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.selection_redo))
            }
        }

        AnimatedVisibility(showRefine) {
            Column {
                // Pick the operation, then drag the slider: the selection updates live and the whole
                // drag becomes a single undo step when released.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val ops = listOf(
                        com.example.ui.viewmodel.SelectionModifierOp.FEATHER to R.string.select_feather,
                        com.example.ui.viewmodel.SelectionModifierOp.GROW to R.string.select_grow,
                        com.example.ui.viewmodel.SelectionModifierOp.SHRINK to R.string.select_shrink,
                        com.example.ui.viewmodel.SelectionModifierOp.SMOOTH to R.string.select_smooth
                    )
                    ops.forEach { (op, label) ->
                        FilterChip(
                            selected = modifierOp == op,
                            onClick = {
                                // Switching the operation restarts from the current selection.
                                onModifierCommit()
                                onModifierPreview(op, 0f)
                            },
                            label = { Text(stringResource(label), fontSize = 12.sp) }
                        )
                    }
                    RefineButton(stringResource(R.string.select_refine), onRefineClick)
                }
                SelectionSlider(
                    label = stringResource(R.string.select_edge_radius),
                    valueLabel = "${modifierAmount.toInt()} px",
                    value = modifierAmount,
                    valueRange = 0f..if (modifierOp == com.example.ui.viewmodel.SelectionModifierOp.SMOOTH) 12f else 50f,
                    onValueChange = { onModifierPreview(modifierOp, it) },
                    onValueChangeFinished = onModifierCommit
                )
            }
        }

        // Output and Mask Management Buttons
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = isQuickMaskView,
                onClick = onToggleQuickMask,
                label = { Text(stringResource(R.string.select_quick_mask_view), fontSize = 12.sp) },
                modifier = Modifier.heightIn(min = 44.dp).testTag("chip_quick_mask")
            )
            OutlinedButton(onClick = onSelectAllClick, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.select_all), fontSize = 12.sp) }
            OutlinedButton(onClick = onInvertClick, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.select_invert), fontSize = 12.sp) }
            OutlinedButton(onClick = onClearClick, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.select_clear), fontSize = 12.sp) }
            Button(
                onClick = onCopyLayerClick,
                colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                modifier = Modifier.heightIn(min = 44.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.select_copy_layer), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun LazyToolRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

@Composable
private fun SelectionToolChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, fontSize = 12.sp) },
        modifier = Modifier.heightIn(min = 44.dp)
    )
}

@Composable
private fun SelectionSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 12.sp)
            Text(valueLabel, color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, onValueChangeFinished = onValueChangeFinished)
    }
}

@Composable
private fun RefineButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 44.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) { Text(text, fontSize = 12.sp) }
}
