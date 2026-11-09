package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.ColorAdjustments
import com.example.data.model.FilterType
import com.example.data.model.ProFilterType
import com.example.engine.ImageProcessor
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Categories of the preset browser. */
enum class FilterBrowserCategory(val labelRes: Int) {
    ALL(R.string.filter_cat_all),
    FAVORITES(R.string.filter_cat_favorites),
    PORTRAIT(R.string.filter_cat_portrait),
    LANDSCAPE(R.string.filter_cat_landscape),
    FILM(R.string.filter_cat_film),
    BLACK_WHITE(R.string.filter_cat_bw),
    CREATIVE(R.string.filter_cat_creative),
    LUTS(R.string.filter_cat_luts),
    MY_LOOKS(R.string.filter_cat_my_looks)
}

/** One card in the preset browser. */
private data class FilterEntry(
    val key: String,
    val label: String,
    val filter: FilterType? = null,
    val lut: com.example.engine.LutInfo? = null,
    val preset: com.example.engine.CustomPreset? = null
)

enum class FilterPanelTab(val labelRes: Int) {
    PRESETS(R.string.filter_tab_presets),
    PRO_EFFECTS(R.string.filter_tab_pro_effects)
}

@Composable
fun FiltersPanel(
    selectedFilter: FilterType,
    filterIntensity: Float = 1.0f,
    sourceBitmap: Bitmap?,
    adjustments: ColorAdjustments,
    onPreviewFilter: (FilterType) -> Unit,
    onIntensityChange: (Float) -> Unit = {},
    onCompareChange: (Boolean) -> Unit = {},
    onApply: () -> Unit,
    onCancel: () -> Unit,
    onApplyProEffect: ((ProFilterType, com.example.engine.ProEffectParams) -> Unit)? = null,
    hasActiveSelection: Boolean = false,
    onClearSelection: (() -> Unit)? = null,
    /** Shows the Pro effect live on the main canvas; null clears the preview. */
    onPreviewProEffect: (ProFilterType?, com.example.engine.ProEffectParams?) -> Unit = { _, _ -> },
    /** Centre of radial blur / tilt-shift, normalised to the layer image (set by tapping the image). */
    proCenter: Pair<Float, Float> = 0.5f to 0.5f,
    /** True while the selected effect wants canvas taps to place its centre. */
    onProCenterPickChange: (Boolean) -> Unit = {},
    /** Effects only apply to image layers; other layers show a hint and Apply is disabled. */
    isImageLayer: Boolean = true,
    selectedLutId: String? = null,
    luts: List<com.example.engine.LutInfo> = emptyList(),
    customPresets: List<com.example.engine.CustomPreset> = emptyList(),
    favorites: Set<String> = emptySet(),
    onPreviewLut: (String) -> Unit = {},
    onApplyCustomPreset: (com.example.engine.CustomPreset) -> Unit = {},
    onToggleFavorite: (String) -> Unit = {},
    onImportLut: (android.net.Uri) -> Unit = {},
    onDeleteLut: (String) -> Unit = {},
    onSaveLook: (String) -> Unit = {},
    onDeleteCustomPreset: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(FilterPanelTab.PRESETS) }
    // Thumbnails keyed "f:<FilterType>", "lut:<id>" or "preset:<id>".
    var previews by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var previewFilter by remember { mutableStateOf(selectedFilter) }
    var selectedKey by remember { mutableStateOf(if (selectedLutId != null) "lut:$selectedLutId" else "f:${selectedFilter.name}") }
    var category by remember { mutableStateOf(FilterBrowserCategory.ALL) }
    var showSaveLookDialog by remember { mutableStateOf(false) }
    val lutPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImportLut(uri) }

    // Pro effects state
    var selectedProEffect by remember { mutableStateOf(ProFilterType.GAUSSIAN_BLUR) }
    var proParams by remember { mutableStateOf(com.example.engine.ProEffects.defaults(ProFilterType.GAUSSIAN_BLUR)) }
    val effectiveProParams = proParams.copy(centerX = proCenter.first, centerY = proCenter.second)

    // "Original": a tap toggles the before view on/off; press and hold shows it only while held.
    val compareInteraction = remember { MutableInteractionSource() }
    val holdingCompare by compareInteraction.collectIsPressedAsState()
    var showingOriginal by remember { mutableStateOf(false) }
    var comparePressStart by remember { mutableStateOf(0L) }
    LaunchedEffect(compareInteraction) {
        compareInteraction.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.PressInteraction.Press) {
                comparePressStart = System.currentTimeMillis()
            }
        }
    }
    val comparingOriginal = showingOriginal || holdingCompare
    LaunchedEffect(comparingOriginal) { onCompareChange(comparingOriginal) }
    // Choosing another look always shows the result again.
    LaunchedEffect(selectedFilter, selectedLutId, filterIntensity) { showingOriginal = false }

    // Generate preset previews. Robust for every image: the thumbnail is a centre crop matching
    // the tile shape (so very wide / tall photos still fill it), one failing look never blanks the
    // others, and previous thumbnails are not recycled while they may still be on screen.
    LaunchedEffect(sourceBitmap, adjustments, luts, customPresets) {
        val src = sourceBitmap?.takeIf { !it.isRecycled }
        if (src == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val map = withContext(Dispatchers.Default) {
            val result = linkedMapOf<String, Bitmap>()
            val thumb = try {
                tileThumbnail(src, 200)
            } catch (e: Throwable) {
                null
            } ?: return@withContext result
            val adjusted = try { ImageProcessor.applyAdjustments(thumb, adjustments) } catch (e: Throwable) { thumb }
            fun keep(key: String, base: Bitmap, make: () -> Bitmap) {
                try {
                    val out = make()
                    result[key] = if (out === base) base.copy(Bitmap.Config.ARGB_8888, false) else out
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // This look could not be previewed; the others still are.
                }
            }
            FilterType.values().forEach { filter -> keep("f:${filter.name}", adjusted) { ImageProcessor.applyFilter(adjusted, filter) } }
            luts.forEach { info ->
                com.example.engine.FilterLibrary.lut(info.id)?.let { lut -> keep("lut:${info.id}", adjusted) { ImageProcessor.applyLut(adjusted, lut) } }
            }
            // A saved look replaces the adjustments, so it is previewed from the plain thumbnail.
            customPresets.forEach { p ->
                keep("preset:${p.id}", thumb) {
                    val withAdj = ImageProcessor.applyAdjustments(thumb, p.adjustments)
                    val lut = com.example.engine.FilterLibrary.lut(p.lutId)
                    val look = if (lut != null) ImageProcessor.applyLut(withAdj, lut, p.intensity) else ImageProcessor.applyFilter(withAdj, p.filter, p.intensity)
                    if (look !== withAdj && withAdj !== thumb && !withAdj.isRecycled) withAdj.recycle()
                    if (look === withAdj && withAdj !== thumb) withAdj else look
                }
            }
            if (adjusted !== thumb && !adjusted.isRecycled) adjusted.recycle()
            if (!thumb.isRecycled) thumb.recycle()
            result
        }
        previews = map
        loading = false
    }

    // Pro effects are previewed live on the main image (scoped to the selection, like the real
    // effect). Slider drags are debounced so the canvas is not re-rendered for every tiny step.
    val latestPreviewProEffect by rememberUpdatedState(onPreviewProEffect)
    LaunchedEffect(activeTab, selectedProEffect, effectiveProParams, sourceBitmap) {
        if (activeTab == FilterPanelTab.PRO_EFFECTS) {
            delay(150)
            latestPreviewProEffect(selectedProEffect, effectiveProParams)
        } else {
            latestPreviewProEffect(null, null)
        }
    }
    val latestCenterPick by rememberUpdatedState(onProCenterPickChange)
    LaunchedEffect(activeTab, selectedProEffect) {
        latestCenterPick(activeTab == FilterPanelTab.PRO_EFFECTS && com.example.engine.ProEffects.usesCenter(selectedProEffect))
    }

    DisposableEffect(Unit) {
        onDispose {
            previews.values.forEach { if (!it.isRecycled) it.recycle() }
            latestPreviewProEffect(null, null)
            latestCenterPick(false)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .background(StudioSurface)
            .padding(12.dp)
    ) {
        // Selection active indicator
        if (hasActiveSelection) {
            Surface(
                color = IndigoPrimary.copy(alpha = 0.22f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, IndigoPrimary.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                        Icon(Icons.Default.SelectAll, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.status_selection_filters_scope),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (onClearSelection != null) {
                        TextButton(
                            onClick = onClearSelection,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(stringResource(R.string.select_clear), color = AmberAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Tab Selector (Presets vs Pro Blur & Effects)
        TabRow(
            selectedTabIndex = activeTab.ordinal,
            containerColor = StudioSurfaceVariant,
            contentColor = Color.White,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        ) {
            FilterPanelTab.values().forEach { tab ->
                val selected = tab == activeTab
                Tab(
                    selected = selected,
                    onClick = { activeTab = tab },
                    text = {
                        Text(
                            stringResource(tab.labelRes),
                            color = if (selected) IndigoPrimary else Color.LightGray,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp
                        )
                    },
                    modifier = Modifier.testTag("tab_filter_${tab.name.lowercase()}")
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (activeTab == FilterPanelTab.PRESETS) {
            // Category chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(FilterBrowserCategory.values().toList(), key = { it.name }) { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { category = cat },
                        label = { Text(stringResource(cat.labelRes), fontSize = 11.sp) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            val filterLabels = FilterType.values().associateWith { stringResource(it.resId) }
            val entries = remember(category, favorites, luts, customPresets, filterLabels) {
                fun filterEntries(types: List<FilterType>) = types.map { FilterEntry("f:${it.name}", filterLabels[it] ?: it.displayName, filter = it) }
                val all = FilterType.values().toList()
                val lutEntries = luts.map { FilterEntry("lut:${it.id}", it.name, lut = it) }
                val presetEntries = customPresets.map { FilterEntry("preset:${it.id}", it.name, preset = it) }
                when (category) {
                    FilterBrowserCategory.ALL -> filterEntries(all) + lutEntries + presetEntries
                    FilterBrowserCategory.FAVORITES ->
                        (filterEntries(all) + lutEntries + presetEntries).filter { it.key in favorites || it.filter == FilterType.NONE }
                    FilterBrowserCategory.LUTS -> lutEntries
                    FilterBrowserCategory.MY_LOOKS -> presetEntries
                    else -> {
                        val wanted = when (category) {
                            FilterBrowserCategory.PORTRAIT -> com.example.engine.FilterCategory.PORTRAIT
                            FilterBrowserCategory.LANDSCAPE -> com.example.engine.FilterCategory.LANDSCAPE
                            FilterBrowserCategory.FILM -> com.example.engine.FilterCategory.FILM
                            FilterBrowserCategory.BLACK_WHITE -> com.example.engine.FilterCategory.BLACK_WHITE
                            else -> com.example.engine.FilterCategory.CREATIVE
                        }
                        filterEntries(listOf(FilterType.NONE) + all.filter { it != FilterType.NONE && com.example.engine.FilterRecipes.categoryOf(it) == wanted })
                    }
                }
            }

            if (entries.isEmpty()) {
                Text(
                    text = stringResource(
                        when (category) {
                            FilterBrowserCategory.LUTS -> R.string.filter_luts_empty
                            FilterBrowserCategory.MY_LOOKS -> R.string.filter_looks_empty
                            else -> R.string.filter_favorites_empty
                        }
                    ),
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 14.dp)
                )
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries, key = { it.key }) { entry ->
                    val selected = entry.key == selectedKey
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(94.dp)
                            .clickable {
                                selectedKey = entry.key
                                when {
                                    entry.filter != null -> { previewFilter = entry.filter; onPreviewFilter(entry.filter) }
                                    entry.lut != null -> { previewFilter = FilterType.NONE; onPreviewLut(entry.lut.id) }
                                    entry.preset != null -> { previewFilter = entry.preset.filter; onApplyCustomPreset(entry.preset) }
                                }
                            }
                            .padding(vertical = 2.dp)
                    ) {
                        Box(
                            Modifier
                                .size(width = 88.dp, height = 76.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(StudioSurfaceVariant)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) IndigoPrimary else Color.White.copy(alpha = .12f),
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            previews[entry.key]?.takeIf { !it.isRecycled }?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = entry.label,
                                    modifier = Modifier.fillMaxSize().padding(3.dp),
                                    contentScale = ContentScale.Fit
                                )
                            } ?: if (loading) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Text("…", color = Color.Gray)
                            }
                            if (entry.filter != FilterType.NONE) {
                                val fav = entry.key in favorites
                                Icon(
                                    imageVector = if (fav) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = stringResource(R.string.filter_toggle_favorite),
                                    tint = if (fav) AmberAccent else Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(Color.Black.copy(alpha = 0.35f))
                                        .clickable { onToggleFavorite(entry.key) }
                                        .padding(2.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            entry.label,
                            color = if (selected) IndigoPrimary else Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }

            // Library actions: import a LUT, save the current look, delete the selected user item.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { showSaveLookDialog = true }, modifier = Modifier.heightIn(min = 40.dp)) {
                    Text(stringResource(R.string.filter_save_look), fontSize = 11.sp)
                }
                OutlinedButton(onClick = { lutPicker.launch(arrayOf("*/*")) }, modifier = Modifier.heightIn(min = 40.dp)) {
                    Text(stringResource(R.string.filter_import_lut), fontSize = 11.sp)
                }
                val selectedUserItem = entries.firstOrNull { it.key == selectedKey && (it.lut != null || it.preset != null) }
                if (selectedUserItem != null) {
                    TextButton(
                        onClick = {
                            selectedUserItem.lut?.let { onDeleteLut(it.id) }
                            selectedUserItem.preset?.let { onDeleteCustomPreset(it.id) }
                            selectedKey = "f:${FilterType.NONE.name}"
                            previewFilter = FilterType.NONE
                            onPreviewFilter(FilterType.NONE)
                        },
                        modifier = Modifier.heightIn(min = 40.dp)
                    ) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }
                }
            }

            if (showSaveLookDialog) {
                var lookName by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { showSaveLookDialog = false },
                    title = { Text(stringResource(R.string.filter_save_look)) },
                    text = {
                        OutlinedTextField(
                            value = lookName,
                            onValueChange = { if (it.length <= 40) lookName = it },
                            label = { Text(stringResource(R.string.filter_look_name)) },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { onSaveLook(lookName); showSaveLookDialog = false },
                            enabled = lookName.isNotBlank()
                        ) { Text(stringResource(R.string.action_save)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSaveLookDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                    }
                )
            }

            if (selectedKey != "f:${FilterType.NONE.name}" && !selectedKey.startsWith("preset:")) {
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.filter_intensity), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("${(filterIntensity * 100).toInt()}%", color = IndigoPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = filterIntensity,
                        onValueChange = onIntensityChange,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = IndigoPrimary, activeTrackColor = IndigoPrimary)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    // A long press was a hold-to-compare; only a quick tap toggles.
                    if (System.currentTimeMillis() - comparePressStart < 350L) showingOriginal = !showingOriginal
                },
                interactionSource = compareInteraction,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("btn_filter_compare"),
                colors = if (comparingOriginal) {
                    ButtonDefaults.outlinedButtonColors(containerColor = AmberAccent.copy(alpha = 0.18f), contentColor = AmberAccent)
                } else ButtonDefaults.outlinedButtonColors()
            ) {
                Text(
                    stringResource(if (comparingOriginal) R.string.filter_showing_original else R.string.filter_original_compare),
                    fontSize = 12.sp,
                    fontWeight = if (comparingOriginal) FontWeight.Bold else FontWeight.Normal
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.filter_cancel), fontSize = 12.sp)
                }
                Button(
                    onClick = onApply,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.filter_apply), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        } else {
            // PRO BLUR & EFFECTS
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ProFilterType.values().toList(), key = { it.name }) { effect ->
                    val isSelected = effect == selectedProEffect
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            if (effect != selectedProEffect) {
                                selectedProEffect = effect
                                proParams = com.example.engine.ProEffects.defaults(effect)
                            }
                        },
                        label = {
                            Text(
                                stringResource(effect.resId),
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.heightIn(min = 38.dp).testTag("chip_pro_${effect.name.lowercase()}")
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    when {
                        !isImageLayer -> R.string.effect_needs_image_layer
                        com.example.engine.ProEffects.usesCenter(selectedProEffect) -> R.string.effect_tap_center_hint
                        selectedProEffect == ProFilterType.HIGH_PASS -> R.string.effect_high_pass_hint
                        else -> R.string.effect_live_preview_hint
                    }
                ),
                color = if (isImageLayer) Color.White.copy(alpha = 0.6f) else AmberAccent,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(8.dp))

            val p = proParams
            fun upd(change: (com.example.engine.ProEffectParams) -> com.example.engine.ProEffectParams) { proParams = change(proParams) }
            when (selectedProEffect) {
                ProFilterType.GAUSSIAN_BLUR -> {
                    ProEffectSlider(stringResource(R.string.effect_radius), p.p1, 0.3f..100f, "px") { v -> upd { it.copy(p1 = v) } }
                }
                ProFilterType.SURFACE_BLUR -> {
                    ProEffectSlider(stringResource(R.string.effect_radius), p.p1, 1f..50f, "px") { v -> upd { it.copy(p1 = v) } }
                    ProEffectSlider(stringResource(R.string.effect_threshold), p.p2, 2f..100f, "") { v -> upd { it.copy(p2 = v) } }
                }
                ProFilterType.OIL_PAINT -> {
                    ProEffectSlider(stringResource(R.string.effect_brush_size), p.p1, 1f..12f, "", step = 1f) { v -> upd { it.copy(p1 = v) } }
                    ProEffectSlider(stringResource(R.string.effect_texture), p.p2, 0f..100f, "%") { v -> upd { it.copy(p2 = v) } }
                }
                ProFilterType.SHARPEN -> {
                    ProEffectSlider(stringResource(R.string.effect_amount), p.p1, 0f..500f, "%") { v -> upd { it.copy(p1 = v) } }
                    ProEffectSlider(stringResource(R.string.effect_radius), p.p2, 0.1f..10f, "px") { v -> upd { it.copy(p2 = v) } }
                    ProEffectSlider(stringResource(R.string.effect_threshold), p.p3, 0f..50f, "") { v -> upd { it.copy(p3 = v) } }
                }
                ProFilterType.NOISE_REDUCTION -> {
                    ProEffectSlider(stringResource(R.string.effect_luminance_noise), p.p1, 0f..100f, "%") { v -> upd { it.copy(p1 = v) } }
                    ProEffectSlider(stringResource(R.string.effect_color_noise), p.p2, 0f..100f, "%") { v -> upd { it.copy(p2 = v) } }
                    ProEffectSlider(stringResource(R.string.effect_detail), p.p3, 0f..100f, "%") { v -> upd { it.copy(p3 = v) } }
                }
                ProFilterType.MOTION_BLUR -> {
                    ProEffectSlider(stringResource(R.string.effect_distance), p.p1, 1f..200f, "px") { v -> upd { it.copy(p1 = v) } }
                    ProEffectSlider(stringResource(R.string.effect_angle), p.p2, 0f..360f, "°", step = 1f) { v -> upd { it.copy(p2 = v) } }
                }
                ProFilterType.RADIAL_BLUR -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                        FilterChip(
                            selected = p.mode == 0,
                            onClick = { upd { it.copy(mode = 0) } },
                            label = { Text(stringResource(R.string.effect_zoom), fontSize = 12.sp) },
                            modifier = Modifier.heightIn(min = 38.dp).testTag("chip_radial_zoom")
                        )
                        FilterChip(
                            selected = p.mode == 1,
                            onClick = { upd { it.copy(mode = 1) } },
                            label = { Text(stringResource(R.string.effect_spin), fontSize = 12.sp) },
                            modifier = Modifier.heightIn(min = 38.dp).testTag("chip_radial_spin")
                        )
                    }
                    ProEffectSlider(stringResource(R.string.effect_amount), p.p1, 1f..100f, "%") { v -> upd { it.copy(p1 = v) } }
                }
                ProFilterType.LENS_BLUR -> {
                    ProEffectSlider(stringResource(R.string.effect_radius), p.p1, 1f..60f, "px") { v -> upd { it.copy(p1 = v) } }
                    ProEffectSlider(stringResource(R.string.effect_highlights), p.p2, 0f..100f, "%") { v -> upd { it.copy(p2 = v) } }
                }
                ProFilterType.TILT_SHIFT -> {
                    ProEffectSlider(stringResource(R.string.effect_radius), p.p1, 1f..60f, "px") { v -> upd { it.copy(p1 = v) } }
                    ProEffectSlider(stringResource(R.string.effect_focus_size), p.p2, 2f..90f, "%") { v -> upd { it.copy(p2 = v) } }
                    ProEffectSlider(stringResource(R.string.effect_transition), p.p3, 2f..80f, "%") { v -> upd { it.copy(p3 = v) } }
                    ProEffectSlider(stringResource(R.string.effect_angle), p.angle, -90f..90f, "°", step = 1f) { v -> upd { it.copy(angle = v) } }
                }
                ProFilterType.HIGH_PASS -> {
                    ProEffectSlider(stringResource(R.string.effect_radius), p.p1, 0.3f..50f, "px") { v -> upd { it.copy(p1 = v) } }
                }
            }
            // Every effect can be faded into the original.
            ProEffectSlider(stringResource(R.string.effect_fade), p.fade, 0f..100f, "%", step = 1f) { v -> upd { it.copy(fade = v) } }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_close), fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { proParams = com.example.engine.ProEffects.defaults(selectedProEffect) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("btn_reset_pro_effect")
                ) {
                    Text(stringResource(R.string.effect_reset), fontSize = 12.sp)
                }
                Button(
                    onClick = { onApplyProEffect?.invoke(selectedProEffect, effectiveProParams) },
                    enabled = isImageLayer,
                    modifier = Modifier.weight(1.5f).heightIn(min = 48.dp).testTag("btn_apply_pro_effect"),
                    colors = ButtonDefaults.buttonColors(containerColor = AmberAccent, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.effect_apply_layer), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ProEffectSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    unit: String,
    step: Float = 0f,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White, fontSize = 12.sp)
            val shown = if (step == 0f && kotlin.math.abs(value) < 10f) {
                (kotlin.math.round(value * 10f) / 10f).toString()
            } else kotlin.math.round(value).toInt().toString()
            Text(
                "$shown$unit",
                color = AmberAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { v -> onValueChange(if (step > 0f) kotlin.math.round(v / step) * step else v) },
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = AmberAccent,
                activeTrackColor = AmberAccent
            )
        )
    }
}

/** Centre crop of [src] with the filter tile's shape (88 x 76), scaled to at most [maxDim] px. */
private fun tileThumbnail(src: Bitmap, maxDim: Int): Bitmap {
    val tileAspect = 88f / 76f
    val w = src.width
    val h = src.height
    val cropW: Int
    val cropH: Int
    if (w.toFloat() / h > tileAspect) {
        cropH = h
        cropW = (h * tileAspect).toInt().coerceIn(1, w)
    } else {
        cropW = w
        cropH = (w / tileAspect).toInt().coerceIn(1, h)
    }
    val scale = minOf(1f, maxDim.toFloat() / maxOf(cropW, cropH))
    val outW = (cropW * scale).toInt().coerceAtLeast(1)
    val outH = (cropH * scale).toInt().coerceAtLeast(1)
    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val left = (w - cropW) / 2
    val top = (h - cropH) / 2
    android.graphics.Canvas(out).drawBitmap(
        src,
        android.graphics.Rect(left, top, left + cropW, top + cropH),
        android.graphics.Rect(0, 0, outW, outH),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    )
    return out
}
