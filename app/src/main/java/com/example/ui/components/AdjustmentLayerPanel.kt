package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.AdjustmentKind
import com.example.data.model.AdjustmentLayerParams
import com.example.data.model.ColorAdjustments
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import kotlin.math.roundToInt

fun adjustmentKindIcon(kind: AdjustmentKind): ImageVector = when (kind) {
    AdjustmentKind.GENERAL -> Icons.Default.Tune
    AdjustmentKind.BRIGHTNESS_CONTRAST -> Icons.Default.Brightness6
    AdjustmentKind.LEVELS -> Icons.Default.BarChart
    AdjustmentKind.CURVES -> Icons.Default.ShowChart
    AdjustmentKind.EXPOSURE -> Icons.Default.Exposure
    AdjustmentKind.VIBRANCE -> Icons.Default.Colorize
    AdjustmentKind.HUE_SATURATION -> Icons.Default.Palette
    AdjustmentKind.COLOR_BALANCE -> Icons.Default.Tune
    AdjustmentKind.BLACK_WHITE -> Icons.Default.FilterBAndW
    AdjustmentKind.PHOTO_FILTER -> Icons.Default.FilterVintage
    AdjustmentKind.GRADIENT_MAP -> Icons.Default.Gradient
    AdjustmentKind.INVERT -> Icons.Default.InvertColors
    AdjustmentKind.POSTERIZE -> Icons.Default.GridOn
    AdjustmentKind.THRESHOLD -> Icons.Default.Tonality
}

/** Photoshop's "New Adjustment Layer" menu as a grid of types. */
@Composable
fun AdjustmentKindPickerDialog(onPick: (AdjustmentKind) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.adjk_picker_title)) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 420.dp)
            ) {
                items(AdjustmentKind.addable, key = { it.name }) { kind ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(StudioSurfaceVariant)
                            .clickable { onPick(kind) }
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                            .heightIn(min = 72.dp)
                            .testTag("adjk_${kind.name.lowercase()}")
                    ) {
                        Icon(adjustmentKindIcon(kind), contentDescription = null, tint = IndigoPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(kind.labelRes), fontSize = 11.sp, color = Color.White, textAlign = TextAlign.Center, lineHeight = 13.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        containerColor = StudioSurface
    )
}

/**
 * Settings of an adjustment layer kind that has its own controls (Levels, Exposure, Color Balance,
 * Black & White, Photo Filter, Gradient Map, Posterize, Threshold, Invert, and the simple
 * Brightness / Contrast and Vibrance kinds). Live changes go through [onChange]; [onCommit] ends one
 * undo step (slider released, switch toggled, swatch picked).
 */
@Composable
fun AdjustmentLayerPanel(
    kind: AdjustmentKind,
    adjustments: ColorAdjustments,
    params: AdjustmentLayerParams,
    onChange: (ColorAdjustments, AdjustmentLayerParams) -> Unit,
    onCommit: () -> Unit,
    onReset: () -> Unit,
    onCompareChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    fun setP(p: AdjustmentLayerParams) = onChange(adjustments, p)
    fun setA(a: ColorAdjustments) = onChange(a, params)
    fun commitP(p: AdjustmentLayerParams) { onChange(adjustments, p); onCommit() }

    Column(
        modifier
            .fillMaxWidth()
            .background(StudioSurface)
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(adjustmentKindIcon(kind), contentDescription = null, tint = IndigoPrimary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(kind.labelRes), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            var comparing by remember { mutableStateOf(false) }
            IconButton(onClick = { comparing = !comparing; onCompareChange(comparing) }) {
                Icon(if (comparing) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(R.string.adjk_toggle_preview), tint = Color.White)
            }
            TextButton(onClick = onReset, modifier = Modifier.testTag("adjk_reset")) { Text(stringResource(R.string.adjk_reset)) }
        }
        Spacer(Modifier.height(6.dp))

        when (kind) {
            AdjustmentKind.BRIGHTNESS_CONTRAST -> {
                AdjSlider(R.string.adj_brightness, adjustments.brightness, -100f..100f, onCommit) { setA(adjustments.copy(brightness = it)) }
                AdjSlider(R.string.adj_contrast, adjustments.contrast, -100f..100f, onCommit) { setA(adjustments.copy(contrast = it)) }
            }
            AdjustmentKind.VIBRANCE -> {
                AdjSlider(R.string.adj_vibrance, adjustments.vibrance, -100f..100f, onCommit) { setA(adjustments.copy(vibrance = it)) }
                AdjSlider(R.string.adj_saturation, adjustments.saturation, -100f..100f, onCommit) { setA(adjustments.copy(saturation = it)) }
            }
            AdjustmentKind.LEVELS -> {
                // Channel: RGB together (0) or red / green / blue alone (1..3), as in Photoshop.
                var channel by remember { mutableIntStateOf(0) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(R.string.levels_channel_rgb, R.string.channel_red, R.string.channel_green, R.string.channel_blue).forEachIndexed { i, label ->
                        val edited = when (i) { 1 -> !params.levelsRed.isIdentity(); 2 -> !params.levelsGreen.isIdentity(); 3 -> !params.levelsBlue.isIdentity(); else -> false }
                        FilterChip(
                            selected = channel == i, onClick = { channel = i },
                            label = { Text(stringResource(label) + if (edited) " •" else "", fontSize = 12.sp) },
                            modifier = Modifier.testTag("levels_channel_$i")
                        )
                    }
                }
                val cur = when (channel) {
                    1 -> params.levelsRed; 2 -> params.levelsGreen; 3 -> params.levelsBlue
                    else -> com.example.data.model.LevelsChannel(params.levelsInBlack, params.levelsInWhite, params.levelsGamma, params.levelsOutBlack, params.levelsOutWhite)
                }
                fun setL(l: com.example.data.model.LevelsChannel) = setP(when (channel) {
                    1 -> params.copy(levelsRed = l); 2 -> params.copy(levelsGreen = l); 3 -> params.copy(levelsBlue = l)
                    else -> params.copy(levelsInBlack = l.inBlack, levelsInWhite = l.inWhite, levelsGamma = l.gamma, levelsOutBlack = l.outBlack, levelsOutWhite = l.outWhite)
                })
                Text(stringResource(R.string.adjk_levels_input), color = Color.LightGray, fontSize = 12.sp)
                AdjSlider(R.string.adjk_levels_shadows, cur.inBlack, 0f..253f, onCommit) { v -> setL(cur.copy(inBlack = v.coerceAtMost(cur.inWhite - 2f))) }
                AdjSlider(R.string.adjk_levels_gamma, cur.gamma, 0.1f..9.99f, onCommit, decimals = 2, logScale = true) { setL(cur.copy(gamma = it)) }
                AdjSlider(R.string.adjk_levels_highlights, cur.inWhite, 2f..255f, onCommit) { v -> setL(cur.copy(inWhite = v.coerceAtLeast(cur.inBlack + 2f))) }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.adjk_levels_output), color = Color.LightGray, fontSize = 12.sp)
                AdjSlider(R.string.adjk_levels_out_black, cur.outBlack, 0f..255f, onCommit) { setL(cur.copy(outBlack = it)) }
                AdjSlider(R.string.adjk_levels_out_white, cur.outWhite, 0f..255f, onCommit) { setL(cur.copy(outWhite = it)) }
            }
            AdjustmentKind.EXPOSURE -> {
                AdjSlider(R.string.adjk_exposure_ev, params.exposureEv, -5f..5f, onCommit, decimals = 2) { setP(params.copy(exposureEv = it)) }
                AdjSlider(R.string.adjk_exposure_offset, params.exposureOffset, -0.5f..0.5f, onCommit, decimals = 3) { setP(params.copy(exposureOffset = it)) }
                AdjSlider(R.string.adjk_exposure_gamma, params.exposureGamma, 0.1f..9.99f, onCommit, decimals = 2, logScale = true) { setP(params.copy(exposureGamma = it)) }
            }
            AdjustmentKind.COLOR_BALANCE -> {
                var tone by remember { mutableIntStateOf(1) }
                SegmentRow(listOf(R.string.adjk_tone_shadows, R.string.adjk_tone_midtones, R.string.adjk_tone_highlights), tone) { tone = it }
                val values = when (tone) { 0 -> params.balanceShadows; 2 -> params.balanceHighlights; else -> params.balanceMidtones }
                fun withValue(i: Int, v: Float): AdjustmentLayerParams {
                    val n = values.copyOf().also { it[i] = v }
                    return when (tone) {
                        0 -> params.copy(balanceShadows = n)
                        2 -> params.copy(balanceHighlights = n)
                        else -> params.copy(balanceMidtones = n)
                    }
                }
                ColorPairSlider(R.string.adjk_cyan, R.string.adjk_red, Color(0xFF00BCD4), Color(0xFFF44336), values[0], onCommit) { setP(withValue(0, it)) }
                ColorPairSlider(R.string.adjk_magenta, R.string.adjk_green, Color(0xFFE91E63), Color(0xFF4CAF50), values[1], onCommit) { setP(withValue(1, it)) }
                ColorPairSlider(R.string.adjk_yellow, R.string.adjk_blue, Color(0xFFFFEB3B), Color(0xFF2196F3), values[2], onCommit) { setP(withValue(2, it)) }
                SwitchLine(R.string.adjk_preserve_luminosity, params.balancePreserveLuminosity) { commitP(params.copy(balancePreserveLuminosity = it)) }
            }
            AdjustmentKind.BLACK_WHITE -> {
                val labels = listOf(R.string.adjk_reds, R.string.adjk_yellows, R.string.adjk_greens, R.string.adjk_cyans, R.string.adjk_blues, R.string.adjk_magentas)
                val colors = listOf(0xFFF44336, 0xFFFFEB3B, 0xFF4CAF50, 0xFF00BCD4, 0xFF2196F3, 0xFFE91E63)
                labels.forEachIndexed { i, label ->
                    AdjSlider(label, params.bwWeights[i], -200f..300f, onCommit, unit = "%", accent = Color(colors[i])) { v ->
                        setP(params.copy(bwWeights = params.bwWeights.copyOf().also { it[i] = v }))
                    }
                }
                TextButton(onClick = { commitP(params.copy(bwWeights = AdjustmentLayerParams().bwWeights)) }) {
                    Text(stringResource(R.string.adjk_bw_default))
                }
                SwitchLine(R.string.adjk_tint, params.bwTint) { commitP(params.copy(bwTint = it)) }
                if (params.bwTint) {
                    AdjSlider(R.string.adjk_hue, params.bwTintHue, 0f..360f, onCommit, unit = "°") { setP(params.copy(bwTintHue = it)) }
                    AdjSlider(R.string.adj_saturation, params.bwTintSaturation, 0f..100f, onCommit, unit = "%") { setP(params.copy(bwTintSaturation = it)) }
                }
            }
            AdjustmentKind.PHOTO_FILTER -> {
                Text(stringResource(R.string.adjk_filter_color), color = Color.LightGray, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                SwatchRow(PHOTO_FILTERS.map { it.first }, params.photoFilterColor) { commitP(params.copy(photoFilterColor = it)) }
                AdjSlider(R.string.adjk_density, params.photoFilterDensity, 0f..100f, onCommit, unit = "%") { setP(params.copy(photoFilterDensity = it)) }
                SwitchLine(R.string.adjk_preserve_luminosity, params.photoFilterPreserveLuminosity) { commitP(params.copy(photoFilterPreserveLuminosity = it)) }
            }
            AdjustmentKind.GRADIENT_MAP -> {
                Text(stringResource(R.string.adjk_gradient_presets), color = Color.LightGray, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GRADIENT_PRESETS.forEach { (a, b) ->
                        val selected = params.gradientStart == a && params.gradientEnd == b
                        Box(
                            Modifier
                                .size(width = 64.dp, height = 36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Brush.horizontalGradient(listOf(Color(a), Color(b))))
                                .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Color.Gray, RoundedCornerShape(8.dp))
                                .clickable { commitP(params.copy(gradientStart = a, gradientEnd = b)) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.adjk_gradient_shadows), color = Color.LightGray, fontSize = 12.sp)
                SwatchRow(SWATCHES, params.gradientStart) { commitP(params.copy(gradientStart = it)) }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.adjk_gradient_highlights), color = Color.LightGray, fontSize = 12.sp)
                SwatchRow(SWATCHES, params.gradientEnd) { commitP(params.copy(gradientEnd = it)) }
                SwitchLine(R.string.adjk_reverse, params.gradientReverse) { commitP(params.copy(gradientReverse = it)) }
            }
            AdjustmentKind.POSTERIZE ->
                AdjSlider(R.string.adjk_levels_count, params.posterizeLevels.toFloat(), 2f..32f, onCommit) { setP(params.copy(posterizeLevels = it.roundToInt())) }
            AdjustmentKind.THRESHOLD ->
                AdjSlider(R.string.adjk_threshold_level, params.thresholdLevel.toFloat(), 1f..255f, onCommit) { setP(params.copy(thresholdLevel = it.roundToInt())) }
            AdjustmentKind.INVERT ->
                Text(stringResource(R.string.adjk_invert_info), color = Color.LightGray, fontSize = 12.sp)
            else -> {}
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.adjk_mask_hint), color = Color.Gray, fontSize = 11.sp)
    }
}

/** Photoshop's photo filter colours (warming / cooling and plain colours). */
private val PHOTO_FILTERS = listOf(
    0xFFEC8A00.toInt() to "Warming 85", 0xFFFA9600.toInt() to "Warming LBA", 0xFFEBB113.toInt() to "Warming 81",
    0xFF006DFF.toInt() to "Cooling 80", 0xFF005DFF.toInt() to "Cooling LBB", 0xFF00B5FF.toInt() to "Cooling 82",
    0xFFEA1A1A.toInt() to "Red", 0xFFF38417.toInt() to "Orange", 0xFFF9E31C.toInt() to "Yellow",
    0xFF19C919.toInt() to "Green", 0xFF1DCBEA.toInt() to "Cyan", 0xFF1D35EA.toInt() to "Blue",
    0xFF9B1DEA.toInt() to "Violet", 0xFFE318E3.toInt() to "Magenta", 0xFFAC7A33.toInt() to "Sepia",
    0xFF008C00.toInt() to "Deep Emerald", 0xFF00C1B1.toInt() to "Underwater"
)

private val GRADIENT_PRESETS = listOf(
    0xFF000000.toInt() to 0xFFFFFFFF.toInt(),
    0xFF2E1065.toInt() to 0xFFFDBA74.toInt(),
    0xFF0B1F4B.toInt() to 0xFF5EEAD4.toInt(),
    0xFF3B1F0E.toInt() to 0xFFF5D7A1.toInt(),
    0xFF1E3A8A.toInt() to 0xFFF472B6.toInt(),
    0xFF14532D.toInt() to 0xFFFEF08A.toInt(),
    0xFF7F1D1D.toInt() to 0xFFFDE68A.toInt(),
    0xFF111827.toInt() to 0xFF38BDF8.toInt()
)

private val SWATCHES = listOf(
    0xFF000000, 0xFF1F2937, 0xFF6B7280, 0xFFFFFFFF, 0xFF7F1D1D, 0xFFEF4444, 0xFFF97316, 0xFFF59E0B,
    0xFFFDE68A, 0xFF14532D, 0xFF22C55E, 0xFF0B1F4B, 0xFF3B82F6, 0xFF38BDF8, 0xFF2E1065, 0xFF8B5CF6,
    0xFFEC4899, 0xFF3B1F0E, 0xFFF5D7A1
).map { it.toInt() }

@Composable
private fun SwatchRow(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { c ->
            val isSel = c == selected
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(if (isSel) 3.dp else 1.dp, if (isSel) Color.White else Color.Gray, CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
private fun SegmentRow(labels: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            FilterChip(
                selected = i == selected,
                onClick = { onSelect(i) },
                label = { Text(stringResource(label), fontSize = 12.sp) },
                modifier = Modifier.weight(1f).heightIn(min = 40.dp)
            )
        }
    }
}

@Composable
private fun SwitchLine(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Labelled slider. Double-tap the value to reset is left to Reset; [logScale] spreads gamma-like
 * ranges (0.1..9.99) so 1.0 sits in the middle.
 */
@Composable
private fun AdjSlider(
    label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onCommit: () -> Unit,
    decimals: Int = 0,
    unit: String = "",
    logScale: Boolean = false,
    accent: Color = IndigoPrimary,
    onValue: (Float) -> Unit
) {
    val text = if (decimals == 0) "${value.roundToInt()}$unit" else String.format(java.util.Locale.US, "%.${decimals}f$unit", value)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), color = Color.LightGray, fontSize = 12.sp)
        Text(text, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    if (logScale) {
        val lo = kotlin.math.ln(range.start); val hi = kotlin.math.ln(range.endInclusive)
        Slider(
            value = kotlin.math.ln(value.coerceIn(range.start, range.endInclusive)),
            onValueChange = { onValue(kotlin.math.exp(it).coerceIn(range.start, range.endInclusive)) },
            onValueChangeFinished = onCommit,
            valueRange = lo..hi,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
        )
    } else {
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValue,
            onValueChangeFinished = onCommit,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
        )
    }
}

/** Colour balance slider between two complementary colours, -100..100. */
@Composable
private fun ColorPairSlider(left: Int, right: Int, leftColor: Color, rightColor: Color, value: Float, onCommit: () -> Unit, onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(left), color = leftColor, fontSize = 12.sp)
        Text("${value.roundToInt()}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(right), color = rightColor, fontSize = 12.sp)
    }
    Slider(
        value = value.coerceIn(-100f, 100f),
        onValueChange = onValue,
        onValueChangeFinished = onCommit,
        valueRange = -100f..100f,
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = rightColor, inactiveTrackColor = leftColor)
    )
}


/** Hue/Saturation "Colorize" (Photoshop): one hue over the whole image, keeping its lightness. */
@Composable
fun ColorizeControls(params: AdjustmentLayerParams, onChange: (AdjustmentLayerParams) -> Unit, onCommit: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.adj_colorize), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Switch(checked = params.colorize, onCheckedChange = { onChange(params.copy(colorize = it)); onCommit() },
                modifier = Modifier.testTag("switch_colorize"))
        }
        if (params.colorize) {
            AdjSlider(R.string.adj_colorize_hue, params.colorizeHue, 0f..360f, onCommit, unit = "°",
                accent = Color(android.graphics.Color.HSVToColor(floatArrayOf(params.colorizeHue, 0.8f, 1f)))) { onChange(params.copy(colorizeHue = it)) }
            AdjSlider(R.string.adj_colorize_saturation, params.colorizeSaturation, 0f..100f, onCommit) { onChange(params.copy(colorizeSaturation = it)) }
            AdjSlider(R.string.adj_colorize_lightness, params.colorizeLightness, -100f..100f, onCommit) { onChange(params.copy(colorizeLightness = it)) }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
    }
}
