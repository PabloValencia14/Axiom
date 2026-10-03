package org.readera.openreadera.data.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readera.openreadera.engine.AndroidPdfEngine
import org.readera.openreadera.engine.PageTextSource
import org.readera.openreadera.engine.RenderOptions

@RunWith(AndroidJUnit4::class)
class PageOcrRecognizerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun imageOnlyPdfHasNoNativeWordsAndRealBundledOcrProducesNormalizedRanges() = runBlocking {
        val file = File.createTempFile("image-only-page-", ".pdf", context.cacheDir)
        val image = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        val engine = AndroidPdfEngine(context)
        val recognizer = PageOcrRecognizer()
        try {
            val canvas = Canvas(image)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 72f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            canvas.drawText("OFFLINE READER", 100f, 240f, paint)
            canvas.drawText("HIGHLIGHT WORDS", 100f, 380f, paint)
            val pdf = PdfDocument()
            try {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(1200, 1600, 1).create())
                page.canvas.drawBitmap(image, 0f, 0f, null) // Raster only: no invisible/native text layer.
                pdf.finishPage(page)
                file.outputStream().use(pdf::writeTo)
            } finally {
                pdf.close()
            }
            assertTrue(engine.open(file.absolutePath))
            val native = engine.getPageTextLayout(0)
            assertTrue(native.text.isBlank())
            assertTrue(native.words.isEmpty())
            assertEquals(PageTextSource.NATIVE, native.source)
            assertTrue(engine.renderPage(0, image, RenderOptions()))
            val result = withTimeout(60_000) { recognizer.recognize(image) }
            assertEquals(PageTextSource.OCR, result.source)
            assertTrue(result.text.contains("OFFLINE"))
            assertTrue(result.text.contains("READER"))
            assertTrue(result.text.contains("HIGHLIGHT"))
            assertTrue(result.words.size >= 4)
            assertTrue(result.words.map { it.lineIndex }.distinct().size >= 2)
            val offline = result.words.first { result.text.substring(it.startUtf16, it.endUtf16) == "OFFLINE" }
            assertEquals(100f / image.width, offline.bounds.left, 0.02f)
            assertTrue(offline.bounds.top in 0.09f..0.16f)
            for (word in result.words) {
                assertTrue(word.startUtf16 >= 0 && word.endUtf16 in (word.startUtf16 + 1)..result.text.length)
                assertTrue(result.text.substring(word.startUtf16, word.endUtf16).isNotBlank())
                assertTrue(word.bounds.left >= 0f && word.bounds.top >= 0f && word.bounds.right <= 1f && word.bounds.bottom <= 1f)
                assertTrue(word.bounds.width() > 0f && word.bounds.height() > 0f)
            }
            assertFalse(image.isRecycled)
        } finally {
            recognizer.close()
            engine.close()
            image.recycle()
            file.delete()
        }
    }

    @Test
    fun closedRecognizerRejectsRequestsWithoutTakingBitmapOwnership() = runBlocking {
        val recognizer = PageOcrRecognizer()
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            recognizer.close()
            recognizer.close()
            try {
                recognizer.recognize(bitmap)
                fail("Closed recognizer must not process another page")
            } catch (_: IllegalStateException) {
                assertFalse(bitmap.isRecycled)
            }
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }
}
