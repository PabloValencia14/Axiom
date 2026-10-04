package org.readera.openreadera.ui.reader.components

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderRenderCacheTest {
    @Test
    fun resolutionsShareBudgetAndEvictedImagesRemainUsableByConsumers() {
        val shown = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val preview = Bitmap.createBitmap(100, 100, Bitmap.Config.RGB_565)
        val next = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val sharpened = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val cache = ReaderRenderCache(shown.allocationByteCount * 2)
        try {
            cache.put(0, shown)
            cache.put(1, preview, preview = true)
            assertEquals(shown.allocationByteCount + preview.allocationByteCount, cache.sizeBytes)
            assertFalse(cache.canFit(2, next.allocationByteCount.toLong(), preview = false))
            cache.put(2, next)
            assertTrue(cache.sizeBytes <= cache.maxBytes)
            assertNull(cache.get(0))
            assertFalse(shown.isRecycled)
            assertEquals(Color.RED, shown.getPixel(0, 0))

            cache.put(1, sharpened)
            assertNull(cache.get(1, preview = true))
            assertSame(sharpened, cache.get(1))
            assertTrue(cache.sizeBytes <= cache.maxBytes)
            assertFalse(preview.isRecycled)
            cache.retainPages(setOf(1))
            assertNull(cache.get(2))
            assertFalse(next.isRecycled)
            cache.clear()
            assertEquals(0, cache.sizeBytes)
            assertFalse(sharpened.isRecycled)
        } finally {
            cache.clear()
            listOf(shown, preview, next, sharpened).forEach(Bitmap::recycle)
        }
    }

    @Test
    fun prefetchFollowsDirectionAndNeverIncludesVisibleOrOutOfRangePages() {
        assertEquals(listOf(6, 4, 7), readerPrefetchPages(5, 20, false, 1))
        assertEquals(listOf(4, 6, 3), readerPrefetchPages(5, 20, false, -1))
        assertEquals(listOf(8, 9, 4, 5, 10, 11), readerPrefetchPages(6, 20, true, 1))
        assertEquals(listOf(4, 5, 8, 9, 2, 3), readerPrefetchPages(6, 20, true, -1))
        assertEquals(listOf(2), readerPrefetchPages(0, 3, true, 1))
        assertEquals(listOf(5, 6, 3, 4), readerPrefetchPages(7, 8, true, -1))
        assertTrue(readerPrefetchPages(0, 1, false, 1).isEmpty())
    }
}
