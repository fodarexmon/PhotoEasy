package com.example.engine

import android.content.Context
import android.graphics.PointF
import android.net.Uri
import com.example.data.model.ColorAdjustments
import com.example.data.model.FilterType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LutInfo(val id: String, val name: String)

/** A look saved by the user: adjustments + preset filter (or LUT) + intensity. */
data class CustomPreset(
    val id: String,
    val name: String,
    val adjustments: ColorAdjustments,
    val filter: FilterType,
    val intensity: Float,
    val lutId: String?
)

/**
 * User filter library: imported .cube LUTs (files/luts/<id>.cube), saved looks
 * (files/filter_presets.json) and favourite filters (shared preferences). Thread-safe; the
 * renderer only reads LUTs by id.
 */
object FilterLibrary {

    private val lock = Any()
    @Volatile private var loaded = false
    private val luts = ConcurrentHashMap<String, CubeLut>()
    @Volatile private var lutList: List<LutInfo> = emptyList()
    @Volatile private var presetList: List<CustomPreset> = emptyList()

    private fun lutDir(context: Context) = File(context.filesDir, "luts").apply { if (!exists()) mkdirs() }
    private fun presetFile(context: Context) = File(context.filesDir, "filter_presets.json")
    private fun prefs(context: Context) = context.getSharedPreferences("filter_library", Context.MODE_PRIVATE)

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val infos = ArrayList<LutInfo>()
            lutDir(context).listFiles { f -> f.isFile && f.name.endsWith(".cube") }?.sortedBy { it.lastModified() }?.forEach { f ->
                runCatching {
                    val id = f.name.removeSuffix(".cube")
                    val lut = CubeLut.parse(f.readText(), id)
                    luts[id] = lut
                    infos.add(LutInfo(id, lut.title))
                }
            }
            lutList = infos
            presetList = runCatching { readPresets(presetFile(context)) }.getOrDefault(emptyList())
            loaded = true
        }
    }

    fun lut(id: String?): CubeLut? = if (id == null) null else luts[id]
    fun lutInfos(): List<LutInfo> = lutList
    fun presets(): List<CustomPreset> = presetList

    /** Copies a .cube file into the app, validating it first. Throws on invalid files. */
    fun importLut(context: Context, uri: Uri, displayName: String?): LutInfo {
        ensureLoaded(context)
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?.also { require(it.size <= 12 * 1024 * 1024) { "LUT file too large" } }
            ?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException("Cannot read file")
        val fallback = (displayName ?: "LUT").substringBeforeLast('.').ifBlank { "LUT" }
        val lut = CubeLut.parse(text, fallback)
        val id = UUID.randomUUID().toString()
        val stored = if (text.lineSequence().any { it.trim().uppercase().startsWith("TITLE") }) text else "TITLE \"${lut.title}\"\n$text"
        File(lutDir(context), "$id.cube").writeText(stored)
        synchronized(lock) {
            luts[id] = lut
            lutList = lutList + LutInfo(id, lut.title)
        }
        return LutInfo(id, lut.title)
    }

    fun deleteLut(context: Context, id: String) {
        synchronized(lock) {
            File(lutDir(context), "$id.cube").delete()
            luts.remove(id)
            lutList = lutList.filterNot { it.id == id }
        }
    }

    fun savePreset(context: Context, preset: CustomPreset) {
        ensureLoaded(context)
        synchronized(lock) {
            presetList = presetList.filterNot { it.id == preset.id } + preset
            writePresets(presetFile(context), presetList)
        }
    }

    fun deletePreset(context: Context, id: String) {
        ensureLoaded(context)
        synchronized(lock) {
            presetList = presetList.filterNot { it.id == id }
            writePresets(presetFile(context), presetList)
        }
    }

    fun favorites(context: Context): Set<String> = prefs(context).getStringSet("favorites", emptySet())?.toSet() ?: emptySet()

    fun toggleFavorite(context: Context, key: String): Set<String> {
        val current = favorites(context).toMutableSet()
        if (!current.add(key)) current.remove(key)
        prefs(context).edit().putStringSet("favorites", current).apply()
        return current
    }

    // --- JSON ---------------------------------------------------------------------------------

    private fun writePresets(file: File, list: List<CustomPreset>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("filter", p.filter.name)
                put("intensity", p.intensity.toDouble())
                p.lutId?.let { put("lutId", it) }
                put("adjustments", AdjustmentsJson.toJson(p.adjustments))
            })
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun readPresets(file: File): List<CustomPreset> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        val out = ArrayList<CustomPreset>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                CustomPreset(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", "Preset"),
                    adjustments = AdjustmentsJson.fromJson(o.optJSONObject("adjustments")),
                    filter = runCatching { FilterType.valueOf(o.optString("filter", "NONE")) }.getOrDefault(FilterType.NONE),
                    intensity = o.optDouble("intensity", 1.0).toFloat().coerceIn(0f, 1f),
                    lutId = o.optString("lutId", "").ifBlank { null }
                )
            )
        }
        return out
    }
}

/** JSON (de)serialisation of [ColorAdjustments], shared by user presets. */
object AdjustmentsJson {
    private fun ptsToJson(points: List<PointF>) = JSONArray().apply { points.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) } }
    private fun ptsFromJson(arr: JSONArray?, def: List<PointF>): List<PointF> {
        if (arr == null) return def
        val list = (0 until arr.length()).mapNotNull { i -> arr.optJSONArray(i)?.let { PointF(it.optDouble(0).toFloat(), it.optDouble(1).toFloat()) } }
        return if (list.size >= 2) list else def
    }

    fun toJson(a: ColorAdjustments): JSONObject = JSONObject().apply {
        put("brightness", a.brightness.toDouble()); put("contrast", a.contrast.toDouble())
        put("saturation", a.saturation.toDouble()); put("temperature", a.temperature.toDouble())
        put("tint", a.tint.toDouble()); put("exposure", a.exposure.toDouble())
        put("highlights", a.highlights.toDouble()); put("shadows", a.shadows.toDouble())
        put("sharpness", a.sharpness.toDouble()); put("vignette", a.vignette.toDouble())
        put("curvesRgb", ptsToJson(a.curvesRgb)); put("curvesRed", ptsToJson(a.curvesRed))
        put("curvesGreen", ptsToJson(a.curvesGreen)); put("curvesBlue", ptsToJson(a.curvesBlue))
        put("hueShift", a.hueShift.toDouble()); put("hslSaturation", a.hslSaturation.toDouble())
        put("hslLightness", a.hslLightness.toDouble()); put("hslColorRange", a.hslColorRange)
        put("replaceColorEnabled", a.replaceColorEnabled); put("replaceSourceColorHex", a.replaceSourceColorHex)
        put("replaceTolerance", a.replaceTolerance.toDouble()); put("replaceTargetHue", a.replaceTargetHue.toDouble())
        put("replaceTargetSaturation", a.replaceTargetSaturation.toDouble()); put("replaceTargetLightness", a.replaceTargetLightness.toDouble())
        put("whites", a.whites.toDouble()); put("blacks", a.blacks.toDouble()); put("vibrance", a.vibrance.toDouble())
        put("clarity", a.clarity.toDouble()); put("dehaze", a.dehaze.toDouble())
        put("colorizeEnabled", a.colorizeEnabled); put("colorizeHue", a.colorizeHue.toDouble())
        put("colorizeSaturation", a.colorizeSaturation.toDouble()); put("colorizeLightness", a.colorizeLightness.toDouble())
        put("colorReplacements", JSONArray().apply {
            a.colorReplacements.forEach { c ->
                put(JSONObject().apply {
                    put("source", c.sourceColor); put("target", c.targetColor)
                    put("tolerance", c.tolerance.toDouble()); put("softness", c.softness.toDouble())
                    put("saturation", c.saturation.toDouble()); put("lightness", c.lightness.toDouble())
                    put("enabled", c.enabled)
                })
            }
        })
        put("hslBands", JSONObject().apply {
            a.hslBands.forEach { (k, b) -> put(k, JSONArray().put(b.hue.toDouble()).put(b.saturation.toDouble()).put(b.lightness.toDouble()).put(b.width.toDouble())) }
        })
    }

    fun fromJson(o: JSONObject?): ColorAdjustments {
        val d = ColorAdjustments()
        if (o == null) return d
        fun f(key: String, def: Float) = o.optDouble(key, def.toDouble()).toFloat()
        return ColorAdjustments(
            brightness = f("brightness", 0f), contrast = f("contrast", 0f), saturation = f("saturation", 0f),
            temperature = f("temperature", 0f), tint = f("tint", 0f), exposure = f("exposure", 0f),
            highlights = f("highlights", 0f), shadows = f("shadows", 0f), sharpness = f("sharpness", 0f), vignette = f("vignette", 0f),
            curvesRgb = ptsFromJson(o.optJSONArray("curvesRgb"), d.curvesRgb),
            curvesRed = ptsFromJson(o.optJSONArray("curvesRed"), d.curvesRed),
            curvesGreen = ptsFromJson(o.optJSONArray("curvesGreen"), d.curvesGreen),
            curvesBlue = ptsFromJson(o.optJSONArray("curvesBlue"), d.curvesBlue),
            hueShift = f("hueShift", 0f), hslSaturation = f("hslSaturation", 0f), hslLightness = f("hslLightness", 0f),
            hslColorRange = o.optString("hslColorRange", d.hslColorRange),
            replaceColorEnabled = o.optBoolean("replaceColorEnabled", false),
            replaceSourceColorHex = o.optInt("replaceSourceColorHex", d.replaceSourceColorHex),
            replaceTolerance = f("replaceTolerance", d.replaceTolerance), replaceTargetHue = f("replaceTargetHue", d.replaceTargetHue),
            replaceTargetSaturation = f("replaceTargetSaturation", 0f), replaceTargetLightness = f("replaceTargetLightness", 0f),
            whites = f("whites", 0f), blacks = f("blacks", 0f), vibrance = f("vibrance", 0f),
            clarity = f("clarity", 0f), dehaze = f("dehaze", 0f),
            colorizeEnabled = o.optBoolean("colorizeEnabled", false),
            colorizeHue = f("colorizeHue", 30f), colorizeSaturation = f("colorizeSaturation", 35f),
            colorizeLightness = f("colorizeLightness", 0f),
            colorReplacements = o.optJSONArray("colorReplacements")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { c ->
                        com.example.data.model.ColorReplacement(
                            sourceColor = c.optInt("source", 0xFFFF1744.toInt()),
                            targetColor = c.optInt("target", 0xFF2979FF.toInt()),
                            tolerance = c.optDouble("tolerance", 35.0).toFloat().coerceIn(1f, 100f),
                            softness = c.optDouble("softness", 50.0).toFloat().coerceIn(0f, 100f),
                            saturation = c.optDouble("saturation", 0.0).toFloat(),
                            lightness = c.optDouble("lightness", 0.0).toFloat(),
                            enabled = c.optBoolean("enabled", true)
                        )
                    }
                }.take(3)
            } ?: emptyList(),
            hslBands = o.optJSONObject("hslBands")?.let { obj ->
                obj.keys().asSequence().mapNotNull { k ->
                    obj.optJSONArray(k)?.let { arr ->
                        k to com.example.data.model.HslBand(arr.optDouble(0).toFloat(), arr.optDouble(1).toFloat(), arr.optDouble(2).toFloat(), arr.optDouble(3, 1.0).toFloat())
                    }
                }.toMap()
            } ?: emptyMap()
        )
    }
}
