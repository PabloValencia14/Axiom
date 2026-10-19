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
        val text = StringBuilder()
        val words = mutableListOf<PageTextWord>()
        val left = listOf("Previous work (Brown et al.,", "2020; Chung et al., 2022)") + List(8) { "Left column ordinary body text" }
        val right = List(10) { "Other column unrelated body text" }
        for (line in left.indices) {
            for ((column, content) in listOf(left[line], right[line]).withIndex()) {
                for ((index, word) in content.split(' ').withIndex()) {
                    val start = text.length
                    text.append(word).append(' ')
                    val x = .08f + .45f * column + .05f * index
                    words += PageTextWord(start, start + word.length,
                        RectF(x, .1f + line * .012f, x + .04f, .11f + line * .012f), line)
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
}
