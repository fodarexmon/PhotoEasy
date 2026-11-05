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
    onApplyProEffect: ((ProFilterType, Float, Float) -> Unit)? = null,
    hasActiveSelection: Boolean = false,
    onClearSelection: (() -> Unit)? = null,
    /** Longest side (px) of the image the Pro effect is applied to; 0 = unknown (no scaling). */
    fullResMaxDimension: Int = 0,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(FilterPanelTab.PRESETS) }
    var previews by remember { mutableStateOf<Map<FilterType, Bitmap>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var previewFilter by remember { mutableStateOf(selectedFilter) }

    // Pro effects state
    var selectedProEffect by remember { mutableStateOf(ProFilterType.GAUSSIAN_BLUR) }
    var param1 by remember { mutableFloatStateOf(15f) }
    var param2 by remember { mutableFloatStateOf(20f) }
    var proPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var proLoading by remember { mutableStateOf(false) }

    val compareInteraction = remember { MutableInteractionSource() }
    val comparingOriginal by compareInteraction.collectIsPressedAsState()
    LaunchedEffect(comparingOriginal) { onCompareChange(comparingOriginal) }

    // Generate preset previews
    LaunchedEffect(sourceBitmap, adjustments) {
        val src = sourceBitmap ?: return@LaunchedEffect
        loading = true
        val map = withContext(Dispatchers.Default) {
            val thumb = ImageProcessor.scaleBitmapToMaxDimension(src, 180)
            val adjusted = ImageProcessor.applyAdjustments(thumb, adjustments)
            val result = linkedMapOf<FilterType, Bitmap>()
            FilterType.values().forEach { filter ->
                val filtered = ImageProcessor.applyFilter(adjusted, filter)
                result[filter] = if (filtered === adjusted) adjusted.copy(Bitmap.Config.ARGB_8888, false) else filtered
            }
            if (adjusted !== thumb && !adjusted.isRecycled) adjusted.recycle()
            if (!thumb.isRecycled) thumb.recycle()
            result
        }
        previews.values.forEach { if (!it.isRecycled) it.recycle() }
        previews = map
        loading = false
    }

    // Generate Pro Effect live preview
    LaunchedEffect(selectedProEffect, param1, param2, sourceBitmap, fullResMaxDimension) {
        val src = sourceBitmap ?: return@LaunchedEffect
        proLoading = true
        delay(120) // Debounce
        val preview = withContext(Dispatchers.Default) {
            val thumb = ImageProcessor.scaleBitmapToMaxDimension(src, 240)
            // The effect is applied to the full-resolution image with the same pixel radius, so the
            // thumbnail must use a proportionally smaller radius or it looks far stronger than the result.
            val ratio = if (fullResMaxDimension > 0) {
                maxOf(thumb.width, thumb.height).toFloat() / fullResMaxDimension.toFloat()
            } else 1f
            val (p1, p2) = ImageProcessor.scaleProEffectParams(selectedProEffect, param1, param2, ratio)
            val result = ImageProcessor.applyProEffect(thumb, selectedProEffect, p1, p2)
            if (result !== thumb && !thumb.isRecycled) thumb.recycle()
            result
        }
        proPreviewBitmap?.let { if (!it.isRecycled) it.recycle() }
        proPreviewBitmap = preview
        proLoading = false
    }

    DisposableEffect(Unit) {
        onDispose {
            previews.values.forEach { if (!it.isRecycled) it.recycle() }
            proPreviewBitmap?.let { if (!it.isRecycled) it.recycle() }
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
            // Preset Filters list
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(FilterType.values().toList(), key = { it.name }) { filter ->
                    val selected = filter == previewFilter
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(94.dp)
                            .clickable {
                                previewFilter = filter
                                onPreviewFilter(filter)
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
                            previews[filter]?.takeIf { !it.isRecycled }?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = stringResource(filter.resId),
                                    modifier = Modifier.fillMaxSize().padding(3.dp),
                                    contentScale = ContentScale.Fit
                                )
                            } ?: if (loading) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Text("…", color = Color.Gray)
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            stringResource(filter.resId),
                            color = if (selected) IndigoPrimary else Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }

            if (previewFilter != FilterType.NONE) {
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
                onClick = {},
                interactionSource = compareInteraction,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            ) {
                Text(stringResource(R.string.filter_original_compare), fontSize = 12.sp)
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
                            selectedProEffect = effect
                            // Reset default parameters for the effect
                            when (effect) {
                                ProFilterType.GAUSSIAN_BLUR -> { param1 = 15f; param2 = 0f }
                                ProFilterType.SURFACE_BLUR -> { param1 = 10f; param2 = 25f }
                                ProFilterType.OIL_PAINT -> { param1 = 5f; param2 = 12f }
                                ProFilterType.SHARPEN -> { param1 = 120f; param2 = 1.5f }
                                ProFilterType.NOISE_REDUCTION -> { param1 = 3f; param2 = 15f }
                                ProFilterType.MOTION_BLUR -> { param1 = 20f; param2 = 0f }
                                ProFilterType.RADIAL_BLUR -> { param1 = 25f; param2 = 0f }
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

            Spacer(Modifier.height(10.dp))

            // Live Preview Thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF14161C))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                proPreviewBitmap?.takeIf { !it.isRecycled }?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Pro Effect Preview",
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                if (proLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), color = IndigoPrimary, strokeWidth = 2.5.dp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Sliders depending on effect
            when (selectedProEffect) {
                ProFilterType.GAUSSIAN_BLUR -> {
                    ProEffectSlider(
                        label = stringResource(R.string.effect_radius),
                        value = param1,
                        valueRange = 1f..60f,
                        unit = "px",
                        onValueChange = { param1 = it }
                    )
                }

                ProFilterType.SURFACE_BLUR -> {
                    ProEffectSlider(
                        label = stringResource(R.string.effect_radius),
                        value = param1,
                        valueRange = 1f..25f,
                        unit = "px",
                        onValueChange = { param1 = it }
                    )
                    ProEffectSlider(
                        label = stringResource(R.string.effect_threshold),
                        value = param2,
                        valueRange = 5f..80f,
                        unit = "",
                        onValueChange = { param2 = it }
                    )
                }

                ProFilterType.OIL_PAINT -> {
                    ProEffectSlider(
                        label = stringResource(R.string.effect_radius),
                        value = param1,
                        valueRange = 1f..12f,
                        unit = "px",
                        onValueChange = { param1 = it }
                    )
                    ProEffectSlider(
                        label = stringResource(R.string.effect_levels),
                        value = param2,
                        valueRange = 4f..32f,
                        unit = "",
                        onValueChange = { param2 = it }
                    )
                }

                ProFilterType.SHARPEN -> {
                    ProEffectSlider(
                        label = stringResource(R.string.effect_amount),
                        value = param1,
                        valueRange = 20f..300f,
                        unit = "%",
                        onValueChange = { param1 = it }
                    )
                    ProEffectSlider(
                        label = stringResource(R.string.effect_radius),
                        value = param2,
                        valueRange = 0.5f..5f,
                        unit = "px",
                        onValueChange = { param2 = it }
                    )
                }

                ProFilterType.NOISE_REDUCTION -> {
                    ProEffectSlider(
                        label = stringResource(R.string.effect_strength),
                        value = param1,
                        valueRange = 1f..8f,
                        unit = "",
                        onValueChange = { param1 = it }
                    )
                    ProEffectSlider(
                        label = stringResource(R.string.effect_threshold),
                        value = param2,
                        valueRange = 5f..50f,
                        unit = "",
                        onValueChange = { param2 = it }
                    )
                }

                ProFilterType.MOTION_BLUR -> {
                    ProEffectSlider(
                        label = stringResource(R.string.effect_distance),
                        value = param1,
                        valueRange = 2f..60f,
                        unit = "px",
                        onValueChange = { param1 = it }
                    )
                    ProEffectSlider(
                        label = stringResource(R.string.effect_angle),
                        value = param2,
                        valueRange = 0f..360f,
                        unit = "°",
                        onValueChange = { param2 = it }
                    )
                }

                ProFilterType.RADIAL_BLUR -> {
                    ProEffectSlider(
                        label = stringResource(R.string.effect_amount),
                        value = param1,
                        valueRange = 5f..60f,
                        unit = "",
                        onValueChange = { param1 = it }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Action Buttons: Apply to Layer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_close), fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        onApplyProEffect?.invoke(selectedProEffect, param1, param2)
                    },
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
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White, fontSize = 12.sp)
            Text(
                "${value.toInt()}$unit",
                color = AmberAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = AmberAccent,
                activeTrackColor = AmberAccent
            )
        )
    }
}
