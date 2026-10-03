package org.readera.openreadera.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import android.content.Context
import org.readera.openreadera.data.pdf.PdfDocumentEditor
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument

class AndroidPdfEngine(context: Context) : DocumentEngine {
    private val pdfDocumentEditor = PdfDocumentEditor(context)
    private val textScratchDir = context.applicationContext.cacheDir
    private var pfd: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null
    private val pageSizes = mutableMapOf<Int, PageSize>()
    private val pageTextLayoutCache = PageTextLayoutCache()
    private var textDocument: PDDocument? = null
    private var filterScratchBitmap: Bitmap? = null
    private var openedFile: File? = null

    companion object {
        private const val TAG = "AndroidPdfEngine"
        private const val HIGH_QUALITY_MIN_WIDTH = 1200
        private const val MAX_SEARCH_RESULTS = 50
        private const val MAX_NATIVE_TEXT_SCRATCH_BYTES = 64L * 1024 * 1024
    }

    override val isAvailable: Boolean = true

    override val engineName: String
        get() = "Android Core Renderer"

    @Synchronized
    override fun open(path: String, password: String?): Boolean {
        close()
        return try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) {
                Log.e(TAG, "File not accessible: $path")
                return false
            }
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(pfd!!)
            openedFile = file
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open PDF with PdfRenderer", e)
            close()
            false
        }
    }

    @Synchronized
    override fun getPageCount(): Int {
        return pdfRenderer?.pageCount ?: 0
    }

    override fun getPageSize(pageIndex: Int): PageSize = synchronized(this) {
        val renderer = pdfRenderer ?: return PageSize(595f, 842f)
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return PageSize(595f, 842f)

        return try {
            pageSizes[pageIndex]?.let { return it }
            val page = obtainPage(pageIndex)
            val size = PageSize(page.width.toFloat(), page.height.toFloat())
            pageSizes[pageIndex] = size
            size
        } catch (e: Exception) {
            Log.e(TAG, "Error getting page size for $pageIndex", e)
            PageSize(595f, 842f)
        }
    }

    override fun renderPage(pageIndex: Int, targetBitmap: Bitmap, options: RenderOptions): Boolean = synchronized(this) {
        val renderer = pdfRenderer ?: return false
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return false

        return try {
            val page = obtainPage(pageIndex)

            val needsFilter = options.consoleMode || options.oledMode || options.nightMode || options.twilightMode

            if (!needsFilter) {
                // Keep previews cheap, but use print quality for the actual paper/slide bitmap.
                // DISPLAY is optimized for speed and visibly softens small text and vector lines.
                val canvas = Canvas(targetBitmap)
                canvas.drawColor(Color.WHITE)
                val renderMode = if (targetBitmap.width >= HIGH_QUALITY_MIN_WIDTH) {
                    PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                } else {
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                }
                try {
                    page.render(targetBitmap, null, null, renderMode)
                } catch (e: Exception) {
                    val fallback = if (renderMode == PdfRenderer.Page.RENDER_MODE_FOR_PRINT) {
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    } else {
                        PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                    }
                    page.render(targetBitmap, null, null, fallback)
                }
            } else {
                // Filtered rendering (Night, Sepia, OLED, Console) with buffer reuse
                val intermediate = if (filterScratchBitmap != null &&
                    filterScratchBitmap!!.width == targetBitmap.width &&
                    filterScratchBitmap!!.height == targetBitmap.height &&
                    !filterScratchBitmap!!.isRecycled
                ) {
                    filterScratchBitmap!!
                } else {
                    filterScratchBitmap?.recycle()
                    val newBmp = Bitmap.createBitmap(
                        targetBitmap.width,
                        targetBitmap.height,
                        Bitmap.Config.ARGB_8888
                    ).apply {
                        density = 480
                    }
                    filterScratchBitmap = newBmp
                    newBmp
                }

                val canvas = Canvas(intermediate)
                canvas.drawColor(Color.WHITE)

                try {
                    page.render(intermediate, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                } catch (e: Exception) {
                    Log.w(TAG, "RENDER_MODE_FOR_PRINT failed, falling back to RENDER_MODE_FOR_DISPLAY", e)
                    page.render(intermediate, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }

                // Apply color filter to targetBitmap
                val targetCanvas = Canvas(targetBitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

                when {
                    options.consoleMode -> {
                        // Green on black terminal mode
                        targetCanvas.drawColor(Color.parseColor("#001400"))
                        val consoleMatrix = ColorMatrix(
                            floatArrayOf(
                                0f, 0f, 0f, 0f, 0f,
                                -0.2f, -0.7f, -0.1f, 0f, 255f,
                                0f, 0f, 0f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                        paint.colorFilter = ColorMatrixColorFilter(consoleMatrix)
                        targetCanvas.drawBitmap(intermediate, 0f, 0f, paint)
                    }
                    options.oledMode -> {
                        // Pure pitch-black background with clean light text for OLED displays
                        targetCanvas.drawColor(Color.parseColor("#000000"))
                        val oledMatrix = ColorMatrix(
                            floatArrayOf(
                                -0.85f, 0f, 0f, 0f, 240f,
                                0f, -0.85f, 0f, 0f, 240f,
                                0f, 0f, -0.85f, 0f, 240f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                        paint.colorFilter = ColorMatrixColorFilter(oledMatrix)
                        targetCanvas.drawBitmap(intermediate, 0f, 0f, paint)
                    }
                    options.nightMode -> {
                        // Invert colors for dark mode reading
                        targetCanvas.drawColor(Color.parseColor("#121212"))
                        val invertMatrix = ColorMatrix(
                            floatArrayOf(
                                -1f, 0f, 0f, 0f, 255f,
                                0f, -1f, 0f, 0f, 255f,
                                0f, 0f, -1f, 0f, 255f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                        paint.colorFilter = ColorMatrixColorFilter(invertMatrix)
                        targetCanvas.drawBitmap(intermediate, 0f, 0f, paint)
                    }
                    options.twilightMode -> {
                        // Warm sepia tone tint
                        targetCanvas.drawColor(Color.parseColor("#FBF0D9"))
                        val sepiaMatrix = ColorMatrix(
                            floatArrayOf(
                                0.393f, 0.769f, 0.189f, 0f, 0f,
                                0.349f, 0.686f, 0.168f, 0f, 0f,
                                0.272f, 0.534f, 0.131f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                        paint.colorFilter = ColorMatrixColorFilter(sepiaMatrix)
                        targetCanvas.drawBitmap(intermediate, 0f, 0f, paint)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering PDF page $pageIndex", e)
            false
        }
    }

    // PdfRenderer allows one open page. Reuse it for size, preview and sharp render.
    private fun obtainPage(pageIndex: Int): PdfRenderer.Page {
        currentPage?.takeIf { it.index == pageIndex }?.let { return it }
        currentPage?.close()
        currentPage = null
        return checkNotNull(pdfRenderer).openPage(pageIndex).also { currentPage = it }
    }

    override fun getPageText(pageIndex: Int): String = getPageTextLayout(pageIndex).text

    override fun getPageTextLineBounds(pageIndex: Int): List<RectF> = getPageTextLayout(pageIndex).lineBounds()

    @Synchronized
    override fun getPageTextLayout(pageIndex: Int): PageTextLayout {
        pageTextLayoutCache.get(pageIndex)?.let { return it }
        val file = openedFile ?: return PageTextLayout("", emptyList())
        if (pageIndex !in 0 until getPageCount()) return PageTextLayout("", emptyList())
        val layout = try {
            // One parser per document, with a finite decoded-stream budget even for glyph-free pages.
            val document = textDocument ?: PDDocument.load(
                file,
                MemoryUsageSetting.setupTempFileOnly(MAX_NATIVE_TEXT_SCRATCH_BYTES).setTempDir(textScratchDir)
            ).also { textDocument = it }
            PdfPageTextExtractor(document.getPage(pageIndex), pageIndex).extract(document)
        } catch (e: Exception) {
            val failedDocument = textDocument
            textDocument = null
            try {
                failedDocument?.close()
            } catch (_: Exception) {
                Log.w(TAG, "Could not close failed PDF text parser")
            }
            // Parser exceptions can contain input operands; never send them to document logs.
            Log.w(TAG, "Could not read native text for page $pageIndex")
            PageTextLayout("", emptyList())
        }
        pageTextLayoutCache.put(pageIndex, layout)
        return layout
    }

    override fun getOutline(): List<OutlineItem> {
        val total = getPageCount()
        if (total <= 0) return emptyList()

        // Generate synthetic outline sections if embedded outline is not available via basic PdfRenderer
        val items = mutableListOf<OutlineItem>()
        val step = maxOf(1, total / 10)
        for (i in 0 until total step step) {
            items.add(OutlineItem(title = "Capítulo ${(i / step) + 1}", page = i, level = 0))
        }
        return items
    }

    @Synchronized
    override fun search(query: String): List<SearchResult> {
        val file = openedFile?.takeIf { it.isFile && it.canRead() } ?: return emptyList()
        return pdfDocumentEditor.searchBlocking(file, query, MAX_SEARCH_RESULTS)
            .map { SearchResult(page = it.page, snippet = it.snippet) }
    }

    @Synchronized
    override fun close() {
        try {
            textDocument?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing PDF text resources", e)
        } finally {
            textDocument = null
            pageTextLayoutCache.evictAll()
        }
        try {
            currentPage?.close()
            currentPage = null
            pdfRenderer?.close()
            pdfRenderer = null
            openedFile = null
            pfd?.close()
            pfd = null
            filterScratchBitmap?.recycle()
            filterScratchBitmap = null
            pageSizes.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing PdfRenderer resources", e)
        }
    }
}
