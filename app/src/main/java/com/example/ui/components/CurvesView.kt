package com.example.ui.components

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.ColorAdjustments
import com.example.engine.ImageProcessor
import com.example.ui.theme.StudioSurfaceVariant
import kotlin.math.hypot

enum class CurveChannel(val labelRes: Int, val color: Color) {
    RGB(R.string.curves_channel_rgb, Color.White),
    RED(R.string.curves_channel_red, Color(0xFFEF5350)),
    GREEN(R.string.curves_channel_green, Color(0xFF66BB6A)),
    BLUE(R.string.curves_channel_blue, Color(0xFF42A5F5))
}

@Composable
fun CurvesView(
    adjustments: ColorAdjustments,
    onAdjustmentsChange: (ColorAdjustments) -> Unit,
    onAdjustmentsCommit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedChannel by remember { mutableStateOf(CurveChannel.RGB) }
    var selectedPointIndex by remember { mutableStateOf<Int?>(null) }

    val currentPoints = when (selectedChannel) {
        CurveChannel.RGB -> adjustments.curvesRgb
        CurveChannel.RED -> adjustments.curvesRed
        CurveChannel.GREEN -> adjustments.curvesGreen
        CurveChannel.BLUE -> adjustments.curvesBlue
    }

    fun updatePoints(newPoints: List<PointF>) {
        val sorted = newPoints.sortedBy { it.x }
        val updated = when (selectedChannel) {
            CurveChannel.RGB -> adjustments.copy(curvesRgb = sorted)
            CurveChannel.RED -> adjustments.copy(curvesRed = sorted)
            CurveChannel.GREEN -> adjustments.copy(curvesGreen = sorted)
            CurveChannel.BLUE -> adjustments.copy(curvesBlue = sorted)
        }
        onAdjustmentsChange(updated)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Channel Selector Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CurveChannel.values().forEach { channel ->
                val isSelected = channel == selectedChannel
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        selectedChannel = channel
                        selectedPointIndex = null
                    },
                    label = {
                        Text(
                            text = stringResource(channel.labelRes),
                            color = if (isSelected) channel.color else Color.LightGray,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp
                        )
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp).testTag("chip_curve_${channel.name.lowercase()}")
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Interactive Curves Canvas
        Box(
            modifier = Modifier
                .size(260.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF14161C))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(selectedChannel, currentPoints) {
                        detectTapGestures { offset ->
                            val normX = (offset.x / size.width).coerceIn(0f, 1f)
                            val normY = (1f - (offset.y / size.height)).coerceIn(0f, 1f)
                            val valX = normX * 255f
                            val valY = normY * 255f

                            // Check if tapped near an existing point
                            val hitIndex = currentPoints.indexOfFirst { pt ->
                                val ptPxX = (pt.x / 255f) * size.width
                                val ptPxY = (1f - (pt.y / 255f)) * size.height
                                hypot(offset.x - ptPxX, offset.y - ptPxY) <= 36f
                            }

                            if (hitIndex != -1) {
                                selectedPointIndex = hitIndex
                            } else {
                                // Add a new control point
                                val mutable = currentPoints.toMutableList()
                                mutable.add(PointF(valX, valY))
                                val sorted = mutable.sortedBy { it.x }
                                selectedPointIndex = sorted.indexOfFirst { it.x == valX && it.y == valY }
                                updatePoints(sorted)
                                onAdjustmentsCommit()
                            }
                        }
                    }
                    .pointerInput(selectedChannel, currentPoints) {
                        var draggingIndex: Int? = null
                        detectDragGestures(
                            onDragStart = { offset ->
                                draggingIndex = currentPoints.indexOfFirst { pt ->
                                    val ptPxX = (pt.x / 255f) * size.width
                                    val ptPxY = (1f - (pt.y / 255f)) * size.height
                                    hypot(offset.x - ptPxX, offset.y - ptPxY) <= 44f
                                }
                                if (draggingIndex != -1) {
                                    selectedPointIndex = draggingIndex
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val idx = draggingIndex ?: return@detectDragGestures
                                if (idx !in currentPoints.indices) return@detectDragGestures

                                val normX = (change.position.x / size.width).coerceIn(0f, 1f)
                                val normY = (1f - (change.position.y / size.height)).coerceIn(0f, 1f)
                                val valX = normX * 255f
                                val valY = normY * 255f

                                val mutable = currentPoints.toMutableList()
                                // For end points, constrain X to 0 or 255
                                val finalX = if (idx == 0) 0f else if (idx == currentPoints.size - 1) 255f else valX
                                mutable[idx] = PointF(finalX, valY)
                                updatePoints(mutable)
                            },
                            onDragEnd = {
                                draggingIndex = null
                                onAdjustmentsCommit()
                            },
                            onDragCancel = {
                                draggingIndex = null
                            }
                        )
                    }
            ) {
                val canvasW = size.width
                val canvasH = size.height

                // Draw Grid (25%, 50%, 75%)
                val gridColor = Color.White.copy(alpha = 0.08f)
                val gridStroke = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                for (i in 1..3) {
                    val x = canvasW * (i / 4f)
                    val y = canvasH * (i / 4f)
                    drawLine(gridColor, Offset(x, 0f), Offset(x, canvasH), strokeWidth = 1f, pathEffect = gridStroke.pathEffect)
                    drawLine(gridColor, Offset(0f, y), Offset(canvasW, y), strokeWidth = 1f, pathEffect = gridStroke.pathEffect)
                }

                // Draw 45-degree diagonal reference line
                drawLine(
                    color = Color.White.copy(alpha = 0.20f),
                    start = Offset(0f, canvasH),
                    end = Offset(canvasW, 0f),
                    strokeWidth = 1.5.dp.toPx()
                )

                // Build and draw spline curve using ImageProcessor LUT
                val lut = ImageProcessor.buildCurveLut(currentPoints)
                val curvePath = Path()
                curvePath.moveTo(0f, canvasH - (lut[0] / 255f) * canvasH)
                for (xVal in 1..255) {
                    val px = (xVal / 255f) * canvasW
                    val py = canvasH - (lut[xVal] / 255f) * canvasH
                    curvePath.lineTo(px, py)
                }

                drawPath(
                    path = curvePath,
                    color = selectedChannel.color,
                    style = Stroke(width = 2.5.dp.toPx())
                )

                // Draw Control Points
                currentPoints.forEachIndexed { index, pt ->
                    val px = (pt.x / 255f) * canvasW
                    val py = (1f - (pt.y / 255f)) * canvasH
                    val isSelected = index == selectedPointIndex

                    // Outer halo
                    if (isSelected) {
                        drawCircle(
                            color = selectedChannel.color.copy(alpha = 0.35f),
                            radius = 12.dp.toPx(),
                            center = Offset(px, py)
                        )
                    }
                    // Solid point
                    drawCircle(
                        color = selectedChannel.color,
                        radius = 6.dp.toPx(),
                        center = Offset(px, py)
                    )
                    // Inner dot
                    drawCircle(
                        color = Color.Black,
                        radius = 2.5.dp.toPx(),
                        center = Offset(px, py)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Info & Point Action Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val pt = selectedPointIndex?.let { currentPoints.getOrNull(it) }
            if (pt != null) {
                Text(
                    text = "In: ${pt.x.toInt()}  |  Out: ${pt.y.toInt()}",
                    color = selectedChannel.color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            } else {
                Text(
                    text = stringResource(R.string.curves_preset_linear),
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedPointIndex != null && selectedPointIndex != 0 && selectedPointIndex != currentPoints.size - 1) {
                    IconButton(
                        onClick = {
                            val idx = selectedPointIndex ?: return@IconButton
                            val mutable = currentPoints.toMutableList()
                            if (idx in 1 until mutable.size - 1) {
                                mutable.removeAt(idx)
                                selectedPointIndex = null
                                updatePoints(mutable)
                                onAdjustmentsCommit()
                            }
                        },
                        modifier = Modifier.size(36.dp).testTag("btn_delete_curve_point")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Point", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
                OutlinedButton(
                    onClick = {
                        updatePoints(listOf(PointF(0f, 0f), PointF(255f, 255f)))
                        selectedPointIndex = null
                        onAdjustmentsCommit()
                    },
                    modifier = Modifier.heightIn(min = 36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.curves_reset_channel), fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Preset Quick Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CurvePresetButton(stringResource(R.string.curves_preset_s_curve), Modifier.weight(1f)) {
                updatePoints(listOf(PointF(0f, 0f), PointF(64f, 46f), PointF(192f, 209f), PointF(255f, 255f)))
                onAdjustmentsCommit()
            }
            CurvePresetButton(stringResource(R.string.curves_preset_lift_shadows), Modifier.weight(1f)) {
                updatePoints(listOf(PointF(0f, 38f), PointF(128f, 148f), PointF(255f, 255f)))
                onAdjustmentsCommit()
            }
            CurvePresetButton(stringResource(R.string.curves_preset_deep_blacks), Modifier.weight(1f)) {
                updatePoints(listOf(PointF(20f, 0f), PointF(128f, 107f), PointF(255f, 255f)))
                onAdjustmentsCommit()
            }
        }
    }
}

@Composable
private fun CurvePresetButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 36.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = StudioSurfaceVariant)
    ) {
        Text(label, fontSize = 10.sp, maxLines = 1)
    }
}
