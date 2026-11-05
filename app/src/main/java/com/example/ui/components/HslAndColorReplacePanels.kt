package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.ColorAdjustments
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurfaceVariant

enum class HslRangeItem(val id: String, val labelRes: Int, val swatch: Color) {
    ALL("ALL", R.string.color_range_all, Color.White),
    REDS("REDS", R.string.color_range_reds, Color(0xFFEF5350)),
    YELLOWS("YELLOWS", R.string.color_range_yellows, Color(0xFFFFEE58)),
    GREENS("GREENS", R.string.color_range_greens, Color(0xFF66BB6A)),
    CYANS("CYANS", R.string.color_range_cyans, Color(0xFF26C6DA)),
    BLUES("BLUES", R.string.color_range_blues, Color(0xFF42A5F5)),
    MAGENTAS("MAGENTAS", R.string.color_range_magentas, Color(0xFFAB47BC))
}

@Composable
fun HslPanel(
    adjustments: ColorAdjustments,
    onAdjustmentsChange: (ColorAdjustments) -> Unit,
    onAdjustmentsCommit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentRange = HslRangeItem.values().find { it.id == adjustments.hslColorRange } ?: HslRangeItem.ALL

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // Color Range Selector
        Text(
            text = stringResource(R.string.adj_tab_hue_sat),
            color = Color.LightGray,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HslRangeItem.values().forEach { range ->
                val isSelected = range.id == currentRange.id
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) StudioSurfaceVariant else Color.Transparent)
                        .clickable {
                            onAdjustmentsChange(adjustments.copy(hslColorRange = range.id))
                            onAdjustmentsCommit()
                        }
                        .padding(vertical = 6.dp)
                        .testTag("hsl_range_${range.id.lowercase()}")
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(range.swatch)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) IndigoPrimary else Color.White.copy(alpha = 0.2f),
                                shape = CircleShape
                            )
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(range.labelRes),
                        fontSize = 9.sp,
                        color = if (isSelected) IndigoPrimary else Color.Gray,
                        maxLines = 1,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Sliders
        HslSlider(
            label = stringResource(R.string.hue_shift),
            value = adjustments.hueShift,
            valueRange = -180f..180f,
            unit = "°",
            onValueChange = { onAdjustmentsChange(adjustments.copy(hueShift = it)) },
            onValueChangeFinished = onAdjustmentsCommit
        )

        HslSlider(
            label = stringResource(R.string.hue_saturation),
            value = adjustments.hslSaturation,
            valueRange = -100f..100f,
            unit = "%",
            onValueChange = { onAdjustmentsChange(adjustments.copy(hslSaturation = it)) },
            onValueChangeFinished = onAdjustmentsCommit
        )

        HslSlider(
            label = stringResource(R.string.hue_lightness),
            value = adjustments.hslLightness,
            valueRange = -100f..100f,
            unit = "%",
            onValueChange = { onAdjustmentsChange(adjustments.copy(hslLightness = it)) },
            onValueChangeFinished = onAdjustmentsCommit
        )

        Spacer(Modifier.height(8.dp))

        // Reset HSL
        OutlinedButton(
            onClick = {
                onAdjustmentsChange(
                    adjustments.copy(
                        hueShift = 0f,
                        hslSaturation = 0f,
                        hslLightness = 0f,
                        hslColorRange = "ALL"
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
    modifier: Modifier = Modifier
) {
    val presetColors = listOf(
        0xFFFF1744.toInt(), // Red
        0xFFFF9100.toInt(), // Orange
        0xFFFFEA00.toInt(), // Yellow
        0xFF00E676.toInt(), // Green
        0xFF00E5FF.toInt(), // Cyan
        0xFF2979FF.toInt(), // Blue
        0xFFD500F9.toInt(), // Magenta
        0xFF3E2723.toInt()  // Brown
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // Enable switch
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ColorLens, contentDescription = null, tint = IndigoPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.replace_enabled),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Switch(
                checked = adjustments.replaceColorEnabled,
                onCheckedChange = {
                    onAdjustmentsChange(adjustments.copy(replaceColorEnabled = it))
                    onAdjustmentsCommit()
                },
                modifier = Modifier.testTag("switch_replace_color")
            )
        }

        Spacer(Modifier.height(8.dp))

        // Source Color Picker Swatches
        Text(
            stringResource(R.string.replace_source_color),
            color = Color.LightGray,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presetColors.forEach { colInt ->
                val isSelected = adjustments.replaceSourceColorHex == colInt
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(colInt))
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) Color.White else Color.Black.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable {
                            onAdjustmentsChange(adjustments.copy(replaceSourceColorHex = colInt))
                            onAdjustmentsCommit()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Tolerance Slider
        HslSlider(
            label = stringResource(R.string.replace_tolerance),
            value = adjustments.replaceTolerance * 100f,
            valueRange = 5f..100f,
            unit = "%",
            onValueChange = { onAdjustmentsChange(adjustments.copy(replaceTolerance = it / 100f)) },
            onValueChangeFinished = onAdjustmentsCommit
        )

        // Target Hue Slider
        HslSlider(
            label = stringResource(R.string.replace_target_color) + " (" + stringResource(R.string.hue_shift) + ")",
            value = adjustments.replaceTargetHue,
            valueRange = -180f..180f,
            unit = "°",
            onValueChange = { onAdjustmentsChange(adjustments.copy(replaceTargetHue = it)) },
            onValueChangeFinished = onAdjustmentsCommit
        )

        // Target Saturation Slider
        HslSlider(
            label = stringResource(R.string.hue_saturation),
            value = adjustments.replaceTargetSaturation,
            valueRange = -100f..100f,
            unit = "%",
            onValueChange = { onAdjustmentsChange(adjustments.copy(replaceTargetSaturation = it)) },
            onValueChangeFinished = onAdjustmentsCommit
        )

        // Target Lightness Slider
        HslSlider(
            label = stringResource(R.string.hue_lightness),
            value = adjustments.replaceTargetLightness,
            valueRange = -100f..100f,
            unit = "%",
            onValueChange = { onAdjustmentsChange(adjustments.copy(replaceTargetLightness = it)) },
            onValueChangeFinished = onAdjustmentsCommit
        )
    }
}

@Composable
private fun HslSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    unit: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White, fontSize = 12.sp)
            Text(
                "${value.toInt()}$unit",
                color = IndigoPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
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
