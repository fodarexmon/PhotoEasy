package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.annotation.StringRes
import com.example.R
import androidx.lifecycle.viewModelScope
import com.example.data.model.ProFilterType
import com.example.data.model.AvailableFonts
import com.example.data.model.BlendModeType
import com.example.data.model.ColorAdjustments
import com.example.data.model.FilterType
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Project
import com.example.data.model.ProjectSnapshot
import com.example.data.model.ProjectSummary
import com.example.data.model.SelectionMode
import com.example.data.model.SelectionToolType
import com.example.data.model.TextAlignment
import com.example.data.model.TextLayerConfig
import com.example.data.repository.ProjectRepository
import com.example.engine.ImageProcessor
import com.example.engine.InpaintEngineMode
import com.example.engine.LayerRenderer
import com.example.engine.RefineEdgeConfig
import com.example.engine.RefineEdgeEngine
import com.example.engine.RefineResult
import com.example.engine.SelectionEngine
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.File
import java.util.Stack

enum class Screen {
    HOME,
    EDITOR,
    SETTINGS
}

enum class ActiveTool {
    NONE,
    ADJUST,
    FILTERS,
    /** The Filters panel opened on its Pro effects tab (blur, sharpen, oil paint... with sliders). */
    EFFECTS,
    /** Sticker library: emoji and vector stickers added as layers. */
    STICKERS,
    /** Retouch brushes: Blur, Sharpen, Smudge, Dodge, Burn, Clone Stamp, Patch. */
    RETOUCH,
    TRANSFORM,
    CROP_PERSPECTIVE,
    SELECTION,
    LAYERS,
    TEXT,
    OBJECT_REMOVAL
}

data class EditorUiState(
    val currentScreen: Screen = Screen.HOME,
    val projects: List<ProjectSummary> = emptyList(),
    val currentProject: Project? = null,
    val selectedLayerIndex: Int = -1,
    val activeTool: ActiveTool = ActiveTool.NONE,
    val isProcessing: Boolean = false,
    val statusMessage: String? = null,
    /** Progress 0..1 of a Pro effect being applied (null = none running). */
    val proEffectProgress: Float? = null,
    val activeSelectionTool: SelectionToolType = SelectionToolType.QUICK_BRUSH,
    val selectionMode: SelectionMode = SelectionMode.ADD,
    val selectionMask: Bitmap? = null,
    val brushSizeDp: Float = 24f,
    val magicWandTolerance: Int = 30,
    val inpaintRadiusPx: Float = 5f,
    val inpaintFeatherPx: Int = 4,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val exportSuccessUri: Uri? = null,
    /** Every file of the last export (several when exporting each layer separately). */
    val exportSuccessUris: List<Uri> = emptyList(),
    val exportSettings: com.example.engine.ExportSettings = com.example.engine.ExportSettings(),
    val exportEstimate: com.example.ui.components.ExportEstimate? = null,
    /** Progress 0..1 of an export (null = none running). */
    val exportProgress: Float? = null,
    val currentLanguage: String = "auto", // "auto", "en", "ar"
    val selectedAspectRatio: Float? = null,
    /** Crop: straighten angle in degrees (-45..45), applied together with the crop. */
    val cropStraighten: Float = 0f,
    /** Crop overlay: THIRDS, GOLDEN, DIAGONAL or NONE. */
    val cropGrid: String = "THIRDS",
    /** Crop: the frame may extend beyond the image to enlarge the canvas. */
    val cropExpandCanvas: Boolean = false,
    /** Crop: exact output size in pixels (0 = keep the cropped size). */
    val cropTargetWidth: Int = 0,
    val cropTargetHeight: Int = 0,
    /** Perspective: keystone sliders (-100..100) for converging verticals / horizontals. */
    val perspectiveVertical: Float = 0f,
    val perspectiveHorizontal: Float = 0f,
    /** Transform tool: FREE, SKEW or DISTORT, reference point 0..8 and options. */
    val transformMode: String = "FREE",
    val transformPivot: Int = 4,
    val transformFromCenter: Boolean = false,
    val transformLockAspect: Boolean = true,
    val transformSnap: Boolean = true,
    val isPerspectiveModeActive: Boolean = false,
    val perspectiveCorners: List<PointF> = emptyList(),
    val isResizeDialogOpen: Boolean = false,
    val isProjectDirty: Boolean = false,
    val isUnsavedChangesDialogOpen: Boolean = false,
    val recoveryProjectId: String? = null,
    val renderRevision: Long = 0L,
    val canUndoSelection: Boolean = false,
    val canRedoSelection: Boolean = false,
    val selectionRevision: Long = 0L,
    val isMagicWandContiguous: Boolean = true,
    val wandOptions: com.example.engine.WandOptions = com.example.engine.WandOptions(),
    val isQuickSelectionAutoEnhance: Boolean = true,
    val isQuickSelectionSampleAll: Boolean = false,
    /** Quick Selection has its own tolerance / edge settings (not shared with the magic wand). */
    val quickSelectionTolerance: Int = 35,
    val quickSelectionEdgeSensitivity: Int = 60,
    val isMaskEditingBlack: Boolean = true,
    val inpaintSelectionMode: InpaintSelectionMode = InpaintSelectionMode.BRUSH,
    val inpaintEngineMode: InpaintEngineMode = InpaintEngineMode.SMART,
    /** Object removal: the brush paints the "never copy texture from here" area instead of the object. */
    val isInpaintProtectMode: Boolean = false,
    val inpaintProtectMask: Bitmap? = null,
    val inpaintProtectRevision: Long = 0L,
    /** The last removal can be redone with different texture choices ("another result"). */
    val canRetryRemoval: Boolean = false,
    val isSmartEdgeSnapEnabled: Boolean = true,
    val isSeamlessBlendEnabled: Boolean = true,
    val showMaskRubyOverlay: Boolean = false,
    val maskBrushSizeDp: Float = 24f,
    /** Mask brush edge: 1 = hard, 0 = very soft. */
    val maskBrushHardness: Float = 0.6f,
    /** Mask brush opacity per stroke (0..1): low values paint grey for gradual blends. */
    val maskBrushOpacity: Float = 1f,
    /** Strength of the last Smart Auto on the selected layer (null = no Smart Auto to tune). */
    val smartAutoStrength: Float? = null,
    val isRefineEdgeDialogOpen: Boolean = false,
    val isMagneticSnapEnabled: Boolean = false,
    /** Last rectangle/ellipse selection; still editable with handles until another selection edit. */
    val editableShape: EditableShape? = null,
    /** Draw shapes from their centre / constrained to a square or circle. */
    val shapeFromCenter: Boolean = false,
    val shapeLockSquare: Boolean = false,
    /** Temporary mode while the on-canvas "hold to subtract" button is pressed. */
    val selectionModeOverride: SelectionMode? = null,
    /** Show unselected areas under a red overlay (Quick Mask) instead of marching ants only. */
    val isQuickMaskView: Boolean = false,
    val selectionModifierOp: SelectionModifierOp = SelectionModifierOp.FEATHER,
    val selectionModifierAmount: Float = 0f,
    val selectionRefine: SelectionRefineParams = SelectionRefineParams(),
    /** Live preview image of the Refine Edge dialog and how it is displayed. */
    val refinePreview: Bitmap? = null,
    /** The same preview area before refinement (hold "Before" in the refine workspace). */
    val refinePreviewOriginal: Bitmap? = null,
    /** Maps the refine preview's pixels back to the layer (for the refine brushes). */
    val refinePreviewGeom: RefinePreviewGeom? = null,
    val refinePreviewMode: RefinePreviewMode = RefinePreviewMode.OVERLAY,
    /** User filter library: imported LUTs, saved looks and favourite filter keys. */
    val luts: List<com.example.engine.LutInfo> = emptyList(),
    val customPresets: List<com.example.engine.CustomPreset> = emptyList(),
    val favoriteFilters: Set<String> = emptySet(),
    // Retouch brushes
    val retouchTool: com.example.engine.RetouchTool = com.example.engine.RetouchTool.BRUSH,
    val retouchSizeDp: Float = 60f,
    val retouchBrush: com.example.engine.RetouchBrush = com.example.engine.RetouchBrush(),
    /** Clone Stamp source point (document coordinates), set by tapping in "set source" mode. */
    val cloneSourceDoc: PointF? = null,
    val isCloneSourcePicking: Boolean = false,
    /** Patch: one finger draws a lasso (true) or drags the selection onto a clean area (false). */
    val patchSelecting: Boolean = true,
    /** Fill: the next tap picks the fill colour from the layer (eyedropper). */
    val isFillColorPicking: Boolean = false,
    /** Where the Clone Stamp is currently sampling (shown as a cross-hair on the canvas). */
    val retouchMarkerDoc: PointF? = null,
    /** Pre-retouch pixels per layer id, for the Before view of Before/After. */
    val compareBefore: Map<String, com.example.data.model.LayerBefore> = emptyMap(),
    /** Layers panel in multi-select mode, and the layers picked there. */
    val isLayerMultiSelect: Boolean = false,
    val selectedLayerIds: Set<String> = emptySet()
)

data class EditableShape(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val isEllipse: Boolean
)

enum class SelectionModifierOp { FEATHER, GROW, SHRINK, SMOOTH }

/**
 * Live selection refine (all at once, like Photoshop's Select and Mask): edge shift in px
 * (positive grows), smooth px, feather px and edge contrast 0..100.
 */
data class SelectionRefineParams(
    val shift: Float = 0f,
    val smooth: Float = 0f,
    val feather: Float = 0f,
    val contrast: Float = 0f
) {
    fun isIdentity(): Boolean = kotlin.math.abs(shift) < 0.25f && smooth < 0.5f && feather < 0.5f && contrast < 0.5f
}

/** Phase of a live on-canvas stroke (selection brush, quick selection, mask painting, wand drag). */
enum class StrokePhase { START, MOVE, END }

enum class RefinePreviewMode { OVERLAY, ON_BLACK, ON_WHITE, MASK }

/** Brushes of the refine workspace: Refine Edge (matte here), Add to / Subtract from the selection. */
enum class RefineBrushMode { REFINE, ADD, SUBTRACT }

/**
 * One brush stroke of the refine workspace. Points are in layer-normalised coordinates (0..1 of
 * the layer image), [radius] is a fraction of the layer image's longer side.
 */
data class RefineStroke(val mode: RefineBrushMode, val points: List<PointF>, val radius: Float)

/** Where the refine preview's pixels come from: [crop] of the layer preview, shown at [w] x [h]. */
data class RefinePreviewGeom(val crop: android.graphics.Rect, val proxyW: Int, val proxyH: Int, val w: Int, val h: Int)

enum class InpaintSelectionMode {
    BRUSH,
    LASSO,
    RECTANGLE
}

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private fun tr(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private val repository = ProjectRepository(application)
    private val prefs = application.getSharedPreferences("photoeasy_prefs", android.content.Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    // Autosave job handle

    private var autosaveJob: Job? = null
    private var pendingTransactionWasDirty: Boolean = false

    // Selection-only history (independent of document undo). Compressed, multi-level.
    private val selectionHistory = com.example.engine.SelectionHistory()

    private fun updateSelectionHistoryFlags() = updateHistoryFlags()

    /** Selection undo / redo steps usable on the CURRENT canvas size (see SelectionHistory.canUndo). */
    private fun selectionCanUndo(): Boolean {
        val p = _uiState.value.currentProject ?: return false
        return selectionHistory.canUndo(p.width, p.height)
    }

    private fun selectionCanRedo(): Boolean {
        val p = _uiState.value.currentProject ?: return false
        return selectionHistory.canRedo(p.width, p.height)
    }

    private fun clearSelectionHistory() {
        selectionHistory.clear()
        historyOrder.removeAll { it == HistoryKind.SELECTION }
        redoOrder.removeAll { it == HistoryKind.SELECTION }
        updateSelectionHistoryFlags()
    }

    /**
     * Records the CURRENT selection as an undo step. Published masks are never mutated in place
     * (copy-on-write), so the reference can safely be compressed on a background thread.
     */
    private suspend fun rememberSelectionState() {
        val mask = _uiState.value.selectionMask ?: return
        if (mask.isRecycled) return
        withContext(Dispatchers.Default) { selectionHistory.push(mask) }
        recordHistoryKind(HistoryKind.SELECTION)
        updateSelectionHistoryFlags()
    }

    fun undoSelection() {
        if (!selectionCanUndo()) return
        moveLastKind(historyOrder, redoOrder, HistoryKind.SELECTION)
        viewModelScope.launch {
            selectionMutex.withLock {
                endTransientSelectionSessions()
                val current = _uiState.value.selectionMask ?: return@withLock
                // The canvas may have changed size while this was queued: never publish a mask of another size.
                if (!selectionCanUndo()) { updateSelectionHistoryFlags(); return@withLock }
                val restored = withContext(Dispatchers.Default) { selectionHistory.undo(current) } ?: return@withLock
                publishSelection(restored)
                updateSelectionHistoryFlags()
            }
        }
    }

    fun redoSelection() {
        if (!selectionCanRedo()) return
        moveLastKind(redoOrder, historyOrder, HistoryKind.SELECTION)
        viewModelScope.launch {
            selectionMutex.withLock {
                endTransientSelectionSessions()
                val current = _uiState.value.selectionMask ?: return@withLock
                if (!selectionCanRedo()) { updateSelectionHistoryFlags(); return@withLock }
                val restored = withContext(Dispatchers.Default) { selectionHistory.redo(current) } ?: return@withLock
                publishSelection(restored)
                updateSelectionHistoryFlags()
            }
        }
    }

    /** Publishes a new selection mask object and invalidates everything that depends on it. */
    private fun publishSelection(mask: Bitmap, keepEditableShape: Boolean = false) {
        // A selection edit that was still running when the canvas changed size (crop, resize,
        // rotate, perspective) finishes with a mask of the old size: it no longer applies.
        val proj = _uiState.value.currentProject
        if (proj != null && (mask.width != proj.width || mask.height != proj.height)) return
        _uiState.value = _uiState.value.copy(
            selectionMask = mask,
            selectionRevision = _uiState.value.selectionRevision + 1,
            renderRevision = _uiState.value.renderRevision + 1,
            editableShape = if (keepEditableShape) _uiState.value.editableShape else null
        )
    }

    init {
        _uiState.value = _uiState.value.copy(
            exportSettings = com.example.engine.ExportSettings.fromJson(prefs.getString("export_settings_v1", null))
        )
        val savedLang = prefs.getString("app_language", "auto") ?: "auto"
        _uiState.value = _uiState.value.copy(currentLanguage = savedLang)
        applyAppLocale(savedLang)

        val lastProjectId = prefs.getString("last_opened_project_id", null)
        if (!lastProjectId.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(recoveryProjectId = lastProjectId)
        }

        loadProjects()
        loadFilterLibrary()
    }

    // --- Filter library (LUTs, saved looks, favourites) --------------------------------------

    private fun loadFilterLibrary() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            withContext(Dispatchers.IO) { com.example.engine.FilterLibrary.ensureLoaded(app) }
            refreshFilterLibraryState()
        }
    }

    private fun refreshFilterLibraryState() {
        val app = getApplication<Application>()
        _uiState.value = _uiState.value.copy(
            luts = com.example.engine.FilterLibrary.lutInfos(),
            customPresets = com.example.engine.FilterLibrary.presets(),
            favoriteFilters = com.example.engine.FilterLibrary.favorites(app)
        )
    }

    /** Imports a .cube LUT chosen by the user; it then appears in the "LUTs" category. */
    fun importLut(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val name = runCatching {
                app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            val result = withContext(Dispatchers.IO) { runCatching { com.example.engine.FilterLibrary.importLut(app, uri, name) } }
            refreshFilterLibraryState()
            _uiState.value = _uiState.value.copy(
                statusMessage = if (result.isSuccess) tr(R.string.status_lut_imported, result.getOrNull()!!.name) else tr(R.string.status_lut_invalid)
            )
        }
    }

    /** Projects (saved ones and the open one) whose layers use the LUT [id]. */
    suspend fun countProjectsUsingLut(id: String): Int {
        val ids = repository.projectIdsUsingLut(id).toMutableSet()
        _uiState.value.currentProject?.let { p -> if (p.layers.any { it.lutId == id }) ids.add(p.id) }
        return ids.size
    }

    fun deleteLut(id: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            withContext(Dispatchers.IO) { com.example.engine.FilterLibrary.deleteLut(app, id) }
            // Layers that used it simply lose the look (the renderer ignores unknown ids).
            refreshFilterLibraryState()
            _uiState.value = _uiState.value.copy(renderRevision = _uiState.value.renderRevision + 1)
        }
    }

    /** Saves the selected layer's adjustments + filter/LUT + intensity as a reusable look. */
    fun saveCurrentLookAsPreset(name: String) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(_uiState.value.selectedLayerIndex) ?: return
        val clean = name.trim().take(40).ifEmpty { return }
        val preset = com.example.engine.CustomPreset(
            id = java.util.UUID.randomUUID().toString(),
            name = clean,
            adjustments = layer.adjustments.copy(),
            filter = layer.filter,
            intensity = layer.filterIntensity,
            lutId = layer.lutId
        )
        viewModelScope.launch {
            withContext(Dispatchers.IO) { com.example.engine.FilterLibrary.savePreset(getApplication(), preset) }
            refreshFilterLibraryState()
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_preset_saved, clean))
        }
    }

    fun deleteCustomPreset(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { com.example.engine.FilterLibrary.deletePreset(getApplication(), id) }
            refreshFilterLibraryState()
        }
    }

    fun toggleFavoriteFilter(key: String) {
        val favs = com.example.engine.FilterLibrary.toggleFavorite(getApplication(), key)
        _uiState.value = _uiState.value.copy(favoriteFilters = favs)
    }

    private fun markDirty() {
        _uiState.value = _uiState.value.copy(isProjectDirty = true)
        scheduleAutosave()
    }

    private fun commitProjectMutation(
        statusMessage: String? = null,
        selectedLayerIndex: Int? = null,
        transformState: (EditorUiState) -> EditorUiState = { it }
    ) {
        val proj = _uiState.value.currentProject ?: return
        // Keep groups whole when a layer was added among a group's members.
        val selId = proj.layers.getOrNull(selectedLayerIndex ?: _uiState.value.selectedLayerIndex)?.id
        var reIndexed: Int? = null
        if (proj.layers.any { it.type == LayerType.GROUP }) {
            com.example.engine.LayerTree.adoptStrays(proj.layers)
            reIndexed = proj.layers.indexOfFirst { it.id == selId }.takeIf { it >= 0 }
        }
        proj.markDirty()
        markDirty()
        val newState = transformState(
            _uiState.value.copy(
                currentProject = proj.copy(layers = ArrayList(proj.layers)),
                renderRevision = proj.renderRevision,
                selectedLayerIndex = reIndexed ?: selectedLayerIndex ?: _uiState.value.selectedLayerIndex,
                statusMessage = statusMessage ?: _uiState.value.statusMessage
            )
        )
        _uiState.value = newState
        // The canvas size may have changed (crop, resize, rotate): selection steps of another
        // size stop being offered.
        updateHistoryFlags()
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(3000L) // 3 seconds debounced autosave
            // Do not autosave while an asynchronous long-running operation is in flight
            // A mask stroke paints its private mask copy in place: wait until the finger lifts
            // (endMaskStroke reschedules) so a half-painted mask is never written.
            if (_uiState.value.isProcessing || pendingTransactionSnapshot != null || maskStroke != null || retouchStrokeActive) {
                return@launch
            }
            val proj = _uiState.value.currentProject
            if (proj != null && _uiState.value.isProjectDirty) {
                val success = persistProject(proj)
                if (success) {
                    _uiState.value = _uiState.value.copy(isProjectDirty = false)
                }
            }
        }
    }

    /**
     * The app went to the background (Activity.onStop): save now instead of waiting for the
     * debounced autosave, because the system may kill the process at any time from here on.
     * An edit that is still open (e.g. a panel with a live preview) is saved as it looks but stays
     * open, so its Cancel still works; Cancel then saves the restored state again.
     */
    fun saveForBackground() {
        val state = _uiState.value
        if (state.currentScreen != Screen.EDITOR) return
        val proj = state.currentProject ?: return
        // A running operation commits (and schedules a save) when it finishes.
        if (state.isProcessing) return
        // The finger left the screen with the app: the stroke is over.
        maskStroke = null
        if (pendingTransactionSnapshot != null) pendingTransactionWasDirty = true
        if (!state.isProjectDirty && pendingTransactionSnapshot == null) {
            // Nothing to write but the selection (selection edits do not mark the project dirty).
            viewModelScope.launch { repository.saveSelection(proj.id, _uiState.value.selectionMask) }
            return
        }
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            if (persistProject(proj) && _uiState.value.currentProject?.id == proj.id) {
                _uiState.value = _uiState.value.copy(isProjectDirty = false)
            }
        }
    }

    fun restoreRecoverySession() {
        val id = _uiState.value.recoveryProjectId ?: return
        openProjectById(id)
        _uiState.value = _uiState.value.copy(recoveryProjectId = null)
    }

    fun dismissRecoverySession() {
        prefs.edit().remove("last_opened_project_id").apply()
        _uiState.value = _uiState.value.copy(recoveryProjectId = null)
    }

    fun handleBackNavigation() {
        if (_uiState.value.isProcessing) return
        // The project autosaves continuously, so "discard" could never really discard. Leaving the
        // editor therefore flushes the last pending changes; the dialog only appears if that fails.
        if (_uiState.value.isProjectDirty || pendingTransactionSnapshot != null) {
            if (pendingTransactionSnapshot != null) saveHistoryState()
            saveAndExitEditor()
        } else {
            exitEditor()
        }
    }

    fun dismissUnsavedDialog() {
        _uiState.value = _uiState.value.copy(isUnsavedChangesDialogOpen = false)
    }

    fun discardAndExitEditor() {
        dismissUnsavedDialog()
        exitEditor()
    }

    fun saveAndExitEditor() {
        val proj = _uiState.value.currentProject
        if (proj != null) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_saving_changes))
                val success = persistProject(proj)
                if (success) {
                    dismissUnsavedDialog()
                    exitEditor()
                } else {
                    // Offer: try again, or leave without the unsaved changes.
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        isProjectDirty = true,
                        isUnsavedChangesDialogOpen = true,
                        statusMessage = tr(R.string.status_save_failed_storage)
                    )
                }
            }
        } else {
            dismissUnsavedDialog()
            exitEditor()
        }
    }

    private fun exitEditor() {
        autosaveJob?.cancel()
        wandSourceCache = null
        // Keep the selection with the project (selection edits alone do not mark it dirty).
        val leavingProject = _uiState.value.currentProject
        val leavingSelection = _uiState.value.selectionMask
        if (leavingProject != null) {
            viewModelScope.launch { repository.saveSelection(leavingProject.id, leavingSelection) }
        }
        endTransientSelectionSessions()
        prefs.edit().remove("last_opened_project_id").apply()
        // Release editor-only memory (history snapshots can hold many bitmaps).
        undoStack.clear()
        redoStack.clear()
        historyOrder.clear()
        redoOrder.clear()
        lastRemoval = null
        protectLastPoint = null
        maskStroke = null
        retouchSession = null
        retouchPending = 0
        retouchStepOpen = false
        _uiState.value = _uiState.value.copy(compareBefore = emptyMap())
        com.example.engine.LayerRenderer.clearLookCache()
        cloneOffsetDoc = null
        pendingTransactionSnapshot = null
        pendingTransactionWasDirty = false
        clearSelectionHistory()
        _uiState.value = _uiState.value.copy(
            currentProject = null,
            currentScreen = Screen.HOME,
            isProjectDirty = false,
            isUnsavedChangesDialogOpen = false,
            // Reset transient editor state so nothing leaks into the next project
            // (e.g. a stuck "Saving…" overlay after Save & Exit).
            isProcessing = false,
            statusMessage = null,
            activeTool = ActiveTool.NONE,
            selectionMask = null,
            isPerspectiveModeActive = false,
            selectedAspectRatio = null,
            isResizeDialogOpen = false,
            isRefineEdgeDialogOpen = false,
            canUndo = false,
            canRedo = false
        )
        // The project list must reflect new projects, renamed timestamps and fresh thumbnails.
        loadProjects()
    }

    fun navigateTo(screen: Screen) {
        if (screen == Screen.HOME && _uiState.value.currentScreen == Screen.EDITOR) {
            handleBackNavigation()
        } else {
            _uiState.value = _uiState.value.copy(currentScreen = screen)
        }
    }

    fun setLanguage(lang: String) {
        prefs.edit().putString("app_language", lang).apply()
        _uiState.value = _uiState.value.copy(currentLanguage = lang)
        applyAppLocale(lang)
    }

    private fun applyAppLocale(lang: String) {
        val localeList = when (lang) {
            "ar" -> LocaleListCompat.forLanguageTags("ar")
            "en" -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        // Already in effect (the normal case on start, since AppCompat / Android 13+ keep the choice):
        // setting it again would recreate the Activity and flash the screen.
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() == localeList.toLanguageTags()) return
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    fun loadProjects() {
        // Runs in the background without touching isProcessing: it is triggered while other
        // operations (import, save & exit) may still own the progress state.
        viewModelScope.launch {
            val list = repository.getProjectSummaries()
            _uiState.value = _uiState.value.copy(projects = list)
        }
    }

    fun createNewProject(name: String, width: Int, height: Int, bgColorHex: Int) {
        viewModelScope.launch {
            val proj = Project(
                name = name.ifBlank { tr(R.string.new_canvas_name_default) },
                width = width,
                height = height
            )

            // Add background layer
            val bgLayer = Layer(
                id = java.util.UUID.randomUUID().toString(),
                name = tr(R.string.layer_name_background),
                type = LayerType.COLOR_FILL,
                colorFillHex = bgColorHex
            )
            proj.layers.add(bgLayer)

            // Opening a project that is not on disk would lose it as soon as the app closes.
            if (!repository.saveProject(proj)) {
                repository.deleteProject(proj.id)
                _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_save_failed_storage))
                return@launch
            }
            openProject(proj)
        }
    }

    fun openProject(summary: ProjectSummary) {
        openProjectById(summary.id)
    }

    fun openProjectById(projectId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true)
            val project = repository.getProjectById(projectId)
            if (project != null) {
                openProject(project)
                _uiState.value = _uiState.value.copy(isProcessing = false)
            } else {
                // Missing/corrupt project (e.g. a stale recovery entry): tell the user and forget it.
                if (prefs.getString("last_opened_project_id", null) == projectId) {
                    prefs.edit().remove("last_opened_project_id").apply()
                }
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    recoveryProjectId = if (_uiState.value.recoveryProjectId == projectId) null else _uiState.value.recoveryProjectId,
                    statusMessage = tr(R.string.status_project_open_failed)
                )
                loadProjects()
            }
        }
    }

    fun openProject(project: Project) {
        pendingTransactionSnapshot = null
        maskStroke = null
        retouchSession = null
        retouchPending = 0
        retouchStepOpen = false
        _uiState.value = _uiState.value.copy(compareBefore = emptyMap(), isLayerMultiSelect = false, selectedLayerIds = emptySet())
        com.example.engine.LayerRenderer.clearLookCache()
        cloneOffsetDoc = null
        undoStack.clear()
        redoStack.clear()
        historyOrder.clear()
        redoOrder.clear()
        lastRemoval = null
        protectLastPoint = null

        prefs.edit().putString("last_opened_project_id", project.id).apply()

        clearSelectionHistory()
        _uiState.value = _uiState.value.copy(
            currentProject = project,
            selectedLayerIndex = if (project.layers.isNotEmpty()) project.layers.size - 1 else -1,
            activeTool = ActiveTool.NONE,
            currentScreen = Screen.EDITOR,
            canUndo = false,
            canRedo = false,
            isProjectDirty = false,
            isUnsavedChangesDialogOpen = false,
            isProcessing = false,
            statusMessage = null,
            selectionMask = SelectionEngine.createBlankMask(project.width, project.height),
            editableShape = null,
            selectionModeOverride = null
        )
        // Restore the selection saved with the project, if any.
        viewModelScope.launch {
            val saved = repository.loadSelection(project.id, project.width, project.height) ?: return@launch
            if (_uiState.value.currentProject?.id == project.id) publishSelection(saved)
        }
        // Before/After of pixel edits made in earlier sessions.
        viewModelScope.launch {
            val ids = project.layers.map { it.id }.toSet()
            val loaded = repository.loadBeforeImages(project.id, ids)
            if (loaded.isEmpty()) return@launch
            if (_uiState.value.currentProject?.id != project.id) { loaded.values.forEach { it.bitmap.recycle() }; return@launch }
            // Edits made meanwhile in this session keep their own "before".
            _uiState.value = _uiState.value.copy(compareBefore = loaded + _uiState.value.compareBefore)
        }
    }

    fun saveCurrentProject() {
        val proj = _uiState.value.currentProject ?: return
        autosaveJob?.cancel()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_saving_project))
            val success = persistProject(proj)
            _uiState.value = _uiState.value.copy(
                isProcessing = false,
                isProjectDirty = if (success) false else _uiState.value.isProjectDirty,
                statusMessage = if (success) tr(R.string.project_saved) else tr(R.string.status_save_failed_storage)
            )
            if (success) {
                loadProjects()
            }
        }
    }

    /** Deletes several projects at once, then refreshes the list a single time. */
    fun deleteProjects(projectIds: Collection<String>) {
        if (projectIds.isEmpty()) return
        viewModelScope.launch {
            val ids = projectIds.toSet()
            for (id in ids) repository.deleteProject(id)
            val last = prefs.getString("last_opened_project_id", null)
            if (_uiState.value.recoveryProjectId in ids || last in ids) {
                prefs.edit().remove("last_opened_project_id").apply()
                _uiState.value = _uiState.value.copy(recoveryProjectId = null)
            }
            if (_uiState.value.currentProject?.id in ids) {
                _uiState.value = _uiState.value.copy(currentProject = null, currentScreen = Screen.HOME)
            }
            loadProjects()
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            repository.deleteProject(projectId)
            if (_uiState.value.recoveryProjectId == projectId || prefs.getString("last_opened_project_id", null) == projectId) {
                prefs.edit().remove("last_opened_project_id").apply()
                _uiState.value = _uiState.value.copy(recoveryProjectId = null)
            }
            if (_uiState.value.currentProject?.id == projectId) {
                _uiState.value = _uiState.value.copy(currentProject = null, currentScreen = Screen.HOME)
            }
            loadProjects()
        }
    }

    override fun onCleared() {
        retouchExecutor?.shutdown()
        super.onCleared()
    }

    fun selectTool(tool: ActiveTool) {
        val previous = _uiState.value.activeTool
        // Leaving the retouch brushes frees the full-resolution pixels they keep.
        if (previous == ActiveTool.RETOUCH && tool != ActiveTool.RETOUCH && !retouchStrokeActive) retouchSession = null
        // Leaving the Adjust panel: an untouched selection adjustment layer goes away (no undo step).
        if (previous == ActiveTool.ADJUST && tool != ActiveTool.ADJUST) endSelectionLook()
        // Leaving the layers panel ends picking several layers.
        if (previous == ActiveTool.LAYERS && tool != ActiveTool.LAYERS && _uiState.value.isLayerMultiSelect)
            _uiState.value = _uiState.value.copy(isLayerMultiSelect = false, selectedLayerIds = emptySet())
        // Leaving a transient editor (filters/adjustments/text/transform) should never
        // leave a half-open history transaction. Commit what the user can currently see.
        if (previous != tool && pendingTransactionSnapshot != null) {
            saveHistoryState()
        }
        _uiState.value = _uiState.value.copy(
            activeTool = tool,
            isPerspectiveModeActive = if (tool == ActiveTool.CROP_PERSPECTIVE) _uiState.value.isPerspectiveModeActive else false,
            selectionMode = if (tool == ActiveTool.OBJECT_REMOVAL) SelectionMode.ADD else _uiState.value.selectionMode,
            // Leaving the crop tool discards an unapplied straighten / exact size.
            cropStraighten = if (tool == ActiveTool.CROP_PERSPECTIVE) _uiState.value.cropStraighten else 0f,
            cropTargetWidth = if (tool == ActiveTool.CROP_PERSPECTIVE) _uiState.value.cropTargetWidth else 0,
            cropTargetHeight = if (tool == ActiveTool.CROP_PERSPECTIVE) _uiState.value.cropTargetHeight else 0
        )
        if (previous != tool) when (tool) {
            // With a selection: Adjust works on a clipped adjustment layer masked by it...
            ActiveTool.ADJUST -> beginSelectionLook()
            // ...and Transform moves only the selected pixels (cut to their own layer first).
            ActiveTool.TRANSFORM -> floatSelectionForTransform()
            else -> {}
        }
    }

    /** Restore the state captured at the beginning of the current transient edit. */
    fun cancelHistoryTransaction() {
        val snapshot = pendingTransactionSnapshot ?: return
        val proj = _uiState.value.currentProject ?: run { pendingTransactionSnapshot = null; return }
        val prevIdx = _uiState.value.selectedLayerIndex
        val prevId = proj.layers.getOrNull(prevIdx)?.id
        proj.width = snapshot.width
        proj.height = snapshot.height
        restoreLayers(proj, snapshot.layers.map { it.snapshotLayer() })
        proj.markDirty()
        pendingTransactionSnapshot = null
        val wasDirty = pendingTransactionWasDirty
        pendingTransactionWasDirty = false
        // A layer added during the edit (e.g. a selection adjustment) is gone: select the one below it.
        val keptIdx = proj.layers.indexOfFirst { it.id == prevId }.takeIf { it >= 0 }
            ?: (prevIdx - 1).coerceIn(0, (proj.layers.size - 1).coerceAtLeast(0))
        _uiState.value = _uiState.value.copy(
            currentProject = proj.copy(layers = ArrayList(proj.layers)),
            renderRevision = proj.renderRevision,
            selectedLayerIndex = keptIdx,
            isProjectDirty = wasDirty
        )
        if (wasDirty) scheduleAutosave()
        updateHistoryFlags()
    }

    fun selectLayer(index: Int) {
        val proj = _uiState.value.currentProject ?: return
        if (index in proj.layers.indices) {
            if (smartAutoSession?.layerId != proj.layers[index].id) clearSmartAutoSession()
            _uiState.value = _uiState.value.copy(selectedLayerIndex = index)
        }
    }

    // --- History Stack Management ---
    fun beginHistoryTransaction() {
        if (pendingTransactionSnapshot == null) {
            val proj = _uiState.value.currentProject ?: return
            pendingTransactionWasDirty = _uiState.value.isProjectDirty
            pendingTransactionSnapshot = ProjectSnapshot(
                width = proj.width,
                height = proj.height,
                layers = proj.layers.map { it.snapshotLayer() },
                renderRevision = proj.renderRevision
            )
        }
    }

    fun commitHistoryTransaction() {
        saveHistoryState()
    }

    // History stack for Undo / Redo with Memory-Budget Management
    private val undoStack = Stack<ProjectSnapshot>()
    private val redoStack = Stack<ProjectSnapshot>()
    private var pendingTransactionSnapshot: ProjectSnapshot? = null

    // One timeline for Undo / Redo: document edits and selection edits in the order they happened,
    // so the main Undo button also undoes selections. Entries whose snapshot was evicted by the
    // memory budget are skipped.
    private enum class HistoryKind { PROJECT, SELECTION }
    private val historyOrder = ArrayDeque<HistoryKind>()
    private val redoOrder = ArrayDeque<HistoryKind>()

    private fun recordHistoryKind(kind: HistoryKind) {
        historyOrder.addLast(kind)
        while (historyOrder.size > 400) historyOrder.removeFirst()
        // A new edit of either kind ends the redo branch of both.
        redoOrder.clear()
        if (kind == HistoryKind.PROJECT) selectionHistory.clearRedo() else redoStack.clear()
    }

    private fun moveLastKind(from: ArrayDeque<HistoryKind>, to: ArrayDeque<HistoryKind>, kind: HistoryKind) {
        val i = from.lastIndexOf(kind)
        if (i >= 0) from.removeAt(i)
        to.addLast(kind)
    }

    private fun nextKind(order: ArrayDeque<HistoryKind>, projectAvailable: Boolean, selectionAvailable: Boolean): HistoryKind? {
        while (order.isNotEmpty()) {
            val k = order.last()
            if ((k == HistoryKind.PROJECT && projectAvailable) || (k == HistoryKind.SELECTION && selectionAvailable)) return k
            order.removeLast()
        }
        return when {
            projectAvailable -> HistoryKind.PROJECT
            selectionAvailable -> HistoryKind.SELECTION
            else -> null
        }
    }

    companion object {
        private const val MAX_HISTORY_STEPS = 15
        private const val MAX_HISTORY_MEMORY_BYTES = 80L * 1024L * 1024L // 80 MB History RAM Budget
    }

    /**
     * Memory that history keeps alive ON TOP of the open document: every distinct bitmap / mask
     * referenced by undo, redo or the pending transaction, counted once (snapshots share unchanged
     * pixels with each other and with the live layers), excluding those the live layers still use.
     */
    private fun historyExtraBytes(): Long {
        val live = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Bitmap, Boolean>())
        _uiState.value.currentProject?.layers?.forEach { l ->
            l.bitmap?.let { live.add(it) }
            l.maskBitmap?.let { live.add(it) }
        }
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Bitmap, Boolean>())
        var bytes = 0L
        fun count(b: Bitmap?) {
            if (b != null && !b.isRecycled && b !in live && seen.add(b)) bytes += b.allocationByteCount.toLong()
        }
        fun countSnapshot(s: ProjectSnapshot?) = s?.layers?.forEach { count(it.bitmap); count(it.maskBitmap) }
        undoStack.forEach { countSnapshot(it) }
        redoStack.forEach { countSnapshot(it) }
        countSnapshot(pendingTransactionSnapshot)
        return bytes
    }

    private fun enforceHistoryMemoryBudget() {
        var evicted = false
        while (undoStack.size > MAX_HISTORY_STEPS) {
            undoStack.removeAt(0)
            evicted = true
        }
        // Evict the oldest steps while the pixels only history holds exceed the budget.
        while (undoStack.size > 2 && historyExtraBytes() > MAX_HISTORY_MEMORY_BYTES) {
            undoStack.removeAt(0)
            evicted = true
        }
        if (evicted) updateHistoryFlags()
    }

    fun saveHistoryState() {
        val proj = _uiState.value.currentProject ?: return
        val snapshot = pendingTransactionSnapshot ?: ProjectSnapshot(
            width = proj.width,
            height = proj.height,
            layers = proj.layers.map { it.snapshotLayer() },
            renderRevision = proj.renderRevision
        )
        undoStack.push(snapshot)
        enforceHistoryMemoryBudget()
        redoStack.clear()
        recordHistoryKind(HistoryKind.PROJECT)
        pendingTransactionSnapshot = null
        pendingTransactionWasDirty = false
        updateHistoryFlags()
        if (_uiState.value.isProjectDirty) scheduleAutosave()
    }

    fun undo() {
        val proj = _uiState.value.currentProject ?: return
        val prevLayerId = proj.layers.getOrNull(_uiState.value.selectedLayerIndex)?.id

        if (pendingTransactionSnapshot != null) {
            val snapshot = pendingTransactionSnapshot!!
            // The live edit is a new step: it ends the old redo branch and becomes redoable itself.
            redoStack.clear()
            redoOrder.clear()
            selectionHistory.clearRedo()
            redoStack.push(ProjectSnapshot(
                width = proj.width,
                height = proj.height,
                layers = proj.layers.map { it.snapshotLayer() },
                renderRevision = proj.renderRevision
            ))
            redoOrder.addLast(HistoryKind.PROJECT)
            pendingTransactionSnapshot = null
            pendingTransactionWasDirty = false
            proj.width = snapshot.width
            proj.height = snapshot.height
            restoreLayers(proj, snapshot.layers)
            proj.markDirty()

            val (keptLayerIndex, newMask) = selectionAfterHistoryChange(prevLayerId, proj)
            _uiState.value = _uiState.value.copy(
                currentProject = proj.copy(layers = ArrayList(proj.layers)),
                renderRevision = proj.renderRevision,
                selectedLayerIndex = keptLayerIndex,
                selectionMask = newMask
            )
            updateHistoryFlags()
            markDirty()
            return
        }

        when (nextKind(historyOrder, undoStack.isNotEmpty(), selectionCanUndo())) {
            HistoryKind.SELECTION -> { undoSelection(); return }
            HistoryKind.PROJECT -> moveLastKind(historyOrder, redoOrder, HistoryKind.PROJECT)
            null -> return
        }
        val currentSnapshot = ProjectSnapshot(
            width = proj.width,
            height = proj.height,
            layers = proj.layers.map { it.snapshotLayer() },
            renderRevision = proj.renderRevision
        )
        redoStack.push(currentSnapshot)

        val previousSnapshot = undoStack.pop()
        proj.width = previousSnapshot.width
        proj.height = previousSnapshot.height
        restoreLayers(proj, previousSnapshot.layers)
        proj.markDirty()

        val (keptLayerIndex, newMask) = selectionAfterHistoryChange(prevLayerId, proj)

        _uiState.value = _uiState.value.copy(
            currentProject = proj.copy(layers = ArrayList(proj.layers)),
            renderRevision = proj.renderRevision,
            selectedLayerIndex = keptLayerIndex,
            selectionMask = newMask
        )
        updateHistoryFlags()
        markDirty()
    }

    fun redo() {
        val proj = _uiState.value.currentProject ?: return
        when (nextKind(redoOrder, redoStack.isNotEmpty(), selectionCanRedo())) {
            HistoryKind.SELECTION -> { redoSelection(); return }
            HistoryKind.PROJECT -> moveLastKind(redoOrder, historyOrder, HistoryKind.PROJECT)
            null -> return
        }
        val prevLayerId = proj.layers.getOrNull(_uiState.value.selectedLayerIndex)?.id
        val currentSnapshot = ProjectSnapshot(
            width = proj.width,
            height = proj.height,
            layers = proj.layers.map { it.snapshotLayer() },
            renderRevision = proj.renderRevision
        )
        undoStack.push(currentSnapshot)

        val nextSnapshot = redoStack.pop()
        proj.width = nextSnapshot.width
        proj.height = nextSnapshot.height
        restoreLayers(proj, nextSnapshot.layers)
        proj.markDirty()

        val (keptLayerIndex, newMask) = selectionAfterHistoryChange(prevLayerId, proj)

        _uiState.value = _uiState.value.copy(
            currentProject = proj.copy(layers = ArrayList(proj.layers)),
            renderRevision = proj.renderRevision,
            selectedLayerIndex = keptLayerIndex,
            selectionMask = newMask
        )
        updateHistoryFlags()
        markDirty()
    }

    /**
     * Restores the snapshot pushed by the most recent [saveHistoryState] without creating a redo
     * entry. Used when a multi-layer operation fails halfway and must leave no partial changes.
     */
    private fun rollbackToLastHistoryState() {
        val proj = _uiState.value.currentProject ?: return
        if (undoStack.isEmpty()) return
        historyOrder.lastIndexOf(HistoryKind.PROJECT).takeIf { it >= 0 }?.let { historyOrder.removeAt(it) }
        val snapshot = undoStack.pop()
        proj.width = snapshot.width
        proj.height = snapshot.height
        restoreLayers(proj, snapshot.layers)
        proj.markDirty()
        _uiState.value = _uiState.value.copy(
            currentProject = proj.copy(layers = ArrayList(proj.layers)),
            renderRevision = proj.renderRevision
        )
        updateHistoryFlags()
    }

    /**
     * Replaces the document's layers with history [layers]. Every layer whose pixels differ from
     * what it showed before is marked dirty: the file on disk may hold newer pixels saved after the
     * snapshot was taken (one layer_<id> file per layer), so a stale "clean" flag would skip the
     * write and the reopened project would show the undone edit again.
     */
    private fun restoreLayers(proj: Project, layers: List<Layer>) {
        val before = java.util.HashMap<String, Bitmap?>()
        proj.layers.forEach { before[it.id] = it.bitmap }
        proj.layers.clear()
        proj.layers.addAll(layers)
        for (l in proj.layers) {
            if (l.bitmap != null && (!before.containsKey(l.id) || before[l.id] !== l.bitmap)) l.isBitmapDirty = true
        }
    }

    /** Source asset files still referenced by undo/redo history must survive orphan cleanup. */
    private fun historyProtectedFileNames(): Set<String> {
        val names = mutableSetOf<String>()
        fun collect(snapshot: ProjectSnapshot?) {
            snapshot?.layers?.forEach { l ->
                l.sourceAssetPath?.let { names.add(File(it).name) }
                // A layer that undo / redo can bring back keeps its saved Before image.
                names.add(com.example.data.repository.ProjectRepository.BEFORE_KEEP_PREFIX + l.id)
            }
        }
        undoStack.forEach { collect(it) }
        redoStack.forEach { collect(it) }
        collect(pendingTransactionSnapshot)
        return names
    }

    /**
     * Saves [proj]. The repository writes on a background thread while the editor keeps changing
     * the live project, so it gets a frozen copy taken here on the main thread: its own layer list
     * and per-layer field values (bitmaps and masks are shared; they are replaced, never edited in
     * place, except the private mask copy of a stroke in progress, see [endMaskStroke]).
     */
    private suspend fun persistProject(proj: Project): Boolean {
        val frozenLayers = proj.layers.map { it.previewSnapshot() }
        val frozen = proj.copy(layers = ArrayList(frozenLayers))
        val ok = repository.saveProject(frozen, historyProtectedFileNames())
        if (ok) {
            proj.updatedAt = frozen.updatedAt
            proj.thumbnailPath = frozen.thumbnailPath
            // A layer is clean only if the pixels just written are still its pixels: a destructive
            // edit that landed during the save keeps the layer dirty for the next save.
            val savedById = frozenLayers.associateBy { it.id }
            for (live in proj.layers) {
                val saved = savedById[live.id] ?: continue
                if (live.isBitmapDirty && !saved.isBitmapDirty && live.bitmap === saved.bitmap) {
                    live.isBitmapDirty = false
                }
            }
        }
        // The active selection is kept with the project so it survives closing the app.
        if (ok && _uiState.value.currentProject?.id == proj.id) {
            repository.saveSelection(proj.id, _uiState.value.selectionMask)
        }
        return ok
    }

    /**
     * After undo/redo keep the user's context: the same layer (matched by id) stays selected and
     * the current selection survives when the canvas size did not change.
     */
    private fun selectionAfterHistoryChange(prevLayerId: String?, proj: Project): Pair<Int, Bitmap> {
        val byId = if (prevLayerId != null) proj.layers.indexOfFirst { it.id == prevLayerId } else -1
        val index = if (byId >= 0) byId else (proj.layers.size - 1).coerceAtLeast(-1)
        val current = _uiState.value.selectionMask
        val mask = if (current != null && !current.isRecycled && current.width == proj.width && current.height == proj.height) {
            current
        } else {
            SelectionEngine.createBlankMask(proj.width, proj.height)
        }
        return index to mask
    }

    private fun updateHistoryFlags() {
        val removal = lastRemoval
        _uiState.value = _uiState.value.copy(
            canRetryRemoval = removal != null && undoStack.size == removal.undoDepth && redoStack.isEmpty() && pendingTransactionSnapshot == null,
            canUndo = undoStack.isNotEmpty() || pendingTransactionSnapshot != null || selectionCanUndo(),
            canRedo = redoStack.isNotEmpty() || selectionCanRedo(),
            canUndoSelection = selectionCanUndo(),
            canRedoSelection = selectionCanRedo()
        )
    }

    private suspend fun loadAuthoritativeLayerBitmap(layer: Layer): Bitmap? {
        // A retouch stroke may still be writing this layer's newest source file.
        awaitRetouchWrites()
        return loadAuthoritativeLayerBitmapNow(layer)
    }

    private suspend fun loadAuthoritativeLayerBitmapNow(layer: Layer): Bitmap? = withContext(Dispatchers.IO) {
        val maxDim = kotlin.math.max(layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight()).toInt().coerceIn(64, 4096)
        layer.sourceAssetPath?.let { path -> File(path).takeIf { it.exists() }?.let { ImageProcessor.decodeFileWithExifSampled(it, maxDim) }?.let { return@withContext it } }
        layer.bitmap?.copy(Bitmap.Config.ARGB_8888, true)
    }

    private suspend fun commitDestructiveBitmap(project: Project, layer: Layer, full: Bitmap, contentW: Float = layer.intrinsicDocumentWidth(), contentH: Float = layer.intrinsicDocumentHeight(), prefix: String = "edit", keepBefore: Boolean = false): Boolean {
        // Pixel edits that Before/After should undo: keep the layer's pixels from before the first one.
        if (keepBefore) layer.bitmap?.takeIf { !it.isRecycled }?.let { rememberBeforeEdit(layer, it) }
        // The re-encoded source has no EXIF: keep the photo's metadata first (older projects).
        if (layer.exifTags == null) {
            layer.sourceAssetPath?.let { path ->
                layer.exifTags = withContext(Dispatchers.IO) { com.example.engine.ExportEngine.readMetadata(File(path)) }
            }
        }
        val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "png"
        val file = File(repository.getProjectDir(project.id), "source_${prefix}_${layer.id}_${System.currentTimeMillis()}.$ext")
        if (!withContext(Dispatchers.IO) { ImageProcessor.saveLosslessBitmap(full, file) }) return false
        val preview = withContext(Dispatchers.Default) { ImageProcessor.scaleBitmapToMaxDimension(full, 2048) }
        layer.bitmap = preview; layer.sourceAssetPath=file.absolutePath; layer.originalWidth=full.width; layer.originalHeight=full.height
        layer.contentWidth=contentW.coerceAtLeast(1f); layer.contentHeight=contentH.coerceAtLeast(1f); layer.isBitmapDirty=true
        // The old pixels now live only in history: re-check the history memory budget.
        enforceHistoryMemoryBudget()
        return true
    }

    // --- Layer Operations ---
    fun addImageFromUri(uri: Uri) {
        val proj = _uiState.value.currentProject ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_loading_photo))
            try {
                val context = getApplication<Application>()
                val (origW, origH) = withContext(Dispatchers.IO) {
                    ImageProcessor.getImageOriginalDimensions(context, uri)
                }
                val bmp = withContext(Dispatchers.IO) {
                    ImageProcessor.decodeSampledBitmapFromUriWithExif(context, uri, 2048)
                }
                if (bmp != null) {
                    saveHistoryState()
                    val layerId = java.util.UUID.randomUUID().toString()

                    // Save immutable full-resolution source asset into project directory
                    val sourcePath = withContext(Dispatchers.IO) {
                        val projDir = repository.getProjectDir(proj.id)
                        val sourceFile = File(projDir, "source_layer_$layerId.dat")
                        if (ImageProcessor.copyUriToFile(context, uri, sourceFile)) {
                            sourceFile.absolutePath
                        } else null
                    }

                    val ow = origW.coerceAtLeast(bmp.width).toFloat()
                    val oh = origH.coerceAtLeast(bmp.height).toFloat()
                    val fit = kotlin.math.min(1f, kotlin.math.min(proj.width / ow, proj.height / oh))
                    val exif = sourcePath?.let { withContext(Dispatchers.IO) { com.example.engine.ExportEngine.readMetadata(File(it)) } }
                    val layer = Layer(
                        id = layerId,
                        name = tr(R.string.layer_name_imported),
                        type = LayerType.IMAGE,
                        bitmap = bmp,
                        exifTags = exif,
                        sourceAssetPath = sourcePath,
                        originalWidth = origW,
                        originalHeight = origH,
                        contentWidth = ow * fit,
                        contentHeight = oh * fit
                    )
                    proj.layers.add(layer)
                    commitProjectMutation(selectedLayerIndex = proj.layers.size - 1)
                    _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_photo_added))
                } else {
                    _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_photo_load_failed))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_photo_load_failed))
            }
        }
    }

    fun importImageAsNewProject(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_importing_photo))
            try {
                val context = getApplication<Application>()
                val (origW, origH) = withContext(Dispatchers.IO) {
                    ImageProcessor.getImageOriginalDimensions(context, uri)
                }
                val decodedBitmap = withContext(Dispatchers.IO) {
                    ImageProcessor.decodeSampledBitmapFromUriWithExif(context, uri, 2048)
                }

                if (decodedBitmap == null) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = tr(R.string.status_image_decode_failed)
                    )
                    return@launch
                }

                val newProject = withContext(Dispatchers.IO) {
                    val projId = java.util.UUID.randomUUID().toString()
                    val (safeW, safeH) = com.example.util.CanvasValidation.clamp(origW, origH)
                    val proj = Project(
                        id = projId,
                        name = tr(R.string.layer_name_imported),
                        width = safeW,
                        height = safeH
                    )

                    // No background fill layer: like Photoshop, an imported photo is the only layer, so
                    // transparent areas of a PNG stay transparent on export (the canvas shows a checkerboard).
                    val layerId = java.util.UUID.randomUUID().toString()
                    val projDir = repository.getProjectDir(projId)
                    val sourceFile = File(projDir, "source_layer_$layerId.dat")
                    val sourceSaved = ImageProcessor.copyUriToFile(context, uri, sourceFile)

                    val imageLayer = Layer(
                        id = layerId,
                        name = tr(R.string.layer_name_imported),
                        type = LayerType.IMAGE,
                        bitmap = decodedBitmap,
                        sourceAssetPath = if (sourceSaved) sourceFile.absolutePath else null,
                        exifTags = if (sourceSaved) com.example.engine.ExportEngine.readMetadata(sourceFile) else null,
                        originalWidth = origW,
                        originalHeight = origH,
                        contentWidth = safeW.toFloat(),
                        contentHeight = safeH.toFloat()
                    )
                    proj.layers.add(imageLayer)

                    if (!repository.saveProject(proj)) {
                        // Usually a full storage: remove the partial project (copied source photo included).
                        repository.deleteProject(projId)
                        throw java.io.IOException("Saving the imported project failed")
                    }
                    proj
                }

                loadProjects()
                openProject(newProject)
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = null)
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusMessage = tr(R.string.status_import_failed)
                )
            }
        }
    }

    fun openSampleProject() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_loading_sample))
            try {
                val sampleProj = repository.createSampleProject()
                loadProjects()
                openProject(sampleProj)
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = null)
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusMessage = tr(R.string.status_sample_failed)
                )
            }
        }
    }

    /** Text layers are named after their content until the user renames them. */
    private fun textLayerAutoName(text: String): String {
        val t = text.trim().replace('\n', ' ').take(24)
        return if (t.isBlank()) tr(R.string.layer_empty_text) else tr(R.string.layer_name_text, t)
    }

    /** Live text edit (one undo step per editing session); keeps an automatic layer name in sync. */
    fun updateTextConfigTransient(config: TextLayerConfig) {
        updateSelectedLayerTransient { l ->
            if (l.type == LayerType.TEXT && l.name == textLayerAutoName(l.textConfig.text)) {
                l.name = textLayerAutoName(config.text)
            }
            l.textConfig = config
        }
    }

    /** Commits a live edit as one undo step; does nothing (no empty step) when nothing changed. */
    fun commitPendingHistory() {
        if (pendingTransactionSnapshot != null) saveHistoryState()
    }

    fun addTextLayer(text: String) {
        val proj = _uiState.value.currentProject ?: return
        saveHistoryState()

        // A new text layer starts with visible placeholder text (selected for typing in the panel).
        val content = text.ifBlank { tr(R.string.text_default_content) }
        val layer = Layer(
            id = java.util.UUID.randomUUID().toString(),
            name = textLayerAutoName(content),
            type = LayerType.TEXT,
            textConfig = TextLayerConfig(text = content)
        )
        proj.layers.add(layer)
        commitProjectMutation(selectedLayerIndex = proj.layers.size - 1)
    }

    /**
     * Adds a transparent pixel layer the size of the canvas above the selected layer (Photoshop's
     * "New Layer"): a place to paint, retouch, clone or put stickers without touching the photo.
     */
    fun addEmptyLayer() {
        val proj = _uiState.value.currentProject ?: return
        if (_uiState.value.isProcessing) return
        val insertAt = (_uiState.value.selectedLayerIndex + 1).coerceIn(0, proj.layers.size)
        val name = uniqueLayerName(tr(R.string.layer_name_empty, proj.layers.size + 1), proj)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true)
            var full: Bitmap? = null
            try {
                full = withContext(Dispatchers.Default) {
                    Bitmap.createBitmap(proj.width, proj.height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
                }
                val layer = Layer(id = java.util.UUID.randomUUID().toString(), name = name, type = LayerType.IMAGE)
                if (!commitDestructiveBitmap(proj, layer, full!!, proj.width.toFloat(), proj.height.toFloat(), "empty")) {
                    throw java.io.IOException("Save failed")
                }
                saveHistoryState()
                proj.layers.add(insertAt, layer)
                commitProjectMutation(
                    selectedLayerIndex = insertAt,
                    statusMessage = tr(R.string.status_empty_layer_added),
                    transformState = { it.copy(isProcessing = false) }
                )
            } catch (e: OutOfMemoryError) {
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_processing_failed_memory))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_save_failed_storage))
            } finally {
                // The layer keeps its own preview copy.
                full?.let { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    /**
     * Adds an adjustment layer of [kind] (Levels, Curves, Black & White...) above the selected
     * layer, like Photoshop's Layer > New Adjustment Layer, and opens its settings.
     */
    /**
     * Adds [def] as a new image layer above the selected one, centred and sized to about 40% of
     * the canvas, then switches to Transform so it can be moved, scaled and rotated right away.
     */
    fun addStickerLayer(def: com.example.engine.StickerDef, style: com.example.engine.StickerStyle) {
        val proj = _uiState.value.currentProject ?: return
        if (_uiState.value.isProcessing) return
        if (pendingTransactionSnapshot != null) saveHistoryState()
        val insertAt = (_uiState.value.selectedLayerIndex + 1).coerceIn(0, proj.layers.size)
        val app = getApplication<Application>()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_adding_sticker))
            var art: Bitmap? = null
            try {
                // Rendered sharp for the canvas: up to 1600 px, never more than the canvas needs.
                val renderSize = (max(proj.width, proj.height) * 0.6f).toInt().coerceIn(256, 1600)
                art = withContext(Dispatchers.Default) { com.example.engine.StickerLibrary.render(app, def, renderSize, style) }
                val a = art!!
                val target = min(proj.width, proj.height) * 0.4f
                val fit = target / max(a.width, a.height).toFloat()
                val layer = Layer(
                    id = java.util.UUID.randomUUID().toString(),
                    name = uniqueLayerName(tr(R.string.layer_name_sticker), proj),
                    type = LayerType.IMAGE,
                    isSticker = true
                )
                if (!commitDestructiveBitmap(proj, layer, a, a.width * fit, a.height * fit, "sticker")) {
                    throw java.io.IOException("Save failed")
                }
                saveHistoryState()
                proj.layers.add(insertAt, layer)
                commitProjectMutation(
                    selectedLayerIndex = insertAt,
                    statusMessage = tr(R.string.status_sticker_added),
                    transformState = { it.copy(isProcessing = false, activeTool = ActiveTool.TRANSFORM) }
                )
            } catch (e: OutOfMemoryError) {
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_processing_failed_memory))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_save_failed_storage))
            } finally {
                art?.let { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    fun addAdjustmentLayer(kind: com.example.data.model.AdjustmentKind = com.example.data.model.AdjustmentKind.GENERAL) {
        val proj = _uiState.value.currentProject ?: return
        if (pendingTransactionSnapshot != null) saveHistoryState()
        saveHistoryState()

        val name = if (kind == com.example.data.model.AdjustmentKind.GENERAL) {
            tr(R.string.layer_name_adjustment, proj.layers.count { it.type == LayerType.ADJUSTMENT } + 1)
        } else {
            uniqueLayerName(tr(kind.labelRes), proj)
        }
        val layer = Layer(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            type = LayerType.ADJUSTMENT,
            adjustments = ColorAdjustments(),
            adjustmentKind = kind
        )
        val insertAt = (_uiState.value.selectedLayerIndex + 1).coerceIn(0, proj.layers.size)
        proj.layers.add(insertAt, layer)
        commitProjectMutation(
            selectedLayerIndex = insertAt,
            statusMessage = tr(R.string.status_adjustment_added),
            transformState = { it.copy(activeTool = ActiveTool.ADJUST) }
        )
    }

    fun updateSelectedLayer(action: (Layer) -> Unit) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx in proj.layers.indices) {
            val layer = proj.layers[idx]
            if (layer.isLocked) {
                _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_layer_locked))
                return
            }
            saveHistoryState()
            layer.ensureMaskAnchor()
            val followers = transformFollowers(proj, layer)
            val before = placementOf(layer)
            action(layer)
            follow(followers, before, placementOf(layer))
            commitProjectMutation()
        }
    }

    /**
     * A new Adjustment layer above [idx], clipped to it and masked by the selection, so a look
     * (filter, LUT, adjustments) changes only the selected area of that layer, like Photoshop's
     * Image > Adjustments on a selection. Null without a selection or for a non-pixel layer.
     */
    private fun selectionLookLayer(proj: Project, idx: Int): Layer? {
        val layer = proj.layers.getOrNull(idx) ?: return null
        if (layer.type != LayerType.IMAGE && layer.type != LayerType.TEXT) return null
        val selection = _uiState.value.selectionMask?.takeIf { !it.isRecycled && SelectionEngine.hasSelection(it) } ?: return null
        val mask = documentSizedMask(selection, proj.width, proj.height).let { if (it === selection) it.copy(Bitmap.Config.ALPHA_8, true) else it }
        return Layer(
            id = java.util.UUID.randomUUID().toString(),
            name = uniqueLayerName(tr(R.string.layer_name_selection_look), proj),
            type = LayerType.ADJUSTMENT,
            adjustments = ColorAdjustments(),
            maskBitmap = mask,
            isClipped = true
        ).also { it.maskAnchor = it.currentMaskAnchor() }
    }

    /** Adjustment layer added when the Adjust panel opened over a selection (removed if left unused). */
    private var selectionLookId: String? = null
    private var selectionLookBaseIndex = -1

    /**
     * Opening the Adjust panel with a selection on an image or text layer: its sliders work on
     * a new clipped Adjustment layer masked by the selection (they start at zero instead of the
     * layer's own values, so nothing is applied twice).
     */
    fun beginSelectionLook() {
        if (selectionLookId != null) return
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        val layer = proj.layers.getOrNull(idx) ?: return
        if (layer.isLocked) return
        val look = selectionLookLayer(proj, idx) ?: return
        beginHistoryTransaction()
        proj.layers.add(idx + 1, look)
        selectionLookId = look.id
        selectionLookBaseIndex = idx
        clearSmartAutoSession()
        commitProjectMutation(selectedLayerIndex = idx + 1, statusMessage = tr(R.string.status_look_in_selection))
    }

    /** Closing the Adjust panel: an untouched selection Adjustment layer is taken away again. */
    fun endSelectionLook() {
        val id = selectionLookId ?: return
        selectionLookId = null
        val proj = _uiState.value.currentProject ?: return
        val i = proj.layers.indexOfFirst { it.id == id }
        if (i < 0) return
        val l = proj.layers[i]
        val unused = l.adjustments.isIdentity() && !l.adjustments.hasCurves() && l.filter == FilterType.NONE && l.lutId == null
        if (!unused) return
        val base = selectionLookBaseIndex.coerceIn(0, proj.layers.size - 2)
        if (pendingTransactionSnapshot != null) {
            cancelHistoryTransaction()
        } else {
            saveHistoryState()
            proj.layers.removeAt(i)
            commitProjectMutation(selectedLayerIndex = base)
        }
    }

    /**
     * Changes the selected layer's look (filter, LUT, preset, adjustments). With an active
     * selection on an image or text layer the change goes to a new clipped Adjustment layer
     * masked by the selection (one per edit session), so only the selected area changes.
     */
    fun updateLookTransient(action: (Layer) -> Unit) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        val layer = proj.layers.getOrNull(idx) ?: return
        if (!layer.isLocked) {
            if (pendingTransactionSnapshot == null) beginHistoryTransaction()
            val look = selectionLookLayer(proj, idx)
            if (look != null) {
                proj.layers.add(idx + 1, look)
                clearSmartAutoSession()
                _uiState.value = _uiState.value.copy(
                    selectedLayerIndex = idx + 1,
                    statusMessage = tr(R.string.status_look_in_selection)
                )
            }
        }
        updateSelectedLayerTransient(action)
    }

    fun updateSelectedLayerTransient(action: (Layer) -> Unit) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx in proj.layers.indices) {
            val layer = proj.layers[idx]
            if (layer.isLocked) return
            if (pendingTransactionSnapshot == null) {
                beginHistoryTransaction()
            }
            layer.ensureMaskAnchor()
            // Picked layers / a group's contents move, scale and rotate with the selected layer.
            val followers = transformFollowers(proj, layer)
            val before = placementOf(layer)
            action(layer)
            follow(followers, before, placementOf(layer))
            proj.markDirty()
            _uiState.value = _uiState.value.copy(
                currentProject = proj.copy(layers = ArrayList(proj.layers)),
                renderRevision = proj.renderRevision,
                isProjectDirty = true
            )
            scheduleAutosave()
        }
    }

    fun toggleSelectedLayerLock() {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx in proj.layers.indices) {
            saveHistoryState()
            val layer = proj.layers[idx]
            layer.isLocked = !layer.isLocked
            commitProjectMutation(
                statusMessage = if (layer.isLocked) tr(R.string.status_layer_locked) else tr(R.string.status_layer_unlocked)
            )
        }
    }

    fun toggleSelectedLayerVisibility() {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx in proj.layers.indices) {
            saveHistoryState()
            val layer = proj.layers[idx]
            layer.isVisible = !layer.isVisible
            commitProjectMutation()
        }
    }

    // --- Several layers at once and layer groups ------------------------------------------

    fun setLayerMultiSelect(enabled: Boolean) {
        val proj = _uiState.value.currentProject
        val current = proj?.layers?.getOrNull(_uiState.value.selectedLayerIndex)?.id
        _uiState.value = _uiState.value.copy(
            isLayerMultiSelect = enabled,
            // Starts with the layer that was selected.
            selectedLayerIds = if (enabled && current != null) setOf(current) else emptySet()
        )
    }

    fun toggleLayerPicked(id: String) {
        val proj = _uiState.value.currentProject ?: return
        val ids = _uiState.value.selectedLayerIds
        val next = if (id in ids) ids - id else ids + id
        val idx = proj.layers.indexOfFirst { it.id == id }
        _uiState.value = _uiState.value.copy(
            selectedLayerIds = next,
            // The last picked layer is the one the tools work on.
            selectedLayerIndex = if (id !in ids && idx >= 0) idx else _uiState.value.selectedLayerIndex
        )
    }

    fun pickAllLayers(all: Boolean) {
        val proj = _uiState.value.currentProject ?: return
        _uiState.value = _uiState.value.copy(selectedLayerIds = if (all) proj.layers.map { it.id }.toSet() else emptySet())
    }

    /** The layers an action applies to: the picked ones in multi-select, else the selected one. */
    private fun targetLayers(proj: Project): List<Layer> {
        val st = _uiState.value
        val ids = if (st.isLayerMultiSelect) st.selectedLayerIds
            else setOfNotNull(proj.layers.getOrNull(st.selectedLayerIndex)?.id)
        return proj.layers.filter { it.id in ids }
    }

    /** [targets] without those already inside a picked group (the group carries them). */
    private fun rootsOf(proj: Project, targets: List<Layer>): List<Layer> {
        val ids = targets.map { it.id }.toSet()
        return targets.filter { t -> com.example.engine.LayerTree.ancestors(proj.layers, t).none { it.id in ids } }
    }

    /** [targets] plus everything inside the picked groups, in layer order. */
    private fun expandedTargets(proj: Project, targets: List<Layer>): List<Layer> {
        val ids = HashSet<String>()
        for (t in targets) {
            ids.add(t.id)
            if (t.type == LayerType.GROUP) ids.addAll(com.example.engine.LayerTree.descendants(proj.layers, t.id))
        }
        return proj.layers.filter { it.id in ids }
    }

    private fun anyLocked(proj: Project, layers: List<Layer>): Boolean =
        layers.any { com.example.engine.LayerTree.effectivelyLocked(proj.layers, it) }

    /** After a change of the layer structure: group layout restored, selection kept on [keepId]. */
    private fun commitStructure(proj: Project, keepId: String?, @StringRes status: Int? = null, pick: Set<String>? = null) {
        com.example.engine.LayerTree.normalize(proj.layers)
        proj.layers.firstOrNull()?.isClipped = false
        val idx = proj.layers.indexOfFirst { it.id == keepId }.takeIf { it >= 0 }
            ?: _uiState.value.selectedLayerIndex.coerceIn(0, (proj.layers.size - 1).coerceAtLeast(0))
        val live = proj.layers.map { it.id }.toSet()
        commitProjectMutation(
            selectedLayerIndex = idx,
            statusMessage = status?.let { tr(it) },
            transformState = { it.copy(selectedLayerIds = (pick ?: it.selectedLayerIds).filter { id -> id in live }.toSet()) }
        )
    }

    /** Deletes the picked layers (groups with their contents). */
    fun deleteLayers() {
        val proj = _uiState.value.currentProject ?: return
        val targets = expandedTargets(proj, targetLayers(proj))
        if (targets.isEmpty()) return
        if (anyLocked(proj, targets)) { _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_locked_delete)); return }
        if (targets.size >= proj.layers.size) { _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_cannot_delete_all_layers)); return }
        saveHistoryState()
        val ids = targets.map { it.id }.toSet()
        val lowest = proj.layers.indexOfFirst { it.id in ids }
        proj.layers.removeAll { it.id in ids }
        val keep = proj.layers.getOrNull((lowest - 1).coerceAtLeast(0))?.id
        commitStructure(proj, keep, R.string.status_layers_deleted, pick = emptySet())
        if (_uiState.value.isLayerMultiSelect) _uiState.value = _uiState.value.copy(isLayerMultiSelect = false)
    }

    /** Duplicates the picked layers (groups with their contents) above the topmost of them. */
    fun duplicateLayers() {
        val proj = _uiState.value.currentProject ?: return
        val targets = expandedTargets(proj, targetLayers(proj))
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_duplicating_layer))
            try {
                val idMap = targets.associate { it.id to java.util.UUID.randomUUID().toString() }
                val copies = withContext(Dispatchers.Default) {
                    targets.map { t ->
                        t.duplicateLayer(uniqueLayerName(tr(R.string.layer_name_copy, t.name), proj))
                    }
                }
                // Copies keep their structure: ids remapped, parents inside the copy follow.
                val fixed = copies.mapIndexed { i, c ->
                    val orig = targets[i]
                    c.copy(id = idMap.getValue(orig.id), parentId = orig.parentId?.let { idMap[it] ?: it })
                }
                saveHistoryState()
                val top = proj.layers.indexOfLast { l -> targets.any { it.id == l.id } }
                proj.layers.addAll(top + 1, fixed)
                val newIds = fixed.map { it.id }.toSet()
                commitStructure(proj, fixed.last().id, R.string.status_layers_duplicated,
                    pick = if (_uiState.value.isLayerMultiSelect) newIds else null)
                _uiState.value = _uiState.value.copy(isProcessing = false)
            } catch (e: OutOfMemoryError) {
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_duplicate_failed))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_duplicate_failed))
            }
        }
    }

    fun setLayersVisible(visible: Boolean) {
        val proj = _uiState.value.currentProject ?: return
        val targets = targetLayers(proj)
        if (targets.isEmpty()) return
        saveHistoryState()
        targets.forEach { it.isVisible = visible }
        commitProjectMutation()
    }

    fun setLayersLocked(locked: Boolean) {
        val proj = _uiState.value.currentProject ?: return
        val targets = targetLayers(proj)
        if (targets.isEmpty()) return
        saveHistoryState()
        expandedTargets(proj, targets).forEach { it.isLocked = locked }
        commitProjectMutation()
    }

    /** Puts the picked layers into a new group (Ctrl+G). */
    fun groupLayers() {
        val proj = _uiState.value.currentProject ?: return
        val roots = rootsOf(proj, targetLayers(proj))
        if (roots.isEmpty()) return
        if (anyLocked(proj, roots)) { _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_layer_locked)); return }
        saveHistoryState()
        val topmost = roots.last()
        val group = Layer(
            id = java.util.UUID.randomUUID().toString(),
            name = uniqueLayerName(tr(R.string.layer_name_group, proj.layers.count { it.type == LayerType.GROUP } + 1), proj),
            type = LayerType.GROUP,
            parentId = com.example.engine.LayerTree.parentOf(proj.layers, topmost)
        )
        roots.forEach { it.parentId = group.id; it.isClipped = false }
        proj.layers.add(proj.layers.indexOf(topmost) + 1, group)
        commitStructure(proj, group.id, R.string.status_layers_grouped, pick = setOf(group.id))
        if (_uiState.value.isLayerMultiSelect) _uiState.value = _uiState.value.copy(isLayerMultiSelect = false, selectedLayerIds = emptySet())
    }

    /** Takes the members out of the selected group(s) and removes the group (Shift+Ctrl+G). */
    fun ungroupLayers() {
        val proj = _uiState.value.currentProject ?: return
        val groups = targetLayers(proj).filter { it.type == LayerType.GROUP }
        if (groups.isEmpty()) { _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_not_a_group)); return }
        if (anyLocked(proj, groups)) { _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_layer_locked)); return }
        saveHistoryState()
        var keep: String? = null
        for (g in groups) {
            val members = proj.layers.filter { it.parentId == g.id }
            members.forEach { it.parentId = g.parentId }
            keep = keep ?: members.lastOrNull()?.id
            proj.layers.remove(g)
        }
        commitStructure(proj, keep, R.string.status_layers_ungrouped, pick = emptySet())
        if (_uiState.value.isLayerMultiSelect) _uiState.value = _uiState.value.copy(isLayerMultiSelect = false)
    }

    fun toggleGroupCollapsed(groupId: String) {
        val proj = _uiState.value.currentProject ?: return
        val g = proj.layers.firstOrNull { it.id == groupId && it.type == LayerType.GROUP } ?: return
        g.isCollapsed = !g.isCollapsed
        // Folding is a view setting: saved with the project but not an undo step.
        commitProjectMutation()
    }

    /** Merges the picked layers (groups with their contents) into one image layer. */
    fun mergeLayers() {
        val proj = _uiState.value.currentProject ?: return
        val picked = targetLayers(proj)
        val single = picked.singleOrNull()
        // One group selected: merge the group; otherwise at least two layers are needed.
        if (picked.size < 2 && single?.type != LayerType.GROUP) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_pick_two_layers)); return
        }
        mergeIntoOne(proj, expandedTargets(proj, picked), R.string.status_layers_merged)
    }

    /** Merges all visible layers into one; hidden layers stay as they are. */
    fun mergeVisibleLayers() {
        val proj = _uiState.value.currentProject ?: return
        val visible = proj.layers.filter { !com.example.engine.LayerTree.effectivelyHidden(proj.layers, it) }
        val visibleIds = visible.map { it.id }.toSet()
        val withGroups = proj.layers.filter { l -> l.id in visibleIds }
        if (withGroups.size < 2) { _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_pick_two_layers)); return }
        mergeIntoOne(proj, withGroups, R.string.status_layers_merged)
    }

    /** Flatten Image: everything into one layer; hidden layers are discarded. */
    fun flattenImage() {
        val proj = _uiState.value.currentProject ?: return
        if (proj.layers.size < 2) return
        mergeIntoOne(proj, proj.layers.toList(), R.string.status_image_flattened)
    }

    private fun mergeIntoOne(proj: Project, layers: List<Layer>, @StringRes done: Int) {
        if (layers.isEmpty() || _uiState.value.isProcessing) return
        if (anyLocked(proj, layers)) { _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_locked_merge)); return }
        val ids = layers.map { it.id }.toSet()
        val topmost = layers.last()
        // The merged layer sits where the topmost one was, in the group that held it.
        var parent = com.example.engine.LayerTree.parentOf(proj.layers, topmost)
        while (parent != null && parent in ids) parent = proj.layers.firstOrNull { it.id == parent }?.let { com.example.engine.LayerTree.parentOf(proj.layers, it) }
        val name = if (topmost.type == LayerType.GROUP && layers.size > 1) topmost.name else layers.filter { it.type != LayerType.GROUP }.joinToString(" + ") { it.name }.take(60)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_merging_layers))
            var historySaved = false
            try {
                val merged = withContext(Dispatchers.Default) {
                    LayerRenderer.renderCompositeCanvas(getApplication(), layers, proj.width, proj.height,
                        includeBackground = false, backgroundColor = null, maxPreviewDimension = null)
                }
                val n = Layer(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name.ifBlank { tr(R.string.layer_name_merged) },
                    type = LayerType.IMAGE,
                    contentWidth = proj.width.toFloat(),
                    contentHeight = proj.height.toFloat(),
                    parentId = parent
                )
                if (!commitDestructiveBitmap(proj, n, merged, proj.width.toFloat(), proj.height.toFloat(), "merge")) throw java.io.IOException("Save failed")
                if (merged !== n.bitmap && !merged.isRecycled) merged.recycle()
                saveHistoryState()
                historySaved = true
                val at = proj.layers.indexOf(topmost) + 1
                proj.layers.add(at, n)
                proj.layers.removeAll { it.id in ids }
                commitStructure(proj, n.id, done, pick = emptySet())
                _uiState.value = _uiState.value.copy(isProcessing = false, isLayerMultiSelect = false)
            } catch (e: OutOfMemoryError) {
                if (historySaved) rollbackToLastHistoryState()
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_merge_failed))
            } catch (e: Exception) {
                if (historySaved) rollbackToLastHistoryState()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_merge_failed))
            }
        }
    }

    /**
     * Moves the layer at [from] (a group with its contents) so it ends up at [to]; dropped among
     * a group's members it joins that group, dropped outside it leaves it.
     */
    private fun moveBlock(proj: Project, from: Int, to: Int) {
        val layer = proj.layers[from]
        val inside = if (layer.type == LayerType.GROUP) com.example.engine.LayerTree.descendants(proj.layers, layer.id) else emptySet()
        val target = proj.layers[to]
        if (target.id in inside) return
        val block = proj.layers.filter { it.id == layer.id || it.id in inside }
        proj.layers.removeAll { it.id == layer.id || it.id in inside }
        val tIdx = proj.layers.indexOf(target)
        // Moving up lands above the target, moving down below it.
        val at = if (to > from) tIdx + 1 else tIdx
        proj.layers.addAll(at, block)
        val below = proj.layers.getOrNull(at - 1)
        val above = proj.layers.getOrNull(at + block.size)
        // The group the drop point is in, seen from the layer above and from the layer below;
        // where they differ (at a group's edge) the outer one wins.
        val fromAbove = above?.let { if (it.type == LayerType.GROUP) it.id else com.example.engine.LayerTree.parentOf(proj.layers, it) }
        val fromBelow = below?.let { com.example.engine.LayerTree.parentOf(proj.layers, it) }
        fun depthOf(id: String?): Int = id?.let { g -> proj.layers.firstOrNull { it.id == g } }?.let { com.example.engine.LayerTree.depth(proj.layers, it) + 1 } ?: 0
        layer.parentId = when {
            above == null -> null
            below == null -> fromAbove
            fromAbove == fromBelow -> fromAbove
            depthOf(fromAbove) <= depthOf(fromBelow) -> fromAbove
            else -> fromBelow
        }
        if (layer.parentId == layer.id || layer.parentId in inside) layer.parentId = null
    }

    // --- Align and distribute -------------------------------------------------------------

    enum class LayerAlign { LEFT, H_CENTER, RIGHT, TOP, V_CENTER, BOTTOM, DISTRIBUTE_H, DISTRIBUTE_V }

    /** Box of [layer] on the document (rotation included), or null for layers with no pixels. */
    private fun docBounds(proj: Project, layer: Layer): android.graphics.RectF? {
        if (layer.type == LayerType.GROUP) {
            var out: android.graphics.RectF? = null
            for (id in com.example.engine.LayerTree.descendants(proj.layers, layer.id)) {
                val l = proj.layers.firstOrNull { it.id == id } ?: continue
                if (l.type == LayerType.GROUP) continue
                val b = docBounds(proj, l) ?: continue
                out = out?.apply { union(b) } ?: android.graphics.RectF(b)
            }
            return out
        }
        val (w, h) = when {
            layer.type == LayerType.ADJUSTMENT -> return null
            layer.bitmap != null || layer.contentWidth > 0f -> layer.intrinsicDocumentWidth() to layer.intrinsicDocumentHeight()
            layer.type == LayerType.TEXT -> LayerRenderer.measureTextLayer(getApplication(), layer, proj.width.toFloat())
            else -> proj.width.toFloat() to proj.height.toFloat()
        }
        val m = Matrix().apply {
            postTranslate(-w / 2f, -h / 2f)
            postScale(layer.scaleX, layer.scaleY)
            postRotate(layer.rotation)
            postTranslate(proj.width / 2f + layer.positionX, proj.height / 2f + layer.positionY)
        }
        return android.graphics.RectF(0f, 0f, w, h).also { m.mapRect(it) }
    }

    /** Moves [layer] (a group: everything in it) by (dx, dy) document pixels. */
    private fun shiftLayer(proj: Project, layer: Layer, dx: Float, dy: Float) {
        val moving = if (layer.type == LayerType.GROUP)
            com.example.engine.LayerTree.descendants(proj.layers, layer.id).mapNotNull { id -> proj.layers.firstOrNull { it.id == id } }
        else listOf(layer)
        for (l in moving) {
            if (l.type == LayerType.GROUP) continue
            l.ensureMaskAnchor()
            l.positionX += dx; l.positionY += dy
        }
    }

    /**
     * Aligns the picked layers to each other (one layer: to the canvas), or spaces three or more
     * evenly, like Photoshop's Align / Distribute.
     */
    fun alignLayers(mode: LayerAlign) {
        val proj = _uiState.value.currentProject ?: return
        val items = rootsOf(proj, targetLayers(proj)).mapNotNull { l -> docBounds(proj, l)?.let { l to it } }
        if (items.isEmpty()) return
        if (anyLocked(proj, items.map { it.first })) { _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_layer_locked)); return }
        val distribute = mode == LayerAlign.DISTRIBUTE_H || mode == LayerAlign.DISTRIBUTE_V
        if (distribute && items.size < 3) { _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_distribute_needs_three)); return }
        val ref = if (items.size == 1) android.graphics.RectF(0f, 0f, proj.width.toFloat(), proj.height.toFloat())
            else android.graphics.RectF(items[0].second).apply { items.forEach { union(it.second) } }
        saveHistoryState()
        when (mode) {
            LayerAlign.DISTRIBUTE_H -> {
                val sorted = items.sortedBy { it.second.centerX() }
                val first = sorted.first().second.centerX(); val last = sorted.last().second.centerX()
                sorted.forEachIndexed { i, (l, b) -> shiftLayer(proj, l, first + (last - first) * i / (sorted.size - 1) - b.centerX(), 0f) }
            }
            LayerAlign.DISTRIBUTE_V -> {
                val sorted = items.sortedBy { it.second.centerY() }
                val first = sorted.first().second.centerY(); val last = sorted.last().second.centerY()
                sorted.forEachIndexed { i, (l, b) -> shiftLayer(proj, l, 0f, first + (last - first) * i / (sorted.size - 1) - b.centerY()) }
            }
            else -> for ((l, b) in items) {
                val dx = when (mode) {
                    LayerAlign.LEFT -> ref.left - b.left
                    LayerAlign.H_CENTER -> ref.centerX() - b.centerX()
                    LayerAlign.RIGHT -> ref.right - b.right
                    else -> 0f
                }
                val dy = when (mode) {
                    LayerAlign.TOP -> ref.top - b.top
                    LayerAlign.V_CENTER -> ref.centerY() - b.centerY()
                    LayerAlign.BOTTOM -> ref.bottom - b.bottom
                    else -> 0f
                }
                shiftLayer(proj, l, dx, dy)
            }
        }
        commitProjectMutation(statusMessage = tr(R.string.status_layers_aligned))
    }

    // --- Transforming several layers together ----------------------------------------------

    /** Layers that move with the selected one: the other picked layers, or a group's contents. */
    private fun transformFollowers(proj: Project, primary: Layer): List<Layer> {
        val st = _uiState.value
        val picked = if (st.isLayerMultiSelect && st.selectedLayerIds.size > 1 && primary.id in st.selectedLayerIds)
            proj.layers.filter { it.id in st.selectedLayerIds } else listOf(primary)
        if (picked.size == 1 && primary.type != LayerType.GROUP) return emptyList()
        return expandedTargets(proj, rootsOf(proj, picked)).filter { it.id != primary.id && it.type != LayerType.GROUP && it.type != LayerType.ADJUSTMENT }
    }

    /** Rotates by [degrees] and scales by ([sx], [sy]) [layers] around the centre of all of them. */
    private fun turnTogether(proj: Project, layers: List<Layer>, degrees: Float, sx: Float, sy: Float) {
        val boxes = layers.filter { it.type != LayerType.GROUP }.mapNotNull { docBounds(proj, it) }
        val all = boxes.firstOrNull()?.let { f -> android.graphics.RectF(f).apply { boxes.forEach { union(it) } } } ?: return
        val px = all.centerX() - proj.width / 2f; val py = all.centerY() - proj.height / 2f
        val zero = Placement(px, py, 1f, 1f, 0f)
        follow(layers.filter { it.type != LayerType.GROUP && it.type != LayerType.ADJUSTMENT }, zero, Placement(px, py, sx, sy, degrees), px, py)
    }

    private class Placement(val x: Float, val y: Float, val sx: Float, val sy: Float, val rot: Float)
    private fun placementOf(l: Layer) = Placement(l.positionX, l.positionY, l.scaleX, l.scaleY, l.rotation)

    /**
     * Gives [followers] the same move / scale / rotation that the selected layer just got
     * ([before] to [after]), around [pivot] (document offsets from the centre).
     */
    private fun follow(followers: List<Layer>, before: Placement, after: Placement, pivotX: Float = before.x, pivotY: Float = before.y) {
        if (followers.isEmpty()) return
        val dsx = if (kotlin.math.abs(before.sx) > 1e-6f) after.sx / before.sx else 1f
        val dsy = if (kotlin.math.abs(before.sy) > 1e-6f) after.sy / before.sy else 1f
        val dr = after.rot - before.rot
        val tx = after.x - before.x; val ty = after.y - before.y
        if (dsx == 1f && dsy == 1f && dr == 0f && tx == 0f && ty == 0f) return
        val rad = Math.toRadians(dr.toDouble())
        val c = kotlin.math.cos(rad).toFloat(); val s = kotlin.math.sin(rad).toFloat()
        for (f in followers) {
            f.ensureMaskAnchor()
            val rx = (f.positionX - pivotX) * dsx; val ry = (f.positionY - pivotY) * dsy
            f.positionX = pivotX + tx + rx * c - ry * s
            f.positionY = pivotY + ty + rx * s + ry * c
            f.scaleX *= dsx; f.scaleY *= dsy
            f.rotation = (f.rotation + dr) % 360f
        }
    }

    fun deleteSelectedLayer() {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx in proj.layers.indices && proj.layers.size > 1) {
            val layer = proj.layers[idx]
            if (layer.isLocked) {
                _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_locked_delete))
                return
            }
            if (layer.type == LayerType.GROUP) {
                // A group goes with everything in it.
                deleteLayers(); return
            }
            saveHistoryState()
            proj.layers.removeAt(idx)
            // A clipped layer that is now at the bottom has nothing to clip to.
            proj.layers.firstOrNull()?.isClipped = false
            val nextIdx = (idx - 1).coerceAtLeast(0)
            commitProjectMutation(selectedLayerIndex = nextIdx)
        }
    }

    fun duplicateSelectedLayer() {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx in proj.layers.indices) {
            val layer = proj.layers[idx]
            if (layer.isLocked) {
                _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_locked_duplicate))
                return
            }

            if (layer.type == LayerType.GROUP) { duplicateLayers(); return }
            val copyName = uniqueLayerName(tr(R.string.layer_name_copy, layer.name), proj)
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_duplicating_layer))
                try {
                    val copied = if (layer.bitmap != null) {
                        withContext(Dispatchers.Default) { layer.duplicateLayer(copyName) }
                    } else {
                        layer.duplicateLayer(copyName)
                    }
                    saveHistoryState()
                    proj.layers.add(idx + 1, copied)
                    commitProjectMutation(
                        selectedLayerIndex = idx + 1,
                        statusMessage = tr(R.string.status_layer_duplicated),
                        transformState = { it.copy(isProcessing = false) }
                    )
                } catch (e: OutOfMemoryError) {
                    System.gc()
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = tr(R.string.status_duplicate_failed)
                    )
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = tr(R.string.status_duplicate_failed)
                    )
                }
            }
        }
    }

    /** [base], or "[base] 2", "[base] 3"... when a layer already has that name. */
    private fun uniqueLayerName(base: String, proj: Project): String {
        val names = proj.layers.mapTo(HashSet()) { it.name }
        if (base !in names) return base
        var n = 2
        while ("$base $n" in names) n++
        return "$base $n"
    }

    /** Drag-and-drop reorder: moves the layer at [from] to [to] (bottom = 0). */
    fun moveLayer(from: Int, to: Int) {
        val proj = _uiState.value.currentProject ?: return
        if (from !in proj.layers.indices || to !in proj.layers.indices || from == to) return
        saveHistoryState()
        val selectedId = proj.layers.getOrNull(_uiState.value.selectedLayerIndex)?.id
        if (proj.layers.any { it.type == LayerType.GROUP || it.parentId != null }) {
            // Groups move with their contents; a layer dropped among a group's members joins it.
            moveBlock(proj, from, to)
            commitStructure(proj, selectedId ?: proj.layers.getOrNull(to)?.id)
            return
        }
        val layer = proj.layers.removeAt(from)
        proj.layers.add(to, layer)
        // A clipped layer moved to the bottom has nothing to clip to.
        proj.layers.firstOrNull()?.isClipped = false
        val newSelected = proj.layers.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: to
        commitProjectMutation(selectedLayerIndex = newSelected)
    }

    fun renameLayer(index: Int, name: String) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(index) ?: return
        val clean = name.trim().take(60)
        if (clean.isEmpty() || clean == layer.name) return
        saveHistoryState()
        layer.name = clean
        commitProjectMutation()
    }

    fun toggleLayerVisibilityAt(index: Int) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(index) ?: return
        saveHistoryState()
        layer.isVisible = !layer.isVisible
        commitProjectMutation()
    }

    fun toggleLayerLockAt(index: Int) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(index) ?: return
        saveHistoryState()
        layer.isLocked = !layer.isLocked
        // A group locks (or unlocks) everything in it.
        if (layer.type == LayerType.GROUP) com.example.engine.LayerTree.descendants(proj.layers, layer.id)
            .forEach { id -> proj.layers.firstOrNull { it.id == id }?.isLocked = layer.isLocked }
        commitProjectMutation(
            statusMessage = if (layer.isLocked) tr(R.string.status_layer_locked) else tr(R.string.status_layer_unlocked)
        )
    }

    /** Selection from the layer's visible pixels (its transparency and mask), like Ctrl+click in Photoshop. */
    fun selectLayerPixels(index: Int) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(index) ?: return
        if (_uiState.value.selectionMask == null) return
        val w = proj.width
        val h = proj.height
        mutateSelectionAsync(R.string.status_selecting_area, R.string.status_selection_failed) { mask ->
            val img: Bitmap? = if (layer.type == LayerType.ADJUSTMENT) {
                LayerRenderer.renderEffectiveMask(layer, w, h)
            } else {
                val ratio = min(1f, 2048f / max(w, h).toFloat())
                val tw = (w * ratio).toInt().coerceAtLeast(1)
                val th = (h * ratio).toInt().coerceAtLeast(1)
                LayerRenderer.renderSingleLayerToBitmap(getApplication(), layer, tw, th, ratio)
            }
            mask.eraseColor(Color.TRANSPARENT)
            if (img != null) {
                val m = Matrix().apply { setScale(w.toFloat() / img.width, h.toFloat() / img.height) }
                Canvas(mask).drawBitmap(img, m, Paint(Paint.FILTER_BITMAP_FLAG))
                img.recycle()
            }
        }
    }

    fun moveLayerUp() {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx in 0 until proj.layers.size - 1) {
            saveHistoryState()
            val temp = proj.layers[idx]
            proj.layers[idx] = proj.layers[idx + 1]
            proj.layers[idx + 1] = temp
            commitProjectMutation(selectedLayerIndex = idx + 1)
        }
    }

    fun moveLayerDown() {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx in 1 until proj.layers.size) {
            saveHistoryState()
            val temp = proj.layers[idx]
            proj.layers[idx] = proj.layers[idx - 1]
            proj.layers[idx - 1] = temp
            commitProjectMutation(selectedLayerIndex = idx - 1)
        }
    }

    fun mergeSelectedLayerDown() {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        val sel = proj.layers.getOrNull(idx)
        if (sel != null && (sel.type == LayerType.GROUP || proj.layers.any { it.type == LayerType.GROUP })) {
            // Merge Group, or this layer with the one below it at the same level.
            if (sel.type == LayerType.GROUP) { mergeIntoOne(proj, expandedTargets(proj, listOf(sel)), R.string.status_layers_merged); return }
            val level = com.example.engine.LayerTree.level(proj.layers, com.example.engine.LayerTree.parentOf(proj.layers, sel))
            val below = level.getOrNull(level.indexOf(sel) - 1) ?: return
            if (below.type == LayerType.ADJUSTMENT) {
                _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_merge_into_adjustment)); return
            }
            mergeIntoOne(proj, expandedTargets(proj, listOf(below, sel)), R.string.status_layers_merged)
            return
        }
        if (idx in 1 until proj.layers.size) {
            val topLayer = proj.layers[idx]
            val bottomLayer = proj.layers[idx - 1]

            if (topLayer.isLocked || bottomLayer.isLocked) {
                _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_locked_merge))
                return
            }
            // An adjustment layer affects everything below it; baking it into one layer would lose that.
            if (bottomLayer.type == LayerType.ADJUSTMENT) {
                _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_merge_into_adjustment))
                return
            }
            if (!topLayer.isVisible || !bottomLayer.isVisible) {
                _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_merge_hidden))
                return
            }

            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_merging_layers))

                var historySaved = false
                try {
                    val mergedBmp = withContext(Dispatchers.Default) {
                        LayerRenderer.renderCompositeCanvas(
                            getApplication(),
                            listOf(bottomLayer, topLayer),
                            proj.width,
                            proj.height,
                            includeBackground = false,
                            backgroundColor = null,
                            maxPreviewDimension = null
                        )
                    }

                    saveHistoryState()
                    historySaved = true

                    if (!commitDestructiveBitmap(proj, bottomLayer, mergedBmp, proj.width.toFloat(), proj.height.toFloat(), "merge")) {
                        throw java.io.IOException("Failed to persist merged layer")
                    }
                    if (mergedBmp !== bottomLayer.bitmap && !mergedBmp.isRecycled) mergedBmp.recycle()
                    bottomLayer.name = "${bottomLayer.name} + ${topLayer.name}"
                    bottomLayer.type = LayerType.IMAGE
                    bottomLayer.opacity = 1.0f
                    bottomLayer.blendMode = BlendModeType.NORMAL
                    bottomLayer.positionX = 0f
                    bottomLayer.positionY = 0f
                    bottomLayer.scaleX = 1f
                    bottomLayer.scaleY = 1f
                    bottomLayer.rotation = 0f
                    // Skew / distort and the LUT look are already baked into the merged pixels.
                    bottomLayer.skewX = 0f
                    bottomLayer.skewY = 0f
                    bottomLayer.distort = null
                    bottomLayer.lutId = null
                    bottomLayer.filter = FilterType.NONE
                    bottomLayer.filterIntensity = 1f
                    bottomLayer.adjustments = ColorAdjustments()
                    // Both layers' masks are already baked into the merged pixels.
                    bottomLayer.maskBitmap = null
                    bottomLayer.isEditingMask = false
                    bottomLayer.isMaskEnabled = true
                    bottomLayer.maskDensity = 1f
                    bottomLayer.maskFeather = 0f
                    bottomLayer.maskAnchor = null
                    bottomLayer.isMaskLinked = true

                    proj.layers.removeAt(idx)
                    commitProjectMutation(
                        selectedLayerIndex = idx - 1,
                        statusMessage = tr(R.string.status_layers_merged),
                        transformState = { it.copy(isProcessing = false) }
                    )
                } catch (e: OutOfMemoryError) {
                    // Failed after its undo step was recorded: drop that step (and any partial change).
                    if (historySaved) rollbackToLastHistoryState()
                    System.gc()
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = tr(R.string.status_merge_failed)
                    )
                } catch (e: Exception) {
                    // Failed after its undo step was recorded: drop that step (and any partial change).
                    if (historySaved) rollbackToLastHistoryState()
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = tr(R.string.status_merge_failed)
                    )
                }
            }
        }
    }

    fun setResizeDialogOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(isResizeDialogOpen = open)
    }

    fun resizeCanvas(newWidth: Int, newHeight: Int, scaleContent: Boolean) {
        val proj = _uiState.value.currentProject ?: return
        if (newWidth <= 0 || newHeight <= 0) return
        saveHistoryState()

        val oldW = proj.width.toFloat()
        val oldH = proj.height.toFloat()
        val scaleX = newWidth / oldW
        val scaleY = newHeight / oldH
        // Linked masks are baked where they currently show before the document is remapped.
        proj.layers.forEach { bakeMaskTransform(it, oldW.toInt(), oldH.toInt()) }

        proj.width = newWidth
        proj.height = newHeight

        // Content stays top-left anchored when not scaled (see position offsets below).
        val maskMatrix = if (scaleContent) Matrix().apply { postScale(scaleX, scaleY) } else Matrix()
        if (scaleContent) {
            for (layer in proj.layers) {
                layer.positionX *= scaleX
                layer.positionY *= scaleY
                layer.scaleX *= scaleX
                layer.scaleY *= scaleY
            }
        } else {
            for (layer in proj.layers) {
                layer.positionX += (oldW - newWidth.toFloat()) / 2f
                layer.positionY += (oldH - newHeight.toFloat()) / 2f
            }
        }
        for (layer in proj.layers) {
            layer.maskBitmap?.let { m ->
                if (!m.isRecycled) {
                    layer.maskBitmap = remapDocumentMask(m, oldW.toInt(), oldH.toInt(), newWidth, newHeight, maskMatrix)
                }
                layer.maskAnchor = layer.currentMaskAnchor()
            }
        }

        val newMask = SelectionEngine.createBlankMask(newWidth, newHeight)
        commitProjectMutation(
            statusMessage = tr(R.string.status_canvas_resized, newWidth, newHeight),
            transformState = { it.copy(selectionMask = newMask, isResizeDialogOpen = false) }
        )
    }

    fun rotateSelectedLayer(degrees: Float) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx in proj.layers.indices) {
            val layer = proj.layers[idx]
            if (layer.isLocked) {
                _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_layer_locked))
                return
            }
            // A one-layer image: rotating by 90 degrees means rotating the picture, so the canvas
            // turns with it (no cut-off corners or empty bands on a non-square image).
            if (proj.layers.size == 1 && kotlin.math.abs(kotlin.math.abs(degrees) - 90f) < 0.01f) {
                rotateCanvas90(degrees > 0f, R.string.status_rotated_whole_image)
                return
            }
            saveHistoryState()
            layer.ensureMaskAnchor()
            val followers = transformFollowers(proj, layer)
            if (followers.isNotEmpty()) {
                // Several layers turn together around the middle of all of them.
                turnTogether(proj, listOf(layer) + followers, degrees, 1f, 1f)
                commitProjectMutation(); return
            }
            layer.rotation = (layer.rotation + degrees) % 360f
            commitProjectMutation()
        }
    }

    fun setLayerRotation(degrees: Float) {
        updateSelectedLayerTransient { it.rotation = ((degrees % 360f) + 360f) % 360f }
    }

    fun resetSelectedLayerTransform() {
        updateSelectedLayer { it.positionX=0f; it.positionY=0f; it.scaleX=1f; it.scaleY=1f; it.rotation=0f; it.skewX=0f; it.skewY=0f; it.distort=null }
    }

    fun flipSelectedLayer(horizontal: Boolean) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx in proj.layers.indices) {
            val layer = proj.layers[idx]
            if (layer.isLocked) {
                _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_layer_locked))
                return
            }
            saveHistoryState()
            val followers = transformFollowers(proj, layer)
            if (followers.isNotEmpty()) {
                turnTogether(proj, listOf(layer) + followers, 0f, if (horizontal) -1f else 1f, if (horizontal) 1f else -1f)
                commitProjectMutation(); return
            }
            if (horizontal) {
                layer.scaleX *= -1f
            } else {
                layer.scaleY *= -1f
            }
            commitProjectMutation()
        }
    }

    fun setCropAspectRatio(ratio: Float?) {
        // Choosing a ratio replaces an exact pixel size.
        _uiState.value = _uiState.value.copy(selectedAspectRatio = ratio, cropTargetWidth = 0, cropTargetHeight = 0)
    }

    fun setTransformMode(mode: String) { _uiState.value = _uiState.value.copy(transformMode = mode) }
    fun setTransformPivot(pivot: Int) { _uiState.value = _uiState.value.copy(transformPivot = pivot.coerceIn(0, 8)) }
    fun toggleTransformFromCenter() { _uiState.value = _uiState.value.copy(transformFromCenter = !_uiState.value.transformFromCenter) }
    fun toggleTransformLockAspect() { _uiState.value = _uiState.value.copy(transformLockAspect = !_uiState.value.transformLockAspect) }
    fun toggleTransformSnap() { _uiState.value = _uiState.value.copy(transformSnap = !_uiState.value.transformSnap) }

    fun setCropStraighten(degrees: Float) {
        _uiState.value = _uiState.value.copy(cropStraighten = degrees.coerceIn(-45f, 45f))
    }

    fun setCropGrid(grid: String) {
        _uiState.value = _uiState.value.copy(cropGrid = grid)
    }

    fun setCropExpandCanvas(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(cropExpandCanvas = enabled, cropStraighten = if (enabled) 0f else _uiState.value.cropStraighten)
    }

    /** Exact output size (e.g. 1080 x 1350): the frame takes that ratio and the result is resampled to it. */
    fun setCropTargetSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            _uiState.value = _uiState.value.copy(cropTargetWidth = 0, cropTargetHeight = 0)
            return
        }
        val w = width.coerceIn(16, 8192)
        val h = height.coerceIn(16, 8192)
        _uiState.value = _uiState.value.copy(cropTargetWidth = w, cropTargetHeight = h, selectedAspectRatio = w.toFloat() / h)
    }

    /** Portrait <-> landscape for the current ratio / size. */
    fun swapCropOrientation() {
        val st = _uiState.value
        val proj = st.currentProject ?: return
        val ratio = st.selectedAspectRatio ?: (proj.width.toFloat() / proj.height.coerceAtLeast(1))
        _uiState.value = st.copy(
            selectedAspectRatio = 1f / ratio,
            cropTargetWidth = st.cropTargetHeight,
            cropTargetHeight = st.cropTargetWidth
        )
    }

    /** Rotates the whole image (canvas and every layer) by 90 degrees. */
    fun rotateCanvas90(clockwise: Boolean, @StringRes statusRes: Int = R.string.status_canvas_rotated) {
        val proj = _uiState.value.currentProject ?: return
        val w = proj.width
        val h = proj.height
        saveHistoryState()
        val deg = if (clockwise) 90f else -90f
        val m = Matrix().apply {
            postTranslate(-w / 2f, -h / 2f)
            postRotate(deg)
            postTranslate(h / 2f, w / 2f)
        }
        proj.layers.forEach { bakeMaskTransform(it, w, h) }
        proj.layers.forEach { l ->
            val x = l.positionX
            val y = l.positionY
            if (clockwise) { l.positionX = -y; l.positionY = x } else { l.positionX = y; l.positionY = -x }
            l.rotation = (((l.rotation + deg) % 360f) + 360f) % 360f
            l.maskBitmap?.let { mk -> if (!mk.isRecycled) l.maskBitmap = remapDocumentMask(mk, w, h, h, w, m) }
            if (l.maskBitmap != null) l.maskAnchor = l.currentMaskAnchor()
        }
        proj.width = h
        proj.height = w
        commitProjectMutation(
            statusMessage = tr(statusRes),
            transformState = {
                it.copy(
                    selectionMask = SelectionEngine.createBlankMask(h, w),
                    selectedAspectRatio = it.selectedAspectRatio?.let { r -> 1f / r },
                    perspectiveCorners = emptyList()
                )
            }
        )
        if (_uiState.value.isPerspectiveModeActive) resetPerspectiveCorners()
    }

    /** Mirrors the whole image (every layer) horizontally or vertically. */
    fun flipCanvas(horizontal: Boolean) {
        val proj = _uiState.value.currentProject ?: return
        val w = proj.width
        val h = proj.height
        saveHistoryState()
        val m = Matrix().apply { if (horizontal) postScale(-1f, 1f, w / 2f, h / 2f) else postScale(1f, -1f, w / 2f, h / 2f) }
        proj.layers.forEach { bakeMaskTransform(it, w, h) }
        proj.layers.forEach { l ->
            if (horizontal) { l.positionX = -l.positionX; l.scaleX = -l.scaleX } else { l.positionY = -l.positionY; l.scaleY = -l.scaleY }
            l.rotation = (((-l.rotation) % 360f) + 360f) % 360f
            l.maskBitmap?.let { mk -> if (!mk.isRecycled) l.maskBitmap = remapDocumentMask(mk, w, h, w, h, m) }
            if (l.maskBitmap != null) l.maskAnchor = l.currentMaskAnchor()
        }
        commitProjectMutation(
            statusMessage = tr(R.string.status_canvas_flipped),
            transformState = { it.copy(selectionMask = SelectionEngine.createBlankMask(w, h)) }
        )
    }

    /**
     * Keystone correction: insets the top / bottom (vertical) or left / right (horizontal) edges of
     * the perspective quad so converging lines of buildings become parallel when applied.
     */
    fun setPerspectiveKeystone(vertical: Float, horizontal: Float) {
        val proj = _uiState.value.currentProject ?: return
        val w = proj.width.toFloat()
        val h = proj.height.toFloat()
        val v = vertical.coerceIn(-100f, 100f) / 100f
        val hz = horizontal.coerceIn(-100f, 100f) / 100f
        val insetX = w * 0.3f
        val insetY = h * 0.3f
        var tlx = 0f; var trx = w; var brx = w; var blx = 0f
        var tly = 0f; var tryy = 0f; var bry = h; var bly = h
        if (v > 0f) { tlx += v * insetX; trx -= v * insetX } else if (v < 0f) { blx += -v * insetX; brx -= -v * insetX }
        if (hz > 0f) { tly += hz * insetY; bly -= hz * insetY } else if (hz < 0f) { tryy += -hz * insetY; bry -= -hz * insetY }
        _uiState.value = _uiState.value.copy(
            perspectiveCorners = listOf(PointF(tlx, tly), PointF(trx, tryy), PointF(brx, bry), PointF(blx, bly)),
            perspectiveVertical = vertical,
            perspectiveHorizontal = horizontal
        )
    }

    /** Places the perspective corners on a detected document / poster / screen. */
    fun autoDetectPerspective() {
        val proj = _uiState.value.currentProject ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_detecting_document))
            try {
                val corners = withContext(Dispatchers.Default) {
                    val img = LayerRenderer.renderCompositeCanvas(
                        getApplication(), proj.layers, proj.width, proj.height,
                        includeBackground = true, maxPreviewDimension = 512
                    )
                    try {
                        val sx = proj.width.toFloat() / img.width
                        val sy = proj.height.toFloat() / img.height
                        ImageProcessor.detectDocumentCorners(img)?.map { PointF(it.x * sx, it.y * sy) }
                    } finally {
                        if (!img.isRecycled) img.recycle()
                    }
                }
                _uiState.value = if (corners != null) {
                    _uiState.value.copy(
                        perspectiveCorners = corners,
                        perspectiveVertical = 0f,
                        perspectiveHorizontal = 0f,
                        isProcessing = false,
                        statusMessage = tr(R.string.status_document_detected)
                    )
                } else {
                    _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_document_not_found))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_document_not_found))
            }
        }
    }

    private fun calculateDefaultPerspectiveCorners(proj: Project, selectedLayer: Layer?): List<PointF> {
        val w = proj.width.toFloat()
        val h = proj.height.toFloat()
        val insetX = (w * 0.08f).coerceAtLeast(10f)
        val insetY = (h * 0.08f).coerceAtLeast(10f)
        return listOf(
            PointF(insetX, insetY),              // 0: Top-Left
            PointF(w - insetX, insetY),          // 1: Top-Right
            PointF(w - insetX, h - insetY),      // 2: Bottom-Right
            PointF(insetX, h - insetY)           // 3: Bottom-Left
        )
    }

    fun setPerspectiveMode(active: Boolean) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        val selectedLayer = proj.layers.getOrNull(idx)
        val corners = calculateDefaultPerspectiveCorners(proj, selectedLayer)

        _uiState.value = _uiState.value.copy(
            isPerspectiveModeActive = active,
            perspectiveCorners = corners
        )
    }

    fun resetPerspectiveCorners() {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        val selectedLayer = proj.layers.getOrNull(idx)
        val corners = calculateDefaultPerspectiveCorners(proj, selectedLayer)

        _uiState.value = _uiState.value.copy(
            perspectiveCorners = corners
        )
    }

    fun updatePerspectiveCorner(index: Int, x: Float, y: Float) {
        val corners = _uiState.value.perspectiveCorners.toMutableList()
        if (index in corners.indices) {
            corners[index] = PointF(x, y)
            _uiState.value = _uiState.value.copy(perspectiveCorners = corners)
        }
    }

    fun updatePerspectiveCorners(newCorners: List<PointF>) {
        if (newCorners.size >= 4) {
            _uiState.value = _uiState.value.copy(perspectiveCorners = newCorners)
        }
    }

    fun fitPerspectiveCornersToCanvas() {
        val proj = _uiState.value.currentProject ?: return
        val w = proj.width.toFloat()
        val h = proj.height.toFloat()
        val corners = listOf(
            PointF(0f, 0f),
            PointF(w, 0f),
            PointF(w, h),
            PointF(0f, h)
        )
        _uiState.value = _uiState.value.copy(perspectiveCorners = corners)
    }

    fun applyPerspectiveTransform() {
        val proj = _uiState.value.currentProject ?: return
        val corners = _uiState.value.perspectiveCorners
        if (corners.size < 4) return

        val oldW = proj.width
        val oldH = proj.height

        // Output size from the rectangle's real proportions (or the chosen ratio).
        val (targetW, targetH) = ImageProcessor.perspectiveOutputSize(corners, oldW, oldH, _uiState.value.selectedAspectRatio)

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProcessing = true,
                statusMessage = tr(R.string.status_applying_perspective)
            )

            var historySaved = false
            try {
                saveHistoryState()
                historySaved = true

                // Homography that maps the chosen quad (document space) onto the output rectangle.
                // Used to keep vector (text) layers where the warped pixels end up.
                val quadToRect = Matrix().apply {
                    setPolyToPoly(
                        floatArrayOf(corners[0].x, corners[0].y, corners[1].x, corners[1].y, corners[2].x, corners[2].y, corners[3].x, corners[3].y), 0,
                        floatArrayOf(0f, 0f, targetW.toFloat(), 0f, targetW.toFloat(), targetH.toFloat(), 0f, targetH.toFloat()), 0,
                        4
                    )
                }

                // Apply Perspective Crop to all layers in the project
                for (layer in proj.layers) {
                    if (layer.type == LayerType.TEXT) {
                        // Text stays editable: move its anchor through the homography and scale by the local stretch.
                        val cx = oldW / 2f + layer.positionX
                        val cy = oldH / 2f + layer.positionY
                        val probe = floatArrayOf(cx, cy, cx + 50f, cy, cx, cy + 50f)
                        quadToRect.mapPoints(probe)
                        val sx = kotlin.math.hypot((probe[2] - probe[0]).toDouble(), (probe[3] - probe[1]).toDouble()).toFloat() / 50f
                        val sy = kotlin.math.hypot((probe[4] - probe[0]).toDouble(), (probe[5] - probe[1]).toDouble()).toFloat() / 50f
                        layer.positionX = probe[0] - targetW / 2f
                        layer.positionY = probe[1] - targetH / 2f
                        if (sx.isFinite() && sx > 0f) layer.scaleX *= sx
                        if (sy.isFinite() && sy > 0f) layer.scaleY *= sy
                        continue
                    }

                    val srcBmp = loadAuthoritativeLayerBitmap(layer) ?: continue
                    // The authoritative bitmap may be larger than the preview proxy; the work layer
                    // keeps the same document footprint so the matrix scales it correctly.
                    val workLayer = workLayerFor(layer, srcBmp)
                    // One resampling straight from the layer's own pixels (layer -> document -> output),
                    // without a full-canvas intermediate per layer: sharper and far lighter on memory.
                    val warpedBmp = withContext(Dispatchers.Default) {
                        val m = Matrix(SelectionEngine.getLayerMatrix(workLayer, oldW, oldH))
                        m.postConcat(quadToRect)
                        Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888).also { out ->
                            Canvas(out).drawBitmap(srcBmp, m, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                        }
                    }
                    if (srcBmp !== layer.bitmap && !srcBmp.isRecycled) srcBmp.recycle()

                    bakeMaskTransform(layer, oldW, oldH)
                    val oldMask = layer.maskBitmap
                    if (oldMask != null && !oldMask.isRecycled) {
                        val warpedMask = withContext(Dispatchers.Default) {
                            // Corners are document coordinates, so the mask must be document-sized.
                            val docMask = documentSizedMask(oldMask, oldW, oldH)
                            try {
                                Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ALPHA_8).also { out ->
                                    Canvas(out).drawBitmap(docMask, quadToRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                                }
                            } finally {
                                if (docMask !== oldMask && !docMask.isRecycled) docMask.recycle()
                            }
                        }
                        // The history snapshot keeps the old mask object; the live one is just replaced.
                        layer.maskBitmap = warpedMask
                    }

                    val committed = commitDestructiveBitmap(proj, layer, warpedBmp, targetW.toFloat(), targetH.toFloat(), "perspective_crop")
                    if (warpedBmp !== layer.bitmap && !warpedBmp.isRecycled) warpedBmp.recycle()
                    if (!committed) throw java.io.IOException("Failed to persist perspective-corrected layer")
                    layer.positionX = 0f
                    layer.positionY = 0f
                    layer.scaleX = 1f
                    layer.scaleY = 1f
                    layer.rotation = 0f
                    // The warp above went through the layer's full matrix, skew / distort included.
                    layer.skewX = 0f
                    layer.skewY = 0f
                    layer.distort = null
                    if (layer.maskBitmap != null) layer.maskAnchor = layer.currentMaskAnchor()
                }

                // Update canvas dimensions to the cropped & perspective-corrected size
                proj.width = targetW
                proj.height = targetH

                commitProjectMutation(
                    statusMessage = tr(R.string.status_perspective_applied),
                    transformState = {
                        it.copy(
                            activeTool = ActiveTool.NONE,
                            isPerspectiveModeActive = false,
                            selectedAspectRatio = null,
                            selectionMask = SelectionEngine.createBlankMask(targetW, targetH),
                            isProcessing = false
                        )
                    }
                )
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is OutOfMemoryError) System.gc()
                // Some layers may already be warped: roll the whole document back to the snapshot.
                if (historySaved) rollbackToLastHistoryState()
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusMessage = tr(if (e is OutOfMemoryError) R.string.status_processing_failed_memory else R.string.status_perspective_failed)
                )
            }
        }
    }

    fun applyCrop(cropRect: android.graphics.RectF? = null, aspectRatio: Float? = null) {
        val proj = _uiState.value.currentProject ?: return
        val oldW = proj.width
        val oldH = proj.height
        val angle = _uiState.value.cropStraighten
        val expand = _uiState.value.cropExpandCanvas
        val targetW = _uiState.value.cropTargetWidth
        val targetH = _uiState.value.cropTargetHeight
        val rect = cropRect ?: run {
            val r = aspectRatio
            if (r == null || r <= 0f) {
                android.graphics.RectF(0f, 0f, oldW.toFloat(), oldH.toFloat())
            } else {
                val nw: Int
                val nh: Int
                if (r >= oldW.toFloat() / oldH) {
                    nw = oldW
                    nh = (oldW / r).toInt().coerceAtLeast(1)
                } else {
                    nh = oldH
                    nw = (oldH * r).toInt().coerceAtLeast(1)
                }
                val l = (oldW - nw) / 2f
                val t = (oldH - nh) / 2f
                android.graphics.RectF(l, t, l + nw, t + nh)
            }
        }
        val l: Int
        val t: Int
        val r: Int
        val b: Int
        if (expand) {
            // The frame may reach beyond the image: the canvas grows (new area is transparent).
            l = kotlin.math.round(rect.left).toInt().coerceIn(-oldW * 2, oldW - 1)
            t = kotlin.math.round(rect.top).toInt().coerceIn(-oldH * 2, oldH - 1)
            r = kotlin.math.round(rect.right).toInt().coerceIn(l + 1, oldW * 3)
            b = kotlin.math.round(rect.bottom).toInt().coerceIn(t + 1, oldH * 3)
        } else {
            l = rect.left.toInt().coerceIn(0, oldW - 1)
            t = rect.top.toInt().coerceIn(0, oldH - 1)
            r = rect.right.toInt().coerceIn(l + 1, oldW)
            b = rect.bottom.toInt().coerceIn(t + 1, oldH)
        }
        val nw = r - l
        val nh = b - t
        val resize = targetW > 0 && targetH > 0 && (targetW != nw || targetH != nh)
        if (nw == oldW && nh == oldH && l == 0 && t == 0 && kotlin.math.abs(angle) < 0.01f && !resize) {
            _uiState.value = _uiState.value.copy(activeTool = ActiveTool.NONE, selectedAspectRatio = null, cropStraighten = 0f)
            return
        }
        saveHistoryState()
        proj.layers.forEach { bakeMaskTransform(it, oldW, oldH) }

        // 1. Straighten: rotate every layer around the image centre (masks follow).
        if (kotlin.math.abs(angle) >= 0.01f) {
            val rad = Math.toRadians(angle.toDouble())
            val cosA = kotlin.math.cos(rad).toFloat()
            val sinA = kotlin.math.sin(rad).toFloat()
            val rot = Matrix().apply { postRotate(angle, oldW / 2f, oldH / 2f) }
            proj.layers.forEach { layer ->
                val x = layer.positionX
                val y = layer.positionY
                layer.positionX = x * cosA - y * sinA
                layer.positionY = x * sinA + y * cosA
                layer.rotation = (((layer.rotation + angle) % 360f) + 360f) % 360f
                layer.maskBitmap?.let { m -> if (!m.isRecycled) layer.maskBitmap = remapDocumentMask(m, oldW, oldH, oldW, oldH, rot) }
            }
        }

        // 2. Crop (or expand) the canvas.
        proj.width = nw
        proj.height = nh
        val cropMatrix = Matrix().apply { postTranslate(-l.toFloat(), -t.toFloat()) }
        proj.layers.forEach {
            it.positionX += (oldW - nw) / 2f - l
            it.positionY += (oldH - nh) / 2f - t
            // Document-space masks must be cropped with the canvas to stay aligned with their layer.
            it.maskBitmap?.let { m ->
                if (!m.isRecycled) it.maskBitmap = remapDocumentMask(m, oldW, oldH, nw, nh, cropMatrix)
            }
        }

        // 3. Exact output size: resample the whole document to it.
        var finalW = nw
        var finalH = nh
        if (resize) {
            val sx = targetW.toFloat() / nw
            val sy = targetH.toFloat() / nh
            val scaleM = Matrix().apply { postScale(sx, sy) }
            proj.layers.forEach {
                it.positionX *= sx
                it.positionY *= sy
                it.scaleX *= sx
                it.scaleY *= sy
                it.maskBitmap?.let { m -> if (!m.isRecycled) it.maskBitmap = remapDocumentMask(m, nw, nh, targetW, targetH, scaleM) }
            }
            proj.width = targetW
            proj.height = targetH
            finalW = targetW
            finalH = targetH
        }
        proj.layers.forEach { if (it.maskBitmap != null) it.maskAnchor = it.currentMaskAnchor() }

        commitProjectMutation(
            statusMessage = tr(R.string.status_canvas_cropped),
            transformState = {
                it.copy(
                    activeTool = ActiveTool.NONE,
                    selectedAspectRatio = null,
                    cropStraighten = 0f,
                    cropTargetWidth = 0,
                    cropTargetHeight = 0,
                    selectionMask = SelectionEngine.createBlankMask(finalW, finalH),
                    isProcessing = false
                )
            }
        )
    }

    fun resetCrop() {
        _uiState.value = _uiState.value.copy(selectedAspectRatio = null, cropStraighten = 0f, cropTargetWidth = 0, cropTargetHeight = 0)
    }

    // --- Smart Auto Correction ---

    /** The corrections Smart Auto added to a layer, so their strength can be tuned afterwards. */
    private class SmartAutoSession(val layerId: String, val delta: ColorAdjustments, var strength: Float)
    private var smartAutoSession: SmartAutoSession? = null

    private fun clearSmartAutoSession() {
        smartAutoSession = null
        if (_uiState.value.smartAutoStrength != null) _uiState.value = _uiState.value.copy(smartAutoStrength = null)
    }

    /**
     * Analyses the layer as it currently looks and ADDS the corrections it still needs to its
     * adjustments (curves, HSL, colour replacement and the user's own slider values are kept).
     * The strength can then be tuned with [setSmartAutoStrength].
     */
    /** The last Smart Auto found visible noise. */
    private var smartAutoNoisy = false

    fun applySmartAutoCorrection() {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx !in proj.layers.indices) return
        val layer = proj.layers[idx]
        if (layer.isLocked) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_layer_locked))
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProcessing = true,
                statusMessage = tr(R.string.status_analyzing_image)
            )
            var src: Bitmap? = null
            try {
                // A small image is enough for analysis. An adjustment layer is analysed on what is
                // below it; an image layer on its own pixels.
                val own = layer.bitmap?.takeIf { layer.type == LayerType.IMAGE && !it.isRecycled }
                src = withContext(Dispatchers.Default) {
                    own?.let { ImageProcessor.scaleBitmapToMaxDimension(it, 1024) } ?: LayerRenderer.renderCompositeCanvas(
                        getApplication(),
                        proj.layers.subList(0, idx),
                        proj.width,
                        proj.height,
                        includeBackground = true,
                        maxPreviewDimension = 1024
                    )
                }
                // Faces: exposure and white balance are judged on them first.
                val faces = try {
                    com.example.engine.FaceWarp.detect(src!!).map { f ->
                        android.graphics.RectF(f.box.left / src!!.width, f.box.top / src!!.height, f.box.right / src!!.width, f.box.bottom / src!!.height)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { emptyList() }
                // Noise is measured at full detail: a crop from the middle of the layer's pixels.
                val detailSource = own ?: src!!
                val result = withContext(Dispatchers.Default) {
                    val cw = minOf(768, detailSource.width); val ch = minOf(768, detailSource.height)
                    val detail = Bitmap.createBitmap(detailSource, (detailSource.width - cw) / 2, (detailSource.height - ch) / 2, cw, ch)
                    try {
                        com.example.engine.SmartAuto.analyze(src!!, layer.adjustments, faces, detail)
                    } finally {
                        if (detail !== detailSource && !detail.isRecycled) detail.recycle()
                    }
                }
                val delta = result.delta
                smartAutoNoisy = result.noisy
                saveHistoryState()
                // With a selection the correction goes to a clipped adjustment layer masked by it.
                val curIdx = proj.layers.indexOf(layer)
                val look = if (curIdx >= 0) selectionLookLayer(proj, curIdx) else null
                val target = if (look != null) {
                    look.adjustments = ImageProcessor.combineSmartAuto(ColorAdjustments(), delta, 1f)
                    proj.layers.add(curIdx + 1, look)
                    look
                } else {
                    layer.adjustments = ImageProcessor.combineSmartAuto(layer.adjustments, delta, 1f)
                    layer
                }
                smartAutoSession = SmartAutoSession(target.id, delta, 1f)
                commitProjectMutation(
                    selectedLayerIndex = if (look != null) curIdx + 1 else _uiState.value.selectedLayerIndex,
                    statusMessage = when {
                        delta.isIdentity() && !delta.hasCurves() -> tr(R.string.status_smart_auto_nothing)
                        // Noisy photo: sharpening was left out; suggest noise reduction.
                        result.noisy -> tr(R.string.status_smart_auto_noisy)
                        else -> tr(R.string.status_smart_auto_applied)
                    },
                    transformState = { it.copy(isProcessing = false, smartAutoStrength = if (delta.isIdentity() && !delta.hasCurves()) null else 1f) }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusMessage = tr(R.string.status_smart_auto_failed)
                )
            } finally {
                val p = layer.bitmap
                src?.let { if (it !== p && !it.isRecycled) it.recycle() }
            }
        }
    }

    /**
     * Live strength (0..150%) of the last Smart Auto. Only the Smart Auto part changes, so slider
     * edits made after it are kept. One undo step per drag ([commitPendingHistory] on release).
     */
    fun setSmartAutoStrength(strength: Float) {
        val session = smartAutoSession ?: return
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(_uiState.value.selectedLayerIndex) ?: return
        if (layer.id != session.layerId) { clearSmartAutoSession(); return }
        val s = strength.coerceIn(0f, 1.5f)
        val change = s - session.strength
        if (kotlin.math.abs(change) < 0.001f) return
        updateSelectedLayerTransient { l -> l.adjustments = ImageProcessor.combineSmartAuto(l.adjustments, session.delta, change) }
        session.strength = s
        _uiState.value = _uiState.value.copy(smartAutoStrength = s)
    }

    /**
     * Colour at a document point as it enters the curves of the selected layer: the layer's pixels
     * (5x5 average) with its other adjustments applied, curves excluded. An adjustment layer
     * samples the topmost visible image layer under the point. Null when there is no image there.
     */
    fun sampleColorForCurves(docX: Float, docY: Float): Int? {
        val identity = listOf(PointF(0f, 0f), PointF(255f, 255f))
        // Curves come before colour replacement, so neither is applied to the sample.
        return sampleLayerColor(docX, docY) {
            it.copy(curvesRgb = identity, curvesRed = identity, curvesGreen = identity, curvesBlue = identity,
                colorReplacements = emptyList(), replaceColorEnabled = false, replacePreviewMask = false)
        }
    }

    /** Colour at a document point as it enters the HSL step (targeted adjustment). */
    fun sampleColorForHsl(docX: Float, docY: Float): Int? =
        sampleLayerColor(docX, docY) {
            it.copy(
                hslBands = emptyMap(), hueShift = 0f, hslSaturation = 0f, hslLightness = 0f, colorizeEnabled = false,
                colorReplacements = emptyList(), replaceColorEnabled = false, replacePreviewMask = false
            )
        }

    /** Colour at a document point as it enters colour replacement (everything else applied). */
    fun sampleColorForReplace(docX: Float, docY: Float): Int? =
        sampleLayerColor(docX, docY) { it.copy(colorReplacements = emptyList(), replaceColorEnabled = false, replacePreviewMask = false) }

    private fun sampleLayerColor(docX: Float, docY: Float, adjustFor: (ColorAdjustments) -> ColorAdjustments): Int? {
        val proj = _uiState.value.currentProject ?: return null
        val selected = proj.layers.getOrNull(_uiState.value.selectedLayerIndex) ?: return null
        val source = if (selected.type == LayerType.IMAGE && selected.bitmap != null) selected else {
            proj.layers.lastOrNull { l ->
                l.isVisible && l.type == LayerType.IMAGE && l.bitmap != null &&
                    SelectionEngine.mapCanvasToLayerLocal(docX, docY, l, proj.width, proj.height) != null
            } ?: return null
        }
        val bmp = source.bitmap ?: return null
        if (bmp.isRecycled) return null
        val local = SelectionEngine.mapCanvasToLayerLocal(docX, docY, source, proj.width, proj.height) ?: return null
        val cx = local.x.toInt().coerceIn(0, bmp.width - 1)
        val cy = local.y.toInt().coerceIn(0, bmp.height - 1)
        var r = 0; var g = 0; var b = 0; var n = 0
        for (y in (cy - 2).coerceAtLeast(0)..(cy + 2).coerceAtMost(bmp.height - 1)) {
            for (x in (cx - 2).coerceAtLeast(0)..(cx + 2).coerceAtMost(bmp.width - 1)) {
                val p = bmp.getPixel(x, y)
                if ((p ushr 24) < 16) continue
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
            }
        }
        if (n == 0) return null
        val raw = Color.rgb(r / n, g / n, b / n)
        val adj = adjustFor(selected.adjustments)
        if (adj.isIdentity()) return raw
        val one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, raw) }
        val out = ImageProcessor.applyAdjustments(one, adj)
        val c = out.getPixel(0, 0)
        if (out !== one) out.recycle()
        one.recycle()
        return c
    }

    /**
     * Adds an adjustment layer above the selected layer whose mask is the current selection, the
     * non-destructive way to adjust only part of the image.
     */
    fun createAdjustmentLayerFromSelection() {
        val proj = _uiState.value.currentProject ?: return
        val selection = _uiState.value.selectionMask?.takeIf { !it.isRecycled && SelectionEngine.hasSelection(it) } ?: return
        saveHistoryState()
        val mask = documentSizedMask(selection, proj.width, proj.height).let { if (it === selection) it.copy(Bitmap.Config.ALPHA_8, true) else it }
        val layer = Layer(
            id = java.util.UUID.randomUUID().toString(),
            name = tr(R.string.layer_name_adjustment, proj.layers.count { it.type == LayerType.ADJUSTMENT } + 1),
            type = LayerType.ADJUSTMENT,
            adjustments = ColorAdjustments(),
            maskBitmap = mask
        )
        layer.maskAnchor = layer.currentMaskAnchor()
        val insertAt = (_uiState.value.selectedLayerIndex + 1).coerceIn(0, proj.layers.size)
        proj.layers.add(insertAt, layer)
        clearSmartAutoSession()
        commitProjectMutation(
            selectedLayerIndex = insertAt,
            statusMessage = tr(R.string.status_adjustment_layer_from_selection)
        )
    }

    fun centerSelectedLayer() {
        updateSelectedLayer { layer ->
            layer.positionX = 0f
            layer.positionY = 0f
        }
    }

    fun fitSelectedLayerToCanvas() {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx !in proj.layers.indices) return
        val layer = proj.layers[idx]
        val (w, h) = if (layer.bitmap != null && layer.bitmap!!.width > 0) {
            Pair(layer.bitmap!!.width.toFloat(), layer.bitmap!!.height.toFloat())
        } else {
            Pair(layer.contentWidth.coerceAtLeast(100f), layer.contentHeight.coerceAtLeast(100f))
        }
        val scale = min(proj.width.toFloat() / w, proj.height.toFloat() / h)
        updateSelectedLayer {
            it.positionX = 0f
            it.positionY = 0f
            it.scaleX = scale
            it.scaleY = scale
            it.rotation = 0f
        }
    }

    fun fillSelectedLayerToCanvas() {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx !in proj.layers.indices) return
        val layer = proj.layers[idx]
        val (w, h) = if (layer.bitmap != null && layer.bitmap!!.width > 0) {
            Pair(layer.bitmap!!.width.toFloat(), layer.bitmap!!.height.toFloat())
        } else {
            Pair(layer.contentWidth.coerceAtLeast(100f), layer.contentHeight.coerceAtLeast(100f))
        }
        val scale = max(proj.width.toFloat() / w, proj.height.toFloat() / h)
        updateSelectedLayer {
            it.positionX = 0f
            it.positionY = 0f
            it.scaleX = scale
            it.scaleY = scale
            it.rotation = 0f
        }
    }

    fun setInpaintSelectionMode(mode: InpaintSelectionMode) {
        _uiState.value = _uiState.value.copy(inpaintSelectionMode = mode)
    }

    fun setInpaintRadius(v: Float) { _uiState.value=_uiState.value.copy(inpaintRadiusPx=v.coerceIn(2f,15f)) }
    fun setInpaintFeather(v: Int) { _uiState.value=_uiState.value.copy(inpaintFeatherPx=v.coerceIn(0,24)) }
    fun setInpaintEngineMode(mode: InpaintEngineMode) { _uiState.value = _uiState.value.copy(inpaintEngineMode = mode) }
    fun toggleSmartEdgeSnap() { _uiState.value = _uiState.value.copy(isSmartEdgeSnapEnabled = !_uiState.value.isSmartEdgeSnapEnabled) }
    fun toggleSeamlessBlend() { _uiState.value = _uiState.value.copy(isSeamlessBlendEnabled = !_uiState.value.isSeamlessBlendEnabled) }

    /** What the last object removal used, so it can be run again with other texture choices. */
    private class RemovalSession(val layerId: String, val docMask: Bitmap, val protect: Bitmap?, val seed: Int, val undoDepth: Int)
    private var lastRemoval: RemovalSession? = null
    private var protectLastPoint: PointF? = null

    fun setInpaintProtectMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            isInpaintProtectMode = enabled,
            // Protecting is painted with the brush.
            inpaintSelectionMode = if (enabled) InpaintSelectionMode.BRUSH else _uiState.value.inpaintSelectionMode
        )
    }

    fun clearInpaintProtect() {
        protectLastPoint = null
        _uiState.value = _uiState.value.copy(inpaintProtectMask = null, inpaintProtectRevision = _uiState.value.inpaintProtectRevision + 1)
    }

    /**
     * Paints (or, in Erase mode, erases) the protected area: texture is never copied from it, e.g.
     * another person or a logo that must not be cloned into the hole.
     */
    fun paintProtectSegment(points: List<PointF>, docRadius: Float, phase: StrokePhase) {
        val proj = _uiState.value.currentProject ?: return
        if (points.isEmpty()) return
        val w = proj.width
        val h = proj.height
        var mask = _uiState.value.inpaintProtectMask
        if (mask == null || mask.isRecycled || mask.width != w || mask.height != h) {
            mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        }
        if (phase == StrokePhase.START) protectLastPoint = null
        val erase = effectiveSelectionMode() == SelectionMode.SUBTRACT
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = (docRadius * 2f).coerceAtLeast(1f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (erase) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val path = Path()
        val first = protectLastPoint ?: points.first()
        path.moveTo(first.x, first.y)
        if (protectLastPoint == null && points.size == 1) path.lineTo(first.x + 0.1f, first.y)
        for (p in points) path.lineTo(p.x, p.y)
        Canvas(mask).drawPath(path, paint)
        protectLastPoint = if (phase == StrokePhase.END) null else PointF(points.last().x, points.last().y)
        _uiState.value = _uiState.value.copy(inpaintProtectMask = mask, inpaintProtectRevision = _uiState.value.inpaintProtectRevision + 1)
    }

    fun applyObjectRemoval() {
        val mask = _uiState.value.selectionMask ?: return
        if (!SelectionEngine.hasSelection(mask)) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_select_object_first))
            return
        }
        val proj = _uiState.value.currentProject ?: return
        val protect = _uiState.value.inpaintProtectMask
            ?.takeIf { !it.isRecycled && it.width == proj.width && it.height == proj.height }
            ?.copy(Bitmap.Config.ALPHA_8, false)
        runObjectRemoval(mask.copy(Bitmap.Config.ALPHA_8, false), protect, 0x5EED)
    }

    /**
     * "Another result": undoes the last removal and runs it again on the same area with different
     * texture choices. Only available while that removal is still the latest edit.
     */
    fun retryObjectRemoval() {
        val session = lastRemoval ?: return
        if (!_uiState.value.canRetryRemoval) return
        val proj = _uiState.value.currentProject ?: return
        val idx = proj.layers.indexOfFirst { it.id == session.layerId }
        if (idx < 0) return
        undo()
        _uiState.value = _uiState.value.copy(selectedLayerIndex = idx)
        runObjectRemoval(session.docMask, session.protect, session.seed + 7919)
    }

    private fun runObjectRemoval(mask: Bitmap, protectDoc: Bitmap?, seed: Int) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx !in proj.layers.indices) return
        val layer = proj.layers[idx]
        if (layer.isLocked || layer.type != LayerType.IMAGE) return
        val radius = _uiState.value.inpaintRadiusPx.toDouble()
        val feather = _uiState.value.inpaintFeatherPx
        val mode = _uiState.value.inpaintEngineMode
        val smartSnap = _uiState.value.isSmartEdgeSnapEnabled
        val seamless = _uiState.value.isSeamlessBlendEnabled

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_content_aware_removal))
            var src: Bitmap? = null
            var local: Bitmap? = null
            var localProtect: Bitmap? = null
            var out: Bitmap? = null
            var historySaved = false
            try {
                src = loadAuthoritativeLayerBitmap(layer) ?: throw IllegalStateException("Image unavailable")
                // workLayerFor keeps the layer's document footprint, so the mask lands on exactly the
                // pixels the user painted over even when the full-res source is larger than the preview.
                val workLayer = workLayerFor(layer, src!!)
                local = withContext(Dispatchers.Default) {
                    SelectionEngine.mapDocumentMaskToLayerLocal(mask, workLayer, proj.width, proj.height)
                }
                if (protectDoc != null) {
                    localProtect = withContext(Dispatchers.Default) {
                        SelectionEngine.mapDocumentMaskToLayerLocal(protectDoc, workLayer, proj.width, proj.height)
                    }
                }
                out = withContext(Dispatchers.Default) {
                    ImageProcessor.inpaintObjectRemoval(
                        source = src!!,
                        maskBitmap = local!!,
                        radiusPx = radius,
                        featherPx = feather,
                        mode = mode,
                        smartEdgeSnap = smartSnap,
                        seamlessBlend = seamless,
                        protectMask = localProtect,
                        seed = seed
                    )
                }
                saveHistoryState()
                historySaved = true
                if (!commitDestructiveBitmap(proj, layer, out!!, layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight(), "content_aware", keepBefore = true)) {
                    throw java.io.IOException("Save failed")
                }
                lastRemoval = RemovalSession(layer.id, mask, protectDoc, seed, undoStack.size)
                commitProjectMutation(
                    statusMessage = tr(R.string.status_object_removed),
                    transformState = { it.copy(selectionMask = SelectionEngine.createBlankMask(proj.width, proj.height), isProcessing = false) }
                )
                clearSelectionHistory()
                updateHistoryFlags()
            } catch (e: OutOfMemoryError) {
                // Failed after its undo step was recorded: drop that step (and any partial change).
                if (historySaved) rollbackToLastHistoryState()
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_processing_failed_memory))
            } catch (e: Exception) {
                // Failed after its undo step was recorded: drop that step (and any partial change).
                if (historySaved) rollbackToLastHistoryState()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_object_remove_failed))
            } finally {
                local?.let { if (!it.isRecycled) it.recycle() }
                localProtect?.let { if (!it.isRecycled) it.recycle() }
                val p = layer.bitmap
                out?.let { if (it !== p && !it.isRecycled) it.recycle() }
                src?.let { if (it !== out && it !== p && !it.isRecycled) it.recycle() }
            }
        }
    }

    // --- Selection Operations ---
    fun setSelectionTool(type: SelectionToolType) {
        _uiState.value = _uiState.value.copy(activeSelectionTool = type)
    }

    fun setSelectionMode(mode: SelectionMode) {
        _uiState.value = _uiState.value.copy(selectionMode = mode)
    }

    fun setBrushSize(sizeDp: Float) {
        _uiState.value = _uiState.value.copy(brushSizeDp = sizeDp)
    }

    fun setMagicWandTolerance(tolerance: Int) {
        _uiState.value = _uiState.value.copy(magicWandTolerance = tolerance)
    }

    fun setMagicWandContiguous(contiguous: Boolean) {
        _uiState.value = _uiState.value.copy(isMagicWandContiguous = contiguous)
    }

    fun setWandOptions(options: com.example.engine.WandOptions) {
        _uiState.value = _uiState.value.copy(wandOptions = options)
    }

    /**
     * Serialises every selection edit and applies it copy-on-write: the published mask bitmap is
     * never mutated in place (except the private working copy of a live brush stroke), so the
     * canvas, the renderer and the undo history always read a consistent mask.
     */
    private val selectionMutex = Mutex()

    /** Mode actually used by selection tools: the hold-to-subtract button overrides the chosen mode. */
    private fun effectiveSelectionMode(): SelectionMode =
        _uiState.value.selectionModeOverride ?: _uiState.value.selectionMode

    fun setSelectionModeOverride(mode: SelectionMode?) {
        _uiState.value = _uiState.value.copy(selectionModeOverride = mode)
    }

    fun toggleQuickMaskView() {
        _uiState.value = _uiState.value.copy(isQuickMaskView = !_uiState.value.isQuickMaskView)
    }

    fun setShapeFromCenter(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(shapeFromCenter = enabled)
    }

    fun setShapeLockSquare(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(shapeLockSquare = enabled)
    }

    fun setQuickSelectionAutoEnhance(enable: Boolean) {
        _uiState.value = _uiState.value.copy(isQuickSelectionAutoEnhance = enable)
    }

    fun setQuickSelectionSampleAll(enable: Boolean) {
        _uiState.value = _uiState.value.copy(isQuickSelectionSampleAll = enable)
    }

    fun setQuickSelectionTolerance(value: Int) {
        _uiState.value = _uiState.value.copy(quickSelectionTolerance = value.coerceIn(5, 100))
    }

    fun setQuickSelectionEdgeSensitivity(value: Int) {
        _uiState.value = _uiState.value.copy(quickSelectionEdgeSensitivity = value.coerceIn(0, 100))
    }

    private fun quickSelectOptions() = com.example.engine.QuickSelectOptions(
        tolerance = _uiState.value.quickSelectionTolerance,
        edgeSensitivity = _uiState.value.quickSelectionEdgeSensitivity,
        autoEnhance = _uiState.value.isQuickSelectionAutoEnhance
    )

    // Transient sessions: an editable shape, a live modifier slider and a live magic-wand drag all
    // recompute from the mask that existed when they started. Any other selection edit ends them.
    private var shapeBaseMask: Bitmap? = null
    private var shapeMode: SelectionMode = SelectionMode.ADD
    private var modifierBaseMask: Bitmap? = null
    private var modifierJob: Job? = null
    private var wandSession: WandSession? = null
    private var wandJob: Job? = null

    private class WandSession(
        val base: Bitmap,
        val source: com.example.engine.WandSource,
        val x: Int,
        val y: Int,
        val mode: SelectionMode,
        val contiguous: Boolean,
        val options: com.example.engine.WandOptions
    )

    // The wand's colour source (image as shown, in Lab) is reused while the image is unchanged,
    // so repeated taps and tolerance drags only re-run the fill. Accessed under selectionMutex.
    private class WandSourceEntry(val key: String, val source: com.example.engine.WandSource)
    @Volatile private var wandSourceCache: WandSourceEntry? = null

    private fun wandLayerKey(l: Layer): String =
        "${l.hashCode()}:${System.identityHashCode(l.bitmap)}:${l.bitmap?.generationId}:" +
            "${System.identityHashCode(l.maskBitmap)}:${l.maskBitmap?.generationId}"

    /**
     * Colour source for the wand: [layer] as rendered on the canvas (adjustments, filters, LUT
     * and transform applied), or the whole visible image when [layer] is null. Rendered in
     * document space at up to 2560 px, sharper than the 2048 px editing proxy.
     */
    private suspend fun wandSourceFor(proj: Project, layer: Layer?): com.example.engine.WandSource {
        val key = buildString {
            append(proj.id).append(':').append(proj.width).append('x').append(proj.height).append(':')
            if (layer != null) append("L:").append(wandLayerKey(layer))
            else proj.layers.forEach { append("A:").append(wandLayerKey(it)).append(';') }
        }
        wandSourceCache?.takeIf { it.key == key }?.let { return it.source }
        wandSourceCache = null // free the old source before allocating a new one
        val job = currentCoroutineContext()[Job]
        val isCancelled: () -> Boolean = { job?.isActive == false }
        suspend fun build(maxDim: Int): com.example.engine.WandSource {
            val bmp = renderDocumentSamplingImage(proj, layer, maxDim)
            try {
                return com.example.engine.MagicWandEngine.buildSource(bmp, proj.width, proj.height, isCancelled)
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
        val source = try {
            build(2560)
        } catch (e: OutOfMemoryError) {
            System.gc()
            build(1280)
        }
        wandSourceCache = WandSourceEntry(key, source)
        return source
    }

    /** [current] = the coroutine calling this, which must not cancel itself. */
    private fun endTransientSelectionSessions(current: Job? = null) {
        shapeBaseMask = null
        modifierBaseMask = null
        // Any other selection edit ends a refine session; its preview stays as the selection.
        if (_uiState.value.selectionRefine != SelectionRefineParams()) {
            _uiState.value = _uiState.value.copy(selectionRefine = SelectionRefineParams())
        }
        if (modifierJob !== current) {
            modifierJob?.cancel()
            modifierJob = null
        }
        wandSession = null
        if (wandJob !== current) {
            wandJob?.cancel()
            wandJob = null
        }
        if (_uiState.value.editableShape != null) {
            _uiState.value = _uiState.value.copy(editableShape = null)
        }
    }

    private fun mutateSelectionAsync(
        @StringRes progressRes: Int? = null,
        @StringRes failureRes: Int? = null,
        block: suspend (Bitmap) -> Unit
    ) {
        viewModelScope.launch {
            selectionMutex.withLock {
                endTransientSelectionSessions()
                val base = _uiState.value.selectionMask ?: return@withLock
                if (base.isRecycled) return@withLock
                if (progressRes != null) {
                    _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(progressRes))
                }
                try {
                    val working = withContext(Dispatchers.Default) {
                        base.copy(Bitmap.Config.ALPHA_8, true).also { block(it) }
                    }
                    // Recorded only once the edit succeeded (the current mask is still [base]), so a
                    // failed edit leaves no empty undo step.
                    rememberSelectionState()
                    publishSelection(working)
                    if (progressRes != null) {
                        _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = null)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    System.gc()
                    _uiState.value = _uiState.value.copy(
                        isProcessing = if (progressRes != null) false else _uiState.value.isProcessing,
                        statusMessage = tr(R.string.status_processing_failed_memory)
                    )
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = if (progressRes != null) false else _uiState.value.isProcessing,
                        statusMessage = tr(failureRes ?: R.string.status_selection_failed)
                    )
                }
            }
        }
    }

    /** Publishes a mask derived from the current one (e.g. clear, move) in order with queued edits. */
    private fun replaceSelectionAsync(newMask: (Bitmap?) -> Bitmap) {
        viewModelScope.launch {
            selectionMutex.withLock {
                endTransientSelectionSessions()
                rememberSelectionState()
                val current = _uiState.value.selectionMask
                val next = withContext(Dispatchers.Default) { newMask(current) }
                publishSelection(next)
            }
        }
    }

    /**
     * Document-space image used for colour sampling (quick selection). It is rendered at a bounded
     * preview size; callers map document coordinates by the size ratio.
     */
    private suspend fun renderDocumentSamplingImage(proj: Project, layer: Layer?, maxDim: Int = 1536): Bitmap {
        val ratio = min(1f, maxDim.toFloat() / max(proj.width, proj.height).toFloat())
        if (layer != null && layer.type == LayerType.IMAGE && layer.bitmap != null) {
            val tw = (proj.width * ratio).toInt().coerceAtLeast(1)
            val th = (proj.height * ratio).toInt().coerceAtLeast(1)
            LayerRenderer.renderSingleLayerToBitmap(getApplication(), layer, tw, th, ratio)?.let { return it }
        }
        return LayerRenderer.renderCompositeCanvas(
            getApplication(), proj.layers, proj.width, proj.height,
            includeBackground = true, maxPreviewDimension = maxDim
        )
    }

    fun applyBrushStroke(points: List<PointF>, isEraser: Boolean = false, docRadius: Float? = null) {
        if (_uiState.value.selectionMask == null || points.isEmpty()) return
        val radius = docRadius ?: _uiState.value.brushSizeDp
        val mode = effectiveSelectionMode()
        mutateSelectionAsync { mask ->
            SelectionEngine.drawBrushStroke(mask, points, radius, isEraser, mode)
        }
    }

    fun applyQuickSelection(points: List<PointF>, isEraser: Boolean = false, docRadius: Float? = null) {
        val proj = _uiState.value.currentProject ?: return
        if (_uiState.value.selectionMask == null || points.isEmpty()) return
        val radius = docRadius ?: _uiState.value.brushSizeDp
        val mode = if (isEraser) SelectionMode.SUBTRACT else effectiveSelectionMode()
        val options = quickSelectOptions()
        val sampleAll = _uiState.value.isQuickSelectionSampleAll
        val activeLayer = if (sampleAll) null else proj.layers.getOrNull(_uiState.value.selectedLayerIndex)
        val pts = points.toList()

        mutateSelectionAsync { mask ->
            // The mask is in document space, so colours are sampled from a document-space image
            // (the active layer as rendered on the canvas, or the full composite) at high detail.
            val source = buildQuickSource(proj, activeLayer, finalQuickDim(proj))
            com.example.engine.QuickSelectEngine.select(source, mask, pts, radius, mode, options)
        }
    }

    /** Detail of the precise pass (stroke end / one-shot): the image size, up to 3072 px. */
    private fun finalQuickDim(proj: Project): Int = min(3072, max(proj.width, proj.height)).coerceAtLeast(1)

    /** Builds a Quick Selection source at [maxDim]; falls back to half the size when memory is short. */
    private suspend fun buildQuickSource(proj: Project, layer: Layer?, maxDim: Int): com.example.engine.QuickSelectSource {
        suspend fun build(dim: Int): com.example.engine.QuickSelectSource {
            val bmp = renderDocumentSamplingImage(proj, layer, dim)
            try {
                return withContext(Dispatchers.Default) {
                    com.example.engine.QuickSelectEngine.buildSource(bmp, proj.width, proj.height)
                }
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
        return try {
            build(maxDim)
        } catch (e: OutOfMemoryError) {
            System.gc()
            build((maxDim / 2).coerceAtLeast(256))
        }
    }

    // --- Live strokes: the selection updates WHILE the finger moves -----------------------------


    /** Working copy owned by the current live brush stroke (published as the selection mask). */
    private var liveBrushMask: Bitmap? = null
    /** Points collected when a stroke cannot be applied incrementally (INTERSECT, or a busy queue). */
    private val deferredStrokePoints = ArrayList<PointF>()
    private var strokeIsDeferred = false
    private var liveStrokeMode: SelectionMode = SelectionMode.ADD
    private val liveQuickMutex = Mutex()

    /** State of one live Quick Selection stroke (its own object, so strokes never mix). */
    private class LiveQuickStroke(
        val proj: Project,
        val layer: Layer?,
        val working: Bitmap,
        val base: Bitmap,
        val mode: SelectionMode,
        val radius: Float,
        val options: com.example.engine.QuickSelectOptions
    ) {
        val all = ArrayList<PointF>()
        val pending = ArrayList<PointF>()
        var ended = false
        var worker: Job? = null
        var source: com.example.engine.QuickSelectSource? = null
        val segmentMode: SelectionMode get() = if (mode == SelectionMode.REPLACE) SelectionMode.ADD else mode
    }
    private var liveQuick: LiveQuickStroke? = null

    /**
     * Selection brush (Quick Brush tool and Object-Removal brush). Each segment is painted straight
     * into a private working copy of the mask, so the marching ants / red overlay follow the finger.
     */
    fun brushSelectionSegment(points: List<PointF>, docRadius: Float, phase: StrokePhase) {
        if (phase == StrokePhase.START) {
            liveStrokeMode = effectiveSelectionMode()
            deferredStrokePoints.clear()
            strokeIsDeferred = liveStrokeMode == SelectionMode.INTERSECT || selectionMutex.isLocked
            if (!strokeIsDeferred) {
                endTransientSelectionSessions()
                val base = _uiState.value.selectionMask ?: return
                recordHistoryKind(HistoryKind.SELECTION)
                // The published base is immutable, so it can be compressed for undo in the background.
                viewModelScope.launch(Dispatchers.Default) {
                    selectionHistory.push(base)
                    withContext(Dispatchers.Main) { updateSelectionHistoryFlags() }
                }
                val working = base.copy(Bitmap.Config.ALPHA_8, true)
                if (liveStrokeMode == SelectionMode.REPLACE) working.eraseColor(Color.TRANSPARENT)
                liveBrushMask = working
                publishSelection(working)
            }
        }
        if (strokeIsDeferred) {
            deferredStrokePoints.addAll(points)
            if (phase == StrokePhase.END) {
                val all = deferredStrokePoints.toList()
                deferredStrokePoints.clear()
                strokeIsDeferred = false
                if (all.isNotEmpty()) applyBrushStroke(all, docRadius = docRadius)
            }
            return
        }
        val working = liveBrushMask ?: return
        if (points.isNotEmpty()) {
            val segmentMode = if (liveStrokeMode == SelectionMode.REPLACE) SelectionMode.ADD else liveStrokeMode
            SelectionEngine.drawBrushStroke(working, points, docRadius, isEraser = false, mode = segmentMode)
            _uiState.value = _uiState.value.copy(
                selectionRevision = _uiState.value.selectionRevision + 1,
                renderRevision = _uiState.value.renderRevision + 1
            )
        }
        if (phase == StrokePhase.END) liveBrushMask = null
    }

    /**
     * Quick Selection with live feedback. While the finger moves, segments are coalesced (the
     * worker never falls behind: everything that arrived while it was busy is processed in one go)
     * and run on a fast 1024 px source with the colour model of the whole stroke so far. When the
     * finger lifts, the whole stroke is recomputed once from the pre-stroke selection at full detail
     * (up to 3072 px), so edges are as precise as the image. One undo step per stroke.
     */
    fun quickSelectionSegment(points: List<PointF>, docRadius: Float, phase: StrokePhase) {
        val proj = _uiState.value.currentProject ?: return
        if (phase == StrokePhase.START) {
            liveStrokeMode = effectiveSelectionMode()
            deferredStrokePoints.clear()
            strokeIsDeferred = liveStrokeMode == SelectionMode.INTERSECT || selectionMutex.isLocked
            liveQuick = null
            if (!strokeIsDeferred) {
                endTransientSelectionSessions()
                val base = _uiState.value.selectionMask ?: return
                recordHistoryKind(HistoryKind.SELECTION)
                viewModelScope.launch(Dispatchers.Default) {
                    selectionHistory.push(base)
                    withContext(Dispatchers.Main) { updateSelectionHistoryFlags() }
                }
                val working = base.copy(Bitmap.Config.ALPHA_8, true)
                if (liveStrokeMode == SelectionMode.REPLACE) working.eraseColor(Color.TRANSPARENT)
                val sampleAll = _uiState.value.isQuickSelectionSampleAll
                liveQuick = LiveQuickStroke(
                    proj = proj,
                    layer = if (sampleAll) null else proj.layers.getOrNull(_uiState.value.selectedLayerIndex),
                    working = working,
                    base = base,
                    mode = liveStrokeMode,
                    radius = docRadius,
                    options = quickSelectOptions()
                )
                publishSelection(working)
            }
        }
        if (strokeIsDeferred) {
            deferredStrokePoints.addAll(points)
            if (phase == StrokePhase.END) {
                val all = deferredStrokePoints.toList()
                deferredStrokePoints.clear()
                strokeIsDeferred = false
                if (all.isNotEmpty()) applyQuickSelection(all, docRadius = docRadius)
            }
            return
        }
        val stroke = liveQuick ?: return
        stroke.pending.addAll(points)
        stroke.all.addAll(points)
        if (phase == StrokePhase.END) {
            stroke.ended = true
            liveQuick = null
        }
        kickLiveQuick(stroke)
    }

    private fun kickLiveQuick(s: LiveQuickStroke) {
        if (s.worker?.isActive == true) return
        s.worker = viewModelScope.launch {
            liveQuickMutex.withLock {
                try {
                    if (s.source == null && (s.pending.isNotEmpty() || s.ended)) {
                        s.source = buildQuickSource(s.proj, s.layer, min(1024, finalQuickDim(s.proj)))
                    }
                    while (s.pending.isNotEmpty()) {
                        val segment = ArrayList(s.pending)
                        s.pending.clear()
                        val model = ArrayList(s.all)
                        val src = s.source ?: break
                        withContext(Dispatchers.Default) {
                            com.example.engine.QuickSelectEngine.select(
                                src, s.working, segment, s.radius, s.segmentMode, s.options, modelPoints = model
                            )
                        }
                        if (_uiState.value.selectionMask === s.working) {
                            _uiState.value = _uiState.value.copy(
                                selectionRevision = _uiState.value.selectionRevision + 1,
                                renderRevision = _uiState.value.renderRevision + 1
                            )
                        }
                    }
                    if (s.ended) finalQuickPass(s)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (e is OutOfMemoryError) System.gc()
                } finally {
                    if (s.ended) s.source = null
                }
            }
        }
    }

    /** Stroke end: recompute the whole stroke at full detail and replace the live result. */
    private suspend fun finalQuickPass(s: LiveQuickStroke) {
        val live = s.source
        val target = finalQuickDim(s.proj)
        if (s.all.isEmpty()) return
        // The live pass already ran at the image's own detail: nothing to gain.
        if (live != null && target <= max(live.width, live.height) * 1.15f) return
        selectionMutex.withLock {
            if (_uiState.value.selectionMask !== s.working || s.base.isRecycled) return@withLock
            val result = try {
                val src = buildQuickSource(s.proj, s.layer, target)
                withContext(Dispatchers.Default) {
                    val m = s.base.copy(Bitmap.Config.ALPHA_8, true)
                    if (s.mode == SelectionMode.REPLACE) m.eraseColor(Color.TRANSPARENT)
                    com.example.engine.QuickSelectEngine.select(src, m, ArrayList(s.all), s.radius, s.segmentMode, s.options)
                    m
                }
            } catch (e: OutOfMemoryError) {
                System.gc()
                null // keep the live result
            }
            if (result != null && _uiState.value.selectionMask === s.working) publishSelection(result)
        }
    }

    /** Layer-mask painting with live feedback; one undo step per stroke. */
    fun paintMaskSegment(points: List<PointF>, docRadius: Float, isReveal: Boolean, phase: StrokePhase) {
        if (points.isEmpty()) return
        paintOnLayerMask(points, docRadius, isReveal, recordHistory = phase == StrokePhase.START)
        if (phase == StrokePhase.END) endMaskStroke()
    }

    // --- Editable rectangle / ellipse ---------------------------------------------------------

    fun applyShapeSelection(rect: android.graphics.RectF, isEllipse: Boolean) {
        if (rect.width() < 1f || rect.height() < 1f) return
        val mode = effectiveSelectionMode()
        val r = android.graphics.RectF(rect)
        viewModelScope.launch {
            selectionMutex.withLock {
                endTransientSelectionSessions()
                val base = _uiState.value.selectionMask ?: return@withLock
                rememberSelectionState()
                val working = withContext(Dispatchers.Default) {
                    base.copy(Bitmap.Config.ALPHA_8, true).also { fillShape(it, r, isEllipse, mode) }
                }
                shapeBaseMask = base
                shapeMode = mode
                publishSelection(working)
                _uiState.value = _uiState.value.copy(editableShape = EditableShape(r.left, r.top, r.right, r.bottom, isEllipse))
            }
        }
    }

    /** Re-applies the last shape with new bounds (handles dragged) without adding another undo step. */
    fun editShapeSelection(rect: android.graphics.RectF) {
        if (rect.width() < 1f || rect.height() < 1f) return
        val r = android.graphics.RectF(rect)
        viewModelScope.launch {
            selectionMutex.withLock {
                val base = shapeBaseMask ?: return@withLock
                val shape = _uiState.value.editableShape ?: return@withLock
                val working = withContext(Dispatchers.Default) {
                    base.copy(Bitmap.Config.ALPHA_8, true).also { fillShape(it, r, shape.isEllipse, shapeMode) }
                }
                publishSelection(working, keepEditableShape = true)
                _uiState.value = _uiState.value.copy(editableShape = shape.copy(left = r.left, top = r.top, right = r.right, bottom = r.bottom))
            }
        }
    }

    fun finishShapeEditing() {
        shapeBaseMask = null
        _uiState.value = _uiState.value.copy(editableShape = null)
    }

    private fun fillShape(mask: Bitmap, r: android.graphics.RectF, isEllipse: Boolean, mode: SelectionMode) {
        val a = PointF(r.left, r.top)
        val b = PointF(r.right, r.bottom)
        if (isEllipse) SelectionEngine.fillEllipseSelection(mask, a, b, mode) else SelectionEngine.fillRectangleSelection(mask, a, b, mode)
    }

    // --- Move the selection outline ----------------------------------------------------------

    fun moveSelection(dx: Float, dy: Float) {
        if (_uiState.value.selectionMask == null || (dx == 0f && dy == 0f)) return
        replaceSelectionAsync { current ->
            val m = current ?: SelectionEngine.createBlankMask(1, 1)
            SelectionEngine.translateMask(m, dx, dy)
        }
    }

    // --- Live modifier sliders (feather / grow / shrink / smooth) -----------------------------

    fun previewSelectionModifier(op: SelectionModifierOp, amount: Float) {
        _uiState.value = _uiState.value.copy(selectionModifierOp = op, selectionModifierAmount = amount)
        // Choosing an operation (amount 0) must not create an empty undo step.
        if (modifierBaseMask == null && amount < 0.5f) {
            modifierJob?.cancel()
            return
        }
        modifierJob?.cancel()
        modifierJob = viewModelScope.launch {
            delay(50) // coalesce slider ticks
            selectionMutex.withLock {
                if (modifierBaseMask == null) {
                    endTransientSelectionSessions(current = currentCoroutineContext()[Job])
                    val base = _uiState.value.selectionMask ?: return@withLock
                    rememberSelectionState()
                    modifierBaseMask = base
                }
                val base = modifierBaseMask ?: return@withLock
                val working = withContext(Dispatchers.Default) {
                    base.copy(Bitmap.Config.ALPHA_8, true).also { m ->
                        val px = amount.coerceAtLeast(0f)
                        if (px >= 0.5f) when (op) {
                            SelectionModifierOp.FEATHER -> SelectionEngine.featherMask(m, px)
                            SelectionModifierOp.GROW -> SelectionEngine.growMask(m, px.toInt().coerceAtLeast(1))
                            SelectionModifierOp.SHRINK -> SelectionEngine.shrinkMask(m, px.toInt().coerceAtLeast(1))
                            SelectionModifierOp.SMOOTH -> SelectionEngine.smoothMask(m, px.toInt().coerceIn(1, 12))
                        }
                    }
                }
                if (modifierBaseMask === base) publishSelection(working)
            }
        }
    }

    /**
     * Live refine: every slider change recomputes the selection from the state at the start of the
     * session with all four settings combined. [applySelectionRefine] keeps the result (one undo
     * step for the whole session), [resetSelectionRefine] restores the original selection.
     */
    fun previewSelectionRefine(params: SelectionRefineParams) {
        _uiState.value = _uiState.value.copy(selectionRefine = params)
        if (modifierBaseMask == null && params.isIdentity()) {
            modifierJob?.cancel()
            return
        }
        modifierJob?.cancel()
        modifierJob = viewModelScope.launch {
            delay(60) // coalesce slider ticks
            selectionMutex.withLock {
                if (modifierBaseMask == null) {
                    endTransientSelectionSessions(current = currentCoroutineContext()[Job])
                    _uiState.value = _uiState.value.copy(selectionRefine = params)
                    val base = _uiState.value.selectionMask ?: return@withLock
                    rememberSelectionState()
                    modifierBaseMask = base
                }
                val base = modifierBaseMask ?: return@withLock
                val p = _uiState.value.selectionRefine
                val working = try {
                    withContext(Dispatchers.Default) {
                        base.copy(Bitmap.Config.ALPHA_8, true).also { m ->
                            SelectionEngine.applyRefine(m, p.shift, p.smooth, p.feather, p.contrast)
                        }
                    }
                } catch (e: OutOfMemoryError) {
                    System.gc()
                    _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_processing_failed_memory))
                    return@withLock
                }
                if (modifierBaseMask === base) publishSelection(working)
            }
        }
    }

    /** Keeps the refined selection and ends the session. */
    fun applySelectionRefine() {
        val pending = modifierJob
        viewModelScope.launch {
            pending?.join()
            modifierBaseMask = null
            _uiState.value = _uiState.value.copy(selectionRefine = SelectionRefineParams())
        }
    }

    /** Restores the selection from before the refine session. */
    fun resetSelectionRefine() {
        modifierJob?.cancel()
        viewModelScope.launch {
            selectionMutex.withLock {
                val base = modifierBaseMask
                modifierBaseMask = null
                _uiState.value = _uiState.value.copy(selectionRefine = SelectionRefineParams())
                if (base != null && !base.isRecycled) publishSelection(base)
            }
        }
    }

    /** Selects a band of [widthPx] around the current selection edge. */
    fun borderSelection(widthPx: Float) {
        if (_uiState.value.selectionMask == null) return
        mutateSelectionAsync(R.string.status_refining_selection, R.string.status_refine_failed) { mask ->
            SelectionEngine.borderMask(mask, widthPx)
        }
    }

    /** Removes small specks and fills small holes in the selection. */
    fun cleanupSelection() {
        val proj = _uiState.value.currentProject ?: return
        if (_uiState.value.selectionMask == null) return
        val minArea = ((proj.width.toLong() * proj.height) / 2000L).toInt().coerceIn(16, 20000)
        mutateSelectionAsync(R.string.status_refining_selection, R.string.status_refine_failed) { mask ->
            SelectionEngine.cleanupMask(mask, minArea)
        }
    }

    /** Slider released: the previewed result becomes the selection (one undo step for the drag). */
    fun endSelectionModifier() {
        val pending = modifierJob
        viewModelScope.launch {
            pending?.join()
            modifierBaseMask = null
            // The slider returns to 0: the next drag modifies the new selection from scratch.
            _uiState.value = _uiState.value.copy(selectionModifierAmount = 0f)
        }
    }

    // --- Magic wand with drag-to-adjust tolerance --------------------------------------------

    /** Resolves the layer under a document point the same way a wand tap does. */
    private fun wandTargetLayer(proj: Project, touchX: Int, touchY: Int): Layer? {
        val idx = _uiState.value.selectedLayerIndex
        var targetLayer = if (idx in proj.layers.indices) proj.layers[idx] else null
        if (targetLayer == null || targetLayer.bitmap == null ||
            SelectionEngine.mapCanvasToLayerLocal(touchX.toFloat(), touchY.toFloat(), targetLayer, proj.width, proj.height) == null
        ) {
            val hit = proj.layers.findLast { l ->
                l.isVisible && l.bitmap != null &&
                    SelectionEngine.mapCanvasToLayerLocal(touchX.toFloat(), touchY.toFloat(), l, proj.width, proj.height) != null
            }
            if (hit != null) {
                targetLayer = hit
                _uiState.value = _uiState.value.copy(selectedLayerIndex = proj.layers.indexOf(hit))
            } else {
                targetLayer = null
            }
        }
        return targetLayer?.takeIf { it.bitmap != null }
    }

    /**
     * Press on the image and drag sideways to change the tolerance: the selection is recomputed
     * live from the press point. [phase] END stores the tolerance and ends the session.
     */
    fun magicWandDrag(touchX: Int, touchY: Int, tolerance: Int, phase: StrokePhase) {
        val proj = _uiState.value.currentProject ?: return
        val tol = tolerance.coerceIn(0, 100)
        if (phase == StrokePhase.START) {
            val options = _uiState.value.wandOptions
            val layer = if (options.sampleAllLayers) null else wandTargetLayer(proj, touchX, touchY)
            if (layer == null && !options.sampleAllLayers) {
                _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_wand_no_layer))
                return
            }
            val mode = effectiveSelectionMode()
            val contiguous = _uiState.value.isMagicWandContiguous
            wandJob?.cancel()
            wandJob = viewModelScope.launch {
                try {
                    selectionMutex.withLock {
                        endTransientSelectionSessions(current = currentCoroutineContext()[Job])
                        val base = _uiState.value.selectionMask ?: return@withLock
                        val source = withContext(Dispatchers.Default) { wandSourceFor(proj, layer) }
                        rememberSelectionState()
                        wandSession = WandSession(base, source, touchX, touchY, mode, contiguous, options)
                    }
                } catch (e: OutOfMemoryError) {
                    System.gc()
                    _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_processing_failed_memory))
                    return@launch
                }
                runWandSession(tol)
            }
            return
        }
        _uiState.value = _uiState.value.copy(magicWandTolerance = tol)
        val previous = wandJob
        wandJob = viewModelScope.launch {
            // Cancel a stale preview, but never the START job before it has created the session.
            if (phase == StrokePhase.MOVE && wandSession != null) previous?.cancel() else previous?.join()
            if (phase == StrokePhase.MOVE) delay(40)
            runWandSession(tol)
            if (phase == StrokePhase.END) wandSession = null
        }
    }

    private suspend fun runWandSession(tolerance: Int) {
        selectionMutex.withLock {
            val s = wandSession ?: return@withLock
            // The fill checks for cancellation, so a newer drag position stops a stale one at once
            // instead of queueing behind it.
            val job = currentCoroutineContext()[Job]
            val working = withContext(Dispatchers.Default) {
                s.base.copy(Bitmap.Config.ALPHA_8, true).also { m ->
                    try {
                        com.example.engine.MagicWandEngine.select(
                            source = s.source,
                            docMask = m,
                            docX = s.x + 0.5f,
                            docY = s.y + 0.5f,
                            tolerance = tolerance,
                            contiguous = s.contiguous,
                            mode = s.mode,
                            options = s.options,
                            isCancelled = { job?.isActive == false }
                        )
                    } catch (e: Throwable) {
                        if (!m.isRecycled) m.recycle()
                        throw e
                    }
                }
            }
            if (wandSession === s) publishSelection(working) else if (!working.isRecycled) working.recycle()
        }
    }

    // --- Layer Mask Operations ---

    fun toggleMaskPaintColor() {
        _uiState.value = _uiState.value.copy(isMaskEditingBlack = !_uiState.value.isMaskEditingBlack)
    }

    fun addLayerMask(fromSelection: Boolean = true) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        val layer = proj.layers.getOrNull(idx) ?: return

        saveHistoryState()
        val curSelection = _uiState.value.selectionMask
        if (fromSelection && curSelection != null && SelectionEngine.hasSelection(curSelection)) {
            layer.maskBitmap = curSelection.copy(Bitmap.Config.ALPHA_8, true)
            _uiState.value = _uiState.value.copy(
                selectionMask = SelectionEngine.createBlankMask(proj.width, proj.height),
                selectionRevision = _uiState.value.selectionRevision + 1
            )
        } else {
            // Layer masks live in document space (same size as the canvas), like selections.
            val mask = Bitmap.createBitmap(proj.width, proj.height, Bitmap.Config.ALPHA_8)
            mask.eraseColor(Color.WHITE)
            layer.maskBitmap = mask
        }
        layer.isMaskEnabled = true
        layer.isEditingMask = true
        layer.isMaskLinked = true
        layer.maskAnchor = layer.currentMaskAnchor()

        commitProjectMutation(statusMessage = tr(R.string.status_layer_mask_added))
    }

    fun invertLayerMask(layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        if (layer.maskBitmap?.isRecycled != false) return
        if (layer.isLocked) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_layer_locked))
            return
        }
        saveHistoryState()
        bakeMaskTransform(layer, proj.width, proj.height)
        // Edit a copy: the old bitmap may still be drawn by a background render or held by history.
        val mask = layer.maskBitmap!!.copy(Bitmap.Config.ALPHA_8, true)
        SelectionEngine.invertMask(mask)
        layer.maskBitmap = mask
        commitProjectMutation(statusMessage = tr(R.string.status_layer_mask_inverted))
    }

    fun toggleLayerMaskEnabled(layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        if (layer.maskBitmap == null) return
        if (layer.isLocked) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_layer_locked))
            return
        }
        saveHistoryState()
        layer.isMaskEnabled = !layer.isMaskEnabled
        commitProjectMutation()
    }

    /** Chain link: a linked mask moves with the layer; an unlinked one stays put on the canvas. */
    fun toggleMaskLink(layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        if (layer.maskBitmap == null) return
        saveHistoryState()
        // Bake the mask where it shows now, then (un)link from the current transform, so toggling
        // never makes the mask jump.
        bakeMaskTransform(layer, proj.width, proj.height)
        layer.isMaskLinked = !layer.isMaskLinked
        layer.maskAnchor = layer.currentMaskAnchor()
        commitProjectMutation(
            statusMessage = tr(if (layer.isMaskLinked) R.string.status_mask_linked else R.string.status_mask_unlinked)
        )
    }

    /** Clipping mask: the layer shows only where the layer below it is opaque. */
    fun toggleClippingMask(layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        if (!layer.isClipped && layerIndex == 0) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_clip_needs_layer_below))
            return
        }
        saveHistoryState()
        layer.isClipped = !layer.isClipped
        commitProjectMutation(
            statusMessage = tr(if (layer.isClipped) R.string.status_clip_on else R.string.status_clip_off)
        )
    }

    /**
     * Writes where a linked mask currently shows back into its pixels and re-anchors it to the
     * current layer transform. Called before editing mask pixels or remapping the document.
     * The old bitmap is not recycled (history snapshots and background renders may hold it).
     */
    private fun bakeMaskTransform(layer: Layer, docW: Int, docH: Int) {
        val mask = layer.maskBitmap ?: return
        if (mask.isRecycled) return
        val moved = LayerRenderer.linkedMaskMatrix(layer, docW, docH)
        if (moved != null) {
            val src = documentSizedMask(mask, docW, docH)
            val out = Bitmap.createBitmap(docW, docH, Bitmap.Config.ALPHA_8)
            out.eraseColor(Color.WHITE)
            Canvas(out).drawBitmap(src, moved, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
            })
            if (src !== mask && !src.isRecycled) src.recycle()
            layer.maskBitmap = out
        }
        layer.maskAnchor = layer.currentMaskAnchor()
    }

    fun setEditingMask(isEditing: Boolean, layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        layer.isEditingMask = isEditing
        _uiState.value = _uiState.value.copy(renderRevision = _uiState.value.renderRevision + 1)
    }

    fun deleteLayerMask(layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        if (layer.maskBitmap == null) return

        saveHistoryState()
        // Not recycled: a background preview render may still be reading it (the canvas shares
        // live mask references instead of copying them every frame). GC reclaims it.
        layer.maskBitmap = null
        layer.isEditingMask = false
        commitProjectMutation(statusMessage = tr(R.string.status_layer_mask_deleted))
    }

    fun applyLayerMask(layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        val mask = layer.maskBitmap ?: return
        val bmp = layer.bitmap ?: return
        if (mask.isRecycled || bmp.isRecycled || layer.type != LayerType.IMAGE) return
        if (layer.isLocked) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_layer_locked))
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_processing))
            var src: Bitmap? = null
            var effectiveDocMask: Bitmap? = null
            var localMask: Bitmap? = null
            var result: Bitmap? = null
            var historySaved = false
            try {
                // Bake from the full-quality source, not the preview proxy.
                src = loadAuthoritativeLayerBitmap(layer) ?: throw IllegalStateException("Image unavailable")
                val workLayer = workLayerFor(layer, src!!)
                result = withContext(Dispatchers.Default) {
                    // Exactly what the renderer shows (feather + density), in document space…
                    effectiveDocMask = LayerRenderer.renderEffectiveMask(layer, proj.width, proj.height)
                        ?: throw IllegalStateException("Mask unavailable")
                    // …mapped onto the layer's own pixels through the layer transform.
                    localMask = SelectionEngine.mapDocumentMaskToLayerLocal(effectiveDocMask!!, workLayer, proj.width, proj.height)
                    val out = src!!.copy(Bitmap.Config.ARGB_8888, true)
                    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    }
                    Canvas(out).drawBitmap(localMask!!, 0f, 0f, maskPaint)
                    out
                }
                saveHistoryState()
                historySaved = true
                if (!commitDestructiveBitmap(proj, layer, result!!, layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight(), "apply_mask")) {
                    throw java.io.IOException("Failed to persist masked layer")
                }
                // The history snapshot still references the old mask; the live layer simply drops it.
                layer.maskBitmap = null
                layer.isEditingMask = false
                layer.maskDensity = 1f
                layer.maskFeather = 0f
                commitProjectMutation(
                    statusMessage = tr(R.string.status_layer_mask_applied),
                    transformState = { it.copy(isProcessing = false) }
                )
            } catch (e: OutOfMemoryError) {
                // Failed after its undo step was recorded: drop that step (and any partial change).
                if (historySaved) rollbackToLastHistoryState()
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_processing_failed_memory))
            } catch (e: Exception) {
                // Failed after its undo step was recorded: drop that step (and any partial change).
                if (historySaved) rollbackToLastHistoryState()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_layer_mask_apply_failed))
            } finally {
                val p = layer.bitmap
                localMask?.let { if (!it.isRecycled) it.recycle() }
                effectiveDocMask?.let { if (!it.isRecycled) it.recycle() }
                result?.let { if (it !== p && !it.isRecycled) it.recycle() }
                src?.let { if (it !== p && !it.isRecycled) it.recycle() }
            }
        }
    }

    /** Returns [mask] if it already matches the document size, otherwise a scaled ALPHA_8 copy (legacy masks). */
    private fun documentSizedMask(mask: Bitmap, docW: Int, docH: Int): Bitmap {
        if (mask.width == docW && mask.height == docH) return mask
        val out = Bitmap.createBitmap(docW, docH, Bitmap.Config.ALPHA_8)
        val m = Matrix().apply { postScale(docW.toFloat() / mask.width.coerceAtLeast(1), docH.toFloat() / mask.height.coerceAtLeast(1)) }
        Canvas(out).drawBitmap(mask, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Re-projects a document-space layer mask after the canvas geometry changes (crop / resize).
     * [matrix] maps old document coordinates to new document coordinates.
     */
    private fun remapDocumentMask(mask: Bitmap, oldW: Int, oldH: Int, newW: Int, newH: Int, matrix: Matrix): Bitmap {
        val src = documentSizedMask(mask, oldW, oldH)
        val out = Bitmap.createBitmap(newW, newH, Bitmap.Config.ALPHA_8)
        Canvas(out).drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        if (src !== mask && !src.isRecycled) src.recycle()
        return out
    }

    /** [recordHistory] = false for the follow-up segments of a live stroke (one undo step per stroke). */
    // Per-stroke mask painting state: the mask as it was when the stroke started, the strongest
    // brush coverage reached so far at each pixel, and the selection limiting the stroke.
    private class MaskStroke(
        val layerId: String,
        val width: Int,
        val height: Int,
        val base: ByteArray,
        val baseRowBytes: Int,
        val coverage: ByteArray,
        val selection: ByteArray?,
        val selectionRowBytes: Int,
        var last: PointF?
    )
    private var maskStroke: MaskStroke? = null

    /**
     * Paints on the selected layer's mask. Brush edge softness and opacity come from the UI state;
     * overlapping dabs within one stroke never exceed the stroke opacity (like Photoshop), so a
     * 40% brush paints an even 40% grey. With an active selection painting stays inside it.
     * [recordHistory] = start of a new stroke.
     */
    fun paintOnLayerMask(points: List<PointF>, brushRadius: Float, isReveal: Boolean, recordHistory: Boolean = true) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        val layer = proj.layers.getOrNull(idx) ?: return
        if (points.isEmpty()) return
        if (layer.isLocked) {
            if (recordHistory) _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_layer_locked))
            return
        }
        val w = proj.width
        val h = proj.height

        if (recordHistory || maskStroke?.layerId != layer.id) {
            saveHistoryState()
            if (layer.maskBitmap == null || layer.maskBitmap!!.isRecycled) {
                layer.maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).apply { eraseColor(Color.WHITE) }
                layer.isMaskEnabled = true
                layer.isMaskLinked = true
                layer.maskAnchor = layer.currentMaskAnchor()
            }
            // Strokes are in document coordinates: legacy masks are normalised and a moved
            // linked mask is baked where it shows.
            layer.maskBitmap?.let { m -> if (m.width != w || m.height != h) layer.maskBitmap = documentSizedMask(m, w, h) }
            bakeMaskTransform(layer, w, h)
            // Paint on a private copy so a background render never reads a half-painted mask.
            layer.maskBitmap = layer.maskBitmap!!.copy(Bitmap.Config.ALPHA_8, true)
            val (base, rb) = SelectionEngine.readAlphaBytes(layer.maskBitmap!!)
            val sel = _uiState.value.selectionMask?.takeIf { !it.isRecycled && SelectionEngine.hasSelection(it) }
            var selBytes: ByteArray? = null
            var selRb = 0
            if (sel != null) {
                val docSel = documentSizedMask(sel, w, h)
                val pair = SelectionEngine.readAlphaBytes(docSel)
                selBytes = pair.first
                selRb = pair.second
                if (docSel !== sel && !docSel.isRecycled) docSel.recycle()
            }
            maskStroke = MaskStroke(layer.id, w, h, base, rb, ByteArray(w * h), selBytes, selRb, null)
        }
        val stroke = maskStroke ?: return
        val mask = layer.maskBitmap ?: return
        if (mask.width != stroke.width || mask.height != stroke.height) return

        val pts = ArrayList<PointF>(points.size + 1)
        stroke.last?.let { pts.add(it) }
        pts.addAll(points)
        stroke.last = PointF(points.last().x, points.last().y)

        val radius = brushRadius.coerceAtLeast(0.5f)
        val hardness = _uiState.value.maskBrushHardness.coerceIn(0f, 1f)
        val opacity = _uiState.value.maskBrushOpacity.coerceIn(0.05f, 1f)
        val target = if (isReveal) 255 else 0

        var minPX = Float.MAX_VALUE; var minPY = Float.MAX_VALUE
        var maxPX = -Float.MAX_VALUE; var maxPY = -Float.MAX_VALUE
        for (p in pts) {
            if (p.x < minPX) minPX = p.x
            if (p.y < minPY) minPY = p.y
            if (p.x > maxPX) maxPX = p.x
            if (p.y > maxPY) maxPY = p.y
        }
        val left = (minPX - radius).toInt().coerceIn(0, w - 1)
        val top = (minPY - radius).toInt().coerceIn(0, h - 1)
        val right = (maxPX + radius + 1).toInt().coerceIn(0, w - 1)
        val bottom = (maxPY + radius + 1).toInt().coerceIn(0, h - 1)
        if (right < left || bottom < top) return

        val inner = radius * hardness
        val falloffSpan = (radius - inner).coerceAtLeast(0.001f)
        val r2 = radius * radius
        val selArr = stroke.selection
        val selRb = stroke.selectionRowBytes
        val cov = stroke.coverage
        fun stamp(cx: Float, cy: Float) {
            val minX = (cx - radius).toInt().coerceIn(left, right)
            val maxX = (cx + radius + 1).toInt().coerceIn(left, right)
            val minY = (cy - radius).toInt().coerceIn(top, bottom)
            val maxY = (cy + radius + 1).toInt().coerceIn(top, bottom)
            for (y in minY..maxY) {
                val dy = y + 0.5f - cy
                val row = y * w
                for (x in minX..maxX) {
                    val dx = x + 0.5f - cx
                    val d2 = dx * dx + dy * dy
                    if (d2 >= r2) continue
                    val d = kotlin.math.sqrt(d2)
                    val f = if (d <= inner) 1f else {
                        val t = ((d - inner) / falloffSpan).coerceIn(0f, 1f)
                        1f - t * t * (3f - 2f * t)
                    }
                    val sel = if (selArr != null) (selArr[y * selRb + x].toInt() and 0xFF) / 255f else 1f
                    val c = (f * opacity * sel * 255f).toInt()
                    if (c > (cov[row + x].toInt() and 0xFF)) cov[row + x] = c.toByte()
                }
            }
        }
        val spacing = (radius * 0.25f).coerceAtLeast(0.5f)
        stamp(pts[0].x, pts[0].y)
        for (i in 0 until pts.size - 1) {
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val dist = kotlin.math.hypot(p2.x - p1.x, p2.y - p1.y)
            val steps = kotlin.math.max(1, (dist / spacing).toInt())
            for (k in 1..steps) {
                val t = k.toFloat() / steps
                stamp(p1.x + t * (p2.x - p1.x), p1.y + t * (p2.y - p1.y))
            }
        }

        // Write only the stroke's bounding box: new = base + (target - base) x coverage.
        val regionW = right - left + 1
        val regionH = bottom - top + 1
        val out = IntArray(regionW * regionH)
        for (y in top..bottom) {
            for (x in left..right) {
                val b = stroke.base[y * stroke.baseRowBytes + x].toInt() and 0xFF
                val c = stroke.coverage[y * w + x].toInt() and 0xFF
                val v = b + ((target - b) * c + (if (target > b) 127 else -127)) / 255
                out[(y - top) * regionW + (x - left)] = (v.coerceIn(0, 255) shl 24) or 0x00FFFFFF
            }
        }
        mask.setPixels(out, 0, regionW, left, top, regionW, regionH)
        // The canvas redraws the whole image: at most ~14 times a second while painting
        // (endMaskStroke shows the final state).
        val now = android.os.SystemClock.uptimeMillis()
        if (recordHistory || now - lastMaskBump >= 70L) {
            lastMaskBump = now
            commitProjectMutation()
        } else maskBumpPending = true
    }

    private var lastMaskBump = 0L
    private var maskBumpPending = false

    /** Ends the current mask stroke and frees its buffers. */
    fun endMaskStroke() {
        maskStroke = null
        if (maskBumpPending) { maskBumpPending = false; commitProjectMutation() }
        if (_uiState.value.isProjectDirty) scheduleAutosave()
    }

    fun setLayerMaskDensity(density: Float, layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        if (layer.maskBitmap == null || layer.isLocked) return
        // Slider drag = one undoable transaction (committed on release) + autosave.
        if (pendingTransactionSnapshot == null) beginHistoryTransaction()
        layer.maskDensity = density.coerceIn(0f, 1f)
        proj.markDirty()
        _uiState.value = _uiState.value.copy(
            currentProject = proj.copy(layers = ArrayList(proj.layers)),
            renderRevision = proj.renderRevision,
            isProjectDirty = true
        )
        scheduleAutosave()
    }

    fun setLayerMaskFeather(feather: Float, layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        if (layer.maskBitmap == null || layer.isLocked) return
        if (pendingTransactionSnapshot == null) beginHistoryTransaction()
        layer.maskFeather = feather.coerceIn(0f, 50f)
        proj.markDirty()
        _uiState.value = _uiState.value.copy(
            currentProject = proj.copy(layers = ArrayList(proj.layers)),
            renderRevision = proj.renderRevision,
            isProjectDirty = true
        )
        scheduleAutosave()
    }

    fun revealAllLayerMask(layerIndex: Int = _uiState.value.selectedLayerIndex) = fillLayerMask(layerIndex, Color.WHITE)

    fun hideAllLayerMask(layerIndex: Int = _uiState.value.selectedLayerIndex) = fillLayerMask(layerIndex, Color.TRANSPARENT)

    private fun fillLayerMask(layerIndex: Int, color: Int) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        if (layer.maskBitmap?.isRecycled != false) return
        if (layer.isLocked) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_layer_locked))
            return
        }
        saveHistoryState()
        val mask = Bitmap.createBitmap(proj.width, proj.height, Bitmap.Config.ALPHA_8)
        mask.eraseColor(color)
        layer.maskBitmap = mask
        layer.maskAnchor = layer.currentMaskAnchor()
        commitProjectMutation()
    }

    /**
     * Fills the layer mask with a gradient from revealed (white) to hidden (black). With an active
     * selection only the selected area changes, softly following the selection's edges.
     */
    fun applyMaskGradient(type: com.example.data.model.MaskGradient, layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        if (layer.isLocked) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_layer_locked))
            return
        }
        val w = proj.width
        val h = proj.height
        val selection = _uiState.value.selectionMask?.takeIf { !it.isRecycled && SelectionEngine.hasSelection(it) }
        saveHistoryState()
        if (layer.maskBitmap == null) {
            layer.maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).apply { eraseColor(Color.WHITE) }
            layer.isMaskEnabled = true
            layer.isMaskLinked = true
        }
        bakeMaskTransform(layer, w, h)
        val base = documentSizedMask(layer.maskBitmap!!, w, h)
        val gradient = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val shader: android.graphics.Shader = when (type) {
            com.example.data.model.MaskGradient.TOP_TO_BOTTOM ->
                android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), Color.WHITE, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
            com.example.data.model.MaskGradient.BOTTOM_TO_TOP ->
                android.graphics.LinearGradient(0f, h.toFloat(), 0f, 0f, Color.WHITE, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
            com.example.data.model.MaskGradient.LEFT_TO_RIGHT ->
                android.graphics.LinearGradient(0f, 0f, w.toFloat(), 0f, Color.WHITE, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
            com.example.data.model.MaskGradient.RIGHT_TO_LEFT ->
                android.graphics.LinearGradient(w.toFloat(), 0f, 0f, 0f, Color.WHITE, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
            com.example.data.model.MaskGradient.RADIAL ->
                android.graphics.RadialGradient(w / 2f, h / 2f, kotlin.math.hypot(w / 2f, h / 2f).coerceAtLeast(1f),
                    Color.WHITE, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
        }
        Canvas(gradient).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint(Paint.DITHER_FLAG).apply {
            this.shader = shader
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        })
        val result = if (selection == null) {
            gradient
        } else {
            // result = base x (1 - selection) + gradient x selection
            val out = base.copy(Bitmap.Config.ALPHA_8, true)
            val (outBytes, rb) = SelectionEngine.readAlphaBytes(out)
            val (gBytes, grb) = SelectionEngine.readAlphaBytes(gradient)
            val sel = documentSizedMask(selection, w, h)
            val (sBytes, srb) = SelectionEngine.readAlphaBytes(sel)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val o = outBytes[y * rb + x].toInt() and 0xFF
                    val g = gBytes[y * grb + x].toInt() and 0xFF
                    val a = sBytes[y * srb + x].toInt() and 0xFF
                    outBytes[y * rb + x] = ((o * (255 - a) + g * a + 127) / 255).toByte()
                }
            }
            SelectionEngine.writeAlphaBytes(out, outBytes)
            if (sel !== selection && !sel.isRecycled) sel.recycle()
            gradient.recycle()
            out
        }
        if (base !== layer.maskBitmap && !base.isRecycled) base.recycle()
        layer.maskBitmap = result
        layer.maskAnchor = layer.currentMaskAnchor()
        layer.isEditingMask = true
        commitProjectMutation(statusMessage = tr(R.string.status_mask_gradient_applied))
    }

    fun setMaskBrushHardness(value: Float) {
        _uiState.value = _uiState.value.copy(maskBrushHardness = value.coerceIn(0f, 1f))
    }

    fun setMaskBrushOpacity(value: Float) {
        _uiState.value = _uiState.value.copy(maskBrushOpacity = value.coerceIn(0.05f, 1f))
    }

    fun toggleMaskRubyOverlay() {
        _uiState.value = _uiState.value.copy(
            showMaskRubyOverlay = !_uiState.value.showMaskRubyOverlay,
            renderRevision = _uiState.value.renderRevision + 1
        )
    }

    fun setMaskBrushSize(sizeDp: Float) {
        _uiState.value = _uiState.value.copy(maskBrushSizeDp = sizeDp.coerceIn(2f, 120f))
    }

    private var proEffectJob: Job? = null

    /** Cancels a Pro effect that is being applied (the layer stays unchanged). */
    fun cancelProEffect() {
        proEffectJob?.cancel()
    }

    // --- Retouch brushes (Blur, Sharpen, Smudge, Dodge, Burn, Clone Stamp, Patch) ------------

    /** Full-resolution pixels of the layer being retouched, kept between strokes. */
    private class RetouchSession(val layerId: String, var sourcePath: String?, val canvas: com.example.engine.RetouchCanvas)

    private sealed class RetouchEvent {
        class Start(
            val layer: Layer, val docW: Int, val docH: Int, val tool: com.example.engine.RetouchTool,
            val brush: com.example.engine.RetouchBrush, val cloneOffsetDoc: PointF?,
            val points: List<PointF>, val docRadius: Float, val selection: Bitmap? = null
        ) : RetouchEvent()
        class Move(val points: List<PointF>, val docRadius: Float) : RetouchEvent()
        class Heal(val layer: Layer, val docW: Int, val docH: Int, val points: List<PointF>, val docRadius: Float, val selection: Bitmap? = null) : RetouchEvent()
        class Fill(
            val layer: Layer, val docW: Int, val docH: Int, val pointDoc: PointF, val endDoc: PointF?,
            val brush: com.example.engine.RetouchBrush, val selection: Bitmap?
        ) : RetouchEvent()
        class End(val points: List<PointF>, val docRadius: Float) : RetouchEvent()
        class Patch(
            val layer: Layer, val docW: Int, val docH: Int, val selection: Bitmap,
            val dxDoc: Float, val dyDoc: Float
        ) : RetouchEvent()
    }

    private var retouchSession: RetouchSession? = null
    private var retouchExecutor: java.util.concurrent.ExecutorService? = null
    private var retouchDispatcherOrNull: kotlinx.coroutines.CoroutineDispatcher? = null
    private val retouchDispatcher: kotlinx.coroutines.CoroutineDispatcher
        get() = retouchDispatcherOrNull ?: java.util.concurrent.Executors.newSingleThreadExecutor().let { ex ->
            retouchExecutor = ex
            ex.asCoroutineDispatcher().also { retouchDispatcherOrNull = it }
        }
    private val retouchEvents = kotlinx.coroutines.channels.Channel<RetouchEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var retouchConsumer: Job? = null
    private val retouchWrites = java.util.Collections.synchronizedList(mutableListOf<Job>())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val retouchWriteDispatcher = Dispatchers.IO.limitedParallelism(1)
    /** Retouch strokes queued or being painted: autosave waits until they are committed. */
    private var retouchPending = 0
    private val retouchStrokeActive: Boolean get() = retouchPending > 0
    /** The stroke being processed has recorded its undo step (roll it back if it fails). */
    private var retouchStepOpen = false
    private class GradientDrag(val layer: Layer, val start: PointF, val selection: Bitmap?)
    private class HealStroke(val layer: Layer, val points: MutableList<PointF>, val docRadius: Float, val selection: Bitmap?)
    /** Spot Healing stroke being painted (document coordinates). */
    private var healStroke: HealStroke? = null
    /** Fill in gradient mode: where the drag started. */
    private var gradientStart: GradientDrag? = null
    /** Stroke points not painted yet (merged moves), see [retouchSegment]. */
    private val pendingMove = ArrayList<PointF>()
    private var pendingMoveRadius = 0f
    private var moveQueued = false
    private fun takePendingMove(): List<PointF> = synchronized(pendingMove) {
        moveQueued = false
        ArrayList(pendingMove).also { pendingMove.clear() }
    }
    /** Last time the canvas was asked to redraw during a stroke (redraws are throttled). */
    private var lastRetouchBump = 0L
    /** Patch's lasso points while it is being drawn (document coordinates). */
    private var patchLasso: MutableList<PointF>? = null
    private var retouchStrokeRejected = false
    private var retouchStrokeFailed = false
    private var patchStartDoc: PointF? = null
    /** Clone Stamp (aligned): source minus the first stroke point, kept until a new source is set. */
    private var cloneOffsetDoc: PointF? = null
    // State of the stroke being processed (worker side).
    private var strokeLayer: Layer? = null
    private var strokePreview: Bitmap? = null
    private var strokeToLocal: Matrix? = null
    private var strokeLocalScale = 1f
    private var strokeOffsetDoc: PointF? = null

    fun setRetouchTool(tool: com.example.engine.RetouchTool) {
        val st = _uiState.value
        val hasSelection = st.selectionMask?.let { !it.isRecycled && SelectionEngine.hasSelection(it) } == true
        _uiState.value = st.copy(
            retouchTool = tool,
            isCloneSourcePicking = tool == com.example.engine.RetouchTool.CLONE && st.cloneSourceDoc == null,
            // Patch starts by drawing the area to fix unless something is already selected.
            patchSelecting = if (tool == com.example.engine.RetouchTool.PATCH && tool != st.retouchTool) !hasSelection else st.patchSelecting,
            isFillColorPicking = false
        )
    }

    fun setRetouchSize(sizeDp: Float) { _uiState.value = _uiState.value.copy(retouchSizeDp = sizeDp.coerceIn(1f, 400f)) }

    fun setRetouchBrush(brush: com.example.engine.RetouchBrush) { _uiState.value = _uiState.value.copy(retouchBrush = brush) }

    fun setCloneSourcePicking(picking: Boolean) { _uiState.value = _uiState.value.copy(isCloneSourcePicking = picking) }

    fun setPatchSelecting(selecting: Boolean) { _uiState.value = _uiState.value.copy(patchSelecting = selecting) }

    fun setFillColorPicking(picking: Boolean) { _uiState.value = _uiState.value.copy(isFillColorPicking = picking) }

    /** Eyedropper for Fill: the layer's colour at a document point (from its preview pixels). */
    private fun pickFillColor(layer: Layer, docX: Float, docY: Float, docW: Int, docH: Int) {
        val bmp = layer.bitmap?.takeIf { !it.isRecycled } ?: return
        val inv = Matrix()
        if (!SelectionEngine.getLayerMatrixForSize(layer, bmp.width.toFloat(), bmp.height.toFloat(), docW, docH).invert(inv)) return
        val pt = floatArrayOf(docX, docY).also { inv.mapPoints(it) }
        val x = pt[0].toInt(); val y = pt[1].toInt()
        val st = _uiState.value
        if (x !in 0 until bmp.width || y !in 0 until bmp.height) {
            _uiState.value = st.copy(statusMessage = tr(R.string.fill_pick_outside))
            return
        }
        val c = bmp.getPixel(x, y) or 0xFF000000.toInt()
        _uiState.value = st.copy(retouchBrush = st.retouchBrush.copy(fillColor = c), isFillColorPicking = false, statusMessage = tr(R.string.fill_color_picked))
    }

    /** Patch's own lasso: replaces the selection with the drawn area, then switches to dragging. */
    private fun applyPatchLasso(points: List<PointF>) {
        if (_uiState.value.selectionMask == null || points.size < 3) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.retouch_patch_lasso_hint))
            return
        }
        mutateSelectionAsync { mask -> SelectionEngine.fillLassoPath(mask, points, com.example.data.model.SelectionMode.REPLACE) }
        _uiState.value = _uiState.value.copy(patchSelecting = false, statusMessage = tr(R.string.retouch_patch_ready))
    }

    /** Sets the Clone Stamp source (a tap in "set source" mode). */
    fun setCloneSource(docX: Float, docY: Float) {
        cloneOffsetDoc = null
        _uiState.value = _uiState.value.copy(
            cloneSourceDoc = PointF(docX, docY), isCloneSourcePicking = false, retouchMarkerDoc = PointF(docX, docY),
            statusMessage = tr(R.string.retouch_source_set)
        )
    }

    private suspend fun awaitRetouchWrites() {
        retouchWrites.toList().forEach { it.join() }
    }

    /** Stroke segments of the retouch brushes, in document coordinates (from the canvas). */
    fun retouchSegment(points: List<PointF>, docRadius: Float, phase: StrokePhase) {
        val st = _uiState.value
        val proj = st.currentProject ?: return
        val tool = st.retouchTool
        if (phase == StrokePhase.START) {
            retouchStrokeRejected = false
            if (tool == com.example.engine.RetouchTool.CLONE && st.isCloneSourcePicking) {
                // "Set source" mode: the touch picks the source instead of painting.
                retouchStrokeRejected = true
                points.firstOrNull()?.let { setCloneSource(it.x, it.y) }
                return
            }
            if (tool == com.example.engine.RetouchTool.PATCH && st.patchSelecting) {
                patchLasso = points.toMutableList()
                return
            }
            val layer = proj.layers.getOrNull(st.selectedLayerIndex)
            val hasSelection = st.selectionMask?.let { !it.isRecycled && SelectionEngine.hasSelection(it) } == true
            val reason = when {
                layer == null || layer.type != LayerType.IMAGE || layer.bitmap == null -> R.string.retouch_needs_image_layer
                layer.isLocked -> R.string.status_layer_locked
                !layer.isVisible -> R.string.retouch_layer_hidden
                tool == com.example.engine.RetouchTool.CLONE && st.cloneSourceDoc == null -> R.string.retouch_set_source_first
                tool == com.example.engine.RetouchTool.PATCH && !hasSelection -> R.string.retouch_patch_needs_selection
                else -> null
            }
            if (reason != null) {
                retouchStrokeRejected = true
                _uiState.value = st.copy(statusMessage = tr(reason))
                return
            }
            if (tool == com.example.engine.RetouchTool.PATCH) { patchStartDoc = points.firstOrNull(); return }
            if (tool == com.example.engine.RetouchTool.HEAL) {
                // Spot Healing: the stroke is collected and healed when the finger lifts.
                healStroke = HealStroke(layer!!, points.toMutableList(), docRadius, st.selectionMask?.takeIf { hasSelection })
                return
            }
            val first = points.firstOrNull() ?: return
            val paints = tool == com.example.engine.RetouchTool.FILL || tool == com.example.engine.RetouchTool.BRUSH
            if (paints && st.isFillColorPicking) {
                // Eyedropper: the touch picks the colour instead of painting.
                retouchStrokeRejected = true
                pickFillColor(layer!!, first.x, first.y, proj.width, proj.height)
                return
            }
            if (tool == com.example.engine.RetouchTool.FILL) {
                val sel = st.selectionMask?.takeIf { hasSelection }
                if (st.retouchBrush.fillMode == com.example.engine.FillMode.GRADIENT) {
                    // A gradient is dragged: from where the finger goes down to where it lifts.
                    gradientStart = GradientDrag(layer!!, PointF(first.x, first.y), sel)
                    return
                }
                // A tap: fills once; the rest of the touch is ignored.
                retouchStrokeRejected = true
                queueRetouch(RetouchEvent.Fill(layer!!, proj.width, proj.height, PointF(first.x, first.y), null, st.retouchBrush, sel))
                return
            }
            if (tool == com.example.engine.RetouchTool.CLONE) {
                val src = st.cloneSourceDoc!!
                if (cloneOffsetDoc == null) cloneOffsetDoc = PointF(src.x - first.x, src.y - first.y)
            }
            queueRetouch(RetouchEvent.Start(
                layer!!, proj.width, proj.height, tool, st.retouchBrush,
                if (tool == com.example.engine.RetouchTool.CLONE) cloneOffsetDoc else null, points.toList(), docRadius,
                // Every brush stays inside the selection, as in Photoshop.
                st.selectionMask?.takeIf { hasSelection }
            ))
            return
        }
        if (retouchStrokeRejected) return
        val heal = healStroke
        if (tool == com.example.engine.RetouchTool.HEAL && heal != null) {
            heal.points.addAll(points)
            if (phase == StrokePhase.END) {
                healStroke = null
                queueRetouch(RetouchEvent.Heal(heal.layer, proj.width, proj.height, heal.points.toList(), heal.docRadius, heal.selection))
            }
            return
        }
        val grad = gradientStart
        if (tool == com.example.engine.RetouchTool.FILL && grad != null) {
            if (phase != StrokePhase.END) return
            gradientStart = null
            val end = points.lastOrNull() ?: return
            if (kotlin.math.hypot(end.x - grad.start.x, end.y - grad.start.y) < 3f) {
                _uiState.value = st.copy(statusMessage = tr(R.string.fill_gradient_hint))
                return
            }
            queueRetouch(RetouchEvent.Fill(grad.layer, proj.width, proj.height, grad.start, PointF(end.x, end.y), st.retouchBrush, grad.selection))
            return
        }
        val lasso = patchLasso
        if (tool == com.example.engine.RetouchTool.PATCH && lasso != null) {
            lasso.addAll(points)
            if (phase == StrokePhase.END) { patchLasso = null; applyPatchLasso(lasso) }
            return
        }
        if (tool == com.example.engine.RetouchTool.PATCH) {
            if (phase != StrokePhase.END) return
            val start = patchStartDoc ?: return
            patchStartDoc = null
            val end = points.lastOrNull() ?: return
            val layer = proj.layers.getOrNull(st.selectedLayerIndex) ?: return
            val sel = st.selectionMask?.takeIf { !it.isRecycled } ?: return
            if (kotlin.math.hypot(end.x - start.x, end.y - start.y) < 2f) {
                _uiState.value = st.copy(statusMessage = tr(R.string.retouch_patch_hint))
                return
            }
            queueRetouch(RetouchEvent.Patch(layer, proj.width, proj.height, sel, end.x - start.x, end.y - start.y))
            return
        }
        if (phase == StrokePhase.MOVE) {
            // Moves are merged: while one is waiting, new points join it instead of queueing
            // another event, so a slow brush (big image) never falls behind the finger.
            synchronized(pendingMove) {
                pendingMove.addAll(points)
                pendingMoveRadius = docRadius
                if (moveQueued) return
                moveQueued = true
            }
            retouchEvents.trySend(RetouchEvent.Move(emptyList(), docRadius))
        } else retouchEvents.trySend(RetouchEvent.End(points.toList(), docRadius))
    }

    /**
     * Queues a stroke start / patch. Strokes are handled strictly in order: the next stroke's undo
     * step is only recorded once the previous one is committed.
     */
    private fun queueRetouch(ev: RetouchEvent) {
        retouchPending++
        retouchEvents.trySend(ev)
        if (retouchConsumer?.isActive != true) {
            retouchConsumer = viewModelScope.launch {
                for (ev in retouchEvents) {
                    try {
                        handleRetouchEvent(ev)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: OutOfMemoryError) {
                        System.gc(); onRetouchFailed(R.string.status_processing_failed_memory)
                    } catch (e: Exception) {
                        onRetouchFailed(R.string.retouch_failed)
                    }
                }
            }
        }
    }

    /**
     * One undo step for a retouch stroke: history first, then the layer gets its own preview copy
     * that the stroke updates live (history keeps the old one). Returns that copy.
     */
    private fun beginRetouchStep(layer: Layer): Bitmap? {
        val old = layer.bitmap?.takeIf { !it.isRecycled } ?: return null
        if (pendingTransactionSnapshot != null) saveHistoryState()
        rememberBeforeEdit(layer, old)
        saveHistoryState()
        val preview = try { old.copy(Bitmap.Config.ARGB_8888, true) } catch (e: OutOfMemoryError) { null } ?: run {
            rollbackToLastHistoryState(); return null
        }
        layer.bitmap = preview
        retouchStepOpen = true
        retouchStrokeFailed = false
        return preview
    }

    /** Keeps the layer's pixels from before its first pixel edit (retouch, Liquify…), for Before/After. */
    private fun rememberBeforeEdit(layer: Layer, current: Bitmap) {
        val known = _uiState.value.compareBefore[layer.id]
        if (known != null && !known.bitmap.isRecycled &&
            known.contentWidth == layer.contentWidth && known.contentHeight == layer.contentHeight) return
        val copy = try { current.copy(Bitmap.Config.ARGB_8888, false) } catch (e: OutOfMemoryError) { null } ?: return
        val before = com.example.data.model.LayerBefore(copy, layer.contentWidth, layer.contentHeight)
        _uiState.value = _uiState.value.copy(compareBefore = _uiState.value.compareBefore + (layer.id to before))
        // Saved with the project, so Before/After still works after the app is closed.
        val projectId = _uiState.value.currentProject?.id ?: return
        viewModelScope.launch { repository.saveBeforeImage(projectId, layer.id, before) }
    }

    private fun onRetouchFailed(@StringRes msg: Int) {
        if (retouchStepOpen) {
            rollbackToLastHistoryState()
            retouchStepOpen = false
            retouchPending = (retouchPending - 1).coerceAtLeast(0)
        }
        retouchStrokeFailed = true
        retouchSession = null
        _uiState.value = _uiState.value.copy(statusMessage = tr(msg))
    }

    private suspend fun ensureRetouchSession(layer: Layer): RetouchSession {
        retouchSession?.let { s -> if (s.layerId == layer.id && s.sourcePath == layer.sourceAssetPath) return s }
        retouchSession = null
        awaitRetouchWrites()
        val full = loadAuthoritativeLayerBitmapNow(layer) ?: throw IllegalStateException("Image unavailable")
        val canvas = withContext(retouchDispatcher) {
            val px = IntArray(full.width * full.height)
            full.getPixels(px, 0, full.width, 0, 0, full.width, full.height)
            com.example.engine.RetouchCanvas(full.width, full.height, px)
        }
        if (full !== layer.bitmap && !full.isRecycled) full.recycle()
        return RetouchSession(layer.id, layer.sourceAssetPath, canvas).also { retouchSession = it }
    }

    private fun prepareStrokeMapping(layer: Layer, canvas: com.example.engine.RetouchCanvas, docW: Int, docH: Int) {
        val toDoc = SelectionEngine.getLayerMatrixForSize(layer, canvas.width.toFloat(), canvas.height.toFloat(), docW, docH)
        val inv = Matrix()
        if (!toDoc.invert(inv)) throw IllegalStateException("Layer cannot be mapped")
        val v = FloatArray(9).also { inv.getValues(it) }
        strokeToLocal = inv
        strokeLocalScale = kotlin.math.sqrt(kotlin.math.abs(v[Matrix.MSCALE_X] * v[Matrix.MSCALE_Y] - v[Matrix.MSKEW_X] * v[Matrix.MSKEW_Y])).coerceAtLeast(0.01f)
    }

    private fun toLocalPoints(points: List<PointF>): FloatArray {
        val arr = FloatArray(points.size * 2)
        points.forEachIndexed { i, p -> arr[i * 2] = p.x; arr[i * 2 + 1] = p.y }
        strokeToLocal?.mapPoints(arr)
        return arr
    }

    private suspend fun paintRetouch(canvas: com.example.engine.RetouchCanvas, points: List<PointF>, docRadius: Float) {
        val preview = strokePreview ?: return
        val local = toLocalPoints(points)
        val r = docRadius * strokeLocalScale
        val changed = withContext(retouchDispatcher) {
            canvas.stroke(local, r)?.also { area -> canvas.downsampleInto(preview, area) }
        }
        val last = points.lastOrNull()
        val off = strokeOffsetDoc
        val marker = if (last != null && off != null) PointF(last.x + off.x, last.y + off.y) else null
        // The canvas redraws the whole image: at most ~14 times a second while painting, so
        // each redraw can finish (the end of the stroke always redraws).
        val now = android.os.SystemClock.uptimeMillis()
        if ((changed != null || marker != null) && now - lastRetouchBump >= 70L) {
            lastRetouchBump = now
            bumpRetouchRender(marker)
        }
    }

    /** Shows the live preview changes without recording history. */
    private fun bumpRetouchRender(marker: PointF?) {
        val proj = _uiState.value.currentProject ?: return
        proj.markDirty()
        _uiState.value = _uiState.value.copy(
            currentProject = proj.copy(layers = ArrayList(proj.layers)),
            renderRevision = proj.renderRevision,
            isProjectDirty = true,
            retouchMarkerDoc = marker ?: _uiState.value.retouchMarkerDoc
        )
    }

    /** The document selection [sel] on the layer's own pixels (alpha bytes and row stride), or null. */
    private fun localSelectionBytes(c: com.example.engine.RetouchCanvas, sel: Bitmap?): Pair<ByteArray?, Int> {
        val s = sel?.takeIf { !it.isRecycled } ?: return null to c.width
        val m = Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ALPHA_8)
        android.graphics.Canvas(m).drawBitmap(s, strokeToLocal!!, Paint(Paint.FILTER_BITMAP_FLAG))
        val r = SelectionEngine.readAlphaBytes(m); m.recycle()
        return r.first to r.second
    }

    private suspend fun handleRetouchEvent(ev: RetouchEvent) {
        when (ev) {
            is RetouchEvent.Start -> {
                val preview = beginRetouchStep(ev.layer) ?: run { retouchStrokeFailed = true; retouchPending = (retouchPending - 1).coerceAtLeast(0); return }
                strokeLayer = ev.layer
                strokePreview = preview
                strokeOffsetDoc = ev.cloneOffsetDoc
                val session = ensureRetouchSession(ev.layer)
                prepareStrokeMapping(ev.layer, session.canvas, ev.docW, ev.docH)
                val off = ev.cloneOffsetDoc?.let { o ->
                    val v = floatArrayOf(o.x, o.y); strokeToLocal!!.mapVectors(v); v
                } ?: floatArrayOf(0f, 0f)
                withContext(retouchDispatcher) {
                    val (selBytes, selStride) = localSelectionBytes(session.canvas, ev.selection)
                    session.canvas.beginStroke(ev.tool, ev.brush, off[0], off[1], selBytes, selStride)
                }
                paintRetouch(session.canvas, ev.points, ev.docRadius)
            }
            is RetouchEvent.Move -> {
                val pts = takePendingMove()
                if (retouchStrokeFailed) return
                val session = retouchSession ?: return
                if (pts.isNotEmpty()) paintRetouch(session.canvas, pts, pendingMoveRadius.takeIf { it > 0f } ?: ev.docRadius)
            }
            is RetouchEvent.End -> {
                val pts = takePendingMove()
                if (retouchStrokeFailed) { retouchStrokeFailed = false; return }
                val session = retouchSession ?: return
                if (pts.isNotEmpty()) paintRetouch(session.canvas, pts, ev.docRadius)
                paintRetouch(session.canvas, ev.points, ev.docRadius)
                withContext(retouchDispatcher) { session.canvas.endStroke() }
                commitRetouch(session)
            }
            is RetouchEvent.Heal -> {
                val preview = beginRetouchStep(ev.layer) ?: run { retouchPending = (retouchPending - 1).coerceAtLeast(0); return }
                strokeLayer = ev.layer
                strokePreview = preview
                strokeOffsetDoc = null
                val session = ensureRetouchSession(ev.layer)
                prepareStrokeMapping(ev.layer, session.canvas, ev.docW, ev.docH)
                val c = session.canvas
                val local = toLocalPoints(ev.points)
                val r = ev.docRadius * strokeLocalScale
                val changed = withContext(retouchDispatcher) {
                    val (selBytes, selStride) = localSelectionBytes(c, ev.selection)
                    c.heal(local, r, selBytes, selStride)?.also { area -> c.downsampleInto(preview, area) }
                }
                if (changed == null) {
                    onRetouchFailed(R.string.heal_failed)
                    return
                }
                commitRetouch(session)
            }
            is RetouchEvent.Fill -> {
                val preview = beginRetouchStep(ev.layer) ?: run { retouchPending = (retouchPending - 1).coerceAtLeast(0); return }
                strokeLayer = ev.layer
                strokePreview = preview
                strokeOffsetDoc = null
                val session = ensureRetouchSession(ev.layer)
                prepareStrokeMapping(ev.layer, session.canvas, ev.docW, ev.docH)
                val c = session.canvas
                val local = toLocalPoints(listOfNotNull(ev.pointDoc, ev.endDoc))
                val b = ev.brush
                val size = kotlin.math.min(c.width, c.height)
                val paint: (Int, Int) -> Int = when {
                    b.fillMode == com.example.engine.FillMode.GRADIENT && local.size >= 4 -> { x, y ->
                        com.example.engine.RetouchCanvas.gradientAt(b, local[0], local[1], local[2], local[3], x, y)
                    }
                    b.fillMode == com.example.engine.FillMode.PATTERN -> { x, y -> com.example.engine.RetouchCanvas.patternAt(b, size, x, y) }
                    else -> { _, _ -> b.fillColor or (0xFF shl 24) }
                }
                val changed = withContext(retouchDispatcher) {
                    val (bytes, stride) = localSelectionBytes(c, ev.selection)
                    c.fill(
                        kotlin.math.floor(local[0]).toInt(), kotlin.math.floor(local[1]).toInt(), paint, b.fillOpacity,
                        b.fillTolerance, b.fillContiguous, b.fillAntiAlias, bytes, stride
                    )?.also { area -> c.downsampleInto(preview, area) }
                }
                if (changed == null) {
                    onRetouchFailed(R.string.fill_nothing)
                    return
                }
                commitRetouch(session)
            }
            is RetouchEvent.Patch -> {
                val preview = beginRetouchStep(ev.layer) ?: run { retouchPending = (retouchPending - 1).coerceAtLeast(0); return }
                strokeLayer = ev.layer
                strokePreview = preview
                strokeOffsetDoc = null
                val session = ensureRetouchSession(ev.layer)
                prepareStrokeMapping(ev.layer, session.canvas, ev.docW, ev.docH)
                val c = session.canvas
                val vec = floatArrayOf(ev.dxDoc, ev.dyDoc).also { strokeToLocal!!.mapVectors(it) }
                val changed = withContext(retouchDispatcher) {
                    // The selection on the layer's own pixels (full resolution).
                    val local = Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ALPHA_8)
                    android.graphics.Canvas(local).drawBitmap(ev.selection, strokeToLocal!!, Paint(Paint.FILTER_BITMAP_FLAG))
                    val (bytes, stride) = SelectionEngine.readAlphaBytes(local)
                    local.recycle()
                    c.patch(bytes, stride, kotlin.math.round(vec[0]).toInt(), kotlin.math.round(vec[1]).toInt())
                        ?.also { area -> c.downsampleInto(preview, area) }
                }
                if (changed == null) {
                    onRetouchFailed(R.string.retouch_patch_needs_selection)
                    return
                }
                commitRetouch(session)
            }
        }
    }

    /**
     * Ends a retouch step: the layer points at a new full-resolution source file, written in the
     * background from a copy of the pixels (strokes do not wait for the encoder). Readers of that
     * file wait for pending writes ([awaitRetouchWrites]).
     */
    private suspend fun commitRetouch(session: RetouchSession) {
        val layer = strokeLayer ?: return
        val proj = _uiState.value.currentProject ?: return
        val c = session.canvas
        // Never more than two strokes waiting to be written: each holds a full-size copy.
        while (retouchWrites.size >= 2) { val w = retouchWrites.firstOrNull() ?: break; w.join(); retouchWrites.remove(w) }
        // The copy is a Bitmap (native memory), not an IntArray on the small Java heap.
        val snapshot = withContext(retouchDispatcher) {
            Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888).also { it.setPixels(c.pix, 0, c.width, 0, 0, c.width, c.height) }
        }
        val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "png"
        val file = File(repository.getProjectDir(proj.id), "source_retouch_${layer.id}_${System.currentTimeMillis()}.$ext")
        // One write at a time, and a failed write (e.g. out of memory) never takes the app down:
        // the layer then keeps using its preview pixels for that step.
        val job = viewModelScope.launch(retouchWriteDispatcher) {
            try {
                val tmp = File(file.path + ".tmp")
                if (ImageProcessor.saveLosslessBitmap(snapshot, tmp, fast = true)) {
                    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                file.delete()
                System.gc()
            } finally {
                snapshot.recycle()
            }
        }
        retouchWrites.add(job)
        job.invokeOnCompletion { retouchWrites.remove(job) }
        layer.sourceAssetPath = file.absolutePath
        layer.originalWidth = c.width
        layer.originalHeight = c.height
        layer.isBitmapDirty = true
        session.sourcePath = file.absolutePath
        retouchStepOpen = false
        retouchPending = (retouchPending - 1).coerceAtLeast(0)
        strokePreview = null
        commitProjectMutation()
    }

    /**
     * Photoshop frequency separation of the selected image layer: adds a Low frequency layer
     * (Gaussian blur of [radiusDoc] document pixels: colour and tone) and above it a High
     * frequency layer (texture) in Linear Light, clipped to Low, so together they look exactly
     * like the original, which is hidden. Retouch Low (Blur, Smudge, Clone) to even out skin tone
     * without losing texture, and High (Clone, Patch) to remove blemishes without changing colour.
     */
    /** Radius (document px) of the last frequency separation: "Smooth selected area" blurs Low with it. */
    private var lastFsRadiusDoc = 6f

    /**
     * Smooths the selected layer (normally the Low frequency layer) inside the active selection
     * with a Gaussian blur at the separation radius: evens out colour and tone there while the
     * High layer keeps the texture.
     */
    fun smoothSelectionOnLayer() {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(_uiState.value.selectedLayerIndex) ?: return
        val sel = _uiState.value.selectionMask
        if (sel == null || !SelectionEngine.hasSelection(sel)) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.fs_smooth_needs_selection)); return
        }
        val bmpW = layer.bitmap?.width ?: 0
        // applyProFilter works on the full-resolution layer: convert the radius to its pixels.
        val fullW = layer.originalWidth.takeIf { it > 0 } ?: bmpW
        val sigma = lastFsRadiusDoc * fullW / layer.intrinsicDocumentWidth().coerceAtLeast(1f)
        applyProFilter(ProFilterType.GAUSSIAN_BLUR, com.example.engine.ProEffects.defaults(ProFilterType.GAUSSIAN_BLUR).copy(p1 = sigma.coerceIn(0.5f, 250f)))
    }

    fun createFrequencySeparation(radiusDoc: Float) =
        createFrequencySeparation(radiusDoc, com.example.engine.LowPassMethod.GAUSSIAN, 20f, false, 2f)

    /**
     * Splits the selected layer into Low (blurred with [method]) and High, or with [threeBand]
     * into Low, Mid and High where [detailRadiusDoc] divides Mid from High.
     */
    fun createFrequencySeparation(
        radiusDoc: Float, method: com.example.engine.LowPassMethod, threshold: Float,
        threeBand: Boolean, detailRadiusDoc: Float
    ) {
        val proj = _uiState.value.currentProject ?: return
        if (_uiState.value.isProcessing) return
        val idx = _uiState.value.selectedLayerIndex
        val layer = proj.layers.getOrNull(idx) ?: return
        if (layer.isLocked) { _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_layer_locked)); return }
        if (layer.type != LayerType.IMAGE || layer.bitmap == null) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.effect_needs_image_layer)); return
        }
        if (pendingTransactionSnapshot != null) saveHistoryState()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_frequency_separating))
            var full: Bitmap? = null
            var looked: Bitmap? = null
            var low: Bitmap? = null
            var mid: Bitmap? = null
            var high: Bitmap? = null
            var historySaved = false
            try {
                full = loadAuthoritativeLayerBitmap(layer) ?: throw IllegalStateException("Image unavailable")
                // Split what the layer shows (its adjustments / filter / LUT baked in).
                looked = withContext(Dispatchers.Default) { com.example.engine.LayerRenderer.applyLayerLook(full!!, layer) }
                val perDoc = full!!.width / layer.intrinsicDocumentWidth().coerceAtLeast(1f)
                val sigma = radiusDoc * perDoc
                if (threeBand) {
                    val parts = withContext(Dispatchers.Default) {
                        com.example.engine.FrequencySeparation.split3(looked!!, sigma, detailRadiusDoc * perDoc, method, threshold)
                    }
                    low = parts.first; mid = parts.second; high = parts.third
                } else {
                    val parts = withContext(Dispatchers.Default) { com.example.engine.FrequencySeparation.split(looked!!, sigma, method, threshold) }
                    low = parts.first; high = parts.second
                }
                fun part(name: Int, blend: BlendModeType, clipped: Boolean) = layer.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    name = uniqueLayerName(tr(name), proj),
                    bitmap = null,
                    sourceAssetPath = null,
                    filter = FilterType.NONE,
                    filterIntensity = 1f,
                    lutId = null,
                    adjustments = ColorAdjustments(),
                    blendMode = blend,
                    isClipped = clipped,
                    isVisible = true,
                    isLocked = false,
                    isEditingMask = false,
                    textConfig = layer.textConfig.copy(),
                    distort = layer.distort?.copyOf()
                )
                val lowLayer = part(R.string.layer_name_fs_low, layer.blendMode, layer.isClipped)
                // High is clipped to Low: its Linear Light texture applies to Low only.
                val highLayer = part(R.string.layer_name_fs_high, BlendModeType.LINEAR_LIGHT, true)
                // Mid (three-band only) sits between Low and High, also Linear Light clipped to Low.
                val midLayer = if (mid != null) part(R.string.layer_name_fs_mid, BlendModeType.LINEAR_LIGHT, true) else null
                val cw = layer.intrinsicDocumentWidth(); val ch = layer.intrinsicDocumentHeight()
                if (!commitDestructiveBitmap(proj, lowLayer, low!!, cw, ch, "fs_low") ||
                    (midLayer != null && !commitDestructiveBitmap(proj, midLayer, mid!!, cw, ch, "fs_mid")) ||
                    !commitDestructiveBitmap(proj, highLayer, high!!, cw, ch, "fs_high")) {
                    throw java.io.IOException("Save failed")
                }
                // Same placement and shape as the original (commit resets nothing of that).
                lowLayer.maskAnchor = layer.maskAnchor; highLayer.maskAnchor = layer.maskAnchor
                midLayer?.maskAnchor = layer.maskAnchor
                saveHistoryState()
                historySaved = true
                layer.isVisible = false
                lastFsRadiusDoc = radiusDoc
                val added = listOfNotNull(lowLayer, midLayer, highLayer)
                added.forEachIndexed { i, l -> proj.layers.add(idx + 1 + i, l) }
                commitProjectMutation(
                    // Low is selected: smoothing tone and colour is the usual next step.
                    selectedLayerIndex = idx + 1,
                    statusMessage = tr(R.string.status_frequency_separated),
                    transformState = { it.copy(isProcessing = false) }
                )
            } catch (e: OutOfMemoryError) {
                if (historySaved) rollbackToLastHistoryState()
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_processing_failed_memory))
            } catch (e: Exception) {
                if (historySaved) rollbackToLastHistoryState()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_effect_failed))
            } finally {
                // The new layers keep their own previews.
                listOf(full, looked, low, mid, high).distinct().forEach { b -> if (b != null && b !== layer.bitmap && !b.isRecycled) b.recycle() }
            }
        }
    }

    /** True when Liquify can work on the selected layer; otherwise explains why in the status. */
    /** The active selection on [layer]'s preview pixels (ALPHA_8, caller recycles), or null. */
    fun selectionOnLayerPreview(layer: Layer): Bitmap? {
        val proj = _uiState.value.currentProject ?: return null
        val sel = _uiState.value.selectionMask?.takeIf { !it.isRecycled && SelectionEngine.hasSelection(it) } ?: return null
        return try { SelectionEngine.mapDocumentMaskToLayerLocal(sel, layer, proj.width, proj.height) } catch (e: Throwable) { null }
    }

        fun canLiquifySelectedLayer(): Boolean {
        val layer = _uiState.value.currentProject?.layers?.getOrNull(_uiState.value.selectedLayerIndex) ?: return false
        val msg = when {
            layer.isLocked -> R.string.status_layer_locked
            layer.type != LayerType.IMAGE || layer.bitmap == null -> R.string.effect_needs_image_layer
            else -> null
        }
        if (msg != null) _uiState.value = _uiState.value.copy(statusMessage = tr(msg))
        return msg == null
    }

    /**
     * Applies a Liquify warp to the selected image layer at full resolution (one undo step).
     * Progress and Cancel use the Pro effect progress bar.
     */
    fun applyLiquify(engine: com.example.engine.LiquifyEngine) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(_uiState.value.selectedLayerIndex) ?: return
        if (layer.isLocked || layer.type != LayerType.IMAGE) return
        if (!engine.hasChanges) return
        if (proEffectJob?.isActive == true) return
        if (pendingTransactionSnapshot != null) saveHistoryState()
        proEffectJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, proEffectProgress = 0f, statusMessage = tr(R.string.status_applying_liquify))
            val job = currentCoroutineContext()[Job]
            var src: Bitmap? = null
            var out: Bitmap? = null
            var historySaved = false
            try {
                src = loadAuthoritativeLayerBitmap(layer) ?: throw IllegalStateException("Image unavailable")
                out = withContext(Dispatchers.Default) {
                    engine.applyFull(src!!, isCancelled = { job?.isCancelled == true }) { f ->
                        viewModelScope.launch(Dispatchers.Main) {
                            if (_uiState.value.proEffectProgress != null) _uiState.value = _uiState.value.copy(proEffectProgress = f * 0.95f)
                        }
                    }
                }
                saveHistoryState()
                historySaved = true
                if (!commitDestructiveBitmap(proj, layer, out!!, layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight(), "liquify", keepBefore = true)) {
                    throw java.io.IOException("Save failed")
                }
                commitProjectMutation(
                    statusMessage = tr(R.string.status_liquify_applied),
                    transformState = { it.copy(isProcessing = false, proEffectProgress = null) }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (historySaved) rollbackToLastHistoryState()
                _uiState.value = _uiState.value.copy(isProcessing = false, proEffectProgress = null, statusMessage = tr(R.string.effect_cancelled))
                throw e
            } catch (e: OutOfMemoryError) {
                if (historySaved) rollbackToLastHistoryState()
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, proEffectProgress = null, statusMessage = tr(R.string.status_processing_failed_memory))
            } catch (e: Exception) {
                if (historySaved) rollbackToLastHistoryState()
                _uiState.value = _uiState.value.copy(isProcessing = false, proEffectProgress = null, statusMessage = tr(R.string.status_effect_failed))
            } finally {
                val p = layer.bitmap
                out?.let { if (it !== p && !it.isRecycled) it.recycle() }
                src?.let { if (it !== p && !it.isRecycled) it.recycle() }
            }
        }
    }

    fun applyProFilter(effect: ProFilterType, params: com.example.engine.ProEffectParams) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        val layer = proj.layers.getOrNull(idx) ?: return
        if (layer.isLocked) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_layer_locked))
            return
        }
        if (layer.type != LayerType.IMAGE) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.effect_needs_image_layer))
            return
        }
        if (proEffectJob?.isActive == true) return

        proEffectJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProcessing = true,
                proEffectProgress = 0f,
                statusMessage = tr(R.string.status_processing)
            )
            val job = currentCoroutineContext()[Job]
            var historySaved = false
            try {
                val src = loadAuthoritativeLayerBitmap(layer) ?: throw IllegalStateException("Image unavailable")
                val processed = withContext(Dispatchers.Default) {
                    ImageProcessor.applyProEffect(
                        src, effect, params,
                        isCancelled = { job?.isCancelled == true },
                        onProgress = { f ->
                            viewModelScope.launch(Dispatchers.Main) {
                                if (_uiState.value.proEffectProgress != null) {
                                    _uiState.value = _uiState.value.copy(proEffectProgress = f * 0.95f)
                                }
                            }
                        }
                    )
                }

                // Scope effect strictly to selection if an active selection exists
                val activeSelection = _uiState.value.selectionMask
                val finalProcessed = if (activeSelection != null && SelectionEngine.hasSelection(activeSelection)) {
                    val localMask = SelectionEngine.mapDocumentMaskToLayerLocal(activeSelection, layer, proj.width, proj.height)
                    val comp = SelectionEngine.compositeFilteredWithSelection(src, processed, localMask)
                    if (!localMask.isRecycled) localMask.recycle()
                    if (processed !== src && !processed.isRecycled) processed.recycle()
                    comp
                } else {
                    processed
                }

                saveHistoryState()
                historySaved = true
                if (!commitDestructiveBitmap(proj, layer, finalProcessed, layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight(), effect.name.lowercase(), keepBefore = true)) {
                    throw java.io.IOException("Save failed")
                }
                if (finalProcessed !== layer.bitmap && !finalProcessed.isRecycled) finalProcessed.recycle()
                commitProjectMutation(
                    statusMessage = tr(R.string.status_effect_applied),
                    transformState = { it.copy(isProcessing = false, proEffectProgress = null) }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Failed after its undo step was recorded: drop that step (and any partial change).
                if (historySaved) rollbackToLastHistoryState()
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    proEffectProgress = null,
                    statusMessage = tr(R.string.effect_cancelled)
                )
                throw e
            } catch (e: OutOfMemoryError) {
                // Failed after its undo step was recorded: drop that step (and any partial change).
                if (historySaved) rollbackToLastHistoryState()
                System.gc()
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    proEffectProgress = null,
                    statusMessage = tr(R.string.status_processing_failed_memory)
                )
            } catch (e: Exception) {
                // Failed after its undo step was recorded: drop that step (and any partial change).
                if (historySaved) rollbackToLastHistoryState()
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    proEffectProgress = null,
                    statusMessage = tr(R.string.status_effect_failed)
                )
            }
        }
    }

    /** Lasso points arrive already snapped to edges (the canvas snaps them live when enabled). */
    fun applyLassoSelection(points: List<PointF>) {
        if (_uiState.value.selectionMask == null || points.size < 3) return
        val mode = effectiveSelectionMode()
        mutateSelectionAsync { mask -> SelectionEngine.fillLassoPath(mask, points, mode) }
    }

    fun applyPolygonSelection(points: List<PointF>) {
        if (_uiState.value.selectionMask == null || points.size < 3) return
        val mode = effectiveSelectionMode()
        mutateSelectionAsync { mask -> SelectionEngine.fillPolygonPoints(mask, points, mode) }
    }

    fun applyRectangleSelection(start: PointF, end: PointF) {
        applyShapeSelection(android.graphics.RectF(minOf(start.x, end.x), minOf(start.y, end.y), maxOf(start.x, end.x), maxOf(start.y, end.y)), isEllipse = false)
    }

    fun applyEllipseSelection(start: PointF, end: PointF) {
        applyShapeSelection(android.graphics.RectF(minOf(start.x, end.x), minOf(start.y, end.y), maxOf(start.x, end.x), maxOf(start.y, end.y)), isEllipse = true)
    }

    fun applyMagicWand(touchX: Int, touchY: Int) {
        val proj = _uiState.value.currentProject ?: return
        if (_uiState.value.selectionMask == null) return
        val tolerance = _uiState.value.magicWandTolerance
        val mode = effectiveSelectionMode()
        val isContiguous = _uiState.value.isMagicWandContiguous
        val options = _uiState.value.wandOptions
        val wandLayer = if (options.sampleAllLayers) null else wandTargetLayer(proj, touchX, touchY)
        if (wandLayer == null && !options.sampleAllLayers) {
            // Tapped outside every image layer: say so instead of silently doing nothing.
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_wand_no_layer))
            return
        }
        if (touchX !in 0 until proj.width || touchY !in 0 until proj.height) return

        mutateSelectionAsync(R.string.status_selecting_area, R.string.status_selection_failed) { mask ->
            val source = wandSourceFor(proj, wandLayer)
            val job = currentCoroutineContext()[Job]
            com.example.engine.MagicWandEngine.select(
                source = source,
                docMask = mask,
                docX = touchX + 0.5f,
                docY = touchY + 0.5f,
                tolerance = tolerance,
                contiguous = isContiguous,
                mode = mode,
                options = options,
                isCancelled = { job?.isActive == false }
            )
        }
    }

    fun featherSelection(radiusPx: Float = 8f) {
        if (_uiState.value.selectionMask == null) return
        mutateSelectionAsync(R.string.status_feathering, R.string.status_feather_failed) { mask ->
            SelectionEngine.featherMask(mask, radiusPx)
        }
    }

    fun growSelection(radiusPx: Int = 5) {
        if (_uiState.value.selectionMask == null) return
        mutateSelectionAsync(R.string.status_expanding_selection, R.string.status_expand_failed) { mask ->
            SelectionEngine.growMask(mask, radiusPx)
        }
    }

    fun shrinkSelection(radiusPx: Int = 5) {
        if (_uiState.value.selectionMask == null) return
        mutateSelectionAsync(R.string.status_contracting_selection, R.string.status_contract_failed) { mask ->
            SelectionEngine.shrinkMask(mask, radiusPx)
        }
    }

    fun smoothSelection(radiusPx: Int = 3) {
        if (_uiState.value.selectionMask == null) return
        mutateSelectionAsync(R.string.status_smoothing_selection, R.string.status_smooth_failed) { mask ->
            SelectionEngine.smoothMask(mask, radiusPx)
        }
    }

    fun refineSelectionEdge(contrastFactor: Float = 2.0f) {
        if (_uiState.value.selectionMask == null) return
        mutateSelectionAsync(R.string.status_refining_selection, R.string.status_refine_failed) { mask ->
            SelectionEngine.refineMaskEdges(mask, contrastFactor)
        }
    }

    fun openRefineEdgeDialog() {
        _uiState.value = _uiState.value.copy(isRefineEdgeDialogOpen = true)
    }

    fun closeRefineEdgeDialog() {
        clearRefinePreview()
        _uiState.value = _uiState.value.copy(isRefineEdgeDialogOpen = false)
    }

    private var refinePreviewJob: Job? = null

    fun clearRefinePreview() {
        refinePreviewJob?.cancel()
        refinePreviewJob = null
        refineAiCache = null
        _uiState.value = _uiState.value.copy(refinePreview = null, refinePreviewOriginal = null, refinePreviewGeom = null)
    }

    fun setRefinePreviewMode(mode: RefinePreviewMode) {
        _uiState.value = _uiState.value.copy(refinePreviewMode = mode)
    }

    /**
     * Live preview for the Refine Edge dialog: the selected area of the active layer is cropped,
     * downscaled (≤ 480 px) and refined with the dialog's settings, so every slider change is
     * visible within a fraction of a second. Pixel radii are scaled to the preview size so the
     * preview matches the full-resolution result.
     */
    fun requestRefinePreview(config: RefineEdgeConfig, strokes: List<RefineStroke> = emptyList()) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(_uiState.value.selectedLayerIndex) ?: return
        val proxy = layer.bitmap ?: return
        val docMask = _uiState.value.selectionMask ?: return
        refinePreviewJob?.cancel()
        refinePreviewJob = viewModelScope.launch {
            // Short debounce: the preview follows the slider almost instantly. A fast, smaller
            // pass is shown first, then the detailed one replaces it.
            delay(60)
            val mode = _uiState.value.refinePreviewMode
            for (size in intArrayOf(360, 720)) {
                val pair = try {
                    withContext(Dispatchers.Default) { buildRefinePreview(proj, layer, proxy, docMask, config, mode, size, strokes) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
                if (pair == null) {
                    if (size == 360) _uiState.value = _uiState.value.copy(refinePreview = null, refinePreviewOriginal = null)
                    break
                }
                _uiState.value = _uiState.value.copy(refinePreview = pair.first, refinePreviewOriginal = pair.second, refinePreviewGeom = pair.third)
            }
        }
    }

    private suspend fun buildRefinePreview(
        proj: Project,
        layer: Layer,
        proxy: Bitmap,
        docMask: Bitmap,
        config: RefineEdgeConfig,
        mode: RefinePreviewMode,
        maxSize: Int = 720,
        strokes: List<RefineStroke> = emptyList()
    ): Triple<Bitmap, Bitmap, RefinePreviewGeom>? {
        val local = SelectionEngine.mapDocumentMaskToLayerLocal(docMask, layer, proj.width, proj.height)
        try {
            // The area shown: the selection plus everything brushed.
            var bounds = SelectionEngine.selectionBounds(local)?.let { android.graphics.Rect(it) }
            val longer = max(proxy.width, proxy.height).toFloat()
            for (st in strokes) for (pt in st.points) {
                val r = (st.radius * longer).toInt() + 1
                val x = (pt.x * proxy.width).toInt(); val y = (pt.y * proxy.height).toInt()
                val b = android.graphics.Rect(x - r, y - r, x + r, y + r)
                if (bounds == null) bounds = b else bounds.union(b)
            }
            if (bounds == null) return null
            val margin = (max(bounds.width(), bounds.height()) * 0.15f).toInt() + 8
            val crop = android.graphics.Rect(
                (bounds.left - margin).coerceAtLeast(0),
                (bounds.top - margin).coerceAtLeast(0),
                (bounds.right + margin).coerceAtMost(proxy.width),
                (bounds.bottom + margin).coerceAtMost(proxy.height)
            )
            if (crop.width() < 2 || crop.height() < 2) return null
            val ratio = min(1f, maxSize.toFloat() / max(crop.width(), crop.height()))
            val pw = (crop.width() * ratio).toInt().coerceAtLeast(2)
            val ph = (crop.height() * ratio).toInt().coerceAtLeast(2)
            val srcCrop = Bitmap.createBitmap(proxy, crop.left, crop.top, crop.width(), crop.height())
            val srcSmall = Bitmap.createScaledBitmap(srcCrop, pw, ph, true)
            val maskCrop = Bitmap.createBitmap(local, crop.left, crop.top, crop.width(), crop.height())
            val maskSmall = Bitmap.createScaledBitmap(maskCrop, pw, ph, true)
            val rx = pw.toFloat() / crop.width(); val ry = ph.toFloat() / crop.height()
            val toPx: (Float, Float) -> Pair<Float, Float> = { u, v -> (u * proxy.width - crop.left) * rx to (v * proxy.height - crop.top) * ry }
            val brushed = applySelectionStrokes(maskSmall, strokes, toPx, longer * rx)
            val area = refineAreaBytes(strokes, pw, ph, toPx, longer * rx)
            val ai = if (config.aiAssist) refineAiAlpha(layer, pw, ph, crop) else null

            // The real refine runs on the full-resolution source: scale pixel radii accordingly.
            val fullMax = max(layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight()).coerceAtLeast(1f)
            val scaleToPreview = (max(pw, ph).toFloat() / max(crop.width(), crop.height())) * (max(proxy.width, proxy.height) / fullMax)
            // Every pixel size shrinks with the preview so it matches the full-resolution result.
            fun sc(v: Float) = v * scaleToPreview
            val previewConfig = config.copy(
                radiusPx = if (config.radiusPx <= 0) 0 else (config.radiusPx * scaleToPreview).toInt().coerceAtLeast(1),
                smoothPx = sc(config.smoothPx),
                featherPx = sc(config.featherPx),
                shiftEdgePx = sc(config.shiftEdgePx),
                borderPx = if (config.borderPx >= 1f) max(1f, sc(config.borderPx)) else 0f
            )
            val result = RefineEdgeEngine.refineEdges(srcSmall, brushed, previewConfig, area, ai)
            val shown = result.decontaminatedSource ?: srcSmall
            fun compose(colors: Bitmap, maskBmp: Bitmap): Bitmap {
                val src = IntArray(pw * ph)
                val alpha = IntArray(pw * ph)
                colors.getPixels(src, 0, pw, 0, 0, pw, ph)
                maskBmp.getPixels(alpha, 0, pw, 0, 0, pw, ph)
                val out = IntArray(pw * ph)
                for (i in out.indices) {
                    val a = ((alpha[i] ushr 24) and 0xFF) / 255f
                    val p = src[i]
                    val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                    out[i] = when (mode) {
                        RefinePreviewMode.ON_BLACK -> Color.rgb((r * a).toInt(), (g * a).toInt(), (b * a).toInt())
                        RefinePreviewMode.ON_WHITE -> Color.rgb((r * a + 255 * (1 - a)).toInt(), (g * a + 255 * (1 - a)).toInt(), (b * a + 255 * (1 - a)).toInt())
                        RefinePreviewMode.MASK -> { val v = (a * 255).toInt(); Color.rgb(v, v, v) }
                        RefinePreviewMode.OVERLAY -> {
                            val k = 0.55f * (1 - a)
                            Color.rgb((r * (1 - k) + 230 * k).toInt(), (g * (1 - k) + 30 * k).toInt(), (b * (1 - k) + 60 * k).toInt())
                        }
                    }
                }
                val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
                bmp.setPixels(out, 0, pw, 0, 0, pw, ph)
                return bmp
            }
            val after = compose(shown, result.refinedMask)
            val before = compose(srcSmall, maskSmall)
            listOf(srcCrop, srcSmall, maskCrop, maskSmall, brushed, result.refinedMask).forEach { if (it !== proxy && !it.isRecycled) it.recycle() }
            result.decontaminatedSource?.let { if (!it.isRecycled) it.recycle() }
            return Triple(after, before, RefinePreviewGeom(android.graphics.Rect(crop), proxy.width, proxy.height, pw, ph))
        } finally {
            if (!local.isRecycled) local.recycle()
        }
    }

    fun toggleMagneticSnap() {
        _uiState.value = _uiState.value.copy(isMagneticSnapEnabled = !_uiState.value.isMagneticSnapEnabled)
    }

    fun autoSelectSubject() {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx !in proj.layers.indices) return
        val layer = proj.layers[idx]

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.select_subject_desc))
            var src: Bitmap? = null
            var work: Bitmap? = null
            try {
                src = loadAuthoritativeLayerBitmap(layer) ?: throw IllegalStateException("Layer image unavailable")
                // Segmentation does not need full resolution; a bounded working copy keeps it fast and
                // avoids OOM. The layer transform maps the result back into document space at any size.
                work = withContext(Dispatchers.Default) { ImageProcessor.scaleBitmapToMaxDimension(src!!, 1024) }
                val workLayer = workLayerFor(layer, work!!)
                // 1) On-device ML Kit subject segmentation (real subject masks: people, pets, objects).
                // 2) Fallback: the built-in colour-saliency estimate when ML Kit is unavailable
                //    (no Google Play services, or its model is still downloading).
                val aiMask = com.example.engine.AiSubjectSegmenter.foregroundMask(work!!)
                val usedAi = aiMask != null
                val docMask = withContext(Dispatchers.Default) {
                    val localSubject = aiMask ?: SelectionEngine.selectSubject(work!!)
                    try {
                        layerLocalMaskToDocument(localSubject, workLayer, proj, base = null)
                    } finally {
                        if (!localSubject.isRecycled) localSubject.recycle()
                    }
                }
                selectionMutex.withLock {
                    endTransientSelectionSessions()
                    rememberSelectionState()
                    publishSelection(docMask)
                }
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusMessage = tr(if (usedAi) R.string.status_subject_selected else R.string.status_subject_selected_basic)
                )
            } catch (e: OutOfMemoryError) {
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_selection_failed))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_selection_failed))
            } finally {
                val p = layer.bitmap
                work?.let { if (it !== p && !it.isRecycled) it.recycle() }
                src?.let { if (it !== p && !it.isRecycled) it.recycle() }
            }
        }
    }

    /**
     * A copy of [layer] whose bitmap is [bitmap] but whose document-space footprint is unchanged, so
     * [SelectionEngine.getLayerMatrix] maps [bitmap]'s pixels exactly where the real layer renders.
     */
    private fun workLayerFor(layer: Layer, bitmap: Bitmap): Layer = layer.copy(
        bitmap = bitmap,
        contentWidth = layer.intrinsicDocumentWidth(),
        contentHeight = layer.intrinsicDocumentHeight()
    )

    /**
     * Draws a layer-local mask (sized like [workLayer].bitmap) into a document-sized ALPHA_8 selection
     * mask. Pixels outside the layer footprint keep the value from [base] (or are empty when null).
     */
    private fun layerLocalMaskToDocument(localMask: Bitmap, workLayer: Layer, proj: Project, base: Bitmap?): Bitmap {
        val doc = if (base != null && !base.isRecycled && base.width == proj.width && base.height == proj.height) {
            base.copy(Bitmap.Config.ALPHA_8, true)
        } else {
            SelectionEngine.createBlankMask(proj.width, proj.height)
        }
        val matrix = SelectionEngine.getLayerMatrix(workLayer, proj.width, proj.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        Canvas(doc).drawBitmap(localMask, matrix, paint)
        return doc
    }

    fun applyRefineEdgeMatting(config: RefineEdgeConfig, strokes: List<RefineStroke> = emptyList()) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx !in proj.layers.indices) return
        val layer = proj.layers[idx]
        val currentMask = _uiState.value.selectionMask ?: return
        val toNewLayer = config.output == com.example.engine.RefineOutput.NEW_LAYER || config.output == com.example.engine.RefineOutput.NEW_LAYER_WITH_MASK

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.refine_hair_desc))
            var src: Bitmap? = null
            var localResult: Bitmap? = null
            var decontaminated: Bitmap? = null
            var cutout: Bitmap? = null
            try {
                src = loadAuthoritativeLayerBitmap(layer) ?: throw IllegalStateException("Layer image unavailable")
                val s0 = src!!
                val workLayer = workLayerFor(layer, s0)
                val ai = if (config.aiAssist) refineAiAlpha(layer, s0.width, s0.height, null) else null
                // Matting runs in layer-local pixel space: the document selection is mapped onto the
                // layer's source pixels and the refined result is mapped back into document space.
                val result = withContext(Dispatchers.Default) {
                    val localMask = SelectionEngine.mapDocumentMaskToLayerLocal(currentMask, workLayer, proj.width, proj.height)
                    var input = localMask
                    try {
                        // Add / Subtract brushes change the selection first (in order), then the
                        // Refine Edge Brush marks the areas to matte.
                        val toPx: (Float, Float) -> Pair<Float, Float> = { u, v -> u * s0.width to v * s0.height }
                        val rScale = max(s0.width, s0.height).toFloat()
                        input = applySelectionStrokes(localMask, strokes, toPx, rScale)
                        val area = refineAreaBytes(strokes, s0.width, s0.height, toPx, rScale)
                        com.example.engine.RefineEdgeEngine.refineEdges(s0, input, config, area, ai)
                    } finally {
                        if (input !== localMask && !input.isRecycled) input.recycle()
                        if (!localMask.isRecycled) localMask.recycle()
                    }
                }
                localResult = result.refinedMask
                decontaminated = result.decontaminatedSource
                val refinedDocMask = withContext(Dispatchers.Default) {
                    layerLocalMaskToDocument(result.refinedMask, workLayer, proj, base = if (toNewLayer) null else currentMask)
                }

                when (config.output) {
                    com.example.engine.RefineOutput.SELECTION, com.example.engine.RefineOutput.LAYER_MASK -> {
                        // Decontamination only runs when the user explicitly enabled it in the dialog.
                        val deco = decontaminated
                        if (deco != null) {
                            saveHistoryState()
                            val committed = commitDestructiveBitmap(
                                proj, layer, deco, layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight(),
                                "refine_decontaminate", keepBefore = true
                            )
                            if (!committed) throw java.io.IOException("Failed to persist decontaminated layer")
                            commitProjectMutation()
                        }
                        if (config.output == com.example.engine.RefineOutput.SELECTION) {
                            selectionMutex.withLock {
                                endTransientSelectionSessions()
                                rememberSelectionState()
                                publishSelection(refinedDocMask)
                            }
                        } else {
                            saveHistoryState()
                            layer.maskBitmap = refinedDocMask
                            layer.isMaskEnabled = true
                            layer.isEditingMask = false
                            layer.isMaskLinked = true
                            layer.maskAnchor = layer.currentMaskAnchor()
                            selectionMutex.withLock {
                                endTransientSelectionSessions()
                                rememberSelectionState()
                                publishSelection(SelectionEngine.createBlankMask(proj.width, proj.height))
                            }
                            commitProjectMutation()
                        }
                    }
                    com.example.engine.RefineOutput.NEW_LAYER, com.example.engine.RefineOutput.NEW_LAYER_WITH_MASK -> {
                        // As in Photoshop: a new layer above, the original hidden; decontaminated
                        // colours go to the new layer only.
                        val dup = withContext(Dispatchers.Default) { layer.duplicateLayer(uniqueLayerName(tr(R.string.layer_name_copy, layer.name), proj)) }
                        dup.maskBitmap = null
                        dup.isEditingMask = false
                        val pixels: Bitmap? = if (config.output == com.example.engine.RefineOutput.NEW_LAYER) {
                            cutout = withContext(Dispatchers.Default) { cutoutPixels(decontaminated ?: s0, result.refinedMask) }
                            cutout
                        } else decontaminated
                        saveHistoryState()
                        if (pixels != null && !commitDestructiveBitmap(proj, dup, pixels, layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight(), "refine_layer")) {
                            rollbackToLastHistoryState()
                            throw java.io.IOException("Save failed")
                        }
                        if (config.output == com.example.engine.RefineOutput.NEW_LAYER_WITH_MASK) {
                            dup.maskBitmap = refinedDocMask
                            dup.isMaskEnabled = true
                            dup.isMaskLinked = true
                            dup.maskAnchor = dup.currentMaskAnchor()
                        }
                        layer.isVisible = false
                        proj.layers.add(idx + 1, dup)
                        selectionMutex.withLock {
                            endTransientSelectionSessions()
                            rememberSelectionState()
                            publishSelection(SelectionEngine.createBlankMask(proj.width, proj.height))
                        }
                        commitProjectMutation(selectedLayerIndex = idx + 1)
                    }
                }
                clearRefinePreview()
                _uiState.value = _uiState.value.copy(
                    isRefineEdgeDialogOpen = false,
                    isProcessing = false,
                    statusMessage = tr(R.string.status_matting_complete)
                )
            } catch (e: OutOfMemoryError) {
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_matting_failed))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_matting_failed))
            } finally {
                val p = layer.bitmap
                src?.let { if (it !== p && !it.isRecycled) it.recycle() }
                localResult?.let { if (!it.isRecycled) it.recycle() }
                decontaminated?.let { if (it !== layer.bitmap && !it.isRecycled) it.recycle() }
                cutout?.let { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    /** [src] with its alpha multiplied by [mask] (same size): the selected pixels only. */
    private fun cutoutPixels(src: Bitmap, mask: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val a = SelectionEngine.alphaBytesPacked(mask)
        val row = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val m = a[y * w + x].toInt() and 0xFF
                val na = ((c ushr 24) * m + 127) / 255
                row[x] = (na shl 24) or (c and 0xFFFFFF)
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    private fun strokePaint(radiusPx: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = (radiusPx * 2f).coerceAtLeast(1f)
        color = Color.BLACK
    }

    private fun strokePath(points: List<PointF>, toPx: (Float, Float) -> Pair<Float, Float>): android.graphics.Path {
        val path = android.graphics.Path()
        points.forEachIndexed { i, p ->
            val (x, y) = toPx(p.x, p.y)
            if (i == 0) { path.moveTo(x, y); if (points.size == 1) path.lineTo(x + 0.01f, y) } else path.lineTo(x, y)
        }
        return path
    }

    /**
     * The selection [mask] with the Add / Subtract brush strokes applied in order (a new
     * bitmap; [mask] itself when there is none). [toPx] maps layer-normalised points to pixels.
     */
    private fun applySelectionStrokes(mask: Bitmap, strokes: List<RefineStroke>, toPx: (Float, Float) -> Pair<Float, Float>, radiusScale: Float): Bitmap {
        val edits = strokes.filter { it.mode != RefineBrushMode.REFINE && it.points.isNotEmpty() }
        if (edits.isEmpty()) return mask
        val out = mask.copy(Bitmap.Config.ALPHA_8, true)
        val canvas = android.graphics.Canvas(out)
        for (st in edits) {
            val paint = strokePaint(st.radius * radiusScale)
            if (st.mode == RefineBrushMode.SUBTRACT) paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
            canvas.drawPath(strokePath(st.points, toPx), paint)
        }
        return out
    }

    /** Refine Edge Brush strokes as w x h bytes (255 = painted), or null when there is none. */
    private fun refineAreaBytes(strokes: List<RefineStroke>, w: Int, h: Int, toPx: (Float, Float) -> Pair<Float, Float>, radiusScale: Float): ByteArray? {
        val refine = strokes.filter { it.mode == RefineBrushMode.REFINE && it.points.isNotEmpty() }
        if (refine.isEmpty()) return null
        val m = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        try {
            val canvas = android.graphics.Canvas(m)
            for (st in refine) canvas.drawPath(strokePath(st.points, toPx), strokePaint(st.radius * radiusScale))
            return SelectionEngine.alphaBytesPacked(m)
        } finally {
            m.recycle()
        }
    }

    /** Subject segmentation of a layer, computed once per layer image (at most 1024 px). */
    private var refineAiCache: Pair<Bitmap, Bitmap?>? = null

    /**
     * Subject-segmentation confidence for [layer] as w x h bytes, optionally only for [crop]
     * (in the layer preview's pixels). Null when segmentation is unavailable.
     */
    private suspend fun refineAiAlpha(layer: Layer, w: Int, h: Int, crop: android.graphics.Rect?): ByteArray? {
        val proxy = layer.bitmap?.takeIf { !it.isRecycled } ?: return null
        val cached = refineAiCache
        val aiMask: Bitmap = (if (cached != null && cached.first === proxy) cached.second else {
            val small = withContext(Dispatchers.Default) { ImageProcessor.scaleBitmapToMaxDimension(proxy, 1024) }
            val m = try { com.example.engine.AiSubjectSegmenter.foregroundMask(small) } finally { if (small !== proxy && !small.isRecycled) small.recycle() }
            refineAiCache = proxy to m
            m
        }) ?: return null
        return withContext(Dispatchers.Default) {
            val sx = aiMask.width.toFloat() / proxy.width; val sy = aiMask.height.toFloat() / proxy.height
            val part = if (crop != null) {
                val l = (crop.left * sx).toInt().coerceIn(0, aiMask.width - 1); val t = (crop.top * sy).toInt().coerceIn(0, aiMask.height - 1)
                val r = kotlin.math.ceil(crop.right * sx).toInt().coerceIn(l + 1, aiMask.width); val b = kotlin.math.ceil(crop.bottom * sy).toInt().coerceIn(t + 1, aiMask.height)
                Bitmap.createBitmap(aiMask, l, t, r - l, b - t)
            } else aiMask
            val scaled = Bitmap.createScaledBitmap(part, w, h, true)
            try {
                val px = IntArray(w)
                val out = ByteArray(w * h)
                for (y in 0 until h) {
                    scaled.getPixels(px, 0, w, 0, y, w, 1)
                    for (x in 0 until w) out[y * w + x] = (px[x] ushr 24).toByte()
                }
                out
            } finally {
                if (scaled !== part && scaled !== aiMask) scaled.recycle()
                if (part !== aiMask) part.recycle()
            }
        }
    }

    fun invertSelection() {
        if (_uiState.value.selectionMask == null) return
        mutateSelectionAsync(R.string.status_inverting_selection, R.string.status_invert_failed) { mask ->
            SelectionEngine.invertMask(mask)
        }
    }

    fun selectAll() {
        if (_uiState.value.selectionMask == null) return
        mutateSelectionAsync { mask -> SelectionEngine.selectAll(mask) }
    }

    fun clearSelection() {
        // Adjusting the whole layer now: an unused selection Adjustment layer goes away.
        if (selectionLookId != null) {
            endSelectionLook()
            // Still open in the Adjust panel: it keeps working on the layer below.
        }
        val proj = _uiState.value.currentProject ?: return
        val w = proj.width
        val h = proj.height
        replaceSelectionAsync { SelectionEngine.createBlankMask(w, h) }
    }

    /** Copies the selected pixels of the selected image layer into a new layer placed exactly over them. */
    /** Edit > Clear: the selected pixels of the selected image layer become transparent. */
    fun deleteSelectedPixels() = selectionPixelEdit("clear", R.string.status_selection_cleared) { px, w, h, m, st, _ ->
        com.example.engine.SelectionOps.erase(px, w, h, m, st)
    }

    /** Edit > Fill: [color] at [opacity] inside the selection. */
    fun fillSelection(color: Int, opacity: Float) = selectionPixelEdit("fill", R.string.status_selection_filled) { px, w, h, m, st, _ ->
        com.example.engine.SelectionOps.fill(px, w, h, m, st, color or (0xFF shl 24), opacity)
    }

    /** Edit > Stroke: a line [widthDoc] document pixels wide along the selection edge. */
    fun strokeSelection(color: Int, widthDoc: Float, position: com.example.engine.StrokePosition, opacity: Float) =
        selectionPixelEdit("stroke", R.string.status_selection_stroked) { px, w, h, m, st, perDoc ->
            com.example.engine.SelectionOps.stroke(px, w, h, m, st, color or (0xFF shl 24), widthDoc * perDoc, position, opacity)
        }

    /**
     * Runs [edit] on the selected image layer's full-resolution pixels with the selection on them
     * (alpha bytes, stride, layer pixels per document pixel), as one undo step.
     */
    private fun selectionPixelEdit(
        prefix: String, @StringRes done: Int,
        edit: (IntArray, Int, Int, ByteArray, Int, Float) -> Unit
    ) {
        val proj = _uiState.value.currentProject ?: return
        if (_uiState.value.isProcessing) return
        val idx = _uiState.value.selectedLayerIndex
        val layer = proj.layers.getOrNull(idx) ?: return
        val mask = _uiState.value.selectionMask?.takeIf { !it.isRecycled && SelectionEngine.hasSelection(it) }
        if (mask == null) { _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_nothing_selected)); return }
        if (layer.isLocked) { _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_layer_locked)); return }
        if (layer.type != LayerType.IMAGE || layer.bitmap == null) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.effect_needs_image_layer)); return
        }
        if (pendingTransactionSnapshot != null) saveHistoryState()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_processing))
            var src: Bitmap? = null
            var local: Bitmap? = null
            var out: Bitmap? = null
            var historySaved = false
            try {
                src = loadAuthoritativeLayerBitmap(layer) ?: throw IllegalStateException("Image unavailable")
                val work = workLayerFor(layer, src!!)
                out = withContext(Dispatchers.Default) {
                    local = SelectionEngine.mapDocumentMaskToLayerLocal(mask, work, proj.width, proj.height)
                    val (mb, stride) = SelectionEngine.readAlphaBytes(local!!)
                    val w = src!!.width; val h = src!!.height
                    val px = IntArray(w * h); src!!.getPixels(px, 0, w, 0, 0, w, h)
                    edit(px, w, h, mb, stride, w / layer.intrinsicDocumentWidth().coerceAtLeast(1f))
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
                }
                saveHistoryState()
                historySaved = true
                if (!commitDestructiveBitmap(proj, layer, out!!, layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight(), prefix, keepBefore = true)) {
                    throw java.io.IOException("Save failed")
                }
                commitProjectMutation(statusMessage = tr(done), transformState = { it.copy(isProcessing = false) })
            } catch (e: OutOfMemoryError) {
                if (historySaved) rollbackToLastHistoryState()
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_processing_failed_memory))
            } catch (e: Exception) {
                if (historySaved) rollbackToLastHistoryState()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_effect_failed))
            } finally {
                local?.let { if (!it.isRecycled) it.recycle() }
                src?.let { if (it !== layer.bitmap && !it.isRecycled) it.recycle() }
                out?.let { if (it !== layer.bitmap && !it.isRecycled) it.recycle() }
            }
        }
    }

    fun copySelectionToNewLayer() = selectionToNewLayer(cut = false)

    /** Layer via Cut: the selected pixels move to a new layer (and leave a hole in this one). */
    fun cutSelectionToNewLayer() = selectionToNewLayer(cut = true)

    /**
     * Transforming with a selection on an image layer: the selected pixels are cut to their own
     * layer first, so moving, scaling, rotating or flipping moves only them (as in Photoshop).
     */
    fun floatSelectionForTransform() {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(_uiState.value.selectedLayerIndex) ?: return
        val sel = _uiState.value.selectionMask?.takeIf { !it.isRecycled && SelectionEngine.hasSelection(it) } ?: return
        if (layer.type != LayerType.IMAGE || layer.isLocked || sel.isRecycled) return
        selectionToNewLayer(cut = true, status = R.string.status_selection_floated)
    }

    private fun selectionToNewLayer(cut: Boolean, @StringRes status: Int = if (cut) R.string.status_cut_new_layer else R.string.status_copied_new_layer) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx !in proj.layers.indices) return
        val layer = proj.layers[idx]
        val mask = _uiState.value.selectionMask ?: return
        if (layer.type != LayerType.IMAGE || !SelectionEngine.hasSelection(mask)) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_nothing_selected))
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_copying_selection))
            var src: Bitmap? = null
            var local: Bitmap? = null
            var copy: Bitmap? = null
            try {
                src = loadAuthoritativeLayerBitmap(layer) ?: throw IllegalStateException("Image unavailable")
                val work = workLayerFor(layer, src!!)
                local = withContext(Dispatchers.Default) {
                    SelectionEngine.mapDocumentMaskToLayerLocal(mask, work, proj.width, proj.height)
                }
                copy = withContext(Dispatchers.Default) { SelectionEngine.copySelectedToNewBitmap(src!!, local!!) }
                    ?: throw IllegalStateException("No pixels selected")
                if (cut && layer.isLocked) throw IllegalStateException("Locked")
                // Layer via Cut: what was copied is removed from the source layer.
                val holed = if (cut) withContext(Dispatchers.Default) {
                    val (mb, stride) = SelectionEngine.readAlphaBytes(local!!)
                    val w = src!!.width; val h = src!!.height
                    val px = IntArray(w * h); src!!.getPixels(px, 0, w, 0, 0, w, h)
                    com.example.engine.SelectionOps.erase(px, w, h, mb, stride)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
                } else null
                // Same placement and look as the source layer (skew / distort included), so the
                // copy sits exactly over the pixels it came from.
                val n = Layer(
                    id = java.util.UUID.randomUUID().toString(),
                    name = tr(if (cut) R.string.layer_name_cut else R.string.layer_name_selection),
                    type = LayerType.IMAGE,
                    exifTags = layer.exifTags,
                    positionX = layer.positionX,
                    positionY = layer.positionY,
                    scaleX = layer.scaleX,
                    scaleY = layer.scaleY,
                    rotation = layer.rotation,
                    skewX = layer.skewX,
                    skewY = layer.skewY,
                    distort = layer.distort?.copyOf(),
                    opacity = layer.opacity,
                    blendMode = layer.blendMode,
                    filter = layer.filter,
                    filterIntensity = layer.filterIntensity,
                    lutId = layer.lutId,
                    adjustments = layer.adjustments.copy(),
                    contentWidth = layer.intrinsicDocumentWidth(),
                    contentHeight = layer.intrinsicDocumentHeight()
                )
                if (!commitDestructiveBitmap(proj, n, copy!!, n.contentWidth, n.contentHeight, "selection")) {
                    throw java.io.IOException("Save failed")
                }
                saveHistoryState()
                if (holed != null) {
                    val ok = commitDestructiveBitmap(proj, layer, holed, layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight(), "cut", keepBefore = true)
                    if (holed !== layer.bitmap && !holed.isRecycled) holed.recycle()
                    if (!ok) { rollbackToLastHistoryState(); throw java.io.IOException("Save failed") }
                }
                proj.layers.add(idx + 1, n)
                commitProjectMutation(
                    selectedLayerIndex = idx + 1,
                    statusMessage = tr(status),
                    transformState = { it.copy(selectionMask = SelectionEngine.createBlankMask(proj.width, proj.height), isProcessing = false) }
                )
                clearSelectionHistory()
            } catch (e: OutOfMemoryError) {
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_processing_failed_memory))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_copy_failed))
            } finally {
                // The new layer keeps its own scaled copy, so these temporaries can all go.
                local?.let { if (!it.isRecycled) it.recycle() }
                src?.let { if (!it.isRecycled) it.recycle() }
                copy?.let { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    // --- Exporting ---

    /** Kept for callers of the old API: a whole-image JPG / PNG export to the gallery. */
    fun exportImage(isPng: Boolean, quality: Int) {
        exportImage(
            _uiState.value.exportSettings.copy(
                format = if (isPng) com.example.engine.ExportFormat.PNG else com.example.engine.ExportFormat.JPEG,
                quality = quality,
                scope = com.example.engine.ExportScope.WHOLE,
                destination = com.example.engine.ExportDestination.GALLERY
            )
        )
    }

    /** Opening the export dialog: project defaults (file name, transparent background). */
    fun prepareExportSettings() {
        val proj = _uiState.value.currentProject ?: return
        val cur = _uiState.value.exportSettings
        val transparentDefault = com.example.engine.ExportEngine.defaultTransparent(proj.layers)
        _uiState.value = _uiState.value.copy(
            exportSettings = cur.copy(
                fileName = com.example.engine.ExportEngine.safeFileName(proj.name),
                transparentBackground = if (transparentDefault) true else cur.transparentBackground,
                scope = if (cur.scope == com.example.engine.ExportScope.SELECTION) com.example.engine.ExportScope.WHOLE else cur.scope
            ),
            exportEstimate = null
        )
    }

    fun updateExportSettings(settings: com.example.engine.ExportSettings) {
        _uiState.value = _uiState.value.copy(exportSettings = settings)
        try { prefs.edit().putString("export_settings_v1", settings.toJson()).apply() } catch (_: Exception) {}
    }

    private var exportEstimateJob: Job? = null

    fun clearExportEstimate() {
        exportEstimateJob?.cancel()
        _uiState.value = _uiState.value.copy(exportEstimate = null)
    }

    /**
     * Output size, estimated file size and a 1:1 compression preview, from a test encode of a
     * reduced render (debounced while settings change).
     */
    fun requestExportEstimate(settings: com.example.engine.ExportSettings) {
        val proj = _uiState.value.currentProject ?: return
        val selection = _uiState.value.selectionMask
        exportEstimateJob?.cancel()
        exportEstimateJob = viewModelScope.launch {
            delay(250)
            val est = try {
                withContext(Dispatchers.Default) {
                    val E = com.example.engine.ExportEngine
                    val alpha = settings.format.supportsAlpha
                    val groups = if (settings.scope == com.example.engine.ExportScope.LAYERS) E.layerGroups(proj.layers).size else 1
                    val layers = E.layersForExport(proj.layers, settings.transparentBackground && alpha)
                    val sample = LayerRenderer.renderCompositeCanvas(
                        getApplication(), layers, proj.width, proj.height,
                        includeBackground = true, backgroundColor = null, maxPreviewDimension = 1600
                    )
                    val temps = java.util.IdentityHashMap<Bitmap, Boolean>()
                    temps[sample] = true
                    try {
                        val sampleScale = sample.width.toFloat() / proj.width.coerceAtLeast(1)
                        var img = sample
                        var docW = proj.width
                        var docH = proj.height
                        if (settings.scope == com.example.engine.ExportScope.SELECTION && selection != null && !selection.isRecycled) {
                            E.selectionOnly(sample, selection)?.let { cropped ->
                                temps[cropped] = true
                                img = cropped
                                docW = (cropped.width / sampleScale).roundToInt().coerceAtLeast(1)
                                docH = (cropped.height / sampleScale).roundToInt().coerceAtLeast(1)
                            }
                        }
                        val (ow, oh) = E.outputSize(docW, docH, settings)
                        val sized = if (ow < img.width) E.resize(img, ow, oh, settings.sharpenOnResize).also { temps[it] = true } else img
                        val enc = if (!alpha) E.flatten(sized, settings.jpegBackground).also { temps[it] = true } else sized
                        val bytes = java.io.ByteArrayOutputStream()
                        E.encode(enc, settings.format, settings.quality, bytes)
                        val total = E.estimateBytes(bytes.size().toLong(), enc.width, enc.height, ow, oh) * groups
                        val preview = if (settings.format.lossy) E.compressionPreview(enc, settings.format, settings.quality, 220, settings.jpegBackground) else null
                        com.example.ui.components.ExportEstimate(ow, oh, total, preview, groups)
                    } finally {
                        temps.keys.forEach { if (!it.isRecycled) it.recycle() }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e is OutOfMemoryError) System.gc()
                null
            }
            _uiState.value = _uiState.value.copy(exportEstimate = est)
        }
    }

    private var exportJob: Job? = null

    /** Cancels a running export (nothing is saved). */
    fun cancelExport() {
        exportJob?.cancel()
    }

    /**
     * Exports with [settings]: renders at full resolution (smaller only when memory is short, and
     * then says so), crops to the selection or splits into layers when asked, resizes with output
     * sharpening, flattens for JPEG, encodes, writes metadata and saves to the gallery, to the
     * document [target] or into the folder [treeUri]. Progress and cancel are shown in the top bar.
     */
    fun exportImage(settings: com.example.engine.ExportSettings, target: Uri? = null, treeUri: Uri? = null) {
        val proj = _uiState.value.currentProject ?: return
        if (_uiState.value.isProcessing) return
        val E = com.example.engine.ExportEngine
        val selection = _uiState.value.selectionMask
        if (settings.scope == com.example.engine.ExportScope.SELECTION &&
            (selection == null || selection.isRecycled || !SelectionEngine.hasSelection(selection))
        ) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_nothing_selected))
            return
        }
        updateExportSettings(settings)
        exportJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProcessing = true, exportProgress = 0f, statusMessage = tr(R.string.status_exporting),
                exportSuccessUri = null, exportSuccessUris = emptyList()
            )
            // Full-resolution sources of the latest retouch strokes must be on disk first.
            awaitRetouchWrites()
            val job = currentCoroutineContext()[Job]
            val cancelled = { job?.isCancelled == true }
            fun progress(f: Float) {
                _uiState.value = _uiState.value.copy(exportProgress = f.coerceIn(0f, 0.99f))
            }
            val alpha = settings.format.supportsAlpha
            val baseName = E.safeFileName(settings.fileName.ifBlank { proj.name })
            // The photo's metadata: kept on the layer since import (older projects: read from the
            // layer's source file, which is still the original photo until a destructive edit).
            val metaLayer = proj.layers.firstOrNull { it.type == LayerType.IMAGE && (it.exifTags != null || it.sourceAssetPath != null) }
            val metaTags = metaLayer?.exifTags
                ?: metaLayer?.sourceAssetPath?.let { path -> withContext(Dispatchers.IO) { E.readMetadata(File(path)) } }
            val saved = ArrayList<Uri>()
            var reducedTo: String? = null
            try {
                val jobs: List<Pair<String, List<Layer>>> = if (settings.scope == com.example.engine.ExportScope.LAYERS) {
                    E.layerGroups(proj.layers).mapIndexed { i, g -> "${baseName}_${i + 1}_${E.safeFileName(g[0].name, "layer")}" to g }
                } else {
                    listOf(baseName to E.layersForExport(proj.layers, settings.transparentBackground && alpha))
                }
                if (jobs.isEmpty()) throw IllegalStateException("Nothing to export")
                for ((index, entry) in jobs.withIndex()) {
                    val (name, layers) = entry
                    val p0 = index.toFloat() / jobs.size
                    val span = 1f / jobs.size
                    // 1. Render (full resolution; smaller steps only when memory is short).
                    var composite: Bitmap? = null
                    var renderScale = 1f
                    for (attempt in floatArrayOf(1f, 0.7f, 0.5f, 0.35f)) {
                        try {
                            val maxDim = if (attempt >= 1f) null else (max(proj.width, proj.height) * attempt).toInt().coerceAtLeast(64)
                            composite = withContext(Dispatchers.Default) {
                                LayerRenderer.renderCompositeCanvas(
                                    getApplication(), layers, proj.width, proj.height,
                                    includeBackground = true,
                                    backgroundColor = if (!alpha && settings.scope == com.example.engine.ExportScope.WHOLE) settings.jpegBackground else null,
                                    maxPreviewDimension = maxDim
                                )
                            }
                            renderScale = attempt
                            break
                        } catch (e: OutOfMemoryError) {
                            System.gc()
                        }
                    }
                    val comp = composite ?: throw OutOfMemoryError("export render")
                    progress(p0 + span * 0.5f)
                    val temps = java.util.IdentityHashMap<Bitmap, Boolean>()
                    temps[comp] = true
                    val tmp = File(getApplication<Application>().cacheDir, "export_${System.nanoTime()}.${settings.format.extension}")
                    try {
                        // 2. Selection crop.
                        var img = comp
                        if (settings.scope == com.example.engine.ExportScope.SELECTION) {
                            img = withContext(Dispatchers.Default) { E.selectionOnly(comp, selection!!) } ?: throw IllegalStateException("Empty selection")
                            temps[img] = true
                        }
                        // 3. Size (in document pixels; never upscale a memory-reduced render).
                        val docW = (img.width / renderScale).roundToInt().coerceAtLeast(1)
                        val docH = (img.height / renderScale).roundToInt().coerceAtLeast(1)
                        var (ow, oh) = E.outputSize(docW, docH, settings)
                        if (renderScale < 1f && (ow > img.width || oh > img.height)) {
                            ow = img.width; oh = img.height
                            reducedTo = "${ow} × ${oh}"
                        }
                        val sized = withContext(Dispatchers.Default) { E.resize(img, ow, oh, settings.sharpenOnResize, cancelled) }
                        temps[sized] = true
                        progress(p0 + span * 0.7f)
                        // 4. JPEG has no transparency: flatten over the chosen colour.
                        val final = if (!alpha) withContext(Dispatchers.Default) { E.flatten(sized, settings.jpegBackground) }.also { temps[it] = true } else sized
                        // 5. Encode, metadata, save.
                        val ok = withContext(Dispatchers.IO) { tmp.outputStream().use { E.encode(final, settings.format, settings.quality, it) } }
                        if (!ok) throw java.io.IOException("Encoding failed")
                        if (settings.scope != com.example.engine.ExportScope.LAYERS) {
                            withContext(Dispatchers.IO) { E.writeMetadata(metaTags, tmp, settings.metadata, final.width, final.height) }
                        }
                        progress(p0 + span * 0.9f)
                        val uri = repository.saveExportFile(
                            tmp, "$name.${settings.format.extension}", settings.format.mimeType,
                            target = if (jobs.size == 1) target else null,
                            treeUri = treeUri
                        ) ?: throw java.io.IOException("Save failed")
                        saved += uri
                    } finally {
                        temps.keys.forEach { if (!it.isRecycled) it.recycle() }
                        tmp.delete()
                    }
                    progress(p0 + span)
                }
                // Success is announced by the editor's "Exported • Share" snackbar (driven by exportSuccessUri).
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    exportProgress = null,
                    exportSuccessUri = saved.firstOrNull(),
                    exportSuccessUris = saved.toList(),
                    statusMessage = reducedTo?.let { tr(R.string.export_reduced_memory, it) }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.value = _uiState.value.copy(isProcessing = false, exportProgress = null, statusMessage = tr(R.string.export_cancelled))
                throw e
            } catch (e: OutOfMemoryError) {
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, exportProgress = null, statusMessage = tr(R.string.status_processing_failed_memory))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false, exportProgress = null,
                    exportSuccessUri = saved.firstOrNull(), exportSuccessUris = saved.toList(),
                    statusMessage = tr(R.string.export_failed)
                )
            }
        }
    }

    fun consumeExportResult() {
        _uiState.value = _uiState.value.copy(exportSuccessUri = null, exportSuccessUris = emptyList())
    }

    /** Renames the open project (persisted by autosave) or a closed one (manifest-only rewrite). */
    fun renameProject(projectId: String, newName: String) {
        val name = newName.trim().take(80)
        if (name.isEmpty()) return
        val current = _uiState.value.currentProject
        if (current != null && current.id == projectId) {
            current.name = name
            commitProjectMutation(statusMessage = tr(R.string.status_project_renamed))
            return
        }
        viewModelScope.launch {
            val ok = repository.renameProject(projectId, name)
            _uiState.value = _uiState.value.copy(
                statusMessage = if (ok) tr(R.string.status_project_renamed) else tr(R.string.status_save_failed_storage)
            )
            loadProjects()
        }
    }

    fun clearStatusMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    fun notifyStoragePermissionDenied() {
        _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_storage_permission_denied))
    }
}
