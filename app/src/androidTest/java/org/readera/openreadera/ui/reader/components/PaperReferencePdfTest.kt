package org.readera.openreadera.ui.reader.components

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readera.openreadera.engine.*
import org.readera.openreadera.papers.*

@RunWith(AndroidJUnit4::class)
class PaperReferencePdfTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun realPaperResolvesBrownChungAndOpenAiFromNativePdfText() {
        val path = InstrumentationRegistry.getArguments().getString("referencePaperPath")
        assumeTrue("Pass an explicitly selected paper for the real document check", path != null)
        val engine = AndroidPdfEngine(instrumentation.targetContext)
        try {
            assertTrue(engine.open(path!!))
            val pages = (0 until engine.getPageCount()).map { it to PdfReferenceText.bibliographyText(engine.getPageTextLayout(it)) }
            val references = PdfReferences.parse(pages)
            java.io.File(instrumentation.targetContext.cacheDir, "reference-index-proof.txt").writeText(
                references.joinToString("\n\n") { "PAGE ${it.page + 1}: ${it.text}" })
            java.io.File(instrumentation.targetContext.cacheDir, "reference-pages-proof.txt").writeText(
                pages.joinToString("\n\n") { "PAGE ${it.first + 1}: ${it.second}" })
            val target = CitationTarget("(Brown et al., 2020; Chung et al., 2022; OpenAI, 2023)", 0, 63)
            val found = PdfReferences.resolve(target, references)
            assertTrue("Brown 2020 must resolve", found.any { it.text.contains("few-shot", true) })
            assertTrue("Chung 2022 must resolve", found.any { it.text.contains("instruction-finetuned", true) })
            assertTrue("OpenAI 2023 must resolve", found.any { it.text.contains("GPT-4", true) })
            val firstPage = engine.getPageTextLayout(0)
            val actualGroup = firstPage.words.mapNotNull { PdfReferenceText.citationAt(firstPage, it) }
                .firstOrNull { it.text.contains("Brown") && it.text.contains("Chung") && it.text.contains("OpenAI") }
            assertNotNull("The actual first-page citation group must be detected at its word locations", actualGroup)
            val actualMatches = PdfReferences.resolve(actualGroup!!, references)
            assertTrue("The visible citation group must resolve all three works", actualMatches.any { it.text.contains("few-shot", true) } &&
                actualMatches.any { it.text.contains("instruction-finetuned", true) } && actualMatches.any { it.text.contains("GPT-4", true) })
            assertTrue("Native text locations must be available", (0 until minOf(3, engine.getPageCount()))
                .any { page -> val layout = engine.getPageTextLayout(page)
                    layout.words.any { PdfReferenceText.citationAt(layout, it) != null } })
        } finally { engine.close() }
    }
}
