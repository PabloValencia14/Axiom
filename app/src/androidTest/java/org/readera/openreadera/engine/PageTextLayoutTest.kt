package org.readera.openreadera.engine

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageTextLayoutTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nativeExtractionAbortsFormsAndMalformedAdjustmentsAndReleasesBoundedScratch() {
        val file = File.createTempFile("bounded-native-layout-", ".pdf", context.cacheDir)
        val engine = AndroidPdfEngine(context)
        try {
            PDDocument(MemoryUsageSetting.setupTempFileOnly(128L * 1024 * 1024).setTempDir(context.cacheDir)).use { document ->
                val fontName = COSName.getPDFName("F1")
                val formName = COSName.getPDFName("TextForm")
                fun formPage(content: String, repetitions: Int) {
                    val resources = PDResources().apply { put(fontName, PDType1Font.HELVETICA) }
                    val form = PDFormXObject(document).apply {
                        this.resources = resources
                        setBBox(PDRectangle(600f, 800f))
                    }
                    form.cosObject.createOutputStream(COSName.FLATE_DECODE).use {
                        it.write(content.toByteArray(Charsets.US_ASCII))
                    }
                    val page = PDPage(PDRectangle(600f, 800f)).apply {
                        this.resources = PDResources().apply { put(formName, form) }
                    }
                    page.setContents(PDStream(document).also { stream ->
                        stream.createOutputStream(COSName.FLATE_DECODE).use { output ->
                            repeat(repetitions) { output.write("/TextForm Do\n".toByteArray(Charsets.US_ASCII)) }
                        }
                    })
                    document.addPage(page)
                }
                // Duplicate suppression retains few output characters; the input glyph budget
                // must still abort immediately rather than be swallowed by Form /Do recovery.
                formPage("BT /F1 12 Tf 10 10 Td (${"A".repeat(1000)}) Tj ET", 101)
                formPage("BT /F1 12 Tf [[(private fixture text)] (visible fixture)] TJ ET", 1)
                val expanded = PDStream(document)
                expanded.createOutputStream(COSName.FLATE_DECODE).use { output ->
                    val whitespace = ByteArray(4096) { 32 }
                    // More than the 64 MiB native parser budget, without a single glyph callback.
                    repeat(16_385) { output.write(whitespace) }
                }
                document.addPage(PDPage(PDRectangle(600f, 800f)).apply { setContents(expanded) })
                val valid = PDPage(PDRectangle(600f, 800f))
                document.addPage(valid)
                PDPageContentStream(document, valid).use {
                    it.beginText()
                    it.setFont(PDType1Font.HELVETICA, 24f)
                    it.newLineAtOffset(50f, 700f)
                    it.showText("Valid page")
                    it.endText()
                }
                document.save(file)
            }
            assertTrue(engine.open(file.absolutePath))
            val parserField = AndroidPdfEngine::class.java.getDeclaredField("textDocument").apply { isAccessible = true }
            for (page in 0..2) {
                val result = engine.getPageTextLayout(page)
                assertTrue(result.text.isEmpty())
                assertTrue(result.words.isEmpty())
                assertNull("Failed native extraction must release its parser", parserField.get(engine))
                assertSame(result, engine.getPageTextLayout(page))
            }
            assertValid(engine.getPageTextLayout(3))
            assertEquals("Valid page", engine.getPageText(3).trim())
        } finally {
            engine.close()
            file.delete()
        }
    }

    @Test
    fun comicPageLabelsAreNotSelectableText() {
        val file = File.createTempFile("comic-page-layout-", ".cbz", context.cacheDir)
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888)
        val engine = CbzEngine()
        try {
            bitmap.eraseColor(Color.WHITE)
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("page.png"))
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                zip.closeEntry()
            }
            assertTrue(engine.open(file.absolutePath))
            assertEquals("", engine.getPageText(0))
            assertEquals("", engine.getPageTextLayout(0).text)
            assertTrue(engine.getPageTextLayout(0).words.isEmpty())
            assertTrue(engine.getPageTextLineBounds(0).isEmpty())
        } finally {
            engine.close()
            bitmap.recycle()
            file.delete()
        }
    }

    @Test
    fun pdfNativeWordsMatchCroppedRotatedRasterAndCacheWithoutOcr() {
        val engine = AndroidPdfEngine(context) // Initializes the existing PDFBox resource loader.
        val file = File.createTempFile("native-page-layout-", ".pdf", context.cacheDir)
        try {
            PDDocument().use { document ->
                for (rotation in listOf(0, 90, 180, 270)) {
                    val page = PDPage(PDRectangle(600f, 800f)).apply {
                        cropBox = PDRectangle(40f, 80f, 400f, 600f)
                        this.rotation = rotation
                    }
                    document.addPage(page)
                    PDPageContentStream(document, page).use { content ->
                        content.beginText()
                        content.setFont(PDType1Font.HELVETICA, 24f)
                        content.newLineAtOffset(100f, 590f) // crop-local x=60, baseline y=510
                        content.showText("Native words")
                        content.endText()
                    }
                }
                document.save(file)
            }
            assertTrue(engine.open(file.absolutePath))
            for (pageIndex in 0..3) {
                val layout = engine.getPageTextLayout(pageIndex)
                assertValid(layout)
                assertEquals(PageTextSource.NATIVE, layout.source)
                assertEquals(listOf("Native", "words"), layout.words.map { layout.text.substring(it.startUtf16, it.endUtf16) })
                assertEquals(layout.text, engine.getPageText(pageIndex))
                assertSame(layout, engine.getPageTextLayout(pageIndex))
                val first = layout.words.first().bounds
                when (pageIndex) {
                    0 -> { assertEquals(0.15f, first.left, 0.01f); assertEquals(0.15f, first.bottom, 0.01f) }
                    1 -> { assertEquals(0.85f, first.left, 0.01f); assertEquals(0.15f, first.top, 0.01f) }
                    2 -> { assertEquals(0.85f, first.top, 0.01f); assertEquals(0.85f, first.right, 0.01f) }
                    3 -> { assertEquals(0.15f, first.right, 0.01f); assertEquals(0.85f, first.bottom, 0.01f) }
                }
                val size = engine.getPageSize(pageIndex)
                val bitmap = Bitmap.createBitmap(size.width.toInt() * 2, size.height.toInt() * 2, Bitmap.Config.ARGB_8888)
                try {
                    assertTrue(engine.renderPage(pageIndex, bitmap, RenderOptions()))
                    assertWordsOverlapInk(layout, bitmap)
                } finally { bitmap.recycle() }
            }
            engine.close()
            assertTrue(engine.open(file.absolutePath))
            assertValid(engine.getPageTextLayout(0))
        } finally {
            engine.close()
            file.delete()
        }
    }

    @Test
    fun txtUsesRenderedLayoutAtPreviewResolutionAndKeepsSurrogatesAcrossPages() {
        val file = File.createTempFile("txt-page-layout-", ".txt", context.cacheDir)
        val engine = TxtEngine()
        try {
            file.writeText("First second café e\u0301 😀 text.\n" + "Readable native body words. ".repeat(30))
            assertTrue(engine.open(file.absolutePath))
            val options = RenderOptions(fontSizeSp = 24, bionicReading = true)
            engine.applyOptions(options)
            val layout = engine.getPageTextLayout(0)
            assertValid(layout)
            assertEquals(engine.getPageText(0), layout.text)
            assertSame(layout, engine.getPageTextLayout(0))
            assertTrue(layout.words.size > 5)
            val size = engine.getPageSize(0)
            val bitmap = Bitmap.createBitmap(700, (700 * size.height / size.width).toInt(), Bitmap.Config.ARGB_8888)
            try {
                assertTrue(engine.renderPage(0, bitmap, options))
                assertWordsOverlapInk(layout, bitmap)
            } finally { bitmap.recycle() }
            engine.close()
            file.writeText("a".repeat(1799) + "😀" + " b".repeat(950))
            assertTrue(engine.open(file.absolutePath))
            val pages = (0 until engine.getPageCount()).map(engine::getPageText)
            assertTrue(pages.any { it.contains("😀") })
            pages.forEach { text -> assertNoBrokenSurrogates(text) }
        } finally {
            engine.close()
            file.delete()
        }
    }

    @Test
    fun epubWordsUsePageLocalUtf16OffsetsAndReflowInvalidatesGeometry() {
        val file = File.createTempFile("epub-page-layout-", ".epub", context.cacheDir)
        val engine = EpubEngine()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name: String, value: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
                }
                entry("mimetype", "application/epub+zip")
                entry("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>""")
                entry("OEBPS/book.opf", """<package><metadata><dc:title xmlns:dc="x">Layout</dc:title></metadata><manifest><item id="body" href="body.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="body"/></spine></package>""")
                entry("OEBPS/body.xhtml", "<html><body><p>${"Native café 😀 reader words wrap correctly. ".repeat(200)}</p></body></html>")
            }
            assertTrue(engine.open(file.absolutePath))
            val first = engine.getPageTextLayout(0)
            assertValid(first)
            assertEquals(engine.getPageText(0), first.text)
            assertEquals(PageTextSource.NATIVE, first.source)
            val options = RenderOptions(fontSizeSp = 30, marginHorizontalDp = 40)
            engine.applyOptions(options)
            val reflowed = engine.getPageTextLayout(0)
            assertValid(reflowed)
            assertNotSame(first, reflowed)
            assertTrue(reflowed.words.first().bounds.left > first.words.first().bounds.left)
            val size = engine.getPageSize(0)
            val bitmap = Bitmap.createBitmap(700, (700 * size.height / size.width).toInt(), Bitmap.Config.ARGB_8888)
            try {
                assertTrue(engine.renderPage(0, bitmap, options))
                assertWordsOverlapInk(reflowed, bitmap)
            } finally { bitmap.recycle() }
        } finally {
            engine.close()
            file.delete()
        }
    }

    private fun assertValid(layout: PageTextLayout) {
        assertTrue(layout.text.isNotBlank())
        assertTrue(layout.words.isNotEmpty())
        assertNoBrokenSurrogates(layout.text)
        for (word in layout.words) {
            assertTrue(word.startUtf16 >= 0 && word.endUtf16 in (word.startUtf16 + 1)..layout.text.length)
            assertTrue(layout.text.substring(word.startUtf16, word.endUtf16).isNotBlank())
            assertFalse(word.startUtf16 > 0 && Character.isLowSurrogate(layout.text[word.startUtf16]))
            assertFalse(word.endUtf16 < layout.text.length && Character.isLowSurrogate(layout.text[word.endUtf16]))
            assertTrue(word.lineIndex >= 0)
            assertTrue(word.bounds.left >= 0f && word.bounds.top >= 0f && word.bounds.right <= 1f && word.bounds.bottom <= 1f)
            assertTrue(word.bounds.width() > 0f && word.bounds.height() > 0f)
        }
    }

    private fun assertNoBrokenSurrogates(text: String) {
        for (index in text.indices) {
            if (Character.isHighSurrogate(text[index])) assertTrue(index + 1 < text.length && Character.isLowSurrogate(text[index + 1]))
            if (Character.isLowSurrogate(text[index])) assertTrue(index > 0 && Character.isHighSurrogate(text[index - 1]))
        }
    }

    private fun assertWordsOverlapInk(layout: PageTextLayout, bitmap: Bitmap) {
        // Ignore whitespace/punctuation-only segments; test the first real body words, not headers.
        for (word in layout.words.filter { layout.text.substring(it.startUtf16, it.endUtf16).any(Char::isLetter) }.take(12)) {
            val bounds = word.bounds
            var found = false
            for (y in (bounds.top * bitmap.height).toInt().coerceAtLeast(0) until (bounds.bottom * bitmap.height).toInt().coerceAtMost(bitmap.height)) {
                for (x in (bounds.left * bitmap.width).toInt().coerceAtLeast(0) until (bounds.right * bitmap.width).toInt().coerceAtMost(bitmap.width)) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.red(pixel) < 150 && Color.green(pixel) < 150 && Color.blue(pixel) < 150) found = true
                }
            }
            assertTrue("Native word box must overlap rendered body glyphs", found)
        }
    }
}
