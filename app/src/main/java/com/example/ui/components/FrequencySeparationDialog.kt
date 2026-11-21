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
import com.example.engine.LowPassMethod
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
    onCreate: (radiusDoc: Float, method: LowPassMethod, threshold: Float, threeBand: Boolean, detailRadiusDoc: Float) -> Unit
) {
    var radiusDoc by remember { mutableFloatStateOf(6f) }
    var method by remember { mutableStateOf(LowPassMethod.GAUSSIAN) }
    var threshold by remember { mutableFloatStateOf(20f) }
    var threeBand by remember { mutableStateOf(false) }
    var detailDoc by remember { mutableFloatStateOf(1.5f) }
    // 0 = Low, 1 = Mid, 2 = High.
    var view by remember { mutableIntStateOf(0) }
    if (!threeBand && view == 1) view = 2
    // A small copy for the live preview.
    val small = remember(preview) {
        val s = minOf(1f, 640f / maxOf(preview.width, preview.height))
        if (s < 1f) Bitmap.createScaledBitmap(preview, (preview.width * s).roundToInt().coerceAtLeast(1), (preview.height * s).roundToInt().coerceAtLeast(1), true)
        else preview.copy(Bitmap.Config.ARGB_8888, false)
    }
    DisposableEffect(small) { onDispose { if (small !== preview && !small.isRecycled) small.recycle() } }
    val smallPerDoc = small.width / (preview.width * docPerPreview)
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(radiusDoc, method, threshold, threeBand, detailDoc, view) {
        delay(150)
        val bmp = withContext(Dispatchers.Default) {
            val sigma = (radiusDoc * smallPerDoc).coerceAtLeast(0.3f)
            val detail = (detailDoc * smallPerDoc).coerceAtLeast(0.3f)
            if (threeBand) {
                val (lo, mi, hi) = FrequencySeparation.split3(small, sigma, detail, method, threshold)
                val keep = when (view) { 0 -> lo; 1 -> mi; else -> hi }
                listOf(lo, mi, hi).forEach { if (it !== keep) it.recycle() }
                keep
            } else if (view == 0) {
                FrequencySeparation.lowPass(small, sigma, method, threshold)
            } else {
                FrequencySeparation.split(small, sigma, method, threshold).let { (lo, hi) -> lo.recycle(); hi }
            }
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
                    FilterChip(selected = view == 0, onClick = { view = 0 }, label = { Text(stringResource(R.string.fs_show_low), fontSize = 12.sp) })
                    if (threeBand) FilterChip(selected = view == 1, onClick = { view = 1 }, label = { Text(stringResource(R.string.fs_show_mid), fontSize = 12.sp) },
                        modifier = Modifier.testTag("fs_view_mid"))
                    FilterChip(selected = view == 2, onClick = { view = 2 }, label = { Text(stringResource(R.string.fs_show_high), fontSize = 12.sp) })
                }
                Text(stringResource(R.string.fs_method), color = Color.LightGray, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        LowPassMethod.GAUSSIAN to R.string.fs_method_gaussian,
                        LowPassMethod.SURFACE to R.string.fs_method_surface,
                        LowPassMethod.MEDIAN to R.string.fs_method_median
                    ).forEach { (m, label) ->
                        FilterChip(selected = method == m, onClick = { method = m },
                            label = { Text(stringResource(label), fontSize = 11.sp, maxLines = 1) },
                            modifier = Modifier.testTag("fs_method_${m.name.lowercase()}"))
                    }
                }
                Text(stringResource(when (method) {
                    LowPassMethod.GAUSSIAN -> R.string.fs_method_gaussian_info
                    LowPassMethod.SURFACE -> R.string.fs_method_surface_info
                    LowPassMethod.MEDIAN -> R.string.fs_method_median_info
                }), color = Color.Gray, fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.fs_radius), color = Color.LightGray, fontSize = 12.sp)
                    Text(String.format(java.util.Locale.US, "%.1f px", radiusDoc), color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Slider(value = radiusDoc, onValueChange = { radiusDoc = it }, valueRange = 1f..40f, modifier = Modifier.testTag("fs_radius"))
                if (method == LowPassMethod.SURFACE) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.fs_threshold), color = Color.LightGray, fontSize = 12.sp)
                        Text("${threshold.roundToInt()}", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 4f..80f, modifier = Modifier.testTag("fs_threshold"))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.fs_three_band), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.fs_three_band_info), color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = threeBand, onCheckedChange = { threeBand = it }, modifier = Modifier.testTag("fs_three_band"))
                }
                if (threeBand) {
                    val maxDetail = (radiusDoc * 0.8f).coerceAtLeast(0.6f)
                    if (detailDoc > maxDetail) detailDoc = maxDetail
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.fs_detail_radius), color = Color.LightGray, fontSize = 12.sp)
                        Text(String.format(java.util.Locale.US, "%.1f px", detailDoc), color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(value = detailDoc.coerceIn(0.5f, maxDetail.coerceAtLeast(0.51f)), onValueChange = { detailDoc = it },
                        valueRange = 0.5f..maxDetail.coerceAtLeast(0.51f), modifier = Modifier.testTag("fs_detail_radius"))
                }
                Text(stringResource(R.string.fs_info), color = Color.Gray, fontSize = 11.sp)
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(radiusDoc, method, threshold, threeBand, detailDoc) }, colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                modifier = Modifier.testTag("fs_create")) { Text(stringResource(R.string.fs_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
