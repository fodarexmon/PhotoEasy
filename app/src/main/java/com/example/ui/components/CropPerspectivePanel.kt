package com.example.ui.components

import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.engine.ImageProcessor
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private data class AspectRatioItem(val label: String, val ratio: Float?)
private data class SizePreset(val labelRes: Int, val w: Int, val h: Int)

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
    modifier: Modifier = Modifier,
    cropStraighten: Float = 0f,
    onStraightenChange: (Float) -> Unit = {},
    cropGrid: String = "THIRDS",
    onGridChange: (String) -> Unit = {},
    expandCanvas: Boolean = false,
    onToggleExpandCanvas: () -> Unit = {},
    targetWidth: Int = 0,
    targetHeight: Int = 0,
    onTargetSize: (Int, Int) -> Unit = { _, _ -> },
    onSwapOrientation: () -> Unit = {},
    onRotateCanvas: (clockwise: Boolean) -> Unit = {},
    onFlipCanvas: (horizontal: Boolean) -> Unit = {},
    perspectiveVertical: Float = 0f,
    perspectiveHorizontal: Float = 0f,
    onKeystoneChange: (Float, Float) -> Unit = { _, _ -> },
    onAutoDetectDocument: () -> Unit = {}
) {
    val ratios = remember(originalAspectRatio) {
        listOf(
            AspectRatioItem("Free", null),
            AspectRatioItem("Original", originalAspectRatio),
            AspectRatioItem("1:1", 1f),
            AspectRatioItem("4:3", 4f / 3f),
            AspectRatioItem("3:2", 3f / 2f),
            AspectRatioItem("16:9", 16f / 9f),
            AspectRatioItem("4:5", 4f / 5f),
            AspectRatioItem("5:7", 5f / 7f),
            AspectRatioItem("2:1", 2f)
        )
    }
    val sizePresets = remember {
        listOf(
            SizePreset(R.string.crop_size_post_square, 1080, 1080),
            SizePreset(R.string.crop_size_post_portrait, 1080, 1350),
            SizePreset(R.string.crop_size_story, 1080, 1920),
            SizePreset(R.string.crop_size_full_hd, 1920, 1080),
            SizePreset(R.string.crop_size_4k, 3840, 2160)
        )
    }
    var showCustomSize by remember { mutableStateOf(false) }
    val width = (cropRect?.width() ?: projectWidth.toFloat()).roundToInt().coerceAtLeast(1)
    val height = (cropRect?.height() ?: projectHeight.toFloat()).roundToInt().coerceAtLeast(1)

    val perspectiveSize = remember(perspectiveCorners, selectedAspectRatio, projectWidth, projectHeight) {
        if (perspectiveCorners.size >= 4) ImageProcessor.perspectiveOutputSize(perspectiveCorners, projectWidth, projectHeight, selectedAspectRatio)
        else projectWidth to projectHeight
    }

    @Composable
    fun ratioRow() {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ratios, key = { it.label }) { item ->
                val selected = when {
                    item.ratio == null -> selectedAspectRatio == null
                    else -> selectedAspectRatio != null && (abs(selectedAspectRatio - item.ratio) < 0.0001f || abs(selectedAspectRatio - 1f / item.ratio) < 0.0001f && item.label != "Original")
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
            item {
                // Portrait <-> landscape for the chosen ratio.
                IconButton(onClick = onSwapOrientation, modifier = Modifier.size(48.dp).testTag("btn_crop_swap_orientation")) {
                    Icon(Icons.Default.ScreenRotation, contentDescription = stringResource(R.string.crop_swap_orientation), tint = Color.White)
                }
            }
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
                if (targetWidth > 0 && targetHeight > 0) stringResource(R.string.crop_size_to, width, height, targetWidth, targetHeight)
                else stringResource(R.string.crop_current_size, width, height),
                color = IndigoPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // Straighten: rotate under the frame; the frame always stays inside the image.
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CropRotate, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.crop_straighten), color = Color.White, fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(String.format(Locale.US, "%.1f°", cropStraighten), color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        if (abs(cropStraighten) >= 0.05f) {
                            TextButton(onClick = { onStraightenChange(0f) }, modifier = Modifier.heightIn(min = 40.dp)) {
                                Text(stringResource(R.string.action_reset), fontSize = 11.sp)
                            }
                        }
                    }
                }
                Slider(
                    value = cropStraighten,
                    onValueChange = { v -> onStraightenChange((v * 10f).roundToInt() / 10f) },
                    valueRange = -45f..45f,
                    enabled = !expandCanvas,
                    modifier = Modifier.testTag("slider_crop_straighten")
                )
            }

            // Whole-image rotate / flip.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CanvasActionButton(Icons.Default.RotateLeft, stringResource(R.string.crop_rotate_left)) { onRotateCanvas(false) }
                CanvasActionButton(Icons.Default.RotateRight, stringResource(R.string.crop_rotate_right)) { onRotateCanvas(true) }
                CanvasActionButton(Icons.Default.Flip, stringResource(R.string.crop_flip_h)) { onFlipCanvas(true) }
                CanvasActionButton(Icons.Default.Flip, stringResource(R.string.crop_flip_v), rotateIcon = true) { onFlipCanvas(false) }
            }

            Spacer(Modifier.height(4.dp))
            ratioRow()

            // Exact output sizes.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                sizePresets.forEach { p ->
                    FilterChip(
                        selected = targetWidth == p.w && targetHeight == p.h,
                        onClick = {
                            if (targetWidth == p.w && targetHeight == p.h) onTargetSize(0, 0) else onTargetSize(p.w, p.h)
                        },
                        label = { Text("${stringResource(p.labelRes)} ${p.w}×${p.h}", fontSize = 11.sp) },
                        modifier = Modifier.heightIn(min = 44.dp)
                    )
                }
                FilterChip(
                    selected = targetWidth > 0 && sizePresets.none { it.w == targetWidth && it.h == targetHeight },
                    onClick = { showCustomSize = true },
                    label = { Text(stringResource(R.string.crop_size_custom), fontSize = 11.sp) },
                    modifier = Modifier.heightIn(min = 44.dp)
                )
            }

            // Grid and canvas expansion.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.crop_grid), color = Color.LightGray, fontSize = 11.sp)
                listOf(
                    "THIRDS" to R.string.crop_grid_thirds,
                    "GOLDEN" to R.string.crop_grid_golden,
                    "DIAGONAL" to R.string.crop_grid_diagonal,
                    "NONE" to R.string.crop_grid_none
                ).forEach { (id, res) ->
                    FilterChip(
                        selected = cropGrid == id,
                        onClick = { onGridChange(id) },
                        label = { Text(stringResource(res), fontSize = 11.sp) },
                        modifier = Modifier.heightIn(min = 40.dp)
                    )
                }
                FilterChip(
                    selected = expandCanvas,
                    onClick = onToggleExpandCanvas,
                    label = { Text(stringResource(R.string.crop_expand_canvas), fontSize = 11.sp) },
                    modifier = Modifier.heightIn(min = 40.dp)
                )
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
            if (selectedAspectRatio == null) {
                Text(stringResource(R.string.perspective_true_ratio_hint), color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }

            OutlinedButton(
                onClick = onAutoDetectDocument,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp).testTag("btn_detect_document")
            ) {
                Icon(Icons.Default.DocumentScanner, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.perspective_auto_detect), fontSize = 12.sp)
            }

            // Keystone: straighten converging verticals / horizontals (buildings).
            Column(Modifier.padding(horizontal = 16.dp)) {
                KeystoneSlider(stringResource(R.string.perspective_vertical), perspectiveVertical) { onKeystoneChange(it, perspectiveHorizontal) }
                KeystoneSlider(stringResource(R.string.perspective_horizontal), perspectiveHorizontal) { onKeystoneChange(perspectiveVertical, it) }
            }

            ratioRow()
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

    if (showCustomSize) {
        var wText by remember { mutableStateOf(if (targetWidth > 0) targetWidth.toString() else width.toString()) }
        var hText by remember { mutableStateOf(if (targetHeight > 0) targetHeight.toString() else height.toString()) }
        val w = wText.toIntOrNull()
        val h = hText.toIntOrNull()
        val valid = w != null && h != null && w in 16..8192 && h in 16..8192
        AlertDialog(
            onDismissRequest = { showCustomSize = false },
            title = { Text(stringResource(R.string.crop_size_custom)) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = wText,
                        onValueChange = { wText = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.crop_width)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Text("×", color = Color.White)
                    OutlinedTextField(
                        value = hText,
                        onValueChange = { hText = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.crop_height)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { if (valid) { onTargetSize(w!!, h!!); showCustomSize = false } }, enabled = valid) {
                    Text(stringResource(R.string.action_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomSize = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun CanvasActionButton(icon: ImageVector, label: String, rotateIcon: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = if (rotateIcon) Modifier.rotate(90f) else Modifier)
        }
        Text(label, color = Color.LightGray, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun KeystoneSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 12.sp)
        Text(value.roundToInt().toString(), color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    Slider(value = value, onValueChange = { onChange(it.roundToInt().toFloat()) }, valueRange = -100f..100f)
}
