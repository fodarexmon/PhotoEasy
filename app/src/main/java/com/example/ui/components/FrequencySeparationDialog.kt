package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.engine.FrequencySeparation
import com.example.engine.ProEffects
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Frequency separation setup: the blur radius that divides colour/tone (Low) from texture (High),
 * with a live preview of either part, like checking the Gaussian Blur radius in Photoshop.
 * [docPerPreview] converts the layer preview's pixels to document pixels.
 */
@Composable
fun FrequencySeparationDialog(
    preview: Bitmap,
    docPerPreview: Float,
    onDismiss: () -> Unit,
    onCreate: (radiusDoc: Float) -> Unit
) {
    var radiusDoc by remember { mutableFloatStateOf(6f) }
    var showHigh by remember { mutableStateOf(false) }
    // A small copy for the live preview.
    val small = remember(preview) {
        val s = minOf(1f, 640f / maxOf(preview.width, preview.height))
        if (s < 1f) Bitmap.createScaledBitmap(preview, (preview.width * s).roundToInt().coerceAtLeast(1), (preview.height * s).roundToInt().coerceAtLeast(1), true)
        else preview.copy(Bitmap.Config.ARGB_8888, false)
    }
    DisposableEffect(small) { onDispose { if (small !== preview && !small.isRecycled) small.recycle() } }
    val smallPerDoc = small.width / (preview.width * docPerPreview)
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(radiusDoc, showHigh) {
        delay(120)
        val bmp = withContext(Dispatchers.Default) {
            val sigma = (radiusDoc * smallPerDoc).coerceAtLeast(0.3f)
            if (showHigh) FrequencySeparation.split(small, sigma).let { (lo, hi) -> lo.recycle(); hi }
            else ProEffects.gaussian(small, sigma)
        }
        // The previous preview is left to the GC: it may still be on screen for this frame.
        shown = bmp
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = { Text(stringResource(R.string.fs_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 300.dp)
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val b = shown
                    if (b != null && !b.isRecycled) {
                        Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                    } else {
                        CircularProgressIndicator(color = IndigoPrimary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showHigh, onClick = { showHigh = false }, label = { Text(stringResource(R.string.fs_show_low), fontSize = 12.sp) })
                    FilterChip(selected = showHigh, onClick = { showHigh = true }, label = { Text(stringResource(R.string.fs_show_high), fontSize = 12.sp) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.fs_radius), color = Color.LightGray, fontSize = 12.sp)
                    Text(String.format(java.util.Locale.US, "%.1f px", radiusDoc), color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Slider(value = radiusDoc, onValueChange = { radiusDoc = it }, valueRange = 1f..40f, modifier = Modifier.testTag("fs_radius"))
                Text(stringResource(R.string.fs_info), color = Color.Gray, fontSize = 11.sp)
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(radiusDoc) }, colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                modifier = Modifier.testTag("fs_create")) { Text(stringResource(R.string.fs_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
