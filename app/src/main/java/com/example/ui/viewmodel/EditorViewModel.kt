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
    val isQuickSelectionAutoEnhance: Boolean = true,
    val isQuickSelectionSampleAll: Boolean = false,
    val isMaskEditingBlack: Boolean = true,
    val inpaintSelectionMode: InpaintSelectionMode = InpaintSelectionMode.BRUSH,
    val inpaintEngineMode: InpaintEngineMode = InpaintEngineMode.AI_PATCH_SYNTHESIS,
    val isSmartEdgeSnapEnabled: Boolean = true,
    val isSeamlessBlendEnabled: Boolean = true,
    val showMaskRubyOverlay: Boolean = false,
    val maskBrushSizeDp: Float = 24f,
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
    /** Live preview image of the Refine Edge dialog and how it is displayed. */
    val refinePreview: Bitmap? = null,
    val refinePreviewMode: RefinePreviewMode = RefinePreviewMode.OVERLAY
)

data class EditableShape(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val isEllipse: Boolean
)

enum class SelectionModifierOp { FEATHER, GROW, SHRINK, SMOOTH }

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

    private fun updateSelectionHistoryFlags() {
        _uiState.value = _uiState.value.copy(
            canUndoSelection = selectionHistory.canUndo,
            canRedoSelection = selectionHistory.canRedo
        )
    }

    private fun clearSelectionHistory() {
        selectionHistory.clear()
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
        updateSelectionHistoryFlags()
    }

    fun undoSelection() {
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
            selectionMode = if (tool == ActiveTool.OBJECT_REMOVAL) SelectionMode.ADD else _uiState.value.selectionMode
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

        if (undoStack.isEmpty()) return
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
        if (redoStack.isEmpty()) return
        val proj = _uiState.value.currentProject ?: return
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
        _uiState.value = _uiState.value.copy(
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
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

    fun addTextLayer(text: String) {
        val proj = _uiState.value.currentProject ?: return
        saveHistoryState()

        val layer = Layer(
            id = java.util.UUID.randomUUID().toString(),
            name = if (text.isBlank()) tr(R.string.layer_empty_text) else tr(R.string.layer_name_text, text),
            type = LayerType.TEXT,
            textConfig = TextLayerConfig(text = text)
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
            saveHistoryState()
            layer.rotation = (layer.rotation + degrees) % 360f
            commitProjectMutation()
        }
    }

    fun setLayerRotation(degrees: Float) {
        updateSelectedLayerTransient { it.rotation = ((degrees % 360f) + 360f) % 360f }
    }

    fun resetSelectedLayerTransform() {
        updateSelectedLayer { it.positionX=0f; it.positionY=0f; it.scaleX=1f; it.scaleY=1f; it.rotation=0f }
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
        _uiState.value = _uiState.value.copy(selectedAspectRatio = ratio)
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

        // 1. Calculate natural dimensions from the perspective quadrilateral
        val topW = kotlin.math.hypot((corners[1].x - corners[0].x).toDouble(), (corners[1].y - corners[0].y).toDouble()).toFloat()
        val botW = kotlin.math.hypot((corners[2].x - corners[3].x).toDouble(), (corners[2].y - corners[3].y).toDouble()).toFloat()
        val leftH = kotlin.math.hypot((corners[3].x - corners[0].x).toDouble(), (corners[3].y - corners[0].y).toDouble()).toFloat()
        val rightH = kotlin.math.hypot((corners[2].x - corners[1].x).toDouble(), (corners[2].y - corners[1].y).toDouble()).toFloat()

        var targetW = kotlin.math.round(maxOf(topW, botW)).toInt().coerceIn(50, 8192)
        var targetH = kotlin.math.round(maxOf(leftH, rightH)).toInt().coerceIn(50, 8192)

        // Conform to selected aspect ratio if user chose one
        val aspectRatio = _uiState.value.selectedAspectRatio
        if (aspectRatio != null && aspectRatio > 0f) {
            if (aspectRatio >= targetW.toFloat() / targetH) {
                targetH = (targetW / aspectRatio).toInt().coerceIn(50, 8192)
            } else {
                targetW = (targetH * aspectRatio).toInt().coerceIn(50, 8192)
            }
        }

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
                    val canvasLayerBmp = withContext(Dispatchers.Default) {
                        val fullCanvas = Bitmap.createBitmap(oldW, oldH, Bitmap.Config.ARGB_8888)
                        val matrix = SelectionEngine.getLayerMatrix(workLayer, oldW, oldH)
                        Canvas(fullCanvas).drawBitmap(srcBmp, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                        fullCanvas
                    }
                    if (srcBmp !== layer.bitmap && !srcBmp.isRecycled) srcBmp.recycle()

                    val warpedBmp = withContext(Dispatchers.Default) {
                        ImageProcessor.transformPerspective(canvasLayerBmp, corners, targetW, targetH)
                    }
                    if (warpedBmp !== canvasLayerBmp && !canvasLayerBmp.isRecycled) canvasLayerBmp.recycle()

                    val oldMask = layer.maskBitmap
                    if (oldMask != null && !oldMask.isRecycled) {
                        val warpedMask = withContext(Dispatchers.Default) {
                            // Corners are document coordinates, so the mask must be document-sized.
                            val docMask = documentSizedMask(oldMask, oldW, oldH)
                            try {
                                ImageProcessor.transformPerspectiveAlpha(docMask, corners, targetW, targetH)
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
        val l = rect.left.toInt().coerceIn(0, oldW - 1)
        val t = rect.top.toInt().coerceIn(0, oldH - 1)
        val r = rect.right.toInt().coerceIn(l + 1, oldW)
        val b = rect.bottom.toInt().coerceIn(t + 1, oldH)
        val nw = r - l
        val nh = b - t
        if (nw == oldW && nh == oldH && l == 0 && t == 0) {
            _uiState.value = _uiState.value.copy(activeTool = ActiveTool.NONE, selectedAspectRatio = null)
            return
        }
        saveHistoryState()
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
        commitProjectMutation(
            statusMessage = tr(R.string.status_canvas_cropped),
            transformState = {
                it.copy(
                    activeTool = ActiveTool.NONE,
                    selectedAspectRatio = null,
                    selectionMask = SelectionEngine.createBlankMask(nw, nh),
                    isProcessing = false
                )
            }
        )
    }

    fun resetCrop() {
        _uiState.value = _uiState.value.copy(selectedAspectRatio = null)
    }

    // --- Smart Auto Correction ---
    fun applySmartAutoCorrection() {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx !in proj.layers.indices) return
        val layer = proj.layers[idx]
        if (layer.isLocked) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProcessing = true,
                statusMessage = tr(R.string.status_analyzing_image)
            )
            var src: Bitmap? = null
            try {
                src = loadAuthoritativeLayerBitmap(layer) ?: run {
                    LayerRenderer.renderCompositeCanvas(
                        getApplication(),
                        proj.layers,
                        proj.width,
                        proj.height,
                        includeBackground = true
                    )
                }
                val autoAdj = withContext(Dispatchers.Default) {
                    ImageProcessor.computeSmartAutoAdjustments(src)
                }
                saveHistoryState()
                layer.adjustments = autoAdj
                commitProjectMutation(
                    statusMessage = tr(R.string.status_smart_auto_applied),
                    transformState = { it.copy(isProcessing = false) }
                )
            } catch (e: Exception) {
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

    fun applyObjectRemoval() {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx !in proj.layers.indices) return
        val layer = proj.layers[idx]
        val mask = _uiState.value.selectionMask ?: return
        if (layer.isLocked || layer.type != LayerType.IMAGE) return
        if (!SelectionEngine.hasSelection(mask)) {
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_select_object_first))
            return
        }
        val radius = _uiState.value.inpaintRadiusPx.toDouble()
        val feather = _uiState.value.inpaintFeatherPx
        val mode = _uiState.value.inpaintEngineMode
        val smartSnap = _uiState.value.isSmartEdgeSnapEnabled
        val seamless = _uiState.value.isSeamlessBlendEnabled

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_content_aware_removal))
            var src: Bitmap? = null
            var local: Bitmap? = null
            var out: Bitmap? = null
            try {
                src = loadAuthoritativeLayerBitmap(layer) ?: throw IllegalStateException("Image unavailable")
                // workLayerFor keeps the layer's document footprint, so the mask lands on exactly the
                // pixels the user painted over even when the full-res source is larger than the preview.
                val workLayer = workLayerFor(layer, src!!)
                local = withContext(Dispatchers.Default) {
                    SelectionEngine.mapDocumentMaskToLayerLocal(mask, workLayer, proj.width, proj.height)
                }
                out = withContext(Dispatchers.Default) {
                    ImageProcessor.inpaintObjectRemoval(
                        source = src!!,
                        maskBitmap = local!!,
                        radiusPx = radius,
                        featherPx = feather,
                        mode = mode,
                        smartEdgeSnap = smartSnap,
                        seamlessBlend = seamless
                    )
                }
                saveHistoryState()
                if (!commitDestructiveBitmap(proj, layer, out!!, layer.intrinsicDocumentWidth(), layer.intrinsicDocumentHeight(), "content_aware")) {
                    throw java.io.IOException("Save failed")
                }
                commitProjectMutation(
                    statusMessage = tr(R.string.status_object_removed),
                    transformState = { it.copy(selectionMask = SelectionEngine.createBlankMask(proj.width, proj.height), isProcessing = false) }
                )
                clearSelectionHistory()
            } catch (e: OutOfMemoryError) {
                System.gc()
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_processing_failed_memory))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_object_remove_failed))
            } finally {
                local?.let { if (!it.isRecycled) it.recycle() }
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
        val layer: Layer,
        val x: Int,
        val y: Int,
        val mode: SelectionMode,
        val contiguous: Boolean
    )

    /** [current] = the coroutine calling this, which must not cancel itself. */
    private fun endTransientSelectionSessions(current: Job? = null) {
        shapeBaseMask = null
        modifierBaseMask = null
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
        val tolerance = _uiState.value.magicWandTolerance
        val autoEnhance = _uiState.value.isQuickSelectionAutoEnhance
        val sampleAll = _uiState.value.isQuickSelectionSampleAll
        val activeLayer = if (sampleAll) null else proj.layers.getOrNull(_uiState.value.selectedLayerIndex)

        mutateSelectionAsync { mask ->
            // The mask is in document space, so colours must be sampled from a document-space
            // image (the active layer as rendered on the canvas, or the full composite).
            val srcBmp = renderDocumentSamplingImage(proj, activeLayer)
            try {
                SelectionEngine.quickSelection(
                    mask = mask,
                    points = points,
                    brushRadius = radius,
                    sourceBitmap = srcBmp,
                    mode = mode,
                    tolerance = tolerance,
                    autoEnhance = autoEnhance
                )
            } finally {
                if (!srcBmp.isRecycled) srcBmp.recycle()
            }
        }
    }

    // --- Live strokes: the selection updates WHILE the finger moves -----------------------------


    /** Working copy owned by the current live brush stroke (published as the selection mask). */
    private var liveBrushMask: Bitmap? = null
    /** Points collected when a stroke cannot be applied incrementally (INTERSECT, or a busy queue). */
    private val deferredStrokePoints = ArrayList<PointF>()
    private var strokeIsDeferred = false
    private var liveStrokeMode: SelectionMode = SelectionMode.ADD
    private var liveQuickSampling: Bitmap? = null
    private val liveQuickMutex = Mutex()

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
     * Quick Selection with live feedback: segments are processed in order on a background thread
     * against a sampling image rendered once per stroke.
     */
    fun quickSelectionSegment(points: List<PointF>, docRadius: Float, phase: StrokePhase) {
        val proj = _uiState.value.currentProject ?: return
        if (phase == StrokePhase.START) {
            liveStrokeMode = effectiveSelectionMode()
            deferredStrokePoints.clear()
            strokeIsDeferred = liveStrokeMode == SelectionMode.INTERSECT || selectionMutex.isLocked
            if (!strokeIsDeferred) {
                endTransientSelectionSessions()
                val base = _uiState.value.selectionMask ?: return
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
                if (all.isNotEmpty()) applyQuickSelection(all, docRadius = docRadius)
            }
            return
        }
        val working = liveBrushMask ?: return
        if (phase == StrokePhase.END) liveBrushMask = null
        val segmentMode = if (liveStrokeMode == SelectionMode.REPLACE) SelectionMode.ADD else liveStrokeMode
        val tolerance = _uiState.value.magicWandTolerance
        val autoEnhance = _uiState.value.isQuickSelectionAutoEnhance
        val sampleAll = _uiState.value.isQuickSelectionSampleAll
        val activeLayer = if (sampleAll) null else proj.layers.getOrNull(_uiState.value.selectedLayerIndex)
        val isStart = phase == StrokePhase.START
        val isEnd = phase == StrokePhase.END
        val segment = points.toList()
        viewModelScope.launch {
            liveQuickMutex.withLock {
                try {
                    if (isStart || liveQuickSampling == null) {
                        liveQuickSampling?.let { if (!it.isRecycled) it.recycle() }
                        liveQuickSampling = renderDocumentSamplingImage(proj, activeLayer)
                    }
                    val sampling = liveQuickSampling
                    if (sampling != null && segment.isNotEmpty()) {
                        withContext(Dispatchers.Default) {
                            SelectionEngine.quickSelection(
                                mask = working,
                                points = segment,
                                brushRadius = docRadius,
                                sourceBitmap = sampling,
                                mode = segmentMode,
                                tolerance = tolerance,
                                autoEnhance = autoEnhance
                            )
                        }
                        if (_uiState.value.selectionMask === working) {
                            _uiState.value = _uiState.value.copy(
                                selectionRevision = _uiState.value.selectionRevision + 1,
                                renderRevision = _uiState.value.renderRevision + 1
                            )
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (e is OutOfMemoryError) System.gc()
                } finally {
                    if (isEnd) {
                        liveQuickSampling?.let { if (!it.isRecycled) it.recycle() }
                        liveQuickSampling = null
                    }
                }
            }
        }
    }

    /** Layer-mask painting with live feedback; one undo step per stroke. */
    fun paintMaskSegment(points: List<PointF>, docRadius: Float, isReveal: Boolean, phase: StrokePhase) {
        if (points.isEmpty()) return
        paintOnLayerMask(points, docRadius, isReveal, recordHistory = phase == StrokePhase.START)
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
            val layer = wandTargetLayer(proj, touchX, touchY)
            if (layer == null) {
                _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_wand_no_layer))
                return
            }
            val mode = effectiveSelectionMode()
            val contiguous = _uiState.value.isMagicWandContiguous
            wandJob?.cancel()
            wandJob = viewModelScope.launch {
                selectionMutex.withLock {
                    endTransientSelectionSessions(current = currentCoroutineContext()[Job])
                    val base = _uiState.value.selectionMask ?: return@withLock
                    rememberSelectionState()
                    wandSession = WandSession(base, layer, touchX, touchY, mode, contiguous)
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
        val proj = _uiState.value.currentProject ?: return
        selectionMutex.withLock {
            val s = wandSession ?: return@withLock
            val working = withContext(Dispatchers.Default) {
                s.base.copy(Bitmap.Config.ALPHA_8, true).also { m ->
                    SelectionEngine.magicWandSelect(
                        layer = s.layer,
                        canvasWidth = proj.width,
                        canvasHeight = proj.height,
                        docMask = m,
                        docTouchX = s.x.toFloat(),
                        docTouchY = s.y.toFloat(),
                        tolerance = tolerance,
                        contiguous = s.contiguous,
                        mode = s.mode,
                        antiAlias = true
                    )
                }
            }
            if (wandSession === s) publishSelection(working)
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

        commitProjectMutation(statusMessage = tr(R.string.status_layer_mask_added))
    }

    fun invertLayerMask(layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        val mask = layer.maskBitmap ?: return
        if (mask.isRecycled) return

        saveHistoryState()
        val w = mask.width
        val h = mask.height
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val a = (pixels[i] ushr 24) and 0xFF
            val invA = 255 - a
            pixels[i] = invA shl 24
        }
        mask.setPixels(pixels, 0, w, 0, 0, w, h)

        commitProjectMutation(statusMessage = tr(R.string.status_layer_mask_inverted))
    }

    fun toggleLayerMaskEnabled(layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        layer.isMaskEnabled = !layer.isMaskEnabled
        commitProjectMutation()
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
    fun paintOnLayerMask(points: List<PointF>, brushRadius: Float, isReveal: Boolean, recordHistory: Boolean = true) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        val layer = proj.layers.getOrNull(idx) ?: return
        if (points.isEmpty()) return

        if (layer.maskBitmap == null || layer.maskBitmap!!.isRecycled) {
            layer.maskBitmap = Bitmap.createBitmap(proj.width, proj.height, Bitmap.Config.ALPHA_8).apply {
                eraseColor(Color.WHITE)
            }
        }
        // Stroke points are in document coordinates; legacy masks of another size are normalised first.
        layer.maskBitmap?.let { m ->
            if (m.width != proj.width || m.height != proj.height) {
                layer.maskBitmap = documentSizedMask(m, proj.width, proj.height)
            }
        }
        val mask = layer.maskBitmap ?: return
        if (recordHistory) saveHistoryState()

        // Only the stroke's bounding box is read and written (not the whole document-sized mask),
        // so long strokes on large canvases stay fast. Pixels are set directly, which also keeps
        // the result identical across renderers and test runners.
        val w = mask.width
        val h = mask.height
        var minPX = Float.MAX_VALUE; var minPY = Float.MAX_VALUE
        var maxPX = -Float.MAX_VALUE; var maxPY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minPX) minPX = p.x
            if (p.y < minPY) minPY = p.y
            if (p.x > maxPX) maxPX = p.x
            if (p.y > maxPY) maxPY = p.y
        }
        val left = (minPX - brushRadius).toInt().coerceIn(0, w - 1)
        val top = (minPY - brushRadius).toInt().coerceIn(0, h - 1)
        val right = (maxPX + brushRadius).toInt().coerceIn(0, w - 1)
        val bottom = (maxPY + brushRadius).toInt().coerceIn(0, h - 1)
        val regionW = right - left + 1
        val regionH = bottom - top + 1
        if (regionW <= 0 || regionH <= 0) return
        val maskPixels = IntArray(regionW * regionH)
        mask.getPixels(maskPixels, 0, regionW, left, top, regionW, regionH)
        val rSq = brushRadius * brushRadius
        val targetPixel = ((if (isReveal) 255 else 0) shl 24) or 0x00FFFFFF

        fun stamp(cx: Float, cy: Float) {
            val minX = (cx - brushRadius).toInt().coerceIn(left, right)
            val maxX = (cx + brushRadius).toInt().coerceIn(left, right)
            val minY = (cy - brushRadius).toInt().coerceIn(top, bottom)
            val maxY = (cy + brushRadius).toInt().coerceIn(top, bottom)
            for (y in minY..maxY) {
                val dy = y - cy
                val row = (y - top) * regionW
                for (x in minX..maxX) {
                    val dx = x - cx
                    if (dx * dx + dy * dy <= rSq) maskPixels[row + (x - left)] = targetPixel
                }
            }
        }

        for (p in points) stamp(p.x, p.y)
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val dist = kotlin.math.hypot(p2.x - p1.x, p2.y - p1.y)
            val steps = kotlin.math.max(1, (dist / (brushRadius * 0.5f).coerceAtLeast(0.5f)).toInt())
            for (s in 1 until steps) {
                val t = s.toFloat() / steps
                stamp(p1.x + t * (p2.x - p1.x), p1.y + t * (p2.y - p1.y))
            }
        }
        mask.setPixels(maskPixels, 0, regionW, left, top, regionW, regionH)

        commitProjectMutation()
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

    fun revealAllLayerMask(layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        val mask = layer.maskBitmap ?: return
        if (mask.isRecycled) return
        saveHistoryState()
        mask.eraseColor(Color.WHITE)
        commitProjectMutation(statusMessage = tr(R.string.status_layer_mask_added))
    }

    fun hideAllLayerMask(layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        val mask = layer.maskBitmap ?: return
        if (mask.isRecycled) return
        saveHistoryState()
        mask.eraseColor(Color.TRANSPARENT)
        commitProjectMutation(statusMessage = tr(R.string.status_layer_mask_added))
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

    fun applyProFilter(effect: ProFilterType, param1: Float, param2: Float = 0f) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        val layer = proj.layers.getOrNull(idx) ?: return
        if (layer.isLocked || layer.type != LayerType.IMAGE) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProcessing = true,
                statusMessage = tr(R.string.status_processing)
            )
            try {
                val src = loadAuthoritativeLayerBitmap(layer) ?: throw IllegalStateException("Image unavailable")
                val processed = withContext(Dispatchers.Default) {
                    ImageProcessor.applyProEffect(src, effect, param1, param2)
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
                    transformState = { it.copy(isProcessing = false) }
                )
            } catch (e: OutOfMemoryError) {
                System.gc()
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusMessage = tr(R.string.status_processing_failed_memory)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
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
        val wandLayer = wandTargetLayer(proj, touchX, touchY)
        if (wandLayer == null) {
            // Tapped outside every image layer: say so instead of silently doing nothing.
            _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_wand_no_layer))
            return
        }

        mutateSelectionAsync(R.string.status_selecting_area, R.string.status_selection_failed) { mask ->
            SelectionEngine.magicWandSelect(
                layer = wandLayer,
                canvasWidth = proj.width,
                canvasHeight = proj.height,
                docMask = mask,
                docTouchX = touchX.toFloat(),
                docTouchY = touchY.toFloat(),
                tolerance = tolerance,
                contiguous = isContiguous,
                mode = mode,
                antiAlias = true
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
