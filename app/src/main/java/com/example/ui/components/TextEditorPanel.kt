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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.AvailableFonts
import com.example.data.model.TextAlignment
import com.example.data.model.TextLayerConfig
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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

private enum class ColorTarget { TEXT, GRADIENT, STROKE, BACKGROUND, GLOW, SHADOW }

@Composable
fun TextEditorPanel(
    config: TextLayerConfig,
    positionX: Float = 0f,
    positionY: Float = 0f,
    onConfigChange: (TextLayerConfig) -> Unit,
    onConfigCommit: () -> Unit = {},
    onPositionChange: (posX: Float, posY: Float) -> Unit = { _, _ -> },
    onCenterText: () -> Unit = {},
    /** Document size: the position sliders cover the whole image. */
    documentWidth: Int = 1200,
    documentHeight: Int = 1200,
    /** When true the text field takes focus with all its text selected (new layer, double tap). */
    requestEditFocus: Boolean = false,
    onEditFocusHandled: () -> Unit = {},
    /** Identifies the edited layer, so the text field resets when another text layer is selected. */
    layerKey: String = "",
    modifier: Modifier = Modifier
) {
    var colorTarget by remember { mutableStateOf<ColorTarget?>(null) }
    val focusRequester = remember { FocusRequester() }
    val latestConfig by rememberUpdatedState(config)
    val latestCommit by rememberUpdatedState(onConfigCommit)

    // The field keeps its own cursor / selection; the layer text follows it. External changes
    // (undo, another layer) replace the field content.
    var fieldValue by remember(layerKey) { mutableStateOf(TextFieldValue(config.text, TextRange(config.text.length))) }
    // Last text this field sent; anything else arriving in [config] is an external change.
    // Only a NEW config text that the field did not send (undo, redo) replaces the field; a config
    // that simply has not caught up with typing yet is ignored.
    var lastSentText by remember(layerKey) { mutableStateOf(config.text) }
    var lastSeenConfigText by remember(layerKey) { mutableStateOf(config.text) }
    if (config.text != lastSeenConfigText) {
        lastSeenConfigText = config.text
        if (config.text != lastSentText) {
            lastSentText = config.text
            fieldValue = TextFieldValue(config.text, TextRange(config.text.length))
        }
    }

    LaunchedEffect(requestEditFocus, layerKey) {
        if (requestEditFocus) {
            delay(120)
            fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
            try {
                focusRequester.requestFocus()
            } catch (_: IllegalStateException) {
                // Field not laid out yet; the user can still tap it.
            }
            onEditFocusHandled()
        }
    }

    DisposableEffect(Unit) {
        onDispose { latestCommit() }
    }

    fun change(newConfig: TextLayerConfig) = onConfigChange(newConfig)
    fun changeAndCommit(newConfig: TextLayerConfig) {
        onConfigChange(newConfig)
        onConfigCommit()
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

        // Multiline text: Enter inserts a new line; the edit is committed when the field loses focus.
        var hadFocus by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { v ->
                fieldValue = v
                if (v.text != lastSentText) {
                    lastSentText = v.text
                    change(latestConfig.copy(text = v.text))
                }
            },
            label = { Text(stringResource(R.string.text_hint)) },
            minLines = 2,
            maxLines = 6,
            singleLine = false,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    if (hadFocus && !focusState.isFocused) onConfigCommit()
                    hadFocus = focusState.isFocused
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

        // Font family: each chip is written in its own font.
        SectionTitle(stringResource(R.string.font_family))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AvailableFonts) { fontItem ->
                val isSelected = fontItem.name.equals(config.fontName, ignoreCase = true)
                val family = remember(fontItem.fontResId) {
                    fontItem.fontResId?.let { FontFamily(Font(it)) } ?: FontFamily.Default
                }
                Box(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) IndigoPrimary else StudioSurfaceVariant)
                        .clickable { changeAndCommit(config.copy(fontName = fontItem.name)) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(fontItem.displayNameResId),
                        color = if (isSelected) Color.White else Color.LightGray,
                        fontSize = 13.sp,
                        fontFamily = family,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Style and alignment
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToggleIcon(Icons.Default.FormatBold, stringResource(R.string.text_bold), config.isBold) {
                    changeAndCommit(config.copy(isBold = !config.isBold))
                }
                ToggleIcon(Icons.Default.FormatItalic, stringResource(R.string.text_italic), config.isItalic) {
                    changeAndCommit(config.copy(isItalic = !config.isItalic))
                }
                ToggleIcon(Icons.Default.FormatUnderlined, stringResource(R.string.text_underline), config.isUnderline) {
                    changeAndCommit(config.copy(isUnderline = !config.isUnderline))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToggleIcon(Icons.Default.FormatAlignLeft, stringResource(R.string.text_align_left), config.alignment == TextAlignment.LEFT) {
                    changeAndCommit(config.copy(alignment = TextAlignment.LEFT))
                }
                ToggleIcon(Icons.Default.FormatAlignCenter, stringResource(R.string.text_align_center), config.alignment == TextAlignment.CENTER) {
                    changeAndCommit(config.copy(alignment = TextAlignment.CENTER))
                }
                ToggleIcon(Icons.Default.FormatAlignRight, stringResource(R.string.text_align_right), config.alignment == TextAlignment.RIGHT) {
                    changeAndCommit(config.copy(alignment = TextAlignment.RIGHT))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LabeledSlider(stringResource(R.string.text_size), config.fontSizeSp.roundToInt().toString(), config.fontSizeSp, 8f..200f,
            { change(config.copy(fontSizeSp = it)) }, onConfigCommit)
        LabeledSlider(stringResource(R.string.text_max_width), "${config.maxLineWidthPercent.roundToInt()}%", config.maxLineWidthPercent, 20f..100f,
            { change(config.copy(maxLineWidthPercent = it)) }, onConfigCommit)

        Spacer(modifier = Modifier.height(10.dp))

        // Fill colour (+ optional gradient)
        ColorHeader(stringResource(R.string.text_color)) { colorTarget = ColorTarget.TEXT }
        ColorSwatchesRow(selectedColorHex = config.colorHex, onColorSelect = { changeAndCommit(config.copy(colorHex = it)) })
        SwitchRow(stringResource(R.string.text_gradient), config.gradientEnabled) {
            changeAndCommit(config.copy(gradientEnabled = it))
        }
        if (config.gradientEnabled) {
            ColorHeader(stringResource(R.string.text_gradient_end), small = true) { colorTarget = ColorTarget.GRADIENT }
            ColorSwatchesRow(selectedColorHex = config.gradientEndColorHex, onColorSelect = { changeAndCommit(config.copy(gradientEndColorHex = it)) })
            LabeledSlider(stringResource(R.string.text_gradient_angle), "${config.gradientAngle.roundToInt()}°", config.gradientAngle, 0f..360f,
                { change(config.copy(gradientAngle = it)) }, onConfigCommit)
        }

        SectionDivider()

        // Outline
        LabeledSlider(stringResource(R.string.text_stroke_width), config.strokeWidthDp.roundToInt().toString(), config.strokeWidthDp, 0f..20f,
            { change(config.copy(strokeWidthDp = it)) }, onConfigCommit, bold = true)
        if (config.strokeWidthDp > 0f) {
            ColorHeader(stringResource(R.string.text_stroke_color), small = true) { colorTarget = ColorTarget.STROKE }
            ColorSwatchesRow(selectedColorHex = config.strokeColorHex, onColorSelect = { changeAndCommit(config.copy(strokeColorHex = it)) })
        }

        SectionDivider()

        // Background box
        SwitchRow(stringResource(R.string.text_background), config.backgroundEnabled, bold = true) {
            changeAndCommit(config.copy(backgroundEnabled = it))
        }
        if (config.backgroundEnabled) {
            ColorHeader(stringResource(R.string.text_background_color), small = true) { colorTarget = ColorTarget.BACKGROUND }
            ColorSwatchesRow(selectedColorHex = config.backgroundColorHex, onColorSelect = { changeAndCommit(config.copy(backgroundColorHex = it)) })
            LabeledSlider(stringResource(R.string.text_opacity), "${(config.backgroundOpacity * 100).roundToInt()}%", config.backgroundOpacity, 0f..1f,
                { change(config.copy(backgroundOpacity = it)) }, onConfigCommit)
            LabeledSlider(stringResource(R.string.text_background_padding), config.backgroundPadding.roundToInt().toString(), config.backgroundPadding, 0f..60f,
                { change(config.copy(backgroundPadding = it)) }, onConfigCommit)
            LabeledSlider(stringResource(R.string.text_background_corner), config.backgroundCorner.roundToInt().toString(), config.backgroundCorner, 0f..60f,
                { change(config.copy(backgroundCorner = it)) }, onConfigCommit)
        }

        SectionDivider()

        // Outer glow
        LabeledSlider(stringResource(R.string.text_outer_glow), config.outerGlowRadiusDp.roundToInt().toString(), config.outerGlowRadiusDp, 0f..40f,
            { change(config.copy(outerGlowRadiusDp = it)) }, onConfigCommit, bold = true)
        if (config.outerGlowRadiusDp > 0f) {
            ColorHeader(stringResource(R.string.text_glow_color), small = true) { colorTarget = ColorTarget.GLOW }
            ColorSwatchesRow(selectedColorHex = config.outerGlowColorHex, onColorSelect = { changeAndCommit(config.copy(outerGlowColorHex = it)) })
            LabeledSlider(stringResource(R.string.text_opacity), "${(config.glowOpacity * 100).roundToInt()}%", config.glowOpacity, 0f..1f,
                { change(config.copy(glowOpacity = it)) }, onConfigCommit)
        }

        SectionDivider()

        // Drop shadow
        LabeledSlider(stringResource(R.string.text_drop_shadow), config.shadowRadiusDp.roundToInt().toString(), config.shadowRadiusDp, 0f..35f,
            { change(config.copy(shadowRadiusDp = it)) }, onConfigCommit, bold = true)
        if (config.shadowRadiusDp > 0f) {
            LabeledSlider(stringResource(R.string.text_shadow_offset_x), config.shadowDxDp.roundToInt().toString(), config.shadowDxDp, -30f..30f,
                { change(config.copy(shadowDxDp = it)) }, onConfigCommit)
            LabeledSlider(stringResource(R.string.text_shadow_offset_y), config.shadowDyDp.roundToInt().toString(), config.shadowDyDp, -30f..30f,
                { change(config.copy(shadowDyDp = it)) }, onConfigCommit)
            ColorHeader(stringResource(R.string.text_shadow_color), small = true) { colorTarget = ColorTarget.SHADOW }
            ColorSwatchesRow(selectedColorHex = config.shadowColorHex, onColorSelect = { changeAndCommit(config.copy(shadowColorHex = it)) })
            LabeledSlider(stringResource(R.string.text_opacity), "${(config.shadowOpacity * 100).roundToInt()}%", config.shadowOpacity, 0f..1f,
                { change(config.copy(shadowOpacity = it)) }, onConfigCommit)
        }

        SectionDivider()

        // Position over the whole image
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle(stringResource(R.string.text_position))
            OutlinedButton(
                onClick = onCenterText,
                modifier = Modifier.heightIn(min = 44.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.text_center_canvas), fontSize = 11.sp)
            }
        }
        val halfW = (documentWidth / 2f).coerceAtLeast(50f)
        val halfH = (documentHeight / 2f).coerceAtLeast(50f)
        LabeledSlider(stringResource(R.string.transform_pos_x), "${positionX.roundToInt()} px", positionX.coerceIn(-halfW, halfW), -halfW..halfW,
            { onPositionChange(it, positionY) }, onConfigCommit)
        LabeledSlider(stringResource(R.string.transform_pos_y), "${positionY.roundToInt()} px", positionY.coerceIn(-halfH, halfH), -halfH..halfH,
            { onPositionChange(positionX, it) }, onConfigCommit)

        SectionDivider()

        LabeledSlider(stringResource(R.string.text_line_spacing), String.format(Locale.US, "%.1fx", config.lineSpacingMultiplier), config.lineSpacingMultiplier, 0.8f..2.5f,
            { change(config.copy(lineSpacingMultiplier = it)) }, onConfigCommit)
        LabeledSlider(stringResource(R.string.text_letter_spacing), String.format(Locale.US, "%.2f", config.letterSpacingEm), config.letterSpacingEm, -0.05f..0.5f,
            { change(config.copy(letterSpacingEm = it)) }, onConfigCommit)
    }

    // Custom Color Dialog
    colorTarget?.let { target ->
        val currentColor = when (target) {
            ColorTarget.TEXT -> config.colorHex
            ColorTarget.GRADIENT -> config.gradientEndColorHex
            ColorTarget.STROKE -> config.strokeColorHex
            ColorTarget.BACKGROUND -> config.backgroundColorHex
            ColorTarget.GLOW -> config.outerGlowColorHex
            ColorTarget.SHADOW -> config.shadowColorHex
        }
        CustomColorPickerDialog(
            initialColorHex = currentColor,
            onDismiss = { colorTarget = null },
            onColorConfirmed = { hex ->
                changeAndCommit(
                    when (target) {
                        ColorTarget.TEXT -> config.copy(colorHex = hex)
                        ColorTarget.GRADIENT -> config.copy(gradientEndColorHex = hex)
                        ColorTarget.STROKE -> config.copy(strokeColorHex = hex)
                        ColorTarget.BACKGROUND -> config.copy(backgroundColorHex = hex)
                        ColorTarget.GLOW -> config.copy(outerGlowColorHex = hex)
                        ColorTarget.SHADOW -> config.copy(shadowColorHex = hex)
                    }
                )
                colorTarget = null
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ToggleIcon(icon: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .background(if (selected) IndigoPrimary else StudioSurfaceVariant, RoundedCornerShape(8.dp))
    ) {
        Icon(icon, contentDescription = description, tint = Color.White)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    bold: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (bold) Color.White else Color.LightGray,
            fontSize = if (bold) 12.sp else 11.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
        )
        Text(text = valueLabel, color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        onValueChangeFinished = onFinished,
        valueRange = range
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, bold: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (bold) Color.White else Color.LightGray,
            fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ColorHeader(label: String, small: Boolean = false, onCustom: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (small) Color.LightGray else Color.White,
            fontSize = if (small) 11.sp else 12.sp,
            fontWeight = if (small) FontWeight.Normal else FontWeight.SemiBold
        )
        TextButton(onClick = onCustom, modifier = Modifier.heightIn(min = 44.dp)) {
            Icon(Icons.Default.ColorLens, contentDescription = null, modifier = Modifier.size(16.dp), tint = IndigoPrimary)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.text_custom_color), fontSize = 11.sp, color = IndigoPrimary)
        }
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
            // Compare the colour only: effect transparency is a separate setting.
            val isSelected = (selectedColorHex and 0x00FFFFFF) == (hexColor and 0x00FFFFFF)
            Box(
                modifier = Modifier
                    .size(44.dp)
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(resultingColor)
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(16.dp))
                ChannelSlider(stringResource(R.string.curves_channel_red), Color(0xFFEF4444), r) { r = it }
                ChannelSlider(stringResource(R.string.curves_channel_green), Color(0xFF10B981), g) { g = it }
                ChannelSlider(stringResource(R.string.curves_channel_blue), Color(0xFF3B82F6), b) { b = it }
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

@Composable
private fun ChannelSlider(label: String, color: Color, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("${(value * 255).roundToInt()}", color = Color.White, fontSize = 12.sp)
    }
    Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
}
