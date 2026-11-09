package com.example.engine

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Multi-level undo/redo for selection masks.
 *
 * Masks are stored compressed (Deflate): a selection is mostly long runs of 0 / 255, so a
 * 12-megapixel mask (12 MB raw) usually shrinks to a few hundred KB. That makes 20+ steps of
 * selection history affordable on a phone. Thread-safe; encoding happens on the caller's thread,
 * so call [push] from a background dispatcher for large masks.
 */
class SelectionHistory(
    private val maxEntries: Int = 25,
    private val maxBytes: Long = 48L * 1024L * 1024L
) {
    private class Entry(val width: Int, val height: Int, val data: ByteArray)

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    val canUndo: Boolean get() = synchronized(this) { undoStack.isNotEmpty() }
    val canRedo: Boolean get() = synchronized(this) { redoStack.isNotEmpty() }

    @Synchronized
    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    /** Drops the redo branch (a document edit happened after the undo). */
    @Synchronized
    fun clearRedo() {
        redoStack.clear()
    }

    /** Records [mask] as the state to return to, and drops the redo branch. */
    fun push(mask: Bitmap) {
        val entry = encode(mask)
        synchronized(this) {
            undoStack.addLast(entry)
            redoStack.clear()
            trim()
        }
    }

    /** Returns the previous mask (or null) and records [current] for redo. */
    fun undo(current: Bitmap): Bitmap? {
        val entry = synchronized(this) { undoStack.removeLastOrNull() } ?: return null
        val currentEntry = encode(current)
        synchronized(this) { redoStack.addLast(currentEntry) }
        return decode(entry)
    }

    /** Returns the next mask (or null) and records [current] for undo. */
    fun redo(current: Bitmap): Bitmap? {
        val entry = synchronized(this) { redoStack.removeLastOrNull() } ?: return null
        val currentEntry = encode(current)
        synchronized(this) {
            undoStack.addLast(currentEntry)
            trim()
        }
        return decode(entry)
    }

    private fun trim() {
        while (undoStack.size > maxEntries) undoStack.removeFirst()
        var total = undoStack.sumOf { it.data.size.toLong() } + redoStack.sumOf { it.data.size.toLong() }
        while (total > maxBytes && undoStack.size > 1) {
            total -= undoStack.removeFirst().data.size
        }
    }

    private fun encode(mask: Bitmap): Entry {
        val raw = SelectionEngine.alphaBytesPacked(mask)
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(maxOf(1024, raw.size / 32))
            val buf = ByteArray(64 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            return Entry(mask.width, mask.height, out.toByteArray())
        } finally {
            deflater.end()
        }
    }

    private fun decode(entry: Entry): Bitmap {
        val raw = ByteArray(entry.width * entry.height)
        val inflater = Inflater()
        try {
            inflater.setInput(entry.data)
            var off = 0
            while (off < raw.size && !inflater.finished()) {
                val n = inflater.inflate(raw, off, raw.size - off)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                off += n
            }
        } finally {
            inflater.end()
        }
        return SelectionEngine.alphaMaskFromPacked(raw, entry.width, entry.height)
    }
}
