package org.readera.openreadera.papers

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readera.openreadera.catalog.CatalogRequestExecutor
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReferencedPaperResolverTest {
    private val reference = PaperReference(null, "Tom B. Brown, Jane Smith. 2020. Language models are few-shot learners. In Proceedings.", 8)
    private fun work(title: String = "Language Models are Few-Shot Learners", year: Int = 2020, author: String = "Tom B. Brown") = JSONObject()
        .put("id", "https://openalex.org/W123").put("title", title).put("publication_year", year)
        .put("authorships", JSONArray().put(JSONObject().put("author", JSONObject().put("display_name", author))))
        .put("best_oa_location", JSONObject().put("is_oa", true).put("pdf_url", "https://arxiv.org/pdf/2005.14165"))

    private fun resolver(body: String, status: Int = 200) = ReferencedPaperResolver(CatalogRequestExecutor(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(body.toResponseBody()).build()
        }).build()))

    @Test fun requiresMatchingTitleYearAndAuthor() {
        assertTrue(ReferencedPaperResolver.matches(reference, work()))
        assertTrue(ReferencedPaperResolver.matches(reference, work(author = "Brown, Tom B.")))
        assertFalse(ReferencedPaperResolver.matches(reference, work(year = 2021)))
        assertFalse(ReferencedPaperResolver.matches(reference, work(author = "Another Person")))
        assertFalse(ReferencedPaperResolver.matches(reference, work(title = "Language Models")))
        assertFalse(ReferencedPaperResolver.matches(reference, work(title = "Unrelated paper")))
    }

    @Test fun explicitArxivIdentifierUsesItsOwnPdfWithoutSearch() = runBlocking {
        val paper = resolver("not JSON").resolve(PaperReference(null, "OpenAI. 2023. GPT-4 technical report. arXiv:2303.08774.", 9))
        assertEquals("https://arxiv.org/pdf/2303.08774", paper.pdfUrl)
        assertEquals("GPT-4 technical report", paper.title)
        val direct = resolver("not JSON").resolve(PaperReference(null,
            "Author. 2023. Another complete report. https://example.org/report.pdf.", 9))
        assertEquals("https://example.org/report.pdf", direct.pdfUrl)
    }

    @Test fun exactMatchFindsOpenAccessAndRejectsAmbiguousOrMissingResults() = runBlocking {
        fun results(vararg works: JSONObject) = JSONObject().put("results", JSONArray(works.toList())).toString()
        assertEquals("https://arxiv.org/pdf/2005.14165", resolver(results(work())).resolve(reference).pdfUrl)
        assertTrue(runCatching { resolver(results(work(title = "Wrong paper"))).resolve(reference) }.isFailure)
        assertTrue(runCatching { resolver(results(work(), work().put("id", "https://openalex.org/W456"))).resolve(reference) }.isFailure)
        assertTrue(runCatching { resolver(results(work().put("best_oa_location", JSONObject.NULL))).resolve(reference) }.isFailure)
    }

    @Test fun rejectsServiceFailureAndOversizedMetadata() = runBlocking {
        assertTrue(runCatching { resolver("{}", 429).resolve(reference) }.isFailure)
        assertTrue(runCatching { resolver(" ".repeat(1_048_577)).resolve(reference) }.isFailure)
    }

    @Test fun explicitDoiPinsTheIdentifiedWork() = runBlocking {
        val cited = reference.copy(text = reference.text + " https://doi.org/10.1000/known-paper.")
        val item = work().put("doi", "https://doi.org/10.1000/known-paper")
        val body = JSONObject().put("results", JSONArray().put(item)).toString()
        assertEquals("https://doi.org/10.1000/known-paper", resolver(body).resolve(cited).doi)
        val wrong = JSONObject().put("results", JSONArray().put(item.put("doi", "https://doi.org/10.1000/different"))).toString()
        assertTrue(runCatching { resolver(wrong).resolve(cited) }.isFailure)
    }

    @Test fun stableNamesSeparatePapersWithTheSameTruncatedTitle() {
        val title = "Shared title ".repeat(10)
        assertNotEquals(paperFileName("first", title, listOf("Author")), paperFileName("second", title, listOf("Author")))
        assertEquals(paperFileName("arxiv:2303.08774", title, listOf("Author")), paperFileName("2303.08774", title, listOf("Author")))
        assertFalse(paperFileName("id", "../Title", listOf("../Name")).contains('/'))
    }
}
