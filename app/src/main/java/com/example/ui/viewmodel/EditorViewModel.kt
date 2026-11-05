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
import kotlinx.coroutines.launch
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
    val isMagneticSnapEnabled: Boolean = false
)

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

    // Lightweight selection-only history. Keep one undo + one redo mask to avoid
    // mixing selection edits with document history or retaining many 12MP masks.
    private var selectionUndoMask: Bitmap? = null
    private var selectionRedoMask: Bitmap? = null

    private fun clearSelectionHistory() {
        selectionUndoMask?.let { if (!it.isRecycled) it.recycle() }
        selectionRedoMask?.let { if (!it.isRecycled) it.recycle() }
        selectionUndoMask = null
        selectionRedoMask = null
        _uiState.value = _uiState.value.copy(canUndoSelection = false, canRedoSelection = false)
    }

    private fun rememberSelectionState() {
        val mask = _uiState.value.selectionMask ?: return
        selectionUndoMask?.let { if (!it.isRecycled) it.recycle() }
        selectionUndoMask = mask.copy(Bitmap.Config.ALPHA_8, true)
        selectionRedoMask?.let { if (!it.isRecycled) it.recycle() }
        selectionRedoMask = null
        _uiState.value = _uiState.value.copy(canUndoSelection = true, canRedoSelection = false)
    }

    fun undoSelection() {
        val undo = selectionUndoMask ?: return
        val current = _uiState.value.selectionMask ?: return
        selectionRedoMask?.let { if (!it.isRecycled) it.recycle() }
        selectionRedoMask = current.copy(Bitmap.Config.ALPHA_8, true)
        _uiState.value = _uiState.value.copy(
            selectionMask = undo.copy(Bitmap.Config.ALPHA_8, true),
            selectionRevision = _uiState.value.selectionRevision + 1,
            renderRevision = _uiState.value.renderRevision + 1,
            canUndoSelection = false,
            canRedoSelection = true
        )
        if (!undo.isRecycled) undo.recycle()
        selectionUndoMask = null
    }

    fun redoSelection() {
        val redo = selectionRedoMask ?: return
        val current = _uiState.value.selectionMask ?: return
        selectionUndoMask?.let { if (!it.isRecycled) it.recycle() }
        selectionUndoMask = current.copy(Bitmap.Config.ALPHA_8, true)
        _uiState.value = _uiState.value.copy(
            selectionMask = redo.copy(Bitmap.Config.ALPHA_8, true),
            selectionRevision = _uiState.value.selectionRevision + 1,
            renderRevision = _uiState.value.renderRevision + 1,
            canUndoSelection = true,
            canRedoSelection = false
        )
        if (!redo.isRecycled) redo.recycle()
        selectionRedoMask = null
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
                val success = repository.saveProject(proj)
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
        if (_uiState.value.isProjectDirty) {
            _uiState.value = _uiState.value.copy(isUnsavedChangesDialogOpen = true)
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
                val success = repository.saveProject(proj)
                if (success) {
                    dismissUnsavedDialog()
                    exitEditor()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        isProjectDirty = true,
                        isUnsavedChangesDialogOpen = false,
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
        prefs.edit().remove("last_opened_project_id").apply()
        _uiState.value = _uiState.value.copy(
            currentProject = null,
            currentScreen = Screen.HOME,
            isProjectDirty = false,
            isUnsavedChangesDialogOpen = false
        )
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
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true)
            val list = repository.getProjectSummaries()

            _uiState.value = _uiState.value.copy(
                projects = list,
                isProcessing = false
            )
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
            }
            _uiState.value = _uiState.value.copy(isProcessing = false)
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
            selectionMask = SelectionEngine.createBlankMask(project.width, project.height)
        )
    }

    fun saveCurrentProject() {
        val proj = _uiState.value.currentProject ?: return
        autosaveJob?.cancel()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_saving_project))
            val success = repository.saveProject(proj)
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

    fun rollbackHistoryTransaction() {
        val snapshot = pendingTransactionSnapshot
        if (snapshot != null) {
            val proj = _uiState.value.currentProject ?: return
            proj.width = snapshot.width
            proj.height = snapshot.height
            proj.layers.clear()
            proj.layers.addAll(snapshot.layers)
            proj.markDirty()
            pendingTransactionSnapshot = null
            _uiState.value = _uiState.value.copy(
                currentProject = proj.copy(layers = ArrayList(proj.layers)),
                renderRevision = proj.renderRevision
            )
        }
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

        if (pendingTransactionSnapshot != null) {
            val snapshot = pendingTransactionSnapshot!!
            pendingTransactionSnapshot = null
            pendingTransactionWasDirty = false
            proj.width = snapshot.width
            proj.height = snapshot.height
            proj.layers.clear()
            proj.layers.addAll(snapshot.layers)
            proj.markDirty()

            val newMask = SelectionEngine.createBlankMask(proj.width, proj.height)
            _uiState.value = _uiState.value.copy(
                currentProject = proj.copy(layers = ArrayList(proj.layers)),
                renderRevision = proj.renderRevision,
                selectedLayerIndex = (proj.layers.size - 1).coerceAtLeast(-1),
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

        val newMask = SelectionEngine.createBlankMask(proj.width, proj.height)

        _uiState.value = _uiState.value.copy(
            currentProject = proj.copy(layers = ArrayList(proj.layers)),
            renderRevision = proj.renderRevision,
            selectedLayerIndex = (proj.layers.size - 1).coerceAtLeast(-1),
            selectionMask = newMask
        )
        updateHistoryFlags()
        markDirty()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val proj = _uiState.value.currentProject ?: return
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

        val newMask = SelectionEngine.createBlankMask(proj.width, proj.height)

        _uiState.value = _uiState.value.copy(
            currentProject = proj.copy(layers = ArrayList(proj.layers)),
            renderRevision = proj.renderRevision,
            selectedLayerIndex = (proj.layers.size - 1).coerceAtLeast(-1),
            selectionMask = newMask
        )
        updateHistoryFlags()
        markDirty()
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
    fun addImageLayer(bitmap: Bitmap, name: String = "") {
        val proj = _uiState.value.currentProject ?: return
        saveHistoryState()

        val layer = Layer(
            id = java.util.UUID.randomUUID().toString(),
            name = name.ifBlank { tr(R.string.layer_name_image) },
            type = LayerType.IMAGE,
            bitmap = bitmap,
            contentWidth = bitmap.width.toFloat(),
            contentHeight = bitmap.height.toFloat()
        )
        proj.layers.add(layer)
        commitProjectMutation(selectedLayerIndex = proj.layers.size - 1)
    }

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
                    bottomLayer.adjustments = ColorAdjustments()

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

            try {
                saveHistoryState()

                // Apply Perspective Crop to all layers in the project
                for (layer in proj.layers) {
                    val srcBmp = loadAuthoritativeLayerBitmap(layer) ?: continue
                    val canvasLayerBmp = if (layer.positionX == 0f && layer.positionY == 0f &&
                        layer.scaleX == 1f && layer.scaleY == 1f && layer.rotation == 0f &&
                        srcBmp.width == oldW && srcBmp.height == oldH
                    ) {
                        srcBmp
                    } else {
                        val fullCanvas = Bitmap.createBitmap(oldW, oldH, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(fullCanvas)
                        val matrix = SelectionEngine.getLayerMatrix(layer, oldW, oldH)
                        canvas.drawBitmap(srcBmp, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                        fullCanvas
                    }

                    val warpedBmp = withContext(Dispatchers.Default) {
                        ImageProcessor.transformPerspective(canvasLayerBmp, corners, targetW, targetH)
                    }

                    if (canvasLayerBmp !== srcBmp && !canvasLayerBmp.isRecycled) {
                        canvasLayerBmp.recycle()
                    }

                    val oldMask = layer.maskBitmap
                    if (oldMask != null && !oldMask.isRecycled) {
                        val warpedMask = withContext(Dispatchers.Default) {
                            ImageProcessor.transformPerspectiveAlpha(oldMask, corners, targetW, targetH)
                        }
                        if (oldMask !== warpedMask && !oldMask.isRecycled) {
                            oldMask.recycle()
                        }
                        layer.maskBitmap = warpedMask
                    }

                    commitDestructiveBitmap(proj, layer, warpedBmp, targetW.toFloat(), targetH.toFloat(), "perspective_crop")
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
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusMessage = tr(R.string.status_perspective_failed)
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
        proj.layers.forEach {
            it.positionX += (oldW - nw) / 2f - l
            it.positionY += (oldH - nh) / 2f - t
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
                val workLayer = layer.copy(bitmap = src!!)
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

    fun applyBrushStroke(points: List<PointF>, isEraser: Boolean = false, docRadius: Float? = null) {
        val mask = _uiState.value.selectionMask ?: return
        rememberSelectionState()
        val radius = docRadius ?: _uiState.value.brushSizeDp
        val mode = _uiState.value.selectionMode
        viewModelScope.launch(Dispatchers.Default) {
            SelectionEngine.drawBrushStroke(mask, points, radius, isEraser, mode)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    selectionMask = mask,
                    selectionRevision = _uiState.value.selectionRevision + 1,
                    renderRevision = _uiState.value.renderRevision + 1
                )
            }
        }
    }

    fun setQuickSelectionAutoEnhance(enable: Boolean) {
        _uiState.value = _uiState.value.copy(isQuickSelectionAutoEnhance = enable)
    }

    fun setQuickSelectionSampleAll(enable: Boolean) {
        _uiState.value = _uiState.value.copy(isQuickSelectionSampleAll = enable)
    }

    fun applyQuickSelection(points: List<PointF>, isEraser: Boolean = false, docRadius: Float? = null) {
        val proj = _uiState.value.currentProject ?: return
        val mask = _uiState.value.selectionMask ?: return
        rememberSelectionState()
        val radius = docRadius ?: _uiState.value.brushSizeDp
        val mode = if (isEraser) SelectionMode.SUBTRACT else _uiState.value.selectionMode
        val tolerance = _uiState.value.magicWandTolerance
        val autoEnhance = _uiState.value.isQuickSelectionAutoEnhance
        val sampleAll = _uiState.value.isQuickSelectionSampleAll

        viewModelScope.launch(Dispatchers.Default) {
            val srcBmp = if (sampleAll) {
                LayerRenderer.renderCompositeCanvas(
                    getApplication(),
                    proj.layers,
                    proj.width,
                    proj.height,
                    includeBackground = true
                )
            } else {
                val idx = _uiState.value.selectedLayerIndex
                val activeLayer = proj.layers.getOrNull(idx)
                activeLayer?.let { loadAuthoritativeLayerBitmap(it) }
                    ?: LayerRenderer.renderCompositeCanvas(
                        getApplication(),
                        proj.layers,
                        proj.width,
                        proj.height,
                        includeBackground = true
                    )
            }

            SelectionEngine.quickSelection(
                mask = mask,
                points = points,
                brushRadius = radius,
                sourceBitmap = srcBmp,
                mode = mode,
                tolerance = tolerance,
                autoEnhance = autoEnhance
            )

            if (sampleAll && !srcBmp.isRecycled) {
                srcBmp.recycle()
            }

            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    selectionMask = mask,
                    selectionRevision = _uiState.value.selectionRevision + 1,
                    renderRevision = _uiState.value.renderRevision + 1
                )
            }
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
            val w = if (layer.bitmap != null && layer.bitmap!!.width > 0) layer.bitmap!!.width else proj.width
            val h = if (layer.bitmap != null && layer.bitmap!!.height > 0) layer.bitmap!!.height else proj.height
            val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
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
        layer.maskBitmap?.recycle()
        layer.maskBitmap = null
        layer.isEditingMask = false
        commitProjectMutation(statusMessage = tr(R.string.status_layer_mask_deleted))
    }

    fun applyLayerMask(layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        val mask = layer.maskBitmap ?: return
        val bmp = layer.bitmap ?: return
        if (mask.isRecycled || bmp.isRecycled) return

        saveHistoryState()
        val result = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(bmp, 0f, 0f, null)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        if (mask.width == bmp.width && mask.height == bmp.height) {
            canvas.drawBitmap(mask, 0f, 0f, maskPaint)
        } else {
            val m = Matrix().apply {
                postScale(bmp.width.toFloat() / mask.width, bmp.height.toFloat() / mask.height)
            }
            canvas.drawBitmap(mask, m, maskPaint)
        }

        viewModelScope.launch {
            commitDestructiveBitmap(proj, layer, result, bmp.width.toFloat(), bmp.height.toFloat(), "apply_mask")
            layer.maskBitmap?.recycle()
            layer.maskBitmap = null
            layer.isEditingMask = false
            commitProjectMutation(statusMessage = tr(R.string.status_layer_mask_applied))
        }
    }

    fun paintOnLayerMask(points: List<PointF>, brushRadius: Float, isReveal: Boolean) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        val layer = proj.layers.getOrNull(idx) ?: return
        if (points.isEmpty()) return

        if (layer.maskBitmap == null || layer.maskBitmap!!.isRecycled) {
            layer.maskBitmap = Bitmap.createBitmap(proj.width, proj.height, Bitmap.Config.ALPHA_8).apply {
                eraseColor(Color.WHITE)
            }
        }
        val mask = layer.maskBitmap ?: return
        saveHistoryState()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = brushRadius * 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (isReveal) {
                color = Color.WHITE
            } else {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
        }

        val canvas = Canvas(mask)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            if (isReveal) {
                color = Color.WHITE
            } else {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
        }
        for (p in points) {
            canvas.drawCircle(p.x, p.y, brushRadius, fillPaint)
        }
        if (points.size > 1) {
            val path = Path()
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                path.lineTo(points[i].x, points[i].y)
            }
            canvas.drawPath(path, paint)
        }

        // Ensure mask pixel buffer is synced for getPixel, test runners, and pixel inspectors
        val w = mask.width
        val h = mask.height
        val maskPixels = IntArray(w * h)
        mask.getPixels(maskPixels, 0, w, 0, 0, w, h)
        val rSq = brushRadius * brushRadius
        val targetAlpha = if (isReveal) 255 else 0

        for (p in points) {
            val minX = (p.x - brushRadius).toInt().coerceIn(0, w - 1)
            val maxX = (p.x + brushRadius).toInt().coerceIn(0, w - 1)
            val minY = (p.y - brushRadius).toInt().coerceIn(0, h - 1)
            val maxY = (p.y + brushRadius).toInt().coerceIn(0, h - 1)
            for (y in minY..maxY) {
                val dy = y - p.y
                val row = y * w
                for (x in minX..maxX) {
                    val dx = x - p.x
                    if (dx * dx + dy * dy <= rSq) {
                        maskPixels[row + x] = (targetAlpha shl 24) or 0x00FFFFFF
                    }
                }
            }
        }
        if (points.size > 1) {
            for (i in 0 until points.size - 1) {
                val p1 = points[i]
                val p2 = points[i + 1]
                val dist = kotlin.math.hypot(p2.x - p1.x, p2.y - p1.y)
                val steps = kotlin.math.max(1, (dist / (brushRadius * 0.5f)).toInt())
                for (s in 1 until steps) {
                    val t = s.toFloat() / steps
                    val px = p1.x + t * (p2.x - p1.x)
                    val py = p1.y + t * (p2.y - p1.y)
                    val minX = (px - brushRadius).toInt().coerceIn(0, w - 1)
                    val maxX = (px + brushRadius).toInt().coerceIn(0, w - 1)
                    val minY = (py - brushRadius).toInt().coerceIn(0, h - 1)
                    val maxY = (py + brushRadius).toInt().coerceIn(0, h - 1)
                    for (y in minY..maxY) {
                        val dy = y - py
                        val row = y * w
                        for (x in minX..maxX) {
                            val dx = x - px
                            if (dx * dx + dy * dy <= rSq) {
                                maskPixels[row + x] = (targetAlpha shl 24) or 0x00FFFFFF
                            }
                        }
                    }
                }
            }
        }
        mask.setPixels(maskPixels, 0, w, 0, 0, w, h)

        commitProjectMutation()
    }

    fun setLayerMaskDensity(density: Float, layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        if (layer.maskBitmap == null) return
        layer.maskDensity = density.coerceIn(0f, 1f)
        proj.markDirty()
        _uiState.value = _uiState.value.copy(
            renderRevision = proj.renderRevision,
            isProjectDirty = true
        )
    }

    fun setLayerMaskFeather(feather: Float, layerIndex: Int = _uiState.value.selectedLayerIndex) {
        val proj = _uiState.value.currentProject ?: return
        val layer = proj.layers.getOrNull(layerIndex) ?: return
        if (layer.maskBitmap == null) return
        layer.maskFeather = feather.coerceIn(0f, 50f)
        proj.markDirty()
        _uiState.value = _uiState.value.copy(
            renderRevision = proj.renderRevision,
            isProjectDirty = true
        )
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

    fun setCurvesPoints(channel: String, points: List<PointF>) {
        updateSelectedLayerTransient { layer ->
            val adj = layer.adjustments
            val updated = when (channel.uppercase()) {
                "RED" -> adj.copy(curvesRed = points)
                "GREEN" -> adj.copy(curvesGreen = points)
                "BLUE" -> adj.copy(curvesBlue = points)
                else -> adj.copy(curvesRgb = points)
            }
            layer.adjustments = updated
        }
    }

    fun applyCurvePreset(channel: String, presetName: String) {
        val points = when (presetName.lowercase()) {
            "s_curve" -> listOf(PointF(0f, 0f), PointF(64f, 46f), PointF(192f, 209f), PointF(255f, 255f))
            "lift_shadows" -> listOf(PointF(0f, 38f), PointF(128f, 148f), PointF(255f, 255f))
            "deep_blacks" -> listOf(PointF(20f, 0f), PointF(128f, 107f), PointF(255f, 255f))
            else -> listOf(PointF(0f, 0f), PointF(255f, 255f))
        }
        saveHistoryState()
        setCurvesPoints(channel, points)
        commitProjectMutation()
    }

    fun resetCurveChannel(channel: String) {
        applyCurvePreset(channel, "linear")
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
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusMessage = e.message ?: "Effect failed"
                )
            }
        }
    }

    fun applyLassoSelection(points: List<PointF>) {
        val mask = _uiState.value.selectionMask ?: return
        val proj = _uiState.value.currentProject ?: return
        rememberSelectionState()
        val mode = _uiState.value.selectionMode
        val snapEnabled = _uiState.value.isMagneticSnapEnabled
        viewModelScope.launch(Dispatchers.Default) {
            val processedPoints = if (snapEnabled && points.size > 2) {
                val activeBmp = proj.layers.getOrNull(_uiState.value.selectedLayerIndex)?.bitmap
                if (activeBmp != null && !activeBmp.isRecycled) {
                    points.map { pt ->
                        SelectionEngine.snapPointToEdge(pt, activeBmp, searchRadius = 16)
                    }
                } else points
            } else points
            SelectionEngine.fillLassoPath(mask, processedPoints, mode)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    selectionMask = mask,
                    selectionRevision = _uiState.value.selectionRevision + 1,
                    renderRevision = _uiState.value.renderRevision + 1
                )
            }
        }
    }

    fun applyPolygonSelection(points: List<PointF>) {
        val mask = _uiState.value.selectionMask ?: return
        val proj = _uiState.value.currentProject ?: return
        rememberSelectionState()
        val mode = _uiState.value.selectionMode
        val snapEnabled = _uiState.value.isMagneticSnapEnabled
        viewModelScope.launch(Dispatchers.Default) {
            val processedPoints = if (snapEnabled && points.size > 2) {
                val activeBmp = proj.layers.getOrNull(_uiState.value.selectedLayerIndex)?.bitmap
                if (activeBmp != null && !activeBmp.isRecycled) {
                    points.map { pt ->
                        SelectionEngine.snapPointToEdge(pt, activeBmp, searchRadius = 16)
                    }
                } else points
            } else points
            SelectionEngine.fillPolygonPoints(mask, processedPoints, mode)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    selectionMask = mask,
                    selectionRevision = _uiState.value.selectionRevision + 1,
                    renderRevision = _uiState.value.renderRevision + 1
                )
            }
        }
    }

    fun applyRectangleSelection(start: PointF, end: PointF) {
        val mask = _uiState.value.selectionMask ?: return
        rememberSelectionState()
        SelectionEngine.fillRectangleSelection(mask, start, end, _uiState.value.selectionMode)
        _uiState.value = _uiState.value.copy(
            selectionMask = mask,
            selectionRevision = _uiState.value.selectionRevision + 1,
            renderRevision = _uiState.value.renderRevision + 1
        )
    }

    fun applyEllipseSelection(start: PointF, end: PointF) {
        val mask = _uiState.value.selectionMask ?: return
        rememberSelectionState()
        SelectionEngine.fillEllipseSelection(mask, start, end, _uiState.value.selectionMode)
        _uiState.value = _uiState.value.copy(
            selectionMask = mask,
            selectionRevision = _uiState.value.selectionRevision + 1,
            renderRevision = _uiState.value.renderRevision + 1
        )
    }

    fun applyMagicWand(touchX: Int, touchY: Int) {
        val proj = _uiState.value.currentProject ?: return
        val mask = _uiState.value.selectionMask ?: return
        val tolerance = _uiState.value.magicWandTolerance
        val mode = _uiState.value.selectionMode
        val isContiguous = _uiState.value.isMagicWandContiguous

        // Smart layer targeting: use active layer if it contains the tap, or auto-detect layer under tap
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
            }
        }
        if (targetLayer == null || targetLayer.bitmap == null) return

        rememberSelectionState()

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_selecting_area))
            try {
                withContext(Dispatchers.Default) {
                    SelectionEngine.magicWandSelect(
                        layer = targetLayer,
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
                _uiState.value = _uiState.value.copy(
                    selectionMask = mask,
                    selectionRevision = _uiState.value.selectionRevision + 1,
                    renderRevision = _uiState.value.renderRevision + 1,
                    isProcessing = false,
                    statusMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_selection_failed))
            }
        }
    }

    fun featherSelection(radiusPx: Float = 8f) {
        val mask = _uiState.value.selectionMask ?: return
        rememberSelectionState()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_feathering))
            try {
                withContext(Dispatchers.Default) {
                    SelectionEngine.featherMask(mask, radiusPx)
                }
                _uiState.value = _uiState.value.copy(
                    selectionMask = mask,
                    selectionRevision = _uiState.value.selectionRevision + 1,
                    renderRevision = _uiState.value.renderRevision + 1,
                    isProcessing = false,
                    statusMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_feather_failed))
            }
        }
    }

    fun growSelection(radiusPx: Int = 5) {
        val mask = _uiState.value.selectionMask ?: return
        rememberSelectionState()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_expanding_selection))
            try {
                withContext(Dispatchers.Default) {
                    SelectionEngine.growMask(mask, radiusPx)
                }
                _uiState.value = _uiState.value.copy(
                    selectionMask = mask,
                    selectionRevision = _uiState.value.selectionRevision + 1,
                    renderRevision = _uiState.value.renderRevision + 1,
                    isProcessing = false,
                    statusMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_expand_failed))
            }
        }
    }

    fun shrinkSelection(radiusPx: Int = 5) {
        val mask = _uiState.value.selectionMask ?: return
        rememberSelectionState()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_contracting_selection))
            try {
                withContext(Dispatchers.Default) {
                    SelectionEngine.shrinkMask(mask, radiusPx)
                }
                _uiState.value = _uiState.value.copy(
                    selectionMask = mask,
                    selectionRevision = _uiState.value.selectionRevision + 1,
                    renderRevision = _uiState.value.renderRevision + 1,
                    isProcessing = false,
                    statusMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_contract_failed))
            }
        }
    }

    fun smoothSelection(radiusPx: Int = 3) {
        val mask = _uiState.value.selectionMask ?: return
        rememberSelectionState()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_smoothing_selection))
            try {
                withContext(Dispatchers.Default) {
                    SelectionEngine.smoothMask(mask, radiusPx)
                }
                _uiState.value = _uiState.value.copy(
                    selectionMask = mask,
                    selectionRevision = _uiState.value.selectionRevision + 1,
                    renderRevision = _uiState.value.renderRevision + 1,
                    isProcessing = false,
                    statusMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_smooth_failed))
            }
        }
    }

    fun refineSelectionEdge(contrastFactor: Float = 2.0f) {
        val mask = _uiState.value.selectionMask ?: return
        rememberSelectionState()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_refining_selection))
            try {
                withContext(Dispatchers.Default) {
                    SelectionEngine.refineMaskEdges(mask, contrastFactor)
                }
                _uiState.value = _uiState.value.copy(
                    selectionMask = mask,
                    selectionRevision = _uiState.value.selectionRevision + 1,
                    renderRevision = _uiState.value.renderRevision + 1,
                    isProcessing = false,
                    statusMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_refine_failed))
            }
        }
    }

    fun openRefineEdgeDialog() {
        _uiState.value = _uiState.value.copy(isRefineEdgeDialogOpen = true)
    }

    fun closeRefineEdgeDialog() {
        _uiState.value = _uiState.value.copy(isRefineEdgeDialogOpen = false)
    }

    fun toggleMagneticSnap() {
        _uiState.value = _uiState.value.copy(isMagneticSnapEnabled = !_uiState.value.isMagneticSnapEnabled)
    }

    fun autoSelectSubject() {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx !in proj.layers.indices) return
        val layer = proj.layers[idx]
        rememberSelectionState()

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.select_subject_desc))
            var src: Bitmap? = null
            try {
                src = loadAuthoritativeLayerBitmap(layer) ?: throw IllegalStateException("Layer image unavailable")
                val subjectMask = withContext(Dispatchers.Default) {
                    SelectionEngine.selectSubject(src!!)
                }
                _uiState.value = _uiState.value.copy(
                    selectionMask = subjectMask,
                    selectionRevision = _uiState.value.selectionRevision + 1,
                    renderRevision = _uiState.value.renderRevision + 1,
                    isProcessing = false,
                    statusMessage = tr(R.string.status_subject_selected)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_selection_failed))
            } finally {
                val p = layer.bitmap
                src?.let { if (it !== p && !it.isRecycled) it.recycle() }
            }
        }
    }

    fun applyRefineEdgeMatting(config: RefineEdgeConfig) {
        val proj = _uiState.value.currentProject ?: return
        val idx = _uiState.value.selectedLayerIndex
        if (idx !in proj.layers.indices) return
        val layer = proj.layers[idx]
        val currentMask = _uiState.value.selectionMask ?: return
        rememberSelectionState()

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.refine_hair_desc))
            var src: Bitmap? = null
            try {
                src = loadAuthoritativeLayerBitmap(layer) ?: throw IllegalStateException("Layer image unavailable")
                val result = withContext(Dispatchers.Default) {
                    SelectionEngine.refineEdgesWithMatting(src!!, currentMask, config)
                }

                // If decontamination was applied, update layer bitmap with cleaned fringes
                if (result.decontaminatedSource != null) {
                    saveHistoryState()
                    commitDestructiveBitmap(
                        proj,
                        layer,
                        result.decontaminatedSource,
                        layer.intrinsicDocumentWidth(),
                        layer.intrinsicDocumentHeight(),
                        "refine_decontaminate"
                    )
                }

                _uiState.value = _uiState.value.copy(
                    selectionMask = result.refinedMask,
                    selectionRevision = _uiState.value.selectionRevision + 1,
                    renderRevision = _uiState.value.renderRevision + 1,
                    isRefineEdgeDialogOpen = false,
                    isProcessing = false,
                    statusMessage = tr(R.string.status_matting_complete)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_matting_failed))
            } finally {
                val p = layer.bitmap
                src?.let { if (it !== p && !it.isRecycled) it.recycle() }
            }
        }
    }

    fun invertSelection() {
        val mask = _uiState.value.selectionMask ?: return
        rememberSelectionState()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusMessage = tr(R.string.status_inverting_selection))
            try {
                withContext(Dispatchers.Default) {
                    SelectionEngine.invertMask(mask)
                }
                _uiState.value = _uiState.value.copy(
                    selectionMask = mask,
                    selectionRevision = _uiState.value.selectionRevision + 1,
                    renderRevision = _uiState.value.renderRevision + 1,
                    isProcessing = false,
                    statusMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = tr(R.string.status_invert_failed))
            }
        }
    }

    fun selectAll() {
        rememberSelectionState()
        _uiState.value.selectionMask?.let {
            SelectionEngine.selectAll(it)
            _uiState.value = _uiState.value.copy(
                selectionMask = it,
                selectionRevision = _uiState.value.selectionRevision + 1,
                renderRevision = _uiState.value.renderRevision + 1
            )
        }
    }

    fun clearSelection() {
        val proj = _uiState.value.currentProject ?: return
        rememberSelectionState()
        val newBlankMask = SelectionEngine.createBlankMask(proj.width, proj.height)
        _uiState.value = _uiState.value.copy(
            selectionMask = newBlankMask,
            selectionRevision = _uiState.value.selectionRevision + 1,
            renderRevision = _uiState.value.renderRevision + 1
        )
    }

    fun copySelectionToNewLayer() {
        val proj=_uiState.value.currentProject?:return;val idx=_uiState.value.selectedLayerIndex;if(idx !in proj.layers.indices)return;val layer=proj.layers[idx];val mask=_uiState.value.selectionMask?:return
        if(layer.type!=LayerType.IMAGE||!SelectionEngine.hasSelection(mask)){_uiState.value=_uiState.value.copy(statusMessage=tr(R.string.status_nothing_selected));return}
        viewModelScope.launch{_uiState.value=_uiState.value.copy(isProcessing=true,statusMessage=tr(R.string.status_copying_selection));var src:Bitmap?=null;var local:Bitmap?=null;var copy:Bitmap?=null
            try{src=loadAuthoritativeLayerBitmap(layer)?:throw IllegalStateException("Image unavailable");val work=layer.copy(bitmap=src!!);local=withContext(Dispatchers.Default){SelectionEngine.mapDocumentMaskToLayerLocal(mask,work,proj.width,proj.height)};copy=withContext(Dispatchers.Default){SelectionEngine.copySelectedToNewBitmap(src!!,local!!)}?:throw IllegalStateException("No pixels selected")
                val n=Layer(id=java.util.UUID.randomUUID().toString(),name=tr(R.string.layer_name_selection),type=LayerType.IMAGE,positionX=layer.positionX,positionY=layer.positionY,scaleX=layer.scaleX,scaleY=layer.scaleY,rotation=layer.rotation,opacity=layer.opacity,blendMode=layer.blendMode,filter=layer.filter,adjustments=layer.adjustments.copy(),contentWidth=layer.intrinsicDocumentWidth(),contentHeight=layer.intrinsicDocumentHeight())
                if(!commitDestructiveBitmap(proj,n,copy!!,n.contentWidth,n.contentHeight,"selection"))throw java.io.IOException("Save failed");saveHistoryState();proj.layers.add(idx+1,n);commitProjectMutation(selectedLayerIndex=idx+1,statusMessage=tr(R.string.status_copied_new_layer),transformState={it.copy(selectionMask=SelectionEngine.createBlankMask(proj.width,proj.height),isProcessing=false)}); clearSelectionHistory()}
            catch(e:Exception){_uiState.value=_uiState.value.copy(isProcessing=false,statusMessage=tr(R.string.status_copy_failed))}
            finally{local?.let{if(!it.isRecycled)it.recycle()};src?.let{if(!it.isRecycled)it.recycle()};copy?.let{if(!it.isRecycled)it.recycle()}}
        }
    }

    // --- Exporting ---
    fun exportImage(isPng: Boolean, quality: Int) {
        val proj = _uiState.value.currentProject ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true)

            val compositeBmp = withContext(Dispatchers.Default) {
                LayerRenderer.renderCompositeCanvas(
                    getApplication(),
                    proj.layers,
                    proj.width,
                    proj.height,
                    backgroundColor = if (isPng) null else android.graphics.Color.WHITE,
                    maxPreviewDimension = null
                )
            }

            val exportUri = repository.exportToGallery(
                compositeBmp,
                proj.name.replace(" ", "_"),
                isPng,
                quality
            )

            _uiState.value = _uiState.value.copy(
                isProcessing = false,
                exportSuccessUri = exportUri,
                statusMessage = if (exportUri != null) tr(R.string.export_success) else tr(R.string.export_failed)
            )
        }
    }

    fun clearStatusMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    fun notifyStoragePermissionDenied() {
        _uiState.value = _uiState.value.copy(statusMessage = tr(R.string.status_storage_permission_denied))
    }
}
