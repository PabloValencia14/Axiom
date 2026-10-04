package org.readera.openreadera.ui.reader.components

import android.graphics.Bitmap
import android.util.LruCache

/** One byte budget for both resolutions. Eviction releases ownership, never recycles UI images. */
internal class ReaderRenderCache(val maxBytes: Int) {
    private data class Key(val page: Int, val preview: Boolean)
    private val cache = object : LruCache<Key, Bitmap>(maxBytes) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
    }

    val sizeBytes: Int get() = cache.size()

    fun get(page: Int, preview: Boolean = false): Bitmap? = cache.get(Key(page, preview))

    fun canFit(page: Int, bytes: Long, preview: Boolean): Boolean {
        val replaced = (cache.get(Key(page, preview))?.allocationByteCount?.toLong() ?: 0L) +
            if (!preview) (cache.get(Key(page, true))?.allocationByteCount?.toLong() ?: 0L) else 0L
        return bytes <= maxBytes.toLong() - sizeBytes + replaced
    }

    fun put(page: Int, bitmap: Bitmap, preview: Boolean = false) {
        if (bitmap.allocationByteCount > maxBytes) return
        if (!preview) cache.remove(Key(page, true))
        cache.put(Key(page, preview), bitmap)
    }

    fun retainPages(pages: Set<Int>) {
        cache.snapshot().keys.filter { it.page !in pages }.forEach(cache::remove)
    }

    fun clear() = cache.evictAll()
}

/** At most two spreads ahead and one behind, ordered by the last navigation direction. */
internal fun readerPrefetchPages(center: Int, total: Int, doublePage: Boolean, direction: Int): List<Int> {
    val step = if (doublePage) 2 else 1
    val forward = if (direction < 0) -step else step
    return buildList {
        for (offset in intArrayOf(forward, -forward, 2 * forward)) {
            val first = center + offset
            for (page in first until first + step) {
                if (page in 0 until total && page !in center until center + step) add(page)
            }
        }
    }
}
