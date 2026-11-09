package com.example.ui.screens

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Transform
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.BlendModeType
import com.example.data.model.ColorAdjustments
import com.example.data.model.FilterType
import com.example.data.model.Layer
import com.example.data.model.Project
import com.example.data.model.SelectionMode
import com.example.data.model.SelectionToolType
import com.example.ui.components.AdjustmentsPanel
import com.example.ui.components.CanvasResizeDialog
import com.example.ui.components.CanvasView
import com.example.ui.components.CropPerspectivePanel
import com.example.ui.components.ExportDialog
import com.example.ui.components.FiltersPanel
import com.example.ui.components.InpaintPanel
import com.example.ui.components.LayersPanel
import com.example.ui.components.SelectionPanel
import com.example.ui.components.TextEditorPanel
import com.example.engine.SelectionEngine
import com.example.ui.components.TransformPanel
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import androidx.activity.result.PickVisualMediaRequest
import com.example.ui.viewmodel.ActiveTool

@Composable
fun EditorScreen(
    project: Project,
    renderRevision: Long = project.renderRevision,
    selectedLayerIndex: Int,
    activeTool: ActiveTool,
    activeSelectionTool: SelectionToolType,
    inpaintSelectionMode: com.example.ui.viewmodel.InpaintSelectionMode = com.example.ui.viewmodel.InpaintSelectionMode.BRUSH,
    selectionMask: Bitmap?,
    brushSizeDp: Float,
    magicWandTolerance: Int,
    isMagicWandContiguous: Boolean = true,
    wandOptions: com.example.engine.WandOptions = com.example.engine.WandOptions(),
    onWandOptionsChange: (com.example.engine.WandOptions) -> Unit = {},
    inpaintRadiusPx: Float = 5f,
    inpaintFeatherPx: Int = 4,
    inpaintEngineMode: com.example.engine.InpaintEngineMode = com.example.engine.InpaintEngineMode.SMART,
    isInpaintProtectMode: Boolean = false,
    onToggleInpaintProtect: () -> Unit = {},
    onClearInpaintProtect: () -> Unit = {},
    inpaintProtectMask: android.graphics.Bitmap? = null,
    inpaintProtectRevision: Long = 0L,
    canRetryRemoval: Boolean = false,
    onRetryRemoval: () -> Unit = {},
    isSmartEdgeSnapEnabled: Boolean = true,
    isSeamlessBlendEnabled: Boolean = true,
    canUndo: Boolean,
    canRedo: Boolean,
    canUndoSelection: Boolean = false,
    canRedoSelection: Boolean = false,
    isProjectDirty: Boolean = false,
    isProcessing: Boolean,
    statusMessage: String?,
    isPerspectiveActive: Boolean,
    perspectiveCorners: List<PointF>,
    selectedAspectRatio: Float?,
    isResizeDialogOpen: Boolean,
    isUnsavedChangesDialogOpen: Boolean = false,
    onDismissUnsavedDialog: () -> Unit = {},
    onSaveAndExit: () -> Unit = {},
    onDiscardAndExit: () -> Unit = {},
    onBackClick: () -> Unit,
    onToolSelect: (ActiveTool) -> Unit,
    onSelectLayer: (Int) -> Unit,
    onUpdateLayer: ((Layer) -> Unit) -> Unit,
    onUpdateLayerTransient: ((Layer) -> Unit) -> Unit,
    onCommitHistoryState: () -> Unit,
    onCancelHistoryState: () -> Unit = {},
    onOpacityChange: (Float) -> Unit,
    onBlendModeChange: (BlendModeType) -> Unit,
    onToggleVisibility: () -> Unit,
    onToggleLock: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMergeDown: () -> Unit,
    onAddImageUri: (Uri) -> Unit,
    onAddText: (String) -> Unit,
    onAddAdjustmentLayer: () -> Unit = {},
    onSmartAutoClick: () -> Unit,
    onObjectRemovalClick: () -> Unit,
    onInpaintSelectionModeChange: (com.example.ui.viewmodel.InpaintSelectionMode) -> Unit = {},
    onInpaintEngineModeChange: (com.example.engine.InpaintEngineMode) -> Unit = {},
    onToggleSmartEdgeSnap: () -> Unit = {},
    onToggleSeamlessBlend: () -> Unit = {},
    onInpaintRadiusChange: (Float) -> Unit = {},
    onInpaintFeatherChange: (Int) -> Unit = {},
    onSelectionToolSelect: (SelectionToolType) -> Unit,
    isRefineEdgeDialogOpen: Boolean = false,
    onOpenRefineEdgeDialog: () -> Unit = {},
    onCloseRefineEdgeDialog: () -> Unit = {},
    onApplyRefineEdge: (com.example.engine.RefineEdgeConfig) -> Unit = {},
    onSelectSubject: () -> Unit = {},
    isMagneticSnap: Boolean = false,
    onToggleMagneticSnap: () -> Unit = {},
    selectionMode: SelectionMode = SelectionMode.ADD,
    onSelectionModeSelect: (SelectionMode) -> Unit = {},
    onBrushSizeChange: (Float) -> Unit,
    onToleranceChange: (Int) -> Unit,
    onToggleMagicWandContiguous: (Boolean) -> Unit = {},
    onBrushStrokeComplete: (List<PointF>, Float) -> Unit,
    onLassoComplete: (List<PointF>) -> Unit,
    onRectangleSelection: (PointF, PointF) -> Unit = { _, _ -> },
    onEllipseSelection: (PointF, PointF) -> Unit = { _, _ -> },
    onPolygonComplete: (List<PointF>) -> Unit = {},
    onMagicWandTap: (Int, Int) -> Unit,
    onFeatherSelection: (Float) -> Unit = {},
    onGrowSelection: (Int) -> Unit = {},
    onShrinkSelection: (Int) -> Unit = {},
    onSmoothSelection: (Int) -> Unit = {},
    onRefineSelectionEdge: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onInvertSelection: () -> Unit,
    onClearSelection: () -> Unit,
    onCopySelectionToLayer: () -> Unit,
    onUndoSelection: () -> Unit = {},
    onRedoSelection: () -> Unit = {},
    onSaveProject: () -> Unit,
    onExportConfirm: (isPng: Boolean, quality: Int) -> Unit,
    luts: List<com.example.engine.LutInfo> = emptyList(),
    customPresets: List<com.example.engine.CustomPreset> = emptyList(),
    favoriteFilters: Set<String> = emptySet(),
    onToggleFavoriteFilter: (String) -> Unit = {},
    onImportLut: (Uri) -> Unit = {},
    onDeleteLut: (String) -> Unit = {},
    onSaveLook: (String) -> Unit = {},
    onDeleteCustomPreset: (String) -> Unit = {},
    onStrokeSegment: ((List<PointF>, Float, com.example.ui.viewmodel.StrokePhase) -> Unit)? = null,
    selectionRevision: Long = 0L,
    onMagicWandDrag: ((Int, Int, Int, com.example.ui.viewmodel.StrokePhase) -> Unit)? = null,
    editableShape: com.example.ui.viewmodel.EditableShape? = null,
    shapeFromCenter: Boolean = false,
    shapeLockSquare: Boolean = false,
    onToggleShapeFromCenter: (Boolean) -> Unit = {},
    onToggleShapeLockSquare: (Boolean) -> Unit = {},
    onShapeCreated: ((android.graphics.RectF, Boolean) -> Unit)? = null,
    onShapeEdited: ((android.graphics.RectF) -> Unit)? = null,
    onMoveSelection: ((Float, Float) -> Unit)? = null,
    isQuickMaskView: Boolean = false,
    onToggleQuickMask: () -> Unit = {},
    onTemporarySubtract: ((Boolean) -> Unit)? = null,
    modifierOp: com.example.ui.viewmodel.SelectionModifierOp = com.example.ui.viewmodel.SelectionModifierOp.FEATHER,
    modifierAmount: Float = 0f,
    onModifierPreview: (com.example.ui.viewmodel.SelectionModifierOp, Float) -> Unit = { _, _ -> },
    onModifierCommit: () -> Unit = {},
    selectionRefine: com.example.ui.viewmodel.SelectionRefineParams = com.example.ui.viewmodel.SelectionRefineParams(),
    onSelectionRefinePreview: (com.example.ui.viewmodel.SelectionRefineParams) -> Unit = {},
    onSelectionRefineApply: () -> Unit = {},
    onSelectionRefineReset: () -> Unit = {},
    onBorderSelection: (Float) -> Unit = {},
    onCleanupSelection: () -> Unit = {},
    isQuickAutoEnhance: Boolean = true,
    onToggleQuickAutoEnhance: (Boolean) -> Unit = {},
    isQuickSampleAll: Boolean = false,
    onToggleQuickSampleAll: (Boolean) -> Unit = {},
    quickSelectionTolerance: Int = 35,
    onQuickSelectionToleranceChange: (Int) -> Unit = {},
    quickSelectionEdgeSensitivity: Int = 60,
    onQuickSelectionEdgeSensitivityChange: (Int) -> Unit = {},
    refinePreview: Bitmap? = null,
    refinePreviewMode: com.example.ui.viewmodel.RefinePreviewMode = com.example.ui.viewmodel.RefinePreviewMode.OVERLAY,
    onRefinePreviewModeChange: (com.example.ui.viewmodel.RefinePreviewMode) -> Unit = {},
    onRequestRefinePreview: (com.example.engine.RefineEdgeConfig) -> Unit = {},
    exportSuccessUri: Uri? = null,
    onExportResultConsumed: () -> Unit = {},
    onRenameProject: (String) -> Unit = {},
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClearStatusMessage: () -> Unit,
    onOpenResizeDialog: () -> Unit,
    onCloseResizeDialog: () -> Unit,
    onConfirmResizeCanvas: (Int, Int, Boolean) -> Unit,
    onRotate90Left: () -> Unit,
    onRotate90Right: () -> Unit,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit,
    onRotationChange: (Float) -> Unit,
    onCenterSelectedLayer: () -> Unit = {},
    onFitSelectedLayerToCanvas: () -> Unit = {},
    onFillSelectedLayerToCanvas: () -> Unit = {},
    onResetTransform: () -> Unit = {},
    onSelectAspectRatio: (Float?) -> Unit,
    onTogglePerspective: (Boolean) -> Unit,
    onApplyCrop: (android.graphics.RectF?) -> Unit,
    onResetCrop: () -> Unit = {},
    onApplyPerspective: () -> Unit,
    onResetPerspective: () -> Unit,
    onUpdatePerspectiveCorner: (Int, Float, Float) -> Unit,
    onUpdatePerspectiveCorners: ((List<PointF>) -> Unit)? = null,
    onFitPerspectiveToCanvas: () -> Unit = {},
    onAddLayerMask: ((Boolean) -> Unit)? = null,
    onInvertLayerMask: (() -> Unit)? = null,
    onToggleLayerMaskEnabled: (() -> Unit)? = null,
    onDeleteLayerMask: (() -> Unit)? = null,
    onApplyLayerMask: (() -> Unit)? = null,
    onSetEditingMask: ((Boolean) -> Unit)? = null,
    onToggleMaskPaintColor: (() -> Unit)? = null,
    isMaskEditingBlack: Boolean = true,
    onApplyProEffect: ((com.example.data.model.ProFilterType, Float, Float) -> Unit)? = null,
    onMaskDensityChange: ((Float) -> Unit)? = null,
    onMaskFeatherChange: ((Float) -> Unit)? = null,
    onRevealAllMask: (() -> Unit)? = null,
    onHideAllMask: (() -> Unit)? = null,
    onToggleRubyOverlay: (() -> Unit)? = null,
    showMaskRubyOverlay: Boolean = false,
    maskBrushSizeDp: Float = 24f,
    onMaskBrushSizeChange: ((Float) -> Unit)? = null,
    layerActions: com.example.ui.components.LayerPanelActions = com.example.ui.components.LayerPanelActions(),
    maskBrushHardness: Float = 0.6f,
    maskBrushOpacity: Float = 1f,
    onTextConfigChange: ((com.example.data.model.TextLayerConfig) -> Unit)? = null,
    smartAutoStrength: Float? = null,
    onSmartAutoStrengthChange: (Float) -> Unit = {},
    onCreateAdjustmentLayerFromSelection: (() -> Unit)? = null,
    /** Colour of the image at a document point as it enters the curves (null = nothing there). */
    onSampleCurveColor: ((Float, Float) -> Int?)? = null,
    onSampleReplaceColor: ((Float, Float) -> Int?)? = null,
    onSampleHslColor: ((Float, Float) -> Int?)? = null,
    cropStraighten: Float = 0f,
    onCropStraightenChange: (Float) -> Unit = {},
    cropGrid: String = "THIRDS",
    onCropGridChange: (String) -> Unit = {},
    cropExpandCanvas: Boolean = false,
    onToggleCropExpandCanvas: () -> Unit = {},
    cropTargetWidth: Int = 0,
    cropTargetHeight: Int = 0,
    onCropTargetSize: (Int, Int) -> Unit = { _, _ -> },
    onSwapCropOrientation: () -> Unit = {},
    onRotateCanvas: (Boolean) -> Unit = {},
    onFlipCanvas: (Boolean) -> Unit = {},
    perspectiveVertical: Float = 0f,
    perspectiveHorizontal: Float = 0f,
    onKeystoneChange: (Float, Float) -> Unit = { _, _ -> },
    onAutoDetectDocument: () -> Unit = {},
    transformMode: String = "FREE",
    onTransformModeChange: (String) -> Unit = {},
    transformPivot: Int = 4,
    onTransformPivotChange: (Int) -> Unit = {},
    transformFromCenter: Boolean = false,
    onToggleTransformFromCenter: () -> Unit = {},
    transformLockAspect: Boolean = true,
    onToggleTransformLockAspect: () -> Unit = {},
    transformSnap: Boolean = true,
    onToggleTransformSnap: () -> Unit = {}
) {
    var currentCropRect by remember { mutableStateOf<android.graphics.RectF?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var compareOriginal by remember { mutableStateOf(false) }
    var showGeneralOriginal by remember { mutableStateOf(false) }
    var isSplitCompareActive by remember { mutableStateOf(false) }
    var splitComparePosition by remember { mutableFloatStateOf(0.5f) }
    val generalCompareInteraction = remember { MutableInteractionSource() }
    val isGeneralComparing by generalCompareInteraction.collectIsPressedAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(isGeneralComparing) {
        showGeneralOriginal = isGeneralComparing
    }

    LaunchedEffect(activeTool) {
        if (activeTool != ActiveTool.ADJUST && activeTool != ActiveTool.FILTERS) compareOriginal = false
    }

    // Curves: tapping the image adds a point / sets black, grey or white points.
    var curvePickMode by remember { mutableStateOf<com.example.ui.components.CurvePickMode?>(null) }
    var curveSample by remember { mutableStateOf<com.example.ui.components.CurveSample?>(null) }
    // HSL targeted adjustment: property changed by dragging on the image (HUE / SAT / LIGHT) or null.
    var hslTargetProperty by remember { mutableStateOf<String?>(null) }
    var hslDragStart by remember { mutableStateOf<Pair<com.example.data.model.ColorAdjustments, Map<String, Float>>?>(null) }
    LaunchedEffect(activeTool) { if (activeTool != ActiveTool.ADJUST) hslTargetProperty = null }
    val onTargetedDrag: (Float, Float, Float, com.example.ui.viewmodel.StrokePhase) -> Unit = { x, y, dxDp, phase ->
        val prop = hslTargetProperty
        val layer = project.layers.getOrNull(selectedLayerIndex)
        if (prop != null && layer != null) {
            when (phase) {
                com.example.ui.viewmodel.StrokePhase.START -> {
                    val color = onSampleHslColor?.invoke(x, y)
                    val weights = color?.let { com.example.engine.ImageProcessor.hslRangeWeights(it) } ?: emptyMap()
                    val maxW = weights.values.maxOrNull() ?: 0f
                    hslDragStart = if (maxW > 0f) layer.adjustments.migrateHsl() to weights.mapValues { it.value / maxW } else null
                }
                else -> {
                    val start = hslDragStart
                    if (start != null) {
                        // 2 dp of drag = 1 unit; the colour's main range moves fully, its neighbour by weight.
                        val amount = dxDp / 2f
                        var adj = start.first
                        start.second.forEach { (key, w) ->
                            val band = start.first.hslBand(key)
                            val changed = when (prop) {
                                "HUE" -> band.copy(hue = (band.hue + amount * w * 0.5f).coerceIn(-60f, 60f))
                                "SAT" -> band.copy(saturation = (band.saturation + amount * w).coerceIn(-100f, 100f))
                                else -> band.copy(lightness = (band.lightness + amount * w).coerceIn(-100f, 100f))
                            }
                            adj = adj.withHslBand(key, changed)
                        }
                        val main = start.second.maxByOrNull { it.value }?.key
                        if (main != null) adj = adj.copy(hslColorRange = main)
                        onUpdateLayerTransient { l -> l.adjustments = adj }
                        if (phase == com.example.ui.viewmodel.StrokePhase.END) {
                            onCommitHistoryState()
                            hslDragStart = null
                        }
                    }
                }
            }
        }
    }

    // Replace colour: eyedropper for (replacement index, source?).
    var replacePick by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    LaunchedEffect(activeTool) { if (activeTool != ActiveTool.ADJUST) { curvePickMode = null; curveSample = null; replacePick = null } }
    val canvasSampler: ((Float, Float) -> Unit)? = when {
        activeTool == ActiveTool.ADJUST && replacePick != null -> { x, y ->
            val target = replacePick
            val color = onSampleReplaceColor?.invoke(x, y)
            if (target != null && color != null) {
                onUpdateLayerTransient { l ->
                    val list = l.adjustments.effectiveReplacements().toMutableList()
                    if (target.first in list.indices) {
                        list[target.first] = if (target.second) list[target.first].copy(sourceColor = color) else list[target.first].copy(targetColor = color)
                        l.adjustments = l.adjustments.withReplacements(list)
                    }
                }
                onCommitHistoryState()
            }
            replacePick = null
        }
        activeTool == ActiveTool.ADJUST && curvePickMode != null -> { x, y ->
            onSampleCurveColor?.invoke(x, y)?.let { color ->
                curveSample = com.example.ui.components.CurveSample(color, System.nanoTime())
            }
        }
        else -> null
    }

    // Set by "add text" and by double-tapping a text layer: the text panel focuses its field.
    var textEditFocusRequested by remember { mutableStateOf(false) }

    // Pro effect shown live on the canvas while its panel is open.
    var proEffectPreview by remember { mutableStateOf<com.example.engine.ProEffectPreview?>(null) }
    // 0 = idle, 1 = Apply pressed, 2 = the effect is being applied. The live preview stays on screen
    // until the real result replaces it, so the image never flashes back to the original meanwhile.
    var proApplyPhase by remember { mutableStateOf(0) }
    LaunchedEffect(proApplyPhase, isProcessing) {
        when {
            proApplyPhase == 1 && isProcessing -> proApplyPhase = 2
            proApplyPhase == 2 && !isProcessing -> { proEffectPreview = null; proApplyPhase = 0 }
            proApplyPhase == 1 -> {
                // The apply did not start (e.g. locked layer): drop the preview.
                kotlinx.coroutines.delay(1500)
                proEffectPreview = null
                proApplyPhase = 0
            }
        }
    }
    LaunchedEffect(proEffectPreview == null) {
        if (proEffectPreview == null) com.example.engine.LayerRenderer.clearProEffectCache()
    }

    BackHandler {
        // Never leave the editor (or a tool) while an operation is still writing to the project.
        if (isProcessing) return@BackHandler
        if (isSplitCompareActive) {
            isSplitCompareActive = false
            return@BackHandler
        }
        when (activeTool) {
            ActiveTool.FILTERS -> {
                // Filter browsing is a transient preview. Back means Cancel, never Apply.
                onCancelHistoryState()
                onToolSelect(ActiveTool.NONE)
            }
            ActiveTool.CROP_PERSPECTIVE -> {
                // Leaving crop/perspective should discard the un-applied crop UI state.
                currentCropRect = null
                if (isPerspectiveActive) onTogglePerspective(false)
                onToolSelect(ActiveTool.NONE)
            }
            ActiveTool.NONE -> onBackClick()
            else -> onToolSelect(ActiveTool.NONE)
        }
    }

    // A crop rectangle is only meaningful while the crop tool is active. Avoid
    // surprising stale rectangles if the user switches tools and later returns.
    LaunchedEffect(activeTool) {
        if (activeTool != ActiveTool.CROP_PERSPECTIVE) currentCropRect = null
    }

    if (isUnsavedChangesDialogOpen) {
        AlertDialog(
            onDismissRequest = onDismissUnsavedDialog,
            title = {
                Text(
                    text = stringResource(R.string.unsaved_title),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.unsaved_desc),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = onSaveAndExit,
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.unsaved_save_exit), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDiscardAndExit) {
                        Text(stringResource(R.string.unsaved_discard), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = onDismissUnsavedDialog) {
                        Text(stringResource(R.string.action_cancel), color = Color.Gray)
                    }
                }
            },
            containerColor = StudioSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // After a successful export: confirm and offer to share the saved image right away.
    val context = LocalContext.current
    val exportedText = stringResource(R.string.export_success)
    val shareLabel = stringResource(R.string.action_share)
    LaunchedEffect(exportSuccessUri) {
        val uri = exportSuccessUri ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = exportedText,
            actionLabel = shareLabel,
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) {
            val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null } ?: "image/*"
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(send, shareLabel).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) {
            }
        }
        onExportResultConsumed()
    }

    // Progress text is shown by the processing overlay; only results are shown as snackbars.
    LaunchedEffect(statusMessage, isProcessing) {
        if (statusMessage != null && !isProcessing) {
            snackbarHostState.showSnackbar(statusMessage)
            onClearStatusMessage()
        }
    }

    val getContentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onAddImageUri(uri)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onAddImageUri(uri)
        }
    }

    fun launchPhotoPicker() {
        try {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (e: Exception) {
            try {
                getContentLauncher.launch("image/*")
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    // hasSelection scans the whole mask; compute it once per mask (selection edits publish a new bitmap)
    // instead of on every recomposition (e.g. every slider tick).
    val hasActiveSelectionCached by androidx.compose.runtime.produceState(initialValue = false, selectionMask, selectionRevision) {
        val m = selectionMask
        value = m != null && !m.isRecycled &&
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { SelectionEngine.hasSelection(m) }
    }
    val selectedLayer = project.layers.getOrNull(selectedLayerIndex)
    val saveStateLabel = when {
        isProcessing -> stringResource(R.string.status_working)
        isProjectDirty -> stringResource(R.string.status_unsaved)
        else -> stringResource(R.string.status_saved)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioSurface)
                    .statusBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick, enabled = !isProcessing) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = Color.White)
                }

                Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        text = project.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                    Text(
                        text = "${project.width} × ${project.height} px • $saveStateLabel",
                        color = when {
                            isProcessing -> AmberAccent
                            isProjectDirty -> MaterialTheme.colorScheme.error
                            else -> Color.Gray
                        },
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }

                val undoEnabled = canUndo && !isProcessing
                val redoEnabled = canRedo && !isProcessing
                IconButton(onClick = onUndo, enabled = undoEnabled) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.action_undo), tint = if (undoEnabled) Color.White else Color.Gray)
                }
                IconButton(onClick = onRedo, enabled = redoEnabled) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.action_redo), tint = if (redoEnabled) Color.White else Color.Gray)
                }
                IconButton(
                    onClick = { isSplitCompareActive = !isSplitCompareActive },
                    enabled = !isProcessing,
                    modifier = Modifier.testTag("btn_compare_before_after")
                ) {
                    Icon(
                        if (isSplitCompareActive) Icons.Default.CompareArrows else Icons.Default.Compare,
                        contentDescription = stringResource(R.string.compare_title),
                        tint = if (isSplitCompareActive || isGeneralComparing) AmberAccent else Color.White
                    )
                }
                IconButton(onClick = { showExportDialog = true }, enabled = !isProcessing, modifier = Modifier.testTag("btn_export_image")) {
                    Icon(Icons.Default.Download, contentDescription = stringResource(R.string.cd_export), tint = IndigoPrimary)
                }
                Box {
                    IconButton(onClick = { showTopMenu = true }, enabled = !isProcessing) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more), tint = Color.White)
                    }
                    DropdownMenu(expanded = showTopMenu, onDismissRequest = { showTopMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_save)) },
                            leadingIcon = { Icon(Icons.Default.Save, null) },
                            onClick = { showTopMenu = false; onSaveProject() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_rename)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { showTopMenu = false; showRenameDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.canvas_resize_title)) },
                            leadingIcon = { Icon(Icons.Default.AspectRatio, null) },
                            onClick = { showTopMenu = false; onOpenResizeDialog() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.adj_smart_auto)) },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, null, tint = AmberAccent) },
                            onClick = { showTopMenu = false; onSmartAutoClick() }
                        )
                    }
                }
            }
        },
        containerColor = StudioBackground
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isWideScreen = maxWidth >= 840.dp && maxHeight >= 480.dp
            val screenMaxHeight = maxHeight

            @Composable
            fun ActiveContextualPanelContent() {
                when (activeTool) {
                    ActiveTool.ADJUST -> {
                        val hasSel = hasActiveSelectionCached
                        selectedLayer?.let { layer ->
                            AdjustmentsPanel(
                                adjustments = layer.adjustments,
                                onAdjustmentsChange = { newAdj ->
                                    onUpdateLayerTransient { l -> l.adjustments = newAdj }
                                },
                                onAdjustmentsCommit = onCommitHistoryState,
                                onSmartAutoClick = onSmartAutoClick,
                                onResetAll = {
                                    onUpdateLayerTransient { l -> l.adjustments = ColorAdjustments() }
                                    onCommitHistoryState()
                                },
                                onCompareChange = { compareOriginal = it },
                                smartAutoStrength = smartAutoStrength,
                                onSmartAutoStrengthChange = onSmartAutoStrengthChange,
                                onSmartAutoStrengthCommit = onCommitHistoryState,
                                onCreateAdjustmentLayerFromSelection = onCreateAdjustmentLayerFromSelection,
                                sourceBitmap = layer.bitmap,
                                curvePickMode = curvePickMode,
                                onCurvePickModeChange = { curvePickMode = it },
                                curvePickedSample = curveSample,
                                onCurveSampleConsumed = { curveSample = null },
                                replacePickTarget = replacePick,
                                hslTargetProperty = hslTargetProperty,
                                onHslTargetPropertyChange = { hslTargetProperty = it; if (it != null) { curvePickMode = null; replacePick = null } },
                                onReplacePickChange = { replacePick = it; if (it != null) curvePickMode = null },
                                hasActiveSelection = hasSel,
                                onClearSelection = onClearSelection
                            )
                        }
                    }

                    ActiveTool.FILTERS -> {
                        val hasSel = hasActiveSelectionCached
                        selectedLayer?.let { layer ->
                            // Same size the ViewModel decodes for destructive effects (authoritative source),
                            // so the live preview scales pixel radii to look like the applied result.
                            val fullResMaxDimension = run {
                                val bmpMax = layer.bitmap?.let { maxOf(it.width, it.height) } ?: 0
                                if (layer.sourceAssetPath != null) {
                                    val intrinsic = maxOf(layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight()).toInt().coerceIn(64, 4096)
                                    val original = maxOf(layer.originalWidth, layer.originalHeight)
                                    if (original > 0) minOf(intrinsic, original) else intrinsic
                                } else bmpMax
                            }
                            // Thumbnails need an image: a layer without its own pixels (adjustment / text /
                            // fill) previews on the image it affects, rendered small in the background.
                            val filtersContext = androidx.compose.ui.platform.LocalContext.current
                            val previewSource by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
                                initialValue = layer.bitmap?.takeIf { !it.isRecycled },
                                layer.id, layer.bitmap, project.id
                            ) {
                                val own = layer.bitmap?.takeIf { !it.isRecycled }
                                if (own != null) { value = own; return@produceState }
                                value = try {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                        val idx = project.layers.indexOfFirst { it.id == layer.id }
                                        val below = if (layer.type == com.example.data.model.LayerType.ADJUSTMENT && idx > 0) project.layers.subList(0, idx).toList() else project.layers.toList()
                                        com.example.engine.LayerRenderer.renderCompositeCanvas(
                                            filtersContext, below, project.width, project.height,
                                            includeBackground = true, maxPreviewDimension = 480
                                        )
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    null
                                }
                            }
                            FiltersPanel(
                                selectedFilter = layer.filter,
                                filterIntensity = layer.filterIntensity,
                                sourceBitmap = previewSource,
                                adjustments = layer.adjustments,
                                // A preset filter and an imported LUT are alternative looks.
                                onPreviewFilter = { filter -> onUpdateLayerTransient { l -> l.filter = filter; l.lutId = null } },
                                selectedLutId = layer.lutId,
                                luts = luts,
                                customPresets = customPresets,
                                favorites = favoriteFilters,
                                onPreviewLut = { id -> onUpdateLayerTransient { l -> l.filter = FilterType.NONE; l.lutId = id } },
                                onApplyCustomPreset = { p ->
                                    onUpdateLayerTransient { l ->
                                        l.adjustments = p.adjustments.copy()
                                        l.filter = p.filter
                                        l.filterIntensity = p.intensity
                                        l.lutId = p.lutId
                                    }
                                },
                                onToggleFavorite = onToggleFavoriteFilter,
                                onImportLut = onImportLut,
                                onDeleteLut = onDeleteLut,
                                onSaveLook = onSaveLook,
                                onDeleteCustomPreset = onDeleteCustomPreset,
                                onIntensityChange = { intensity -> onUpdateLayerTransient { l -> l.filterIntensity = intensity } },
                                onCompareChange = { compareOriginal = it },
                                onApply = { onCommitHistoryState(); onToolSelect(ActiveTool.NONE) },
                                onCancel = { onCancelHistoryState(); onToolSelect(ActiveTool.NONE) },
                                onApplyProEffect = { effect, p1, p2 ->
                                    // Keep showing exactly what is being applied until the result arrives.
                                    proEffectPreview = com.example.engine.ProEffectPreview(layer.id, layer.bitmap, effect, p1, p2, fullResMaxDimension)
                                    proApplyPhase = 1
                                    onApplyProEffect?.invoke(effect, p1, p2)
                                    onToolSelect(ActiveTool.NONE)
                                },
                                hasActiveSelection = hasSel,
                                onClearSelection = onClearSelection,
                                onPreviewProEffect = { effect, p1, p2 ->
                                    if (proApplyPhase == 0) {
                                        proEffectPreview = effect?.let {
                                            com.example.engine.ProEffectPreview(layer.id, layer.bitmap, it, p1, p2, fullResMaxDimension)
                                        }
                                    }
                                }
                            )
                        }
                    }

                    ActiveTool.CROP_PERSPECTIVE -> {
                        CropPerspectivePanel(
                            isPerspectiveActive = isPerspectiveActive,
                            originalAspectRatio = project.width.toFloat() / project.height.toFloat(),
                            selectedAspectRatio = selectedAspectRatio,
                            cropRect = currentCropRect,
                            perspectiveCorners = perspectiveCorners,
                            projectWidth = project.width,
                            projectHeight = project.height,
                            onSelectAspectRatio = { currentCropRect = null; onSelectAspectRatio(it) },
                            onTogglePerspective = onTogglePerspective,
                            onApplyCrop = { onApplyCrop(currentCropRect); onToolSelect(ActiveTool.NONE) },
                            onResetCrop = { currentCropRect = null; onResetCrop() },
                            onApplyPerspective = { onApplyPerspective(); onToolSelect(ActiveTool.NONE) },
                            onResetPerspective = onResetPerspective,
                            onFitPerspectiveToCanvas = onFitPerspectiveToCanvas,
                            cropStraighten = cropStraighten,
                            onStraightenChange = { currentCropRect = null; onCropStraightenChange(it) },
                            cropGrid = cropGrid,
                            onGridChange = onCropGridChange,
                            expandCanvas = cropExpandCanvas,
                            onToggleExpandCanvas = onToggleCropExpandCanvas,
                            targetWidth = cropTargetWidth,
                            targetHeight = cropTargetHeight,
                            onTargetSize = { w, h -> currentCropRect = null; onCropTargetSize(w, h) },
                            onSwapOrientation = { currentCropRect = null; onSwapCropOrientation() },
                            onRotateCanvas = { currentCropRect = null; onRotateCanvas(it) },
                            onFlipCanvas = onFlipCanvas,
                            perspectiveVertical = perspectiveVertical,
                            perspectiveHorizontal = perspectiveHorizontal,
                            onKeystoneChange = onKeystoneChange,
                            onAutoDetectDocument = onAutoDetectDocument
                        )
                    }

                    ActiveTool.SELECTION -> {
                        SelectionPanel(
                            selectedTool = activeSelectionTool,
                            selectionMode = selectionMode,
                            brushSizeDp = brushSizeDp,
                            tolerance = magicWandTolerance,
                            isContiguous = isMagicWandContiguous,
                            wandOptions = wandOptions,
                            onWandOptionsChange = onWandOptionsChange,
                            canUndoSelection = canUndoSelection,
                            canRedoSelection = canRedoSelection,
                            onToolSelect = onSelectionToolSelect,
                            onModeSelect = onSelectionModeSelect,
                            onBrushSizeChange = onBrushSizeChange,
                            onToleranceChange = onToleranceChange,
                            onToggleContiguous = onToggleMagicWandContiguous,
                            onFeatherClick = onFeatherSelection,
                            onGrowClick = onGrowSelection,
                            onShrinkClick = onShrinkSelection,
                            onSmoothClick = onSmoothSelection,
                            onRefineClick = onRefineSelectionEdge,
                            onSelectAllClick = onSelectAll,
                            onInvertClick = onInvertSelection,
                            onClearClick = onClearSelection,
                            onCopyLayerClick = onCopySelectionToLayer,
                            onUndoSelection = onUndoSelection,
                            onRedoSelection = onRedoSelection,
                            onSelectSubject = onSelectSubject,
                            onOpenRefineEdgeDialog = onOpenRefineEdgeDialog,
                            isMagneticSnap = isMagneticSnap,
                            onToggleMagneticSnap = onToggleMagneticSnap,
                            isQuickAutoEnhance = isQuickAutoEnhance,
                            onToggleQuickAutoEnhance = onToggleQuickAutoEnhance,
                            isQuickSampleAll = isQuickSampleAll,
                            onToggleQuickSampleAll = onToggleQuickSampleAll,
                            quickTolerance = quickSelectionTolerance,
                            onQuickToleranceChange = onQuickSelectionToleranceChange,
                            quickEdgeSensitivity = quickSelectionEdgeSensitivity,
                            onQuickEdgeSensitivityChange = onQuickSelectionEdgeSensitivityChange,
                            isQuickMaskView = isQuickMaskView,
                            onToggleQuickMask = onToggleQuickMask,
                            shapeFromCenter = shapeFromCenter,
                            onToggleShapeFromCenter = onToggleShapeFromCenter,
                            shapeLockSquare = shapeLockSquare,
                            onToggleShapeLockSquare = onToggleShapeLockSquare,
                            modifierOp = modifierOp,
                            modifierAmount = modifierAmount,
                            onModifierPreview = onModifierPreview,
                            onModifierCommit = onModifierCommit,
                            refineParams = selectionRefine,
                            onRefinePreview = onSelectionRefinePreview,
                            onRefineApply = onSelectionRefineApply,
                            onRefineReset = onSelectionRefineReset,
                            onBorderSelection = onBorderSelection,
                            onCleanupSelection = onCleanupSelection,
                            documentMaxDim = maxOf(project.width, project.height)
                        )
                    }

                    ActiveTool.TRANSFORM -> {
                        selectedLayer?.let { layer ->
                            val transformContext = androidx.compose.ui.platform.LocalContext.current
                            val box = remember(layer.id, layer.bitmap, layer.textConfig, project.width, project.height) {
                                when {
                                    layer.bitmap != null -> layer.intrinsicDocumentWidth() to layer.intrinsicDocumentHeight()
                                    layer.type == com.example.data.model.LayerType.TEXT ->
                                        com.example.engine.LayerRenderer.measureTextLayer(transformContext, layer, project.width.toFloat())
                                    else -> project.width.toFloat() to project.height.toFloat()
                                }
                            }
                            TransformPanel(
                                boxWidth = box.first,
                                boxHeight = box.second,
                                documentWidth = project.width,
                                documentHeight = project.height,
                                mode = transformMode,
                                onModeChange = onTransformModeChange,
                                pivot = transformPivot,
                                onPivotChange = onTransformPivotChange,
                                fromCenter = transformFromCenter,
                                onToggleFromCenter = onToggleTransformFromCenter,
                                lockAspect = transformLockAspect,
                                onToggleLockAspect = onToggleTransformLockAspect,
                                snap = transformSnap,
                                onToggleSnap = onToggleTransformSnap,
                                skewX = layer.skewX,
                                skewY = layer.skewY,
                                onSkewChange = { kx, ky -> onUpdateLayerTransient { l -> l.skewX = kx; l.skewY = ky } },
                                hasDistort = layer.distort != null,
                                onResetDistort = {
                                    onUpdateLayerTransient { l -> l.distort = null }
                                    onCommitHistoryState()
                                },
                                onApplyNumeric = { w, h, angle, x, y ->
                                    onUpdateLayerTransient { l ->
                                        if (box.first > 0f) l.scaleX = (if (l.scaleX < 0f) -1f else 1f) * w / box.first
                                        if (box.second > 0f) l.scaleY = (if (l.scaleY < 0f) -1f else 1f) * h / box.second
                                        l.rotation = ((angle % 360f) + 360f) % 360f
                                        l.positionX = x
                                        l.positionY = y
                                    }
                                    onCommitHistoryState()
                                },
                                rotation = layer.rotation,
                                scaleX = layer.scaleX,
                                scaleY = layer.scaleY,
                                positionX = layer.positionX,
                                positionY = layer.positionY,
                                onRotationChange = onRotationChange,
                                onRotationCommit = onCommitHistoryState,
                                onScaleChange = { sx, sy ->
                                    onUpdateLayerTransient { l ->
                                        l.scaleX = sx
                                        l.scaleY = sy
                                    }
                                },
                                onPositionChange = { px, py ->
                                    onUpdateLayerTransient { l ->
                                        l.positionX = px
                                        l.positionY = py
                                    }
                                },
                                onTransformCommit = onCommitHistoryState,
                                onRotate90Left = onRotate90Left,
                                onRotate90Right = onRotate90Right,
                                onFlipHorizontal = onFlipHorizontal,
                                onFlipVertical = onFlipVertical,
                                onCenterCanvas = onCenterSelectedLayer,
                                onFitCanvas = onFitSelectedLayerToCanvas,
                                onFillCanvas = onFillSelectedLayerToCanvas,
                                onReset = onResetTransform
                            )
                        }
                    }

                    ActiveTool.LAYERS -> {
                        LayersPanel(
                            layers = project.layers,
                            selectedIndex = selectedLayerIndex,
                            onSelectLayer = onSelectLayer,
                            onOpacityChange = { op ->
                                onUpdateLayerTransient { l -> l.opacity = op }
                            },
                            onOpacityCommit = onCommitHistoryState,
                            onBlendModeChange = onBlendModeChange,
                            onToggleVisibility = onToggleVisibility,
                            onToggleLock = onToggleLock,
                            onMoveUp = onMoveUp,
                            onMoveDown = onMoveDown,
                            onDuplicate = onDuplicate,
                            onDelete = onDelete,
                            onMergeDown = onMergeDown,
                            onAddImageClick = { launchPhotoPicker() },
                            onAddTextClick = onAddText,
                            onAddAdjustmentClick = onAddAdjustmentLayer,
                            onAddLayerMask = onAddLayerMask,
                            onInvertLayerMask = onInvertLayerMask,
                            onToggleLayerMaskEnabled = onToggleLayerMaskEnabled,
                            onDeleteLayerMask = onDeleteLayerMask,
                            onApplyLayerMask = onApplyLayerMask,
                            onSetEditingMask = onSetEditingMask,
                            onToggleMaskPaintColor = onToggleMaskPaintColor,
                            isMaskEditingBlack = isMaskEditingBlack,
                            hasActiveSelection = hasActiveSelectionCached,
                            onMaskDensityChange = onMaskDensityChange,
                            onMaskFeatherChange = onMaskFeatherChange,
                            onRevealAllMask = onRevealAllMask,
                            onHideAllMask = onHideAllMask,
                            onToggleRubyOverlay = onToggleRubyOverlay,
                            showMaskRubyOverlay = showMaskRubyOverlay,
                            maskBrushSizeDp = maskBrushSizeDp,
                            onMaskBrushSizeChange = onMaskBrushSizeChange,
                            actions = layerActions,
                            maskBrushHardness = maskBrushHardness,
                            maskBrushOpacity = maskBrushOpacity,
                            documentWidth = project.width,
                            documentHeight = project.height
                        )
                    }

                    ActiveTool.TEXT -> {
                        val textLayer = selectedLayer?.takeIf { it.type == com.example.data.model.LayerType.TEXT }
                        if (textLayer != null) {
                            TextEditorPanel(
                                config = textLayer.textConfig,
                                positionX = textLayer.positionX,
                                positionY = textLayer.positionY,
                                onConfigChange = { newCfg ->
                                    val change = onTextConfigChange
                                    if (change != null) change(newCfg) else onUpdateLayerTransient { l -> l.textConfig = newCfg }
                                },
                                documentWidth = project.width,
                                documentHeight = project.height,
                                requestEditFocus = textEditFocusRequested,
                                onEditFocusHandled = { textEditFocusRequested = false },
                                layerKey = textLayer.id,
                                onConfigCommit = onCommitHistoryState,
                                onPositionChange = { px, py ->
                                    onUpdateLayerTransient { l ->
                                        l.positionX = px
                                        l.positionY = py
                                    }
                                },
                                onCenterText = onCenterSelectedLayer
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = stringResource(R.string.text_no_layer_selected),
                                    color = Color.White,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { onAddText(""); textEditFocusRequested = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.text_add_layer_button), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    ActiveTool.OBJECT_REMOVAL -> {
                        InpaintPanel(
                            brushSizeDp = brushSizeDp,
                            inpaintRadiusPx = inpaintRadiusPx,
                            featherPx = inpaintFeatherPx,
                            selectionMode = selectionMode,
                            inpaintSelectionMode = inpaintSelectionMode,
                            engineMode = inpaintEngineMode,
                            smartEdgeSnap = isSmartEdgeSnapEnabled,
                            seamlessBlend = isSeamlessBlendEnabled,
                            canUndoMask = canUndoSelection,
                            onBrushSizeChange = onBrushSizeChange,
                            onRadiusChange = onInpaintRadiusChange,
                            onFeatherChange = onInpaintFeatherChange,
                            onMaskModeChange = onSelectionModeSelect,
                            onInpaintSelectionModeChange = onInpaintSelectionModeChange,
                            onEngineModeChange = onInpaintEngineModeChange,
                            onToggleSmartEdgeSnap = onToggleSmartEdgeSnap,
                            onToggleSeamlessBlend = onToggleSeamlessBlend,
                            onClearMask = onClearSelection,
                            onUndoMask = onUndoSelection,
                            onRemoveClick = onObjectRemovalClick,
                            isProtectMode = isInpaintProtectMode,
                            onToggleProtectMode = onToggleInpaintProtect,
                            hasProtectArea = inpaintProtectMask != null,
                            onClearProtect = onClearInpaintProtect,
                            canRetry = canRetryRemoval,
                            onRetry = onRetryRemoval
                        )
                    }

                    else -> {}
                }
            }

            @Composable
            fun ToolBarItemsContent(isVertical: Boolean) {
                val tools = listOf(
                    Triple(stringResource(R.string.tool_adjust), Icons.Default.Tune, ActiveTool.ADJUST to "tool_btn_adjust"),
                    Triple(stringResource(R.string.tool_filters), Icons.Default.AutoFixHigh, ActiveTool.FILTERS to "tool_btn_filters"),
                    Triple(stringResource(R.string.tool_transform), Icons.Default.Transform, ActiveTool.TRANSFORM to "tool_btn_transform"),
                    Triple(stringResource(R.string.tool_crop), Icons.Default.Crop, ActiveTool.CROP_PERSPECTIVE to "tool_btn_crop"),
                    Triple(stringResource(R.string.tool_select), Icons.Default.SelectAll, ActiveTool.SELECTION to "tool_btn_selection"),
                    Triple(stringResource(R.string.tool_layers), Icons.Default.Layers, ActiveTool.LAYERS to "tool_btn_layers"),
                    Triple(stringResource(R.string.tool_text), Icons.Default.TextFields, ActiveTool.TEXT to "tool_btn_text"),
                    Triple(stringResource(R.string.tool_inpaint), Icons.Default.CleaningServices, ActiveTool.OBJECT_REMOVAL to "tool_btn_inpaint")
                )

                if (isVertical) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(vertical = 12.dp, horizontal = 4.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        tools.forEach { (title, icon, pair) ->
                            val (tool, testTag) = pair
                            ToolBarItem(
                                title = title,
                                icon = icon,
                                isActive = activeTool == tool,
                                onClick = { onToolSelect(if (activeTool == tool) ActiveTool.NONE else tool) },
                                testTag = testTag
                            )
                        }
                    }
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().background(StudioSurface),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(tools, key = { it.third.second }) { (title, icon, pair) ->
                            val (tool, testTag) = pair
                            ToolBarItem(
                                title = title,
                                icon = icon,
                                isActive = activeTool == tool,
                                onClick = { onToolSelect(if (activeTool == tool) ActiveTool.NONE else tool) },
                                testTag = testTag
                            )
                        }
                    }
                }
            }

            if (isWideScreen) {
                // Wide Screen Canonical Studio Layout (Tablet / Foldable)
                // Left: Vertical Navigation Rail | Center: Canvas Viewport | Right: Contextual Inspector Panel
                Row(modifier = Modifier.fillMaxSize()) {
                    // Left Tool Navigation Rail
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .fillMaxHeight()
                            .background(StudioSurface)
                    ) {
                        ToolBarItemsContent(isVertical = true)
                    }

                    // Center Canvas Viewport
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        CanvasView(
                            project = project,
                            renderRevision = renderRevision,
                            selectedLayerIndex = selectedLayerIndex,
                            activeTool = activeTool,
                            activeSelectionTool = activeSelectionTool,
                            inpaintSelectionMode = inpaintSelectionMode,
                            selectionMask = selectionMask,
                            brushSizeDp = brushSizeDp,
                            isPerspectiveActive = isPerspectiveActive,
                            perspectiveCorners = perspectiveCorners,
                            selectedAspectRatio = selectedAspectRatio,
                            onBrushStrokeComplete = onBrushStrokeComplete,
                            onLassoComplete = onLassoComplete,
                            onRectangleSelection = onRectangleSelection,
                            onEllipseSelection = onEllipseSelection,
                            onPolygonComplete = onPolygonComplete,
                            onMagicWandTap = onMagicWandTap,
                            onUpdatePerspectiveCorner = onUpdatePerspectiveCorner,
                            onUpdatePerspectiveCorners = onUpdatePerspectiveCorners,
                            onUpdateLayerTransient = onUpdateLayerTransient,
                            onCommitHistoryState = onCommitHistoryState,
                            onSelectLayer = onSelectLayer,
                            onApplyCropRect = { rect -> currentCropRect = rect },
                            modifier = Modifier.fillMaxSize(),
                            showSelectedLayerOriginal = compareOriginal,
                            proEffectPreview = proEffectPreview,
                            onRequestTextEdit = { textEditFocusRequested = true },
                            onCanvasSample = canvasSampler,
                            targetedAdjustActive = activeTool == ActiveTool.ADJUST && hslTargetProperty != null,
                            onTargetedDrag = onTargetedDrag,
                            protectMask = if (activeTool == ActiveTool.OBJECT_REMOVAL) inpaintProtectMask else null,
                            protectRevision = inpaintProtectRevision,
                            cropStraighten = cropStraighten,
                            cropGrid = cropGrid,
                            cropExpandCanvas = cropExpandCanvas,
                            transformMode = transformMode,
                            transformPivot = transformPivot,
                            transformFromCenter = transformFromCenter,
                            transformLockAspect = transformLockAspect,
                            transformSnap = transformSnap,
                            showGeneralOriginal = showGeneralOriginal || isGeneralComparing,
                            isSplitCompareActive = isSplitCompareActive,
                            splitComparePosition = splitComparePosition,
                            onSplitComparePositionChange = { splitComparePosition = it },
                            onToggleSplitCompare = { isSplitCompareActive = !isSplitCompareActive },
                            showMaskRubyOverlay = showMaskRubyOverlay,
                            maskBrushSizeDp = maskBrushSizeDp,
                            onStrokeSegment = onStrokeSegment,
                            selectionRevision = selectionRevision,
                            isMagneticSnap = isMagneticSnap,
                            magicWandTolerance = magicWandTolerance,
                            onMagicWandDrag = onMagicWandDrag,
                            editableShape = editableShape,
                            shapeFromCenter = shapeFromCenter,
                            shapeLockSquare = shapeLockSquare,
                            onShapeCreated = onShapeCreated,
                            onShapeEdited = onShapeEdited,
                            onMoveSelection = onMoveSelection,
                            isQuickMaskView = isQuickMaskView,
                            onTemporarySubtract = onTemporarySubtract
                        )

                        // Floating Before & After quick action pill
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSplitCompareActive || isGeneralComparing) AmberAccent else StudioSurface.copy(alpha = 0.88f),
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(top = 12.dp, start = 12.dp)
                                .testTag("btn_floating_compare_wide")
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = generalCompareInteraction,
                                        indication = null,
                                        // Press & hold shows the original; the split view is toggled from the top bar.
                                        onClick = {}
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    if (isSplitCompareActive) Icons.Default.CompareArrows else Icons.Default.Compare,
                                    contentDescription = stringResource(R.string.compare_title),
                                    tint = if (isSplitCompareActive || isGeneralComparing) Color.Black else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (isSplitCompareActive) stringResource(R.string.compare_split_mode) else stringResource(R.string.compare_title),
                                    color = if (isSplitCompareActive || isGeneralComparing) Color.Black else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                    }

                    // Right Studio Contextual Inspector Panel
                    AnimatedVisibility(
                        visible = activeTool != ActiveTool.NONE,
                        enter = slideInHorizontally { it },
                        exit = slideOutHorizontally { it }
                    ) {
                        Box(
                            modifier = Modifier
                                .width(340.dp)
                                .fillMaxHeight()
                                .background(StudioSurface)
                        ) {
                            ActiveContextualPanelContent()
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        CanvasView(
                            project = project,
                            renderRevision = renderRevision,
                            selectedLayerIndex = selectedLayerIndex,
                            activeTool = activeTool,
                            activeSelectionTool = activeSelectionTool,
                            inpaintSelectionMode = inpaintSelectionMode,
                            selectionMask = selectionMask,
                            brushSizeDp = brushSizeDp,
                            isPerspectiveActive = isPerspectiveActive,
                            perspectiveCorners = perspectiveCorners,
                            selectedAspectRatio = selectedAspectRatio,
                            onBrushStrokeComplete = onBrushStrokeComplete,
                            onLassoComplete = onLassoComplete,
                            onRectangleSelection = onRectangleSelection,
                            onEllipseSelection = onEllipseSelection,
                            onPolygonComplete = onPolygonComplete,
                            onMagicWandTap = onMagicWandTap,
                            onUpdatePerspectiveCorner = onUpdatePerspectiveCorner,
                            onUpdatePerspectiveCorners = onUpdatePerspectiveCorners,
                            onUpdateLayerTransient = onUpdateLayerTransient,
                            onCommitHistoryState = onCommitHistoryState,
                            onSelectLayer = onSelectLayer,
                            onApplyCropRect = { currentCropRect = it },
                            modifier = Modifier.fillMaxSize(),
                            showSelectedLayerOriginal = compareOriginal,
                            proEffectPreview = proEffectPreview,
                            onRequestTextEdit = { textEditFocusRequested = true },
                            onCanvasSample = canvasSampler,
                            targetedAdjustActive = activeTool == ActiveTool.ADJUST && hslTargetProperty != null,
                            onTargetedDrag = onTargetedDrag,
                            protectMask = if (activeTool == ActiveTool.OBJECT_REMOVAL) inpaintProtectMask else null,
                            protectRevision = inpaintProtectRevision,
                            cropStraighten = cropStraighten,
                            cropGrid = cropGrid,
                            cropExpandCanvas = cropExpandCanvas,
                            transformMode = transformMode,
                            transformPivot = transformPivot,
                            transformFromCenter = transformFromCenter,
                            transformLockAspect = transformLockAspect,
                            transformSnap = transformSnap,
                            showGeneralOriginal = showGeneralOriginal || isGeneralComparing,
                            isSplitCompareActive = isSplitCompareActive,
                            splitComparePosition = splitComparePosition,
                            onSplitComparePositionChange = { splitComparePosition = it },
                            onToggleSplitCompare = { isSplitCompareActive = !isSplitCompareActive },
                            showMaskRubyOverlay = showMaskRubyOverlay,
                            maskBrushSizeDp = maskBrushSizeDp,
                            onStrokeSegment = onStrokeSegment,
                            selectionRevision = selectionRevision,
                            isMagneticSnap = isMagneticSnap,
                            magicWandTolerance = magicWandTolerance,
                            onMagicWandDrag = onMagicWandDrag,
                            editableShape = editableShape,
                            shapeFromCenter = shapeFromCenter,
                            shapeLockSquare = shapeLockSquare,
                            onShapeCreated = onShapeCreated,
                            onShapeEdited = onShapeEdited,
                            onMoveSelection = onMoveSelection,
                            isQuickMaskView = isQuickMaskView,
                            onTemporarySubtract = onTemporarySubtract
                        )

                        // Floating Before & After quick action pill
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSplitCompareActive || isGeneralComparing) AmberAccent else StudioSurface.copy(alpha = 0.88f),
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(top = 12.dp, start = 12.dp)
                                .testTag("btn_floating_compare_compact")
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = generalCompareInteraction,
                                        indication = null,
                                        // Press & hold shows the original; the split view is toggled from the top bar.
                                        onClick = {}
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    if (isSplitCompareActive) Icons.Default.CompareArrows else Icons.Default.Compare,
                                    contentDescription = stringResource(R.string.compare_title),
                                    tint = if (isSplitCompareActive || isGeneralComparing) Color.Black else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (isSplitCompareActive) stringResource(R.string.compare_split_mode) else stringResource(R.string.compare_title),
                                    color = if (isSplitCompareActive || isGeneralComparing) Color.Black else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    AnimatedVisibility(visible = activeTool != ActiveTool.NONE, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                        val panelMaxHeight = when {
                            screenMaxHeight < 480.dp -> screenMaxHeight * 0.32f
                            screenMaxHeight < 600.dp -> screenMaxHeight * 0.36f
                            else -> screenMaxHeight * 0.42f
                        }
                        Box(Modifier.fillMaxWidth().heightIn(max = panelMaxHeight).background(StudioSurface)) { ActiveContextualPanelContent() }
                    }
                    ToolBarItemsContent(false)
                }
            }

            // Blocks the WHOLE editor (canvas, panels and tool rail) while a long operation runs,
            // so a second operation cannot start on top of the first one.
            if (isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                        .testTag("editor_processing_overlay"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = IndigoPrimary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = statusMessage ?: stringResource(R.string.status_processing),
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(
            width = project.width,
            height = project.height,
            onDismiss = { showExportDialog = false },
            onExportConfirm = { isPng, quality ->
                showExportDialog = false
                onExportConfirm(isPng, quality)
            }
        )
    }

    if (showRenameDialog) {
        RenameProjectDialog(
            initialName = project.name,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                showRenameDialog = false
                onRenameProject(newName)
            }
        )
    }

    if (isResizeDialogOpen) {
        CanvasResizeDialog(
            currentWidth = project.width,
            currentHeight = project.height,
            onDismiss = onCloseResizeDialog,
            onConfirmResize = onConfirmResizeCanvas
        )
    }

    if (isRefineEdgeDialogOpen) {
        com.example.ui.components.RefineEdgeDialog(
            onDismiss = onCloseRefineEdgeDialog,
            onApply = onApplyRefineEdge,
            preview = refinePreview,
            previewMode = refinePreviewMode,
            onPreviewModeChange = onRefinePreviewModeChange,
            onRequestPreview = onRequestRefinePreview
        )
    }
}

@Composable
private fun ToolBarItem(
    title: String,
    icon: ImageVector,
    isActive: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .sizeIn(minWidth = 68.dp, minHeight = 64.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (isActive) IndigoPrimary else StudioSurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isActive) Color.White else Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            color = if (isActive) IndigoPrimary else Color.White,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
        )
    }
}
