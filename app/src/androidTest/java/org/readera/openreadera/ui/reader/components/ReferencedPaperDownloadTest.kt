package org.readera.openreadera.ui.reader.components

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readera.openreadera.engine.AndroidPdfEngine
import org.readera.openreadera.papers.AcademicPaper
import org.readera.openreadera.papers.AcademicPapersClient
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReferencedPaperDownloadTest {
    @Test fun validatesCachesAndRejectsNonPdfWithoutLeavingPartialFiles() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val names = mutableSetOf<String>()
        val prefix = "paper-download-qa-${java.util.UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                names += prefix + name
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        val directory = File(base.cacheDir, prefix).apply { mkdirs() }
        val pdf = PdfDocument()
        val bytes = try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            page.canvas.drawText("Synthetic referenced paper", 60f, 100f, Paint().apply { textSize = 16f })
            pdf.finishPage(page)
            ByteArrayOutputStream().also(pdf::writeTo).toByteArray()
        } finally { pdf.close() }
        var requests = 0
        val network = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val body = if (chain.request().url.encodedPath.endsWith("html")) "<html>Sign in</html>".toByteArray() else bytes
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(body.toResponseBody()).build()
        }.build()
        val client = AcademicPapersClient(context, network) { directory }
        val paper = AcademicPaper("synthetic", "Synthetic referenced paper", listOf("Author"), "2023", "", "",
            pdfUrl = "https://example.org/paper.pdf")
        try {
            val file = client.downloadPaper(paper) {}.getOrThrow()
            val engine = AndroidPdfEngine(context)
            try { assertTrue(engine.open(file.path)); assertEquals(1, engine.getPageCount()) } finally { engine.close() }
            assertEquals(file, client.downloadPaper(paper) {}.getOrThrow())
            assertEquals("A repeated open must reuse its validated local PDF", 1, requests)
            assertTrue(client.downloadPaper(paper.copy(id = "html", pdfUrl = "https://example.org/html")) {}.isFailure)
            assertTrue(client.downloadPaper(paper.copy(id = "private", pdfUrl = "https://127.0.0.1/paper.pdf")) {}.isFailure)
            assertEquals("Unsafe destinations must be rejected before any request", 2, requests)
            assertEquals(listOf(file.name), directory.listFiles()!!.map { it.name })
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
            names.forEach(base::deleteSharedPreferences)
        }
    }
}
