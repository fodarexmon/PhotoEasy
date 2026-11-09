package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.engine.ExportDestination
import com.example.engine.ExportEngine
import com.example.engine.ExportFormat
import com.example.engine.ExportMetadata
import com.example.engine.ExportScope
import com.example.engine.ExportSettings
import com.example.engine.ExportSizeMode
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant

/** Result of a quick test encode: output size, estimated file size and a 1:1 compression preview. */
data class ExportEstimate(
    val width: Int,
    val height: Int,
    val bytes: Long,
    val preview: android.graphics.Bitmap?,
    val files: Int
)

/**
 * Professional export: format (JPG / PNG / WebP / lossless WebP), quality presets, output size
 * (original, percent, long edge, presets) with output sharpening, transparent background,
 * JPEG background colour, scope (whole image / selection / each layer), metadata, file name,
 * destination (gallery or Files) and share. Shows the output size, the estimated file size and a
 * 1:1 preview of the compression. Settings are remembered.
 */
@Composable
fun ExportDialog(
    settings: ExportSettings,
    estimate: ExportEstimate?,
    hasSelection: Boolean,
    hasBackgroundFill: Boolean,
    layerCount: Int,
    onSettingsChange: (ExportSettings) -> Unit,
    onRequestEstimate: (ExportSettings) -> Unit,
    onDismiss: () -> Unit,
    onExport: (ExportSettings) -> Unit
) {
    val s = settings
    LaunchedEffect(s.copy(fileName = "", destination = ExportDestination.GALLERY, shareAfter = false)) { onRequestEstimate(s) }
    fun set(n: ExportSettings) = onSettingsChange(n)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = StudioSurface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .testTag("dialog_export")
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                // Summary: output size, estimated file size, compression preview.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (estimate != null) {
                            Text(stringResource(R.string.export_output_size, estimate.width, estimate.height), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (estimate.files > 1) stringResource(R.string.export_estimate_files, estimate.files, ExportEngine.formatBytes(estimate.bytes))
                                else stringResource(R.string.export_estimate, ExportEngine.formatBytes(estimate.bytes)),
                                color = IndigoPrimary, fontSize = 12.sp
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = IndigoPrimary)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.export_estimating), color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                    val pv = estimate?.preview
                    if (s.format.lossy && pv != null && !pv.isRecycled) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                bitmap = pv.asImageBitmap(),
                                contentDescription = stringResource(R.string.export_compression_preview),
                                contentScale = ContentScale.Crop,
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp))
                            )
                            Text(stringResource(R.string.export_compression_preview), color = Color.Gray, fontSize = 9.sp)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Format
                    Section(stringResource(R.string.export_format)) {
                        ChipRow {
                            ExportFormat.available().forEach { f ->
                                Chip(
                                    label = stringResource(
                                        when (f) {
                                            ExportFormat.JPEG -> R.string.format_jpg
                                            ExportFormat.PNG -> R.string.format_png
                                            ExportFormat.WEBP -> R.string.format_webp
                                            ExportFormat.WEBP_LOSSLESS -> R.string.format_webp_lossless
                                        }
                                    ),
                                    selected = s.format == f,
                                    tag = "chip_format_${f.name.lowercase()}"
                                ) { set(s.copy(format = f)) }
                            }
                        }
                        Text(
                            stringResource(
                                when (s.format) {
                                    ExportFormat.JPEG -> R.string.format_jpg_desc
                                    ExportFormat.PNG -> R.string.format_png_desc
                                    ExportFormat.WEBP -> R.string.format_webp_desc
                                    ExportFormat.WEBP_LOSSLESS -> R.string.format_webp_lossless_desc
                                }
                            ),
                            color = Color.Gray, fontSize = 11.sp
                        )
                    }

                    // Quality (lossy formats)
                    if (s.format.lossy) {
                        Section(stringResource(R.string.export_quality) + ": ${s.quality}%") {
                            ChipRow {
                                listOf(100 to R.string.export_quality_max, 92 to R.string.export_quality_high, 80 to R.string.export_quality_web, 65 to R.string.export_quality_small)
                                    .forEach { (q, res) -> Chip(stringResource(res), s.quality == q) { set(s.copy(quality = q)) } }
                            }
                            Slider(
                                value = s.quality.toFloat(),
                                onValueChange = { set(s.copy(quality = it.toInt().coerceIn(1, 100))) },
                                valueRange = 1f..100f,
                                modifier = Modifier.testTag("slider_export_quality")
                            )
                        }
                    }

                    // Size
                    Section(stringResource(R.string.export_size)) {
                        ChipRow {
                            Chip(stringResource(R.string.export_size_original), s.sizeMode == ExportSizeMode.ORIGINAL) { set(s.copy(sizeMode = ExportSizeMode.ORIGINAL)) }
                            Chip(stringResource(R.string.export_size_percent), s.sizeMode == ExportSizeMode.PERCENT) { set(s.copy(sizeMode = ExportSizeMode.PERCENT)) }
                            Chip(stringResource(R.string.export_size_long_edge), s.sizeMode == ExportSizeMode.LONG_EDGE) { set(s.copy(sizeMode = ExportSizeMode.LONG_EDGE)) }
                        }
                        when (s.sizeMode) {
                            ExportSizeMode.PERCENT -> {
                                Text("${s.percent}%", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Slider(value = s.percent.toFloat(), onValueChange = { set(s.copy(percent = (it / 5f).toInt() * 5)) }, valueRange = 5f..200f)
                            }
                            ExportSizeMode.LONG_EDGE -> {
                                ChipRow {
                                    listOf(1080 to "1080 (Instagram)", 1920 to "1920 (Full HD)", 2048 to "2048", 3840 to "3840 (4K)").forEach { (px, label) ->
                                        Chip(label, s.longEdge == px) { set(s.copy(longEdge = px)) }
                                    }
                                }
                                var text by remember(s.longEdge) { mutableStateOf(s.longEdge.toString()) }
                                OutlinedTextField(
                                    value = text,
                                    onValueChange = { v ->
                                        text = v.filter { it.isDigit() }.take(5)
                                        text.toIntOrNull()?.takeIf { it in 16..16384 }?.let { set(s.copy(longEdge = it)) }
                                    },
                                    label = { Text(stringResource(R.string.export_long_edge_px)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            ExportSizeMode.ORIGINAL -> {}
                        }
                        if (s.sizeMode != ExportSizeMode.ORIGINAL) {
                            SwitchRow(stringResource(R.string.export_sharpen), stringResource(R.string.export_sharpen_desc), s.sharpenOnResize) { set(s.copy(sharpenOnResize = it)) }
                        }
                    }

                    // Background
                    Section(stringResource(R.string.export_background)) {
                        if (s.format.supportsAlpha) {
                            SwitchRow(
                                stringResource(R.string.export_transparent_bg),
                                stringResource(if (hasBackgroundFill) R.string.export_transparent_bg_desc else R.string.export_transparent_bg_none),
                                s.transparentBackground,
                                enabled = hasBackgroundFill,
                                tag = "switch_export_transparent"
                            ) { set(s.copy(transparentBackground = it)) }
                        } else {
                            Text(stringResource(R.string.export_jpeg_bg_desc), color = Color.Gray, fontSize = 11.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf(android.graphics.Color.WHITE, android.graphics.Color.BLACK, 0xFF808080.toInt(), 0xFFF5F0E6.toInt()).forEach { c ->
                                    Box(
                                        Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(Color(c))
                                            .border(if (s.jpegBackground == c) 3.dp else 1.dp, if (s.jpegBackground == c) IndigoPrimary else Color.Gray, CircleShape)
                                            .clickable { set(s.copy(jpegBackground = c)) }
                                    )
                                }
                            }
                        }
                    }

                    // Scope
                    Section(stringResource(R.string.export_scope)) {
                        ChipRow {
                            Chip(stringResource(R.string.export_scope_whole), s.scope == ExportScope.WHOLE) { set(s.copy(scope = ExportScope.WHOLE)) }
                            Chip(stringResource(R.string.export_scope_selection), s.scope == ExportScope.SELECTION, enabled = hasSelection) { set(s.copy(scope = ExportScope.SELECTION)) }
                            Chip(stringResource(R.string.export_scope_layers, layerCount), s.scope == ExportScope.LAYERS, enabled = layerCount > 0) { set(s.copy(scope = ExportScope.LAYERS)) }
                        }
                    }

                    // Metadata
                    Section(stringResource(R.string.export_metadata)) {
                        ChipRow {
                            Chip(stringResource(R.string.export_meta_keep), s.metadata == ExportMetadata.KEEP_ALL) { set(s.copy(metadata = ExportMetadata.KEEP_ALL)) }
                            Chip(stringResource(R.string.export_meta_no_location), s.metadata == ExportMetadata.NO_LOCATION) { set(s.copy(metadata = ExportMetadata.NO_LOCATION)) }
                            Chip(stringResource(R.string.export_meta_none), s.metadata == ExportMetadata.NONE) { set(s.copy(metadata = ExportMetadata.NONE)) }
                        }
                    }

                    // File name & destination
                    Section(stringResource(R.string.export_file)) {
                        OutlinedTextField(
                            value = s.fileName,
                            onValueChange = { set(s.copy(fileName = it.take(60))) },
                            label = { Text(stringResource(R.string.export_file_name)) },
                            suffix = { Text(".${s.format.extension}", color = Color.Gray) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("field_export_name")
                        )
                        ChipRow {
                            Chip(stringResource(R.string.export_dest_gallery), s.destination == ExportDestination.GALLERY) { set(s.copy(destination = ExportDestination.GALLERY)) }
                            Chip(stringResource(R.string.export_dest_files), s.destination == ExportDestination.FILES) { set(s.copy(destination = ExportDestination.FILES)) }
                        }
                        SwitchRow(stringResource(R.string.export_share_after), null, s.shareAfter) { set(s.copy(shareAfter = it)) }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(
                        onClick = { onExport(s) },
                        enabled = s.scope != ExportScope.SELECTION || hasSelection,
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                        modifier = Modifier.weight(1.4f).heightIn(min = 48.dp).testTag("btn_confirm_export")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_export), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = StudioSurfaceVariant.copy(alpha = 0.6f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, tag: String? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, fontSize = 12.sp) },
        modifier = Modifier.heightIn(min = 40.dp).let { if (tag != null) it.testTag(tag) else it }
    )
}

@Composable
private fun SwitchRow(title: String, desc: String?, checked: Boolean, enabled: Boolean = true, tag: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (desc != null) Text(desc, color = Color.Gray, fontSize = 10.sp)
        }
        Switch(
            checked = checked && enabled,
            enabled = enabled,
            onCheckedChange = onChange,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier
        )
    }
}
