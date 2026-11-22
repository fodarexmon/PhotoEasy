package com.example.engine.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * AI models downloaded on first use (not shipped inside the APK). Each model is published as a
 * release asset with a JSON manifest giving its size and SHA-256; a download resumes where it
 * stopped and the file is only kept when it matches the manifest.
 */
object ModelStore {

    /** Where the models are published (GitHub release "models-v1" of the app's repository). */
    const val BASE_URL = "https://github.com/fodarexmon/PhotoEasy/releases/download/models-v1/"

    data class Manifest(val name: String, val file: String, val size: Long, val sha256: String, val inputSize: Int, val layout: String)

    private fun dir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(context: Context, name: String) = File(dir(context), "$name.tflite")
    private fun manifestFile(context: Context, name: String) = File(dir(context), "$name.json")

    fun parseManifest(json: String): Manifest {
        val o = JSONObject(json)
        return Manifest(
            name = o.getString("name"), file = o.getString("file"), size = o.getLong("size"),
            sha256 = o.getString("sha256").lowercase(), inputSize = o.optInt("inputSize", 256),
            layout = o.optString("layout", "NHWC")
        )
    }

    /** The installed model's manifest, or null when the model is not (fully) downloaded. */
    fun installed(context: Context, name: String): Manifest? {
        val mf = manifestFile(context, name)
        val f = modelFile(context, name)
        if (!mf.exists() || !f.exists()) return null
        return try { parseManifest(mf.readText()).takeIf { it.size == f.length() } } catch (e: Exception) { null }
    }

    /** Fetches the manifest of [name] (small JSON), for the size shown before downloading. */
    suspend fun fetchManifest(name: String): Manifest = withContext(Dispatchers.IO) {
        val c = open(URL("$BASE_URL$name.json"), 0L)
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            parseManifest(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    private fun open(url: URL, from: Long): HttpURLConnection {
        val c = url.openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "PhotoEasy")
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    /**
     * Downloads model [name] (resuming a partial download), checks it against its manifest and
     * installs it. [onProgress] gets (bytes so far, total).
     */
    suspend fun download(context: Context, name: String, onProgress: (Long, Long) -> Unit): Manifest = withContext(Dispatchers.IO) {
        val manifest = fetchManifest(name)
        val part = File(dir(context), "$name.part")
        var have = if (part.exists()) part.length() else 0L
        if (have > manifest.size) { part.delete(); have = 0L }
        if (have < manifest.size) {
            val c = open(URL(BASE_URL + manifest.file), have)
            try {
                val code = c.responseCode
                // 206 = resumed; 200 = the server sent the whole file again.
                if (code == 200 && have > 0) { part.delete(); have = 0L }
                if (code != 200 && code != 206) throw java.io.IOException("HTTP $code")
                c.inputStream.use { input ->
                    FileOutputStream(part, have > 0).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var last = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            if (have - last > 256 * 1024 || have >= manifest.size) { onProgress(have, manifest.size); last = have }
                        }
                        out.fd.sync()
                    }
                }
            } finally { c.disconnect() }
        }
        if (part.length() != manifest.size) throw java.io.IOException("Incomplete download")
        if (sha256(part) != manifest.sha256) { part.delete(); throw java.io.IOException("Checksum mismatch") }
        val target = modelFile(context, name)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw java.io.IOException("Could not install the model")
        manifestFile(context, name).writeText(JSONObject().apply {
            put("name", manifest.name); put("file", manifest.file); put("size", manifest.size)
            put("sha256", manifest.sha256); put("inputSize", manifest.inputSize); put("layout", manifest.layout)
        }.toString())
        manifest
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(f, "r").use { raf ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = raf.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Removes model [name] (to free space). */
    fun delete(context: Context, name: String) {
        modelFile(context, name).delete(); manifestFile(context, name).delete()
        File(dir(context), "$name.part").delete()
    }
}
