package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.AvailableFonts
import com.example.data.model.TextAlignment
import com.example.data.model.TextLayerConfig
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant

private val RichPresetColors = listOf(
    0xFFFFFFFF.toInt(), // White
    0xFFD1D5DB.toInt(), // Light Gray
    0xFF6B7280.toInt(), // Medium Gray
    0xFF1F2937.toInt(), // Dark Gray
    0xFF000000.toInt(), // Black
    0xFFEF4444.toInt(), // Red
    0xFFDC2626.toInt(), // Crimson
    0xFFF97316.toInt(), // Orange
    0xFFF59E0B.toInt(), // Amber
    0xFFEAB308.toInt(), // Gold
    0xFFFACC15.toInt(), // Yellow
    0xFF84CC16.toInt(), // Lime
    0xFF10B981.toInt(), // Emerald Green
    0xFF059669.toInt(), // Forest Green
    0xFF14B8A6.toInt(), // Teal
    0xFF06B6D4.toInt(), // Cyan
    0xFF00E5FF.toInt(), // Neon Cyan
    0xFF38BDF8.toInt(), // Sky Blue
    0xFF3B82F6.toInt(), // Blue
    0xFF2563EB.toInt(), // Royal Blue
    0xFF6366F1.toInt(), // Indigo
    0xFF8B5CF6.toInt(), // Violet
    0xFFA855F7.toInt(), // Purple
    0xFFD946EF.toInt(), // Magenta
    0xFFEC4899.toInt(), // Pink
    0xFFF43F5E.toInt(), // Rose
    0xFFFDE68A.toInt(), // Cream
    0xFFB45309.toInt()  // Bronze
)

@Composable
fun TextEditorPanel(
    config: TextLayerConfig,
    positionX: Float = 0f,
    positionY: Float = 0f,
    onConfigChange: (TextLayerConfig) -> Unit,
    onConfigCommit: () -> Unit = {},
    onPositionChange: (posX: Float, posY: Float) -> Unit = { _, _ -> },
    onCenterText: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var showColorPickerTarget by remember { mutableStateOf<String?>(null) } // "text", "stroke", "shadow", "glow"

    DisposableEffect(Unit) {
        onDispose {
            onConfigCommit()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioSurface)
            .padding(16.dp)
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.text_edit_title),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Multiline Text Input Field
        OutlinedTextField(
            value = config.text,
            onValueChange = { onConfigChange(config.copy(text = it)) },
            label = { Text(stringResource(R.string.text_hint)) },
            minLines = 2,
            maxLines = 4,
            singleLine = false,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    onConfigCommit()
                    focusManager.clearFocus()
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused) {
                        onConfigCommit()
                    }
                }
                .testTag("input_text_content"),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = IndigoPrimary,
                unfocusedBorderColor = StudioSurfaceVariant,
                focusedLabelColor = IndigoPrimary,
                unfocusedLabelColor = Color.Gray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Font Family Selector
        Text(
            text = stringResource(R.string.font_family),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AvailableFonts) { fontItem ->
                val isSelected = fontItem.name.equals(config.fontName, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) IndigoPrimary else StudioSurfaceVariant)
                        .clickable {
                            onConfigChange(config.copy(fontName = fontItem.name))
                            onConfigCommit()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(fontItem.displayNameResId),
                        color = if (isSelected) Color.White else Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Font Size Slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.text_size), color = Color.White, fontSize = 12.sp)
            Text(text = "${config.fontSizeSp.toInt()}sp", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = config.fontSizeSp,
            onValueChange = { onConfigChange(config.copy(fontSizeSp = it)) },
            onValueChangeFinished = onConfigCommit,
            valueRange = 12f..140f
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Text Alignment & Color Palette
        Text(text = stringResource(R.string.text_align), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = {
                        onConfigChange(config.copy(alignment = TextAlignment.LEFT))
                        onConfigCommit()
                    },
                    modifier = Modifier.size(48.dp).background(if (config.alignment == TextAlignment.LEFT) IndigoPrimary else StudioSurfaceVariant, RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Default.FormatAlignLeft, contentDescription = stringResource(R.string.text_align_left), tint = Color.White)
                }
                IconButton(
                    onClick = {
                        onConfigChange(config.copy(alignment = TextAlignment.CENTER))
                        onConfigCommit()
                    },
                    modifier = Modifier.size(48.dp).background(if (config.alignment == TextAlignment.CENTER) IndigoPrimary else StudioSurfaceVariant, RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Default.FormatAlignCenter, contentDescription = stringResource(R.string.text_align_center), tint = Color.White)
                }
                IconButton(
                    onClick = {
                        onConfigChange(config.copy(alignment = TextAlignment.RIGHT))
                        onConfigCommit()
                    },
                    modifier = Modifier.size(48.dp).background(if (config.alignment == TextAlignment.RIGHT) IndigoPrimary else StudioSurfaceVariant, RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Default.FormatAlignRight, contentDescription = stringResource(R.string.text_align_right), tint = Color.White)
                }
            }

            OutlinedButton(
                onClick = onCenterText,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.text_center_canvas), fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Text Fill Color Swatches (28 colors + custom picker)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.text_color), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            TextButton(
                onClick = { showColorPickerTarget = "text" },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.ColorLens, contentDescription = null, modifier = Modifier.size(16.dp), tint = IndigoPrimary)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.text_custom_color), fontSize = 11.sp, color = IndigoPrimary)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        ColorSwatchesRow(
            selectedColorHex = config.colorHex,
            onColorSelect = { hex ->
                onConfigChange(config.copy(colorHex = hex))
                onConfigCommit()
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Stroke / Outline Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.text_stroke_width), color = Color.White, fontSize = 12.sp)
            Text(text = "${config.strokeWidthDp.toInt()}dp", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = config.strokeWidthDp,
            onValueChange = { onConfigChange(config.copy(strokeWidthDp = it)) },
            onValueChangeFinished = onConfigCommit,
            valueRange = 0f..20f
        )
        if (config.strokeWidthDp > 0f) {
            Spacer(modifier = Modifier.height(4.dp))
            ColorSwatchesRow(
                selectedColorHex = config.strokeColorHex,
                onColorSelect = { hex ->
                    onConfigChange(config.copy(strokeColorHex = hex))
                    onConfigCommit()
                }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Outer Glow Section (glow radius & color)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.text_outer_glow), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(text = "${config.outerGlowRadiusDp.toInt()}dp", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = config.outerGlowRadiusDp,
            onValueChange = { onConfigChange(config.copy(outerGlowRadiusDp = it)) },
            onValueChangeFinished = onConfigCommit,
            valueRange = 0f..40f
        )
        if (config.outerGlowRadiusDp > 0f) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.text_glow_color), color = Color.LightGray, fontSize = 11.sp)
                TextButton(
                    onClick = { showColorPickerTarget = "glow" },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.ColorLens, contentDescription = null, modifier = Modifier.size(16.dp), tint = IndigoPrimary)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.text_custom_color), fontSize = 11.sp, color = IndigoPrimary)
                }
            }
            ColorSwatchesRow(
                selectedColorHex = config.outerGlowColorHex,
                onColorSelect = { hex ->
                    onConfigChange(config.copy(outerGlowColorHex = hex))
                    onConfigCommit()
                }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Drop Shadow Section (radius, offset dx/dy, color)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.text_drop_shadow), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(text = "${config.shadowRadiusDp.toInt()}dp", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = config.shadowRadiusDp,
            onValueChange = { onConfigChange(config.copy(shadowRadiusDp = it)) },
            onValueChangeFinished = onConfigCommit,
            valueRange = 0f..35f
        )
        if (config.shadowRadiusDp > 0f) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.text_shadow_offset_x), color = Color.LightGray, fontSize = 11.sp)
                Text("${config.shadowDxDp.toInt()}dp", color = IndigoPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = config.shadowDxDp,
                onValueChange = { onConfigChange(config.copy(shadowDxDp = it)) },
                onValueChangeFinished = onConfigCommit,
                valueRange = -30f..30f
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.text_shadow_offset_y), color = Color.LightGray, fontSize = 11.sp)
                Text("${config.shadowDyDp.toInt()}dp", color = IndigoPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = config.shadowDyDp,
                onValueChange = { onConfigChange(config.copy(shadowDyDp = it)) },
                onValueChangeFinished = onConfigCommit,
                valueRange = -30f..30f
            )

            Spacer(modifier = Modifier.height(4.dp))
            ColorSwatchesRow(
                selectedColorHex = config.shadowColorHex,
                onColorSelect = { hex ->
                    onConfigChange(config.copy(shadowColorHex = hex))
                    onConfigCommit()
                }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Text Position Controls
        Text(text = stringResource(R.string.text_position), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.transform_pos_x), color = Color.LightGray, fontSize = 11.sp)
            Text("${positionX.toInt()} px", color = IndigoPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = positionX,
            onValueChange = { newX -> onPositionChange(newX, positionY) },
            onValueChangeFinished = onConfigCommit,
            valueRange = -600f..600f
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.transform_pos_y), color = Color.LightGray, fontSize = 11.sp)
            Text("${positionY.toInt()} px", color = IndigoPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = positionY,
            onValueChange = { newY -> onPositionChange(positionX, newY) },
            onValueChangeFinished = onConfigCommit,
            valueRange = -600f..600f
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Line Spacing Multiplier Slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.text_line_spacing), color = Color.White, fontSize = 12.sp)
            Text(text = String.format("%.1fx", config.lineSpacingMultiplier), color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = config.lineSpacingMultiplier,
            onValueChange = { onConfigChange(config.copy(lineSpacingMultiplier = it)) },
            onValueChangeFinished = onConfigCommit,
            valueRange = 0.8f..2.5f
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Letter Spacing Slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.text_letter_spacing), color = Color.White, fontSize = 12.sp)
            Text(text = String.format("%.2fem", config.letterSpacingEm), color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = config.letterSpacingEm,
            onValueChange = { onConfigChange(config.copy(letterSpacingEm = it)) },
            onValueChangeFinished = onConfigCommit,
            valueRange = -0.05f..0.3f
        )
    }

    // Custom Color Dialog
    showColorPickerTarget?.let { target ->
        val currentColor = when (target) {
            "text" -> config.colorHex
            "stroke" -> config.strokeColorHex
            "shadow" -> config.shadowColorHex
            "glow" -> config.outerGlowColorHex
            else -> config.colorHex
        }

        CustomColorPickerDialog(
            initialColorHex = currentColor,
            onDismiss = { showColorPickerTarget = null },
            onColorConfirmed = { selectedHex ->
                when (target) {
                    "text" -> onConfigChange(config.copy(colorHex = selectedHex))
                    "stroke" -> onConfigChange(config.copy(strokeColorHex = selectedHex))
                    "shadow" -> onConfigChange(config.copy(shadowColorHex = selectedHex))
                    "glow" -> onConfigChange(config.copy(outerGlowColorHex = selectedHex))
                }
                onConfigCommit()
                showColorPickerTarget = null
            }
        )
    }
}

@Composable
private fun ColorSwatchesRow(
    selectedColorHex: Int,
    onColorSelect: (Int) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(RichPresetColors) { hexColor ->
            val isSelected = selectedColorHex == hexColor
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(hexColor))
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) IndigoPrimary else Color.White.copy(alpha = 0.25f),
                        shape = CircleShape
                    )
                    .clickable { onColorSelect(hexColor) }
            )
        }
    }
}

@Composable
private fun CustomColorPickerDialog(
    initialColorHex: Int,
    onDismiss: () -> Unit,
    onColorConfirmed: (Int) -> Unit
) {
    var r by remember { mutableFloatStateOf(((initialColorHex shr 16) and 0xFF) / 255f) }
    var g by remember { mutableFloatStateOf(((initialColorHex shr 8) and 0xFF) / 255f) }
    var b by remember { mutableFloatStateOf((initialColorHex and 0xFF) / 255f) }

    val resultingColor = Color(red = r, green = g, blue = b)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.text_pick_color), color = Color.White) },
        containerColor = StudioSurface,
        text = {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                // Color Preview Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(resultingColor)
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                )

                Spacer(Modifier.height(16.dp))

                // Red Slider
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.curves_channel_red), color = Color(0xFFEF4444), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("${(r * 255).toInt()}", color = Color.White, fontSize = 12.sp)
                }
                Slider(value = r, onValueChange = { r = it }, valueRange = 0f..1f)

                // Green Slider
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.curves_channel_green), color = Color(0xFF10B981), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("${(g * 255).toInt()}", color = Color.White, fontSize = 12.sp)
                }
                Slider(value = g, onValueChange = { g = it }, valueRange = 0f..1f)

                // Blue Slider
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.curves_channel_blue), color = Color(0xFF3B82F6), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("${(b * 255).toInt()}", color = Color.White, fontSize = 12.sp)
                }
                Slider(value = b, onValueChange = { b = it }, valueRange = 0f..1f)
            }
        },
        confirmButton = {
            Button(
                onClick = { onColorConfirmed(resultingColor.toArgb()) },
                colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.action_apply), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.filter_cancel))
            }
        }
    )
}
