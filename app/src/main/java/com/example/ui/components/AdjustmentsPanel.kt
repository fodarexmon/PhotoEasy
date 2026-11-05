package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.ColorAdjustments
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant

import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import com.example.ui.theme.CyanAccent
import kotlin.math.abs

enum class AdjustmentsTab(val labelRes: Int) {
    BASIC(R.string.adj_tab_basic),
    CURVES(R.string.adj_tab_curves),
    HSL(R.string.adj_tab_hue_sat),
    REPLACE(R.string.adj_tab_replace_color)
}

@Composable
fun AdjustmentsPanel(
    adjustments: ColorAdjustments,
    onAdjustmentsChange: (ColorAdjustments) -> Unit,
    onAdjustmentsCommit: () -> Unit,
    onSmartAutoClick: () -> Unit,
    onResetAll: () -> Unit,
    onCompareChange: (Boolean) -> Unit = {},
    hasActiveSelection: Boolean = false,
    onClearSelection: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(AdjustmentsTab.BASIC) }
    val compareInteraction = remember { MutableInteractionSource() }
    val comparingOriginal by compareInteraction.collectIsPressedAsState()
    LaunchedEffect(comparingOriginal) { onCompareChange(comparingOriginal) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioSurface)
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        // Selection active indicator
        if (hasActiveSelection) {
            Surface(
                color = IndigoPrimary.copy(alpha = 0.22f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, IndigoPrimary.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SelectAll, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.status_selection_active_scoped),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (onClearSelection != null) {
                        TextButton(
                            onClick = onClearSelection,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(stringResource(R.string.select_clear), color = AmberAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        // Tab Selector
        ScrollableTabRow(
            selectedTabIndex = activeTab.ordinal,
            containerColor = StudioSurfaceVariant,
            contentColor = Color.White,
            edgePadding = 8.dp,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        ) {
            AdjustmentsTab.values().forEach { tab ->
                val selected = tab == activeTab
                Tab(
                    selected = selected,
                    onClick = { activeTab = tab },
                    text = {
                        Text(
                            stringResource(tab.labelRes),
                            color = if (selected) IndigoPrimary else Color.LightGray,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp
                        )
                    },
                    modifier = Modifier.testTag("tab_adj_${tab.name.lowercase()}")
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Category-level hold comparison states
        val basicCompareInteraction = remember { MutableInteractionSource() }
        val isComparingBasic by basicCompareInteraction.collectIsPressedAsState()
        var basicBeforeHold by remember { mutableStateOf<ColorAdjustments?>(null) }
        LaunchedEffect(isComparingBasic) {
            if (isComparingBasic) {
                basicBeforeHold = adjustments
                onAdjustmentsChange(
                    adjustments.copy(
                        brightness = 0f, contrast = 0f, saturation = 0f,
                        temperature = 0f, tint = 0f, exposure = 0f,
                        highlights = 0f, shadows = 0f, sharpness = 0f, vignette = 0f
                    )
                )
            } else {
                basicBeforeHold?.let { onAdjustmentsChange(it); onAdjustmentsCommit() }
                basicBeforeHold = null
            }
        }

        val curvesCompareInteraction = remember { MutableInteractionSource() }
        val isComparingCurves by curvesCompareInteraction.collectIsPressedAsState()
        var curvesBeforeHold by remember { mutableStateOf<ColorAdjustments?>(null) }
        LaunchedEffect(isComparingCurves) {
            if (isComparingCurves) {
                curvesBeforeHold = adjustments
                val def = listOf(android.graphics.PointF(0f, 0f), android.graphics.PointF(255f, 255f))
                onAdjustmentsChange(adjustments.copy(curvesRgb = def, curvesRed = def, curvesGreen = def, curvesBlue = def))
            } else {
                curvesBeforeHold?.let { onAdjustmentsChange(it); onAdjustmentsCommit() }
                curvesBeforeHold = null
            }
        }

        val hslCompareInteraction = remember { MutableInteractionSource() }
        val isComparingHsl by hslCompareInteraction.collectIsPressedAsState()
        var hslBeforeHold by remember { mutableStateOf<ColorAdjustments?>(null) }
        LaunchedEffect(isComparingHsl) {
            if (isComparingHsl) {
                hslBeforeHold = adjustments
                onAdjustmentsChange(adjustments.copy(hueShift = 0f, hslSaturation = 0f, hslLightness = 0f))
            } else {
                hslBeforeHold?.let { onAdjustmentsChange(it); onAdjustmentsCommit() }
                hslBeforeHold = null
            }
        }

        val replaceCompareInteraction = remember { MutableInteractionSource() }
        val isComparingReplace by replaceCompareInteraction.collectIsPressedAsState()
        var replaceBeforeHold by remember { mutableStateOf<ColorAdjustments?>(null) }
        LaunchedEffect(isComparingReplace) {
            if (isComparingReplace) {
                replaceBeforeHold = adjustments
                onAdjustmentsChange(adjustments.copy(replaceColorEnabled = false))
            } else {
                replaceBeforeHold?.let { onAdjustmentsChange(it); onAdjustmentsCommit() }
                replaceBeforeHold = null
            }
        }

        when (activeTab) {
            AdjustmentsTab.BASIC -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onSmartAutoClick,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("btn_smart_auto"),
                        colors = ButtonDefaults.buttonColors(containerColor = AmberAccent, contentColor = Color.Black),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.adj_smart_auto), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = onResetAll, modifier = Modifier.heightIn(min = 44.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.adj_reset_all), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {},
                    interactionSource = basicCompareInteraction,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("btn_compare_basic"),
                    colors = if (isComparingBasic) ButtonDefaults.outlinedButtonColors(containerColor = AmberAccent.copy(alpha = 0.15f), contentColor = AmberAccent) else ButtonDefaults.outlinedButtonColors()
                ) {
                    Icon(Icons.Default.Compare, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isComparingBasic) stringResource(R.string.compare_before) else stringResource(R.string.compare_basic_adjustments),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(8.dp))
                AdjustmentSlider(stringResource(R.string.adj_brightness), adjustments.brightness, onValueChange = { onAdjustmentsChange(adjustments.copy(brightness = it)) }, onValueChangeFinished = onAdjustmentsCommit)
                AdjustmentSlider(stringResource(R.string.adj_contrast), adjustments.contrast, onValueChange = { onAdjustmentsChange(adjustments.copy(contrast = it)) }, onValueChangeFinished = onAdjustmentsCommit)
                AdjustmentSlider(stringResource(R.string.adj_saturation), adjustments.saturation, onValueChange = { onAdjustmentsChange(adjustments.copy(saturation = it)) }, onValueChangeFinished = onAdjustmentsCommit)
                AdjustmentSlider(stringResource(R.string.adj_temperature), adjustments.temperature, onValueChange = { onAdjustmentsChange(adjustments.copy(temperature = it)) }, onValueChangeFinished = onAdjustmentsCommit)
                AdjustmentSlider(stringResource(R.string.adj_tint), adjustments.tint, onValueChange = { onAdjustmentsChange(adjustments.copy(tint = it)) }, onValueChangeFinished = onAdjustmentsCommit)
                AdjustmentSlider(stringResource(R.string.adj_exposure), adjustments.exposure, onValueChange = { onAdjustmentsChange(adjustments.copy(exposure = it)) }, onValueChangeFinished = onAdjustmentsCommit)
                AdjustmentSlider(stringResource(R.string.adj_highlights), adjustments.highlights, onValueChange = { onAdjustmentsChange(adjustments.copy(highlights = it)) }, onValueChangeFinished = onAdjustmentsCommit)
                AdjustmentSlider(stringResource(R.string.adj_shadows), adjustments.shadows, onValueChange = { onAdjustmentsChange(adjustments.copy(shadows = it)) }, onValueChangeFinished = onAdjustmentsCommit)
                AdjustmentSlider(stringResource(R.string.adj_sharpness), adjustments.sharpness, 0f..100f, onValueChange = { onAdjustmentsChange(adjustments.copy(sharpness = it)) }, onValueChangeFinished = onAdjustmentsCommit)
                AdjustmentSlider(stringResource(R.string.adj_vignette), adjustments.vignette, 0f..100f, onValueChange = { onAdjustmentsChange(adjustments.copy(vignette = it)) }, onValueChangeFinished = onAdjustmentsCommit)
            }

            AdjustmentsTab.CURVES -> {
                OutlinedButton(
                    onClick = {},
                    interactionSource = curvesCompareInteraction,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(bottom = 6.dp).testTag("btn_compare_curves"),
                    colors = if (isComparingCurves) ButtonDefaults.outlinedButtonColors(containerColor = AmberAccent.copy(alpha = 0.15f), contentColor = AmberAccent) else ButtonDefaults.outlinedButtonColors()
                ) {
                    Icon(Icons.Default.Compare, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isComparingCurves) stringResource(R.string.compare_before) else stringResource(R.string.compare_curves),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                CurvesView(
                    adjustments = adjustments,
                    onAdjustmentsChange = onAdjustmentsChange,
                    onAdjustmentsCommit = onAdjustmentsCommit
                )
            }

            AdjustmentsTab.HSL -> {
                OutlinedButton(
                    onClick = {},
                    interactionSource = hslCompareInteraction,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(bottom = 6.dp).testTag("btn_compare_hsl"),
                    colors = if (isComparingHsl) ButtonDefaults.outlinedButtonColors(containerColor = AmberAccent.copy(alpha = 0.15f), contentColor = AmberAccent) else ButtonDefaults.outlinedButtonColors()
                ) {
                    Icon(Icons.Default.Compare, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isComparingHsl) stringResource(R.string.compare_before) else stringResource(R.string.compare_hsl),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                HslPanel(
                    adjustments = adjustments,
                    onAdjustmentsChange = onAdjustmentsChange,
                    onAdjustmentsCommit = onAdjustmentsCommit
                )
            }

            AdjustmentsTab.REPLACE -> {
                OutlinedButton(
                    onClick = {},
                    interactionSource = replaceCompareInteraction,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(bottom = 6.dp).testTag("btn_compare_replace"),
                    colors = if (isComparingReplace) ButtonDefaults.outlinedButtonColors(containerColor = AmberAccent.copy(alpha = 0.15f), contentColor = AmberAccent) else ButtonDefaults.outlinedButtonColors()
                ) {
                    Icon(Icons.Default.Compare, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isComparingReplace) stringResource(R.string.compare_before) else stringResource(R.string.compare_replace_color),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                ColorReplacePanel(
                    adjustments = adjustments,
                    onAdjustmentsChange = onAdjustmentsChange,
                    onAdjustmentsCommit = onAdjustmentsCommit
                )
            }
        }
    }
}

@Composable
private fun AdjustmentSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = -100f..100f,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    defaultValue: Float = 0f
) {
    var savedValueBeforeHold by remember { mutableFloatStateOf(value) }
    val sliderCompareInteraction = remember { MutableInteractionSource() }
    val isHoldingSliderCompare by sliderCompareInteraction.collectIsPressedAsState()

    LaunchedEffect(isHoldingSliderCompare) {
        if (isHoldingSliderCompare) {
            savedValueBeforeHold = value
            onValueChange(defaultValue)
        } else {
            if (abs(savedValueBeforeHold - defaultValue) > 0.01f) {
                onValueChange(savedValueBeforeHold)
                onValueChangeFinished()
            }
        }
    }

    val isModified = abs(value - defaultValue) > 0.01f

    Column(Modifier.padding(vertical = 3.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    color = if (isHoldingSliderCompare) AmberAccent else Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (isModified) FontWeight.SemiBold else FontWeight.Normal
                )
                if (isHoldingSliderCompare) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.compare_before),
                        color = AmberAccent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isModified || isHoldingSliderCompare) {
                    IconButton(
                        onClick = {
                            onValueChange(defaultValue)
                            onValueChangeFinished()
                        },
                        interactionSource = sliderCompareInteraction,
                        modifier = Modifier.size(28.dp).testTag("btn_compare_${label.lowercase().replace(" ", "_")}")
                    ) {
                        Icon(
                            imageVector = if (isHoldingSliderCompare) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(R.string.compare_this_adjustment),
                            tint = if (isHoldingSliderCompare) AmberAccent else IndigoPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = value.toInt().toString(),
                    color = if (isHoldingSliderCompare) AmberAccent else if (isModified) IndigoPrimary else Color.Gray,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange
        )
    }
}
