package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.engine.StrokePosition
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import kotlin.math.roundToInt

private val swatches = listOf(
    0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(),
    0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt()
)

/**
 * Edit > Fill / Edit > Stroke for the selection: colour, opacity and, for a stroke, its width
 * (document pixels) and position relative to the selection edge.
 */
@Composable
fun SelectionFillStrokeDialog(
    stroke: Boolean,
    initialColor: Int,
    onDismiss: () -> Unit,
    onFill: (color: Int, opacity: Float) -> Unit,
    onStroke: (color: Int, widthDoc: Float, position: StrokePosition, opacity: Float) -> Unit
) {
    var color by remember { mutableIntStateOf(initialColor or (0xFF shl 24)) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var width by remember { mutableFloatStateOf(6f) }
    var position by remember { mutableStateOf(StrokePosition.CENTER) }
    var custom by remember { mutableStateOf(false) }
    if (custom) {
        CustomColorPickerDialog(initialColorHex = color, onDismiss = { custom = false },
            onColorConfirmed = { color = it or (0xFF shl 24); custom = false })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = { Text(stringResource(if (stroke) R.string.sel_stroke_title else R.string.sel_fill_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sel_color), color = Color.LightGray, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    swatches.forEach { c ->
                        Box(
                            Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(Color(c))
                                .border(BorderStroke(if (c == color) 2.dp else 1.dp, if (c == color) IndigoPrimary else Color.Gray), RoundedCornerShape(6.dp))
                                .clickable { color = c }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Color(color)).border(1.dp, Color.Gray, RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { custom = true }) { Text(stringResource(R.string.sel_custom_color)) }
                }
                if (stroke) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.sel_stroke_width), color = Color.LightGray, fontSize = 12.sp)
                        Text("${width.roundToInt()} px", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(value = width, onValueChange = { width = it }, valueRange = 1f..100f, modifier = Modifier.testTag("sel_stroke_width"))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            StrokePosition.INSIDE to R.string.sel_stroke_inside,
                            StrokePosition.CENTER to R.string.sel_stroke_center,
                            StrokePosition.OUTSIDE to R.string.sel_stroke_outside
                        ).forEach { (p, label) ->
                            FilterChip(selected = position == p, onClick = { position = p }, label = { Text(stringResource(label), fontSize = 12.sp) })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.sel_opacity), color = Color.LightGray, fontSize = 12.sp)
                    Text("${(opacity * 100).roundToInt()}%", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0.05f..1f)
            }
        },
        confirmButton = {
            Button(
                onClick = { if (stroke) onStroke(color, width, position, opacity) else onFill(color, opacity) },
                colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                modifier = Modifier.testTag("sel_fill_stroke_ok")
            ) { Text(stringResource(if (stroke) R.string.sel_stroke else R.string.sel_fill)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
