package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.R
import com.example.data.model.BlendModeType
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.MaskGradient
import com.example.engine.LayerRenderer
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Layer-panel actions added for reordering, renaming, clipping and advanced mask editing. */
class LayerPanelActions(
    val onToggleVisibilityAt: (Int) -> Unit = {},
    val onToggleLockAt: (Int) -> Unit = {},
    val onMoveLayer: (from: Int, to: Int) -> Unit = { _, _ -> },
    val onRenameLayer: (Int, String) -> Unit = { _, _ -> },
    val onToggleClip: () -> Unit = {},
    val onToggleMaskLink: () -> Unit = {},
    val onSelectLayerPixels: (Int) -> Unit = {},
    val onMaskGradient: (MaskGradient) -> Unit = {},
    val onMaskBrushHardnessChange: (Float) -> Unit = {},
    val onMaskBrushOpacityChange: (Float) -> Unit = {}
)

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
    actions: LayerPanelActions = LayerPanelActions(),
    maskBrushHardness: Float = 0.6f,
    maskBrushOpacity: Float = 1f,
    /** Document size, for thumbnails that show the layer as placed on the canvas. */
    documentWidth: Int = 0,
    documentHeight: Int = 0,
    modifier: Modifier = Modifier
) {
    var showAddMenu by remember { mutableStateOf(false) }
    var showBlendMenu by remember { mutableStateOf(false) }
    var showActionsMenu by remember { mutableStateOf(false) }
    var showMaskMenu by remember { mutableStateOf(false) }
    var renameIndex by remember { mutableStateOf<Int?>(null) }
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
                        Text("${(selectedLayer.opacity * 100).roundToInt()}%", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                                text = {
                                    Text(
                                        stringResource(mode.resId),
                                        fontWeight = if (mode == selectedLayer.blendMode) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
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
                            if (selectedLayer.maskBitmap == null && !hasActiveSelection) {
                                onAddLayerMask?.invoke(false)
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
                                    Text(stringResource(if (selectedLayer.isMaskLinked) R.string.layer_mask_unlink else R.string.layer_mask_link))
                                },
                                leadingIcon = { Icon(if (selectedLayer.isMaskLinked) Icons.Default.LinkOff else Icons.Default.Link, null) },
                                onClick = { showMaskMenu = false; actions.onToggleMaskLink() }
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
                                text = { Text(stringResource(R.string.layer_select_pixels)) },
                                leadingIcon = { Icon(Icons.Default.HighlightAlt, null) },
                                onClick = { showMaskMenu = false; actions.onSelectLayerPixels(selectedIndex) }
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
                            text = { Text(stringResource(R.string.action_rename)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { showActionsMenu = false; renameIndex = selectedIndex }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(if (selectedLayer.isClipped) R.string.layer_clip_release else R.string.layer_clip_create)) },
                            leadingIcon = { Icon(Icons.Default.SubdirectoryArrowRight, null) },
                            enabled = selectedLayer.isClipped || selectedIndex > 0,
                            onClick = { showActionsMenu = false; actions.onToggleClip() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.layer_select_pixels)) },
                            leadingIcon = { Icon(Icons.Default.HighlightAlt, null) },
                            onClick = { showActionsMenu = false; actions.onSelectLayerPixels(selectedIndex) }
                        )
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
                MaskControls(
                    layer = selectedLayer,
                    showMaskRubyOverlay = showMaskRubyOverlay,
                    isMaskEditingBlack = isMaskEditingBlack,
                    maskBrushSizeDp = maskBrushSizeDp,
                    maskBrushHardness = maskBrushHardness,
                    maskBrushOpacity = maskBrushOpacity,
                    hasActiveSelection = hasActiveSelection,
                    onToggleRubyOverlay = onToggleRubyOverlay,
                    onSetEditingMask = onSetEditingMask,
                    onMaskDensityChange = onMaskDensityChange,
                    onMaskFeatherChange = onMaskFeatherChange,
                    onCommit = onOpacityCommit,
                    onToggleMaskPaintColor = onToggleMaskPaintColor,
                    onInvertLayerMask = onInvertLayerMask,
                    onMaskBrushSizeChange = onMaskBrushSizeChange,
                    actions = actions
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.layer_reorder_hint),
            color = Color.Gray,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        // Drag-to-reorder state (list is shown top layer first).
        var draggingId by remember { mutableStateOf<String?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        var rowPitchPx by remember { mutableFloatStateOf(0f) }
        val spacingPx = with(LocalDensity.current) { 6.dp.toPx() }
        val latestLayers by rememberUpdatedState(layers)

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            itemsIndexed(layers.reversed(), key = { _, layer -> layer.id }) { revIdx, layer ->
                val actualIdx = layers.size - 1 - revIdx
                val isSelected = actualIdx == selectedIndex
                val isDragging = draggingId == layer.id
                val layerId = layer.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragging) dragOffset else 0f
                            shadowElevation = if (isDragging) 12f else 0f
                        }
                        .onSizeChanged { rowPitchPx = it.height + spacingPx }
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected || isDragging) StudioSurfaceVariant else Color.Transparent)
                        .border(if (isSelected) 1.5.dp else 0.dp, if (isSelected) IndigoPrimary else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { onSelectLayer(actualIdx) }
                        .padding(start = 0.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                        .padding(start = if (layer.isClipped) 14.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Drag handle: drag up/down to reorder.
                    Box(
                        modifier = Modifier
                            .size(width = 32.dp, height = 48.dp)
                            .pointerInput(layerId) {
                                detectVerticalDragGestures(
                                    onDragStart = { draggingId = layerId; dragOffset = 0f },
                                    onVerticalDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount
                                    },
                                    onDragEnd = {
                                        val list = latestLayers
                                        val from = list.indexOfFirst { it.id == layerId }
                                        val pitch = if (rowPitchPx > 1f) rowPitchPx else 1f
                                        // Screen order is reversed: dragging down moves the layer lower.
                                        val steps = (dragOffset / pitch).roundToInt()
                                        val to = (from - steps).coerceIn(0, list.size - 1)
                                        draggingId = null
                                        dragOffset = 0f
                                        if (from >= 0 && to != from) actions.onMoveLayer(from, to)
                                    },
                                    onDragCancel = { draggingId = null; dragOffset = 0f }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.DragHandle, contentDescription = stringResource(R.string.cd_layer_drag), tint = Color.Gray, modifier = Modifier.size(20.dp))
                    }

                    if (layer.isClipped) {
                        Icon(
                            Icons.Default.SubdirectoryArrowRight,
                            contentDescription = stringResource(R.string.cd_layer_clipped),
                            tint = IndigoPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                    }

                    // Layer Content Thumbnail
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2A2C34))
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
                        LayerThumbnailContent(layer, documentWidth, documentHeight)
                    }

                    // Layer Mask Thumbnail (if present), with a chain link that toggles linking.
                    if (layer.maskBitmap != null) {
                        IconButton(
                            onClick = {
                                if (actualIdx != selectedIndex) onSelectLayer(actualIdx)
                                actions.onToggleMaskLink()
                            },
                            modifier = Modifier.size(width = 24.dp, height = 46.dp)
                        ) {
                            Icon(
                                if (layer.isMaskLinked) Icons.Default.Link else Icons.Default.LinkOff,
                                contentDescription = stringResource(if (layer.isMaskLinked) R.string.layer_mask_unlink else R.string.layer_mask_link),
                                tint = if (layer.isMaskLinked) Color.LightGray else Color.Gray,
                                modifier = Modifier.size(14.dp)
                            )
                        }
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
                            val maskThumb = rememberMaskThumbnail(layer, documentWidth, documentHeight)
                            if (maskThumb != null) {
                                Image(
                                    bitmap = maskThumb,
                                    contentDescription = stringResource(R.string.layer_mask_title),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
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

                    Spacer(Modifier.width(8.dp))
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
                                else -> buildString {
                                    append(
                                        stringResource(
                                            when (layer.type) {
                                                LayerType.IMAGE -> R.string.layer_add_image
                                                LayerType.TEXT -> R.string.layer_add_text
                                                LayerType.ADJUSTMENT -> R.string.layer_add_adjustment
                                                else -> R.string.layer_color_fill
                                            }
                                        )
                                    )
                                    if (layer.blendMode != BlendModeType.NORMAL) append(" · ").append(stringResource(layer.blendMode.resId))
                                    if (layer.opacity < 0.995f) append(" · ").append((layer.opacity * 100).roundToInt()).append('%')
                                }
                            },
                            color = if (layer.maskBitmap != null && layer.isEditingMask) IndigoPrimary else Color.Gray,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { actions.onToggleVisibilityAt(actualIdx) }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (layer.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = stringResource(R.string.layer_visibility),
                            tint = if (layer.isVisible) Color.LightGray else Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = { actions.onToggleLockAt(actualIdx) }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (layer.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = stringResource(R.string.layer_lock),
                            tint = if (layer.isLocked) MaterialTheme.colorScheme.error else Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    renameIndex?.let { idx ->
        val layer = layers.getOrNull(idx)
        if (layer == null) {
            renameIndex = null
        } else {
            var name by remember(layer.id) { mutableStateOf(layer.name) }
            AlertDialog(
                onDismissRequest = { renameIndex = null },
                title = { Text(stringResource(R.string.layer_rename_title)) },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(60) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().testTag("field_layer_name")
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { actions.onRenameLayer(idx, name); renameIndex = null },
                        enabled = name.isNotBlank()
                    ) { Text(stringResource(R.string.action_rename)) }
                },
                dismissButton = {
                    TextButton(onClick = { renameIndex = null }) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }
    }
}

@Composable
private fun MaskControls(
    layer: Layer,
    showMaskRubyOverlay: Boolean,
    isMaskEditingBlack: Boolean,
    maskBrushSizeDp: Float,
    maskBrushHardness: Float,
    maskBrushOpacity: Float,
    hasActiveSelection: Boolean,
    onToggleRubyOverlay: (() -> Unit)?,
    onSetEditingMask: ((Boolean) -> Unit)?,
    onMaskDensityChange: ((Float) -> Unit)?,
    onMaskFeatherChange: ((Float) -> Unit)?,
    onCommit: (() -> Unit)?,
    onToggleMaskPaintColor: (() -> Unit)?,
    onInvertLayerMask: (() -> Unit)?,
    onMaskBrushSizeChange: ((Float) -> Unit)?,
    actions: LayerPanelActions
) {
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
                        selected = layer.isEditingMask,
                        onClick = { onSetEditingMask?.invoke(!layer.isEditingMask) },
                        label = {
                            Text(
                                if (layer.isEditingMask) stringResource(R.string.layer_mask_editing_mask) else stringResource(R.string.layer_mask_editing_layer),
                                fontSize = 10.sp
                            )
                        },
                        modifier = Modifier.heightIn(min = 32.dp).testTag("chip_editing_mask")
                    )
                }
            }

            MaskSlider(
                label = stringResource(R.string.layer_mask_density),
                valueLabel = "${(layer.maskDensity * 100).roundToInt()}%",
                value = layer.maskDensity,
                range = 0f..1f,
                onChange = { onMaskDensityChange?.invoke(it) },
                onFinished = { onCommit?.invoke() },
                tag = "slider_mask_density"
            )
            MaskSlider(
                label = stringResource(R.string.layer_mask_feather),
                valueLabel = "${layer.maskFeather.roundToInt()} px",
                value = layer.maskFeather,
                range = 0f..50f,
                onChange = { onMaskFeatherChange?.invoke(it) },
                onFinished = { onCommit?.invoke() },
                tag = "slider_mask_feather"
            )

            // Gradient: revealed -> hidden, limited to the selection when one is active.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(if (hasActiveSelection) R.string.layer_mask_gradient_in_selection else R.string.layer_mask_gradient),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                listOf(
                    Triple(MaskGradient.TOP_TO_BOTTOM, Icons.Default.ArrowDownward, R.string.mask_gradient_down),
                    Triple(MaskGradient.BOTTOM_TO_TOP, Icons.Default.ArrowUpward, R.string.mask_gradient_up),
                    Triple(MaskGradient.LEFT_TO_RIGHT, Icons.Default.ArrowForward, R.string.mask_gradient_right),
                    Triple(MaskGradient.RIGHT_TO_LEFT, Icons.Default.ArrowBack, R.string.mask_gradient_left),
                    Triple(MaskGradient.RADIAL, Icons.Default.RadioButtonUnchecked, R.string.mask_gradient_radial)
                ).forEach { (type, icon, label) ->
                    IconButton(onClick = { actions.onMaskGradient(type) }, modifier = Modifier.size(40.dp)) {
                        Icon(icon, contentDescription = stringResource(label), tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Editing Mask Toolbar
            if (layer.isEditingMask) {
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
                            modifier = Modifier.size(40.dp).testTag("btn_invert_mask")
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

                MaskSlider(
                    label = stringResource(R.string.layer_mask_brush_size),
                    valueLabel = "${maskBrushSizeDp.roundToInt()} dp",
                    value = maskBrushSizeDp,
                    range = 2f..120f,
                    onChange = { onMaskBrushSizeChange?.invoke(it) },
                    tag = "slider_mask_brush_size"
                )
                MaskSlider(
                    label = stringResource(R.string.layer_mask_hardness),
                    valueLabel = "${(maskBrushHardness * 100).roundToInt()}%",
                    value = maskBrushHardness,
                    range = 0f..1f,
                    onChange = actions.onMaskBrushHardnessChange,
                    tag = "slider_mask_brush_hardness"
                )
                MaskSlider(
                    label = stringResource(R.string.layer_mask_opacity),
                    valueLabel = "${(maskBrushOpacity * 100).roundToInt()}%",
                    value = maskBrushOpacity,
                    range = 0.05f..1f,
                    onChange = actions.onMaskBrushOpacityChange,
                    tag = "slider_mask_brush_opacity"
                )
            }
        }
    }
}

@Composable
private fun MaskSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: (() -> Unit)? = null,
    tag: String
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 11.sp)
        Text(valueLabel, color = IndigoPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        onValueChangeFinished = { onFinished?.invoke() },
        valueRange = range,
        modifier = Modifier.height(28.dp).testTag(tag)
    )
}

private const val THUMB_SIZE = 96

/**
 * Small thumbnail of the layer as placed on the canvas (transform, adjustments, filter), without
 * its mask, which has its own thumbnail. Rendered off the main thread and debounced while sliders move.
 */
@Composable
private fun rememberLayerThumbnail(layer: Layer, docW: Int, docH: Int): ImageBitmap? {
    val context = LocalContext.current
    val snapshot = layer.previewSnapshot().also { it.isMaskEnabled = false }
    val key = snapshot.hashCode() xor (layer.bitmap?.generationId ?: 0)
    val state = produceState<ImageBitmap?>(initialValue = null, layer.id, key, docW, docH) {
        delay(120)
        val w = if (docW > 0) docW else layer.bitmap?.width ?: return@produceState
        val h = if (docH > 0) docH else layer.bitmap?.height ?: return@produceState
        val ratio = THUMB_SIZE.toFloat() / max(w, h).toFloat()
        val tw = (w * ratio).roundToInt().coerceAtLeast(1)
        val th = (h * ratio).roundToInt().coerceAtLeast(1)
        value = try {
            withContext(Dispatchers.Default) {
                LayerRenderer.renderSingleLayerToBitmap(context, snapshot, tw, th, ratio)?.asImageBitmap()
            }
        } catch (e: OutOfMemoryError) {
            null
        }
    }
    return state.value
}

/** Black/white thumbnail of the mask (white = revealed), placed where the mask currently is. */
@Composable
private fun rememberMaskThumbnail(layer: Layer, docW: Int, docH: Int): ImageBitmap? {
    val mask = layer.maskBitmap
    val anchorKey = LayerRenderer.linkedMaskMatrix(layer, max(docW, 1), max(docH, 1))?.let { m ->
        FloatArray(9).also { m.getValues(it) }.contentHashCode()
    } ?: 0
    val state = produceState<ImageBitmap?>(initialValue = null, mask, mask?.generationId, anchorKey, docW, docH) {
        if (mask == null || mask.isRecycled) { value = null; return@produceState }
        delay(80)
        val w = if (docW > 0) docW else mask.width
        val h = if (docH > 0) docH else mask.height
        val ratio = THUMB_SIZE.toFloat() / max(w, h).toFloat()
        val tw = (w * ratio).roundToInt().coerceAtLeast(1)
        val th = (h * ratio).roundToInt().coerceAtLeast(1)
        value = withContext(Dispatchers.Default) {
            if (mask.isRecycled) return@withContext null
            val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            out.eraseColor(android.graphics.Color.BLACK)
            val moved = LayerRenderer.linkedMaskMatrix(layer, w, h)
            val m = AndroidMatrix().apply {
                setScale(w.toFloat() / mask.width, h.toFloat() / mask.height)
                if (moved != null) postConcat(moved)
                postScale(tw.toFloat() / w, th.toFloat() / h)
            }
            val canvas = AndroidCanvas(out)
            if (moved != null) {
                // Area the moved mask no longer covers is revealed, as rendered.
                canvas.drawColor(android.graphics.Color.WHITE)
                val hole = AndroidPaint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
                val r = android.graphics.RectF(0f, 0f, mask.width.toFloat(), mask.height.toFloat())
                m.mapRect(r)
                canvas.drawRect(r, hole)
                canvas.drawColor(android.graphics.Color.BLACK, android.graphics.PorterDuff.Mode.DST_OVER)
            }
            // An ALPHA_8 bitmap is drawn in the paint colour: white where the mask reveals.
            canvas.drawBitmap(mask, m, AndroidPaint(AndroidPaint.FILTER_BITMAP_FLAG).apply { color = android.graphics.Color.WHITE })
            out.asImageBitmap()
        }
    }
    return state.value
}

@Composable
private fun LayerThumbnailContent(layer: Layer, docW: Int, docH: Int) {
    when (layer.type) {
        LayerType.ADJUSTMENT -> Icon(Icons.Default.Tune, null, tint = IndigoPrimary)
        else -> {
            val thumb = rememberLayerThumbnail(layer, docW, docH)
            if (thumb != null) {
                Image(thumb, contentDescription = stringResource(R.string.layer_thumbnail), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                when (layer.type) {
                    LayerType.TEXT -> Text("T", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    LayerType.IMAGE -> Icon(Icons.Default.Image, null, tint = Color.Gray)
                    else -> Icon(Icons.Default.Layers, null, tint = Color.Gray)
                }
            }
        }
    }
}
