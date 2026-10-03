package org.readera.openreadera.data.pdf

import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PdfDocumentEditorTest {
    @Test
    fun pageEditingExtractionAndMergePreserveOrderAndSource() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val editor = PdfDocumentEditor(context)
        val token = System.nanoTime()
        val source = File(context.cacheDir, "pdf-smoke-source-$token.pdf")
        val edited = File(context.cacheDir, "pdf-smoke-edited-$token.pdf")
        val extracted = File(context.cacheDir, "pdf-smoke-extracted-$token.pdf")
        val merged = File(context.cacheDir, "pdf-smoke-merged-$token.pdf")
        try {
            createBlankPdf(source, pageCount = 3)
            val originalBytes = source.readBytes()

            editor.applyPagePlan(source, listOf(2, 0, 1), setOf(1), mapOf(2 to 90), edited)
            assertEquals(2, editor.pageCount(edited))
            PDDocument.load(edited).use { document ->
                assertEquals(520f, document.getPage(0).mediaBox.width)
                assertEquals(90, document.getPage(0).rotation)
                assertEquals(500f, document.getPage(1).mediaBox.width)
            }

            editor.extractPages(source, listOf(2, 0), extracted)
            PDDocument.load(extracted).use { document ->
                assertEquals(2, document.numberOfPages)
                assertEquals(520f, document.getPage(0).mediaBox.width)
                assertEquals(500f, document.getPage(1).mediaBox.width)
            }

            editor.merge(listOf(edited, source), merged)
            PDDocument.load(merged).use { document ->
                assertEquals(5, document.numberOfPages)
                assertEquals(520f, document.getPage(0).mediaBox.width)
                assertEquals(500f, document.getPage(2).mediaBox.width)
                assertEquals(520f, document.getPage(4).mediaBox.width)
            }
            assertArrayEquals(originalBytes, source.readBytes())
        } finally {
            source.delete()
            edited.delete()
            extracted.delete()
            merged.delete()
        }
    }

    @Test
    fun visualSignatureChangesOnlySelectedRotatedCroppedPageAndPreservesSource() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val editor = PdfDocumentEditor(context)
        val token = System.nanoTime()
        val source = File(context.cacheDir, "pdf-signature-source-$token.pdf")
        val output = File(context.cacheDir, "pdf-signature-output-$token.pdf")
        val invalidPageOutput = File(context.cacheDir, "pdf-signature-invalid-page-$token.pdf")
        try {
            PDDocument().use { document ->
                repeat(3) { index ->
                    val page = com.tom_roush.pdfbox.pdmodel.PDPage(
                        com.tom_roush.pdfbox.pdmodel.common.PDRectangle(500f + index * 20, 700f)
                    )
                    if (index == 1) {
                        page.cropBox = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(40f, 60f, 400f, 600f)
                        page.rotation = 90
                    }
                    document.addPage(page)
                    PDPageContentStream(document, page).use { stream ->
                        stream.moveTo(10f, 20f)
                        stream.lineTo(30f, 40f + index)
                        stream.stroke()
                    }
                }
                document.save(source)
            }
            val originalBytes = source.readBytes()
            val signature = PdfVisualSignature(
                pageIndex = 1,
                strokes = listOf(
                    PdfSignatureStroke(
                        listOf(
                            PdfSignaturePoint(0f, 0f),
                            PdfSignaturePoint(0.5f, 0.5f),
                            PdfSignaturePoint(1f, 1f)
                        )
                    )
                ),
                alignment = PdfSignatureAlignment.CENTER,
                canvasAspectRatio = 3f
            )
            val beforeOtherPage = PDDocument.load(source).use { PDFRenderer(it).renderImage(0) }
            try {
                val before = PDDocument.load(source).use { document ->
                    document.pages.map { page ->
                        page.mediaBox.width to page.contents.readBytes()
                    }
                }

                editor.addVisualSignature(source, signature, output)

                PDDocument.load(output).use { document ->
                    assertEquals(3, document.numberOfPages)
                    assertEquals(before[0].first, document.getPage(0).mediaBox.width)
                    assertEquals(before[2].first, document.getPage(2).mediaBox.width)
                    assertArrayEquals(before[0].second, document.getPage(0).contents.readBytes())
                    assertArrayEquals(before[2].second, document.getPage(2).contents.readBytes())
                    assertEquals(90, document.getPage(1).rotation)
                    assertEquals(40f, document.getPage(1).cropBox.lowerLeftX)
                    assertEquals(60f, document.getPage(1).cropBox.lowerLeftY)
                    assertEquals(400f, document.getPage(1).cropBox.width)
                    assertEquals(600f, document.getPage(1).cropBox.height)
                    assertFalse(before[1].second.contentEquals(document.getPage(1).contents.readBytes()))

                    val renderer = PDFRenderer(document)
                    val renderedOtherPage = renderer.renderImage(0)
                    try {
                        assertTrue(beforeOtherPage.sameAs(renderedOtherPage))
                    } finally {
                        renderedOtherPage.recycle()
                    }

                    val renderedSignaturePage = renderer.renderImage(1)
                    try {
                        assertTrue(renderedSignaturePage.width > renderedSignaturePage.height)
                        val bottomStart = (renderedSignaturePage.height * 0.80f).toInt()
                        val bottomEnd = (renderedSignaturePage.height * 0.98f).toInt()
                        var visibleSignature = false
                        for (y in bottomStart until bottomEnd) {
                            for (x in 0 until renderedSignaturePage.width) {
                                if (renderedSignaturePage.getPixel(x, y) != android.graphics.Color.WHITE) {
                                    visibleSignature = true
                                    break
                                }
                            }
                            if (visibleSignature) break
                        }
                        assertTrue(visibleSignature)
                    } finally {
                        renderedSignaturePage.recycle()
                    }
                }
                assertArrayEquals(originalBytes, source.readBytes())
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking {
                        editor.addVisualSignature(
                            source,
                            signature.copy(pageIndex = 3),
                            invalidPageOutput
                        )
                    }
                }
                assertFalse(invalidPageOutput.exists())
            } finally {
                beforeOtherPage.recycle()
            }
        } finally {
            source.delete()
            output.delete()
            invalidPageOutput.delete()
        }
    }

    private fun createBlankPdf(file: File, pageCount: Int) {
        val bodies = ArrayList<String>(2 + pageCount * 2)
        bodies += "<< /Type /Catalog /Pages 2 0 R >>"
        val pageObjectNumbers = (0 until pageCount).map { 3 + it }
        bodies += "<< /Type /Pages /Kids [${pageObjectNumbers.joinToString(" ") { "$it 0 R" }}] /Count $pageCount >>"
        for (index in 0 until pageCount) {
            val contentObject = 3 + pageCount + index
            val width = 500 + index * 10
            bodies += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width 792] /Resources << >> /Contents $contentObject 0 R >>"
        }
        repeat(pageCount) {
            val content = "q\nQ\n"
            bodies += "<< /Length ${content.length} >>\nstream\n${content}endstream"
        }

        val output = ByteArrayOutputStream()
        fun write(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        write("%PDF-1.4\n")
        val offsets = bodies.mapIndexed { index, body ->
            val offset = output.size()
            write("${index + 1} 0 obj\n$body\nendobj\n")
            offset
        }
        val xrefOffset = output.size()
        write("xref\n0 ${bodies.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(it)) }
        write("trailer\n<< /Size ${bodies.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
        file.writeBytes(output.toByteArray())
    }
}
