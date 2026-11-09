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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.math.max
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
    val refinePreviewMode: RefinePreviewMode = RefinePreviewMode.OVERLAY,
    /** User filter library: imported LUTs, saved looks and favourite filter keys. */
    val luts: List<com.example.engine.LutInfo> = emptyList(),
    val customPresets: List<com.example.engine.CustomPreset> = emptyList(),
    val favoriteFilters: Set<String> = emptySet()
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
        if (!selectionHistory.canUndo) return
        moveLastKind(historyOrder, redoOrder, HistoryKind.SELECTION)
        viewModelScope.launch {
            selectionMutex.withLock {
                endTransientSelectionSessions()
                val current = _uiState.value.selectionMask ?: return@withLock
                val restored = withContext(Dispatchers.Default) { selectionHistory.undo(current) } ?: return@withLock
                publishSelection(restored)
                updateSelectionHistoryFlags()
            }
        }
    }

    fun redoSelection() {
        if (!selectionHistory.canRedo) return
        moveLastKind(redoOrder, historyOrder, HistoryKind.SELECTION)
        viewModelScope.launch {
            selectionMutex.withLock {
                endTransientSelectionSessions()
                val current = _uiState.value.selectionMask ?: return@withLock
                val restored = withContext(Dispatchers.Default) { selectionHistory.redo(current) } ?: return@withLock
                publishSelection(restored)
                updateSelectionHistoryFlags()
            }
        }
    }

    /** Publishes a new selection mask object and invalidates everything that depends on it. */
    private fun publishSelection(mask: Bitmap, keepEditableShape: Boolean = false) {
        _uiState.value = _uiState.value.copy(
            selectionMask = mask,
            selectionRevision = _uiState.value.selectionRevision + 1,
            renderRevision = _uiState.value.renderRevision + 1,
            editableShape = if (keepEditableShape) _uiState.value.editableShape else null
        )
    }

    init {
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
        proj.markDirty()
        markDirty()
        val newState = transformState(
            _uiState.value.copy(
                currentProject = proj.copy(layers = ArrayList(proj.layers)),
                renderRevision = proj.renderRevision,
                selectedLayerIndex = selectedLayerIndex ?: _uiState.value.selectedLayerIndex,
                statusMessage = statusMessage ?: _uiState.value.statusMessage
            )
        )
        _uiState.value = newState
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(3000L) // 3 seconds debounced autosave
            // Do not autosave while an asynchronous long-running operation is in flight
            if (_uiState.value.isProcessing || pendingTransactionSnapshot != null) {
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

            repository.saveProject(proj)
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

    fun selectTool(tool: ActiveTool) {
        val previous = _uiState.value.activeTool
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
    }

    /** Restore the state captured at the beginning of the current transient edit. */
    fun cancelHistoryTransaction() {
        val snapshot = pendingTransactionSnapshot ?: return
        val proj = _uiState.value.currentProject ?: run { pendingTransactionSnapshot = null; return }
        proj.width = snapshot.width
        proj.height = snapshot.height
        proj.layers.clear()
        proj.layers.addAll(snapshot.layers.map { it.snapshotLayer() })
        proj.markDirty()
        pendingTransactionSnapshot = null
        val wasDirty = pendingTransactionWasDirty
        pendingTransactionWasDirty = false
        _uiState.value = _uiState.value.copy(
            currentProject = proj.copy(layers = ArrayList(proj.layers)),
            renderRevision = proj.renderRevision,
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

    private fun calculateSnapshotMemoryBytes(snapshot: ProjectSnapshot): Long {
        var bytes = 0L
        for (l in snapshot.layers) {
            val bmp = l.bitmap
            if (bmp != null && !bmp.isRecycled) {
                bytes += bmp.allocationByteCount.toLong()
            }
        }
        return bytes
    }

    private fun enforceHistoryMemoryBudget() {
        // Enforce maximum stack steps
        while (undoStack.size > MAX_HISTORY_STEPS) {
            undoStack.removeAt(0)
        }

        // Calculate total memory held across all undo snapshots
        var totalBytes = undoStack.sumOf { calculateSnapshotMemoryBytes(it) }

        // If history exceeds RAM budget (80MB), evict oldest snapshots until budget is respected
        while (undoStack.size > 2 && totalBytes > MAX_HISTORY_MEMORY_BYTES) {
            val evicted = undoStack.removeAt(0)
            totalBytes -= calculateSnapshotMemoryBytes(evicted)
        }
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
            pendingTransactionSnapshot = null
            pendingTransactionWasDirty = false
            proj.width = snapshot.width
            proj.height = snapshot.height
            proj.layers.clear()
            proj.layers.addAll(snapshot.layers)
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

        when (nextKind(historyOrder, undoStack.isNotEmpty(), selectionHistory.canUndo)) {
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
        proj.layers.clear()
        proj.layers.addAll(previousSnapshot.layers)
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
        when (nextKind(redoOrder, redoStack.isNotEmpty(), selectionHistory.canRedo)) {
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
        proj.layers.clear()
        proj.layers.addAll(nextSnapshot.layers)
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
        proj.layers.clear()
        proj.layers.addAll(snapshot.layers)
        proj.markDirty()
        _uiState.value = _uiState.value.copy(
            currentProject = proj.copy(layers = ArrayList(proj.layers)),
            renderRevision = proj.renderRevision
        )
        updateHistoryFlags()
    }

    /** Source asset files still referenced by undo/redo history must survive orphan cleanup. */
    private fun historyProtectedFileNames(): Set<String> {
        val names = mutableSetOf<String>()
        fun collect(snapshot: ProjectSnapshot?) {
            snapshot?.layers?.forEach { l -> l.sourceAssetPath?.let { names.add(File(it).name) } }
        }
        undoStack.forEach { collect(it) }
        redoStack.forEach { collect(it) }
        collect(pendingTransactionSnapshot)
        return names
    }

    private suspend fun persistProject(proj: Project): Boolean {
        val ok = repository.saveProject(proj, historyProtectedFileNames())
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
            canUndo = undoStack.isNotEmpty() || pendingTransactionSnapshot != null || selectionHistory.canUndo,
            canRedo = redoStack.isNotEmpty() || selectionHistory.canRedo,
            canUndoSelection = selectionHistory.canUndo,
            canRedoSelection = selectionHistory.canRedo
        )
    }

    private suspend fun loadAuthoritativeLayerBitmap(layer: Layer): Bitmap? = withContext(Dispatchers.IO) {
        val maxDim = kotlin.math.max(layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight()).toInt().coerceIn(64, 4096)
        layer.sourceAssetPath?.let { path -> File(path).takeIf { it.exists() }?.let { ImageProcessor.decodeFileWithExifSampled(it, maxDim) }?.let { return@withContext it } }
        layer.bitmap?.copy(Bitmap.Config.ARGB_8888, true)
    }

    private suspend fun commitDestructiveBitmap(project: Project, layer: Layer, full: Bitmap, contentW: Float = layer.intrinsicDocumentWidth(), contentH: Float = layer.intrinsicDocumentHeight(), prefix: String = "edit"): Boolean {
        val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "png"
        val file = File(repository.getProjectDir(project.id), "source_${prefix}_${layer.id}_${System.currentTimeMillis()}.$ext")
        if (!withContext(Dispatchers.IO) { ImageProcessor.saveLosslessBitmap(full, file) }) return false
        val preview = withContext(Dispatchers.Default) { ImageProcessor.scaleBitmapToMaxDimension(full, 2048) }
        layer.bitmap = preview; layer.sourceAssetPath=file.absolutePath; layer.originalWidth=full.width; layer.originalHeight=full.height
        layer.contentWidth=contentW.coerceAtLeast(1f); layer.contentHeight=contentH.coerceAtLeast(1f); layer.isBitmapDirty=true
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
                    val layer = Layer(
                        id = layerId,
                        name = tr(R.string.layer_name_imported),
                        type = LayerType.IMAGE,
                        bitmap = bmp,
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

                    val bgLayer = Layer(
                        id = java.util.UUID.randomUUID().toString(),
                        name = tr(R.string.layer_name_background),
                        type = LayerType.COLOR_FILL,
                        colorFillHex = 0xFF12131C.toInt()
                    )
                    proj.layers.add(bgLayer)

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
                        originalWidth = origW,
                        originalHeight = origH,
                        contentWidth = safeW.toFloat(),
                        contentHeight = safeH.toFloat()
                    )
                    proj.layers.add(imageLayer)

                    repository.saveProject(proj)
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

    fun addAdjustmentLayer() {
        val proj = _uiState.value.currentProject ?: return
        saveHistoryState()

        val layer = Layer(
            id = java.util.UUID.randomUUID().toString(),
            name = tr(R.string.layer_name_adjustment, proj.layers.count { it.type == LayerType.ADJUSTMENT } + 1),
            type = LayerType.ADJUSTMENT,
            adjustments = ColorAdjustments()
        )
        proj.layers.add(layer)
        commitProjectMutation(
            selectedLayerIndex = proj.layers.size - 1,
            statusMessage = tr(R.string.status_adjustment_added)
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
            action(layer)
            commitProjectMutation()
        }
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
            action(layer)
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

    fun deleteSelectedLayer() {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx in proj.layers.indices && proj.layers.size > 1) {
            val layer = proj.layers[idx]
            if (layer.isLocked) {
                _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_locked_delete))
                return
            }
            saveHistoryState()
            proj.layers.removeAt(idx)
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

            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_duplicating_layer))
                try {
                    val copied = if (layer.bitmap != null) {
                        withContext(Dispatchers.Default) { layer.duplicateLayer() }
                    } else {
                        layer.duplicateLayer()
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

    /** Drag-and-drop reorder: moves the layer at [from] to [to] (bottom = 0). */
    fun moveLayer(from: Int, to: Int) {
        val proj = _uiState.value.currentProject ?: return
        if (from !in proj.layers.indices || to !in proj.layers.indices || from == to) return
        saveHistoryState()
        val selectedId = proj.layers.getOrNull(_uiState.value.selectedLayerIndex)?.id
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
                    bottomLayer.filter = FilterType.NONE
                    bottomLayer.filterIntensity = 1f
                    bottomLayer.adjustments = ColorAdjustments()
                    // Both layers' masks are already baked into the merged pixels.
                    bottomLayer.maskBitmap = null
                    bottomLayer.isEditingMask = false
                    bottomLayer.isMaskEnabled = true
                    bottomLayer.maskDensity = 1f
                    bottomLayer.maskFeather = 0f

                    proj.layers.removeAt(idx)
                    commitProjectMutation(
                        selectedLayerIndex = idx - 1,
                        statusMessage = tr(R.string.status_layers_merged),
                        transformState = { it.copy(isProcessing = false) }
                    )
                } catch (e: OutOfMemoryError) {
                    System.gc()
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = tr(R.string.status_merge_failed)
                    )
                } catch (e: Exception) {
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
                        // The history snapshot holds its own mask copy; the live one is just replaced.
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
                src = withContext(Dispatchers.Default) {
                    val own = layer.bitmap?.takeIf { layer.type == LayerType.IMAGE && !it.isRecycled }
                    own?.let { ImageProcessor.scaleBitmapToMaxDimension(it, 512) } ?: LayerRenderer.renderCompositeCanvas(
                        getApplication(),
                        proj.layers.subList(0, idx),
                        proj.width,
                        proj.height,
                        includeBackground = true,
                        maxPreviewDimension = 512
                    )
                }
                val delta = withContext(Dispatchers.Default) {
                    ImageProcessor.computeSmartAutoAdjustments(src!!, layer.adjustments)
                }
                saveHistoryState()
                layer.adjustments = ImageProcessor.combineSmartAuto(layer.adjustments, delta, 1f)
                smartAutoSession = SmartAutoSession(layer.id, delta, 1f)
                commitProjectMutation(
                    statusMessage = tr(if (delta.isIdentity()) R.string.status_smart_auto_nothing else R.string.status_smart_auto_applied),
                    transformState = { it.copy(isProcessing = false, smartAutoStrength = if (delta.isIdentity()) null else 1f) }
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
                if (!commitDestructiveBitmap(proj, layer, out!!, layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight(), "content_aware")) {
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
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_processing_failed_memory))
            } catch (e: Exception) {
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
                rememberSelectionState()
                if (progressRes != null) {
                    _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(progressRes))
                }
                try {
                    val working = withContext(Dispatchers.Default) {
                        base.copy(Bitmap.Config.ALPHA_8, true).also { block(it) }
                    }
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
                if (!commitDestructiveBitmap(proj, layer, result!!, layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight(), "apply_mask")) {
                    throw java.io.IOException("Failed to persist masked layer")
                }
                // The history snapshot owns its own mask copy; the live mask is simply dropped.
                layer.maskBitmap = null
                layer.isEditingMask = false
                layer.maskDensity = 1f
                layer.maskFeather = 0f
                commitProjectMutation(
                    statusMessage = tr(R.string.status_layer_mask_applied),
                    transformState = { it.copy(isProcessing = false) }
                )
            } catch (e: OutOfMemoryError) {
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_processing_failed_memory))
            } catch (e: Exception) {
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
                    val d = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (d >= radius) continue
                    val f = if (d <= inner) 1f else {
                        val t = ((d - inner) / falloffSpan).coerceIn(0f, 1f)
                        1f - t * t * (3f - 2f * t)
                    }
                    val sel = stroke.selection?.let { (it[y * stroke.selectionRowBytes + x].toInt() and 0xFF) / 255f } ?: 1f
                    val c = (f * opacity * sel * 255f).toInt()
                    if (c > (stroke.coverage[row + x].toInt() and 0xFF)) stroke.coverage[row + x] = c.toByte()
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
        commitProjectMutation()
    }

    /** Ends the current mask stroke and frees its buffers. */
    fun endMaskStroke() {
        maskStroke = null
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
                if (!commitDestructiveBitmap(proj, layer, finalProcessed, layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight(), effect.name.lowercase())) {
                    throw java.io.IOException("Save failed")
                }
                if (finalProcessed !== layer.bitmap && !finalProcessed.isRecycled) finalProcessed.recycle()
                commitProjectMutation(
                    statusMessage = tr(R.string.status_effect_applied),
                    transformState = { it.copy(isProcessing = false, proEffectProgress = null) }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    proEffectProgress = null,
                    statusMessage = tr(R.string.effect_cancelled)
                )
                throw e
            } catch (e: OutOfMemoryError) {
                System.gc()
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    proEffectProgress = null,
                    statusMessage = tr(R.string.status_processing_failed_memory)
                )
            } catch (e: Exception) {
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
        _uiState.value = _uiState.value.copy(refinePreview = null)
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
    fun requestRefinePreview(config: RefineEdgeConfig) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(_uiState.value.selectedLayerIndex) ?: return
        val proxy = layer.bitmap ?: return
        val docMask = _uiState.value.selectionMask ?: return
        refinePreviewJob?.cancel()
        refinePreviewJob = viewModelScope.launch {
            delay(180) // debounce slider drags
            val preview = try {
                withContext(Dispatchers.Default) { buildRefinePreview(proj, layer, proxy, docMask, config, _uiState.value.refinePreviewMode) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            _uiState.value = _uiState.value.copy(refinePreview = preview)
        }
    }

    private fun buildRefinePreview(
        proj: Project,
        layer: Layer,
        proxy: Bitmap,
        docMask: Bitmap,
        config: RefineEdgeConfig,
        mode: RefinePreviewMode
    ): Bitmap? {
        val local = SelectionEngine.mapDocumentMaskToLayerLocal(docMask, layer, proj.width, proj.height)
        try {
            val bounds = SelectionEngine.selectionBounds(local) ?: return null
            val margin = (max(bounds.width(), bounds.height()) * 0.15f).toInt() + 8
            val crop = android.graphics.Rect(
                (bounds.left - margin).coerceAtLeast(0),
                (bounds.top - margin).coerceAtLeast(0),
                (bounds.right + margin).coerceAtMost(proxy.width),
                (bounds.bottom + margin).coerceAtMost(proxy.height)
            )
            if (crop.width() < 2 || crop.height() < 2) return null
            val ratio = min(1f, 480f / max(crop.width(), crop.height()))
            val pw = (crop.width() * ratio).toInt().coerceAtLeast(2)
            val ph = (crop.height() * ratio).toInt().coerceAtLeast(2)
            val srcCrop = Bitmap.createBitmap(proxy, crop.left, crop.top, crop.width(), crop.height())
            val srcSmall = Bitmap.createScaledBitmap(srcCrop, pw, ph, true)
            val maskCrop = Bitmap.createBitmap(local, crop.left, crop.top, crop.width(), crop.height())
            val maskSmall = Bitmap.createScaledBitmap(maskCrop, pw, ph, true)

            // The real refine runs on the full-resolution source: scale pixel radii accordingly.
            val fullMax = max(layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight()).coerceAtLeast(1f)
            val scaleToPreview = (max(pw, ph).toFloat() / max(crop.width(), crop.height())) * (max(proxy.width, proxy.height) / fullMax)
            val previewConfig = config.copy(
                radiusPx = (config.radiusPx * scaleToPreview).toInt().coerceAtLeast(1),
                smoothPx = (config.smoothPx * scaleToPreview).toInt(),
                featherPx = config.featherPx * scaleToPreview
            )
            val result = RefineEdgeEngine.refineEdges(srcSmall, maskSmall, previewConfig)
            val shown = result.decontaminatedSource ?: srcSmall
            val src = IntArray(pw * ph)
            val alpha = IntArray(pw * ph)
            shown.getPixels(src, 0, pw, 0, 0, pw, ph)
            result.refinedMask.getPixels(alpha, 0, pw, 0, 0, pw, ph)
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
            listOf(srcCrop, srcSmall, maskCrop, maskSmall, result.refinedMask).forEach { if (it !== proxy && !it.isRecycled) it.recycle() }
            result.decontaminatedSource?.let { if (!it.isRecycled) it.recycle() }
            return bmp
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

    fun applyRefineEdgeMatting(config: RefineEdgeConfig) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx !in proj.layers.indices) return
        val layer = proj.layers[idx]
        val currentMask = _uiState.value.selectionMask ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.refine_hair_desc))
            var src: Bitmap? = null
            try {
                src = loadAuthoritativeLayerBitmap(layer) ?: throw IllegalStateException("Layer image unavailable")
                val workLayer = workLayerFor(layer, src!!)
                // Matting runs in layer-local pixel space: the document selection is mapped onto the
                // layer's source pixels and the refined result is mapped back into document space.
                val result = withContext(Dispatchers.Default) {
                    val localMask = SelectionEngine.mapDocumentMaskToLayerLocal(currentMask, workLayer, proj.width, proj.height)
                    try {
                        SelectionEngine.refineEdgesWithMatting(src!!, localMask, config)
                    } finally {
                        if (!localMask.isRecycled) localMask.recycle()
                    }
                }
                val refinedDocMask = withContext(Dispatchers.Default) {
                    try {
                        layerLocalMaskToDocument(result.refinedMask, workLayer, proj, base = currentMask)
                    } finally {
                        if (!result.refinedMask.isRecycled) result.refinedMask.recycle()
                    }
                }

                // Decontamination only runs when the user explicitly enabled it in the dialog.
                val decontaminated = result.decontaminatedSource
                if (decontaminated != null) {
                    saveHistoryState()
                    val committed = commitDestructiveBitmap(
                        proj,
                        layer,
                        decontaminated,
                        layer.intrinsicDocumentWidth(),
                        layer.intrinsicDocumentHeight(),
                        "refine_decontaminate"
                    )
                    if (decontaminated !== layer.bitmap && !decontaminated.isRecycled) decontaminated.recycle()
                    if (!committed) throw java.io.IOException("Failed to persist decontaminated layer")
                    commitProjectMutation()
                }

                selectionMutex.withLock {
                    endTransientSelectionSessions()
                    rememberSelectionState()
                    publishSelection(refinedDocMask)
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
        val proj = _uiState.value.currentProject ?: return
        val w = proj.width
        val h = proj.height
        replaceSelectionAsync { SelectionEngine.createBlankMask(w, h) }
    }

    fun copySelectionToNewLayer() {
        val proj=_uiState.value.currentProject?:return;val idx=_uiState.value.selectedLayerIndex;if(idx !in proj.layers.indices)return;val layer=proj.layers[idx];val mask=_uiState.value.selectionMask?:return
        if(layer.type!=LayerType.IMAGE||!SelectionEngine.hasSelection(mask)){_uiState.value=_uiState.value.copy(statusMessage=tr(R.string.status_nothing_selected));return}
        viewModelScope.launch{_uiState.value=_uiState.value.copy(isProcessing=true,statusMessage=tr(R.string.status_copying_selection));var src:Bitmap?=null;var local:Bitmap?=null;var copy:Bitmap?=null
            try{src=loadAuthoritativeLayerBitmap(layer)?:throw IllegalStateException("Image unavailable");val work=workLayerFor(layer,src!!);local=withContext(Dispatchers.Default){SelectionEngine.mapDocumentMaskToLayerLocal(mask,work,proj.width,proj.height)};copy=withContext(Dispatchers.Default){SelectionEngine.copySelectedToNewBitmap(src!!,local!!)}?:throw IllegalStateException("No pixels selected")
                val n=Layer(id=java.util.UUID.randomUUID().toString(),name=tr(R.string.layer_name_selection),type=LayerType.IMAGE,positionX=layer.positionX,positionY=layer.positionY,scaleX=layer.scaleX,scaleY=layer.scaleY,rotation=layer.rotation,opacity=layer.opacity,blendMode=layer.blendMode,filter=layer.filter,adjustments=layer.adjustments.copy(),contentWidth=layer.intrinsicDocumentWidth(),contentHeight=layer.intrinsicDocumentHeight())
                if(!commitDestructiveBitmap(proj,n,copy!!,n.contentWidth,n.contentHeight,"selection"))throw java.io.IOException("Save failed");saveHistoryState();proj.layers.add(idx+1,n);commitProjectMutation(selectedLayerIndex=idx+1,statusMessage=tr(R.string.status_copied_new_layer),transformState={it.copy(selectionMask=SelectionEngine.createBlankMask(proj.width,proj.height),isProcessing=false)}); clearSelectionHistory()}
            catch(e:Exception){_uiState.value=_uiState.value.copy(isProcessing=false,statusMessage=tr(R.string.status_copy_failed))}
            finally{local?.let{if(!it.isRecycled)it.recycle()};src?.let{if(!it.isRecycled)it.recycle()};copy?.let{if(!it.isRecycled)it.recycle()}}
        }
    }

    // --- Exporting ---
    fun exportImage(isPng: Boolean, quality: Int) {
        val proj = _uiState.value.currentProject ?: return
        if (_uiState.value.isProcessing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_exporting), exportSuccessUri = null)
            var compositeBmp: Bitmap? = null
            try {
                compositeBmp = withContext(Dispatchers.Default) {
                    LayerRenderer.renderCompositeCanvas(
                        getApplication(),
                        proj.layers,
                        proj.width,
                        proj.height,
                        backgroundColor = if (isPng) null else android.graphics.Color.WHITE,
                        maxPreviewDimension = null
                    )
                }

                // File names: keep letters/digits (any script), dash and underscore only.
                val safeName = proj.name.trim()
                    .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
                    .trim('_')
                    .ifEmpty { "PhotoEasy" }
                    .take(60)
                val exportUri = repository.exportToGallery(compositeBmp!!, safeName, isPng, quality)

                // Success is announced by the editor's "Exported • Share" snackbar (driven by exportSuccessUri).
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    exportSuccessUri = exportUri,
                    statusMessage = if (exportUri != null) null else tr(R.string.export_failed)
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_processing_failed_memory))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.export_failed))
            } finally {
                compositeBmp?.let { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    fun consumeExportResult() {
        _uiState.value = _uiState.value.copy(exportSuccessUri = null)
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
