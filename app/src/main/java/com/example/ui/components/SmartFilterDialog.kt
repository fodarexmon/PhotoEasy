package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.example.R
import com.example.data.model.BlendModeType
import com.example.data.model.SmartFilter
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import kotlin.math.roundToInt

/** Name shown for a Pro effect. */
@Composable
fun proEffectLabel(effect: com.example.data.model.ProFilterType): String = stringResource(effect.displayNameResId)

/**
 * Edits a Smart Filter: its settings and its blending options (opacity, blend mode). Changes
 * show live on the canvas, which stays visible.
 */
@Composable
fun SmartFilterDialog(
    filter: SmartFilter,
    onChange: (SmartFilter) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    var f by remember(filter.id) { mutableStateOf(filter) }
    fun set(n: SmartFilter) { f = n; onChange(n) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 560.dp).heightIn(max = 520.dp).padding(8.dp)
                    .clip(RoundedCornerShape(16.dp)).background(StudioSurface)
                    .verticalScroll(rememberScrollState()).padding(14.dp)
            ) {
                Text(stringResource(R.string.smart_filter_edit, proEffectLabel(f.effect)), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                ProEffectControls(f.effect, f.params) { set(f.copy(params = it)) }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.smart_filter_blending), color = Color.LightGray, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.sel_opacity), color = Color.LightGray, fontSize = 12.sp)
                    Text("${(f.opacity * 100).roundToInt()}%", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Slider(value = f.opacity, onValueChange = { set(f.copy(opacity = it)) }, valueRange = 0f..1f, modifier = Modifier.testTag("smart_filter_opacity"))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        BlendModeType.NORMAL, BlendModeType.MULTIPLY, BlendModeType.SCREEN, BlendModeType.OVERLAY,
                        BlendModeType.SOFT_LIGHT, BlendModeType.DARKEN, BlendModeType.LIGHTEN, BlendModeType.LUMINOSITY
                    ).forEach { m ->
                        FilterChip(selected = f.blendMode == m, onClick = { set(f.copy(blendMode = m)) },
                            label = { Text(stringResource(m.resId), fontSize = 12.sp) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    Button(onClick = onDone, colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                        modifier = Modifier.testTag("smart_filter_done")) { Text(stringResource(R.string.action_apply)) }
                }
            }
        }
    }
}
