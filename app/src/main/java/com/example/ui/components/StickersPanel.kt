package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.engine.StickerCategory
import com.example.engine.StickerDef
import com.example.engine.StickerLibrary
import com.example.engine.StickerStyle
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val STICKER_COLORS = listOf(
    null, 0xFFFFFFFF, 0xFF111827, 0xFFEF4444, 0xFFF97316, 0xFFF59E0B, 0xFFFACC15, 0xFF22C55E,
    0xFF14B8A6, 0xFF06B6D4, 0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899, 0xFFA16207
).map { it?.toInt() }

/**
 * Sticker library: category tabs, a grid of previews, a colour for vector stickers (the first
 * swatch keeps each sticker's own colour) and the white border / shadow style. A tap adds the
 * sticker as a new layer.
 */
@Composable
fun StickersPanel(
    onAddSticker: (StickerDef, StickerStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var category by rememberSaveable { mutableStateOf(StickerCategory.EMOJI) }
    var color by rememberSaveable { mutableStateOf<Int?>(null) }
    var outline by rememberSaveable { mutableStateOf(false) }
    var shadow by rememberSaveable { mutableStateOf(false) }
    val stickers = remember(category) { StickerLibrary.byCategory(category) }

    // Previews of the current category (and colour), rendered off the main thread.
    val previews by produceState<Map<String, Bitmap>>(emptyMap(), category, color) {
        val out = HashMap<String, Bitmap>()
        for (def in stickers) {
            val bmp = withContext(Dispatchers.Default) {
                try { StickerLibrary.preview(context, def, 144, if (def.tintable) color else null) } catch (e: Exception) { null }
            } ?: continue
            out[def.id] = bmp
            if (out.size % 12 == 0 || out.size == stickers.size) value = HashMap(out)
        }
        value = out
    }

    Column(modifier.fillMaxWidth().background(StudioSurface).padding(horizontal = 12.dp, vertical = 8.dp)) {
        ScrollableTabRow(
            selectedTabIndex = category.ordinal,
            containerColor = StudioSurfaceVariant,
            contentColor = Color.White,
            edgePadding = 0.dp
        ) {
            StickerCategory.values().forEach { c ->
                Tab(
                    selected = c == category,
                    onClick = { category = c },
                    text = { Text(stringResource(c.labelRes), fontSize = 12.sp, fontWeight = if (c == category) FontWeight.Bold else FontWeight.Normal) },
                    modifier = Modifier.testTag("sticker_tab_${c.name.lowercase()}")
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        if (category != StickerCategory.EMOJI) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.sticker_color), color = Color.LightGray, fontSize = 12.sp)
                STICKER_COLORS.forEach { c ->
                    val sel = c == color
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(if (c == null) StudioSurfaceVariant else Color(c))
                            .border(if (sel) 3.dp else 1.dp, if (sel) Color.White else Color.Gray, CircleShape)
                            .clickable { color = c },
                        contentAlignment = Alignment.Center
                    ) {
                        if (c == null) Text("A", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = outline, onClick = { outline = !outline }, label = { Text(stringResource(R.string.sticker_border), fontSize = 12.sp) })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = shadow, onClick = { shadow = !shadow }, label = { Text(stringResource(R.string.sticker_shadow), fontSize = 12.sp) })
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp)
        ) {
            items(stickers, key = { it.id }) { def ->
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(StudioSurfaceVariant)
                        .clickable { onAddSticker(def, StickerStyle(color = if (def.tintable) color else null, outline = outline, shadow = shadow)) }
                        .padding(6.dp)
                        .testTag("sticker_${def.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = previews[def.id]
                    if (bmp != null && !bmp.isRecycled) {
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                    } else {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = IndigoPrimary)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.sticker_hint), color = Color.Gray, fontSize = 11.sp)
    }
}
