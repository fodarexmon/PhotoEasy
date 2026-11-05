package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.data.model.StandardCanvasPresets
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant

@Composable
fun CanvasResizeDialog(
    currentWidth: Int,
    currentHeight: Int,
    onDismiss: () -> Unit,
    onConfirmResize: (newWidth: Int, newHeight: Int, scaleContent: Boolean) -> Unit
) {
    var widthStr by remember { mutableStateOf(currentWidth.toString()) }
    var heightStr by remember { mutableStateOf(currentHeight.toString()) }
    var keepAspectRatio by remember { mutableStateOf(true) }
    var scaleContent by remember { mutableStateOf(true) }

    val originalRatio = if (currentHeight > 0) currentWidth.toFloat() / currentHeight else 1.0f

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("dialog_canvas_resize")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.canvas_resize_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Presets
                Text(text = stringResource(R.string.canvas_resize_presets), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StandardCanvasPresets.forEach { preset ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(StudioSurfaceVariant)
                                .clickable {
                                    widthStr = preset.width.toString()
                                    heightStr = preset.height.toString()
                                }
                                .padding(6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(preset.titleResId),
                                fontSize = 11.sp,
                                color = Color.White,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Dimension Inputs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = widthStr,
                        onValueChange = { newW ->
                            widthStr = newW
                            if (keepAspectRatio) {
                                val wVal = newW.toIntOrNull()
                                if (wVal != null && originalRatio > 0f) {
                                    heightStr = (wVal / originalRatio).toInt().toString()
                                }
                            }
                        },
                        label = { Text(stringResource(R.string.width_px)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(onClick = { keepAspectRatio = !keepAspectRatio }) {
                        Icon(
                            if (keepAspectRatio) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = stringResource(R.string.lock_aspect_ratio),
                            tint = if (keepAspectRatio) IndigoPrimary else Color.Gray
                        )
                    }

                    OutlinedTextField(
                        value = heightStr,
                        onValueChange = { newH ->
                            heightStr = newH
                            if (keepAspectRatio) {
                                val hVal = newH.toIntOrNull()
                                if (hVal != null) {
                                    widthStr = (hVal * originalRatio).toInt().toString()
                                }
                            }
                        },
                        label = { Text(stringResource(R.string.height_px)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Scale Content switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.canvas_resize_scale_content),
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Switch(
                        checked = scaleContent,
                        onCheckedChange = { scaleContent = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = IndigoPrimary)
                    )
                }

                // Validation state
                val wParsed = widthStr.toIntOrNull()
                val hParsed = heightStr.toIntOrNull()
                val validation = com.example.util.CanvasValidation.validate(wParsed, hParsed)

                if (!validation.isValid) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = validation.errorResId?.let { stringResource(it, *validation.errorArgs.toTypedArray()) } ?: stringResource(R.string.canvas_invalid_dimensions),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(8.dp)) {
                        Text(stringResource(R.string.action_cancel))
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = {
                            if (validation.isValid) {
                                onConfirmResize(validation.width, validation.height, scaleContent)
                            }
                        },
                        enabled = validation.isValid,
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("btn_confirm_canvas_resize")
                    ) {
                        Text(stringResource(R.string.action_apply), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
