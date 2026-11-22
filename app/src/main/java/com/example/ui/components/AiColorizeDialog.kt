package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.viewmodel.EditorViewModel
import kotlin.math.roundToInt

/**
 * AI Colorize: first downloads the model (once, with its size and progress), then colorizes the
 * layer into a new layer with a colour intensity.
 */
@Composable
fun AiColorizeDialog(
    state: EditorViewModel.AiColorizeState,
    onDownload: () -> Unit,
    onColorize: (Float) -> Unit,
    onDeleteModel: () -> Unit,
    onDismiss: () -> Unit
) {
    var intensity by remember { mutableFloatStateOf(1.2f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = { Text(stringResource(R.string.ai_colorize_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.installed) {
                    Text(stringResource(R.string.ai_colorize_needs_model), color = Color.LightGray, fontSize = 13.sp)
                    val mb = state.size?.let { (it / (1024f * 1024f)).roundToInt() }
                    Text(
                        if (mb != null) stringResource(R.string.ai_model_size, mb) else stringResource(R.string.ai_model_size_unknown),
                        color = Color.Gray, fontSize = 12.sp
                    )
                    if (state.downloading) {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().testTag("ai_download_progress"))
                        Text("${(state.progress * 100).roundToInt()}%", color = IndigoPrimary, fontSize = 12.sp)
                    }
                    if (state.error) Text(stringResource(R.string.ai_model_download_failed), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                } else {
                    Text(stringResource(R.string.ai_colorize_info), color = Color.LightGray, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.ai_colorize_intensity), color = Color.LightGray, fontSize = 12.sp)
                        Text("${(intensity * 100).roundToInt()}%", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(value = intensity, onValueChange = { intensity = it }, valueRange = 0.5f..2f, modifier = Modifier.testTag("ai_colorize_intensity"))
                    TextButton(onClick = onDeleteModel, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.ai_model_delete), fontSize = 12.sp, color = Color.Gray)
                    }
                }
                Text(stringResource(R.string.ai_colorize_credit), color = Color.Gray, fontSize = 10.sp)
            }
        },
        confirmButton = {
            if (!state.installed) {
                Button(onClick = onDownload, enabled = !state.downloading, colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                    modifier = Modifier.testTag("btn_ai_download")) {
                    Text(stringResource(if (state.error) R.string.ai_model_retry else R.string.ai_model_download))
                }
            } else {
                Button(onClick = { onColorize(intensity) }, colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                    modifier = Modifier.testTag("btn_ai_colorize")) { Text(stringResource(R.string.ai_colorize_run)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
