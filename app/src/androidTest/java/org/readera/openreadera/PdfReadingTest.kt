package org.readera.openreadera

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readera.openreadera.data.db.AppDatabase
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.ReaderColorTheme
import org.readera.openreadera.data.model.ReaderViewMode
import org.readera.openreadera.data.preferences.ReaderPreferences
import org.readera.openreadera.data.repository.BookRepository
import org.readera.openreadera.data.pdf.PdfDocumentEditor
import org.readera.openreadera.data.pdf.PdfOcrLine
import org.readera.openreadera.data.pdf.PdfOcrPage
import org.readera.openreadera.data.scanner.DocumentScanProcessor
import org.readera.openreadera.engine.AndroidPdfEngine
import org.readera.openreadera.engine.EngineManager
import org.readera.openreadera.engine.RenderOptions
import org.readera.openreadera.ui.reader.ReaderViewModel
import java.io.File
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.readera.openreadera.translation.DocumentTranslationCoordinator
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class PdfReadingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun existingPdf() {
        val path = InstrumentationRegistry.getArguments().getString("pdfPath")
        org.junit.Assume.assumeTrue("Supply -e pdfPath to benchmark an existing document", path != null)
        val engine = AndroidPdfEngine(instrumentation.targetContext)
        try {
            val start = SystemClock.elapsedRealtimeNanos()
            assertTrue(engine.open(checkNotNull(path)))
            val opened = SystemClock.elapsedRealtimeNanos()
            val count = engine.getPageCount()
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Existing PDF pages=$count openMs=${(opened - start) / 1e6}\n") })
            for (index in listOf(0, 1, count / 2, count - 1).distinct().filter { it in 0 until count }) {
                val size = engine.getPageSize(index)
                val targetWidth = 3200
                val bitmap = Bitmap.createBitmap(targetWidth, (targetWidth * size.height / size.width).toInt().coerceIn(400, 8000), Bitmap.Config.ARGB_8888)
                try {
                    val begin = SystemClock.elapsedRealtimeNanos()
                    assertTrue(engine.renderPage(index, bitmap, RenderOptions()))
                    val end = SystemClock.elapsedRealtimeNanos()
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Existing PDF page=$index size=${size.width}x${size.height} renderMs=${(end - begin) / 1e6}\n") })
                    if (index == minOf(1, count - 1)) {
                        val output = File(instrumentation.targetContext.externalCacheDir, "pdf-validation.png")
                        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            engine.close()
        }
    }

    private fun fixture(): File {
        val file = File.createTempFile("paper-slides-", ".pdf", instrumentation.targetContext.cacheDir)
        val pdf = PdfDocument()
        try {
            repeat(8) { index ->
                val width = if (index % 2 == 0) 600 else 960
                val height = if (index % 2 == 0) 850 else 540
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                page.canvas.drawColor(pageColor(index))
                val paint = Paint().apply { color = Color.BLACK; textSize = 12f }
                repeat(25) { row ->
                    page.canvas.drawText("Paper / slide ${index + 1} - Formula E = mc2 - row $row", 30f, 40f + row * 16, paint)
                }
                page.canvas.drawRect(40f, height - 100f, width - 40f, height - 40f, paint)
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        return file
    }

    private fun pageColor(index: Int) = Color.rgb(180 + index * 5, 210, 230)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun awaitCondition(condition: () -> Boolean) = runBlocking {
        withTimeout(30_000) { while (!condition()) delay(20) }
    }

    @Test
    fun portraitLandscapePreviewThemesAndReopen() {
        val file = fixture()
        val engine = AndroidPdfEngine(instrumentation.targetContext)
        try {
            assertTrue(engine.open(file.path))
            assertEquals(8, engine.getPageCount())
            for (index in 0..1) {
                val size = engine.getPageSize(index)
                assertEquals(if (index == 0) 600f else 960f, size.width)
                val preview = Bitmap.createBitmap(600, (600 * size.height / size.width).toInt(), Bitmap.Config.ARGB_8888)
                val full = Bitmap.createBitmap(2400, (2400 * size.height / size.width).toInt(), Bitmap.Config.ARGB_8888)
                try {
                    val start = SystemClock.elapsedRealtimeNanos()
                    assertTrue(engine.renderPage(index, preview, RenderOptions()))
                    val previewDone = SystemClock.elapsedRealtimeNanos()
                    assertTrue(engine.renderPage(index, full, RenderOptions()))
                    val fullDone = SystemClock.elapsedRealtimeNanos()
                    assertEquals(pageColor(index), preview.getPixel(0, 0))
                    assertEquals(pageColor(index), full.getPixel(0, 0))
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "PDF page=$index previewMs=${(previewDone - start) / 1e6} fullMs=${(fullDone - previewDone) / 1e6}\n")
                    })
                    for (options in listOf(RenderOptions(nightMode = true), RenderOptions(twilightMode = true), RenderOptions(oledMode = true))) {
                        assertTrue(engine.renderPage(index, preview, options))
                        assertNotEquals(pageColor(index), preview.getPixel(0, 0))
                    }
                } finally {
                    preview.recycle()
                    full.recycle()
                }
            }
            engine.close()
            assertTrue(engine.open(file.path))
            assertEquals(600f, engine.getPageSize(0).width)
        } finally {
            engine.close()
            file.delete()
        }
    }

    @Test
    fun pdfSearchFindsCaseInsensitiveMatchesAndCapsResults() {
        val file = fixture()
        val engine = AndroidPdfEngine(instrumentation.targetContext)
        try {
            assertTrue(engine.open(file.path))
            val pageThreeResults = engine.search("sLiDe 3")
            assertTrue(pageThreeResults.isNotEmpty())
            assertTrue(pageThreeResults.all { it.page == 2 })
            assertTrue(pageThreeResults.all { it.snippet.contains("slide 3", ignoreCase = true) })
            assertTrue(engine.search("not present").isEmpty())
            assertTrue(engine.search("  ").isEmpty())
            assertEquals(50, engine.search("Formula E").size)
        } finally {
            engine.close()
            file.delete()
        }
    }

    @Test
    fun pageEditsPreserveSourceAndRespectOrderRotationAndDeletion() = runBlocking {
        val source = fixture()
        val token = System.nanoTime()
        val edited = File(instrumentation.targetContext.cacheDir, "edited-$token.pdf")
        val extracted = File(instrumentation.targetContext.cacheDir, "extracted-$token.pdf")
        val merged = File(instrumentation.targetContext.cacheDir, "merged-$token.pdf")
        val invalid = File(instrumentation.targetContext.cacheDir, "invalid-$token.pdf")
        val originalBytes = source.readBytes()
        val editor = PdfDocumentEditor(instrumentation.targetContext)
        try {
            editor.applyPagePlan(
                input = source,
                order = listOf(2, 0, 1, 3, 4, 5, 6, 7),
                deletedPageIndices = setOf(0),
                rotations = mapOf(2 to 90),
                output = edited
            )
            assertEquals(7, editor.pageCount(edited))
            assertTrue(editor.extractPageText(edited, 0).contains("slide 3", ignoreCase = true))
            assertTrue(editor.extractPageText(edited, 1).contains("slide 2", ignoreCase = true))
            PDDocument.load(edited).use { assertEquals(90, it.getPage(0).rotation) }

            editor.extractPages(source, listOf(7, 1), extracted)
            assertEquals(2, editor.pageCount(extracted))
            assertTrue(editor.extractPageText(extracted, 0).contains("slide 8", ignoreCase = true))
            assertTrue(editor.extractPageText(extracted, 1).contains("slide 2", ignoreCase = true))

            editor.merge(listOf(source, source), merged)
            assertEquals(16, editor.pageCount(merged))
            assertTrue(editor.extractPageText(merged, 8).contains("slide 1", ignoreCase = true))


            val invalidPlan = runCatching {
                editor.applyPagePlan(
                    source,
                    listOf(0, 0, 1, 2, 3, 4, 5, 6),
                    emptySet(),
                    emptyMap(),
                    invalid
                )
            }
            assertTrue(invalidPlan.isFailure)
            assertFalse(invalid.exists())
            assertArrayEquals(originalBytes, source.readBytes())
        } finally {
            source.delete()
            edited.delete()
            extracted.delete()
            merged.delete()
            invalid.delete()
        }
    }

    @Test
    fun searchableOcrLayerIsExtractableAndSearchable() = runBlocking {
        val source = fixture()
        val originalBytes = source.readBytes()
        val token = System.nanoTime()
        val searchable = File(instrumentation.targetContext.cacheDir, "searchable-$token.pdf")
        val editor = PdfDocumentEditor(instrumentation.targetContext)
        try {
            val pages = (0 until 8).map { index ->
                PdfOcrPage(
                    width = 600,
                    height = 850,
                    lines = listOf(PdfOcrLine("Searchable OCR page ${index + 1}", 30f, 30f, 300f, 60f))
                )
            }
            editor.addOcrTextLayer(source, pages, searchable)
            assertTrue(editor.extractPageText(searchable, 4).contains("Searchable OCR page 5", ignoreCase = true))
            val matches = withContext(Dispatchers.IO) {
                editor.searchBlocking(searchable, "SEARCHABLE OCR PAGE 5")
            }
            assertEquals(listOf(4), matches.map { it.page })
            assertArrayEquals(originalBytes, source.readBytes())
        } finally {
            source.delete()
            searchable.delete()
        }
    }

    @Test
    fun imageToPdfAndPdfToImageAndTextExportsProduceFiles() = runBlocking {
        val context = instrumentation.targetContext
        val processor = DocumentScanProcessor(context)
        val editor = PdfDocumentEditor(context)
        val token = System.nanoTime()
        val inputImage = File(context.cacheDir, "input-$token.jpg")
        val sourcePdf = fixture()
        var imagePdf: File? = null
        var textExport: File? = null
        val outputImages = File(context.cacheDir, "images-$token")
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            inputImage.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            }
            imagePdf = processor.imageUrisToPdf(listOf(Uri.fromFile(inputImage)), "image-to-pdf-$token")
            assertEquals(1, editor.pageCount(checkNotNull(imagePdf)))
            val images = processor.pdfToImages(checkNotNull(imagePdf), outputImages)
            assertEquals(1, images.size)
            assertTrue(images.single().isFile && images.single().length() > 0L)

            textExport = processor.pdfToText(sourcePdf)
            assertTrue(checkNotNull(textExport).readText().contains("slide 1", ignoreCase = true))
        } finally {
            bitmap.recycle()
            inputImage.delete()
            sourcePdf.delete()
            imagePdf?.delete()
            textExport?.delete()
            outputImages.deleteRecursively()
        }
    }

    @Test
    fun readerPrefetchSettingsRapidNavigationAndDoublePage() = runBlocking {
        val file = fixture()
        // Separate preferences and in-memory DB: no user library, progress or sync writes.
        val prefix = "pdf_test_${System.nanoTime()}_"
        val prefsNames = mutableSetOf<String>()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val isolated = prefix + name
                synchronized(prefsNames) { prefsNames.add(isolated) }
                return super.getSharedPreferences(isolated, mode)
            }
        }
        context.getSharedPreferences("google_sync_prefs", Context.MODE_PRIVATE).edit().putBoolean("explicitly_signed_out", true).commit()
        context.getSharedPreferences("drive_sync_data", Context.MODE_PRIVATE).edit().putBoolean("auto_sync_enabled", false).commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = BookRepository(db.bookDao(), db.bookmarkDao(), db.quoteDao(), db.collectionDao(), db.searchHistoryDao(), db.drawingStrokeDao())
        val preferences = ReaderPreferences(context)
        preferences.updateTheme(ReaderColorTheme.DAY)
        val id = repository.insertBook(Book(title = "PDF regression fixture", filePath = file.path, format = "PDF", currentPage = 4, totalPages = 8))
        val store = ViewModelStore()
        lateinit var model: ReaderViewModel
        val started = SystemClock.elapsedRealtimeNanos()
        try {
            main {
                model = ReaderViewModel(id, repository, preferences, EngineManager(context), context)
                store.put("pdf", model)
                model.setViewportDimensions(2136, 3200)
            }
            awaitCondition { model.uiState.value.currentPageBitmap?.width ?: 0 >= 3000 }
            assertEquals(4, model.uiState.value.currentPage)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "PDF reader firstSharpMs=${(SystemClock.elapsedRealtimeNanos() - started) / 1e6}\n")
            })
            val firstShown = checkNotNull(model.uiState.value.currentPageBitmap)
            main { model.goToPage(5) }
            awaitCondition {
                model.uiState.value.currentPageBitmap?.width ?: 0 >= 3000 &&
                    !model.uiState.value.isLoadingPage &&
                    model.uiState.value.currentPageBitmap?.getPixel(0, 0) == pageColor(5)
            }
            val nextShown = checkNotNull(model.uiState.value.currentPageBitmap)
            main {
                model.goToPage(4)
                assertSame(firstShown, model.uiState.value.currentPageBitmap)
                model.goToPage(5)
                assertSame(nextShown, model.uiState.value.currentPageBitmap)
                assertFalse(model.uiState.value.isLoadingPage)
            }
            val unchanged = model.uiState.value.currentPageBitmap
            main {
                model.updateBrightness(0.5f)
                model.updateFontSize(24)
                model.updateLineSpacing(1.8f)
                model.updateMargin(30)
            }
            instrumentation.waitForIdleSync()
            assertSame(unchanged, model.uiState.value.currentPageBitmap)
            assertEquals(5, model.uiState.value.currentPage)
            main { model.setViewportDimensions(3200, 2136) }
            awaitCondition { model.uiState.value.currentPageBitmap?.width == 4096 }
            assertEquals(pageColor(5), model.uiState.value.currentPageBitmap!!.getPixel(0, 0))
            // Compose can still hold old images after viewport invalidation or cache eviction.
            assertFalse(firstShown.isRecycled)
            assertFalse(nextShown.isRecycled)
            assertEquals(pageColor(4), firstShown.getPixel(0, 0))
            main { listOf(1, 7, 2, 0, 6, 7).forEach(model::goToPage) }
            awaitCondition { model.uiState.value.currentPage == 7 && model.uiState.value.currentPageBitmap?.getPixel(0, 0) == pageColor(7) }
            main { model.goToPage(2); model.updateViewMode(ReaderViewMode.DOUBLE_PAGE) }
            awaitCondition {
                (model.uiState.value.currentPageBitmap?.width ?: 0) > 1000 &&
                    (model.uiState.value.secondPageBitmap?.width ?: 0) > 1000 &&
                    !model.uiState.value.isLoadingPage
            }
            assertEquals(2, model.uiState.value.currentPage)
            assertEquals(pageColor(2), model.uiState.value.currentPageBitmap!!.getPixel(0, 0))
            assertEquals(pageColor(3), model.uiState.value.secondPageBitmap!!.getPixel(0, 0))
            main { model.updateTheme(ReaderColorTheme.NIGHT) }
            awaitCondition { model.uiState.value.currentPageBitmap?.getPixel(0, 0) != pageColor(2) }
            main { model.updateTheme(ReaderColorTheme.DAY) }
            awaitCondition { model.uiState.value.currentPageBitmap?.getPixel(0, 0) == pageColor(2) }
        } finally {
            main { store.clear() }
            // Allow the existing asynchronous progress writer and engine close to finish.
            delay(500)
            db.close()
            file.delete()
            synchronized(prefsNames) { prefsNames.forEach { instrumentation.targetContext.deleteSharedPreferences(it) } }
        }
    }
    @Test
    fun nativeTranslationCopyPreservesGraphicsAndReopensInActualEngine() = runBlocking {
        val context = instrumentation.targetContext
        PDFBoxResourceLoader.init(context)
        val root = File(context.cacheDir, "native-pdf-engine-${System.nanoTime()}").apply { mkdirs() }
        val source = File(root, "self-authored.pdf")
        val outputDirectory = File(root, "output").apply { mkdirs() }
        val evidence = File(context.getExternalFilesDir(null), "native-translation-${System.nanoTime()}").apply { mkdirs() }
        val checker = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until checker.height) for (x in 0 until checker.width)
                checker.setPixel(x, y, if ((x + y) % 2 == 0) Color.MAGENTA else Color.GREEN)
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(440f, 420f))
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.setNonStrokingColor(.86f, .91f, .96f)
                    stream.addRect(0f, 0f, 440f, 420f)
                    stream.fill()
                    stream.drawImage(LosslessFactory.createFromImage(document, checker), 185f, 110f, 56f, 56f)
                    stream.setStrokingColor(.18f, .58f, .22f)
                    stream.setLineWidth(2f)
                    stream.moveTo(188f, 205f); stream.lineTo(242f, 245f); stream.stroke()
                    stream.setNonStrokingColor(.1f, .2f, .4f)
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(34f, 345f); stream.showText("Spanning heading.")
                    stream.newLineAtOffset(0f, -35f); stream.showText("Left paragraph first.")
                    stream.newLineAtOffset(0f, -16f); stream.showText("Left paragraph next.")
                    stream.endText()
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(258f, 310f); stream.showText("Right paragraph first.")
                    stream.newLineAtOffset(0f, -16f); stream.showText("Right paragraph next.")
                    stream.endText()
                }
                document.documentInformation.title = "Self-authored native translation fixture"
                document.save(source)
            }
        } finally {
            checker.recycle()
        }
        val originalBytes = source.readBytes()
        val sourceHash = MessageDigest.getInstance("SHA-256").digest(originalBytes)
        var originalPageWidth = 0f
        var originalPageHeight = 0f
        val sourceEngine = AndroidPdfEngine(context)
        val sourceRender = Bitmap.createBitmap(880, 840, Bitmap.Config.ARGB_8888)
        try {
            assertTrue(sourceEngine.open(source.path))
            assertEquals(1, sourceEngine.getPageCount())
            originalPageWidth = sourceEngine.getPageSize(0).width
            originalPageHeight = sourceEngine.getPageSize(0).height
            assertTrue(sourceEngine.renderPage(0, sourceRender, RenderOptions()))
            assertEquals(Color.rgb(219, 232, 245), sourceRender.getPixel(20, 20))
            savePdfEvidence(sourceRender, File(evidence, "original-render.png"))
        } finally {
            sourceEngine.close()
        }

        val calls = mutableListOf<String>()
        val coordinator = DocumentTranslationCoordinator(context)
        val translated = coordinator.translateCopy(
            source = coordinator.resolveSource(source.path),
            outputDirectory = outputDirectory,
            title = "Self-authored fixture",
            targetLanguage = "es",
            translate = { text, language ->
                assertEquals("es", language)
                calls += text
                Result.success(
                    when (text) {
                        "Spanning heading." -> "Titulo."
                        "Left paragraph first. Left paragraph next." -> "Texto izquierdo."
                        "Right paragraph first. Right paragraph next." -> "Texto derecho."
                        else -> error("Unexpected fixture paragraph: $text")
                    }
                )
            },
            onProgress = { _, _ -> }
        )
        assertEquals(listOf("Spanning heading.", "Left paragraph first. Left paragraph next.", "Right paragraph first. Right paragraph next."), calls)
        assertEquals("pdf", translated.extension)
        assertArrayEquals(originalBytes, source.readBytes())
        assertArrayEquals(sourceHash, MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
        assertEquals(listOf(translated), outputDirectory.listFiles()!!.toList())

        val reopened = AndroidPdfEngine(context)
        val rendered = Bitmap.createBitmap(880, 840, Bitmap.Config.ARGB_8888)
        try {
            assertTrue(reopened.open(translated.path))
            assertEquals(1, reopened.getPageCount())
            assertEquals(originalPageWidth, reopened.getPageSize(0).width)
            assertEquals(originalPageHeight, reopened.getPageSize(0).height)
            assertTrue(reopened.renderPage(0, rendered, RenderOptions()))
            val text = reopened.getPageText(0)
            assertTrue(text, text.contains("Titulo."))
            assertTrue(text, text.contains("Texto izquierdo."))
            assertTrue(text, text.contains("Texto derecho."))
            assertFalse(text, text.contains("Left paragraph first."))
            assertFalse(text, text.contains("Right paragraph first."))
            assertFalse(sourceRender.sameAs(rendered))
            // Background, center artwork and vector remain byte-for-byte identical outside old/new text ink.
            val old = sourceRender
            val scaleX = old.width.toFloat() / 440f
            val scaleY = old.height.toFloat() / 420f
            val textZones = listOf(
                android.graphics.RectF(0f, 35f, 230f, 165f),
                android.graphics.RectF(245f, 95f, 440f, 145f)
            ).map { android.graphics.Rect((it.left * scaleX).toInt(), (it.top * scaleY).toInt(), (it.right * scaleX).toInt(), (it.bottom * scaleY).toInt()) }
            for (y in 0 until rendered.height step 3) for (x in 0 until rendered.width step 3) {
                if (textZones.none { it.contains(x, y) }) assertEquals("Unchanged graphic pixel $x,$y", old.getPixel(x, y), rendered.getPixel(x, y))
            }
            savePdfEvidence(rendered, File(evidence, "translated-render.png"))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "Native PDF translation output: ${translated.path}\nNative PDF visual evidence: ${evidence.path}\n")
            })
        } finally {
            reopened.close()
            rendered.recycle()
            sourceRender.recycle()
            root.deleteRecursively()
        }
    }

    private fun savePdfEvidence(bitmap: Bitmap, file: File) {
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
