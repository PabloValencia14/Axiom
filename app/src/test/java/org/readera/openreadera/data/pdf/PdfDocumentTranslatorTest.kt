package org.readera.openreadera.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readera.openreadera.translation.TranslationRejectedException
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfDocumentTranslatorTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val font get() = PDType1Font.HELVETICA
    private val marker = Regex("""__AXIOM_[A-F0-9]+_(?:S\d+_(?:BEGIN|END)|[MC]\d+)__""")

    @Test fun longTranslationFlowsBelowOriginalWithoutFontReductionOrTruncation() = runBlocking {
        withFiles { source, output ->
            simple(source, "BT /F1 20 Tf 1 0 0 1 40 220 Tm (Short prose.) Tj ET")
            val original = source.readBytes()
            val translation = "Long translated paragraph ".repeat(40)
            PdfDocumentTranslator(context).translateCopy(source, output, "es", { _, _ ->
                Result.success(translation)
            }, { _, _ -> })
            PDDocument.load(output).use { document ->
                assertEquals(1, document.numberOfPages)
                val page = document.getPage(0)
                assertTrue("flow expands the output page", page.mediaBox.height > 300f)
                val text = PDFTextStripper().getText(document).replace(Regex("\\s+"), " ")
                assertTrue(text.indexOf("Short prose.") in 0 until text.indexOf("Long translated"))
                assertTrue(text.contains(translation.trim()))
            }
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun extremeTranslationContinuesAcrossBoundedPagesWithoutLosingSourceOrText() = runBlocking {
        withFiles { source, output ->
            simple(source, "BT /F1 20 Tf 1 0 0 1 40 220 Tm (Short prose.) Tj ET")
            val translation = "long text ".repeat(12_000)
            PdfDocumentTranslator(context).translateCopy(source, output, "es", { _, _ ->
                Result.success(translation)
            }, { _, _ -> })
            PDDocument.load(output).use { document ->
                assertTrue("oversized flow continues onto output pages", document.numberOfPages > 1)
                assertTrue("every output page stays within the PDF page-height bound",
                    (0 until document.numberOfPages).all { document.getPage(it).mediaBox.height <= 14_400f })
                val text = PDFTextStripper().getText(document).replace(Regex("\\s+"), " ")
                assertTrue(text.indexOf("Short prose.") in 0 until text.indexOf("long text"))
                assertEquals(12_000, Regex("long text").findAll(text).count())
            }
        }
    }

    @Test fun vectorBandsKeepOriginalPixelsOnceAndFlowLongStyledColumnText() = runBlocking {
        withFiles { source, output ->
            columns(source)
            val graphicsBounds = PDDocument.load(source).use { sourceDocument ->
                PdfTranslationAnalyzer(sourceDocument.getPage(0), 0).analyze().graphics.map { it.bounds.toShortString() }
            }
            val original = source.readBytes()
            val calls = mutableListOf<String>()
            val translatedChunks = mapOf(
                "Spanning full-width heading across the page." to "Título.",
                "Left paragraph first. Left paragraph next." to "Párrafo izquierdo.",
                "Right paragraph first. Right paragraph next." to "Párrafo derecho.",
                "(Lab, 2025)" to "(Lab, 2025)",
                "Occlusion sample." to "Tapado."
            )
            val longText = "Expanded translation flows below the source. ".repeat(30)
            PdfDocumentTranslator(context).translateCopy(source, output, "es", { request, _ ->
                calls += request
                Result.success(translateProse(request, translatedChunks, longText))
            }, { _, _ -> })
            assertTrue(calls.any { Regex("""__AXIOM_[A-F0-9]+_C\d+__""").containsMatchIn(it) })
            PDDocument.load(source).use { before -> PDDocument.load(output).use { document ->
                val page = document.getPage(0)
                assertEquals(1, document.numberOfPages)
                assertTrue("long prose expands rather than shrinking", page.mediaBox.height > 420f)
                val text = PDFTextStripper().getText(document).replace(Regex("\\s+"), " ")
                listOf("Spanning full-width heading", "Left paragraph first.", "Right paragraph first.", "= 2", "Lab, 2025")
                    .forEach { assertTrue("original text/formula/citation missing: $it", text.contains(it)) }
                assertTrue(text.contains("Párrafo izquierdo."))
                assertTrue(text.contains("Expanded translation flows below the source."))
                assertEquals(1, Regex("Spanning full-width heading").findAll(text).count())
                assertTrue(page.resources.fontNames.map { page.resources.getFont(it).name }.any { it.contains("Bold") })
                val originalImage = PDFRenderer(before).renderImage(0)
                val resultImage = PDFRenderer(document).renderImage(0)
                try {
                    val sourceCoverage = magentaCoverage(originalImage)
                    val outputCoverage = magentaCoverage(resultImage)
                    assertTrue(
                        "image artwork coverage missing or repeated ($sourceCoverage -> $outputCoverage): $graphicsBounds",
                        outputCoverage.toFloat() in (sourceCoverage * .8f)..(sourceCoverage * 1.2f)
                    )
                } finally { originalImage.recycle(); resultImage.recycle() }
            } }
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun croppedRotatedSharedFormsAndBlankPagesComposeWithDisplayGeometry() = runBlocking {
        withFiles { source, output ->
            forms(source)
            val original = source.readBytes()
            val calls = mutableListOf<String>()
            PdfDocumentTranslator(context).translateCopy(source, output, "es", { text, _ ->
                calls += text
                Result.success("Texto.")
            }, { _, _ -> })
            assertEquals(List(8) { "Shared form prose." }, calls)
            PDDocument.load(source).use { before -> PDDocument.load(output).use { document ->
                assertEquals(5, document.numberOfPages)
                val text = PDFTextStripper().getText(document).filterNot(Char::isWhitespace)
                assertEquals(8, Regex("Sharedformprose[.]").findAll(text).count())
                assertEquals(8, Regex("Texto[.]").findAll(text).count())
                listOf(350f to 360f, 360f to 350f, 350f to 360f, 360f to 350f)
                    .forEachIndexed { index, size ->
                        val page = document.getPage(index)
                        assertEquals(0, page.rotation)
                        assertEquals(size.first, page.mediaBox.width)
                        assertTrue("translated rotated page expands vertically", page.mediaBox.height > size.second)
                        assertTrue("cropped/rotated image resource remains present", containsPdfImage(page.resources))
                    }
                assertEquals(440f, document.getPage(4).mediaBox.width)
                assertEquals(440f, document.getPage(4).mediaBox.height)
            } }
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun nativeFontCoverageRejectsUnsupportedOutputAndCleansProviderFailures() = runBlocking {
        for (translation in listOf("\ud83e\udd84", "مرحبا", "e\u0301")) withFiles { source, output ->
            simple(source, "BT /F1 12 Tf 1 0 0 1 40 220 Tm (Short prose.) Tj ET")
            val bytes = source.readBytes()
            val failure = try {
                PdfDocumentTranslator(context).translateCopy(source, output, "es", { _, _ -> Result.success(translation) }, { _, _ -> })
                null
            } catch (exception: TranslationRejectedException) { exception }
            assertNotNull(failure)
            assertNotNull(failure!!.location)
            assertFalse(output.exists())
            assertArrayEquals(bytes, source.readBytes())
        }
        for (cancel in listOf(false, true)) withFiles { source, output ->
            simple(source, "BT /F1 12 Tf 1 0 0 1 40 220 Tm (Short prose.) Tj ET")
            val bytes = source.readBytes()
            val problem = if (cancel) CancellationException("cancel") else IOException("provider unavailable")
            try {
                PdfDocumentTranslator(context).translateCopy(source, output, "es", { _, _ -> Result.failure(problem) }, { _, _ -> })
                fail("Expected provider failure")
            } catch (failure: Exception) {
                if (cancel) assertTrue(failure is CancellationException && failure.message == problem.message)
                else assertTrue(failure is IOException && failure.message == problem.message)
            }
            assertFalse(output.exists())
            assertArrayEquals(bytes, source.readBytes())
        }
    }

    @Test fun explicitlyClippedProseRemainsTranslatableFromCopiedPageContent() = runBlocking {
        withFiles { source, output ->
            simple(source, "q 40 220 4 4 re W n BT /F1 12 Tf 1 0 0 1 40 220 Tm (Clipped prose.) Tj ET Q")
            var calls = 0
            PdfDocumentTranslator(context).translateCopy(source, output, "es", { text, _ ->
                calls++
                assertTrue(text.contains("Clipped prose."))
                Result.success("Texto.")
            }, { _, _ -> })
            assertEquals(1, calls)
            PDDocument.load(output).use { document ->
                val text = PDFTextStripper().getText(document)
                assertTrue(text.contains("Clipped prose."))
                assertTrue(text.contains("Texto."))
            }
        }
    }

    @Test fun preflightRejectsScansInvisibleOcrClippingType3CyclesSignaturesAndRestrictions() = runBlocking {
        val cases = listOf(
            "" to "No visible native prose",
            "BT /F1 12 Tf 3 Tr 1 0 0 1 40 220 Tm (Invisible OCR prose.) Tj ET" to "Invisible OCR",
            "BT /F1 12 Tf 4 Tr 1 0 0 1 40 220 Tm (Clipping text.) Tj ET" to "Text clipping",
            "BT /F1 12 Tf 1 .2 0 1 40 220 Tm (Oblique prose.) Tj ET" to "geometry"
        )
        for ((content, reason) in cases) withFiles { source, output ->
            simple(source, content, imageOnly = content.isEmpty())
            assertRejectedWithoutProvider(source, output, reason)
        }
        withFiles { source, output ->
            simple(source, "BT /F1 12 Tf 1 0 0 1 40 220 Tm (Signed prose.) Tj ET")
            PDDocument.load(source).use { document ->
                document.documentCatalog.cosObject.setItem(COSName.PERMS, PDSignature().cosObject)
                document.save(source)
            }
            assertRejectedWithoutProvider(source, output, "Signed")
        }
        withFiles { source, output ->
            simple(source, "BT /F1 12 Tf 1 0 0 1 40 220 Tm (Restricted prose.) Tj ET")
            PDDocument.load(source).use { document ->
                val permission = com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission().apply {
                    setCanModify(false); setCanExtractContent(false)
                }
                document.protect(com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner-secret", "", permission))
                document.save(source)
            }
            assertRejectedWithoutProvider(source, output, "permissions")
        }
        withFiles { source, output ->
            PDFBoxResourceLoader.init(context)
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(300f, 300f)); document.addPage(page); page.resources = PDResources()
                val form = PDFormXObject(document).apply { setBBox(PDRectangle(100f, 100f)); resources = PDResources() }
                form.resources.put(COSName.getPDFName("Loop"), form)
                form.contentStream.createOutputStream().use { it.write("/Loop Do".toByteArray()) }
                page.resources.put(COSName.getPDFName("Loop"), form)
                page.setContents(PDStream(document).apply { createOutputStream().use { it.write("/Loop Do".toByteArray()) } })
                document.save(source)
            }
            assertRejectedWithoutProvider(source, output, "Cyclic")
        }
        withFiles { source, output ->
            simple(source, "BT /F1 12 Tf 1 0 0 1 40 220 Tm (Type3 prose.) Tj ET")
            PDDocument.load(source).use { document ->
                document.getPage(0).resources.getFont(COSName.getPDFName("F1")).cosObject.setItem(COSName.SUBTYPE, COSName.TYPE3)
                document.save(source)
            }
            assertRejectedWithoutProvider(source, output, "Type3")
        }
    }

    private fun translateProse(request: String, translations: Map<String, String>, longText: String): String {
        val output = StringBuilder()
        var offset = 0
        marker.findAll(request).forEach { match ->
            val chunk = request.substring(offset, match.range.first)
            output.append(translateChunk(chunk, translations, longText))
            output.append(match.value)
            offset = match.range.last + 1
        }
        output.append(translateChunk(request.substring(offset), translations, longText))
        return output.toString()
    }

    private fun translateChunk(chunk: String, translations: Map<String, String>, longText: String): String {
        val leading = chunk.takeWhile(Char::isWhitespace)
        val trailing = chunk.takeLastWhile(Char::isWhitespace)
        val text = chunk.trim()
        val translation = when {
            text.contains("Right paragraph") -> longText
            text.isEmpty() -> text
            else -> translations[text] ?: "Traducción."
        }
        return if (text.isEmpty()) chunk else leading + translation + trailing
    }

    private suspend fun assertRejectedWithoutProvider(source: File, output: File, reason: String) {
        val original = source.readBytes(); var calls = 0
        val failure = try {
            PdfDocumentTranslator(context).translateCopy(source, output, "es", { _, _ -> calls++; Result.success("Texto.") }, { _, _ -> }); null
        } catch (exception: TranslationRejectedException) { exception }
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains(reason, ignoreCase = true))
        assertNotNull(failure.location)
        assertEquals(0, calls)
        assertFalse(output.exists())
        assertArrayEquals(original, source.readBytes())
    }

    private fun columns(file: File) {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(440f, 420f)); document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                graphics(document, stream)
                fun text(value: String, x: Float, y: Float, size: Float, selected: PDType1Font = font) {
                    stream.beginText(); stream.setFont(selected, size); stream.newLineAtOffset(x, y); stream.showText(value); stream.endText()
                }
                stream.setNonStrokingColor(.1f, .2f, .4f)
                text("Spanning full-width heading across the page.", 40f, 370f, 18f, PDType1Font.HELVETICA_BOLD)
                text("Left paragraph first.", 40f, 325f, 12f)
                text("Left paragraph next.", 40f, 309f, 12f)
                text("Right paragraph first.", 235f, 325f, 12f)
                text("Right paragraph next.", 235f, 309f, 12f, PDType1Font.HELVETICA_BOLD)
                text("(Lab, 2025)", 235f, 293f, 8f, PDType1Font.HELVETICA_OBLIQUE)
                text("Occlusion sample.", 40f, 260f, 12f)
                text("= 2", 235f, 260f, 12f)
                stream.setNonStrokingColor(.8f, .1f, .2f); stream.addRect(55f, 259f, 18f, 10f); stream.fill()
            }
            page.annotations = listOf(PDAnnotationLink().apply { rectangle = PDRectangle(40f, 40f, 20f, 20f) })
            document.documentInformation.title = "Preserved catalog metadata"
            document.save(file)
        }
    }

    private fun forms(file: File) {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document ->
            val pixels = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            try {
                for (y in 0..3) for (x in 0..3)
                    pixels.setPixel(x, y, if ((x + y) % 2 == 0) Color.MAGENTA else Color.GREEN)
                val image = LosslessFactory.createFromImage(document, pixels)
                val form = PDFormXObject(document).apply { setBBox(PDRectangle(140f, 60f)); resources = PDResources() }
                form.resources.put(COSName.getPDFName("F1"), font)
                form.resources.put(COSName.getPDFName("Im1"), image)
                form.contentStream.createOutputStream().use {
                    it.write("q 20 0 0 20 70 20 cm /Im1 Do Q BT /F1 12 Tf 1 0 0 1 5 20 Tm (Shared form prose.) Tj ET".toByteArray())
                }
                for (rotation in listOf(0, 90, 180, 270)) {
                    val page = PDPage(PDRectangle(440f, 440f)); document.addPage(page)
                    page.cropBox = PDRectangle(30f, 40f, 350f, 360f); page.rotation = rotation
                    PDPageContentStream(document, page).use { stream ->
                        stream.setNonStrokingColor(.85f, .9f, .95f); stream.addRect(30f, 40f, 350f, 360f); stream.fill()
                        for ((x, y) in listOf(60f to 300f, 210f to 150f)) {
                            stream.saveGraphicsState(); stream.transform(com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(x, y))
                            stream.drawForm(form); stream.restoreGraphicsState()
                        }
                    }
                }
            } finally { pixels.recycle() }
            val blank = PDPage(PDRectangle(440f, 440f)); document.addPage(blank)
            PDPageContentStream(document, blank).use {
                it.setNonStrokingColor(.3f, .5f, .7f); it.addRect(40f, 40f, 30f, 30f); it.fill()
            }
            document.save(file)
        }
    }

    private fun simple(file: File, content: String, imageOnly: Boolean = false) {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(300f, 300f)); document.addPage(page); page.resources = PDResources()
            page.resources.put(COSName.getPDFName("F1"), font)
            page.resources.put(COSName.getPDFName("F2"), PDType1Font.HELVETICA_BOLD)
            page.setContents(PDStream(document).apply { createOutputStream().use { it.write(content.toByteArray(Charsets.US_ASCII)) } })
            if (imageOnly) PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { graphics(document, it) }
            document.save(file)
        }
    }

    private fun graphics(document: PDDocument, stream: PDPageContentStream) {
        stream.setNonStrokingColor(.85f, .9f, .95f); stream.addRect(0f, 0f, 440f, 420f); stream.fill()
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0..11) for (x in 0..11) bitmap.setPixel(x, y, if ((x + y) % 2 == 0) Color.MAGENTA else Color.GREEN)
            stream.drawImage(LosslessFactory.createFromImage(document, bitmap), 270f, 70f, 60f, 60f)
        } finally { bitmap.recycle() }
        stream.setStrokingColor(.2f, .6f, .1f); stream.setLineWidth(2f)
        stream.moveTo(40f, 80f); stream.lineTo(150f, 180f); stream.stroke()
    }

    private fun colorCount(bitmap: Bitmap, color: Int): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (bitmap.getPixel(x, y) == color) count++
        return count
    }
    private fun magentaCoverage(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.red(pixel) > 200 && Color.green(pixel) < 100 && Color.blue(pixel) > 150) count++
        }
        return count
    }
    private fun containsPdfImage(
        resources: PDResources,
        visited: MutableSet<com.tom_roush.pdfbox.cos.COSStream> = mutableSetOf()
    ): Boolean {
        for (name in resources.xObjectNames) {
            when (val xObject = resources.getXObject(name)) {
                is PDImageXObject -> return true
                is PDFormXObject -> {
                    if (visited.add(xObject.cosObject) && xObject.resources?.let { containsPdfImage(it, visited) } == true)
                        return true
                }
            }
        }
        return false
    }

    private suspend fun withFiles(block: suspend (File, File) -> Unit) {
        val token = System.nanoTime()
        val source = File(context.cacheDir, "pdf-band-source-$token.pdf")
        val output = File(context.cacheDir, "pdf-band-output-$token.pdf.part")
        try { block(source, output) } finally { source.delete(); output.delete() }
    }
}
