package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.BlendModeType
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant

@Composable
fun LayersPanel(
    layers: List<Layer>,
    selectedIndex: Int,
    onSelectLayer: (Int) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onOpacityCommit: (() -> Unit)? = null,
    onBlendModeChange: (BlendModeType) -> Unit,
    onToggleVisibility: () -> Unit,
    onToggleLock: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMergeDown: () -> Unit,
    onAddImageClick: () -> Unit,
    onAddTextClick: (String) -> Unit,
    onAddAdjustmentClick: () -> Unit = {},
    onAddLayerMask: ((Boolean) -> Unit)? = null,
    onInvertLayerMask: (() -> Unit)? = null,
    onToggleLayerMaskEnabled: (() -> Unit)? = null,
    onDeleteLayerMask: (() -> Unit)? = null,
    onApplyLayerMask: (() -> Unit)? = null,
    onSetEditingMask: ((Boolean) -> Unit)? = null,
    onToggleMaskPaintColor: (() -> Unit)? = null,
    isMaskEditingBlack: Boolean = true,
    hasActiveSelection: Boolean = false,
    onMaskDensityChange: ((Float) -> Unit)? = null,
    onMaskFeatherChange: ((Float) -> Unit)? = null,
    onRevealAllMask: (() -> Unit)? = null,
    onHideAllMask: (() -> Unit)? = null,
    onToggleRubyOverlay: (() -> Unit)? = null,
    showMaskRubyOverlay: Boolean = false,
    maskBrushSizeDp: Float = 24f,
    onMaskBrushSizeChange: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showAddMenu by remember { mutableStateOf(false) }
    var showBlendMenu by remember { mutableStateOf(false) }
    var showActionsMenu by remember { mutableStateOf(false) }
    var showMaskMenu by remember { mutableStateOf(false) }
    val selectedLayer = layers.getOrNull(selectedIndex)

    Column(modifier.fillMaxWidth().fillMaxHeight().background(StudioSurface).padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.layers_title), style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
            Box {
                IconButton(
                    onClick = { showAddMenu = true },
                    modifier = Modifier.size(48.dp).testTag("btn_add_layer")
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_layer), tint = IndigoPrimary)
                }
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layer_add_image)) },
                        leadingIcon = { Icon(Icons.Default.Image, null) },
                        onClick = { showAddMenu = false; onAddImageClick() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layer_add_text)) },
                        leadingIcon = { Icon(Icons.Default.TextFields, null) },
                        onClick = { showAddMenu = false; onAddTextClick("") }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layer_add_adjustment)) },
                        leadingIcon = { Icon(Icons.Default.Tune, null) },
                        onClick = { showAddMenu = false; onAddAdjustmentClick() }
                    )
                }
            }
        }

        if (selectedLayer != null) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.layer_opacity), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text("${(selectedLayer.opacity * 100).toInt()}%", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = selectedLayer.opacity,
                        onValueChange = onOpacityChange,
                        onValueChangeFinished = { onOpacityCommit?.invoke() },
                        valueRange = 0f..1f,
                        modifier = Modifier.testTag("slider_layer_opacity")
                    )
                }
                Box {
                    OutlinedButton(
                        onClick = { showBlendMenu = true },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("btn_blend_mode"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(selectedLayer.blendMode.resId), fontSize = 12.sp, maxLines = 1)
                    }
                    DropdownMenu(expanded = showBlendMenu, onDismissRequest = { showBlendMenu = false }) {
                        BlendModeType.getAvailableModes().forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(stringResource(mode.resId)) },
                                onClick = { showBlendMenu = false; onBlendModeChange(mode) }
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Layer Mask Action Button
                Box {
                    IconButton(
                        onClick = {
                            if (selectedLayer.maskBitmap == null) {
                                if (hasActiveSelection) {
                                    showMaskMenu = true
                                } else {
                                    onAddLayerMask?.invoke(false)
                                }
                            } else {
                                showMaskMenu = true
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            if (selectedLayer.maskBitmap != null) Icons.Default.FilterFrames else Icons.Default.AddBox,
                            contentDescription = stringResource(R.string.layer_mask_title),
                            tint = if (selectedLayer.maskBitmap != null) {
                                if (selectedLayer.isEditingMask) IndigoPrimary else Color.White
                            } else Color.LightGray
                        )
                    }
                    DropdownMenu(expanded = showMaskMenu, onDismissRequest = { showMaskMenu = false }) {
                        if (selectedLayer.maskBitmap == null) {
                            if (hasActiveSelection) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.layer_mask_from_selection)) },
                                    leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                                    onClick = { showMaskMenu = false; onAddLayerMask?.invoke(true) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.layer_mask_add)) },
                                leadingIcon = { Icon(Icons.Default.AddBox, null) },
                                onClick = { showMaskMenu = false; onAddLayerMask?.invoke(false) }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.layer_mask_invert)) },
                                leadingIcon = { Icon(Icons.Default.InvertColors, null) },
                                onClick = { showMaskMenu = false; onInvertLayerMask?.invoke() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.layer_mask_reveal_all)) },
                                leadingIcon = { Icon(Icons.Default.Check, null) },
                                onClick = { showMaskMenu = false; onRevealAllMask?.invoke() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.layer_mask_hide_all)) },
                                leadingIcon = { Icon(Icons.Default.Close, null) },
                                onClick = { showMaskMenu = false; onHideAllMask?.invoke() }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (selectedLayer.isMaskEnabled) R.string.layer_mask_disable else R.string.layer_mask_enable
                                        )
                                    )
                                },
                                leadingIcon = { Icon(if (selectedLayer.isMaskEnabled) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) },
                                onClick = { showMaskMenu = false; onToggleLayerMaskEnabled?.invoke() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.layer_mask_apply)) },
                                leadingIcon = { Icon(Icons.Default.CheckCircle, null) },
                                onClick = { showMaskMenu = false; onApplyLayerMask?.invoke() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.layer_mask_delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { showMaskMenu = false; onDeleteLayerMask?.invoke() }
                            )
                        }
                    }
                }

                IconButton(onClick = onToggleVisibility, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (selectedLayer.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = stringResource(R.string.layer_visibility),
                        tint = if (selectedLayer.isVisible) IndigoPrimary else Color.Gray
                    )
                }
                IconButton(onClick = onToggleLock, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (selectedLayer.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = stringResource(R.string.layer_lock),
                        tint = if (selectedLayer.isLocked) MaterialTheme.colorScheme.error else Color.White
                    )
                }
                Box {
                    IconButton(onClick = { showActionsMenu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.layer_more_actions))
                    }
                    DropdownMenu(expanded = showActionsMenu, onDismissRequest = { showActionsMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.layer_move_up)) },
                            leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                            enabled = selectedIndex < layers.size - 1,
                            onClick = { showActionsMenu = false; onMoveUp() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.layer_move_down)) },
                            leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                            enabled = selectedIndex > 0,
                            onClick = { showActionsMenu = false; onMoveDown() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.layer_duplicate)) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = { showActionsMenu = false; onDuplicate() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.layer_merge_down)) },
                            leadingIcon = { Icon(Icons.Default.CallMerge, null) },
                            enabled = selectedIndex > 0,
                            onClick = { showActionsMenu = false; onMergeDown() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.layer_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            enabled = layers.size > 1,
                            onClick = { showActionsMenu = false; onDelete() }
                        )
                    }
                }
            }

            // Layer Mask Controls Section (Active whenever layer has a mask)
            if (selectedLayer.maskBitmap != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = StudioSurfaceVariant
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.layer_mask_controls),
                                color = IndigoPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = showMaskRubyOverlay,
                                    onClick = { onToggleRubyOverlay?.invoke() },
                                    label = { Text(stringResource(R.string.layer_mask_overlay), fontSize = 10.sp) },
                                    modifier = Modifier.heightIn(min = 32.dp).testTag("chip_ruby_overlay")
                                )
                                FilterChip(
                                    selected = selectedLayer.isEditingMask,
                                    onClick = { onSetEditingMask?.invoke(!selectedLayer.isEditingMask) },
                                    label = {
                                        Text(
                                            if (selectedLayer.isEditingMask) stringResource(R.string.layer_mask_editing_mask) else stringResource(R.string.layer_mask_editing_layer),
                                            fontSize = 10.sp
                                        )
                                    },
                                    modifier = Modifier.heightIn(min = 32.dp).testTag("chip_editing_mask")
                                )
                            }
                        }

                        // Mask Density Slider
                        Row(
                            Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.layer_mask_density), color = Color.White, fontSize = 11.sp)
                            Text("${(selectedLayer.maskDensity * 100).toInt()}%", color = IndigoPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = selectedLayer.maskDensity,
                            onValueChange = { onMaskDensityChange?.invoke(it) },
                            valueRange = 0f..1f,
                            modifier = Modifier.height(28.dp).testTag("slider_mask_density")
                        )

                        // Mask Feather Slider
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.layer_mask_feather), color = Color.White, fontSize = 11.sp)
                            Text("${selectedLayer.maskFeather.toInt()} px", color = IndigoPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = selectedLayer.maskFeather,
                            onValueChange = { onMaskFeatherChange?.invoke(it) },
                            valueRange = 0f..30f,
                            modifier = Modifier.height(28.dp).testTag("slider_mask_feather")
                        )

                        // Editing Mask Toolbar
                        if (selectedLayer.isEditingMask) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    FilterChip(
                                        selected = isMaskEditingBlack,
                                        onClick = { onToggleMaskPaintColor?.invoke() },
                                        label = {
                                            Text(
                                                if (isMaskEditingBlack) stringResource(R.string.layer_mask_paint_black) else stringResource(R.string.layer_mask_paint_white),
                                                fontSize = 10.sp
                                            )
                                        },
                                        modifier = Modifier.heightIn(min = 32.dp)
                                    )
                                    IconButton(
                                        onClick = { onInvertLayerMask?.invoke() },
                                        modifier = Modifier.size(32.dp).testTag("btn_invert_mask")
                                    ) {
                                        Icon(Icons.Default.InvertColors, contentDescription = stringResource(R.string.layer_mask_invert), tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                                TextButton(
                                    onClick = { onSetEditingMask?.invoke(false) },
                                    modifier = Modifier.heightIn(min = 32.dp)
                                ) {
                                    Text(stringResource(R.string.action_done), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            itemsIndexed(layers.reversed(), key = { _, layer -> layer.id }) { revIdx, layer ->
                val actualIdx = layers.size - 1 - revIdx
                val isSelected = actualIdx == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) StudioSurfaceVariant else Color.Transparent)
                        .border(if (isSelected) 1.5.dp else 0.dp, if (isSelected) IndigoPrimary else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { onSelectLayer(actualIdx) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Layer Content Thumbnail
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.3f))
                            .border(
                                width = if (isSelected && !layer.isEditingMask) 2.dp else 1.dp,
                                color = if (isSelected && !layer.isEditingMask) IndigoPrimary else Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                onSelectLayer(actualIdx)
                                onSetEditingMask?.invoke(false)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        LayerThumbnailContent(layer)
                    }

                    // Layer Mask Thumbnail (if present)
                    if (layer.maskBitmap != null) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Link,
                            contentDescription = "Linked",
                            tint = Color.Gray,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black)
                                .border(
                                    width = if (isSelected && layer.isEditingMask) 2.dp else 1.dp,
                                    color = if (isSelected && layer.isEditingMask) Color.White else Color.White.copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    onSelectLayer(actualIdx)
                                    onSetEditingMask?.invoke(true)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val maskBmp = layer.maskBitmap
                            if (maskBmp != null && !maskBmp.isRecycled) {
                                Image(
                                    bitmap = maskBmp.asImageBitmap(),
                                    contentDescription = stringResource(R.string.layer_mask_title),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            if (!layer.isMaskEnabled) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.layer_mask_disable),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            layer.name,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            when {
                                layer.maskBitmap != null && layer.isEditingMask -> stringResource(R.string.layer_mask_editing_mask)
                                layer.type == LayerType.IMAGE -> stringResource(R.string.layer_add_image)
                                layer.type == LayerType.TEXT -> stringResource(R.string.layer_add_text)
                                layer.type == LayerType.ADJUSTMENT -> stringResource(R.string.layer_add_adjustment)
                                else -> stringResource(R.string.layer_color_fill)
                            },
                            color = if (layer.maskBitmap != null && layer.isEditingMask) IndigoPrimary else Color.Gray,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                    if (!layer.isVisible) Icon(Icons.Default.VisibilityOff, stringResource(R.string.cd_layer_hidden), tint = Color.Gray, modifier = Modifier.size(18.dp))
                    if (layer.isLocked) Icon(Icons.Default.Lock, stringResource(R.string.layer_lock), tint = Color.Gray, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun LayerThumbnailContent(layer: Layer) {
    when (layer.type) {
        LayerType.IMAGE -> {
            val bmp = layer.bitmap
            if (bmp != null && !bmp.isRecycled) {
                Image(bmp.asImageBitmap(), contentDescription = stringResource(R.string.layer_thumbnail), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else Icon(Icons.Default.Image, null, tint = Color.Gray)
        }
        LayerType.TEXT -> Text("T", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        LayerType.ADJUSTMENT -> Icon(Icons.Default.Tune, null, tint = IndigoPrimary)
        else -> Icon(Icons.Default.Layers, null, tint = Color.Gray)
    }
}
