package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asComposePath
import com.example.engine.SelectionPathTracer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.coroutineScope
import androidx.compose.animation.core.animate
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Layer
import com.example.data.model.Project
import com.example.data.model.SelectionToolType
import com.example.engine.LayerRenderer
import com.example.engine.SelectionEngine
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CheckerDark
import com.example.ui.theme.CheckerLight
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import com.example.ui.viewmodel.ActiveTool
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rectangle spanned by [a] (anchor) and [b] (finger) in document space. [fromCenter] treats [a]
 * as the centre; [square] constrains to a square / circle.
 */
private fun constrainedShapeRect(a: PointF, b: PointF, fromCenter: Boolean, square: Boolean): RectF {
    var dx = b.x - a.x
    var dy = b.y - a.y
    if (square) {
        val m = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
        dx = if (dx < 0) -m else m
        dy = if (dy < 0) -m else m
    }
    return if (fromCenter) {
        RectF(a.x - kotlin.math.abs(dx), a.y - kotlin.math.abs(dy), a.x + kotlin.math.abs(dx), a.y + kotlin.math.abs(dy))
    } else {
        RectF(minOf(a.x, a.x + dx), minOf(a.y, a.y + dy), maxOf(a.x, a.x + dx), maxOf(a.y, a.y + dy))
    }
}

private enum class TransformHandle {
    NONE,
    BODY,
    CORNER_TL,
    CORNER_TR,
    CORNER_BR,
    CORNER_BL,
    EDGE_T,
    EDGE_R,
    EDGE_B,
    EDGE_L,
    ROTATION
}

private enum class CropHandle {
    NONE,
    BODY,
    CORNER_TL,
    CORNER_TR,
    CORNER_BR,
    CORNER_BL,
    EDGE_T,
    EDGE_B,
    EDGE_L,
    EDGE_R
}

@Composable
fun CanvasView(
    project: Project,
    renderRevision: Long = project.renderRevision,
    selectedLayerIndex: Int,
    activeTool: ActiveTool,
    activeSelectionTool: SelectionToolType,
    inpaintSelectionMode: com.example.ui.viewmodel.InpaintSelectionMode = com.example.ui.viewmodel.InpaintSelectionMode.BRUSH,
    selectionMask: Bitmap?,
    brushSizeDp: Float,
    isPerspectiveActive: Boolean,
    perspectiveCorners: List<PointF>,
    selectedAspectRatio: Float?,
    onBrushStrokeComplete: (List<PointF>, Float) -> Unit,
    onLassoComplete: (List<PointF>) -> Unit,
    onRectangleSelection: ((PointF, PointF) -> Unit)? = null,
    onEllipseSelection: ((PointF, PointF) -> Unit)? = null,
    onPolygonComplete: ((List<PointF>) -> Unit)? = null,
    onMagicWandTap: (Int, Int) -> Unit,
    onUpdatePerspectiveCorner: (Int, Float, Float) -> Unit,
    onUpdatePerspectiveCorners: ((List<PointF>) -> Unit)? = null,
    onUpdateLayerTransient: (((Layer) -> Unit) -> Unit)? = null,
    onCommitHistoryState: (() -> Unit)? = null,
    onSelectLayer: ((Int) -> Unit)? = null,
    onApplyCropRect: ((RectF) -> Unit)? = null,
    modifier: Modifier = Modifier,
    showSelectedLayerOriginal: Boolean = false,
    showGeneralOriginal: Boolean = false,
    isSplitCompareActive: Boolean = false,
    splitComparePosition: Float = 0.5f,
    onSplitComparePositionChange: ((Float) -> Unit)? = null,
    onToggleSplitCompare: (() -> Unit)? = null,
    showMaskRubyOverlay: Boolean = false,
    maskBrushSizeDp: Float = 24f,
    /** Live brush strokes (selection brush, quick selection, mask painting). Null = apply on finger up. */
    onStrokeSegment: ((List<PointF>, Float, com.example.ui.viewmodel.StrokePhase) -> Unit)? = null,
    selectionRevision: Long = 0L,
    isMagneticSnap: Boolean = false,
    magicWandTolerance: Int = 30,
    onMagicWandDrag: ((Int, Int, Int, com.example.ui.viewmodel.StrokePhase) -> Unit)? = null,
    editableShape: com.example.ui.viewmodel.EditableShape? = null,
    shapeFromCenter: Boolean = false,
    shapeLockSquare: Boolean = false,
    onShapeCreated: ((RectF, Boolean) -> Unit)? = null,
    onShapeEdited: ((RectF) -> Unit)? = null,
    onMoveSelection: ((Float, Float) -> Unit)? = null,
    isQuickMaskView: Boolean = false,
    onTemporarySubtract: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val canvasAccessibilityDescription = stringResource(R.string.cd_editor_canvas)

    // Painting a layer mask uses its own brush size; every other brush tool uses the selection brush.
    val isEditingSelectedMask = activeTool == ActiveTool.LAYERS &&
        project.layers.getOrNull(selectedLayerIndex)?.isEditingMask == true
    val effectiveBrushSizeDp = if (isEditingSelectedMask) maskBrushSizeDp else brushSizeDp
    val selectedLayerType = project.layers.getOrNull(selectedLayerIndex)?.type

    // Gesture handlers must not be restarted while a drag is in progress (that cancels the drag),
    // so they read frequently-changing values through these always-current references instead
    // of capturing them as pointerInput keys.
    val latestProject by rememberUpdatedState(project)
    val latestSelectedLayerIndex by rememberUpdatedState(selectedLayerIndex)
    val latestPerspectiveCorners by rememberUpdatedState(perspectiveCorners)
    val latestSplitComparePosition by rememberUpdatedState(splitComparePosition)
    val latestBrushSizeDp by rememberUpdatedState(effectiveBrushSizeDp)
    val latestOnBrushStrokeComplete by rememberUpdatedState(onBrushStrokeComplete)
    val latestOnLassoComplete by rememberUpdatedState(onLassoComplete)
    val latestOnRectangleSelection by rememberUpdatedState(onRectangleSelection)
    val latestOnEllipseSelection by rememberUpdatedState(onEllipseSelection)
    val latestOnPolygonComplete by rememberUpdatedState(onPolygonComplete)
    val latestOnMagicWandTap by rememberUpdatedState(onMagicWandTap)
    val latestOnUpdatePerspectiveCorner by rememberUpdatedState(onUpdatePerspectiveCorner)
    val latestOnUpdatePerspectiveCorners by rememberUpdatedState(onUpdatePerspectiveCorners)
    val latestOnUpdateLayerTransient by rememberUpdatedState(onUpdateLayerTransient)
    val latestOnCommitHistoryState by rememberUpdatedState(onCommitHistoryState)
    val latestOnApplyCropRect by rememberUpdatedState(onApplyCropRect)
    val latestOnSplitComparePositionChange by rememberUpdatedState(onSplitComparePositionChange)
    val latestOnStrokeSegment by rememberUpdatedState(onStrokeSegment)
    val latestMagneticSnap by rememberUpdatedState(isMagneticSnap)
    val latestMagicWandTolerance by rememberUpdatedState(magicWandTolerance)
    val latestOnMagicWandDrag by rememberUpdatedState(onMagicWandDrag)
    val latestEditableShape by rememberUpdatedState(editableShape)
    val latestShapeFromCenter by rememberUpdatedState(shapeFromCenter)
    val latestShapeLockSquare by rememberUpdatedState(shapeLockSquare)
    val latestOnShapeCreated by rememberUpdatedState(onShapeCreated)
    val latestOnShapeEdited by rememberUpdatedState(onShapeEdited)
    val latestOnMoveSelection by rememberUpdatedState(onMoveSelection)
    val haptic = LocalHapticFeedback.current

    // Transient gesture visuals.
    var wandBubble by remember { mutableStateOf<Pair<Offset, Int>?>(null) }
    var moveDragStart by remember { mutableStateOf<PointF?>(null) }
    var moveDelta by remember { mutableStateOf(Offset.Zero) }
    var editingShapeRect by remember { mutableStateOf<RectF?>(null) }
    // A finished move / shape edit is shown until the new mask arrives.
    LaunchedEffect(selectionMask) {
        moveDelta = Offset.Zero
        editingShapeRect = null
    }

    // Viewport size measurement for dynamic scale calculation
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    var autoFit by remember(project.id) { mutableStateOf(true) }

    val density = LocalDensity.current
    val padPx = remember(density) { with(density) { 32.dp.toPx() } }

    // Fit scale calculations
    val fitScale = remember(viewportWidth, viewportHeight, project.width, project.height, padPx) {
        if (viewportWidth > 0f && viewportHeight > 0f && project.width > 0 && project.height > 0) {
            val scaleW = (viewportWidth - padPx) / project.width.toFloat()
            val scaleH = (viewportHeight - padPx) / project.height.toFloat()
            min(scaleW, scaleH).coerceAtLeast(0.01f)
        } else 1.0f
    }

    val fitWidthScale = remember(viewportWidth, project.width, padPx) {
        if (viewportWidth > 0f && project.width > 0) {
            ((viewportWidth - padPx) / project.width.toFloat()).coerceAtLeast(0.01f)
        } else 1.0f
    }

    val minScale = remember(fitScale) {
        min(fitScale * 0.2f, 0.05f).coerceAtLeast(0.02f)
    }
    val maxScale = 10.0f

    // Zoom and Pan transform states
    var scale by remember { mutableFloatStateOf(1.0f) }
    var multiTouchActive by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Viewport math. The document centre is drawn at viewportCentre + offset, scaled by `scale`.
    val viewportScope = rememberCoroutineScope()
    var focusAnimationJob by remember { mutableStateOf<Job?>(null) }
    val viewportEdgeMarginPx = remember(density) { with(density) { 48.dp.toPx() } }

    fun screenToDoc(screen: Offset, viewportCenter: Offset): Offset {
        val p = latestProject
        val s = scale.coerceAtLeast(0.0001f)
        return Offset(
            (screen.x - viewportCenter.x - offsetX) / s + p.width / 2f,
            (screen.y - viewportCenter.y - offsetY) / s + p.height / 2f
        )
    }

    /** Offset that puts document point [doc] exactly in the middle of the viewport at [atScale]. */
    fun docToCenteredOffset(doc: Offset, atScale: Float): Offset {
        val p = latestProject
        return Offset(-(doc.x - p.width / 2f) * atScale, -(doc.y - p.height / 2f) * atScale)
    }

    /** Keeps at least a margin of the image on screen so a pinch can never throw it out of view. */
    fun clampViewportOffset(o: Offset, atScale: Float): Offset {
        val p = latestProject
        val limitX = ((p.width * atScale) / 2f + viewportWidth / 2f - viewportEdgeMarginPx).coerceAtLeast(0f)
        val limitY = ((p.height * atScale) / 2f + viewportHeight / 2f - viewportEdgeMarginPx).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-limitX, limitX), o.y.coerceIn(-limitY, limitY))
    }

    /**
     * Zoom buttons: keep whatever is in the middle of the screen in the middle. The offset is a
     * screen-space distance, so it must scale with the zoom factor or the view drifts.
     */
    fun zoomAroundViewportCenter(factor: Float) {
        focusAnimationJob?.cancel()
        val oldScale = scale
        val newScale = (oldScale * factor).coerceIn(minScale, maxScale)
        val applied = if (oldScale > 0f) newScale / oldScale else 1f
        scale = newScale
        val clamped = clampViewportOffset(Offset(offsetX * applied, offsetY * applied), newScale)
        offsetX = clamped.x
        offsetY = clamped.y
    }

    // While the user remains in Fit mode, keep the whole document visible when
    // contextual panels open/close or the window size changes.
    LaunchedEffect(fitScale, project.id, autoFit) {
        if (autoFit && viewportWidth > 0f && viewportHeight > 0f) {
            scale = fitScale
            offsetX = 0f
            offsetY = 0f
        }
    }

    // Photoshop-style Marching Ants Animation Phase (Alternating Black and White Dotted Lines)
    val infiniteTransition = rememberInfiniteTransition(label = "marching_ants")
    val marchingAntsPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ants_phase"
    )

    // Vector Contour boundary of the active selection mask for fast rendering
    // Every selection edit publishes a NEW mask bitmap (copy-on-write), so the mask reference alone
    // keys the contour. Tracing runs off the main thread so slider drags never wait for it.
    // selectionRevision also changes while a live stroke paints into the same mask object.
    val selectionContourPath by produceState<android.graphics.Path?>(initialValue = null, selectionMask, selectionRevision) {
        val mask = selectionMask
        value = if (mask != null && !mask.isRecycled) {
            withContext(Dispatchers.Default) { SelectionPathTracer.traceSelectionContour(mask) }
        } else {
            null
        }
    }

    // Touch points for selection gestures
    val currentPoints = remember { mutableStateListOf<PointF>() }
    var isStrokeActive by remember { mutableStateOf(false) }
    var previewClearJob by remember { mutableStateOf<Job?>(null) }

    // Remove the live stroke preview as soon as the finished step is reflected on the canvas
    // (a new selection mask, or a re-render after painting a layer mask) instead of leaving it
    // on screen until the next stroke starts.
    LaunchedEffect(selectionMask, renderRevision) {
        if (!isStrokeActive && currentPoints.isNotEmpty()) currentPoints.clear()
    }
    var shapeStart by remember { mutableStateOf<PointF?>(null) }
    var shapeEnd by remember { mutableStateOf<PointF?>(null) }
    val polygonPoints = remember { mutableStateListOf<PointF>() }

    LaunchedEffect(activeTool, activeSelectionTool) {
        if (activeTool != ActiveTool.SELECTION || activeSelectionTool != SelectionToolType.POLYGON) {
            polygonPoints.clear()
        }
    }

    var activeDraggingCorner by remember { mutableIntStateOf(-1) }
    var activePerspectiveHandle by remember { mutableIntStateOf(-1) }
    var perspectiveDragStartTouch by remember { mutableStateOf(Offset.Zero) }
    var perspectiveDragStartCorners by remember { mutableStateOf(listOf<PointF>()) }

    // Layer transform handle states
    var activeTransformHandle by remember { mutableStateOf(TransformHandle.NONE) }
    var initialTouchPos by remember { mutableStateOf(Offset.Zero) }
    var lastTouchPos by remember { mutableStateOf(Offset.Zero) }
    var initialLayerScaleX by remember { mutableFloatStateOf(1f) }
    var initialLayerScaleY by remember { mutableFloatStateOf(1f) }
    var liveTouchCursor by remember { mutableStateOf<Offset?>(null) }

    // Interactive Crop Handle & Rectangle states
    var cropRectState by remember(project.id, project.width, project.height) {
        mutableStateOf(RectF(0f, 0f, project.width.toFloat(), project.height.toFloat()))
    }
    var activeCropHandle by remember { mutableStateOf(CropHandle.NONE) }
    var cropDragStartTouch by remember { mutableStateOf(Offset.Zero) }
    var cropDragStartRect by remember { mutableStateOf(RectF()) }

    // Synchronize cropRectState when selectedAspectRatio changes
    LaunchedEffect(selectedAspectRatio, project.width, project.height) {
        if (activeTool == ActiveTool.CROP_PERSPECTIVE && !isPerspectiveActive) {
            val pWidth = project.width.toFloat()
            val pHeight = project.height.toFloat()
            if (selectedAspectRatio != null && selectedAspectRatio > 0f) {
                val targetW: Float
                val targetH: Float
                if (selectedAspectRatio >= (pWidth / pHeight)) {
                    targetW = pWidth
                    targetH = (pWidth / selectedAspectRatio).coerceAtMost(pHeight)
                } else {
                    targetH = pHeight
                    targetW = (pHeight * selectedAspectRatio).coerceAtMost(pWidth)
                }
                val left = (pWidth - targetW) / 2f
                val top = (pHeight - targetH) / 2f
                cropRectState = RectF(left, top, left + targetW, top + targetH)
            } else {
                cropRectState = RectF(0f, 0f, pWidth, pHeight)
            }
        }
    }

    // Snapping alignment guides
    var showSnapV by remember { mutableStateOf(false) }
    var showSnapH by remember { mutableStateOf(false) }

    var compositeBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var originalCompositeBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Reusable double-buffer pool for Proxy Preview rendering:
    // Prevents continuous GC allocation & RAM spikes during rapid slider scrubbing
    val previewBuffers = remember { arrayOfNulls<Bitmap>(2) }
    var activeBufferIndex by remember { mutableIntStateOf(0) }
    val renderGeneration = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    DisposableEffect(Unit) {
        onDispose {
            previewBuffers[0]?.let { if (!it.isRecycled) it.recycle() }
            previewBuffers[0] = null
            previewBuffers[1]?.let { if (!it.isRecycled) it.recycle() }
            previewBuffers[1] = null
        }
    }

    // Increase preview fidelity only when the user actually zooms in. Keeping this
    // bucketed avoids re-rendering on every tiny pinch delta.
    val zoomVsFit = if (fitScale > 0f) scale / fitScale else 1f
    val previewMaxDimension = when {
        zoomVsFit >= 3f -> 3072
        zoomVsFit >= 1.75f -> 2048
        else -> 1440
    }

    // Render composite layer stack asynchronously on Dispatchers.Default (Proxy Preview)
    // Conflates rapid slider movements and provides cooperative cancellation checks
    LaunchedEffect(project.id, project.width, project.height, renderRevision, previewMaxDimension, showSelectedLayerOriginal, showGeneralOriginal, isSplitCompareActive, selectedLayerIndex, selectionMask) {
        val gen = renderGeneration.incrementAndGet()

        // 1. Conflate/Debounce rapid slider micro-updates (~16ms = 1 frame window)
        // If a new slider update arrives before this frame begins, this coroutine is cancelled immediately
        // without touching CPU or allocating memory.
        delay(16L)
        if (gen != renderGeneration.get()) return@LaunchedEffect

        val layersSnapshot = project.layers.mapIndexed { index, layer ->
            layer.previewSnapshot().also { snapshot ->
                if (showGeneralOriginal) {
                    snapshot.filter = com.example.data.model.FilterType.NONE
                    snapshot.adjustments = com.example.data.model.ColorAdjustments()
                } else if (showSelectedLayerOriginal && index == selectedLayerIndex) {
                    snapshot.filter = com.example.data.model.FilterType.NONE
                    snapshot.adjustments = com.example.data.model.ColorAdjustments()
                }
            }
        }
        val w = project.width
        val h = project.height

        // Calculate preview target dimensions based on current zoom bucket
        val maxSide = max(w, h)
        val scaleRatio = if (maxSide > previewMaxDimension) previewMaxDimension.toFloat() / maxSide.toFloat() else 1.0f
        val targetW = (w * scaleRatio).toInt().coerceAtLeast(1)
        val targetH = (h * scaleRatio).toInt().coerceAtLeast(1)

        // Select the inactive buffer from our double-buffer pool
        val backBufferIndex = 1 - activeBufferIndex
        var backBuffer = previewBuffers[backBufferIndex]
        if (backBuffer == null || backBuffer.isRecycled || backBuffer.width != targetW || backBuffer.height != targetH) {
            backBuffer?.recycle()
            backBuffer = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            previewBuffers[backBufferIndex] = backBuffer
        }

        try {
            val rendered = withContext(Dispatchers.Default) {
                LayerRenderer.renderCompositeCanvas(
                    context = context,
                    layers = layersSnapshot,
                    width = w,
                    height = h,
                    includeBackground = false,
                    maxPreviewDimension = previewMaxDimension,
                    targetBitmap = backBuffer,
                    generationId = gen,
                    // Non-destructive adjustments/filters are stored per layer and always apply to the
                    // whole layer on save/export, so the preview must not scope them to the selection.
                    activeSelectionMask = null,
                    selectedLayerId = null
                )
            }

            // Swap buffers only if this generation is still the latest and the coroutine is active
            if (gen == renderGeneration.get() && currentCoroutineContext().isActive) {
                activeBufferIndex = backBufferIndex
                compositeBitmap = rendered
            }

            // If split comparison is active, also render the unadjusted original image
            if (isSplitCompareActive) {
                val originalLayersSnapshot = project.layers.map { layer ->
                    layer.previewSnapshot().also { snapshot ->
                        snapshot.filter = com.example.data.model.FilterType.NONE
                        snapshot.adjustments = com.example.data.model.ColorAdjustments()
                    }
                }
                val origRendered = withContext(Dispatchers.Default) {
                    LayerRenderer.renderCompositeCanvas(
                        context = context,
                        layers = originalLayersSnapshot,
                        width = w,
                        height = h,
                        includeBackground = false,
                        maxPreviewDimension = previewMaxDimension,
                        activeSelectionMask = null,
                        selectedLayerId = null
                    )
                }
                if (gen == renderGeneration.get() && currentCoroutineContext().isActive) {
                    originalCompositeBitmap = origRendered
                }
            }
        } catch (_: CancellationException) {
            // Cancelled cooperatively; back buffer is preserved in pool for the next render revision
        }
    }

    /**
     * Document-space corners (TL, TR, BR, BL as x,y pairs) of the box the renderer actually draws
     * for [layer]. Text layers are measured with the same StaticLayout the renderer uses and are
     * centred on their position; bitmap layers use the layer matrix.
     */
    fun layerCornersDoc(layer: Layer): FloatArray {
        val p = latestProject
        val bmp = layer.bitmap
        val w: Float
        val h: Float
        val matrix: android.graphics.Matrix
        if (bmp != null) {
            w = bmp.width.toFloat()
            h = bmp.height.toFloat()
            matrix = SelectionEngine.getLayerMatrix(layer, p.width, p.height)
        } else {
            val size = if (layer.type == com.example.data.model.LayerType.TEXT) {
                LayerRenderer.measureTextLayer(context, layer, p.width.toFloat())
            } else {
                p.width.toFloat() to p.height.toFloat()
            }
            w = size.first
            h = size.second
            matrix = android.graphics.Matrix().apply {
                postTranslate(-w / 2f, -h / 2f)
                postScale(layer.scaleX, layer.scaleY)
                postRotate(layer.rotation)
                postTranslate(p.width / 2f + layer.positionX, p.height / 2f + layer.positionY)
            }
        }
        val pts = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        matrix.mapPoints(pts)
        return pts
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { sz ->
                viewportWidth = sz.width.toFloat()
                viewportHeight = sz.height.toFloat()
            }
            .testTag("canvas_viewport")
            .semantics { contentDescription = canvasAccessibilityDescription }
            .pointerInput(activeTool, minScale, maxScale, isSplitCompareActive) {
                awaitEachGesture {
                    // A new touch always wins over a running "centre the zoomed area" animation.
                    focusAnimationJob?.cancel()
                    var pinchFocusDoc: Offset? = null   // document point the user started pinching on
                    var pinchStartScale = scale
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        // One finger pans the viewport only when the active tool has no one-finger
                        // gesture of its own: painting a layer mask (Layers) and dragging a text
                        // layer (Text) must receive the drag instead.
                        val liveLayer = latestProject.layers.getOrNull(latestSelectedLayerIndex)
                        val toolOwnsOneFinger = (activeTool == ActiveTool.LAYERS && liveLayer?.isEditingMask == true) ||
                            (activeTool == ActiveTool.TEXT && liveLayer?.type == com.example.data.model.LayerType.TEXT)
                        val oneFingerViewportPan = !isSplitCompareActive && !toolOwnsOneFinger && (activeTool == ActiveTool.NONE ||
                            activeTool == ActiveTool.ADJUST || activeTool == ActiveTool.FILTERS ||
                            activeTool == ActiveTool.LAYERS || activeTool == ActiveTool.TEXT)
                        if (pressed >= 2) multiTouchActive = true
                        val shouldTransformViewport = pressed >= 2 || (pressed == 1 && oneFingerViewportPan)
                        if (shouldTransformViewport) {
                            // Only pointers that were down in BOTH the previous and the current event
                            // are used. Compose's calculatePan/Zoom mix a finger that just landed or
                            // lifted into one side of the comparison, which made the view jump.
                            val stable = event.changes.filter { it.pressed && it.previousPressed }
                            if (stable.isNotEmpty()) {
                                var curX = 0f; var curY = 0f; var prevX = 0f; var prevY = 0f
                                for (c in stable) {
                                    curX += c.position.x; curY += c.position.y
                                    prevX += c.previousPosition.x; prevY += c.previousPosition.y
                                }
                                val cnt = stable.size.toFloat()
                                val curCentroid = Offset(curX / cnt, curY / cnt)
                                val prevCentroid = Offset(prevX / cnt, prevY / cnt)

                                var zoom = 1f
                                if (stable.size >= 2) {
                                    var curSpread = 0f; var prevSpread = 0f
                                    for (c in stable) {
                                        curSpread += (c.position - curCentroid).getDistance()
                                        prevSpread += (c.previousPosition - prevCentroid).getDistance()
                                    }
                                    if (prevSpread > 1f && curSpread > 1f) zoom = curSpread / prevSpread
                                }

                                val center = Offset(size.width / 2f, size.height / 2f)
                                if (stable.size >= 2 && pinchFocusDoc == null) {
                                    pinchFocusDoc = screenToDoc(prevCentroid, center)
                                    pinchStartScale = scale
                                }

                                val oldScale = scale
                                val newScale = (oldScale * zoom).coerceIn(minScale, maxScale)
                                val appliedZoom = if (oldScale > 0f) newScale / oldScale else 1f
                                // Exact anchoring: the document point that was under the fingers stays
                                // under the fingers (and follows them when they move).
                                val oldOffset = Offset(offsetX, offsetY)
                                val newOffset = curCentroid - center - (prevCentroid - center - oldOffset) * appliedZoom
                                scale = newScale
                                val clamped = clampViewportOffset(newOffset, newScale)
                                offsetX = clamped.x
                                offsetY = clamped.y
                                autoFit = false
                            }
                            activeTransformHandle = TransformHandle.NONE
                            event.changes.forEach { change -> if (change.pressed) change.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    multiTouchActive = false

                    // After a zoom-in pinch, bring the area the user pinched on to the centre of the view.
                    val focus = pinchFocusDoc
                    if (focus != null && scale > pinchStartScale * 1.05f) {
                        val target = clampViewportOffset(docToCenteredOffset(focus, scale), scale)
                        val startX = offsetX
                        val startY = offsetY
                        focusAnimationJob = viewportScope.launch {
                            animate(0f, 1f, animationSpec = tween(durationMillis = 260)) { t, _ ->
                                offsetX = startX + (target.x - startX) * t
                                offsetY = startY + (target.y - startY) * t
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(
                    // Only values that select WHICH gesture detector runs are keys. Values that change
                    // during a drag (zoom/pan, layer transform, perspective corners, render revision)
                    // are read live; keying on them restarted the detector and cancelled the drag.
                    activeTool,
                    activeSelectionTool,
                    inpaintSelectionMode,
                    isPerspectiveActive,
                    selectedLayerIndex,
                    selectedLayerType,
                    isEditingSelectedMask,
                    selectedAspectRatio,
                    multiTouchActive,
                    isSplitCompareActive,
                    project.id
                ) {
                    if (multiTouchActive) return@pointerInput
                    // Viewport geometry is evaluated on every event so zoom/pan changes apply immediately.
                    fun curDrawW() = (latestProject.width.toFloat().coerceAtLeast(1f) * scale).coerceAtLeast(1f)
                    fun curDrawH() = (latestProject.height.toFloat().coerceAtLeast(1f) * scale).coerceAtLeast(1f)
                    fun curStartX() = (size.width - curDrawW()) / 2f + offsetX
                    fun curStartY() = (size.height - curDrawH()) / 2f + offsetY
                    fun toDoc(o: Offset): PointF = PointF(
                        ((o.x - (size.width / 2f) - offsetX) / scale) + (latestProject.width / 2f),
                        ((o.y - (size.height / 2f) - offsetY) / scale) + (latestProject.height / 2f)
                    )
                    fun toScreen(p: PointF): Offset = Offset(
                        curStartX() + (p.x / latestProject.width) * curDrawW(),
                        curStartY() + (p.y / latestProject.height) * curDrawH()
                    )
                    fun clampDoc(p: PointF): PointF = PointF(
                        p.x.coerceIn(0f, latestProject.width.toFloat()),
                        p.y.coerceIn(0f, latestProject.height.toFloat())
                    )
                    /** Live magnetic snapping against the on-screen preview image (document proportional). */
                    fun snapIfMagnetic(p: PointF): PointF {
                        if (!latestMagneticSnap) return p
                        val bmp = compositeBitmap ?: return p
                        if (bmp.isRecycled) return p
                        val rx = bmp.width / latestProject.width.toFloat()
                        val ry = bmp.height / latestProject.height.toFloat()
                        val radius = (with(density) { 14.dp.toPx() } / (scale * latestProject.width / bmp.width.toFloat()).coerceAtLeast(0.01f))
                            .toInt().coerceIn(3, 24)
                        val s = SelectionEngine.snapPointToEdge(PointF(p.x * rx, p.y * ry), bmp, searchRadius = radius)
                        return PointF(s.x / rx, s.y / ry)
                    }

                    if (isSplitCompareActive) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dxFraction = dragAmount.x / curDrawW()
                                val newRatio = (latestSplitComparePosition + dxFraction).coerceIn(0.05f, 0.95f)
                                latestOnSplitComparePositionChange?.invoke(newRatio)
                            }
                        )
                    } else if (isPerspectiveActive && latestPerspectiveCorners.size >= 4) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                perspectiveDragStartTouch = startOffset
                                perspectiveDragStartCorners = latestPerspectiveCorners.map { PointF(it.x, it.y) }

                                val c0 = latestPerspectiveCorners[0]
                                val c1 = latestPerspectiveCorners[1]
                                val c2 = latestPerspectiveCorners[2]
                                val c3 = latestPerspectiveCorners[3]

                                val sc0 = Offset(curStartX() + ((c0.x / latestProject.width) * curDrawW()), curStartY() + ((c0.y / latestProject.height) * curDrawH()))
                                val sc1 = Offset(curStartX() + ((c1.x / latestProject.width) * curDrawW()), curStartY() + ((c1.y / latestProject.height) * curDrawH()))
                                val sc2 = Offset(curStartX() + ((c2.x / latestProject.width) * curDrawW()), curStartY() + ((c2.y / latestProject.height) * curDrawH()))
                                val sc3 = Offset(curStartX() + ((c3.x / latestProject.width) * curDrawW()), curStartY() + ((c3.y / latestProject.height) * curDrawH()))

                                val cornerThreshold = 44.dp.toPx()
                                val edgeThreshold = 36.dp.toPx()

                                fun distSq(p1: Offset, p2: Offset): Float {
                                    val dx = p1.x - p2.x; val dy = p1.y - p2.y
                                    return dx * dx + dy * dy
                                }

                                val cThreshSq = cornerThreshold * cornerThreshold
                                val eThreshSq = edgeThreshold * edgeThreshold

                                val topMid = Offset((sc0.x + sc1.x) / 2f, (sc0.y + sc1.y) / 2f)
                                val rightMid = Offset((sc1.x + sc2.x) / 2f, (sc1.y + sc2.y) / 2f)
                                val bottomMid = Offset((sc3.x + sc2.x) / 2f, (sc3.y + sc2.y) / 2f)
                                val leftMid = Offset((sc0.x + sc3.x) / 2f, (sc0.y + sc3.y) / 2f)

                                activePerspectiveHandle = when {
                                    distSq(startOffset, sc0) < cThreshSq -> 0
                                    distSq(startOffset, sc1) < cThreshSq -> 1
                                    distSq(startOffset, sc2) < cThreshSq -> 2
                                    distSq(startOffset, sc3) < cThreshSq -> 3
                                    distSq(startOffset, topMid) < eThreshSq -> 4
                                    distSq(startOffset, rightMid) < eThreshSq -> 5
                                    distSq(startOffset, bottomMid) < eThreshSq -> 6
                                    distSq(startOffset, leftMid) < eThreshSq -> 7
                                    else -> {
                                        val poly = listOf(sc0, sc1, sc2, sc3)
                                        var inside = false
                                        var j = poly.size - 1
                                        for (i in poly.indices) {
                                            if ((poly[i].y > startOffset.y) != (poly[j].y > startOffset.y) &&
                                                (startOffset.x < (poly[j].x - poly[i].x) * (startOffset.y - poly[i].y) / (poly[j].y - poly[i].y) + poly[i].x)
                                            ) {
                                                inside = !inside
                                            }
                                            j = i
                                        }
                                        if (inside) 8 else -1
                                    }
                                }
                                activeDraggingCorner = activePerspectiveHandle
                            },
                            onDrag = { change, _ ->
                                if (activePerspectiveHandle != -1 && perspectiveDragStartCorners.size >= 4) {
                                    change.consume()
                                    val dxDoc = ((change.position.x - perspectiveDragStartTouch.x) / curDrawW()) * latestProject.width
                                    val dyDoc = ((change.position.y - perspectiveDragStartTouch.y) / curDrawH()) * latestProject.height

                                    val pw = latestProject.width.toFloat()
                                    val ph = latestProject.height.toFloat()
                                    val newCorners = perspectiveDragStartCorners.map { PointF(it.x, it.y) }.toMutableList()

                                    when (activePerspectiveHandle) {
                                        0 -> newCorners[0] = PointF((perspectiveDragStartCorners[0].x + dxDoc).coerceIn(0f, pw), (perspectiveDragStartCorners[0].y + dyDoc).coerceIn(0f, ph))
                                        1 -> newCorners[1] = PointF((perspectiveDragStartCorners[1].x + dxDoc).coerceIn(0f, pw), (perspectiveDragStartCorners[1].y + dyDoc).coerceIn(0f, ph))
                                        2 -> newCorners[2] = PointF((perspectiveDragStartCorners[2].x + dxDoc).coerceIn(0f, pw), (perspectiveDragStartCorners[2].y + dyDoc).coerceIn(0f, ph))
                                        3 -> newCorners[3] = PointF((perspectiveDragStartCorners[3].x + dxDoc).coerceIn(0f, pw), (perspectiveDragStartCorners[3].y + dyDoc).coerceIn(0f, ph))
                                        4 -> { // Top edge moves corners 0 and 1
                                            newCorners[0] = PointF((perspectiveDragStartCorners[0].x + dxDoc).coerceIn(0f, pw), (perspectiveDragStartCorners[0].y + dyDoc).coerceIn(0f, ph))
                                            newCorners[1] = PointF((perspectiveDragStartCorners[1].x + dxDoc).coerceIn(0f, pw), (perspectiveDragStartCorners[1].y + dyDoc).coerceIn(0f, ph))
                                        }
                                        5 -> { // Right edge moves corners 1 and 2
                                            newCorners[1] = PointF((perspectiveDragStartCorners[1].x + dxDoc).coerceIn(0f, pw), (perspectiveDragStartCorners[1].y + dyDoc).coerceIn(0f, ph))
                                            newCorners[2] = PointF((perspectiveDragStartCorners[2].x + dxDoc).coerceIn(0f, pw), (perspectiveDragStartCorners[2].y + dyDoc).coerceIn(0f, ph))
                                        }
                                        6 -> { // Bottom edge moves corners 3 and 2
                                            newCorners[3] = PointF((perspectiveDragStartCorners[3].x + dxDoc).coerceIn(0f, pw), (perspectiveDragStartCorners[3].y + dyDoc).coerceIn(0f, ph))
                                            newCorners[2] = PointF((perspectiveDragStartCorners[2].x + dxDoc).coerceIn(0f, pw), (perspectiveDragStartCorners[2].y + dyDoc).coerceIn(0f, ph))
                                        }
                                        7 -> { // Left edge moves corners 0 and 3
                                            newCorners[0] = PointF((perspectiveDragStartCorners[0].x + dxDoc).coerceIn(0f, pw), (perspectiveDragStartCorners[0].y + dyDoc).coerceIn(0f, ph))
                                            newCorners[3] = PointF((perspectiveDragStartCorners[3].x + dxDoc).coerceIn(0f, pw), (perspectiveDragStartCorners[3].y + dyDoc).coerceIn(0f, ph))
                                        }
                                        8 -> { // Body translates all 4 corners
                                            for (i in 0..3) {
                                                newCorners[i] = PointF((perspectiveDragStartCorners[i].x + dxDoc).coerceIn(0f, pw), (perspectiveDragStartCorners[i].y + dyDoc).coerceIn(0f, ph))
                                            }
                                        }
                                    }

                                    val updateAll = latestOnUpdatePerspectiveCorners
                                    if (updateAll != null) {
                                        updateAll(newCorners)
                                    } else {
                                        for (i in newCorners.indices) {
                                            latestOnUpdatePerspectiveCorner(i, newCorners[i].x, newCorners[i].y)
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                activePerspectiveHandle = -1
                                activeDraggingCorner = -1
                            }
                        )
                    } else if (activeTool == ActiveTool.CROP_PERSPECTIVE && !isPerspectiveActive) {
                        // Interactive Crop Drag Handling
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                cropDragStartTouch = startOffset
                                cropDragStartRect = RectF(cropRectState)

                                val pW = latestProject.width.toFloat().coerceAtLeast(1f)
                                val pH = latestProject.height.toFloat().coerceAtLeast(1f)
                                val scL = curStartX() + (cropRectState.left / pW) * curDrawW()
                                val scT = curStartY() + (cropRectState.top / pH) * curDrawH()
                                val scR = curStartX() + (cropRectState.right / pW) * curDrawW()
                                val scB = curStartY() + (cropRectState.bottom / pH) * curDrawH()

                                val hitThreshold = 36.dp.toPx()

                                activeCropHandle = when {
                                    sqrt((startOffset.x - scL) * (startOffset.x - scL) + (startOffset.y - scT) * (startOffset.y - scT)) < hitThreshold -> CropHandle.CORNER_TL
                                    sqrt((startOffset.x - scR) * (startOffset.x - scR) + (startOffset.y - scT) * (startOffset.y - scT)) < hitThreshold -> CropHandle.CORNER_TR
                                    sqrt((startOffset.x - scR) * (startOffset.x - scR) + (startOffset.y - scB) * (startOffset.y - scB)) < hitThreshold -> CropHandle.CORNER_BR
                                    sqrt((startOffset.x - scL) * (startOffset.x - scL) + (startOffset.y - scB) * (startOffset.y - scB)) < hitThreshold -> CropHandle.CORNER_BL
                                    selectedAspectRatio == null && abs(startOffset.y - scT) < hitThreshold && startOffset.x in scL..scR -> CropHandle.EDGE_T
                                    selectedAspectRatio == null && abs(startOffset.y - scB) < hitThreshold && startOffset.x in scL..scR -> CropHandle.EDGE_B
                                    selectedAspectRatio == null && abs(startOffset.x - scL) < hitThreshold && startOffset.y in scT..scB -> CropHandle.EDGE_L
                                    selectedAspectRatio == null && abs(startOffset.x - scR) < hitThreshold && startOffset.y in scT..scB -> CropHandle.EDGE_R
                                    startOffset.x in scL..scR && startOffset.y in scT..scB -> CropHandle.BODY
                                    else -> CropHandle.NONE
                                }
                            },
                            onDrag = { change, dragAmount ->
                                if (activeCropHandle != CropHandle.NONE) {
                                    change.consume()
                                    val pWidth = latestProject.width.toFloat().coerceAtLeast(1f)
                                    val pHeight = latestProject.height.toFloat().coerceAtLeast(1f)
                                    val dxDoc = (dragAmount.x / curDrawW()) * pWidth
                                    val dyDoc = (dragAmount.y / curDrawH()) * pHeight

                                    if (dxDoc.isNaN() || dyDoc.isNaN() || dxDoc.isInfinite() || dyDoc.isInfinite()) {
                                        return@detectDragGestures
                                    }

                                    val minCropSize = 40f

                                    when (activeCropHandle) {
                                        CropHandle.BODY -> {
                                            val w = cropRectState.width().coerceIn(minCropSize, pWidth)
                                            val h = cropRectState.height().coerceIn(minCropSize, pHeight)
                                            val maxL = (pWidth - w).coerceAtLeast(0f)
                                            val maxT = (pHeight - h).coerceAtLeast(0f)
                                            val left = (cropRectState.left + dxDoc).coerceIn(0f, maxL)
                                            val top = (cropRectState.top + dyDoc).coerceIn(0f, maxT)
                                            cropRectState = RectF(left, top, left + w, top + h)
                                        }

                                        CropHandle.CORNER_TL -> {
                                            val maxL = (cropRectState.right - minCropSize).coerceAtLeast(0f)
                                            val newL = (cropRectState.left + dxDoc).coerceIn(0f, maxL)
                                            val maxT = (cropRectState.bottom - minCropSize).coerceAtLeast(0f)
                                            val newT = if (selectedAspectRatio != null && selectedAspectRatio > 0f) {
                                                val currW = (cropRectState.right - newL).coerceAtLeast(minCropSize)
                                                val targetH = currW / selectedAspectRatio
                                                (cropRectState.bottom - targetH).coerceIn(0f, maxT)
                                            } else {
                                                (cropRectState.top + dyDoc).coerceIn(0f, maxT)
                                            }
                                            cropRectState = RectF(newL, newT, cropRectState.right, cropRectState.bottom)
                                        }

                                        CropHandle.CORNER_TR -> {
                                            val minR = (cropRectState.left + minCropSize).coerceAtMost(pWidth)
                                            val newR = (cropRectState.right + dxDoc).coerceIn(minR, pWidth)
                                            val maxT = (cropRectState.bottom - minCropSize).coerceAtLeast(0f)
                                            val newT = if (selectedAspectRatio != null && selectedAspectRatio > 0f) {
                                                val currW = (newR - cropRectState.left).coerceAtLeast(minCropSize)
                                                val targetH = currW / selectedAspectRatio
                                                (cropRectState.bottom - targetH).coerceIn(0f, maxT)
                                            } else {
                                                (cropRectState.top + dyDoc).coerceIn(0f, maxT)
                                            }
                                            cropRectState = RectF(cropRectState.left, newT, newR, cropRectState.bottom)
                                        }

                                        CropHandle.CORNER_BR -> {
                                            val minR = (cropRectState.left + minCropSize).coerceAtMost(pWidth)
                                            val newR = (cropRectState.right + dxDoc).coerceIn(minR, pWidth)
                                            val minB = (cropRectState.top + minCropSize).coerceAtMost(pHeight)
                                            val newB = if (selectedAspectRatio != null && selectedAspectRatio > 0f) {
                                                val currW = (newR - cropRectState.left).coerceAtLeast(minCropSize)
                                                val targetH = currW / selectedAspectRatio
                                                (cropRectState.top + targetH).coerceIn(minB, pHeight)
                                            } else {
                                                (cropRectState.bottom + dyDoc).coerceIn(minB, pHeight)
                                            }
                                            cropRectState = RectF(cropRectState.left, cropRectState.top, newR, newB)
                                        }

                                        CropHandle.CORNER_BL -> {
                                            val maxL = (cropRectState.right - minCropSize).coerceAtLeast(0f)
                                            val newL = (cropRectState.left + dxDoc).coerceIn(0f, maxL)
                                            val minB = (cropRectState.top + minCropSize).coerceAtMost(pHeight)
                                            val newB = if (selectedAspectRatio != null && selectedAspectRatio > 0f) {
                                                val currW = (cropRectState.right - newL).coerceAtLeast(minCropSize)
                                                val targetH = currW / selectedAspectRatio
                                                (cropRectState.top + targetH).coerceIn(minB, pHeight)
                                            } else {
                                                (cropRectState.bottom + dyDoc).coerceIn(minB, pHeight)
                                            }
                                            cropRectState = RectF(newL, cropRectState.top, cropRectState.right, newB)
                                        }

                                        CropHandle.EDGE_T -> {
                                            val maxT = (cropRectState.bottom - minCropSize).coerceAtLeast(0f)
                                            val newT = (cropRectState.top + dyDoc).coerceIn(0f, maxT)
                                            cropRectState = RectF(cropRectState.left, newT, cropRectState.right, cropRectState.bottom)
                                        }

                                        CropHandle.EDGE_B -> {
                                            val minB = (cropRectState.top + minCropSize).coerceAtMost(pHeight)
                                            val newB = (cropRectState.bottom + dyDoc).coerceIn(minB, pHeight)
                                            cropRectState = RectF(cropRectState.left, cropRectState.top, cropRectState.right, newB)
                                        }

                                        CropHandle.EDGE_L -> {
                                            val maxL = (cropRectState.right - minCropSize).coerceAtLeast(0f)
                                            val newL = (cropRectState.left + dxDoc).coerceIn(0f, maxL)
                                            cropRectState = RectF(newL, cropRectState.top, cropRectState.right, cropRectState.bottom)
                                        }

                                        CropHandle.EDGE_R -> {
                                            val minR = (cropRectState.left + minCropSize).coerceAtMost(pWidth)
                                            val newR = (cropRectState.right + dxDoc).coerceIn(minR, pWidth)
                                            cropRectState = RectF(cropRectState.left, cropRectState.top, newR, cropRectState.bottom)
                                        }

                                        else -> {}
                                    }
                                }
                            },
                            onDragEnd = {
                                activeCropHandle = CropHandle.NONE
                                latestOnApplyCropRect?.invoke(cropRectState)
                            }
                        )
                    } else if (activeTool == ActiveTool.SELECTION || activeTool == ActiveTool.OBJECT_REMOVAL || (activeTool == ActiveTool.LAYERS && selectedLayerIndex in latestProject.layers.indices && latestProject.layers[selectedLayerIndex].isEditingMask)) {
                        if (activeSelectionTool == SelectionToolType.MAGIC_WAND && activeTool == ActiveTool.SELECTION) {
                            // Tap = select at the current tolerance. Press and drag sideways = change the
                            // tolerance live (right = more, left = less), like Procreate's selection tools.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val downDoc = toDoc(down.position)
                                val insideDoc = downDoc.x in 0f..latestProject.width.toFloat() && downDoc.y in 0f..latestProject.height.toFloat()
                                val startTolerance = latestMagicWandTolerance
                                val slop = viewConfiguration.touchSlop
                                val pxPerStep = with(density) { 3.dp.toPx() }
                                var dragging = false
                                var lastTolerance = startTolerance
                                var released = false
                                while (!released) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (event.changes.count { it.pressed } > 1) { wandBubble = null; return@awaitEachGesture }
                                    if (!change.pressed) { released = true; break }
                                    val dx = change.position.x - down.position.x
                                    if (!dragging && abs(dx) > slop && insideDoc) {
                                        dragging = true
                                        latestOnMagicWandDrag?.invoke(downDoc.x.toInt(), downDoc.y.toInt(), startTolerance, com.example.ui.viewmodel.StrokePhase.START)
                                    }
                                    if (dragging) {
                                        change.consume()
                                        val tol = (startTolerance + (dx / pxPerStep).toInt()).coerceIn(0, 100)
                                        wandBubble = change.position to tol
                                        if (tol != lastTolerance) {
                                            lastTolerance = tol
                                            latestOnMagicWandDrag?.invoke(downDoc.x.toInt(), downDoc.y.toInt(), tol, com.example.ui.viewmodel.StrokePhase.MOVE)
                                        }
                                    }
                                }
                                wandBubble = null
                                if (dragging) {
                                    latestOnMagicWandDrag?.invoke(downDoc.x.toInt(), downDoc.y.toInt(), lastTolerance, com.example.ui.viewmodel.StrokePhase.END)
                                } else if (released && insideDoc) {
                                    latestOnMagicWandTap(downDoc.x.toInt(), downDoc.y.toInt())
                                }
                            }
                        } else if (activeSelectionTool == SelectionToolType.POLYGON && activeTool == ActiveTool.SELECTION) {
                            coroutineScope {
                                // Taps add points; tapping the first point or double-tapping closes the shape.
                                launch {
                                    detectTapGestures(
                                        onDoubleTap = {
                                            if (polygonPoints.size >= 3) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                latestOnPolygonComplete?.invoke(polygonPoints.toList())
                                                polygonPoints.clear()
                                            }
                                        },
                                        onTap = { offset ->
                                            val doc = toDoc(offset)
                                            if (doc.x in 0f..latestProject.width.toFloat() && doc.y in 0f..latestProject.height.toFloat()) {
                                                if (polygonPoints.size >= 3) {
                                                    val first = toScreen(polygonPoints.first())
                                                    val closeThresholdPx = with(density) { 36.dp.toPx() }
                                                    if ((offset - first).getDistance() <= closeThresholdPx) {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        latestOnPolygonComplete?.invoke(polygonPoints.toList())
                                                        polygonPoints.clear()
                                                        return@detectTapGestures
                                                    }
                                                }
                                                polygonPoints.add(snapIfMagnetic(doc))
                                            }
                                        }
                                    )
                                }
                                // Drag an existing point to adjust it.
                                launch {
                                    var dragIndex = -1
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            val grab = with(density) { 28.dp.toPx() }
                                            dragIndex = polygonPoints.indices.minByOrNull { (toScreen(polygonPoints[it]) - offset).getDistance() }
                                                ?.takeIf { (toScreen(polygonPoints[it]) - offset).getDistance() <= grab } ?: -1
                                            if (dragIndex >= 0) {
                                                liveTouchCursor = offset
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        },
                                        onDrag = { change, _ ->
                                            if (dragIndex in polygonPoints.indices) {
                                                change.consume()
                                                liveTouchCursor = change.position
                                                polygonPoints[dragIndex] = snapIfMagnetic(clampDoc(toDoc(change.position)))
                                            }
                                        },
                                        onDragEnd = { dragIndex = -1; liveTouchCursor = null },
                                        onDragCancel = { dragIndex = -1; liveTouchCursor = null }
                                    )
                                }
                            }
                        } else if (activeSelectionTool == SelectionToolType.MOVE_SELECTION && activeTool == ActiveTool.SELECTION) {
                            detectDragGestures(
                                onDragStart = { offset -> moveDragStart = toDoc(offset); moveDelta = Offset.Zero },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val s = moveDragStart ?: return@detectDragGestures
                                    val p = toDoc(change.position)
                                    moveDelta = Offset(p.x - s.x, p.y - s.y)
                                },
                                onDragEnd = {
                                    val d = moveDelta
                                    moveDragStart = null
                                    if (d != Offset.Zero) latestOnMoveSelection?.invoke(d.x, d.y) else moveDelta = Offset.Zero
                                },
                                onDragCancel = { moveDragStart = null; moveDelta = Offset.Zero }
                            )
                        } else if ((activeTool == ActiveTool.SELECTION && (activeSelectionTool == SelectionToolType.RECTANGLE || activeSelectionTool == SelectionToolType.ELLIPSE)) ||
                            (activeTool == ActiveTool.OBJECT_REMOVAL && inpaintSelectionMode == com.example.ui.viewmodel.InpaintSelectionMode.RECTANGLE)
                        ) {
                            val isEllipseTool = activeTool == ActiveTool.SELECTION && activeSelectionTool == SelectionToolType.ELLIPSE
                            var editMode = 0 // 0 = new shape, 1 = move, 2..5 = resize from corner TL, TR, BR, BL
                            var editStartRect: RectF? = null
                            var dragStartDoc = PointF()
                            detectDragGestures(
                                onDragStart = { offset ->
                                    liveTouchCursor = offset
                                    val doc = clampDoc(toDoc(offset))
                                    dragStartDoc = doc
                                    val existing = latestEditableShape?.takeIf { it.isEllipse == isEllipseTool }
                                    editMode = 0
                                    editStartRect = null
                                    if (existing != null) {
                                        val r = RectF(existing.left, existing.top, existing.right, existing.bottom)
                                        val corners = listOf(PointF(r.left, r.top), PointF(r.right, r.top), PointF(r.right, r.bottom), PointF(r.left, r.bottom))
                                        val grab = with(density) { 30.dp.toPx() }
                                        val hit = corners.indices.firstOrNull { (toScreen(corners[it]) - offset).getDistance() <= grab }
                                        if (hit != null) {
                                            editMode = 2 + hit
                                            editStartRect = r
                                        } else if (r.contains(doc.x, doc.y)) {
                                            editMode = 1
                                            editStartRect = r
                                        }
                                        if (editMode != 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    if (editMode == 0) {
                                        shapeStart = doc
                                        shapeEnd = doc
                                    } else {
                                        editingShapeRect = editStartRect
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    liveTouchCursor = change.position
                                    val doc = clampDoc(toDoc(change.position))
                                    val start = editStartRect
                                    if (editMode == 0) {
                                        shapeEnd = doc
                                    } else if (start != null) {
                                        editingShapeRect = when (editMode) {
                                            1 -> {
                                                val dx = doc.x - dragStartDoc.x
                                                val dy = doc.y - dragStartDoc.y
                                                RectF(start.left + dx, start.top + dy, start.right + dx, start.bottom + dy)
                                            }
                                            else -> {
                                                // Opposite corner stays fixed.
                                                val fixed = when (editMode) {
                                                    2 -> PointF(start.right, start.bottom)
                                                    3 -> PointF(start.left, start.bottom)
                                                    4 -> PointF(start.left, start.top)
                                                    else -> PointF(start.right, start.top)
                                                }
                                                constrainedShapeRect(fixed, doc, fromCenter = false, square = latestShapeLockSquare)
                                            }
                                        }
                                    }
                                },
                                onDragEnd = {
                                    liveTouchCursor = null
                                    if (editMode == 0) {
                                        val a = shapeStart
                                        val b = shapeEnd
                                        if (a != null && b != null) {
                                            val r = constrainedShapeRect(a, b, latestShapeFromCenter, latestShapeLockSquare)
                                            if (activeTool == ActiveTool.OBJECT_REMOVAL) {
                                                latestOnRectangleSelection?.invoke(PointF(r.left, r.top), PointF(r.right, r.bottom))
                                            } else {
                                                latestOnShapeCreated?.invoke(r, isEllipseTool)
                                            }
                                        }
                                        shapeStart = null
                                        shapeEnd = null
                                    } else {
                                        editingShapeRect?.let { latestOnShapeEdited?.invoke(RectF(it)) }
                                    }
                                },
                                onDragCancel = {
                                    liveTouchCursor = null
                                    shapeStart = null
                                    shapeEnd = null
                                    editingShapeRect = null
                                }
                            )
                        } else {
                            val isLassoTool = (activeSelectionTool == SelectionToolType.LASSO && activeTool == ActiveTool.SELECTION) ||
                                (activeTool == ActiveTool.OBJECT_REMOVAL && inpaintSelectionMode == com.example.ui.viewmodel.InpaintSelectionMode.LASSO)
                            var strokeRadius = 0f
                            var emittedCount = 0
                            var lastEmitTime = 0L
                            fun emitSegment(phase: com.example.ui.viewmodel.StrokePhase) {
                                val segmentFn = latestOnStrokeSegment ?: return
                                // Overlap one point so consecutive segments join without gaps.
                                val from = (emittedCount - 1).coerceAtLeast(0)
                                val pts = if (from < currentPoints.size) currentPoints.subList(from, currentPoints.size).toList() else emptyList()
                                emittedCount = currentPoints.size
                                lastEmitTime = android.os.SystemClock.uptimeMillis()
                                segmentFn(pts, strokeRadius, phase)
                            }
                            detectDragGestures(
                                onDragStart = { offset ->
                                    previewClearJob?.cancel()
                                    isStrokeActive = true
                                    liveTouchCursor = offset
                                    currentPoints.clear()
                                    val first = toDoc(offset)
                                    currentPoints.add(if (isLassoTool) snapIfMagnetic(first) else first)
                                    val screenRadius = with(density) { (latestBrushSizeDp.dp.toPx()) / 2f }
                                    val docScale = curDrawW() / latestProject.width.toFloat()
                                    strokeRadius = if (docScale > 0f) screenRadius / docScale else latestBrushSizeDp
                                    if (!isLassoTool && latestOnStrokeSegment != null) {
                                        currentPoints.add(PointF(first.x, first.y)) // a dot for the first touch
                                        emittedCount = 0
                                        emitSegment(com.example.ui.viewmodel.StrokePhase.START)
                                    }
                                },
                                onDrag = { change, _ ->
                                    liveTouchCursor = change.position
                                    val p = toDoc(change.position)
                                    currentPoints.add(if (isLassoTool) snapIfMagnetic(p) else p)
                                    if (!isLassoTool && latestOnStrokeSegment != null) {
                                        val now = android.os.SystemClock.uptimeMillis()
                                        if (now - lastEmitTime >= 45 || currentPoints.size - emittedCount >= 10) {
                                            emitSegment(com.example.ui.viewmodel.StrokePhase.MOVE)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    isStrokeActive = false
                                    liveTouchCursor = null
                                    if (isLassoTool) {
                                        latestOnLassoComplete(currentPoints.toList())
                                    } else if (latestOnStrokeSegment != null) {
                                        emitSegment(com.example.ui.viewmodel.StrokePhase.END)
                                    } else {
                                        latestOnBrushStrokeComplete(currentPoints.toList(), strokeRadius)
                                    }
                                    // The preview stays only until the real result is published (see the
                                    // LaunchedEffect on the mask); this is a safety net for no-op strokes.
                                    previewClearJob = viewportScope.launch {
                                        delay(1200)
                                        if (!isStrokeActive) currentPoints.clear()
                                    }
                                },
                                onDragCancel = {
                                    isStrokeActive = false
                                    liveTouchCursor = null
                                    if (!isLassoTool && latestOnStrokeSegment != null) {
                                        emitSegment(com.example.ui.viewmodel.StrokePhase.END)
                                    }
                                    currentPoints.clear()
                                }
                            )
                        }
                    } else if (activeTool == ActiveTool.TRANSFORM && selectedLayerIndex in latestProject.layers.indices) {
                        // Interactive Layer Bounding Box & Transform Gestures
                        // Re-resolved on every drag start: undo/redo can replace the Layer instance
                        // without restarting this detector.
                        var selectedLayer = latestProject.layers[selectedLayerIndex]

                        detectDragGestures(
                            onDragStart = { startOffset ->
                                latestProject.layers.getOrNull(selectedLayerIndex)?.let { selectedLayer = it }
                                initialTouchPos = startOffset
                                lastTouchPos = startOffset
                                initialLayerScaleX = selectedLayer.scaleX
                                initialLayerScaleY = selectedLayer.scaleY

                                if (!selectedLayer.isLocked && selectedLayer.isVisible) {
                                    val pts = layerCornersDoc(selectedLayer)

                                    val scP0 = Offset(curStartX() + (pts[0] / latestProject.width) * curDrawW(), curStartY() + (pts[1] / latestProject.height) * curDrawH())
                                    val scP1 = Offset(curStartX() + (pts[2] / latestProject.width) * curDrawW(), curStartY() + (pts[3] / latestProject.height) * curDrawH())
                                    val scP2 = Offset(curStartX() + (pts[4] / latestProject.width) * curDrawW(), curStartY() + (pts[5] / latestProject.height) * curDrawH())
                                    val scP3 = Offset(curStartX() + (pts[6] / latestProject.width) * curDrawW(), curStartY() + (pts[7] / latestProject.height) * curDrawH())
                                    val scCenter = Offset((scP0.x + scP2.x) / 2f, (scP0.y + scP2.y) / 2f)

                                    val scTopMid = Offset((scP0.x + scP1.x) / 2f, (scP0.y + scP1.y) / 2f)
                                    val scRightMid = Offset((scP1.x + scP2.x) / 2f, (scP1.y + scP2.y) / 2f)
                                    val scBottomMid = Offset((scP2.x + scP3.x) / 2f, (scP2.y + scP3.y) / 2f)
                                    val scLeftMid = Offset((scP3.x + scP0.x) / 2f, (scP3.y + scP0.y) / 2f)

                                    val vX = scTopMid.x - scCenter.x
                                    val vY = scTopMid.y - scCenter.y
                                    val vLen = sqrt(vX * vX + vY * vY)
                                    val scRotOffset = if (vLen > 0) Offset(scTopMid.x + (vX / vLen) * 36.dp.toPx(), scTopMid.y + (vY / vLen) * 36.dp.toPx()) else Offset(scTopMid.x, scTopMid.y - 36.dp.toPx())

                                    val threshold = 36.dp.toPx()

                                    fun ptInQuad(pt: Offset, a: Offset, b: Offset, c: Offset, d: Offset): Boolean {
                                        val poly = listOf(a, b, c, d)
                                        var ins = false
                                        var j = poly.size - 1
                                        for (i in poly.indices) {
                                            if ((poly[i].y > pt.y) != (poly[j].y > pt.y) &&
                                                (pt.x < (poly[j].x - poly[i].x) * (pt.y - poly[i].y) / (poly[j].y - poly[i].y) + poly[i].x)
                                            ) {
                                                ins = !ins
                                            }
                                            j = i
                                        }
                                        return ins
                                    }

                                    activeTransformHandle = when {
                                        sqrt((startOffset.x - scRotOffset.x) * (startOffset.x - scRotOffset.x) + (startOffset.y - scRotOffset.y) * (startOffset.y - scRotOffset.y)) < threshold -> TransformHandle.ROTATION
                                        sqrt((startOffset.x - scP0.x) * (startOffset.x - scP0.x) + (startOffset.y - scP0.y) * (startOffset.y - scP0.y)) < threshold -> TransformHandle.CORNER_TL
                                        sqrt((startOffset.x - scP1.x) * (startOffset.x - scP1.x) + (startOffset.y - scP1.y) * (startOffset.y - scP1.y)) < threshold -> TransformHandle.CORNER_TR
                                        sqrt((startOffset.x - scP2.x) * (startOffset.x - scP2.x) + (startOffset.y - scP2.y) * (startOffset.y - scP2.y)) < threshold -> TransformHandle.CORNER_BR
                                        sqrt((startOffset.x - scP3.x) * (startOffset.x - scP3.x) + (startOffset.y - scP3.y) * (startOffset.y - scP3.y)) < threshold -> TransformHandle.CORNER_BL
                                        sqrt((startOffset.x - scTopMid.x) * (startOffset.x - scTopMid.x) + (startOffset.y - scTopMid.y) * (startOffset.y - scTopMid.y)) < threshold -> TransformHandle.EDGE_T
                                        sqrt((startOffset.x - scRightMid.x) * (startOffset.x - scRightMid.x) + (startOffset.y - scRightMid.y) * (startOffset.y - scRightMid.y)) < threshold -> TransformHandle.EDGE_R
                                        sqrt((startOffset.x - scBottomMid.x) * (startOffset.x - scBottomMid.x) + (startOffset.y - scBottomMid.y) * (startOffset.y - scBottomMid.y)) < threshold -> TransformHandle.EDGE_B
                                        sqrt((startOffset.x - scLeftMid.x) * (startOffset.x - scLeftMid.x) + (startOffset.y - scLeftMid.y) * (startOffset.y - scLeftMid.y)) < threshold -> TransformHandle.EDGE_L
                                        ptInQuad(startOffset, scP0, scP1, scP2, scP3) || sqrt((startOffset.x - scCenter.x) * (startOffset.x - scCenter.x) + (startOffset.y - scCenter.y) * (startOffset.y - scCenter.y)) < 60.dp.toPx() -> TransformHandle.BODY
                                        else -> TransformHandle.NONE
                                    }
                                } else {
                                    activeTransformHandle = TransformHandle.NONE
                                }
                            },
                            onDrag = { change, _ ->
                                if (!selectedLayer.isLocked && selectedLayer.isVisible && activeTransformHandle != TransformHandle.NONE) {
                                    val pos = change.position
                                    val pts = layerCornersDoc(selectedLayer)

                                    val scP0 = Offset(curStartX() + (pts[0] / latestProject.width) * curDrawW(), curStartY() + (pts[1] / latestProject.height) * curDrawH())
                                    val scP2 = Offset(curStartX() + (pts[4] / latestProject.width) * curDrawW(), curStartY() + (pts[5] / latestProject.height) * curDrawH())
                                    val scCenter = Offset((scP0.x + scP2.x) / 2f, (scP0.y + scP2.y) / 2f)

                                    when (activeTransformHandle) {
                                        TransformHandle.BODY -> {
                                            val screenDx = pos.x - lastTouchPos.x
                                            val screenDy = pos.y - lastTouchPos.y
                                            val docDx = (screenDx / curDrawW()) * latestProject.width
                                            val docDy = (screenDy / curDrawH()) * latestProject.height

                                            var newPosX = selectedLayer.positionX + docDx
                                            var newPosY = selectedLayer.positionY + docDy

                                            // Snapping to Canvas Center
                                            val snapThresholdDoc = 15f

                                            if (abs(newPosX) < snapThresholdDoc) {
                                                newPosX = 0f
                                                showSnapV = true
                                            } else {
                                                showSnapV = false
                                            }

                                            if (abs(newPosY) < snapThresholdDoc) {
                                                newPosY = 0f
                                                showSnapH = true
                                            } else {
                                                showSnapH = false
                                            }

                                            latestOnUpdateLayerTransient?.invoke { l ->
                                                l.positionX = newPosX
                                                l.positionY = newPosY
                                            }
                                        }

                                        TransformHandle.ROTATION -> {
                                            val dx = pos.x - scCenter.x
                                            val dy = pos.y - scCenter.y
                                            val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
                                            val normAngle = (angleDeg % 360f + 360f) % 360f

                                            latestOnUpdateLayerTransient?.invoke { l ->
                                                l.rotation = normAngle
                                            }
                                        }

                                        TransformHandle.CORNER_TL, TransformHandle.CORNER_TR, TransformHandle.CORNER_BR, TransformHandle.CORNER_BL -> {
                                            val initDist = sqrt((initialTouchPos.x - scCenter.x) * (initialTouchPos.x - scCenter.x) + (initialTouchPos.y - scCenter.y) * (initialTouchPos.y - scCenter.y))
                                            val currDist = sqrt((pos.x - scCenter.x) * (pos.x - scCenter.x) + (pos.y - scCenter.y) * (pos.y - scCenter.y))

                                            val scaleFactor = if (initDist > 5f) currDist / initDist else 1f
                                            val newScaleX = (initialLayerScaleX * scaleFactor).coerceIn(-10f, 10f)
                                            val newScaleY = (initialLayerScaleY * scaleFactor).coerceIn(-10f, 10f)

                                            latestOnUpdateLayerTransient?.invoke { l ->
                                                l.scaleX = newScaleX
                                                l.scaleY = newScaleY
                                            }
                                        }

                                        TransformHandle.EDGE_T, TransformHandle.EDGE_B -> {
                                            val initDistY = abs(initialTouchPos.y - scCenter.y)
                                            val currDistY = abs(pos.y - scCenter.y)
                                            val scaleFactor = if (initDistY > 5f) currDistY / initDistY else 1f
                                            val newScaleY = (initialLayerScaleY * scaleFactor).coerceIn(-10f, 10f)
                                            latestOnUpdateLayerTransient?.invoke { l -> l.scaleY = newScaleY }
                                        }

                                        TransformHandle.EDGE_L, TransformHandle.EDGE_R -> {
                                            val initDistX = abs(initialTouchPos.x - scCenter.x)
                                            val currDistX = abs(pos.x - scCenter.x)
                                            val scaleFactor = if (initDistX > 5f) currDistX / initDistX else 1f
                                            val newScaleX = (initialLayerScaleX * scaleFactor).coerceIn(-10f, 10f)
                                            latestOnUpdateLayerTransient?.invoke { l -> l.scaleX = newScaleX }
                                        }

                                        else -> {}
                                    }

                                    lastTouchPos = pos
                                }
                            },
                            onDragEnd = {
                                if (activeTransformHandle != TransformHandle.NONE) {
                                    latestOnCommitHistoryState?.invoke()
                                }
                                activeTransformHandle = TransformHandle.NONE
                                showSnapV = false
                                showSnapH = false
                            }
                        )
                    } else if (activeTool == ActiveTool.TEXT && selectedLayerType == com.example.data.model.LayerType.TEXT &&
                        selectedLayerIndex in latestProject.layers.indices
                    ) {
                        // Direct on-canvas touch-and-drag for Text layers (only text layers move with the Text tool)
                        var selectedLayer = latestProject.layers[selectedLayerIndex]

                        detectDragGestures(
                            onDragStart = { startOffset ->
                                latestProject.layers.getOrNull(selectedLayerIndex)?.let { selectedLayer = it }
                                lastTouchPos = startOffset
                            },
                            onDrag = { change, _ ->
                                if (!selectedLayer.isLocked && selectedLayer.isVisible) {
                                    val pos = change.position
                                    val screenDx = pos.x - lastTouchPos.x
                                    val screenDy = pos.y - lastTouchPos.y
                                    val docDx = (screenDx / curDrawW()) * latestProject.width
                                    val docDy = (screenDy / curDrawH()) * latestProject.height

                                    latestOnUpdateLayerTransient?.invoke { l ->
                                        l.positionX += docDx
                                        l.positionY += docDy
                                    }
                                    lastTouchPos = pos
                                }
                            },
                            onDragEnd = {
                                latestOnCommitHistoryState?.invoke()
                            }
                        )
                    }
                }
        ) {
            val canvasW = size.width
            val canvasH = size.height

            val pW = project.width.toFloat().coerceAtLeast(1f)
            val pH = project.height.toFloat().coerceAtLeast(1f)
            val targetDrawW = (pW * scale).coerceAtLeast(1f)
            val targetDrawH = (pH * scale).coerceAtLeast(1f)

            val startX = (canvasW - targetDrawW) / 2f + offsetX
            val startY = (canvasH - targetDrawH) / 2f + offsetY

            // 1. Draw Transparent Checkerboard Background
            val checkSize = 20.dp.toPx()
            val totalRows = (targetDrawH / checkSize).toInt() + 1
            val totalCols = (targetDrawW / checkSize).toInt() + 1
            // Only draw checker cells visible in the viewport. This keeps deep zooms smooth.
            val firstCol = kotlin.math.floor(((0f - startX) / checkSize).toDouble()).toInt().coerceIn(0, totalCols)
            val lastCol = kotlin.math.ceil(((canvasW - startX) / checkSize).toDouble()).toInt().coerceIn(firstCol, totalCols)
            val firstRow = kotlin.math.floor(((0f - startY) / checkSize).toDouble()).toInt().coerceIn(0, totalRows)
            val lastRow = kotlin.math.ceil(((canvasH - startY) / checkSize).toDouble()).toInt().coerceIn(firstRow, totalRows)

            for (r in firstRow until lastRow) {
                for (c in firstCol until lastCol) {
                    val color = if ((r + c) % 2 == 0) CheckerLight else CheckerDark
                    val rx = startX + (c * checkSize)
                    val ry = startY + (r * checkSize)
                    val rw = checkSize.coerceAtMost(startX + targetDrawW - rx)
                    val rh = checkSize.coerceAtMost(startY + targetDrawH - ry)
                    if (rw > 0 && rh > 0) drawRect(color, Offset(rx, ry), Size(rw, rh))
                }
            }

            // 2. Draw Composite Layer Stack
            compositeBitmap?.let { bmp ->
                if (isSplitCompareActive && originalCompositeBitmap != null && !originalCompositeBitmap!!.isRecycled) {
                    val splitRatioClamped = splitComparePosition.coerceIn(0.02f, 0.98f)
                    val splitX = startX + targetDrawW * splitRatioClamped

                    // A. Draw full edited image
                    drawImage(
                        image = bmp.asImageBitmap(),
                        dstOffset = androidx.compose.ui.unit.IntOffset(startX.toInt(), startY.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(targetDrawW.toInt(), targetDrawH.toInt())
                    )

                    // B. Clip and draw original image to the left of the split divider
                    clipRect(left = startX, top = startY, right = splitX, bottom = startY + targetDrawH) {
                        drawImage(
                            image = originalCompositeBitmap!!.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(startX.toInt(), startY.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(targetDrawW.toInt(), targetDrawH.toInt())
                        )
                    }

                    // C. Draw vertical split line
                    drawLine(
                        color = Color.Black.copy(alpha = 0.5f),
                        start = Offset(splitX - 1f, startY),
                        end = Offset(splitX - 1f, startY + targetDrawH),
                        strokeWidth = 4.dp.toPx()
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(splitX, startY),
                        end = Offset(splitX, startY + targetDrawH),
                        strokeWidth = 2.dp.toPx()
                    )

                    // D. Draw circular handle with arrow indicators
                    val handleCenterY = startY + targetDrawH / 2f
                    drawCircle(color = Color.Black.copy(alpha = 0.45f), radius = 20.dp.toPx(), center = Offset(splitX, handleCenterY))
                    drawCircle(color = Color.White, radius = 17.dp.toPx(), center = Offset(splitX, handleCenterY))
                    drawCircle(color = IndigoPrimary, radius = 13.dp.toPx(), center = Offset(splitX, handleCenterY))
                    drawCircle(color = Color.White, radius = 3.dp.toPx(), center = Offset(splitX - 5.dp.toPx(), handleCenterY))
                    drawCircle(color = Color.White, radius = 3.dp.toPx(), center = Offset(splitX + 5.dp.toPx(), handleCenterY))
                } else {
                    drawImage(
                        image = bmp.asImageBitmap(),
                        dstOffset = androidx.compose.ui.unit.IntOffset(startX.toInt(), startY.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(targetDrawW.toInt(), targetDrawH.toInt())
                    )
                }
            }

            // 3. Draw Alignment Snapping Guidelines
            if (showSnapV) {
                val midX = startX + targetDrawW / 2f
                drawLine(
                    color = CyanAccent,
                    start = Offset(midX, startY),
                    end = Offset(midX, startY + targetDrawH),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                )
            }
            if (showSnapH) {
                val midY = startY + targetDrawH / 2f
                drawLine(
                    color = CyanAccent,
                    start = Offset(startX, midY),
                    end = Offset(startX + targetDrawW, midY),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                )
            }

            // 4. Draw Inpainting Target Mask Overlay ONLY for Object Removal (vibrant red highlight)
            if (selectionMask != null && activeTool == ActiveTool.OBJECT_REMOVAL) {
                drawImage(
                    image = selectionMask.asImageBitmap(),
                    dstOffset = androidx.compose.ui.unit.IntOffset(startX.toInt(), startY.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(targetDrawW.toInt(), targetDrawH.toInt()),
                    alpha = 0.42f,
                    colorFilter = ColorFilter.tint(Color(0xFFFF2A6D))
                )
            }

            // 4.5. Ruby Mask Overlay (Photoshop Quick Mask visualization)
            if (showMaskRubyOverlay) {
                val curLayer = project.layers.getOrNull(selectedLayerIndex)
                val lMask = curLayer?.maskBitmap
                if (lMask != null && !lMask.isRecycled) {
                    drawImage(
                        image = lMask.asImageBitmap(),
                        dstOffset = androidx.compose.ui.unit.IntOffset(startX.toInt(), startY.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(targetDrawW.toInt(), targetDrawH.toInt()),
                        alpha = 0.50f,
                        colorFilter = ColorFilter.tint(Color(0xFFE53935))
                    )
                }
            }

            // 4.55. Quick Mask view: everything NOT selected is covered in translucent red.
            if (isQuickMaskView && selectionMask != null && !selectionMask.isRecycled &&
                activeTool != ActiveTool.OBJECT_REMOVAL && activeTool != ActiveTool.CROP_PERSPECTIVE
            ) {
                val bounds = androidx.compose.ui.geometry.Rect(startX, startY, startX + targetDrawW, startY + targetDrawH)
                drawIntoCanvas { c -> c.saveLayer(bounds, androidx.compose.ui.graphics.Paint()) }
                drawRect(Color(0xFFE53935).copy(alpha = 0.5f), Offset(startX, startY), Size(targetDrawW, targetDrawH))
                drawImage(
                    image = selectionMask.asImageBitmap(),
                    dstOffset = androidx.compose.ui.unit.IntOffset((startX + moveDelta.x * targetDrawW / project.width).toInt(), (startY + moveDelta.y * targetDrawH / project.height).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(targetDrawW.toInt(), targetDrawH.toInt()),
                    blendMode = BlendMode.DstOut
                )
                drawIntoCanvas { c -> c.restore() }
            }

            // 4.6. High-contrast alternating Black & White Marching Ants along selection boundary
            // Persists continuously across tools (Adjustments, Filters, Layers, Transform, etc.)
            if (selectionMask != null && activeTool != ActiveTool.CROP_PERSPECTIVE) {
                selectionContourPath?.let { cPath ->
                    if (!cPath.isEmpty) {
                        val m = android.graphics.Matrix()
                        // While the Move tool drags, the outline follows the finger before it is applied.
                        m.postTranslate(moveDelta.x, moveDelta.y)
                        m.postScale(targetDrawW / project.width.toFloat(), targetDrawH / project.height.toFloat())
                        m.postTranslate(startX, startY)
                        val screenPath = android.graphics.Path()
                        cPath.transform(m, screenPath)
                        val composePath = screenPath.asComposePath()

                        // Dark dash segment
                        drawPath(
                            path = composePath,
                            color = Color.Black,
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), marchingAntsPhase)
                            )
                        )
                        // Light dash segment (offset to create alternating black & white ants)
                        drawPath(
                            path = composePath,
                            color = Color.White,
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), (marchingAntsPhase + 8f) % 16f)
                            )
                        )
                    }
                }
            }

            // 5. Draw Current Drawing Stroke Path (real-time feedback calibrated to screen DP)
            if (currentPoints.isNotEmpty()) {
                val path = Path()
                val firstP = currentPoints.first()
                val firstScreenX = startX + ((firstP.x / project.width) * targetDrawW)
                val firstScreenY = startY + ((firstP.y / project.height) * targetDrawH)
                path.moveTo(firstScreenX, firstScreenY)

                for (p in currentPoints) {
                    val screenX = startX + ((p.x / project.width) * targetDrawW)
                    val screenY = startY + ((p.y / project.height) * targetDrawH)
                    path.lineTo(screenX, screenY)
                }

                val isLassoPreview = (activeSelectionTool == SelectionToolType.LASSO && activeTool == ActiveTool.SELECTION) ||
                    (activeTool == ActiveTool.OBJECT_REMOVAL && inpaintSelectionMode == com.example.ui.viewmodel.InpaintSelectionMode.LASSO)
                val accent = if (activeTool == ActiveTool.OBJECT_REMOVAL) Color(0xFFFF2A6D) else CyanAccent

                if (isLassoPreview) {
                    // A lasso is an outline, not a brush: draw a thin, high-contrast line (fixed on
                    // screen, so it stays precise at any zoom) instead of a brush-wide band.
                    path.close()
                    drawPath(path = path, color = Color.Black.copy(alpha = 0.6f), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path = path, color = accent, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else {
                    // Brush: translucent band of the real brush diameter (constant on screen, so zooming
                    // in makes the brush finer on the image) plus a thin centre line for precision.
                    val screenStrokeWidth = with(density) { effectiveBrushSizeDp.dp.toPx() }
                    drawPath(
                        path = path,
                        color = accent.copy(alpha = 0.32f),
                        style = Stroke(width = screenStrokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawPath(
                        path = path,
                        color = Color.White.copy(alpha = 0.85f),
                        style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }

            // 5a. Live Brush Cursor Ring calibrated to screen DP (never balloons when zooming)
            liveTouchCursor?.let { cursor ->
                val shapeOrRemovalRect = activeTool == ActiveTool.OBJECT_REMOVAL && inpaintSelectionMode != com.example.ui.viewmodel.InpaintSelectionMode.BRUSH
                if ((activeTool == ActiveTool.OBJECT_REMOVAL && !shapeOrRemovalRect) || isEditingSelectedMask || (activeTool == ActiveTool.SELECTION && (activeSelectionTool == SelectionToolType.QUICK_BRUSH || activeSelectionTool == SelectionToolType.QUICK_SELECTION))) {
                    val cursorRadius = with(density) { (effectiveBrushSizeDp.dp.toPx()) / 2f }
                    drawCircle(
                        color = if (activeTool == ActiveTool.OBJECT_REMOVAL) Color(0xFFFF2A6D).copy(alpha = 0.35f) else CyanAccent.copy(alpha = 0.35f),
                        radius = cursorRadius,
                        center = cursor
                    )
                    drawCircle(
                        color = Color.White,
                        radius = cursorRadius,
                        center = cursor,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }

            // Shapes: the one being drawn, or the last one (still editable with corner handles).
            val sa = shapeStart
            val se = shapeEnd
            val isShapeTool = (activeTool == ActiveTool.SELECTION && (activeSelectionTool == SelectionToolType.RECTANGLE || activeSelectionTool == SelectionToolType.ELLIPSE)) ||
                (activeTool == ActiveTool.OBJECT_REMOVAL && inpaintSelectionMode == com.example.ui.viewmodel.InpaintSelectionMode.RECTANGLE)
            val drawingEllipse = activeTool == ActiveTool.SELECTION && activeSelectionTool == SelectionToolType.ELLIPSE
            val shownShape: RectF? = when {
                !isShapeTool -> null
                sa != null && se != null -> constrainedShapeRect(sa, se, shapeFromCenter, shapeLockSquare)
                editingShapeRect != null -> editingShapeRect
                editableShape != null && editableShape.isEllipse == drawingEllipse && activeTool == ActiveTool.SELECTION ->
                    RectF(editableShape.left, editableShape.top, editableShape.right, editableShape.bottom)
                else -> null
            }
            if (shownShape != null) {
                val l = startX + (shownShape.left / project.width) * targetDrawW
                val t = startY + (shownShape.top / project.height) * targetDrawH
                val w = (shownShape.width() / project.width) * targetDrawW
                val h = (shownShape.height() / project.height) * targetDrawH
                val dashB = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), marchingAntsPhase)
                val dashW = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), (marchingAntsPhase + 8f) % 16f)
                if (!drawingEllipse) {
                    drawRect(Color.Black, Offset(l, t), Size(w, h), style = Stroke(2.dp.toPx(), pathEffect = dashB))
                    drawRect(Color.White, Offset(l, t), Size(w, h), style = Stroke(2.dp.toPx(), pathEffect = dashW))
                } else {
                    drawRect(Color.White.copy(alpha = 0.35f), Offset(l, t), Size(w, h), style = Stroke(1.dp.toPx()))
                    drawOval(Color.Black, Offset(l, t), Size(w, h), style = Stroke(2.dp.toPx(), pathEffect = dashB))
                    drawOval(Color.White, Offset(l, t), Size(w, h), style = Stroke(2.dp.toPx(), pathEffect = dashW))
                }
                // Corner handles once the shape exists (drag a corner to resize, inside to move).
                if (sa == null && activeTool == ActiveTool.SELECTION) {
                    for (c in listOf(Offset(l, t), Offset(l + w, t), Offset(l + w, t + h), Offset(l, t + h))) {
                        drawCircle(Color.White, radius = 8.dp.toPx(), center = c)
                        drawCircle(IndigoPrimary, radius = 5.5.dp.toPx(), center = c)
                    }
                }
                // Live dimensions (document pixels).
                val label = "${shownShape.width().toInt()} × ${shownShape.height().toInt()}"
                drawIntoCanvas { c ->
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE
                        textSize = 12.sp.toPx()
                        isFakeBoldText = true
                        setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK)
                    }
                    val tx = l + w / 2f - paint.measureText(label) / 2f
                    val ty = (t - 8.dp.toPx()).let { if (it < 16.dp.toPx()) t + h + 18.dp.toPx() else it }
                    c.nativeCanvas.drawText(label, tx, ty, paint)
                }
            }

            // 5b. Draw Polygon Selection Preview (when POLYGON selection tool active)
            if (activeSelectionTool == SelectionToolType.POLYGON && activeTool == ActiveTool.SELECTION && polygonPoints.isNotEmpty()) {
                val polyPath = Path()
                val firstP = polygonPoints.first()
                val firstScreenX = startX + ((firstP.x / project.width) * targetDrawW)
                val firstScreenY = startY + ((firstP.y / project.height) * targetDrawH)
                polyPath.moveTo(firstScreenX, firstScreenY)

                for (i in 1 until polygonPoints.size) {
                    val p = polygonPoints[i]
                    val screenX = startX + ((p.x / project.width) * targetDrawW)
                    val screenY = startY + ((p.y / project.height) * targetDrawH)
                    polyPath.lineTo(screenX, screenY)
                }

                drawPath(
                    path = polyPath,
                    color = CyanAccent,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )

                if (polygonPoints.size >= 3) {
                    val lastP = polygonPoints.last()
                    val lastScreenX = startX + ((lastP.x / project.width) * targetDrawW)
                    val lastScreenY = startY + ((lastP.y / project.height) * targetDrawH)
                    drawLine(
                        color = CyanAccent.copy(alpha = 0.75f),
                        start = Offset(lastScreenX, lastScreenY),
                        end = Offset(firstScreenX, firstScreenY),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                }

                polygonPoints.forEachIndexed { idx, p ->
                    val screenX = startX + ((p.x / project.width) * targetDrawW)
                    val screenY = startY + ((p.y / project.height) * targetDrawH)
                    if (idx == 0) {
                        drawCircle(color = Color.Yellow, radius = 12.dp.toPx(), center = Offset(screenX, screenY), style = Stroke(2.5f.dp.toPx()))
                        drawCircle(color = Color.Red, radius = 6.dp.toPx(), center = Offset(screenX, screenY))
                    } else {
                        drawCircle(color = Color.White, radius = 5.dp.toPx(), center = Offset(screenX, screenY))
                        drawCircle(color = Color.Red, radius = 3.5f.dp.toPx(), center = Offset(screenX, screenY))
                    }
                }
            }

            // 5c. Magnifier loupe: shows the area under the finger enlarged in a corner, so the finger
            // never hides what is being selected.
            val loupeTouch = liveTouchCursor
            val loupeBmp = compositeBitmap
            val loupeTool = activeTool == ActiveTool.SELECTION || activeTool == ActiveTool.OBJECT_REMOVAL || isEditingSelectedMask
            if (loupeTouch != null && loupeTool && loupeBmp != null && !loupeBmp.isRecycled) {
                val loupeR = 58.dp.toPx()
                val margin = 14.dp.toPx()
                val zoomFactor = 2.5f
                val onRight = loupeTouch.x < canvasW / 2f && loupeTouch.y < loupeR * 2f + margin * 3f
                val lc = if (onRight) Offset(canvasW - margin - loupeR, margin + loupeR) else Offset(margin + loupeR, margin + loupeR)
                val docX = (loupeTouch.x - startX) / targetDrawW * pW
                val docY = (loupeTouch.y - startY) / targetDrawH * pH
                val screenPerDoc = scale * zoomFactor
                val bmpPerDoc = loupeBmp.width / pW
                val halfDoc = loupeR / screenPerDoc
                // Source rect (bitmap px), clamped to the bitmap; destination adjusted to match.
                val sL = ((docX - halfDoc) * bmpPerDoc).coerceIn(0f, loupeBmp.width.toFloat())
                val sT = ((docY - halfDoc) * bmpPerDoc).coerceIn(0f, loupeBmp.height.toFloat())
                val sR = ((docX + halfDoc) * bmpPerDoc).coerceIn(0f, loupeBmp.width.toFloat())
                val sB = ((docY + halfDoc) * bmpPerDoc).coerceIn(0f, loupeBmp.height.toFloat())
                val loupePath = Path().apply { addOval(androidx.compose.ui.geometry.Rect(lc, loupeR)) }
                drawCircle(Color.Black.copy(alpha = 0.35f), radius = loupeR + 3.dp.toPx(), center = lc + Offset(0f, 2.dp.toPx()))
                clipPath(loupePath) {
                    drawRect(CheckerDark, Offset(lc.x - loupeR, lc.y - loupeR), Size(loupeR * 2, loupeR * 2))
                    if (sR - sL >= 1f && sB - sT >= 1f) {
                        val dL = lc.x + (sL / bmpPerDoc - docX) * screenPerDoc
                        val dT = lc.y + (sT / bmpPerDoc - docY) * screenPerDoc
                        val dW = (sR - sL) / bmpPerDoc * screenPerDoc
                        val dH = (sB - sT) / bmpPerDoc * screenPerDoc
                        drawImage(
                            image = loupeBmp.asImageBitmap(),
                            srcOffset = androidx.compose.ui.unit.IntOffset(sL.toInt(), sT.toInt()),
                            srcSize = androidx.compose.ui.unit.IntSize((sR - sL).toInt().coerceAtLeast(1), (sB - sT).toInt().coerceAtLeast(1)),
                            dstOffset = androidx.compose.ui.unit.IntOffset(dL.toInt(), dT.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(dW.toInt().coerceAtLeast(1), dH.toInt().coerceAtLeast(1))
                        )
                    }
                    // The stroke / outline in progress, magnified.
                    if (currentPoints.size > 1) {
                        val lp = Path()
                        currentPoints.forEachIndexed { i, p ->
                            val o = Offset(lc.x + (p.x - docX) * screenPerDoc, lc.y + (p.y - docY) * screenPerDoc)
                            if (i == 0) lp.moveTo(o.x, o.y) else lp.lineTo(o.x, o.y)
                        }
                        drawPath(lp, Color.Black.copy(alpha = 0.6f), style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawPath(lp, CyanAccent, style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    // Brush footprint (true size on the image, magnified) and a precise crosshair.
                    val brushRing = (effectiveBrushSizeDp.dp.toPx() / 2f) * zoomFactor
                    if (brushRing < loupeR) {
                        drawCircle(Color.White.copy(alpha = 0.9f), radius = brushRing, center = lc, style = Stroke(width = 1.dp.toPx()))
                    }
                    val ch = 7.dp.toPx()
                    drawLine(Color.Black, Offset(lc.x - ch, lc.y), Offset(lc.x + ch, lc.y), strokeWidth = 3.dp.toPx())
                    drawLine(Color.Black, Offset(lc.x, lc.y - ch), Offset(lc.x, lc.y + ch), strokeWidth = 3.dp.toPx())
                    drawLine(Color.White, Offset(lc.x - ch, lc.y), Offset(lc.x + ch, lc.y), strokeWidth = 1.2.dp.toPx())
                    drawLine(Color.White, Offset(lc.x, lc.y - ch), Offset(lc.x, lc.y + ch), strokeWidth = 1.2.dp.toPx())
                }
                drawCircle(Color.White, radius = loupeR, center = lc, style = Stroke(width = 2.dp.toPx()))
            }

            // 5d. Magic wand tolerance bubble while dragging.
            wandBubble?.let { (pos, tol) ->
                val bc = Offset(pos.x, (pos.y - 64.dp.toPx()).coerceAtLeast(28.dp.toPx()))
                drawCircle(Color.Black.copy(alpha = 0.7f), radius = 26.dp.toPx(), center = bc)
                drawCircle(CyanAccent, radius = 26.dp.toPx(), center = bc, style = Stroke(width = 2.dp.toPx()))
                drawIntoCanvas { c ->
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE
                        textSize = 15.sp.toPx()
                        isFakeBoldText = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    c.nativeCanvas.drawText(tol.toString(), bc.x, bc.y + 5.sp.toPx(), paint)
                }
            }

            // 6. Canvas Border
            drawRect(
                color = Color(0x806366F1),
                topLeft = Offset(startX, startY),
                size = Size(targetDrawW, targetDrawH),
                style = Stroke(width = 2.dp.toPx())
            )

            // 7. Interactive Bounding Box & Handles Overlay for Selected Layer
            if (!isPerspectiveActive && activeTool == ActiveTool.TRANSFORM && selectedLayerIndex in project.layers.indices) {
                val layer = project.layers[selectedLayerIndex]
                if (layer.isVisible) {
                    val pts = layerCornersDoc(layer)

                    val scP0 = Offset(startX + (pts[0] / project.width) * targetDrawW, startY + (pts[1] / project.height) * targetDrawH)
                    val scP1 = Offset(startX + (pts[2] / project.width) * targetDrawW, startY + (pts[3] / project.height) * targetDrawH)
                    val scP2 = Offset(startX + (pts[4] / project.width) * targetDrawW, startY + (pts[5] / project.height) * targetDrawH)
                    val scP3 = Offset(startX + (pts[6] / project.width) * targetDrawW, startY + (pts[7] / project.height) * targetDrawH)
                    val scCenter = Offset((scP0.x + scP2.x) / 2f, (scP0.y + scP2.y) / 2f)

                    val bPath = Path()
                    bPath.moveTo(scP0.x, scP0.y)
                    bPath.lineTo(scP1.x, scP1.y)
                    bPath.lineTo(scP2.x, scP2.y)
                    bPath.lineTo(scP3.x, scP3.y)
                    bPath.close()

                    // Draw Layer Bounding Box Border
                    val borderColor = if (layer.isLocked) Color.Red else CyanAccent
                    drawPath(
                        path = bPath,
                        color = borderColor,
                        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f))
                    )

                    if (!layer.isLocked) {
                        // Rotation Knob Connector Line
                        val scTopMid = Offset((scP0.x + scP1.x) / 2f, (scP0.y + scP1.y) / 2f)
                        val vX = scTopMid.x - scCenter.x
                        val vY = scTopMid.y - scCenter.y
                        val vLen = sqrt(vX * vX + vY * vY)
                        val scRotOffset = if (vLen > 0) Offset(scTopMid.x + (vX / vLen) * 36.dp.toPx(), scTopMid.y + (vY / vLen) * 36.dp.toPx()) else Offset(scTopMid.x, scTopMid.y - 36.dp.toPx())

                        drawLine(
                            color = CyanAccent,
                            start = scTopMid,
                            end = scRotOffset,
                            strokeWidth = 2.dp.toPx()
                        )

                        // 4 Corner Scale Handles
                        for (handlePos in listOf(scP0, scP1, scP2, scP3)) {
                            drawCircle(color = Color.White, radius = 10.dp.toPx(), center = handlePos)
                            drawCircle(color = IndigoPrimary, radius = 7.dp.toPx(), center = handlePos)
                        }

                        // 4 Midpoint Edge Handles
                        val scRightMid = Offset((scP1.x + scP2.x) / 2f, (scP1.y + scP2.y) / 2f)
                        val scBottomMid = Offset((scP2.x + scP3.x) / 2f, (scP2.y + scP3.y) / 2f)
                        val scLeftMid = Offset((scP3.x + scP0.x) / 2f, (scP3.y + scP0.y) / 2f)

                        for (edgeMid in listOf(scTopMid, scRightMid, scBottomMid, scLeftMid)) {
                            drawCircle(color = Color.White, radius = 7.dp.toPx(), center = edgeMid)
                            drawCircle(color = CyanAccent, radius = 4.5.dp.toPx(), center = edgeMid)
                        }

                        // Rotation Knob Handle
                        drawCircle(color = Color.White, radius = 14.dp.toPx(), center = scRotOffset)
                        drawCircle(color = CyanAccent, radius = 10.dp.toPx(), center = scRotOffset)

                        // Center Position Anchor Knob
                        drawCircle(color = Color.White, radius = 8.dp.toPx(), center = scCenter)
                        drawCircle(color = IndigoPrimary, radius = 5.dp.toPx(), center = scCenter)
                    }
                }
            }

            // 7b. Draw Active Text Layer Bounding Box (when Text tool active)
            if (activeTool == ActiveTool.TEXT && selectedLayerIndex in project.layers.indices) {
                val layer = project.layers[selectedLayerIndex]
                if (layer.isVisible && layer.type == com.example.data.model.LayerType.TEXT) {
                    val pts = layerCornersDoc(layer)
                    val scP0 = Offset(startX + (pts[0] / project.width) * targetDrawW, startY + (pts[1] / project.height) * targetDrawH)
                    val scP1 = Offset(startX + (pts[2] / project.width) * targetDrawW, startY + (pts[3] / project.height) * targetDrawH)
                    val scP2 = Offset(startX + (pts[4] / project.width) * targetDrawW, startY + (pts[5] / project.height) * targetDrawH)
                    val scP3 = Offset(startX + (pts[6] / project.width) * targetDrawW, startY + (pts[7] / project.height) * targetDrawH)

                    val tPath = Path()
                    tPath.moveTo(scP0.x, scP0.y)
                    tPath.lineTo(scP1.x, scP1.y)
                    tPath.lineTo(scP2.x, scP2.y)
                    tPath.lineTo(scP3.x, scP3.y)
                    tPath.close()

                    drawPath(
                        path = tPath,
                        color = IndigoPrimary,
                        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f))
                    )
                }
            }

            // 8. Perspective Grid & Corner Drag Handles Overlay (Photoshop Style)
            if (isPerspectiveActive && perspectiveCorners.size >= 4) {
                val pPath = Path()
                val screenCorners = perspectiveCorners.map { c ->
                    Offset(
                        startX + ((c.x / project.width) * targetDrawW),
                        startY + ((c.y / project.height) * targetDrawH)
                    )
                }

                pPath.moveTo(screenCorners[0].x, screenCorners[0].y)
                for (i in 1..3) {
                    pPath.lineTo(screenCorners[i].x, screenCorners[i].y)
                }
                pPath.close()

                // 1. Darken exterior area outside the perspective crop quadrilateral
                val fullViewportPath = Path().apply {
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                }
                val dimmedExterior = Path.combine(PathOperation.Difference, fullViewportPath, pPath)
                drawPath(path = dimmedExterior, color = Color.Black.copy(alpha = 0.52f))

                // 2. Draw 3x3 Perspective Subdivision Grid
                val c0 = screenCorners[0]
                val c1 = screenCorners[1]
                val c2 = screenCorners[2]
                val c3 = screenCorners[3]

                val gridColor = Color.White.copy(alpha = 0.55f)
                val gridStroke = 1.dp.toPx()

                // Horizontal perspective grid lines
                for (t in listOf(1f / 3f, 2f / 3f)) {
                    val pLeft = Offset(c0.x + (c3.x - c0.x) * t, c0.y + (c3.y - c0.y) * t)
                    val pRight = Offset(c1.x + (c2.x - c1.x) * t, c1.y + (c2.y - c1.y) * t)
                    drawLine(color = gridColor, start = pLeft, end = pRight, strokeWidth = gridStroke)
                }

                // Vertical perspective grid lines
                for (t in listOf(1f / 3f, 2f / 3f)) {
                    val pTop = Offset(c0.x + (c1.x - c0.x) * t, c0.y + (c1.y - c0.y) * t)
                    val pBottom = Offset(c3.x + (c2.x - c3.x) * t, c3.y + (c2.y - c3.y) * t)
                    drawLine(color = gridColor, start = pTop, end = pBottom, strokeWidth = gridStroke)
                }

                // 3. Crisp Quadrilateral Boundary
                drawPath(
                    path = pPath,
                    color = CyanAccent,
                    style = Stroke(width = 2.5.dp.toPx())
                )

                // 4. Corner Handles with Active Touch Indicator
                for (i in screenCorners.indices) {
                    val sc = screenCorners[i]
                    val isDragging = activeDraggingCorner == i

                    if (isDragging) {
                        drawLine(
                            color = CyanAccent.copy(alpha = 0.7f),
                            start = Offset(sc.x - 24.dp.toPx(), sc.y),
                            end = Offset(sc.x + 24.dp.toPx(), sc.y),
                            strokeWidth = 1.5.dp.toPx()
                        )
                        drawLine(
                            color = CyanAccent.copy(alpha = 0.7f),
                            start = Offset(sc.x, sc.y - 24.dp.toPx()),
                            end = Offset(sc.x, sc.y + 24.dp.toPx()),
                            strokeWidth = 1.5.dp.toPx()
                        )
                        drawCircle(color = CyanAccent.copy(alpha = 0.35f), radius = 22.dp.toPx(), center = sc)
                        drawCircle(color = Color.White, radius = 16.dp.toPx(), center = sc)
                        drawCircle(color = CyanAccent, radius = 12.dp.toPx(), center = sc)
                    } else {
                        drawCircle(color = Color.White, radius = 14.dp.toPx(), center = sc)
                        drawCircle(color = IndigoPrimary, radius = 10.dp.toPx(), center = sc)
                        drawCircle(color = Color.White, radius = 3.dp.toPx(), center = sc)
                    }
                }

                // 5. Midpoint Edge Handles (Photoshop style edge manipulation)
                val edgeMidpoints = listOf(
                    Offset((c0.x + c1.x) / 2f, (c0.y + c1.y) / 2f), // Top
                    Offset((c1.x + c2.x) / 2f, (c1.y + c2.y) / 2f), // Right
                    Offset((c3.x + c2.x) / 2f, (c3.y + c2.y) / 2f), // Bottom
                    Offset((c0.x + c3.x) / 2f, (c0.y + c3.y) / 2f)  // Left
                )
                for (ei in edgeMidpoints.indices) {
                    val em = edgeMidpoints[ei]
                    val isEdgeDragging = activePerspectiveHandle == (4 + ei)
                    val rOuter = if (isEdgeDragging) 11.dp.toPx() else 7.dp.toPx()
                    val rInner = if (isEdgeDragging) 8.dp.toPx() else 4.5.dp.toPx()
                    drawCircle(color = if (isEdgeDragging) CyanAccent else Color.White, radius = rOuter, center = em)
                    drawCircle(color = if (isEdgeDragging) Color.White else IndigoPrimary, radius = rInner, center = em)
                }

                if (activePerspectiveHandle == 8) {
                    drawPath(path = pPath, color = CyanAccent.copy(alpha = 0.22f))
                }
            }

            // 9. Interactive Professional Crop Box Overlay (when Crop tool active)
            if (activeTool == ActiveTool.CROP_PERSPECTIVE && !isPerspectiveActive) {
                val pWidth = project.width.toFloat().coerceAtLeast(1f)
                val pHeight = project.height.toFloat().coerceAtLeast(1f)

                val cL = cropRectState.left.takeIf { !it.isNaN() }?.coerceIn(0f, pWidth) ?: 0f
                val cT = cropRectState.top.takeIf { !it.isNaN() }?.coerceIn(0f, pHeight) ?: 0f
                val cR = cropRectState.right.takeIf { !it.isNaN() }?.coerceIn(cL + 1f, pWidth) ?: pWidth
                val cB = cropRectState.bottom.takeIf { !it.isNaN() }?.coerceIn(cT + 1f, pHeight) ?: pHeight

                val scL = startX + (cL / pWidth) * targetDrawW
                val scT = startY + (cT / pHeight) * targetDrawH
                val scR = startX + (cR / pWidth) * targetDrawW
                val scB = startY + (cB / pHeight) * targetDrawH

                val cropW = (scR - scL).coerceAtLeast(1f)
                val cropH = (scB - scT).coerceAtLeast(1f)

                if (!scL.isNaN() && !scT.isNaN() && !cropW.isNaN() && !cropH.isNaN()) {
                    // Outside Dimming Overlays
                    val dimColor = Color.Black.copy(alpha = 0.65f)
                    // Top dim
                    if (scT > startY) {
                        val dimH = (scT - startY).coerceAtLeast(0f)
                        if (dimH > 0f) drawRect(dimColor, Offset(startX, startY), Size(targetDrawW, dimH))
                    }
                    // Bottom dim
                    if (scB < startY + targetDrawH) {
                        val dimH = (startY + targetDrawH - scB).coerceAtLeast(0f)
                        if (dimH > 0f) drawRect(dimColor, Offset(startX, scB), Size(targetDrawW, dimH))
                    }
                    // Left dim
                    if (scL > startX) {
                        val dimW = (scL - startX).coerceAtLeast(0f)
                        if (dimW > 0f) drawRect(dimColor, Offset(startX, scT), Size(dimW, cropH))
                    }
                    // Right dim
                    if (scR < startX + targetDrawW) {
                        val dimW = (startX + targetDrawW - scR).coerceAtLeast(0f)
                        if (dimW > 0f) drawRect(dimColor, Offset(scR, scT), Size(dimW, cropH))
                    }

                    // Solid Crop Rectangle Outline
                    if (cropW > 0f && cropH > 0f && !cropW.isNaN() && !cropH.isNaN()) {
                        drawRect(
                            color = Color.White,
                            topLeft = Offset(scL, scT),
                            size = Size(cropW, cropH),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    // 3x3 Rule-of-Thirds Grid Lines
                    val gridColor = Color.White.copy(alpha = 0.5f)
                    val gridStroke = 1.dp.toPx()
                    val dW1 = cropW / 3f
                    val dW2 = cropW * 2f / 3f
                    val dH1 = cropH / 3f
                    val dH2 = cropH * 2f / 3f

                    drawLine(gridColor, Offset(scL + dW1, scT), Offset(scL + dW1, scB), strokeWidth = gridStroke)
                    drawLine(gridColor, Offset(scL + dW2, scT), Offset(scL + dW2, scB), strokeWidth = gridStroke)
                    drawLine(gridColor, Offset(scL, scT + dH1), Offset(scR, scT + dH1), strokeWidth = gridStroke)
                    drawLine(gridColor, Offset(scL, scT + dH2), Offset(scR, scT + dH2), strokeWidth = gridStroke)

                // Corner L-Brackets & Edge Handles
                val bracketLen = 20.dp.toPx()
                val handleThickness = 4.dp.toPx()
                val handleColor = Color.White

                // Top-Left Corner
                drawLine(handleColor, Offset(scL - 2f, scT), Offset(scL + bracketLen, scT), strokeWidth = handleThickness)
                drawLine(handleColor, Offset(scL, scT - 2f), Offset(scL, scT + bracketLen), strokeWidth = handleThickness)

                // Top-Right Corner
                drawLine(handleColor, Offset(scR + 2f, scT), Offset(scR - bracketLen, scT), strokeWidth = handleThickness)
                drawLine(handleColor, Offset(scR, scT - 2f), Offset(scR, scT + bracketLen), strokeWidth = handleThickness)

                // Bottom-Right Corner
                drawLine(handleColor, Offset(scR + 2f, scB), Offset(scR - bracketLen, scB), strokeWidth = handleThickness)
                drawLine(handleColor, Offset(scR, scB + 2f), Offset(scR, scB - bracketLen), strokeWidth = handleThickness)

                // Bottom-Left Corner
                drawLine(handleColor, Offset(scL - 2f, scB), Offset(scL + bracketLen, scB), strokeWidth = handleThickness)
                drawLine(handleColor, Offset(scL, scB + 2f), Offset(scL, scB - bracketLen), strokeWidth = handleThickness)

                // Edge Center Handles
                val midX = (scL + scR) / 2f
                val midY = (scT + scB) / 2f
                val barHalf = 16.dp.toPx()

                if (selectedAspectRatio == null) {
                    // Edge handles are intentionally hidden while an aspect ratio is locked;
                    // corners preserve the chosen ratio and avoid accidental distortion.
                    drawLine(handleColor, Offset(midX - barHalf, scT), Offset(midX + barHalf, scT), strokeWidth = handleThickness)
                    drawLine(handleColor, Offset(midX - barHalf, scB), Offset(midX + barHalf, scB), strokeWidth = handleThickness)
                    drawLine(handleColor, Offset(scL, midY - barHalf), Offset(scL, midY + barHalf), strokeWidth = handleThickness)
                    drawLine(handleColor, Offset(scR, midY - barHalf), Offset(scR, midY + barHalf), strokeWidth = handleThickness)
                }
            }
        }
    }

        // 9. Floating Polygon Selection Overlay Bar
        if (activeTool == ActiveTool.SELECTION && activeSelectionTool == SelectionToolType.POLYGON && polygonPoints.isNotEmpty()) {
            Surface(
                color = StudioSurface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.polygon_points_count, polygonPoints.size),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = { if (polygonPoints.isNotEmpty()) polygonPoints.removeAt(polygonPoints.size - 1) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = stringResource(R.string.cd_undo_point), tint = Color.LightGray)
                    }
                    IconButton(
                        onClick = { polygonPoints.clear() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear_points), tint = Color.Red)
                    }
                    if (polygonPoints.size >= 3) {
                        Button(
                            onClick = {
                                onPolygonComplete?.invoke(polygonPoints.toList())
                                polygonPoints.clear()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_done), tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_apply), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Hold-to-subtract: phones have no Alt key, so this thumb button switches any selection
        // tool to Subtract only while it is held.
        val showSubtractHold = onTemporarySubtract != null && !isSplitCompareActive &&
            ((activeTool == ActiveTool.SELECTION && activeSelectionTool != SelectionToolType.MOVE_SELECTION) ||
                activeTool == ActiveTool.OBJECT_REMOVAL)
        if (showSubtractHold) {
            var holdingSubtract by remember { mutableStateOf(false) }
            val subtractLabel = stringResource(R.string.select_hold_subtract)
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = if (holdingSubtract) Color(0xFFFF2A6D) else StudioSurface.copy(alpha = 0.9f),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 14.dp)
                    .size(56.dp)
                    .semantics { contentDescription = subtractLabel }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                holdingSubtract = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onTemporarySubtract?.invoke(true)
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    holdingSubtract = false
                                    onTemporarySubtract?.invoke(false)
                                }
                            }
                        )
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Remove, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
        }

        // Floating Top Header Bar for Split Comparison
        if (isSplitCompareActive) {
            Surface(
                color = StudioSurface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .testTag("split_compare_bar")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.compare_before),
                        color = AmberAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(Icons.Default.CompareArrows, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource(R.string.compare_after),
                        color = CyanAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (onToggleSplitCompare != null) {
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = onToggleSplitCompare,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel), tint = Color.LightGray, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        } else if (showGeneralOriginal) {
            Surface(
                color = AmberAccent.copy(alpha = 0.92f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .testTag("banner_original_preview")
            ) {
                Text(
                    text = "👁  " + stringResource(R.string.compare_before),
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        // Floating Viewport Zoom Controls & Percentage Badge
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = StudioSurface.copy(alpha = 0.88f),
            shadowElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = { autoFit = false; zoomAroundViewportCenter(0.8f) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = stringResource(R.string.cd_zoom_out),
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Text(
                    text = "${(scale * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .sizeIn(minWidth = 52.dp, minHeight = 48.dp)
                        .clickable {
                            autoFit = true
                            scale = fitScale
                            offsetX = 0f
                            offsetY = 0f
                        }
                        .padding(horizontal = 4.dp)
                )

                IconButton(
                    onClick = { autoFit = false; zoomAroundViewportCenter(1.25f) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.cd_zoom_in),
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = {
                        autoFit = true
                        scale = fitScale
                        offsetX = 0f
                        offsetY = 0f
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.FitScreen,
                        contentDescription = stringResource(R.string.cd_fit_screen),
                        tint = CyanAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
