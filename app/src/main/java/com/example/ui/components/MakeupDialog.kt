package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.engine.FaceMesh
import com.example.engine.Makeup
import com.example.engine.MakeupFace
import com.example.engine.MakeupSettings
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private enum class MakeupTab(val label: Int) {
    LOOKS(R.string.mk_tab_looks), LIPS(R.string.mk_tab_lips), CHEEKS(R.string.mk_tab_cheeks),
    EYES(R.string.mk_tab_eyes), BROWS_IRIS(R.string.mk_tab_brows_iris), TEETH(R.string.mk_tab_teeth)
}

private val LIP_COLORS = listOf(0xB0394A, 0xB0101E, 0xC4686E, 0xC08A7E, 0x8E2A3A, 0xD45A7A, 0x9A4A3A, 0x6E2230)
private val BLUSH_COLORS = listOf(0xE8848C, 0xEE9A92, 0xE57E86, 0xD9A08C, 0xF0A07A, 0xC98585)
private val SHADOW_COLORS = listOf(0x8A5A4A, 0x9A6248, 0x2E2A2E, 0xB08870, 0x6A4A7A, 0x7A5A3A, 0xB8865A, 0x3A4A6A)
private val LINER_COLORS = listOf(0x1A1414, 0x3A2620, 0x1C2A4A, 0x2A3A2A)
private val BROW_COLORS = listOf(0x3B2A22, 0x2E221C, 0x4A3528, 0x5A4232, 0x6E5440, 0x1E1A18)
private val EYE_COLORS = listOf(0x3A7DB8, 0x4A9A6A, 0x8A6A3A, 0x7A8A9A, 0x5AA8C8, 0x6A4A2A, 0x8A5AB0)

/**
 * Smart makeup: finds the faces (MediaPipe's face mesh with irises, ML Kit as a fallback), shows
 * the makeup live on a working copy, and hands the settings back for the full-size render.
 *
 * [source] is the layer as it looks; [onApply] gets the faces in [source]'s working-copy pixels,
 * that copy's width, and the settings by face.
 */
@Composable
fun MakeupDialog(
    source: Bitmap,
    onDismiss: () -> Unit,
    onApply: (faces: List<MakeupFace>, workWidth: Int, settings: List<MakeupSettings>) -> Unit
) {
    val context = LocalContext.current
    val work by produceState<Bitmap?>(null, source) {
        value = withContext(Dispatchers.Default) {
            val s = min(1f, 1280f / maxOf(source.width, source.height))
            if (s < 1f) Bitmap.createScaledBitmap(source, (source.width * s).roundToInt().coerceAtLeast(1), (source.height * s).roundToInt().coerceAtLeast(1), true)
            else source.copy(Bitmap.Config.ARGB_8888, false)
        }
    }
    var faces by remember { mutableStateOf<List<MakeupFace>?>(null) }
    LaunchedEffect(work) {
        val w = work ?: return@LaunchedEffect
        faces = withContext(Dispatchers.Default) { FaceMesh.detect(context, w) }
    }
    val settings = remember { mutableStateListOf<MakeupSettings>() }
    LaunchedEffect(faces) { faces?.let { f -> settings.clear(); repeat(f.size) { settings.add(MakeupSettings()) } } }
    var selectedFace by remember { mutableIntStateOf(0) }
    var allFaces by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(MakeupTab.LOOKS) }
    var comparing by remember { mutableStateOf(false) }
    var customFor by remember { mutableStateOf<((Int) -> Unit)?>(null) }
    var customInitial by remember { mutableIntStateOf(0) }

    val current = settings.getOrNull(selectedFace) ?: MakeupSettings()
    fun update(change: (MakeupSettings) -> MakeupSettings) {
        if (settings.isEmpty()) return
        val v = change(settings[selectedFace.coerceIn(0, settings.size - 1)])
        if (allFaces) settings.indices.forEach { settings[it] = v } else settings[selectedFace] = v
    }

    // Live preview, redrawn shortly after the sliders stop.
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val snapshot = settings.toList()
    LaunchedEffect(work, faces, snapshot) {
        val w = work ?: return@LaunchedEffect
        val f = faces ?: return@LaunchedEffect
        delay(80)
        val made = withContext(Dispatchers.Default) { Makeup.render(w, f, snapshot) }
        // The old preview may still be on screen: left to the GC rather than recycled here.
        preview = made
    }

    val barsPadding = hostSystemBarsPadding()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = StudioSurface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            modifier = Modifier.padding(barsPadding).fillMaxSize().padding(6.dp)
        ) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.action_cancel), tint = Color.LightGray) }
                    Text(stringResource(R.string.mk_title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Button(
                        onClick = { val f = faces; val w = work; if (f != null && w != null) onApply(f, w.width, settings.toList()) },
                        enabled = !faces.isNullOrEmpty() && settings.any { !it.isNeutral },
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                        modifier = Modifier.testTag("btn_makeup_apply")
                    ) { Text(stringResource(R.string.filter_apply), fontWeight = FontWeight.Bold) }
                }

                // Picture; hold the compare button to see it without makeup.
                Box(
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    val shown = if (comparing) work else (preview ?: work)
                    if (shown != null && !shown.isRecycled) {
                        Image(shown.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                    when {
                        work == null || faces == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = IndigoPrimary)
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.mk_finding_faces), color = Color.White, fontSize = 12.sp)
                        }
                    }
                    Surface(
                        shape = CircleShape, color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(44.dp).testTag("btn_makeup_compare")
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(); comparing = true
                                    waitForUpOrCancellation(); comparing = false
                                }
                            }
                    ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Compare, stringResource(R.string.mk_compare), tint = Color.White) } }
                }

                val found = faces
                if (found != null && found.isEmpty()) {
                    Text(stringResource(R.string.mk_no_face), color = AmberAccent, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                    return@Column
                }
                if (found != null && found.isNotEmpty() && !found[0].precise) {
                    Text(stringResource(R.string.mk_basic_mode), color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                }
                // Faces.
                if (found != null && found.size > 1) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = allFaces, onClick = { allFaces = !allFaces }, label = { Text(stringResource(R.string.mk_all_faces), fontSize = 11.sp) })
                        Spacer(Modifier.width(6.dp))
                        found.indices.forEach { i ->
                            FilterChip(
                                selected = !allFaces && selectedFace == i,
                                onClick = { allFaces = false; selectedFace = i },
                                label = { Text(stringResource(R.string.mk_face_n, i + 1), fontSize = 11.sp) },
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                    }
                }
                // Sections.
                ScrollableTabRow(
                    selectedTabIndex = tab.ordinal, edgePadding = 0.dp, containerColor = StudioSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    MakeupTab.values().forEach { t ->
                        Tab(selected = tab == t, onClick = { tab = t }, text = { Text(stringResource(t.label), fontSize = 12.sp) },
                            modifier = Modifier.testTag("mk_tab_${t.name.lowercase()}"))
                    }
                }
                Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
                    fun pick(initial: Int, set: (Int) -> Unit) { customInitial = initial; customFor = set }
                    when (tab) {
                        MakeupTab.LOOKS -> {
                            Text(stringResource(R.string.mk_looks_hint), color = Color.Gray, fontSize = 11.sp)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val looks = listOf(
                                    Makeup.Look.NATURAL to R.string.mk_look_natural, Makeup.Look.GLAM to R.string.mk_look_glam,
                                    Makeup.Look.SMOKEY to R.string.mk_look_smokey, Makeup.Look.NUDE to R.string.mk_look_nude,
                                    Makeup.Look.BOLD_RED to R.string.mk_look_bold_red
                                )
                                looks.forEach { (look, label) ->
                                    OutlinedButton(onClick = { update { Makeup.look(look) } }, modifier = Modifier.testTag("mk_look_${look.name.lowercase()}")) {
                                        Text(stringResource(label), fontSize = 12.sp)
                                    }
                                }
                            }
                            OutlinedButton(onClick = { update { MakeupSettings() } }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.mk_remove_all), fontSize = 12.sp)
                            }
                        }
                        MakeupTab.LIPS -> {
                            MkSlider(R.string.mk_lip_color, current.lipAmount) { v -> update { it.copy(lipAmount = v) } }
                            Swatches(LIP_COLORS, current.lipColor, { c -> update { it.copy(lipColor = c, lipAmount = if (it.lipAmount == 0f) 50f else it.lipAmount) } }) {
                                pick(current.lipColor) { c -> update { it.copy(lipColor = c, lipAmount = if (it.lipAmount == 0f) 50f else it.lipAmount) } }
                            }
                            MkSlider(R.string.mk_lip_gloss, current.lipGloss) { v -> update { it.copy(lipGloss = v) } }
                        }
                        MakeupTab.CHEEKS -> {
                            MkSlider(R.string.mk_blush, current.blushAmount) { v -> update { it.copy(blushAmount = v) } }
                            Swatches(BLUSH_COLORS, current.blushColor, { c -> update { it.copy(blushColor = c, blushAmount = if (it.blushAmount == 0f) 40f else it.blushAmount) } }) {
                                pick(current.blushColor) { c -> update { it.copy(blushColor = c) } }
                            }
                            MkSlider(R.string.mk_contour, current.contour) { v -> update { it.copy(contour = v) } }
                            MkSlider(R.string.mk_highlight, current.highlight) { v -> update { it.copy(highlight = v) } }
                        }
                        MakeupTab.EYES -> {
                            MkSlider(R.string.mk_eyeshadow, current.shadowAmount) { v -> update { it.copy(shadowAmount = v) } }
                            Swatches(SHADOW_COLORS, current.shadowColor, { c -> update { it.copy(shadowColor = c, shadowAmount = if (it.shadowAmount == 0f) 50f else it.shadowAmount) } }) {
                                pick(current.shadowColor) { c -> update { it.copy(shadowColor = c) } }
                            }
                            MkSlider(R.string.mk_shadow_height, current.shadowHeight) { v -> update { it.copy(shadowHeight = v) } }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 4.dp))
                            MkSlider(R.string.mk_eyeliner, current.linerAmount) { v -> update { it.copy(linerAmount = v) } }
                            Swatches(LINER_COLORS, current.linerColor, { c -> update { it.copy(linerColor = c, linerAmount = if (it.linerAmount == 0f) 70f else it.linerAmount) } }) {
                                pick(current.linerColor) { c -> update { it.copy(linerColor = c) } }
                            }
                            MkSlider(R.string.mk_liner_thickness, current.linerThickness) { v -> update { it.copy(linerThickness = v) } }
                            MkSlider(R.string.mk_liner_wing, current.linerWing) { v -> update { it.copy(linerWing = v) } }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 4.dp))
                            MkSlider(R.string.mk_mascara, current.mascara) { v -> update { it.copy(mascara = v) } }
                            MkSlider(R.string.mk_lash_length, current.lashLength) { v -> update { it.copy(lashLength = v) } }
                        }
                        MakeupTab.BROWS_IRIS -> {
                            MkSlider(R.string.mk_brows, current.browAmount) { v -> update { it.copy(browAmount = v) } }
                            Swatches(BROW_COLORS, current.browColor, { c -> update { it.copy(browColor = c, browAmount = if (it.browAmount == 0f) 40f else it.browAmount) } }) {
                                pick(current.browColor) { c -> update { it.copy(browColor = c) } }
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 4.dp))
                            MkSlider(R.string.mk_eye_color, current.eyeColorAmount) { v -> update { it.copy(eyeColorAmount = v) } }
                            Swatches(EYE_COLORS, current.eyeColor, { c -> update { it.copy(eyeColor = c, eyeColorAmount = if (it.eyeColorAmount == 0f) 60f else it.eyeColorAmount) } }) {
                                pick(current.eyeColor) { c -> update { it.copy(eyeColor = c) } }
                            }
                        }
                        MakeupTab.TEETH -> {
                            MkSlider(R.string.mk_teeth, current.teeth) { v -> update { it.copy(teeth = v) } }
                            Text(stringResource(R.string.mk_teeth_hint), color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    customFor?.let { set ->
        CustomColorPickerDialog(
            initialColorHex = customInitial,
            onDismiss = { customFor = null },
            onColorConfirmed = { c -> set(c and 0xFFFFFF); customFor = null }
        )
    }
}

@Composable
private fun MkSlider(label: Int, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(label), color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${value.roundToInt()}", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = IndigoPrimary, activeTrackColor = IndigoPrimary),
            modifier = Modifier.height(32.dp)
        )
    }
}

@Composable
private fun Swatches(colors: List<Int>, selected: Int, onPick: (Int) -> Unit, onCustom: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        colors.forEach { c ->
            val isSel = (c and 0xFFFFFF) == (selected and 0xFFFFFF)
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or c))
                    .border(if (isSel) 3.dp else 1.dp, if (isSel) Color.White else Color.White.copy(alpha = 0.25f), CircleShape)
                    .clickable { onPick(c) }
            )
        }
        Box(
            Modifier.size(30.dp).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape).clickable { onCustom() },
            contentAlignment = Alignment.Center
        ) { Text("+", color = Color.White, fontSize = 16.sp) }
    }
}
