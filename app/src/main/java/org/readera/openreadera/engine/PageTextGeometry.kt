package org.readera.openreadera.engine

import android.graphics.Path
import android.graphics.RectF
import android.text.StaticLayout
import java.text.BreakIterator
import java.util.Locale

/** Reuses the exact layout used for body rendering, including bidi runs and styled text. */
internal fun StaticLayout.pageTextLayout(
    text: String, pageWidth: Float, pageHeight: Float, offsetX: Float, offsetY: Float
): PageTextLayout {
    val words = ArrayList<PageTextWord>()
    val breaks = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(text) }
    val path = Path()
    val box = RectF()
    var start = breaks.first()
    var end = breaks.next()
    while (end != BreakIterator.DONE) {
        if (!text.substring(start, end).isBlank()) {
            var partStart = start
            while (partStart < end) {
                val line = getLineForOffset(partStart)
                val partEnd = minOf(end, getLineEnd(line))
                if (partEnd <= partStart) break
                path.reset()
                getSelectionPath(partStart, partEnd, path)
                path.computeBounds(box, true)
                val normalized = normalizedTextBounds(
                    box.left + offsetX, box.top + offsetY,
                    box.right + offsetX, box.bottom + offsetY, pageWidth, pageHeight
                )
                if (normalized != null) words += PageTextWord(partStart, partEnd, normalized, line)
                partStart = partEnd
            }
        }
        start = end
        end = breaks.next()
    }
    return PageTextLayout(text, words)
}

internal fun normalizedTextBounds(
    left: Float, top: Float, right: Float, bottom: Float, width: Float, height: Float
): RectF? {
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
        !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
    val bounds = RectF(
        (left / width).coerceIn(0f, 1f), (top / height).coerceIn(0f, 1f),
        (right / width).coerceIn(0f, 1f), (bottom / height).coerceIn(0f, 1f)
    )
    return bounds.takeIf { it.width() > 0f && it.height() > 0f }
}

internal fun PageTextLayout.lineBounds(): List<RectF> = words.groupBy { it.lineIndex }.values.map { line ->
    RectF(line.first().bounds).also { box -> line.drop(1).forEach { box.union(it.bounds) } }
}

/** Count text and geometry, not just pages, to keep native extraction caches finite. */
internal class PageTextLayoutCache : android.util.LruCache<Int, PageTextLayout>(4 * 1024 * 1024) {
    override fun sizeOf(key: Int, value: PageTextLayout): Int =
        64 + value.text.length * 2 + value.words.size * 64
}
