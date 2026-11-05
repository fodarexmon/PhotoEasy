package com.example.ui.components

import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import kotlin.math.roundToInt

private data class AspectRatioItem(val label: String, val ratio: Float?)

@Composable
fun CropPerspectivePanel(
    isPerspectiveActive: Boolean,
    originalAspectRatio: Float,
    selectedAspectRatio: Float?,
    cropRect: RectF?,
    perspectiveCorners: List<PointF> = emptyList(),
    projectWidth: Int,
    projectHeight: Int,
    onSelectAspectRatio: (Float?) -> Unit,
    onTogglePerspective: (Boolean) -> Unit,
    onApplyCrop: () -> Unit,
    onResetCrop: () -> Unit,
    onApplyPerspective: () -> Unit,
    onResetPerspective: () -> Unit,
    onFitPerspectiveToCanvas: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val ratios = remember(originalAspectRatio) {
        listOf(
            AspectRatioItem("Free", null),
            AspectRatioItem("Original", originalAspectRatio),
            AspectRatioItem("1:1", 1f),
            AspectRatioItem("4:3", 4f / 3f),
            AspectRatioItem("3:4", 3f / 4f),
            AspectRatioItem("16:9", 16f / 9f),
            AspectRatioItem("9:16", 9f / 16f),
            AspectRatioItem("4:5", 4f / 5f)
        )
    }
    val width = (cropRect?.width() ?: projectWidth.toFloat()).roundToInt().coerceAtLeast(1)
    val height = (cropRect?.height() ?: projectHeight.toFloat()).roundToInt().coerceAtLeast(1)

    val perspectiveSize = remember(perspectiveCorners, selectedAspectRatio, projectWidth, projectHeight) {
        if (perspectiveCorners.size >= 4) {
            val topW = kotlin.math.hypot((perspectiveCorners[1].x - perspectiveCorners[0].x).toDouble(), (perspectiveCorners[1].y - perspectiveCorners[0].y).toDouble()).toFloat()
            val botW = kotlin.math.hypot((perspectiveCorners[2].x - perspectiveCorners[3].x).toDouble(), (perspectiveCorners[2].y - perspectiveCorners[3].y).toDouble()).toFloat()
            val leftH = kotlin.math.hypot((perspectiveCorners[3].x - perspectiveCorners[0].x).toDouble(), (perspectiveCorners[3].y - perspectiveCorners[0].y).toDouble()).toFloat()
            val rightH = kotlin.math.hypot((perspectiveCorners[2].x - perspectiveCorners[1].x).toDouble(), (perspectiveCorners[2].y - perspectiveCorners[1].y).toDouble()).toFloat()
            var pw = kotlin.math.round(maxOf(topW, botW)).toInt().coerceIn(50, 8192)
            var ph = kotlin.math.round(maxOf(leftH, rightH)).toInt().coerceIn(50, 8192)
            if (selectedAspectRatio != null && selectedAspectRatio > 0f) {
                if (selectedAspectRatio >= pw.toFloat() / ph) {
                    ph = (pw / selectedAspectRatio).toInt().coerceIn(50, 8192)
                } else {
                    pw = (ph * selectedAspectRatio).toInt().coerceIn(50, 8192)
                }
            }
            pw to ph
        } else {
            projectWidth to projectHeight
        }
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).background(StudioSurface).padding(vertical = 12.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !isPerspectiveActive,
                onClick = { onTogglePerspective(false) },
                label = { Text(stringResource(R.string.tool_crop), fontSize = 12.sp) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
            FilterChip(
                selected = isPerspectiveActive,
                onClick = { onTogglePerspective(true) },
                label = { Text(stringResource(R.string.perspective_transform), fontSize = 12.sp) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }

        Spacer(Modifier.height(6.dp))
        if (!isPerspectiveActive) {
            Text(stringResource(R.string.crop_pro_hint), color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                stringResource(R.string.crop_current_size, width, height),
                color = IndigoPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ratios, key = { it.label }) { item ->
                    val isOriginal = item.label == "Original"
                    val selected = when {
                        item.label == "Free" -> selectedAspectRatio == null
                        isOriginal -> selectedAspectRatio != null && kotlin.math.abs(selectedAspectRatio - originalAspectRatio) < 0.0001f
                        else -> selectedAspectRatio != null && item.ratio != null && kotlin.math.abs(selectedAspectRatio - item.ratio) < 0.0001f
                    }
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectAspectRatio(item.ratio) },
                        label = {
                            Text(
                                when (item.label) {
                                    "Free" -> stringResource(R.string.crop_free)
                                    "Original" -> stringResource(R.string.crop_original)
                                    else -> item.label
                                },
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onResetCrop,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.transform_reset))
                }
                Button(
                    onClick = onApplyCrop,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_apply_crop), fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Text(stringResource(R.string.perspective_drag_hint), color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                stringResource(R.string.crop_current_size, perspectiveSize.first, perspectiveSize.second),
                color = IndigoPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ratios, key = { it.label }) { item ->
                    val isOriginal = item.label == "Original"
                    val selected = when {
                        item.label == "Free" -> selectedAspectRatio == null
                        isOriginal -> selectedAspectRatio != null && kotlin.math.abs(selectedAspectRatio - originalAspectRatio) < 0.0001f
                        else -> selectedAspectRatio != null && item.ratio != null && kotlin.math.abs(selectedAspectRatio - item.ratio) < 0.0001f
                    }
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectAspectRatio(item.ratio) },
                        label = {
                            Text(
                                when (item.label) {
                                    "Free" -> stringResource(R.string.crop_free)
                                    "Original" -> stringResource(R.string.crop_original)
                                    else -> item.label
                                },
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onFitPerspectiveToCanvas?.invoke() },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.FitScreen, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.perspective_fit_canvas), fontSize = 12.sp, maxLines = 1)
                }
                OutlinedButton(onClick = onResetPerspective, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.transform_reset), fontSize = 12.sp, maxLines = 1)
                }
                Button(
                    onClick = onApplyPerspective,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_apply_crop), fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}
