package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.IndigoLight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
    /** Long-press on the eye: show only this layer / bring the others back (Alt+click in Photoshop). */
    val onSoloLayerAt: (Int) -> Unit = {},
    val onToggleLockAt: (Int) -> Unit = {},
    val onMoveLayer: (from: Int, to: Int) -> Unit = { _, _ -> },
    val onRenameLayer: (Int, String) -> Unit = { _, _ -> },
    val onToggleClip: () -> Unit = {},
    val onToggleMaskLink: () -> Unit = {},
    val onSelectLayerPixels: (Int) -> Unit = {},
    val onMaskGradient: (MaskGradient) -> Unit = {},
    val onMaskBrushHardnessChange: (Float) -> Unit = {},
    val onMaskBrushOpacityChange: (Float) -> Unit = {},
    // Several layers at once (long press a layer) and groups.
    val multiSelect: Boolean = false,
    val pickedIds: Set<String> = emptySet(),
    val onSetMultiSelect: (Boolean) -> Unit = {},
    val onTogglePicked: (String) -> Unit = {},
    val onPickAll: (Boolean) -> Unit = {},
    val onDeletePicked: () -> Unit = {},
    val onDuplicatePicked: () -> Unit = {},
    val onSetPickedVisible: (Boolean) -> Unit = {},
    val onSetPickedLocked: (Boolean) -> Unit = {},
    val onMergePicked: () -> Unit = {},
    val onGroup: () -> Unit = {},
    val onUngroup: () -> Unit = {},
    val onToggleCollapsed: (String) -> Unit = {},
    val onMergeVisible: () -> Unit = {},
    val onFlatten: () -> Unit = {},
    /** 0..5 = left, centre, right, top, middle, bottom; 6 / 7 = distribute horizontally / vertically. */
    val onAlign: (Int) -> Unit = {},
    /** Opens Blend If for the selected layer. */
    val onBlendIf: () -> Unit = {},
    /** Opens the settings of the adjustment layer at this index. */
    val onEditAdjustment: (Int) -> Unit = {},
    // Smart Objects: convert / rasterize, and the Smart Filters (layer id, filter id).
    val onConvertToSmartObject: () -> Unit = {},
    val onRasterize: () -> Unit = {},
    val onEditSmartFilter: (String, String) -> Unit = { _, _ -> },
    val onToggleSmartFilter: (String, String) -> Unit = { _, _ -> },
    val onRemoveSmartFilter: (String, String) -> Unit = { _, _ -> },
    val onSetSmartFiltersVisible: (String, Boolean) -> Unit = { _, _ -> },
    val onSmartMaskFromSelection: (String) -> Unit = {},
    val onInvertSmartMask: (String) -> Unit = {},
    val onClearSmartMask: (String) -> Unit = {},
    /** Opens the Effects panel (to add a Smart Filter). */
    val onOpenEffects: () -> Unit = {},
    val onMoveSmartFilterUp: (String, String) -> Unit = { _, _ -> }
)

/** Align / distribute choices, in [LayerPanelActions.onAlign] order. */
private val alignChoices = listOf(
    R.string.align_left to Icons.Default.AlignHorizontalLeft,
    R.string.align_h_center to Icons.Default.AlignHorizontalCenter,
    R.string.align_right to Icons.Default.AlignHorizontalRight,
    R.string.align_top to Icons.Default.AlignVerticalTop,
    R.string.align_v_center to Icons.Default.AlignVerticalCenter,
    R.string.align_bottom to Icons.Default.AlignVerticalBottom,
    R.string.distribute_h to Icons.Default.ViewColumn,
    R.string.distribute_v to Icons.Default.TableRows
)

@Composable
private fun AlignMenu(expanded: Boolean, onDismiss: () -> Unit, onAlign: (Int) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        alignChoices.forEachIndexed { i, (label, icon) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                leadingIcon = { Icon(icon, null) },
                onClick = { onDismiss(); onAlign(i) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    onAddAdjustmentClick: (com.example.data.model.AdjustmentKind) -> Unit = {},
    onAddEmptyClick: () -> Unit = {},
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
    var showAlignMenu by remember { mutableStateOf(false) }
    var renameIndex by remember { mutableStateOf<Int?>(null) }
    val selectedLayer = layers.getOrNull(selectedIndex)

    var showAdjustmentPicker by remember { mutableStateOf(false) }
    if (showAdjustmentPicker) {
        AdjustmentKindPickerDialog(
            onPick = { kind -> showAdjustmentPicker = false; onAddAdjustmentClick(kind) },
            onDismiss = { showAdjustmentPicker = false }
        )
    }
    // Flexible height: as tall as the layers need (up to ~42% of the screen) until the user drags
    // the handle; then the chosen height (kept while the editor is open).
    val density = LocalDensity.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val maxPanel = screenH * 0.75f
    val minPanel = 150.dp
    var userHeight by rememberSaveable { mutableStateOf<Float?>(null) } // dp
    var measuredPx by remember { mutableIntStateOf(0) }
    val handleLabel = stringResource(R.string.layers_panel_resize)
    val sized = userHeight?.let { Modifier.height(it.dp.coerceIn(minPanel, maxPanel)) } ?: Modifier.wrapContentHeight()
    Column(
        modifier.fillMaxWidth().then(sized).onSizeChanged { measuredPx = it.height }
            .background(StudioSurface).padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
    ) {
        // Drag handle: drag up for a taller panel, down for a smaller one; double tap resets.
        Box(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            val cur = userHeight ?: with(density) { measuredPx.toDp().value }
                            userHeight = (cur - with(density) { amount.toDp().value }).coerceIn(minPanel.value, maxPanel.value)
                        }
                    )
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { userHeight = null }) }
                .semantics { contentDescription = handleLabel }
                .testTag("layers_panel_handle"),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(StudioBorder))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.layers_title), style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                "${layers.size}",
                color = Color.LightGray,
                fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(StudioSurfaceVariant).padding(horizontal = 8.dp, vertical = 1.dp)
            )
            Spacer(Modifier.weight(1f))
            Box {
                Button(
                    onClick = { showAddMenu = true },
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(36.dp).testTag("btn_add_layer")
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_layer), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.layer_add_button), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layer_add_empty)) },
                        leadingIcon = { Icon(Icons.Default.Layers, null) },
                        onClick = { showAddMenu = false; onAddEmptyClick() },
                        modifier = Modifier.testTag("menu_add_empty_layer")
                    )
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
                        onClick = { showAddMenu = false; showAdjustmentPicker = true }
                    )
                }
            }
        }

        if (actions.multiSelect) {
            MultiSelectBar(layers, actions)
        } else if (selectedLayer != null) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(stringResource(R.string.layer_opacity), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                ThinSlider(
                    value = selectedLayer.opacity,
                    onValueChange = onOpacityChange,
                    onValueChangeFinished = { onOpacityCommit?.invoke() },
                    modifier = Modifier.weight(1f).testTag("slider_layer_opacity")
                )
                Text(
                    "${(selectedLayer.opacity * 100).roundToInt()}%",
                    color = IndigoLight, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(38.dp)
                )
                Box {
                    OutlinedButton(
                        onClick = { showBlendMenu = true },
                        modifier = Modifier.height(32.dp).testTag("btn_blend_mode"),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(stringResource(selectedLayer.blendMode.resId), fontSize = 11.sp, maxLines = 1)
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

                // Clipping mask (Photoshop's "Create Clipping Mask"): the layer shows only where the
                // layer below it has pixels. Not possible for the bottom layer.
                val canClip = selectedLayer.isClipped || selectedIndex > 0
                IconButton(
                    onClick = { actions.onToggleClip() },
                    enabled = canClip,
                    modifier = Modifier.size(48.dp).testTag("btn_toggle_clip")
                ) {
                    Icon(
                        Icons.Default.SubdirectoryArrowRight,
                        contentDescription = stringResource(if (selectedLayer.isClipped) R.string.layer_clip_release else R.string.layer_clip_create),
                        tint = when {
                            selectedLayer.isClipped -> IndigoLight
                            canClip -> Color.White
                            else -> Color.Gray.copy(alpha = 0.5f)
                        }
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
                        if (selectedLayer.type == LayerType.IMAGE) {
                            if (!selectedLayer.isSmartObject) DropdownMenuItem(
                                text = { Text(stringResource(R.string.smart_object_convert)) },
                                leadingIcon = { Icon(Icons.Default.AutoAwesomeMotion, null) },
                                onClick = { showActionsMenu = false; actions.onConvertToSmartObject() },
                                modifier = Modifier.testTag("menu_smart_object")
                            ) else DropdownMenuItem(
                                text = { Text(stringResource(R.string.smart_object_rasterize)) },
                                leadingIcon = { Icon(Icons.Default.GridOn, null) },
                                onClick = { showActionsMenu = false; actions.onRasterize() },
                                modifier = Modifier.testTag("menu_rasterize")
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.blend_if_menu)) },
                            leadingIcon = { Icon(Icons.Default.Gradient, null) },
                            onClick = { showActionsMenu = false; actions.onBlendIf() },
                            modifier = Modifier.testTag("menu_blend_if")
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.layers_select_several)) },
                            leadingIcon = { Icon(Icons.Default.DoneAll, null) },
                            onClick = { showActionsMenu = false; actions.onSetMultiSelect(true) },
                            modifier = Modifier.testTag("menu_layers_multi")
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.layer_group)) },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                            onClick = { showActionsMenu = false; actions.onGroup() }
                        )
                        if (selectedLayer.type == LayerType.GROUP) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.layer_ungroup)) },
                                leadingIcon = { Icon(Icons.Default.LayersClear, null) },
                                onClick = { showActionsMenu = false; actions.onUngroup() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.layer_align_to_canvas)) },
                            leadingIcon = { Icon(Icons.Default.AlignHorizontalCenter, null) },
                            onClick = { showActionsMenu = false; showAlignMenu = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.layers_merge_visible)) },
                            leadingIcon = { Icon(Icons.Default.CallMerge, null) },
                            enabled = layers.size > 1,
                            onClick = { showActionsMenu = false; actions.onMergeVisible() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.layers_flatten)) },
                            leadingIcon = { Icon(Icons.Default.Layers, null) },
                            enabled = layers.size > 1,
                            onClick = { showActionsMenu = false; actions.onFlatten() }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.layer_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            enabled = layers.size > 1,
                            onClick = { showActionsMenu = false; onDelete() }
                        )
                    }
                    AlignMenu(showAlignMenu, { showAlignMenu = false }, actions.onAlign)
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

        if (layers.size > 1) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(if (actions.multiSelect) R.string.layers_multi_hint else R.string.layer_reorder_hint),
                color = Color.Gray,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        } else Spacer(Modifier.height(6.dp))

        // Drag-to-reorder state (list is shown top layer first).
        var draggingId by remember { mutableStateOf<String?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        var rowPitchPx by remember { mutableFloatStateOf(0f) }
        val spacingPx = with(LocalDensity.current) { 6.dp.toPx() }
        val latestLayers by rememberUpdatedState(layers)
        // Rows shown: top layer first; members of a folded group are hidden.
        val rows = remember(layers, layers.map { it.isCollapsed to it.parentId }) {
            layers.reversed().filter { !com.example.engine.LayerTree.insideCollapsed(layers, it) }
        }
        val latestRows by rememberUpdatedState(rows)

        LazyColumn(
            modifier = Modifier.fillMaxWidth().then(
                if (userHeight != null) Modifier.weight(1f) else Modifier.heightIn(max = screenH * 0.42f - 120.dp)
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            itemsIndexed(rows, key = { _, layer -> layer.id }) { _, layer ->
                val actualIdx = layers.indexOf(layer)
                val depth = com.example.engine.LayerTree.depth(layers, layer)
                val isPicked = actions.multiSelect && layer.id in actions.pickedIds
                val isSelected = if (actions.multiSelect) isPicked else actualIdx == selectedIndex
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
                        .combinedClickable(
                            onClick = { if (actions.multiSelect) actions.onTogglePicked(layer.id) else onSelectLayer(actualIdx) },
                            // Long press: pick several layers.
                            onLongClick = {
                                if (!actions.multiSelect) { onSelectLayer(actualIdx); actions.onSetMultiSelect(true) }
                                else actions.onTogglePicked(layer.id)
                            }
                        )
                        .padding(start = 0.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                        // Group members and clipped layers are indented, as in Photoshop.
                        .padding(start = (depth * 16).dp + if (layer.isClipped) 22.dp else 0.dp),
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
                                        val shown = latestRows
                                        val from = list.indexOfFirst { it.id == layerId }
                                        val pitch = if (rowPitchPx > 1f) rowPitchPx else 1f
                                        // Rows are top layer first: dragging down moves the layer lower.
                                        val steps = (dragOffset / pitch).roundToInt()
                                        val fromRow = shown.indexOfFirst { it.id == layerId }
                                        val targetRow = (fromRow + steps).coerceIn(0, (shown.size - 1).coerceAtLeast(0))
                                        val to = shown.getOrNull(targetRow)?.let { t -> list.indexOfFirst { it.id == t.id } } ?: from
                                        draggingId = null
                                        dragOffset = 0f
                                        if (from >= 0 && to != from) actions.onMoveLayer(from, to)
                                    },
                                    onDragCancel = { draggingId = null; dragOffset = 0f }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (actions.multiSelect) {
                            Icon(
                                if (isPicked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = stringResource(R.string.cd_selected),
                                tint = if (isPicked) IndigoPrimary else Color.Gray, modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Icon(Icons.Default.DragHandle, contentDescription = stringResource(R.string.cd_layer_drag), tint = Color.Gray, modifier = Modifier.size(20.dp))
                        }
                    }
                    if (layer.type == LayerType.GROUP) {
                        // Fold / unfold the group.
                        IconButton(onClick = { actions.onToggleCollapsed(layer.id) }, modifier = Modifier.size(width = 26.dp, height = 46.dp).testTag("btn_group_fold")) {
                            Icon(
                                if (layer.isCollapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
                                contentDescription = stringResource(if (layer.isCollapsed) R.string.group_expand else R.string.group_collapse),
                                tint = Color.LightGray, modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (layer.isClipped) {
                        // Arrow pointing down to the layer it is clipped to.
                        Icon(
                            Icons.Default.SubdirectoryArrowRight,
                            contentDescription = stringResource(R.string.cd_layer_clipped),
                            tint = IndigoLight,
                            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = 90f }
                        )
                        Spacer(Modifier.width(4.dp))
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
                                // Photoshop: the adjustment layer's icon opens its settings.
                                if (layer.type == LayerType.ADJUSTMENT && !actions.multiSelect) actions.onEditAdjustment(actualIdx)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        LayerThumbnailContent(layer, documentWidth, documentHeight)
                        if (layer.isSmartObject) {
                            // Smart Object badge (bottom corner of the thumbnail).
                            Icon(
                                Icons.Default.AutoAwesomeMotion, contentDescription = stringResource(R.string.smart_object),
                                tint = Color.White,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(14.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp)).padding(1.dp)
                            )
                        }
                    }
                    if (layer.type == LayerType.ADJUSTMENT && !actions.multiSelect) {
                        // Reopen the adjustment's controls at any time.
                        IconButton(
                            onClick = { onSelectLayer(actualIdx); actions.onEditAdjustment(actualIdx) },
                            modifier = Modifier.size(40.dp).testTag("btn_edit_adjustment")
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.cd_edit_adjustment), tint = IndigoPrimary, modifier = Modifier.size(20.dp))
                        }
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
                        // A base of a clipping group has its name underlined (Photoshop).
                        val clipCount = clippedCountAbove(layers, actualIdx)
                        Text(
                            layer.name,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textDecoration = if (clipCount > 0) androidx.compose.ui.text.style.TextDecoration.Underline else null
                        )
                        val clipBase = if (layer.isClipped) clipBaseIndex(layers, actualIdx)?.let { layers[it].name } else null
                        Text(
                            when {
                                layer.maskBitmap != null && layer.isEditingMask -> stringResource(R.string.layer_mask_editing_mask)
                                clipBase != null -> stringResource(R.string.layer_clipped_to, clipBase)
                                clipCount > 0 -> stringResource(R.string.layer_clip_base, clipCount)
                                else -> buildString {
                                    append(
                                        stringResource(
                                            when (layer.type) {
                                                LayerType.GROUP -> R.string.layer_kind_group
                                                LayerType.IMAGE -> R.string.layer_add_image
                                                LayerType.TEXT -> R.string.layer_add_text
                                                LayerType.ADJUSTMENT -> R.string.layer_add_adjustment
                                                else -> R.string.layer_color_fill
                                            }
                                        )
                                    )
                                    if (layer.blendMode != BlendModeType.NORMAL) append(" · ").append(stringResource(layer.blendMode.resId))
                                    if (layer.opacity < 0.995f) append(" · ").append((layer.opacity * 100).roundToInt()).append('%')
                                    if (!layer.blendIf.isIdentity()) append(" · ").append(stringResource(R.string.blend_if_badge))
                                }
                            },
                            color = when {
                                layer.maskBitmap != null && layer.isEditingMask -> IndigoPrimary
                                clipBase != null || clipCount > 0 -> IndigoLight
                                else -> Color.Gray
                            },
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Tap: show / hide. Long-press: show only this layer, again to bring the others back.
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                onClick = { actions.onToggleVisibilityAt(actualIdx) },
                                onLongClick = { actions.onSoloLayerAt(actualIdx) },
                                onClickLabel = stringResource(R.string.layer_visibility),
                                onLongClickLabel = stringResource(R.string.layer_solo)
                            )
                            .testTag("layer_eye_$actualIdx"),
                        contentAlignment = Alignment.Center
                    ) {
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
                // Smart Filters under a Smart Object (Photoshop shows them as sub-rows).
                if (layer.isSmartObject && layer.smartFilters.isNotEmpty() && !actions.multiSelect) {
                    SmartFilterRows(layer, depth, actions)
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
    // Open while the mask is being edited; "Done" folds it to its title row, and the arrow opens
    // or folds it at any time.
    var expanded by rememberSaveable(layer.id) { mutableStateOf(layer.isEditingMask) }
    LaunchedEffect(layer.isEditingMask) { expanded = layer.isEditingMask }
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
            // Title on its own line (tap to fold / open), the two switches on the line below, so
            // long labels never squeeze the title into a narrow column.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { expanded = !expanded }
                    .testTag("mask_controls_toggle")
            ) {
                Text(
                    stringResource(R.string.layer_mask_controls),
                    color = IndigoPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.mask_controls_hide else R.string.mask_controls_show),
                    tint = IndigoPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = showMaskRubyOverlay,
                    onClick = { onToggleRubyOverlay?.invoke() },
                    label = { Text(stringResource(R.string.layer_mask_overlay), fontSize = 10.sp, maxLines = 2) },
                    modifier = Modifier.weight(1f).heightIn(min = 36.dp).testTag("chip_ruby_overlay")
                )
                FilterChip(
                    selected = layer.isEditingMask,
                    onClick = { onSetEditingMask?.invoke(!layer.isEditingMask) },
                    label = {
                        Text(
                            if (layer.isEditingMask) stringResource(R.string.layer_mask_editing_mask) else stringResource(R.string.layer_mask_editing_layer),
                            fontSize = 10.sp, maxLines = 2
                        )
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 36.dp).testTag("chip_editing_mask")
                )
            }

            if (expanded) {
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
        LayerType.GROUP -> Icon(if (layer.isCollapsed) Icons.Default.Folder else Icons.Default.FolderOpen, null, tint = Color(0xFFFFC857))
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


/** Index of the layer [idx] is clipped to: the nearest layer below it that is not clipped itself. */
internal fun clipBaseIndex(layers: List<Layer>, idx: Int): Int? {
    if (idx !in layers.indices || !layers[idx].isClipped) return null
    var j = idx - 1
    while (j >= 0 && layers[j].isClipped) j--
    return j.takeIf { it >= 0 }
}

/** How many layers directly above [idx] are clipped to it (0 when it is not a clipping base). */
internal fun clippedCountAbove(layers: List<Layer>, idx: Int): Int {
    if (idx !in layers.indices || layers[idx].isClipped) return 0
    var n = 0
    var j = idx + 1
    while (j < layers.size && layers[j].isClipped) { n++; j++ }
    return n
}

/** A slim slider (4 dp track, 16 dp thumb) that still has a full-size touch area. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = 0f..1f,
        interactionSource = interaction,
        modifier = modifier.height(32.dp),
        thumb = {
            Box(Modifier.size(16.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.White))
        },
        track = { state ->
            val frac = ((state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val r = size.height / 2f
                drawRoundRect(StudioBorder, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
                val w = size.width * frac
                drawRoundRect(
                    IndigoPrimary,
                    topLeft = androidx.compose.ui.geometry.Offset(if (rtl) size.width - w else 0f, 0f),
                    size = androidx.compose.ui.geometry.Size(w, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
                )
            }
        }
    )
}


/** Multi-select mode: how many layers are picked and what to do with them. */
@Composable
private fun MultiSelectBar(layers: List<Layer>, actions: LayerPanelActions) {
    val picked = layers.filter { it.id in actions.pickedIds }
    val n = picked.size
    var showAlign by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.layers_delete_title, n)) },
            text = { Text(stringResource(R.string.layers_delete_message)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; actions.onDeletePicked() }) {
                    Text(stringResource(R.string.layer_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { actions.onSetMultiSelect(false) }, modifier = Modifier.size(40.dp).testTag("btn_layers_multi_done")) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel), tint = Color.White)
        }
        Text(stringResource(R.string.layers_picked, n), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        val all = n == layers.size
        TextButton(onClick = { actions.onPickAll(!all) }) {
            Text(stringResource(if (all) R.string.projects_select_none else R.string.projects_select_all), fontSize = 12.sp)
        }
    }
    val enabled = n > 0
    val allVisible = picked.all { it.isVisible }
    val allLocked = picked.isNotEmpty() && picked.all { it.isLocked }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        @Composable
        fun Action(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String, on: Boolean = enabled, tint: Color = Color.White, onClick: () -> Unit) {
            OutlinedButton(
                onClick = onClick, enabled = on,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(38.dp).testTag(tag)
            ) {
                Icon(icon, null, modifier = Modifier.size(16.dp), tint = if (on) tint else Color.Gray)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(label), fontSize = 12.sp, color = if (on) tint else Color.Gray)
            }
        }
        Action(R.string.layer_group, Icons.Default.CreateNewFolder, "btn_layers_group") { actions.onGroup() }
        Action(R.string.layers_merge, Icons.Default.CallMerge, "btn_layers_merge",
            on = n >= 2 || picked.singleOrNull()?.type == LayerType.GROUP) { actions.onMergePicked() }
        Action(R.string.layer_duplicate, Icons.Default.ContentCopy, "btn_layers_duplicate") { actions.onDuplicatePicked() }
        Action(if (allVisible) R.string.layers_hide else R.string.layers_show,
            if (allVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "btn_layers_visible") { actions.onSetPickedVisible(!allVisible) }
        Action(if (allLocked) R.string.layers_unlock else R.string.layers_lock,
            if (allLocked) Icons.Default.LockOpen else Icons.Default.Lock, "btn_layers_lock") { actions.onSetPickedLocked(!allLocked) }
        Box {
            Action(R.string.layers_align, Icons.Default.AlignHorizontalCenter, "btn_layers_align") { showAlign = true }
            AlignMenu(showAlign, { showAlign = false }, actions.onAlign)
        }
        if (picked.any { it.type == LayerType.GROUP }) {
            Action(R.string.layer_ungroup, Icons.Default.LayersClear, "btn_layers_ungroup") { actions.onUngroup() }
        }
        Action(R.string.layer_delete, Icons.Default.Delete, "btn_layers_delete", on = enabled && n < layers.size,
            tint = MaterialTheme.colorScheme.error) { confirmDelete = true }
    }
}


/** The Smart Filters of [layer]: a header (show all, filter mask) and one row per filter. */
@Composable
private fun SmartFilterRows(layer: Layer, depth: Int, actions: LayerPanelActions) {
    var maskMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = (depth * 16 + 40).dp, end = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.onSetSmartFiltersVisible(layer.id, !layer.smartFiltersVisible) }, modifier = Modifier.size(32.dp)) {
                Icon(if (layer.smartFiltersVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, stringResource(R.string.layer_visibility),
                    tint = Color.LightGray, modifier = Modifier.size(16.dp))
            }
            Text(stringResource(R.string.smart_filters), color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            // Filter mask: from the selection, inverted or removed.
            Box {
                TextButton(onClick = { maskMenu = true }, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(30.dp).testTag("btn_smart_mask")) {
                    Icon(Icons.Default.Gradient, null, modifier = Modifier.size(14.dp), tint = if (layer.smartFilterMask != null) IndigoPrimary else Color.Gray)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(if (layer.smartFilterMask != null) R.string.smart_filter_mask_on else R.string.smart_filter_mask), fontSize = 11.sp,
                        color = if (layer.smartFilterMask != null) IndigoPrimary else Color.Gray)
                }
                DropdownMenu(expanded = maskMenu, onDismissRequest = { maskMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.smart_filter_mask_from_selection)) }, onClick = { maskMenu = false; actions.onSmartMaskFromSelection(layer.id) })
                    if (layer.smartFilterMask != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.smart_filter_mask_invert)) }, onClick = { maskMenu = false; actions.onInvertSmartMask(layer.id) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.smart_filter_mask_remove)) }, onClick = { maskMenu = false; actions.onClearSmartMask(layer.id) })
                    }
                }
            }
            IconButton(onClick = actions.onOpenEffects, modifier = Modifier.size(32.dp).testTag("btn_add_smart_filter")) {
                Icon(Icons.Default.Add, stringResource(R.string.smart_filter_add), tint = IndigoPrimary, modifier = Modifier.size(18.dp))
            }
        }
        // Top of the list = applied last, as in Photoshop.
        val last = layer.smartFilters.lastOrNull()?.id
        layer.smartFilters.asReversed().forEach { f ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable { actions.onEditSmartFilter(layer.id, f.id) }.padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { actions.onToggleSmartFilter(layer.id, f.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(if (f.enabled) Icons.Default.Visibility else Icons.Default.VisibilityOff, stringResource(R.string.layer_visibility),
                        tint = if (f.enabled) Color.LightGray else Color.Gray, modifier = Modifier.size(16.dp))
                }
                Text(
                    proEffectLabel(f.effect) + if (f.opacity < 0.995f || f.blendMode != com.example.data.model.BlendModeType.NORMAL)
                        " · ${(f.opacity * 100).roundToInt()}%" else "",
                    color = if (f.enabled) Color.White else Color.Gray, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1
                )
                if (layer.smartFilters.size > 1 && f.id != last) {
                    // Move up the list: applied later.
                    IconButton(onClick = { actions.onMoveSmartFilterUp(layer.id, f.id) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowUpward, stringResource(R.string.smart_filter_move_up), tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(onClick = { actions.onEditSmartFilter(layer.id, f.id) }, modifier = Modifier.size(32.dp).testTag("btn_edit_smart_filter")) {
                    Icon(Icons.Default.Tune, stringResource(R.string.smart_filter_edit_short), tint = IndigoPrimary, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { actions.onRemoveSmartFilter(layer.id, f.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, stringResource(R.string.layer_delete), tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
