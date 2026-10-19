package org.readera.openreadera.papers

import org.junit.Assert.*
import org.junit.Test

class PdfReferencesTest {
    @Test fun yearStartingAContinuationLineDoesNotSwitchToNumberedReferences() {
        val refs = PdfReferences.parse(listOf(8 to "References\nDan Hendrycks, Collin Burns, and Jacob Steinhardt.\n2021. Measuring massive multitask language understanding.\nOpenAI. 2023. GPT-4 technical report."))
        assertEquals(2, refs.size)
        assertTrue(refs.all { it.label == null })
        assertEquals(1, PdfReferences.resolve(CitationTarget("(OpenAI, 2023)", 0, 14), refs).size)
    }

    @Test fun apaEntriesAndMultipleYearsForOneAuthorResolveSeparately() {
        val refs = PdfReferences.parse(listOf(3 to "References\nSmith, J. (2020). First report.\nSmith, J. (2022). Second report."))
        val text = "(Smith, 2020, 2022)"
        assertEquals(2, PdfReferences.resolve(PdfReferences.at(text, 11)!!, refs).size)
    }

    @Test fun authorYearGroupResolvesActualEntriesAndKeepsWrappedAuthorsTogether() {
        val pages = listOf(8 to """
            References
            Tom Brown, Benjamin Mann, Nick Ryder, Melanie
            Subbiah, Jared Kaplan, and Dario Amodei. 2020.
            Language models are few-shot learners.
            Hyung Won Chung, Le Hou, Shayne Longpre, Barret
            Zoph, and Jason Wei. 2022. Scaling instruction-finetuned
            language models. arXiv:2210.11416.
            OpenAI. 2023. GPT-4 technical report.
        """.trimIndent())
        val text = "Previous work (Brown et al., 2020; Chung et al., 2022; OpenAI, 2023) supports this."
        val target = PdfReferences.at(text, text.indexOf("2022"))!!
        val found = PdfReferences.resolve(target, PdfReferences.parse(pages))
        assertEquals(3, found.size)
        assertTrue(found[0].text.contains("few-shot learners"))
        assertTrue(found[1].text.contains("Scaling instruction-finetuned"))
        assertTrue(found[2].text.contains("GPT-4 technical report"))
        assertTrue(found.all { it.page == 8 })
    }

    @Test fun numericRangesAreBoundedAndResolveAcrossPages() {
        val refs = PdfReferences.parse(listOf(9 to "References\n[1] First. 2020.\n[2] Second. 2021.",
            10 to "[3] Third. 2022.\n[4] Fourth. 2023.\nAppendix A\nNot a reference."))
        val text = "Compared with [1, 3–4]."
        assertEquals(listOf("1", "3", "4"), PdfReferences.resolve(PdfReferences.at(text, 17)!!, refs).map { it.label })
        assertTrue(PdfReferences.resolve(CitationTarget("[1-9999]", 0, 8), refs).isEmpty())
        assertNull(PdfReferences.at(text, 1))
        assertTrue(PdfReferences.parse(listOf(0 to "Some text. Brown 2020. No bibliography here.")).isEmpty())
    }

    @Test fun unresolvedAndAmbiguousReferencesNeverInventMetadata() {
        val refs = listOf(PaperReference(null, "OpenAI. 2023. One report.", 4),
            PaperReference(null, "OpenAI. 2023. Another report.", 5))
        val text = "OpenAI (2023)"
        val target = PdfReferences.at(text, text.indexOf("2023"))!!
        assertEquals(2, PdfReferences.resolve(target, refs).size)
        assertEquals(target, PdfReferences.at(text, 2))
        assertTrue(PdfReferences.resolve(CitationTarget("(Unknown, 2024)", 0, 15), refs).isEmpty())
    }
}
