package org.readera.openreadera.engine

import org.readera.openreadera.papers.CitationTarget
import org.readera.openreadera.papers.PdfReferences

/** Keep the two columns of a paper separate when the positional extractor interleaves its rows. */
internal object PdfReferenceText {
    private fun columns(layout: PageTextLayout): List<List<PageTextWord>> {
        // Ignore full-width title/author rows when detecting the body gutter.
        val body = layout.words.filter { it.bounds.centerY() in .3f.. .96f }
        // shortcut: infer two columns from a central gutter; unusual layouts keep extraction order.
        val twoColumns = body.count { it.bounds.right < .49f } > 20 &&
            body.count { it.bounds.left > .51f } > 20 &&
            body.count { it.bounds.left < .51f && it.bounds.right > .49f } <= maxOf(2, body.size / 100)
        if (!twoColumns) return listOf(layout.words)
        val headerLines = layout.words.filter { it.bounds.centerY() < .3f &&
            it.bounds.left < .51f && it.bounds.right > .49f }.map { it.lineIndex }.toSet()
        val header = layout.words.filter { it.lineIndex in headerLines }
        val columns = layout.words.filterNot { it.lineIndex in headerLines }
        return listOf(header, columns.filter { it.bounds.centerX() < .5f },
            columns.filter { it.bounds.centerX() >= .5f }).filter { it.isNotEmpty() }
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
