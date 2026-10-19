package org.readera.openreadera.engine

import org.readera.openreadera.papers.CitationTarget
import org.readera.openreadera.papers.PdfReferences

/** Keep the two columns of a paper separate when the positional extractor interleaves its rows. */
internal object PdfReferenceText {
    private fun columns(layout: PageTextLayout): List<List<PageTextWord>> {
        val body = layout.words.filter { it.bounds.centerY() in .04f.. .96f }
        // shortcut: require a clear central gutter; unusual layouts retain the extraction order.
        val twoColumns = body.count { it.bounds.right < .49f } > 20 &&
            body.count { it.bounds.left > .51f } > 20 &&
            body.none { it.bounds.left < .51f && it.bounds.right > .49f }
        return if (twoColumns) listOf(layout.words.filter { it.bounds.centerX() < .5f },
            layout.words.filter { it.bounds.centerX() >= .5f }) else listOf(layout.words)
    }

    fun bibliographyText(layout: PageTextLayout): String {
        if (layout.words.isEmpty()) return layout.text
        return columns(layout).joinToString("\n") { words ->
            words.groupBy { it.lineIndex }.values.joinToString("\n") { line ->
                line.joinToString(" ") { layout.text.substring(it.startUtf16, it.endUtf16) }
            }
        }
    }

    fun citationAt(layout: PageTextLayout, word: PageTextWord): CitationTarget? {
        val column = columns(layout).firstOrNull { word in it } ?: return null
        val nearby = column.filter { kotlin.math.abs(it.bounds.centerY() - word.bounds.centerY()) < .065f }
        val text = StringBuilder()
        var offset = -1
        var line = -1
        for (candidate in nearby) {
            if (text.isNotEmpty()) text.append(if (line == candidate.lineIndex) ' ' else '\n')
            if (candidate == word) offset = text.length + (candidate.endUtf16 - candidate.startUtf16) / 2
            text.append(layout.text, candidate.startUtf16, candidate.endUtf16)
            line = candidate.lineIndex
        }
        if (offset < 0) return null
        return PdfReferences.at(text.toString(), offset)
    }
}
