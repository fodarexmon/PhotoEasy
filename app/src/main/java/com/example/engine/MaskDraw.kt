package com.example.engine

import android.graphics.Bitmap

/**
 * Masks are stored as ALPHA_8 bitmaps. Drawn onto a colour (ARGB) bitmap with an alpha-only
 * transfer mode (DST_IN / DST_OUT), current Android graphics treat an ALPHA_8 source as fully
 * opaque, so the mask had no effect (a black layer mask hid nothing). Drawing an ARGB copy of the
 * mask works everywhere; [argb] gives that copy, cached so a mask is converted once, not per frame.
 */
object MaskDraw {

    private class Entry(val src: Bitmap, val generation: Int, val argb: Bitmap)

    private val lock = Any()
    private val cache = ArrayList<Entry>(4)

    /** [mask] as an ARGB_8888 bitmap with the same alpha (black colour); [mask] itself when not ALPHA_8. */
    fun argb(mask: Bitmap): Bitmap {
        if (mask.config != Bitmap.Config.ALPHA_8) return mask
        val gen = mask.generationId
        synchronized(lock) {
            cache.firstOrNull { it.src === mask && it.generation == gen && !it.argb.isRecycled }?.let { return it.argb }
        }
        val out = mask.copy(Bitmap.Config.ARGB_8888, false)
        synchronized(lock) {
            cache.removeAll { it.src === mask || it.argb.isRecycled }
            cache.add(0, Entry(mask, gen, out))
            // Evicted copies are left to the GC: a render may still be drawing them.
            while (cache.size > 4) cache.removeAt(cache.size - 1)
        }
        return out
    }

    fun clear() = synchronized(lock) { cache.clear() }
}
