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
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.CompareArrows
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
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
import kotlin.math.roundToInt
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
    onTemporarySubtract: ((Boolean) -> Unit)? = null,
    /** Pro effect shown live on the selected layer before it is applied. */
    proEffectPreview: com.example.engine.ProEffectPreview? = null,
    /** Double tap on a text layer (Text tool): open its text for typing. */
    onRequestTextEdit: (() -> Unit)? = null,
    /** When set, a tap on the image reports its document point (curves picking) instead of panning. */
    onCanvasSample: ((Float, Float) -> Unit)? = null,
    /** Object removal: area protected from being copied (shown in green). */
    protectMask: Bitmap? = null,
    protectRevision: Long = 0L,
    /** Crop: straighten angle (degrees) previewed live, overlay grid and canvas expansion. */
    cropStraighten: Float = 0f,
    cropGrid: String = "THIRDS",
    cropExpandCanvas: Boolean = false,
    /** Transform tool: FREE (move / scale / rotate), SKEW or DISTORT, and its options. */
    transformMode: String = "FREE",
    /** Reference point 0..8 (TL, T, TR, L, centre, R, BL, B, BR) for rotation. */
    transformPivot: Int = 4,
    transformFromCenter: Boolean = false,
    transformLockAspect: Boolean = true,
    transformSnap: Boolean = true,
    /** HSL targeted adjustment: drag on the image to change the colour under the finger. */
    targetedAdjustActive: Boolean = false,
    onTargetedDrag: ((docX: Float, docY: Float, dxDp: Float, phase: com.example.ui.viewmodel.StrokePhase) -> Unit)? = null
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
    val latestOnSelectLayerForText by rememberUpdatedState(onSelectLayer)
    val latestOnRequestTextEdit by rememberUpdatedState(onRequestTextEdit)
    val latestOnCanvasSample by rememberUpdatedState(onCanvasSample)
    val latestCropStraighten by rememberUpdatedState(cropStraighten)
    val latestCropExpand by rememberUpdatedState(cropExpandCanvas)
    val latestCropRatio by rememberUpdatedState(selectedAspectRatio)
    val latestTransformMode by rememberUpdatedState(transformMode)
    val latestTransformPivot by rememberUpdatedState(transformPivot)
    val latestTransformFromCenter by rememberUpdatedState(transformFromCenter)
    val latestTransformLock by rememberUpdatedState(transformLockAspect)
    val latestTransformSnap by rememberUpdatedState(transformSnap)
    val latestOnTargetedDrag by rememberUpdatedState(onTargetedDrag)
    // Smart guides (document coordinates) and the live value bubble shown while transforming.
    var guideXs by remember { mutableStateOf<List<Float>>(emptyList()) }
    var guideYs by remember { mutableStateOf<List<Float>>(emptyList()) }
    var transformBubble by remember { mutableStateOf<Pair<Offset, String>?>(null) }
    // Last tap on a text layer (time, layer index) for double-tap detection across detector restarts.
    val textTapState = remember { longArrayOf(0L, -1L) }
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

    // Synchronize the crop frame with the ratio and the straighten angle: the largest frame of that
    // ratio, centred, that still lies completely inside the (rotated) image, so no empty corners.
    LaunchedEffect(selectedAspectRatio, project.width, project.height, cropStraighten) {
        if (activeTool == ActiveTool.CROP_PERSPECTIVE && !isPerspectiveActive) {
            val pWidth = project.width.toFloat()
            val pHeight = project.height.toFloat()
            val a = selectedAspectRatio?.takeIf { it > 0f } ?: (pWidth / pHeight)
            val rad = Math.toRadians(cropStraighten.toDouble())
            val c = abs(kotlin.math.cos(rad)).toFloat()
            val sn = abs(kotlin.math.sin(rad)).toFloat()
            val ch = minOf(pWidth / (a * c + sn), pHeight / (a * sn + c))
            val cw = ch * a
            val left = (pWidth - cw) / 2f
            val top = (pHeight - ch) / 2f
            cropRectState = RectF(left, top, left + cw, top + ch)
            latestOnApplyCropRect?.invoke(RectF(cropRectState))
        }
    }

    /** Crop frame allowed area: inside the image (rotated by the straighten angle), or beyond it when expanding. */
    fun cropFrameFits(rect: RectF): Boolean {
        val pW = latestProject.width.toFloat()
        val pH = latestProject.height.toFloat()
        if (latestCropExpand) return rect.left >= -pW && rect.top >= -pH && rect.right <= pW * 2f && rect.bottom <= pH * 2f
        val angle = latestCropStraighten
        if (abs(angle) < 0.01f) return rect.left >= -0.5f && rect.top >= -0.5f && rect.right <= pW + 0.5f && rect.bottom <= pH + 0.5f
        val rad = Math.toRadians(-angle.toDouble())
        val cosA = kotlin.math.cos(rad).toFloat()
        val sinA = kotlin.math.sin(rad).toFloat()
        val cx = pW / 2f
        val cy = pH / 2f
        for ((x, y) in listOf(rect.left to rect.top, rect.right to rect.top, rect.right to rect.bottom, rect.left to rect.bottom)) {
            val dx = x - cx
            val dy = y - cy
            val ux = dx * cosA - dy * sinA + cx
            val uy = dx * sinA + dy * cosA + cy
            if (ux < -0.5f || uy < -0.5f || ux > pW + 0.5f || uy > pH + 0.5f) return false
        }
        return true
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
    LaunchedEffect(project.id, project.width, project.height, renderRevision, previewMaxDimension, showSelectedLayerOriginal, showGeneralOriginal, isSplitCompareActive, selectedLayerIndex, selectionMask, proEffectPreview) {
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
                    snapshot.lutId = null
                    snapshot.adjustments = com.example.data.model.ColorAdjustments()
                } else if (showSelectedLayerOriginal && index == selectedLayerIndex) {
                    snapshot.filter = com.example.data.model.FilterType.NONE
                    snapshot.lutId = null
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
                    selectedLayerId = null,
                    // The live Pro effect is limited to the selection, exactly like the applied effect.
                    proEffectPreview = if (showGeneralOriginal || showSelectedLayerOriginal) null else proEffectPreview,
                    proEffectMask = selectionMask
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
                        snapshot.lutId = null
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
    /** True when the document point lies inside [layer]'s (possibly rotated) box. */
    fun layerContainsDoc(layer: Layer, x: Float, y: Float): Boolean {
        val c = layerCornersDoc(layer)
        var sign = 0
        for (i in 0 until 4) {
            val ax = c[i * 2]; val ay = c[i * 2 + 1]
            val bx = c[((i + 1) % 4) * 2]; val by = c[((i + 1) % 4) * 2 + 1]
            val cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            val s = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
            if (s != 0) {
                if (sign == 0) sign = s else if (s != sign) return false
            }
        }
        return true
    }

    /** Topmost visible text layer under a document point, or -1. */
    fun textLayerIndexAt(x: Float, y: Float): Int {
        val layers = latestProject.layers
        for (i in layers.indices.reversed()) {
            val l = layers[i]
            if (l.isVisible && l.type == com.example.data.model.LayerType.TEXT && layerContainsDoc(l, x, y)) return i
        }
        return -1
    }

    /**
     * Placement of the layer's box WITHOUT its skew / distort: local -> document matrix and the
     * local box size (bitmap pixels, or the text layout / canvas size).
     */
    fun layerBase(layer: Layer): Triple<android.graphics.Matrix, Float, Float> {
        val p = latestProject
        val plain = layer.copy(skewX = 0f, skewY = 0f, distort = null)
        val bmp = layer.bitmap
        return if (bmp != null) {
            Triple(SelectionEngine.getLayerMatrix(plain, p.width, p.height), bmp.width.toFloat(), bmp.height.toFloat())
        } else {
            val size = if (layer.type == com.example.data.model.LayerType.TEXT) {
                LayerRenderer.measureTextLayer(context, layer, p.width.toFloat())
            } else {
                p.width.toFloat() to p.height.toFloat()
            }
            val m = android.graphics.Matrix().apply {
                postTranslate(-size.first / 2f, -size.second / 2f)
                postScale(layer.scaleX, layer.scaleY)
                postRotate(layer.rotation)
                postTranslate(p.width / 2f + layer.positionX, p.height / 2f + layer.positionY)
            }
            Triple(m, size.first, size.second)
        }
    }

    /** Unscaled box size of the layer in document units. */
    fun layerBoxDoc(layer: Layer): Pair<Float, Float> = when {
        layer.bitmap != null -> layer.intrinsicDocumentWidth() to layer.intrinsicDocumentHeight()
        layer.type == com.example.data.model.LayerType.TEXT -> LayerRenderer.measureTextLayer(context, layer, latestProject.width.toFloat())
        else -> latestProject.width.toFloat() to latestProject.height.toFloat()
    }

    fun layerCornersDoc(layer: Layer): FloatArray {
        val (base, w, h) = layerBase(layer)
        val matrix = SelectionEngine.layerShapeMatrix(layer, w, h)?.let { shape ->
            android.graphics.Matrix(shape).apply { postConcat(base) }
        } ?: base
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
                    // Text tool: a gesture that starts on the selected text layer moves it with one
                    // finger and scales / rotates it with two; anywhere else it pans / zooms the view.
                    var gestureOnText: Boolean? = null
                    var textTransformed = false
                    // Tap detection for on-image sampling (curves): one finger that barely moved.
                    var tapStart: Offset? = null
                    var tapCancelled = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (tapStart == null) event.changes.firstOrNull { it.pressed }?.let { tapStart = it.position }
                        if (pressed > 1) tapCancelled = true
                        tapStart?.let { ts ->
                            event.changes.firstOrNull()?.let { c ->
                                if ((c.position - ts).getDistance() > viewConfiguration.touchSlop) tapCancelled = true
                            }
                        }
                        if (gestureOnText == null) {
                            val first = event.changes.firstOrNull { it.pressed }
                            val sel = latestProject.layers.getOrNull(latestSelectedLayerIndex)
                            gestureOnText = first != null && sel != null &&
                                ((activeTool == ActiveTool.TEXT && sel.type == com.example.data.model.LayerType.TEXT) ||
                                    (activeTool == ActiveTool.TRANSFORM && sel.type != com.example.data.model.LayerType.ADJUSTMENT)) &&
                                sel.isVisible && !sel.isLocked && run {
                                    val d = screenToDoc(first.position, Offset(size.width / 2f, size.height / 2f))
                                    layerContainsDoc(sel, d.x, d.y)
                                }
                        }
                        if (gestureOnText == true && pressed >= 2) {
                            val stable = event.changes.filter { it.pressed && it.previousPressed }
                            if (stable.size >= 2) {
                                val a = stable[0]
                                val b = stable[1]
                                val cur = b.position - a.position
                                val prev = b.previousPosition - a.previousPosition
                                val prevLen = prev.getDistance()
                                val zoom = if (prevLen > 1f) (cur.getDistance() / prevLen) else 1f
                                val deg = Math.toDegrees((atan2(cur.y, cur.x) - atan2(prev.y, prev.x)).toDouble()).toFloat()
                                val move = (a.position + b.position) / 2f - (a.previousPosition + b.previousPosition) / 2f
                                val s = scale.coerceAtLeast(0.0001f)
                                latestOnUpdateLayerTransient?.invoke { l ->
                                    l.scaleX = (l.scaleX * zoom).coerceIn(0.05f, 40f)
                                    l.scaleY = (l.scaleY * zoom).coerceIn(0.05f, 40f)
                                    l.rotation = (((l.rotation + deg) % 360f) + 360f) % 360f
                                    l.positionX += move.x / s
                                    l.positionY += move.y / s
                                }
                                textTransformed = true
                            }
                            multiTouchActive = true
                            event.changes.forEach { change -> if (change.pressed) change.consume() }
                            continue
                        }
                        // One finger pans the viewport only when the active tool has no one-finger
                        // gesture of its own: painting a layer mask (Layers) and dragging a text
                        // layer (Text) must receive the drag instead.
                        val liveLayer = latestProject.layers.getOrNull(latestSelectedLayerIndex)
                        val toolOwnsOneFinger = (activeTool == ActiveTool.ADJUST && targetedAdjustActive) ||
                            (activeTool == ActiveTool.LAYERS && liveLayer?.isEditingMask == true) ||
                            (activeTool == ActiveTool.TEXT && gestureOnText == true)
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
                    if (textTransformed) latestOnCommitHistoryState?.invoke()
                    val sampler = latestOnCanvasSample
                    val ts = tapStart
                    if (sampler != null && ts != null && !tapCancelled) {
                        val d = screenToDoc(ts, Offset(size.width / 2f, size.height / 2f))
                        if (d.x in 0f..latestProject.width.toFloat() && d.y in 0f..latestProject.height.toFloat()) sampler(d.x, d.y)
                    }

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
                    project.id,
                    targetedAdjustActive
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
                                if (activePerspectiveHandle in 0..3) liveTouchCursor = startOffset
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

                                    if (activePerspectiveHandle in 0..3) liveTouchCursor = change.position
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
                                liveTouchCursor = null
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
                                    abs(startOffset.y - scT) < hitThreshold && startOffset.x in scL..scR -> CropHandle.EDGE_T
                                    abs(startOffset.y - scB) < hitThreshold && startOffset.x in scL..scR -> CropHandle.EDGE_B
                                    abs(startOffset.x - scL) < hitThreshold && startOffset.y in scT..scB -> CropHandle.EDGE_L
                                    abs(startOffset.x - scR) < hitThreshold && startOffset.y in scT..scB -> CropHandle.EDGE_R
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
                                    val ratio = latestCropRatio?.takeIf { it > 0f }
                                    val cur = cropRectState
                                    val bounds = if (latestCropExpand) RectF(-pWidth, -pHeight, pWidth * 2f, pHeight * 2f) else RectF(0f, 0f, pWidth, pHeight)

                                    // Resize keeping the opposite corner (or edge centre) fixed; with a ratio the
                                    // frame is scaled down as a whole at the limits, so the ratio always holds.
                                    fun fromAnchor(ax: Float, ay: Float, dirX: Int, dirY: Int, wantW: Float, wantH: Float): RectF {
                                        var w = wantW.coerceAtLeast(minCropSize)
                                        var h = wantH.coerceAtLeast(minCropSize)
                                        if (ratio != null) {
                                            if (w / h > ratio) h = w / ratio else w = h * ratio
                                        }
                                        val maxW = if (dirX > 0) bounds.right - ax else if (dirX < 0) ax - bounds.left else w
                                        val maxH = if (dirY > 0) bounds.bottom - ay else if (dirY < 0) ay - bounds.top else h
                                        if (ratio != null) {
                                            val k = minOf(1f, maxW / w, maxH / h)
                                            w *= k; h *= k
                                        } else {
                                            w = w.coerceAtMost(maxW); h = h.coerceAtMost(maxH)
                                        }
                                        val left = if (dirX > 0) ax else if (dirX < 0) ax - w else ax - w / 2f
                                        val top = if (dirY > 0) ay else if (dirY < 0) ay - h else ay - h / 2f
                                        return RectF(left, top, left + w, top + h)
                                    }

                                    val candidate: RectF? = when (activeCropHandle) {
                                        CropHandle.BODY -> {
                                            val w = cur.width()
                                            val h = cur.height()
                                            val left = (cur.left + dxDoc).coerceIn(bounds.left, (bounds.right - w).coerceAtLeast(bounds.left))
                                            val top = (cur.top + dyDoc).coerceIn(bounds.top, (bounds.bottom - h).coerceAtLeast(bounds.top))
                                            RectF(left, top, left + w, top + h)
                                        }
                                        CropHandle.CORNER_TL -> fromAnchor(cur.right, cur.bottom, -1, -1, cur.right - (cur.left + dxDoc), cur.bottom - (cur.top + dyDoc))
                                        CropHandle.CORNER_TR -> fromAnchor(cur.left, cur.bottom, 1, -1, (cur.right + dxDoc) - cur.left, cur.bottom - (cur.top + dyDoc))
                                        CropHandle.CORNER_BR -> fromAnchor(cur.left, cur.top, 1, 1, (cur.right + dxDoc) - cur.left, (cur.bottom + dyDoc) - cur.top)
                                        CropHandle.CORNER_BL -> fromAnchor(cur.right, cur.top, -1, 1, cur.right - (cur.left + dxDoc), (cur.bottom + dyDoc) - cur.top)
                                        CropHandle.EDGE_T -> if (ratio == null) RectF(cur.left, (cur.top + dyDoc).coerceIn(bounds.top, cur.bottom - minCropSize), cur.right, cur.bottom)
                                            else fromAnchor(cur.centerX(), cur.bottom, 0, -1, 0f, cur.bottom - (cur.top + dyDoc))
                                        CropHandle.EDGE_B -> if (ratio == null) RectF(cur.left, cur.top, cur.right, (cur.bottom + dyDoc).coerceIn(cur.top + minCropSize, bounds.bottom))
                                            else fromAnchor(cur.centerX(), cur.top, 0, 1, 0f, (cur.bottom + dyDoc) - cur.top)
                                        CropHandle.EDGE_L -> if (ratio == null) RectF((cur.left + dxDoc).coerceIn(bounds.left, cur.right - minCropSize), cur.top, cur.right, cur.bottom)
                                            else fromAnchor(cur.right, cur.centerY(), -1, 0, cur.right - (cur.left + dxDoc), 0f)
                                        CropHandle.EDGE_R -> if (ratio == null) RectF(cur.left, cur.top, (cur.right + dxDoc).coerceIn(cur.left + minCropSize, bounds.right), cur.bottom)
                                            else fromAnchor(cur.left, cur.centerY(), 1, 0, (cur.right + dxDoc) - cur.left, 0f)
                                        else -> null
                                    }
                                    // A frame that would leave the (straightened) image keeps its last valid shape.
                                    if (candidate != null && candidate.width() >= minCropSize - 0.5f && candidate.height() >= minCropSize - 0.5f && cropFrameFits(candidate)) {
                                        cropRectState = candidate
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
                                    if (event.changes.count { it.pressed } > 1) {
                                        wandBubble = null
                                        // A second finger (pinch) ends a tolerance drag cleanly at its last value.
                                        if (dragging) {
                                            latestOnMagicWandDrag?.invoke(downDoc.x.toInt(), downDoc.y.toInt(), lastTolerance, com.example.ui.viewmodel.StrokePhase.END)
                                        }
                                        return@awaitEachGesture
                                    }
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
                    } else if (activeTool == ActiveTool.ADJUST && targetedAdjustActive) {
                        // Targeted adjustment: press on a colour, drag right / left to raise / lower it.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val d = toDoc(down.position)
                            if (d.x !in 0f..latestProject.width.toFloat() || d.y !in 0f..latestProject.height.toFloat()) return@awaitEachGesture
                            latestOnTargetedDrag?.invoke(d.x, d.y, 0f, com.example.ui.viewmodel.StrokePhase.START)
                            var lastDx = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } > 1) break
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                val dx = (change.position.x - down.position.x) / density
                                if (abs(dx - lastDx) >= 0.5f) {
                                    lastDx = dx
                                    latestOnTargetedDrag?.invoke(d.x, d.y, dx, com.example.ui.viewmodel.StrokePhase.MOVE)
                                }
                            }
                            latestOnTargetedDrag?.invoke(d.x, d.y, lastDx, com.example.ui.viewmodel.StrokePhase.END)
                        }
                    } else if (activeTool == ActiveTool.TRANSFORM && selectedLayerIndex in latestProject.layers.indices) {
                        // Professional transform: corners scale from the opposite corner (or the centre),
                        // edges along the layer's own axes, rotation is relative with angle snapping and
                        // a movable reference point, smart guides while moving, plus skew and distort.
                        class Start(
                            val layer: Layer,
                            val handle: TransformHandle,
                            val startDoc: PointF,
                            val hw: Float,
                            val hh: Float,
                            val baseInv: android.graphics.Matrix,
                            val bw: Float,
                            val bh: Float,
                            val pivot: PointF,
                            val angle0: Float,
                            val corners: FloatArray
                        )
                        var st: Start? = null
                        fun rotVec(x: Float, y: Float, deg: Float): PointF {
                            val r = Math.toRadians(deg.toDouble())
                            val c = kotlin.math.cos(r).toFloat()
                            val sn = kotlin.math.sin(r).toFloat()
                            return PointF(x * c - y * sn, x * sn + y * c)
                        }
                        fun toLocal(m: android.graphics.Matrix, p: PointF): PointF {
                            val a = floatArrayOf(p.x, p.y)
                            m.mapPoints(a)
                            return PointF(a[0], a[1])
                        }

                        detectDragGestures(
                            onDragStart = { startOffset ->
                                st = null
                                val layer = latestProject.layers.getOrNull(latestSelectedLayerIndex)
                                if (layer != null && !layer.isLocked && layer.isVisible) {
                                    val pts = layerCornersDoc(layer)
                                    fun sc(i: Int) = Offset(curStartX() + (pts[i * 2] / latestProject.width) * curDrawW(), curStartY() + (pts[i * 2 + 1] / latestProject.height) * curDrawH())
                                    val c0 = sc(0); val c1 = sc(1); val c2 = sc(2); val c3 = sc(3)
                                    val center = Offset((c0.x + c1.x + c2.x + c3.x) / 4f, (c0.y + c1.y + c2.y + c3.y) / 4f)
                                    val topMid = Offset((c0.x + c1.x) / 2f, (c0.y + c1.y) / 2f)
                                    val rightMid = Offset((c1.x + c2.x) / 2f, (c1.y + c2.y) / 2f)
                                    val bottomMid = Offset((c2.x + c3.x) / 2f, (c2.y + c3.y) / 2f)
                                    val leftMid = Offset((c3.x + c0.x) / 2f, (c3.y + c0.y) / 2f)
                                    val v = topMid - center
                                    val vl = v.getDistance()
                                    val rotHandle = if (vl > 0f) topMid + v / vl * 36.dp.toPx() else Offset(topMid.x, topMid.y - 36.dp.toPx())
                                    val th = 36.dp.toPx()
                                    fun near(a: Offset) = (startOffset - a).getDistance() < th
                                    val mode = latestTransformMode
                                    val startDocPt = toDoc(startOffset)
                                    val handle = when {
                                        mode == "FREE" && near(rotHandle) -> TransformHandle.ROTATION
                                        mode != "SKEW" && near(c0) -> TransformHandle.CORNER_TL
                                        mode != "SKEW" && near(c1) -> TransformHandle.CORNER_TR
                                        mode != "SKEW" && near(c2) -> TransformHandle.CORNER_BR
                                        mode != "SKEW" && near(c3) -> TransformHandle.CORNER_BL
                                        mode != "DISTORT" && near(topMid) -> TransformHandle.EDGE_T
                                        mode != "DISTORT" && near(rightMid) -> TransformHandle.EDGE_R
                                        mode != "DISTORT" && near(bottomMid) -> TransformHandle.EDGE_B
                                        mode != "DISTORT" && near(leftMid) -> TransformHandle.EDGE_L
                                        layerContainsDoc(layer, startDocPt.x, startDocPt.y) || (startOffset - center).getDistance() < 60.dp.toPx() -> TransformHandle.BODY
                                        else -> TransformHandle.NONE
                                    }
                                    if (handle != TransformHandle.NONE) {
                                        val (base, bw, bh) = layerBase(layer)
                                        val inv = android.graphics.Matrix()
                                        base.invert(inv)
                                        val (boxW, boxH) = layerBoxDoc(layer)
                                        val cx = latestProject.width / 2f + layer.positionX
                                        val cy = latestProject.height / 2f + layer.positionY
                                        val pv = latestTransformPivot.coerceIn(0, 8)
                                        val pvOff = rotVec(layer.scaleX * boxW / 2f * ((pv % 3) - 1), layer.scaleY * boxH / 2f * ((pv / 3) - 1), layer.rotation)
                                        val pivot = PointF(cx + pvOff.x, cy + pvOff.y)
                                        val angle0 = Math.toDegrees(atan2((startDocPt.y - pivot.y).toDouble(), (startDocPt.x - pivot.x).toDouble())).toFloat()
                                        st = Start(layer.copy(distort = layer.distort?.copyOf()), handle, startDocPt, boxW / 2f, boxH / 2f, inv, bw, bh, pivot, angle0, pts)
                                    }
                                    activeTransformHandle = handle
                                } else {
                                    activeTransformHandle = TransformHandle.NONE
                                }
                            },
                            onDrag = { change, _ ->
                                val s0 = st
                                if (s0 != null) {
                                    change.consume()
                                    val p = toDoc(change.position)
                                    val l0 = s0.layer
                                    val docW = latestProject.width.toFloat()
                                    val docH = latestProject.height.toFloat()
                                    val c0x = docW / 2f + l0.positionX
                                    val c0y = docH / 2f + l0.positionY
                                    val mode = latestTransformMode
                                    when (s0.handle) {
                                        TransformHandle.BODY -> {
                                            var dx = p.x - s0.startDoc.x
                                            var dy = p.y - s0.startDoc.y
                                            val gx = ArrayList<Float>()
                                            val gy = ArrayList<Float>()
                                            if (latestTransformSnap) {
                                                // Smart guides: canvas edges / centre and other layers' edges / centres.
                                                val thr = 8.dp.toPx() / (curDrawW() / docW).coerceAtLeast(0.0001f)
                                                val xs = floatArrayOf(s0.corners[0], s0.corners[2], s0.corners[4], s0.corners[6])
                                                val ys = floatArrayOf(s0.corners[1], s0.corners[3], s0.corners[5], s0.corners[7])
                                                val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
                                                val tx = arrayListOf(0f, docW / 2f, docW)
                                                val ty = arrayListOf(0f, docH / 2f, docH)
                                                latestProject.layers.forEachIndexed { i, other ->
                                                    if (i == latestSelectedLayerIndex || !other.isVisible || other.type == com.example.data.model.LayerType.ADJUSTMENT ||
                                                        other.type == com.example.data.model.LayerType.COLOR_FILL) return@forEachIndexed
                                                    val oc = layerCornersDoc(other)
                                                    val ox = floatArrayOf(oc[0], oc[2], oc[4], oc[6]); val oy = floatArrayOf(oc[1], oc[3], oc[5], oc[7])
                                                    tx.add(ox.min()); tx.add((ox.min() + ox.max()) / 2f); tx.add(ox.max())
                                                    ty.add(oy.min()); ty.add((oy.min() + oy.max()) / 2f); ty.add(oy.max())
                                                }
                                                var bestX = thr; var snapX = 0f; var lineX: Float? = null
                                                for (m in floatArrayOf(minX + dx, (minX + maxX) / 2f + dx, maxX + dx)) for (t in tx) {
                                                    val d = t - m
                                                    if (abs(d) < bestX) { bestX = abs(d); snapX = d; lineX = t }
                                                }
                                                var bestY = thr; var snapY = 0f; var lineY: Float? = null
                                                for (m in floatArrayOf(minY + dy, (minY + maxY) / 2f + dy, maxY + dy)) for (t in ty) {
                                                    val d = t - m
                                                    if (abs(d) < bestY) { bestY = abs(d); snapY = d; lineY = t }
                                                }
                                                dx += snapX; dy += snapY
                                                lineX?.let { gx.add(it) }
                                                lineY?.let { gy.add(it) }
                                            }
                                            guideXs = gx
                                            guideYs = gy
                                            val nx = l0.positionX + dx
                                            val ny = l0.positionY + dy
                                            latestOnUpdateLayerTransient?.invoke { l -> l.positionX = nx; l.positionY = ny }
                                            transformBubble = change.position to "X ${nx.roundToInt()}  Y ${ny.roundToInt()}"
                                        }

                                        TransformHandle.ROTATION -> {
                                            val a = Math.toDegrees(atan2((p.y - s0.pivot.y).toDouble(), (p.x - s0.pivot.x).toDouble())).toFloat()
                                            var newRot = l0.rotation + (a - s0.angle0)
                                            newRot = ((newRot % 360f) + 360f) % 360f
                                            if (latestTransformSnap) {
                                                val step = (newRot / 15f).roundToInt() * 15f
                                                val limit = if (step % 90f == 0f) 5f else 2.5f
                                                if (abs(newRot - step) < limit) newRot = ((step % 360f) + 360f) % 360f
                                            }
                                            var delta = newRot - l0.rotation
                                            delta = ((delta % 360f) + 540f) % 360f - 180f
                                            val off = rotVec(c0x - s0.pivot.x, c0y - s0.pivot.y, delta)
                                            val nx = s0.pivot.x + off.x - docW / 2f
                                            val ny = s0.pivot.y + off.y - docH / 2f
                                            latestOnUpdateLayerTransient?.invoke { l -> l.rotation = newRot; l.positionX = nx; l.positionY = ny }
                                            val shown = if (newRot > 180f) newRot - 360f else newRot
                                            transformBubble = change.position to String.format(java.util.Locale.US, "%.1f°", shown)
                                        }

                                        TransformHandle.CORNER_TL, TransformHandle.CORNER_TR, TransformHandle.CORNER_BR, TransformHandle.CORNER_BL -> {
                                            val idx = when (s0.handle) { TransformHandle.CORNER_TL -> 0; TransformHandle.CORNER_TR -> 1; TransformHandle.CORNER_BR -> 2; else -> 3 }
                                            if (mode == "DISTORT") {
                                                // Move one corner freely (in the layer's own units, non-destructive).
                                                val lp = toLocal(s0.baseInv, p)
                                                val ls = toLocal(s0.baseInv, s0.startDoc)
                                                val d = l0.distort?.copyOf() ?: floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
                                                d[idx * 2] = (d[idx * 2] + (lp.x - ls.x) / s0.bw.coerceAtLeast(1f)).coerceIn(-3f, 4f)
                                                d[idx * 2 + 1] = (d[idx * 2 + 1] + (lp.y - ls.y) / s0.bh.coerceAtLeast(1f)).coerceIn(-3f, 4f)
                                                latestOnUpdateLayerTransient?.invoke { l -> l.distort = d }
                                            } else {
                                                val sx = if (idx == 1 || idx == 2) 1f else -1f
                                                val sy = if (idx >= 2) 1f else -1f
                                                val fromCenter = latestTransformFromCenter
                                                // Anchor = opposite corner (or the centre); vector to the finger in the layer's frame.
                                                val anchorOff = if (fromCenter) PointF(0f, 0f) else rotVec(l0.scaleX * s0.hw * -sx, l0.scaleY * s0.hh * -sy, l0.rotation)
                                                val ax = c0x + anchorOff.x
                                                val ay = c0y + anchorOff.y
                                                val v = rotVec(p.x - ax, p.y - ay, -l0.rotation)
                                                val span = if (fromCenter) 1f else 2f
                                                val dX = l0.scaleX * s0.hw * span * sx
                                                val dY = l0.scaleY * s0.hh * span * sy
                                                var fx: Float
                                                var fy: Float
                                                if (latestTransformLock) {
                                                    val f = ((v.x * dX + v.y * dY) / (dX * dX + dY * dY).coerceAtLeast(1e-6f)).coerceAtLeast(0.02f)
                                                    fx = f; fy = f
                                                } else {
                                                    fx = (v.x / dX.takeIf { abs(it) > 1e-4f }.let { it ?: 1f }).coerceAtLeast(0.02f)
                                                    fy = (v.y / dY.takeIf { abs(it) > 1e-4f }.let { it ?: 1f }).coerceAtLeast(0.02f)
                                                }
                                                val nsx = (l0.scaleX * fx).coerceIn(-50f, 50f)
                                                val nsy = (l0.scaleY * fy).coerceIn(-50f, 50f)
                                                val cOff = if (fromCenter) PointF(0f, 0f) else rotVec(nsx * s0.hw * sx, nsy * s0.hh * sy, l0.rotation)
                                                val ncx = ax + cOff.x
                                                val ncy = ay + cOff.y
                                                latestOnUpdateLayerTransient?.invoke { l ->
                                                    l.scaleX = nsx; l.scaleY = nsy
                                                    l.positionX = ncx - docW / 2f; l.positionY = ncy - docH / 2f
                                                }
                                                transformBubble = change.position to "${(abs(nsx) * s0.hw * 2f).roundToInt()} × ${(abs(nsy) * s0.hh * 2f).roundToInt()}"
                                            }
                                        }

                                        TransformHandle.EDGE_T, TransformHandle.EDGE_B, TransformHandle.EDGE_L, TransformHandle.EDGE_R -> {
                                            val horizontalEdge = s0.handle == TransformHandle.EDGE_T || s0.handle == TransformHandle.EDGE_B
                                            val sign = if (s0.handle == TransformHandle.EDGE_B || s0.handle == TransformHandle.EDGE_R) 1f else -1f
                                            if (mode == "SKEW") {
                                                // Slide an edge along itself: skew (in the layer's own axes).
                                                val lp = toLocal(s0.baseInv, p)
                                                val ls = toLocal(s0.baseInv, s0.startDoc)
                                                if (horizontalEdge) {
                                                    val k0 = kotlin.math.tan(Math.toRadians(l0.skewX.toDouble())).toFloat()
                                                    val k = k0 + sign * (lp.x - ls.x) / (s0.bh / 2f).coerceAtLeast(1f)
                                                    val deg = Math.toDegrees(kotlin.math.atan(k.toDouble())).toFloat().coerceIn(-60f, 60f)
                                                    latestOnUpdateLayerTransient?.invoke { l -> l.skewX = deg }
                                                    transformBubble = change.position to String.format(java.util.Locale.US, "%.1f°", deg)
                                                } else {
                                                    val k0 = kotlin.math.tan(Math.toRadians(l0.skewY.toDouble())).toFloat()
                                                    val k = k0 + sign * (lp.y - ls.y) / (s0.bw / 2f).coerceAtLeast(1f)
                                                    val deg = Math.toDegrees(kotlin.math.atan(k.toDouble())).toFloat().coerceIn(-60f, 60f)
                                                    latestOnUpdateLayerTransient?.invoke { l -> l.skewY = deg }
                                                    transformBubble = change.position to String.format(java.util.Locale.US, "%.1f°", deg)
                                                }
                                            } else {
                                                // Scale along the layer's own axis, opposite edge fixed (or the centre).
                                                val fromCenter = latestTransformFromCenter
                                                val ex = if (horizontalEdge) 0f else sign
                                                val ey = if (horizontalEdge) sign else 0f
                                                val anchorOff = if (fromCenter) PointF(0f, 0f) else rotVec(l0.scaleX * s0.hw * -ex, l0.scaleY * s0.hh * -ey, l0.rotation)
                                                val ax = c0x + anchorOff.x
                                                val ay = c0y + anchorOff.y
                                                val v = rotVec(p.x - ax, p.y - ay, -l0.rotation)
                                                val span = if (fromCenter) 1f else 2f
                                                val f = if (horizontalEdge) {
                                                    (v.y / (l0.scaleY * s0.hh * span * ey).let { if (abs(it) < 1e-4f) 1f else it }).coerceAtLeast(0.02f)
                                                } else {
                                                    (v.x / (l0.scaleX * s0.hw * span * ex).let { if (abs(it) < 1e-4f) 1f else it }).coerceAtLeast(0.02f)
                                                }
                                                val lock = latestTransformLock
                                                val nsx = if (!horizontalEdge || lock) (l0.scaleX * f).coerceIn(-50f, 50f) else l0.scaleX
                                                val nsy = if (horizontalEdge || lock) (l0.scaleY * f).coerceIn(-50f, 50f) else l0.scaleY
                                                val cOff = if (fromCenter) PointF(0f, 0f) else rotVec(nsx * s0.hw * ex, nsy * s0.hh * ey, l0.rotation)
                                                val ncx = ax + cOff.x
                                                val ncy = ay + cOff.y
                                                latestOnUpdateLayerTransient?.invoke { l ->
                                                    l.scaleX = nsx; l.scaleY = nsy
                                                    l.positionX = ncx - docW / 2f; l.positionY = ncy - docH / 2f
                                                }
                                                transformBubble = change.position to "${(abs(nsx) * s0.hw * 2f).roundToInt()} × ${(abs(nsy) * s0.hh * 2f).roundToInt()}"
                                            }
                                        }

                                        else -> {}
                                    }
                                }
                            },
                            onDragEnd = {
                                if (st != null) latestOnCommitHistoryState?.invoke()
                                st = null
                                activeTransformHandle = TransformHandle.NONE
                                guideXs = emptyList()
                                guideYs = emptyList()
                                transformBubble = null
                                showSnapV = false
                                showSnapH = false
                            },
                            onDragCancel = {
                                if (st != null) latestOnCommitHistoryState?.invoke()
                                st = null
                                activeTransformHandle = TransformHandle.NONE
                                guideXs = emptyList()
                                guideYs = emptyList()
                                transformBubble = null
                            }
                        )
                    } else if (activeTool == ActiveTool.TEXT) {
                        // Text tool: tap a text layer to select it, double tap to edit its text, and
                        // drag the selected text when the drag starts on it (elsewhere the view pans).
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downDoc = toDoc(down.position)
                            val sel = latestProject.layers.getOrNull(latestSelectedLayerIndex)
                            val onSelectedText = sel != null && sel.type == com.example.data.model.LayerType.TEXT &&
                                sel.isVisible && !sel.isLocked && layerContainsDoc(sel, downDoc.x, downDoc.y)
                            val slop = viewConfiguration.touchSlop
                            var last = down.position
                            var moved = false
                            var dragged = false
                            var multi = false
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } > 1) { multi = true; break }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                if (!moved && (change.position - down.position).getDistance() > slop) moved = true
                                if (moved && onSelectedText) {
                                    val d = change.position - last
                                    val s = scale.coerceAtLeast(0.0001f)
                                    latestOnUpdateLayerTransient?.invoke { l ->
                                        l.positionX += d.x / s
                                        l.positionY += d.y / s
                                    }
                                    dragged = true
                                    change.consume()
                                }
                                last = change.position
                            }
                            if (dragged) {
                                latestOnCommitHistoryState?.invoke()
                            } else if (!moved && !multi) {
                                val hit = textLayerIndexAt(downDoc.x, downDoc.y)
                                if (hit >= 0) {
                                    val now = System.currentTimeMillis()
                                    val isDouble = hit.toLong() == textTapState[1] && now - textTapState[0] < 350L
                                    if (hit != latestSelectedLayerIndex) latestOnSelectLayerForText?.invoke(hit)
                                    if (isDouble) latestOnRequestTextEdit?.invoke()
                                    textTapState[0] = if (isDouble) 0L else now
                                    textTapState[1] = hit.toLong()
                                }
                            }
                        }
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
                } else if (activeTool == ActiveTool.CROP_PERSPECTIVE && !isPerspectiveActive && abs(cropStraighten) >= 0.01f) {
                    rotate(cropStraighten, pivot = Offset(startX + targetDrawW / 2f, startY + targetDrawH / 2f)) {
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(startX.toInt(), startY.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(targetDrawW.toInt(), targetDrawH.toInt())
                        )
                    }
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
                if (curLayer != null && lMask != null && !lMask.isRecycled) {
                    // Hidden (black) mask areas are covered in red, revealed areas stay clear, and a
                    // linked mask is shown where it currently is (moved with its layer).
                    drawIntoCanvas { c ->
                        val nc = c.nativeCanvas
                        val bounds = android.graphics.RectF(startX, startY, startX + targetDrawW, startY + targetDrawH)
                        val saved = nc.saveLayer(bounds, null)
                        nc.drawRect(bounds, android.graphics.Paint().apply { color = 0x80E53935.toInt() })
                        val m = android.graphics.Matrix().apply {
                            setScale(project.width.toFloat() / lMask.width, project.height.toFloat() / lMask.height)
                            com.example.engine.LayerRenderer.linkedMaskMatrix(curLayer, project.width, project.height)?.let { postConcat(it) }
                            postScale(targetDrawW / project.width, targetDrawH / project.height)
                            postTranslate(startX, startY)
                        }
                        nc.drawBitmap(lMask, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
                        })
                        nc.restoreToCount(saved)
                    }
                }
            }

            // 4.52. Object removal: protected area (never used as texture source) in green.
            if (protectMask != null && !protectMask.isRecycled && protectRevision >= 0L) {
                drawImage(
                    image = protectMask.asImageBitmap(),
                    dstOffset = androidx.compose.ui.unit.IntOffset(startX.toInt(), startY.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(targetDrawW.toInt(), targetDrawH.toInt()),
                    alpha = 0.45f,
                    colorFilter = ColorFilter.tint(Color(0xFF22C55E))
                )
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
            val loupeTool = activeTool == ActiveTool.SELECTION || activeTool == ActiveTool.OBJECT_REMOVAL || isEditingSelectedMask ||
                (activeTool == ActiveTool.CROP_PERSPECTIVE && isPerspectiveActive)
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
            // Smart guides and the live value bubble (transform tool).
            if (activeTool == ActiveTool.TRANSFORM) {
                val guideColor = Color(0xFFFF4FD8)
                guideXs.forEach { gx ->
                    val x = startX + gx / project.width * targetDrawW
                    drawLine(guideColor, Offset(x, startY), Offset(x, startY + targetDrawH), strokeWidth = 1.dp.toPx())
                }
                guideYs.forEach { gy ->
                    val y = startY + gy / project.height * targetDrawH
                    drawLine(guideColor, Offset(startX, y), Offset(startX + targetDrawW, y), strokeWidth = 1.dp.toPx())
                }
                transformBubble?.let { (pos, label) ->
                    drawIntoCanvas { c ->
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE
                            textSize = 13.sp.toPx()
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                        val tw = paint.measureText(label)
                        val bx = pos.x
                        val by = (pos.y - 44.dp.toPx()).coerceAtLeast(20.dp.toPx())
                        c.nativeCanvas.drawRoundRect(
                            bx - tw / 2f - 8.dp.toPx(), by - 16.dp.toPx(), bx + tw / 2f + 8.dp.toPx(), by + 6.dp.toPx(),
                            8.dp.toPx(), 8.dp.toPx(),
                            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
                        )
                        c.nativeCanvas.drawText(label, bx, by, paint)
                    }
                }
                // Reference point used for rotation.
                project.layers.getOrNull(selectedLayerIndex)?.takeIf { it.isVisible }?.let { layer ->
                    val pv = transformPivot.coerceIn(0, 8)
                    if (pv != 4 && transformMode == "FREE") {
                        val pts = layerCornersDoc(layer)
                        val cx = (pts[0] + pts[2] + pts[4] + pts[6]) / 4f
                        val cy = (pts[1] + pts[3] + pts[5] + pts[7]) / 4f
                        val (bw, bh) = layerBoxDoc(layer)
                        val r = Math.toRadians(layer.rotation.toDouble())
                        val ox = layer.scaleX * bw / 2f * ((pv % 3) - 1)
                        val oy = layer.scaleY * bh / 2f * ((pv / 3) - 1)
                        val px = cx + (ox * kotlin.math.cos(r) - oy * kotlin.math.sin(r)).toFloat()
                        val py = cy + (ox * kotlin.math.sin(r) + oy * kotlin.math.cos(r)).toFloat()
                        val sp = Offset(startX + px / project.width * targetDrawW, startY + py / project.height * targetDrawH)
                        drawCircle(Color.White, radius = 7.dp.toPx(), center = sp, style = Stroke(width = 2.dp.toPx()))
                        drawLine(Color.White, Offset(sp.x - 10.dp.toPx(), sp.y), Offset(sp.x + 10.dp.toPx(), sp.y), strokeWidth = 1.5.dp.toPx())
                        drawLine(Color.White, Offset(sp.x, sp.y - 10.dp.toPx()), Offset(sp.x, sp.y + 10.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                    }
                }
            }

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

                    // Composition grid (thirds / golden ratio / diagonals); a fine grid while straightening.
                    val gridColor = Color.White.copy(alpha = 0.5f)
                    val gridStroke = 1.dp.toPx()
                    if (abs(cropStraighten) >= 0.01f) {
                        val fine = Color.White.copy(alpha = 0.22f)
                        for (k in 1 until 8) {
                            drawLine(fine, Offset(scL + cropW * k / 8f, scT), Offset(scL + cropW * k / 8f, scB), strokeWidth = gridStroke)
                            drawLine(fine, Offset(scL, scT + cropH * k / 8f), Offset(scR, scT + cropH * k / 8f), strokeWidth = gridStroke)
                        }
                    }
                    when (cropGrid) {
                        "GOLDEN" -> for (fr in floatArrayOf(0.382f, 0.618f)) {
                            drawLine(gridColor, Offset(scL + cropW * fr, scT), Offset(scL + cropW * fr, scB), strokeWidth = gridStroke)
                            drawLine(gridColor, Offset(scL, scT + cropH * fr), Offset(scR, scT + cropH * fr), strokeWidth = gridStroke)
                        }
                        "DIAGONAL" -> {
                            drawLine(gridColor, Offset(scL, scT), Offset(scR, scB), strokeWidth = gridStroke)
                            drawLine(gridColor, Offset(scR, scT), Offset(scL, scB), strokeWidth = gridStroke)
                        }
                        "NONE" -> {}
                        else -> for (fr in floatArrayOf(1f / 3f, 2f / 3f)) {
                            drawLine(gridColor, Offset(scL + cropW * fr, scT), Offset(scL + cropW * fr, scB), strokeWidth = gridStroke)
                            drawLine(gridColor, Offset(scL, scT + cropH * fr), Offset(scR, scT + cropH * fr), strokeWidth = gridStroke)
                        }
                    }

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

        // Zoom percentage: a small, unobtrusive badge (pinch to zoom). It dims when the zoom is not
        // changing so it never hides the image; tapping it fits the image to the screen.
        val zoomPercent = (scale * 100).roundToInt()
        var zoomRecentlyChanged by remember { mutableStateOf(false) }
        LaunchedEffect(zoomPercent) {
            zoomRecentlyChanged = true
            delay(1500)
            zoomRecentlyChanged = false
        }
        val badgeAlpha by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (zoomRecentlyChanged) 1f else 0.55f,
            label = "zoomBadgeAlpha"
        )
        val fitDescription = stringResource(R.string.cd_fit_screen)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp)
                .graphicsLayer { alpha = badgeAlpha }
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = fitDescription, enabled = !autoFit) {
                    autoFit = true
                    scale = fitScale
                    offsetX = 0f
                    offsetY = 0f
                }
                .testTag("badge_zoom_percent")
        ) {
            Text(
                text = "$zoomPercent%",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}
