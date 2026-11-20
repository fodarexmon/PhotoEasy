package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.engine.RetouchBrush
import com.example.engine.RetouchTool
import com.example.engine.ToneRange
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import kotlin.math.roundToInt

private fun retouchIcon(t: RetouchTool): ImageVector = when (t) {
    RetouchTool.BLUR -> Icons.Default.BlurOn
    RetouchTool.SHARPEN -> Icons.Default.Details
    RetouchTool.SMUDGE -> Icons.Default.Fingerprint
    RetouchTool.DODGE -> Icons.Default.Brightness7
    RetouchTool.BURN -> Icons.Default.Brightness4
    RetouchTool.CLONE -> Icons.Default.ContentCopy
    RetouchTool.PATCH -> Icons.Default.Healing
}

private fun retouchLabel(t: RetouchTool): Int = when (t) {
    RetouchTool.BLUR -> R.string.retouch_blur
    RetouchTool.SHARPEN -> R.string.retouch_sharpen
    RetouchTool.SMUDGE -> R.string.retouch_smudge
    RetouchTool.DODGE -> R.string.retouch_dodge
    RetouchTool.BURN -> R.string.retouch_burn
    RetouchTool.CLONE -> R.string.retouch_clone
    RetouchTool.PATCH -> R.string.retouch_patch
}

/** Options of the retouch brushes (Photoshop's Blur, Sharpen, Smudge, Dodge, Burn, Clone Stamp, Patch). */
@Composable
fun RetouchPanel(
    tool: RetouchTool,
    sizeDp: Float,
    brush: RetouchBrush,
    hasCloneSource: Boolean,
    isPickingSource: Boolean,
    hasSelection: Boolean,
    onToolChange: (RetouchTool) -> Unit,
    onSizeChange: (Float) -> Unit,
    onBrushChange: (RetouchBrush) -> Unit,
    onPickSourceChange: (Boolean) -> Unit,
    onSelectArea: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(StudioSurface)
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RetouchTool.values().forEach { t ->
                val sel = t == tool
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) IndigoPrimary.copy(alpha = 0.35f) else StudioSurfaceVariant)
                        .clickable { onToolChange(t) }
                        .padding(vertical = 8.dp)
                        .testTag("retouch_${t.name.lowercase()}")
                ) {
                    Icon(retouchIcon(t), contentDescription = null, tint = if (sel) Color.White else Color.LightGray, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(retouchLabel(t)), color = if (sel) Color.White else Color.LightGray, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (tool == RetouchTool.PATCH) {
            Text(stringResource(R.string.retouch_patch_info), color = Color.LightGray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            if (!hasSelection) {
                Button(onClick = onSelectArea, colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)) {
                    Icon(Icons.Default.Gesture, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.retouch_select_area))
                }
            } else {
                Text(stringResource(R.string.retouch_patch_ready), color = AmberAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            RSlider(R.string.retouch_size, sizeDp, 4f..400f, "${sizeDp.roundToInt()} dp", onSizeChange)
            RSlider(R.string.retouch_hardness, brush.hardness * 100f, 0f..100f, "${(brush.hardness * 100).roundToInt()}%") { onBrushChange(brush.copy(hardness = it / 100f)) }
            val strengthLabel = when (tool) {
                RetouchTool.DODGE, RetouchTool.BURN -> R.string.retouch_exposure
                RetouchTool.CLONE -> R.string.retouch_opacity
                else -> R.string.retouch_strength
            }
            RSlider(strengthLabel, brush.strength * 100f, 1f..100f, "${(brush.strength * 100).roundToInt()}%") { onBrushChange(brush.copy(strength = it / 100f)) }

            if (tool == RetouchTool.DODGE || tool == RetouchTool.BURN) {
                Text(stringResource(R.string.retouch_range), color = Color.LightGray, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ToneRange.SHADOWS to R.string.adjk_tone_shadows, ToneRange.MIDTONES to R.string.adjk_tone_midtones, ToneRange.HIGHLIGHTS to R.string.adjk_tone_highlights)
                        .forEach { (r, label) ->
                            FilterChip(
                                selected = brush.range == r,
                                onClick = { onBrushChange(brush.copy(range = r)) },
                                label = { Text(stringResource(label), fontSize = 12.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.retouch_protect_tones), color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(checked = brush.protectTones, onCheckedChange = { onBrushChange(brush.copy(protectTones = it)) })
                }
            }

            if (tool == RetouchTool.CLONE) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = isPickingSource,
                        onClick = { onPickSourceChange(!isPickingSource) },
                        label = { Text(stringResource(R.string.retouch_set_source), fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.AddLocation, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("retouch_set_source")
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            when {
                                isPickingSource -> R.string.retouch_tap_source
                                hasCloneSource -> R.string.retouch_clone_ready
                                else -> R.string.retouch_set_source_first
                            }
                        ),
                        color = if (isPickingSource) AmberAccent else Color.LightGray,
                        fontSize = 11.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.retouch_hint), color = Color.Gray, fontSize = 11.sp)
    }
}

@Composable
private fun RSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), color = Color.LightGray, fontSize = 12.sp)
        Text(valueText, color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValue, valueRange = range)
}
