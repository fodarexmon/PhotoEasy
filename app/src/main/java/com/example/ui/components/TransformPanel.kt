package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun TransformPanel(
    rotation: Float,
    scaleX: Float = 1f,
    scaleY: Float = 1f,
    positionX: Float = 0f,
    positionY: Float = 0f,
    onRotationChange: (Float) -> Unit,
    onRotationCommit: () -> Unit,
    onScaleChange: (scaleX: Float, scaleY: Float) -> Unit = { _, _ -> },
    onPositionChange: (posX: Float, posY: Float) -> Unit = { _, _ -> },
    onTransformCommit: () -> Unit = {},
    onRotate90Left: () -> Unit,
    onRotate90Right: () -> Unit,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit,
    onCenterCanvas: () -> Unit = {},
    onFitCanvas: () -> Unit = {},
    onFillCanvas: () -> Unit = {},
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    /** Unscaled box size of the layer in document pixels (for width / height in px). */
    boxWidth: Float = 0f,
    boxHeight: Float = 0f,
    documentWidth: Int = 1000,
    documentHeight: Int = 1000,
    mode: String = "FREE",
    onModeChange: (String) -> Unit = {},
    pivot: Int = 4,
    onPivotChange: (Int) -> Unit = {},
    fromCenter: Boolean = false,
    onToggleFromCenter: () -> Unit = {},
    lockAspect: Boolean = true,
    onToggleLockAspect: () -> Unit = {},
    snap: Boolean = true,
    onToggleSnap: () -> Unit = {},
    skewX: Float = 0f,
    skewY: Float = 0f,
    onSkewChange: (Float, Float) -> Unit = { _, _ -> },
    hasDistort: Boolean = false,
    onResetDistort: () -> Unit = {},
    /** Exact values: width px, height px, rotation degrees, X, Y. */
    onApplyNumeric: (Float, Float, Float, Float, Float) -> Unit = { _, _, _, _, _ -> }
) {
    val normalized = ((rotation % 360f) + 360f) % 360f
    val signed = if (normalized > 180f) normalized - 360f else normalized
    var showNumeric by remember { mutableStateOf(false) }

    val absScaleX = abs(scaleX)
    val absScaleY = abs(scaleY)
    val signX = if (scaleX < 0) -1f else 1f
    val signY = if (scaleY < 0) -1f else 1f
    val halfW = (documentWidth.toFloat()).coerceAtLeast(100f)
    val halfH = (documentHeight.toFloat()).coerceAtLeast(100f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .background(StudioSurface)
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.transform_title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            TextButton(onClick = { showNumeric = true }, modifier = Modifier.heightIn(min = 44.dp).testTag("btn_transform_numeric")) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.transform_exact_values), fontSize = 12.sp)
            }
        }

        // Mode: free transform / skew / distort.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "FREE" to R.string.transform_mode_free,
                "SKEW" to R.string.transform_mode_skew,
                "DISTORT" to R.string.transform_mode_distort
            ).forEach { (id, res) ->
                FilterChip(
                    selected = mode == id,
                    onClick = { onModeChange(id) },
                    label = { Text(stringResource(res), fontSize = 12.sp) },
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("chip_transform_${id.lowercase()}")
                )
            }
        }
        Text(
            stringResource(
                when (mode) {
                    "SKEW" -> R.string.transform_hint_skew
                    "DISTORT" -> R.string.transform_hint_distort
                    else -> R.string.transform_hint_free
                }
            ),
            color = Color.Gray,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
        )

        // Options: lock proportions, scale from centre, snapping.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = lockAspect,
                onClick = onToggleLockAspect,
                label = { Text(stringResource(R.string.transform_proportional), fontSize = 11.sp) },
                leadingIcon = { Icon(if (lockAspect) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(14.dp)) },
                modifier = Modifier.heightIn(min = 44.dp)
            )
            FilterChip(
                selected = fromCenter,
                onClick = onToggleFromCenter,
                label = { Text(stringResource(R.string.transform_from_center), fontSize = 11.sp) },
                modifier = Modifier.heightIn(min = 44.dp)
            )
            FilterChip(
                selected = snap,
                onClick = onToggleSnap,
                label = { Text(stringResource(R.string.transform_snap), fontSize = 11.sp) },
                modifier = Modifier.heightIn(min = 44.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        when (mode) {
            "SKEW" -> {
                ValueSlider(stringResource(R.string.transform_skew_h), String.format(Locale.US, "%.1f°", skewX), skewX, -60f..60f,
                    { onSkewChange(it, skewY) }, onTransformCommit)
                ValueSlider(stringResource(R.string.transform_skew_v), String.format(Locale.US, "%.1f°", skewY), skewY, -60f..60f,
                    { onSkewChange(skewX, it) }, onTransformCommit)
            }
            "DISTORT" -> {
                OutlinedButton(onClick = onResetDistort, enabled = hasDistort, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.transform_reset_distort))
                }
            }
            else -> {
                // Reference point (rotation pivot).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.transform_pivot), color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    PivotGrid(pivot, onPivotChange)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Quick canvas alignments.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickButton(Icons.Default.CenterFocusStrong, stringResource(R.string.transform_center_canvas), Modifier.weight(1f), onCenterCanvas)
            QuickButton(Icons.Default.FitScreen, stringResource(R.string.transform_fit_canvas), Modifier.weight(1f), onFitCanvas)
            QuickButton(Icons.Default.Fullscreen, stringResource(R.string.transform_fill_canvas), Modifier.weight(1f), onFillCanvas)
        }

        Spacer(Modifier.height(10.dp))

        // Scale on a logarithmic slider (0.05x..20x) so small and large sizes are both precise.
        fun toLog(v: Float) = (ln(v.coerceIn(0.05f, 20f).toDouble()) / ln(2.0)).toFloat()
        fun fromLog(v: Float) = 2.0.pow(v.toDouble()).toFloat()
        val logRange = toLog(0.05f)..toLog(20f)
        if (lockAspect) {
            val avg = (absScaleX + absScaleY) / 2f
            ValueSlider(
                stringResource(R.string.transform_scale),
                String.format(Locale.US, "%.0f%%  ·  %d × %d", avg * 100f, (absScaleX * boxWidth).roundToInt(), (absScaleY * boxHeight).roundToInt()),
                toLog(avg), logRange,
                { v ->
                    val n = fromLog(v)
                    val ratio = if (avg > 0f) n / avg else 1f
                    onScaleChange(scaleX * ratio, scaleY * ratio)
                },
                onTransformCommit
            )
        } else {
            ValueSlider(stringResource(R.string.transform_scale_x), String.format(Locale.US, "%.0f%%  ·  %d px", absScaleX * 100f, (absScaleX * boxWidth).roundToInt()),
                toLog(absScaleX), logRange, { onScaleChange(fromLog(it) * signX, scaleY) }, onTransformCommit)
            ValueSlider(stringResource(R.string.transform_scale_y), String.format(Locale.US, "%.0f%%  ·  %d px", absScaleY * 100f, (absScaleY * boxHeight).roundToInt()),
                toLog(absScaleY), logRange, { onScaleChange(scaleX, fromLog(it) * signY) }, onTransformCommit)
        }

        ValueSlider(stringResource(R.string.transform_rotation), String.format(Locale.US, "%.1f°", signed), signed, -180f..180f,
            { onRotationChange(it) }, onRotationCommit)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransformAction(stringResource(R.string.rotate_left), Icons.Default.RotateLeft, Modifier.weight(1f), onRotate90Left)
            TransformAction(stringResource(R.string.rotate_right), Icons.Default.RotateRight, Modifier.weight(1f), onRotate90Right)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransformAction(stringResource(R.string.flip_h), Icons.Default.Flip, Modifier.weight(1f), onFlipHorizontal)
            TransformAction(stringResource(R.string.flip_v), Icons.Default.Flip, Modifier.weight(1f), onFlipVertical, rotateIcon = true)
        }

        Spacer(Modifier.height(12.dp))

        // Position over the whole image.
        ValueSlider(stringResource(R.string.transform_pos_x), "${positionX.roundToInt()} px", positionX.coerceIn(-halfW, halfW), -halfW..halfW,
            { onPositionChange(it, positionY) }, onTransformCommit)
        ValueSlider(stringResource(R.string.transform_pos_y), "${positionY.roundToInt()} px", positionY.coerceIn(-halfH, halfH), -halfH..halfH,
            { onPositionChange(positionX, it) }, onTransformCommit)

        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.transform_reset))
        }
    }

    if (showNumeric) {
        var wText by remember { mutableStateOf((absScaleX * boxWidth).roundToInt().toString()) }
        var hText by remember { mutableStateOf((absScaleY * boxHeight).roundToInt().toString()) }
        var aText by remember { mutableStateOf(String.format(Locale.US, "%.1f", signed)) }
        var xText by remember { mutableStateOf(positionX.roundToInt().toString()) }
        var yText by remember { mutableStateOf(positionY.roundToInt().toString()) }
        val w = wText.toFloatOrNull()
        val h = hText.toFloatOrNull()
        val a = aText.toFloatOrNull()
        val x = xText.toFloatOrNull()
        val y = yText.toFloatOrNull()
        val valid = w != null && h != null && a != null && x != null && y != null && w >= 1f && h >= 1f
        AlertDialog(
            onDismissRequest = { showNumeric = false },
            title = { Text(stringResource(R.string.transform_exact_values)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(stringResource(R.string.crop_width), wText, Modifier.weight(1f)) { wText = it }
                        NumberField(stringResource(R.string.crop_height), hText, Modifier.weight(1f)) { hText = it }
                    }
                    NumberField(stringResource(R.string.transform_rotation), aText, Modifier.fillMaxWidth(), allowDecimal = true) { aText = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("X", xText, Modifier.weight(1f)) { xText = it }
                        NumberField("Y", yText, Modifier.weight(1f)) { yText = it }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    if (valid) onApplyNumeric(w!!, h!!, a!!, x!!, y!!)
                    showNumeric = false
                }) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = { TextButton(onClick = { showNumeric = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, allowDecimal: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '-' || (allowDecimal && it == '.') }.take(9)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun PivotGrid(selected: Int, onSelect: (Int) -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).background(StudioSurfaceVariant).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (col in 0 until 3) {
                    val i = row * 3 + col
                    Box(
                        Modifier
                            .size(28.dp)
                            .clickable { onSelect(i) }
                            .testTag("pivot_$i"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .size(if (i == selected) 12.dp else 8.dp)
                                .clip(CircleShape)
                                .background(if (i == selected) IndigoPrimary else Color.Transparent)
                                .border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ValueSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.LightGray, fontSize = 12.sp)
        Text(valueLabel, color = IndigoPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        onValueChangeFinished = onFinished,
        valueRange = range
    )
}

@Composable
private fun QuickButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun TransformAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit, rotateIcon: Boolean = false) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp).then(if (rotateIcon) Modifier.rotate(90f) else Modifier))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}
