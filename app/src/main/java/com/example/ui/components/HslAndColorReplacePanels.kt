package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.ColorAdjustments
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurfaceVariant

enum class HslRangeItem(val id: String, val labelRes: Int, val swatch: Color, val center: Float) {
    ALL("ALL", R.string.color_range_all, Color.White, 0f),
    REDS("REDS", R.string.color_range_reds, Color(0xFFEF5350), 0f),
    ORANGES("ORANGES", R.string.color_range_oranges, Color(0xFFFF9800), 30f),
    YELLOWS("YELLOWS", R.string.color_range_yellows, Color(0xFFFFEE58), 60f),
    GREENS("GREENS", R.string.color_range_greens, Color(0xFF66BB6A), 120f),
    CYANS("CYANS", R.string.color_range_aquas, Color(0xFF26C6DA), 180f),
    BLUES("BLUES", R.string.color_range_blues, Color(0xFF42A5F5), 240f),
    PURPLES("PURPLES", R.string.color_range_purples, Color(0xFF7E57C2), 270f),
    MAGENTAS("MAGENTAS", R.string.color_range_magentas, Color(0xFFEC407A), 300f)
}

private fun hueColor(h: Float): Color = Color(android.graphics.Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, 0.85f, 0.95f)))

@Composable
fun HslPanel(
    adjustments: ColorAdjustments,
    onAdjustmentsChange: (ColorAdjustments) -> Unit,
    onAdjustmentsCommit: () -> Unit,
    modifier: Modifier = Modifier,
    /** Targeted adjustment on the image: "HUE", "SAT", "LIGHT" or null (off). */
    targetProperty: String? = null,
    onTargetPropertyChange: (String?) -> Unit = {}
) {
    val currentRange = HslRangeItem.values().find { it.id == adjustments.hslColorRange } ?: HslRangeItem.ALL
    // Each range keeps its own values; switching ranges never moves an edit to another colour.
    val band = adjustments.hslBand(currentRange.id)
    val bands = adjustments.effectiveHslBands()
    val isAll = currentRange == HslRangeItem.ALL
    val hueRange = if (isAll) -180f..180f else -60f..60f

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // Targeted adjustment: drag on the image.
        Text(stringResource(R.string.hsl_target_tool), color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("HUE" to R.string.hue_shift, "SAT" to R.string.hue_saturation, "LIGHT" to R.string.hue_lightness).forEach { (id, res) ->
                FilterChip(
                    selected = targetProperty == id,
                    onClick = { onTargetPropertyChange(if (targetProperty == id) null else id) },
                    label = { Text(stringResource(res), fontSize = 11.sp) },
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp).testTag("chip_hsl_target_${id.lowercase()}")
                )
            }
        }
        if (targetProperty != null) {
            Text(stringResource(R.string.hsl_target_hint), color = Color(0xFFFBBF24), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
        }

        Spacer(Modifier.height(8.dp))

        // Range grid: two rows of large targets.
        val items = HslRangeItem.values().toList()
        for (row in listOf(items.take(5), items.drop(5))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { range ->
                    val isSelected = range.id == currentRange.id
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) StudioSurfaceVariant else Color.Transparent)
                            .clickable { onAdjustmentsChange(adjustments.migrateHsl().copy(hslColorRange = range.id)) }
                            .padding(vertical = 6.dp)
                            .testTag("hsl_range_${range.id.lowercase()}")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(if (range == HslRangeItem.ALL) Color.Transparent else range.swatch)
                                .border(if (isSelected) 2.dp else 1.dp, if (isSelected) IndigoPrimary else Color.White.copy(alpha = 0.3f), CircleShape)
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            stringResource(range.labelRes),
                            fontSize = 10.sp,
                            color = if (isSelected) IndigoPrimary else Color.LightGray,
                            maxLines = 1,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                        Box(
                            Modifier.size(4.dp).clip(CircleShape)
                                .background(if (bands[range.id]?.isIdentity() == false) IndigoPrimary else Color.Transparent)
                        )
                    }
                }
                if (row.size < 5) Spacer(Modifier.weight((5 - row.size).toFloat()))
            }
        }

        Spacer(Modifier.height(10.dp))

        // Hue: a strip shows which colours the slider leads to.
        val stripColors = if (isAll) (0..12).map { hueColor(it * 30f) } else (0..6).map { hueColor(currentRange.center + hueRange.start + it * (hueRange.endInclusive - hueRange.start) / 6f) }
        HslSlider(
            label = stringResource(R.string.hue_shift),
            value = band.hue,
            valueRange = hueRange,
            unit = "°",
            onValueChange = { onAdjustmentsChange(adjustments.withHslBand(currentRange.id, band.copy(hue = it))) },
            onValueChangeFinished = onAdjustmentsCommit,
            onReset = { onAdjustmentsChange(adjustments.withHslBand(currentRange.id, band.copy(hue = 0f))); onAdjustmentsCommit() },
            strip = stripColors
        )
        val baseColor = if (isAll) Color(0xFF9E9E9E) else hueColor(currentRange.center + band.hue)
        HslSlider(
            label = stringResource(R.string.hue_saturation),
            value = band.saturation,
            valueRange = -100f..100f,
            unit = "%",
            onValueChange = { onAdjustmentsChange(adjustments.withHslBand(currentRange.id, band.copy(saturation = it))) },
            onValueChangeFinished = onAdjustmentsCommit,
            onReset = { onAdjustmentsChange(adjustments.withHslBand(currentRange.id, band.copy(saturation = 0f))); onAdjustmentsCommit() },
            strip = listOf(Color(0xFF808080), baseColor)
        )
        HslSlider(
            label = stringResource(R.string.hue_lightness),
            value = band.lightness,
            valueRange = -100f..100f,
            unit = "%",
            onValueChange = { onAdjustmentsChange(adjustments.withHslBand(currentRange.id, band.copy(lightness = it))) },
            onValueChangeFinished = onAdjustmentsCommit,
            onReset = { onAdjustmentsChange(adjustments.withHslBand(currentRange.id, band.copy(lightness = 0f))); onAdjustmentsCommit() },
            strip = listOf(Color.Black, baseColor, Color.White)
        )
        if (!isAll) {
            HslSlider(
                label = stringResource(R.string.hsl_range_width),
                value = band.width * 100f,
                valueRange = 50f..200f,
                unit = "%",
                onValueChange = { onAdjustmentsChange(adjustments.withHslBand(currentRange.id, band.copy(width = it / 100f))) },
                onValueChangeFinished = onAdjustmentsCommit,
                onReset = { onAdjustmentsChange(adjustments.withHslBand(currentRange.id, band.copy(width = 1f))); onAdjustmentsCommit() }
            )
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        Spacer(Modifier.height(6.dp))

        // Colorize: one hue for the whole image.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.hsl_colorize), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Switch(
                checked = adjustments.colorizeEnabled,
                onCheckedChange = { onAdjustmentsChange(adjustments.copy(colorizeEnabled = it)); onAdjustmentsCommit() },
                modifier = Modifier.testTag("switch_colorize")
            )
        }
        if (adjustments.colorizeEnabled) {
            HslSlider(
                label = stringResource(R.string.hue_shift),
                value = adjustments.colorizeHue,
                valueRange = 0f..360f,
                unit = "°",
                onValueChange = { onAdjustmentsChange(adjustments.copy(colorizeHue = it)) },
                onValueChangeFinished = onAdjustmentsCommit,
                strip = (0..12).map { hueColor(it * 30f) }
            )
            HslSlider(
                label = stringResource(R.string.hue_saturation),
                value = adjustments.colorizeSaturation,
                valueRange = 0f..100f,
                unit = "%",
                onValueChange = { onAdjustmentsChange(adjustments.copy(colorizeSaturation = it)) },
                onValueChangeFinished = onAdjustmentsCommit,
                strip = listOf(Color(0xFF808080), hueColor(adjustments.colorizeHue))
            )
            HslSlider(
                label = stringResource(R.string.hue_lightness),
                value = adjustments.colorizeLightness,
                valueRange = -100f..100f,
                unit = "%",
                onValueChange = { onAdjustmentsChange(adjustments.copy(colorizeLightness = it)) },
                onValueChangeFinished = onAdjustmentsCommit
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                onAdjustmentsChange(
                    adjustments.copy(
                        hueShift = 0f,
                        hslSaturation = 0f,
                        hslLightness = 0f,
                        hslBands = emptyMap(),
                        hslColorRange = "ALL",
                        colorizeEnabled = false
                    )
                )
                onAdjustmentsCommit()
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.adj_reset_all), fontSize = 12.sp)
        }
    }
}

@Composable
fun ColorReplacePanel(
    adjustments: ColorAdjustments,
    onAdjustmentsChange: (ColorAdjustments) -> Unit,
    onAdjustmentsCommit: () -> Unit,
    modifier: Modifier = Modifier,
    /** Active eyedropper: (replacement index, true = source / false = target), or null. */
    pickTarget: Pair<Int, Boolean>? = null,
    onPickRequest: (Pair<Int, Boolean>?) -> Unit = {},
    hasActiveSelection: Boolean = false,
    onAdjustSelectionOnly: (() -> Unit)? = null
) {
    val presetColors = listOf(
        0xFFE53935.toInt(), 0xFFFF7043.toInt(), 0xFFFFB300.toInt(), 0xFFFDD835.toInt(),
        0xFF7CB342.toInt(), 0xFF2E7D32.toInt(), 0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(),
        0xFF3949AB.toInt(), 0xFF8E24AA.toInt(), 0xFFD81B60.toInt(), 0xFF6D4C41.toInt(),
        0xFF212121.toInt(), 0xFF9E9E9E.toInt(), 0xFFFFFFFF.toInt()
    )
    val list = adjustments.effectiveReplacements()
    var selected by remember { mutableStateOf(0) }
    if (selected > list.lastIndex) selected = (list.size - 1).coerceAtLeast(0)
    val latest by rememberUpdatedState(adjustments)

    fun update(i: Int, transform: (com.example.data.model.ColorReplacement) -> com.example.data.model.ColorReplacement) {
        val l = latest.effectiveReplacements().toMutableList()
        if (i !in l.indices) return
        l[i] = transform(l[i])
        onAdjustmentsChange(latest.withReplacements(l))
    }

    // Hold to see which pixels are affected (white = replaced).
    val maskInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val showingMask by maskInteraction.collectIsPressedAsState()
    LaunchedEffect(showingMask) {
        if (showingMask != latest.replacePreviewMask) onAdjustmentsChange(latest.copy(replacePreviewMask = showingMask))
    }

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // Replacement tabs (up to three per layer).
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            list.forEachIndexed { i, c ->
                FilterChip(
                    selected = i == selected,
                    onClick = { selected = i; onPickRequest(null) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(c.sourceColor)))
                            Text(" → ", fontSize = 11.sp, color = if (c.enabled) Color.White else Color.Gray)
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(c.targetColor)))
                        }
                    },
                    modifier = Modifier.heightIn(min = 40.dp).testTag("chip_replacement_$i")
                )
            }
            if (list.size < 3) {
                TextButton(
                    onClick = {
                        onAdjustmentsChange(latest.withReplacements(latest.effectiveReplacements() + com.example.data.model.ColorReplacement()))
                        onAdjustmentsCommit()
                        selected = list.size
                    },
                    modifier = Modifier.heightIn(min = 40.dp).testTag("btn_add_replacement")
                ) { Text(stringResource(R.string.replace_add), fontSize = 12.sp) }
            }
        }

        if (list.isEmpty()) {
            Text(
                stringResource(R.string.replace_empty_hint),
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(8.dp)
            )
            return@Column
        }
        val c = list[selected]

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.replace_enabled), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val l = latest.effectiveReplacements().toMutableList()
                    if (selected in l.indices) l.removeAt(selected)
                    onAdjustmentsChange(latest.withReplacements(l))
                    onAdjustmentsCommit()
                    onPickRequest(null)
                }, modifier = Modifier.heightIn(min = 40.dp)) {
                    Text(stringResource(R.string.replace_remove), color = Color(0xFFEF4444), fontSize = 12.sp)
                }
                Switch(
                    checked = c.enabled,
                    onCheckedChange = { on -> update(selected) { it.copy(enabled = on) }; onAdjustmentsCommit() },
                    modifier = Modifier.testTag("switch_replace_color")
                )
            }
        }

        // Before -> after preview.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(width = 64.dp, height = 32.dp).clip(RoundedCornerShape(8.dp)).background(Color(c.sourceColor)))
            Text("  →  ", color = Color.White, fontSize = 16.sp)
            Box(Modifier.size(width = 64.dp, height = 32.dp).clip(RoundedCornerShape(8.dp)).background(Color(c.targetColor)))
        }

        ColorRow(
            label = stringResource(R.string.replace_source_color),
            current = c.sourceColor,
            presets = presetColors,
            picking = pickTarget == (selected to true),
            onPick = { onPickRequest(if (pickTarget == (selected to true)) null else selected to true) },
            onSelect = { col -> update(selected) { it.copy(sourceColor = col) }; onAdjustmentsCommit() }
        )
        ColorRow(
            label = stringResource(R.string.replace_target_color),
            current = c.targetColor,
            presets = presetColors,
            picking = pickTarget == (selected to false),
            onPick = { onPickRequest(if (pickTarget == (selected to false)) null else selected to false) },
            onSelect = { col -> update(selected) { it.copy(targetColor = col) }; onAdjustmentsCommit() }
        )
        if (pickTarget != null) {
            Text(
                stringResource(R.string.replace_pick_hint),
                color = Color(0xFFFBBF24),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        // Fine-tune the target hue (keeps its colourfulness and brightness).
        val targetHsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c.targetColor, it) }
        HslSlider(
            label = stringResource(R.string.replace_target_hue),
            value = targetHsv[0],
            valueRange = 0f..360f,
            unit = "°",
            onValueChange = { hue ->
                update(selected) { it.copy(targetColor = android.graphics.Color.HSVToColor(floatArrayOf(hue, targetHsv[1].coerceAtLeast(0.15f), targetHsv[2].coerceAtLeast(0.15f)))) }
            },
            onValueChangeFinished = onAdjustmentsCommit
        )
        HslSlider(
            label = stringResource(R.string.replace_tolerance),
            value = c.tolerance,
            valueRange = 1f..100f,
            unit = "",
            onValueChange = { v -> update(selected) { it.copy(tolerance = v) } },
            onValueChangeFinished = onAdjustmentsCommit
        )
        HslSlider(
            label = stringResource(R.string.replace_softness),
            value = c.softness,
            valueRange = 0f..100f,
            unit = "",
            onValueChange = { v -> update(selected) { it.copy(softness = v) } },
            onValueChangeFinished = onAdjustmentsCommit
        )
        HslSlider(
            label = stringResource(R.string.hue_saturation),
            value = c.saturation,
            valueRange = -100f..100f,
            unit = "%",
            onValueChange = { v -> update(selected) { it.copy(saturation = v) } },
            onValueChangeFinished = onAdjustmentsCommit
        )
        HslSlider(
            label = stringResource(R.string.hue_lightness),
            value = c.lightness,
            valueRange = -100f..100f,
            unit = "%",
            onValueChange = { v -> update(selected) { it.copy(lightness = v) } },
            onValueChangeFinished = onAdjustmentsCommit
        )

        OutlinedButton(
            onClick = {},
            interactionSource = maskInteraction,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(top = 4.dp).testTag("btn_replace_show_mask"),
            colors = if (showingMask) ButtonDefaults.outlinedButtonColors(containerColor = Color.White.copy(alpha = 0.15f)) else ButtonDefaults.outlinedButtonColors()
        ) {
            Text(stringResource(R.string.replace_show_area), fontSize = 12.sp)
        }

        if (hasActiveSelection && onAdjustSelectionOnly != null) {
            TextButton(onClick = onAdjustSelectionOnly, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
                Text(stringResource(R.string.replace_selection_only_hint), fontSize = 11.sp, color = IndigoPrimary)
            }
        }
    }
}

@Composable
private fun ColorRow(
    label: String,
    current: Int,
    presets: List<Int>,
    picking: Boolean,
    onPick: () -> Unit,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        FilterChip(
            selected = picking,
            onClick = onPick,
            label = { Text(stringResource(R.string.replace_pick_from_image), fontSize = 11.sp) },
            leadingIcon = { Icon(Icons.Default.Colorize, contentDescription = null, modifier = Modifier.size(14.dp)) },
            modifier = Modifier.heightIn(min = 40.dp)
        )
    }
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
    ) {
        items(presets.size) { idx ->
            val col = presets[idx]
            val isSel = (current and 0xFFFFFF) == (col and 0xFFFFFF)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(col))
                    .border(if (isSel) 3.dp else 1.dp, if (isSel) Color.White else Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .clickable { onSelect(col) },
                contentAlignment = Alignment.Center
            ) {
                if (isSel) Icon(Icons.Default.Check, contentDescription = null, tint = if ((col and 0xFFFFFF) == 0xFFFFFF) Color.Black else Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun HslSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    unit: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    /** Double tap on the label row resets the slider. */
    onReset: (() -> Unit)? = null,
    /** Colour strip shown under the label (what the slider leads to). */
    strip: List<Color>? = null
) {
    val latestReset by rememberUpdatedState(onReset)
    Column(Modifier.padding(vertical = 3.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { latestReset?.invoke() }) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White, fontSize = 12.sp)
            Text(
                "${kotlin.math.round(value).toInt()}$unit",
                color = IndigoPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (strip != null && strip.size >= 2) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(strip))
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = IndigoPrimary,
                activeTrackColor = IndigoPrimary
            )
        )
    }
}
