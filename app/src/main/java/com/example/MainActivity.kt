package com.example

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.example.data.model.SelectionToolType
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.PhotoEasyTheme
import com.example.ui.viewmodel.ActiveTool
import com.example.ui.viewmodel.EditorViewModel
import com.example.ui.viewmodel.Screen
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val viewModel: EditorViewModel by viewModels()
    private var pendingLegacyExport: Pair<Boolean, Int>? = null
    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingLegacyExport
        pendingLegacyExport = null
        if (granted && pending != null) {
            viewModel.exportImage(pending.first, pending.second)
        } else if (!granted && pending != null) {
            viewModel.notifyStoragePermissionDenied()
        }
    }
    private fun exportImageSafely(isPng: Boolean, quality: Int) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingLegacyExport = isPng to quality; storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else viewModel.exportImage(isPng, quality)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            val layoutDirection = when (uiState.currentLanguage) {
                "ar" -> LayoutDirection.Rtl
                "en" -> LayoutDirection.Ltr
                else -> if (Locale.getDefault().language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
            }

            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                PhotoEasyTheme {
                    when (uiState.currentScreen) {
                        Screen.HOME -> {
                            HomeScreen(
                                projects = uiState.projects,
                                isProcessing = uiState.isProcessing,
                                statusMessage = uiState.statusMessage,
                                onClearStatusMessage = { viewModel.clearStatusMessage() },
                                recoveryProjectId = uiState.recoveryProjectId,
                                onOpenProject = { viewModel.openProject(it) },
                                onRestoreSession = { viewModel.restoreRecoverySession() },
                                onDismissRecoverySession = { viewModel.dismissRecoverySession() },
                                onCreateNewCanvas = { name, w, h, bg ->
                                    viewModel.createNewProject(name, w, h, bg)
                                },
                                onImportImageUri = { uri ->
                                    viewModel.importImageAsNewProject(uri)
                                },
                                onOpenSampleProject = { viewModel.openSampleProject() },
                                onDeleteProject = { viewModel.deleteProject(it) },
                                onRenameProject = { id, name -> viewModel.renameProject(id, name) },
                                onOpenSettings = { viewModel.navigateTo(Screen.SETTINGS) }
                            )
                        }

                        Screen.EDITOR -> {
                            uiState.currentProject?.let { proj ->
                                EditorScreen(
                                    project = proj,
                                    renderRevision = uiState.renderRevision,
                                    selectedLayerIndex = uiState.selectedLayerIndex,
                                    activeTool = uiState.activeTool,
                                    activeSelectionTool = uiState.activeSelectionTool,
                                    selectionMask = uiState.selectionMask,
                                    brushSizeDp = uiState.brushSizeDp,
                                    magicWandTolerance = uiState.magicWandTolerance,
                                    isMagicWandContiguous = uiState.isMagicWandContiguous,
                                    inpaintRadiusPx = uiState.inpaintRadiusPx,
                                    inpaintFeatherPx = uiState.inpaintFeatherPx,
                                    canUndo = uiState.canUndo,
                                    canRedo = uiState.canRedo,
                                    canUndoSelection = uiState.canUndoSelection,
                                    canRedoSelection = uiState.canRedoSelection,
                                    isProjectDirty = uiState.isProjectDirty,
                                    isProcessing = uiState.isProcessing,
                                    statusMessage = uiState.statusMessage,
                                    isPerspectiveActive = uiState.isPerspectiveModeActive,
                                    perspectiveCorners = uiState.perspectiveCorners,
                                    selectedAspectRatio = uiState.selectedAspectRatio,
                                    isResizeDialogOpen = uiState.isResizeDialogOpen,
                                    isUnsavedChangesDialogOpen = uiState.isUnsavedChangesDialogOpen,
                                    onDismissUnsavedDialog = { viewModel.dismissUnsavedDialog() },
                                    onSaveAndExit = { viewModel.saveAndExitEditor() },
                                    onDiscardAndExit = { viewModel.discardAndExitEditor() },
                                    onBackClick = { viewModel.handleBackNavigation() },
                                    onToolSelect = { viewModel.selectTool(it) },
                                    onSelectLayer = { viewModel.selectLayer(it) },
                                    onUpdateLayer = { action -> viewModel.updateSelectedLayer(action) },
                                    onUpdateLayerTransient = { action -> viewModel.updateSelectedLayerTransient(action) },
                                    onCommitHistoryState = { viewModel.saveHistoryState() },
                                    onCancelHistoryState = { viewModel.cancelHistoryTransaction() },
                                    onOpacityChange = { op -> viewModel.updateSelectedLayerTransient { l -> l.opacity = op } },
                                    onBlendModeChange = { mode -> viewModel.updateSelectedLayer { l -> l.blendMode = mode } },
                                    onToggleVisibility = { viewModel.toggleSelectedLayerVisibility() },
                                    onToggleLock = { viewModel.toggleSelectedLayerLock() },
                                    onMoveUp = { viewModel.moveLayerUp() },
                                    onMoveDown = { viewModel.moveLayerDown() },
                                    onDuplicate = { viewModel.duplicateSelectedLayer() },
                                    onDelete = { viewModel.deleteSelectedLayer() },
                                    onMergeDown = { viewModel.mergeSelectedLayerDown() },
                                    onAddImageUri = { uri -> viewModel.addImageFromUri(uri) },
                                    onAddText = { text -> viewModel.addTextLayer(text) },
                                    onAddAdjustmentLayer = { viewModel.addAdjustmentLayer() },
                                    onSmartAutoClick = { viewModel.applySmartAutoCorrection() },
                                    onObjectRemovalClick = { viewModel.applyObjectRemoval() },
                                    inpaintSelectionMode = uiState.inpaintSelectionMode,
                                    inpaintEngineMode = uiState.inpaintEngineMode,
                                    isSmartEdgeSnapEnabled = uiState.isSmartEdgeSnapEnabled,
                                    isSeamlessBlendEnabled = uiState.isSeamlessBlendEnabled,
                                    onInpaintEngineModeChange = { viewModel.setInpaintEngineMode(it) },
                                    onToggleSmartEdgeSnap = { viewModel.toggleSmartEdgeSnap() },
                                    onToggleSeamlessBlend = { viewModel.toggleSeamlessBlend() },
                                    onInpaintSelectionModeChange = { viewModel.setInpaintSelectionMode(it) },
                                    onInpaintRadiusChange = { viewModel.setInpaintRadius(it) },
                                    onInpaintFeatherChange = { viewModel.setInpaintFeather(it) },
                                    onSelectionToolSelect = { viewModel.setSelectionTool(it) },
                                    isRefineEdgeDialogOpen = uiState.isRefineEdgeDialogOpen,
                                    onOpenRefineEdgeDialog = { viewModel.openRefineEdgeDialog() },
                                    onCloseRefineEdgeDialog = { viewModel.closeRefineEdgeDialog() },
                                    onApplyRefineEdge = { viewModel.applyRefineEdgeMatting(it) },
                                    onSelectSubject = { viewModel.autoSelectSubject() },
                                    isMagneticSnap = uiState.isMagneticSnapEnabled,
                                    onToggleMagneticSnap = { viewModel.toggleMagneticSnap() },
                                    selectionMode = uiState.selectionMode,
                                    onSelectionModeSelect = { viewModel.setSelectionMode(it) },
                                    onBrushSizeChange = { viewModel.setBrushSize(it) },
                                    onToleranceChange = { viewModel.setMagicWandTolerance(it) },
                                    onToggleMagicWandContiguous = { viewModel.setMagicWandContiguous(it) },
                                    onBrushStrokeComplete = { points, docRadius ->
                                        val activeLayer = uiState.currentProject?.layers?.getOrNull(uiState.selectedLayerIndex)
                                        if (activeLayer?.isEditingMask == true) {
                                            viewModel.paintOnLayerMask(points, docRadius, isReveal = !uiState.isMaskEditingBlack)
                                        } else if (uiState.activeSelectionTool == SelectionToolType.QUICK_SELECTION && uiState.activeTool == ActiveTool.SELECTION) {
                                            viewModel.applyQuickSelection(points, isEraser = false, docRadius = docRadius)
                                        } else {
                                            viewModel.applyBrushStroke(points, isEraser = false, docRadius = docRadius)
                                        }
                                    },
                                    onLassoComplete = { points -> viewModel.applyLassoSelection(points) },
                                    onRectangleSelection = { a, b -> viewModel.applyRectangleSelection(a, b) },
                                    onEllipseSelection = { a, b -> viewModel.applyEllipseSelection(a, b) },
                                    onPolygonComplete = { points -> viewModel.applyPolygonSelection(points) },
                                    onMagicWandTap = { x, y -> viewModel.applyMagicWand(x, y) },
                                    onFeatherSelection = { r -> viewModel.featherSelection(r) },
                                    onGrowSelection = { r -> viewModel.growSelection(r) },
                                    onShrinkSelection = { r -> viewModel.shrinkSelection(r) },
                                    onSmoothSelection = { r -> viewModel.smoothSelection(r) },
                                    onRefineSelectionEdge = { viewModel.refineSelectionEdge() },
                                    onSelectAll = { viewModel.selectAll() },
                                    onInvertSelection = { viewModel.invertSelection() },
                                    onClearSelection = { viewModel.clearSelection() },
                                    onCopySelectionToLayer = { viewModel.copySelectionToNewLayer() },
                                    onUndoSelection = { viewModel.undoSelection() },
                                    onRedoSelection = { viewModel.redoSelection() },
                                    onSaveProject = { viewModel.saveCurrentProject() },
                                    onExportConfirm = { isPng, quality -> exportImageSafely(isPng, quality) },
                                    // Live strokes: the selection / mask updates while the finger moves.
                                    onStrokeSegment = { points, radius, phase ->
                                        val activeLayer = uiState.currentProject?.layers?.getOrNull(uiState.selectedLayerIndex)
                                        when {
                                            uiState.activeTool == ActiveTool.LAYERS && activeLayer?.isEditingMask == true ->
                                                viewModel.paintMaskSegment(points, radius, isReveal = !uiState.isMaskEditingBlack, phase = phase)
                                            uiState.activeTool == ActiveTool.SELECTION && uiState.activeSelectionTool == SelectionToolType.QUICK_SELECTION ->
                                                viewModel.quickSelectionSegment(points, radius, phase)
                                            else -> viewModel.brushSelectionSegment(points, radius, phase)
                                        }
                                    },
                                    selectionRevision = uiState.selectionRevision,
                                    onMagicWandDrag = { x, y, tol, phase -> viewModel.magicWandDrag(x, y, tol, phase) },
                                    editableShape = uiState.editableShape,
                                    shapeFromCenter = uiState.shapeFromCenter,
                                    shapeLockSquare = uiState.shapeLockSquare,
                                    onToggleShapeFromCenter = { viewModel.setShapeFromCenter(it) },
                                    onToggleShapeLockSquare = { viewModel.setShapeLockSquare(it) },
                                    onShapeCreated = { rect, isEllipse -> viewModel.applyShapeSelection(rect, isEllipse) },
                                    onShapeEdited = { rect -> viewModel.editShapeSelection(rect) },
                                    onMoveSelection = { dx, dy -> viewModel.moveSelection(dx, dy) },
                                    isQuickMaskView = uiState.isQuickMaskView,
                                    onToggleQuickMask = { viewModel.toggleQuickMaskView() },
                                    onTemporarySubtract = { held ->
                                        viewModel.setSelectionModeOverride(if (held) com.example.data.model.SelectionMode.SUBTRACT else null)
                                    },
                                    modifierOp = uiState.selectionModifierOp,
                                    modifierAmount = uiState.selectionModifierAmount,
                                    onModifierPreview = { op, amount -> viewModel.previewSelectionModifier(op, amount) },
                                    onModifierCommit = { viewModel.endSelectionModifier() },
                                    isQuickAutoEnhance = uiState.isQuickSelectionAutoEnhance,
                                    onToggleQuickAutoEnhance = { viewModel.setQuickSelectionAutoEnhance(it) },
                                    isQuickSampleAll = uiState.isQuickSelectionSampleAll,
                                    onToggleQuickSampleAll = { viewModel.setQuickSelectionSampleAll(it) },
                                    refinePreview = uiState.refinePreview,
                                    refinePreviewMode = uiState.refinePreviewMode,
                                    onRefinePreviewModeChange = { viewModel.setRefinePreviewMode(it) },
                                    onRequestRefinePreview = { viewModel.requestRefinePreview(it) },
                                    exportSuccessUri = uiState.exportSuccessUri,
                                    onExportResultConsumed = { viewModel.consumeExportResult() },
                                    onRenameProject = { name -> viewModel.renameProject(proj.id, name) },
                                    onUndo = { viewModel.undo() },
                                    onRedo = { viewModel.redo() },
                                    onClearStatusMessage = { viewModel.clearStatusMessage() },
                                    onOpenResizeDialog = { viewModel.setResizeDialogOpen(true) },
                                    onCloseResizeDialog = { viewModel.setResizeDialogOpen(false) },
                                    onConfirmResizeCanvas = { w, h, scale -> viewModel.resizeCanvas(w, h, scale) },
                                    onRotate90Left = { viewModel.rotateSelectedLayer(-90f) },
                                    onRotate90Right = { viewModel.rotateSelectedLayer(90f) },
                                    onFlipHorizontal = { viewModel.flipSelectedLayer(true) },
                                    onFlipVertical = { viewModel.flipSelectedLayer(false) },
                                    onRotationChange = { viewModel.setLayerRotation(it) },
                                    onCenterSelectedLayer = { viewModel.centerSelectedLayer() },
                                    onFitSelectedLayerToCanvas = { viewModel.fitSelectedLayerToCanvas() },
                                    onFillSelectedLayerToCanvas = { viewModel.fillSelectedLayerToCanvas() },
                                    onResetTransform = { viewModel.resetSelectedLayerTransform() },
                                    onSelectAspectRatio = { viewModel.setCropAspectRatio(it) },
                                    onTogglePerspective = { viewModel.setPerspectiveMode(it) },
                                    onApplyCrop = { cropRect -> viewModel.applyCrop(cropRect, uiState.selectedAspectRatio) },
                                    onResetCrop = { viewModel.resetCrop() },
                                    onApplyPerspective = { viewModel.applyPerspectiveTransform() },
                                    onResetPerspective = { viewModel.resetPerspectiveCorners() },
                                    onUpdatePerspectiveCorner = { idx, x, y -> viewModel.updatePerspectiveCorner(idx, x, y) },
                                    onUpdatePerspectiveCorners = { corners -> viewModel.updatePerspectiveCorners(corners) },
                                    onFitPerspectiveToCanvas = { viewModel.fitPerspectiveCornersToCanvas() },
                                    onAddLayerMask = { fromSel -> viewModel.addLayerMask(fromSel) },
                                    onInvertLayerMask = { viewModel.invertLayerMask() },
                                    onToggleLayerMaskEnabled = { viewModel.toggleLayerMaskEnabled() },
                                    onDeleteLayerMask = { viewModel.deleteLayerMask() },
                                    onApplyLayerMask = { viewModel.applyLayerMask() },
                                    onSetEditingMask = { isEditing -> viewModel.setEditingMask(isEditing) },
                                    onToggleMaskPaintColor = { viewModel.toggleMaskPaintColor() },
                                    isMaskEditingBlack = uiState.isMaskEditingBlack,
                                    onApplyProEffect = { effect, p1, p2 -> viewModel.applyProFilter(effect, p1, p2) },
                                    onMaskDensityChange = { viewModel.setLayerMaskDensity(it) },
                                    onMaskFeatherChange = { viewModel.setLayerMaskFeather(it) },
                                    onRevealAllMask = { viewModel.revealAllLayerMask() },
                                    onHideAllMask = { viewModel.hideAllLayerMask() },
                                    onToggleRubyOverlay = { viewModel.toggleMaskRubyOverlay() },
                                    showMaskRubyOverlay = uiState.showMaskRubyOverlay,
                                    maskBrushSizeDp = uiState.maskBrushSizeDp,
                                    onMaskBrushSizeChange = { viewModel.setMaskBrushSize(it) }
                                )
                            }
                        }

                        Screen.SETTINGS -> {
                            // System back returns to the project list instead of closing the app.
                            BackHandler { viewModel.navigateTo(Screen.HOME) }
                            SettingsScreen(
                                currentLanguage = uiState.currentLanguage,
                                onLanguageChange = { viewModel.setLanguage(it) },
                                onBackClick = { viewModel.navigateTo(Screen.HOME) }
                            )
                        }
                    }
                }
            }
        }
    }
}
