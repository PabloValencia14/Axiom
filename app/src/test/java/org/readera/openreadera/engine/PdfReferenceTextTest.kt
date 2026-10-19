package org.readera.openreadera.engine

import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.readera.openreadera.papers.PdfReferences

@RunWith(RobolectricTestRunner::class)
class PdfReferenceTextTest {
    @Test fun twoColumnRowsDoNotMixOtherProseIntoWrappedCitations() {
        val text = StringBuilder("Full-width title\n")
        val words = mutableListOf(PageTextWord(0, 16, RectF(.35f, .1f, .65f, .12f), -1))
        val left = listOf("Previous work (Brown et al.,", "2020; Chung et al., 2022)") + List(8) { "Left column ordinary body text" }
        val right = List(10) { "Other column unrelated body text" }
        for (line in left.indices) {
            for ((column, content) in listOf(left[line], right[line]).withIndex()) {
                for ((index, word) in content.split(' ').withIndex()) {
                    val start = text.length
                    text.append(word).append(' ')
                    val x = .08f + .45f * column + .05f * index
                    words += PageTextWord(start, start + word.length,
                        RectF(x, .35f + line * .012f, x + .04f, .36f + line * .012f), line)
                }
            }
            text.append('\n')
        }
        val layout = PageTextLayout(text.toString(), words)
        val selected = words.first { text.substring(it.startUtf16, it.endUtf16) == "2020;" }
        val target = PdfReferenceText.citationAt(layout, selected)!!
        assertTrue(target.text.contains("Chung"))
        assertFalse(target.text, target.text.contains("unrelated"))
        val refs = PdfReferences.parse(listOf(8 to "References\nTom Brown, A. Person. 2020. First report.\nHyung Won Chung, B. Person. 2022. Second report."))
        assertEquals("Target: ${target.text}; refs: $refs", 2, PdfReferences.resolve(target, refs).size)
    }
    @Test fun bibliographyAuthorsTitlesAndContinuationPagesOpenTheExactEntry() {
        fun layout(vararg lines: String): PageTextLayout {
            val text = StringBuilder()
            val words = mutableListOf<PageTextWord>()
            lines.forEachIndexed { row, line ->
                line.split(' ').forEachIndexed { col, word ->
                    val start = text.length
                    text.append(word).append(' ')
                    words += PageTextWord(start, start + word.length,
                        RectF(.05f + col * .08f, .1f + row * .05f, .12f + col * .08f, .13f + row * .05f), row)
                }
                text.append('\n')
            }
            return PageTextLayout(text.toString(), words)
        }
        val first = layout("Earlier work (OpenAI, 2023).", "References",
            "OpenAI. 2023. GPT-4 technical report.", "Ben Kilby. 2021. Dialogic")
        val second = layout("pedagogies and learning.", "Jonathan Osborne. 2010. Arguing to learn.",
            "Appendix A", "Ordinary appendix text.")
        val refs = PdfReferences.parse(listOf(4 to PdfReferenceText.bibliographyText(first),
            5 to PdfReferenceText.bibliographyText(second)))
        assertEquals(3, refs.size)
        fun target(page: Int, original: PageTextLayout, word: String) =
            PdfReferenceText.withReferences(original, page, refs).let { linked ->
                PdfReferenceText.citationAt(linked, linked.words.first {
                    linked.text.substring(it.startUtf16, it.endUtf16) == word
                })
            }
        for (word in listOf("OpenAI.", "GPT-4", "report.")) {
            val found = target(4, first, word)!!
            assertEquals(refs[0], found.reference)
            assertEquals(listOf(refs[0]), PdfReferences.resolve(found, refs))
        }
        assertEquals(refs[1], target(4, first, "Kilby.")!!.reference)
        assertEquals(refs[1], target(5, second, "pedagogies")!!.reference)
        assertEquals(refs[2], target(5, second, "Osborne.")!!.reference)
        assertNull(target(5, second, "Ordinary"))
        assertNull(target(4, first, "References"))
        assertNull(target(4, first, "Earlier"))
    }

    @Test fun numberedBibliographyLinksFollowBothColumnsWithoutMixingRows() {
        val text = StringBuilder("References\n")
        val words = mutableListOf(PageTextWord(0, 10, RectF(.35f, .1f, .65f, .12f), -1))
        for (row in 0..5) {
            for (column in 0..1) {
                val number = 1 + row + column * 6
                "[$number] Author$number. 2020. Report $number.".split(' ').forEachIndexed { index, word ->
                    val start = text.length
                    text.append(word).append(' ')
                    val x = .08f + column * .45f + index * .05f
                    words += PageTextWord(start, start + word.length,
                        RectF(x, .35f + row * .03f, x + .04f, .37f + row * .03f), row)
                }
            }
            text.append('\n')
        }
        val layout = PageTextLayout(text.toString(), words)
        val refs = PdfReferences.parse(listOf(9 to PdfReferenceText.bibliographyText(layout)))
        assertEquals(12, refs.size)
        val linked = PdfReferenceText.withReferences(layout, 9, refs)
        for (word in words.drop(1)) {
            val target = PdfReferenceText.citationAt(linked, word)!!
            val expected = if (word.bounds.centerX() < .5f) word.lineIndex + 1 else word.lineIndex + 7
            assertEquals(expected.toString(), target.reference!!.label)
        }
    }

}
