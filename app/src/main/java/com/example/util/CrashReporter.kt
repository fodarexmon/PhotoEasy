package com.example.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the last crash's stack trace in the app's files, so it can be shown and shared on the
 * next launch (there is no other way to know why the app stopped on a user's phone).
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val rt = Runtime.getRuntime()
                val version = try {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                } catch (_: Throwable) { "?" }
                val text = buildString {
                    append("PhotoEasy ").append(version).append('\n')
                    append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
                    append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                    append("Thread: ").append(thread.name).append('\n')
                    append("Java heap: ").append((rt.totalMemory() - rt.freeMemory()) / 1048576).append(" / ")
                    append(rt.maxMemory() / 1048576).append(" MB\n\n")
                    append(sw.toString().take(60_000))
                }
                File(app.filesDir, FILE).writeText(text)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report of the last crash, if any (not yet dismissed). */
    fun lastReport(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) { File(context.filesDir, FILE).delete() }
}
