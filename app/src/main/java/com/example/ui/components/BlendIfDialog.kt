package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.example.R
import com.example.data.model.BlendIf
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Blend If (Photoshop's Blending Options): channel plus the "This layer" and "Underlying layer"
 * sliders. Changes are shown live ([onChange]); the dialog does not dim the canvas.
 */
@Composable
fun BlendIfDialog(
    initial: BlendIf,
    onChange: (BlendIf) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit
) {
    var b by remember { mutableStateOf(initial) }
    fun set(n: BlendIf) { b = n; onChange(n) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Keep the canvas visible behind the panel.
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(8.dp)
                    .clip(RoundedCornerShape(16.dp)).background(StudioSurface).padding(14.dp)
            ) {
                Text(stringResource(R.string.blend_if_title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(R.string.blend_if_gray, R.string.channel_red, R.string.channel_green, R.string.channel_blue).forEachIndexed { i, label ->
                        FilterChip(selected = b.channel == i, onClick = { set(b.copy(channel = i)) },
                            label = { Text(stringResource(label), fontSize = 12.sp) }, modifier = Modifier.testTag("blend_if_channel_$i"))
                    }
                }
                val tint = when (b.channel) { 1 -> Color.Red; 2 -> Color.Green; 3 -> Color(0xFF3D7BFF); else -> Color.White }
                Spacer(Modifier.height(8.dp))
                BlendIfSlider(
                    label = stringResource(R.string.blend_if_this_layer), tint = tint,
                    values = floatArrayOf(b.thisLow0, b.thisLow1, b.thisHigh0, b.thisHigh1),
                    tag = "blend_if_this",
                    onChange = { v -> set(b.copy(thisLow0 = v[0], thisLow1 = v[1], thisHigh0 = v[2], thisHigh1 = v[3])) }
                )
                Spacer(Modifier.height(8.dp))
                BlendIfSlider(
                    label = stringResource(R.string.blend_if_underlying), tint = tint,
                    values = floatArrayOf(b.underLow0, b.underLow1, b.underHigh0, b.underHigh1),
                    tag = "blend_if_under",
                    onChange = { v -> set(b.copy(underLow0 = v[0], underLow1 = v[1], underHigh0 = v[2], underHigh1 = v[3])) }
                )
                Text(stringResource(R.string.blend_if_hint), color = Color.Gray, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { set(BlendIf()) }, modifier = Modifier.testTag("blend_if_reset")) { Text(stringResource(R.string.action_reset)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    Button(onClick = onApply, colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                        modifier = Modifier.testTag("blend_if_apply")) { Text(stringResource(R.string.action_apply)) }
                }
            }
        }
    }
}

/**
 * One Blend If slider: values = (low0, low1, high0, high1) in 0..255. Drag a handle to move it;
 * long press a handle to split it into two halves (a soft fade) or join them again.
 */
@Composable
private fun BlendIfSlider(label: String, tint: Color, values: FloatArray, tag: String, onChange: (FloatArray) -> Unit) {
    val v by rememberUpdatedState(values)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var widthPx by remember { mutableFloatStateOf(1f) }
    // Which value(s) the current drag moves: 0 = low pair, 1 = low0, 2 = low1, 3 = high pair, 4 = high0, 5 = high1.
    var grab by remember { mutableIntStateOf(-1) }
    val pad = 14f
    fun toValue(x: Float): Float {
        val t = ((x - pad) / (widthPx - 2 * pad)).coerceIn(0f, 1f)
        return (if (rtl) 1f - t else t) * 255f
    }
    fun toX(value: Float): Float {
        val t = value / 255f
        return pad + (if (rtl) 1f - t else t) * (widthPx - 2 * pad)
    }
    fun nearest(x: Float): Int {
        val lowSplit = v[0] != v[1]; val highSplit = v[2] != v[3]
        val cands = buildList {
            if (lowSplit) { add(1 to v[0]); add(2 to v[1]) } else add(0 to v[0])
            if (highSplit) { add(4 to v[2]); add(5 to v[3]) } else add(3 to v[2])
        }
        return cands.minBy { abs(toX(it.second) - x) }.first
    }
    fun moved(which: Int, value: Float): FloatArray {
        val n = v.copyOf()
        val x = value.roundToInt().toFloat().coerceIn(0f, 255f)
        when (which) {
            0 -> { val w = (n[1] - n[0]); n[0] = x.coerceAtMost(n[2] - w); n[1] = n[0] + w }
            1 -> n[0] = x.coerceAtMost(n[1])
            2 -> n[1] = x.coerceIn(n[0], n[2])
            3 -> { val w = (n[3] - n[2]); n[2] = x.coerceIn(n[1], 255f - w); n[3] = n[2] + w }
            4 -> n[2] = x.coerceIn(n[1], n[3])
            5 -> n[3] = x.coerceAtLeast(n[2])
        }
        return n
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.LightGray, fontSize = 12.sp)
            fun pair(a: Float, b: Float) = if (a == b) "${a.roundToInt()}" else "${a.roundToInt()}/${b.roundToInt()}"
            Text("${pair(values[0], values[1])}   ${pair(values[2], values[3])}", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Canvas(
            Modifier.fillMaxWidth().height(44.dp).testTag(tag)
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { pos ->
                        // Split a joined handle (8 levels apart) or join a split one.
                        val n = v.copyOf()
                        when (nearest(pos.x)) {
                            0 -> { n[1] = (n[0] + 8f).coerceAtMost(n[2]); if (n[1] == n[0]) n[0] = (n[1] - 8f).coerceAtLeast(0f) }
                            1, 2 -> { val m = ((n[0] + n[1]) / 2f).roundToInt().toFloat(); n[0] = m; n[1] = m }
                            3 -> { n[2] = (n[3] - 8f).coerceAtLeast(n[1]); if (n[2] == n[3]) n[3] = (n[2] + 8f).coerceAtMost(255f) }
                            4, 5 -> { val m = ((n[2] + n[3]) / 2f).roundToInt().toFloat(); n[2] = m; n[3] = m }
                        }
                        onChange(n)
                    })
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos -> grab = nearest(pos.x) },
                        onDragEnd = { grab = -1 },
                        onDragCancel = { grab = -1 },
                        onDrag = { change, _ ->
                            change.consume()
                            if (grab >= 0) onChange(moved(grab, toValue(change.position.x)))
                        }
                    )
                }
        ) {
            widthPx = size.width
            val barTop = 4.dp.toPx(); val barH = 14.dp.toPx()
            val left = pad; val right = size.width - pad
            drawRect(
                Brush.horizontalGradient(if (rtl) listOf(tint, Color.Black) else listOf(Color.Black, tint), startX = left, endX = right),
                topLeft = Offset(left, barTop), size = Size(right - left, barH)
            )
            drawRect(Color.White.copy(alpha = 0.25f), topLeft = Offset(left, barTop), size = Size(right - left, barH), style = Stroke(1f))
            val tip = barTop + barH + 2.dp.toPx(); val hh = 12.dp.toPx(); val hw = 8.dp.toPx()
            fun handle(value: Float, fill: Color, half: Int) {
                // half: 0 = whole triangle, -1 = left half, 1 = right half.
                val x = toX(value)
                val p = Path().apply {
                    moveTo(x, tip)
                    when (half) {
                        -1 -> { lineTo(x - hw, tip + hh); lineTo(x, tip + hh) }
                        1 -> { lineTo(x, tip + hh); lineTo(x + hw, tip + hh) }
                        else -> { lineTo(x - hw, tip + hh); lineTo(x + hw, tip + hh) }
                    }
                    close()
                }
                drawPath(p, fill)
                drawPath(p, Color.Gray, style = Stroke(1.5f))
            }
            val lowFill = Color(0xFF111111); val highFill = Color.White
            // In right-to-left the bar runs the other way: the "outer" half sits on the right.
            val outer = if (rtl) 1 else -1
            if (values[0] == values[1]) handle(values[0], lowFill, 0) else { handle(values[0], lowFill, outer); handle(values[1], lowFill, -outer) }
            if (values[2] == values[3]) handle(values[2], highFill, 0) else { handle(values[2], highFill, outer); handle(values[3], highFill, -outer) }
        }
    }
}
