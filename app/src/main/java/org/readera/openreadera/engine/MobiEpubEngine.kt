package org.readera.openreadera.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import java.io.File

class MobiEpubEngine(context: Context) : DocumentEngine {
    private val cacheDir = context.applicationContext.cacheDir
    private var convertedFile: File? = null
    private var epubEngine: EpubEngine? = null

    override val isAvailable: Boolean
        get() = MobiConverter.isAvailable

    override val engineName: String = "Conversor MOBI sin DRM"

    override fun open(path: String, password: String?): Boolean {
        close()
        if (!isAvailable) return false
        val source = File(path)
        if (!source.isFile || !source.canRead() || source.length() == 0L) return false

        var temporary: File? = null
        try {
            temporary = File.createTempFile("mobi-converted-", ".epub", cacheDir)
            if (!MobiConverter.nativeConvertMobiToEpub(source.absolutePath, temporary.absolutePath)) return false

            val engine = EpubEngine()
            if (!engine.open(temporary.absolutePath)) {
                engine.close()
                return false
            }
            convertedFile = temporary
            epubEngine = engine
            return true
        } catch (_: Exception) {
            return false
        } catch (_: LinkageError) {
            return false
        } finally {
            if (epubEngine == null) temporary?.delete()
        }
    }

    override fun getPageCount(): Int = epubEngine?.getPageCount() ?: 0

    override fun getPageSize(pageIndex: Int): PageSize =
        epubEngine?.getPageSize(pageIndex) ?: PageSize(0f, 0f)

    override fun renderPage(pageIndex: Int, targetBitmap: Bitmap, options: RenderOptions): Boolean =
        epubEngine?.renderPage(pageIndex, targetBitmap, options) ?: false

    override fun getPageText(pageIndex: Int): String = epubEngine?.getPageText(pageIndex).orEmpty()

    override fun getPageTextLayout(pageIndex: Int): PageTextLayout =
        epubEngine?.getPageTextLayout(pageIndex) ?: PageTextLayout("", emptyList())

    override fun getPageTextRange(pageIndex: Int): PageTextRange? = epubEngine?.getPageTextRange(pageIndex)

    override fun getPageTextLineBounds(pageIndex: Int): List<RectF> =
        epubEngine?.getPageTextLineBounds(pageIndex).orEmpty()

    override fun getOutline(): List<OutlineItem> = epubEngine?.getOutline().orEmpty()

    override fun search(query: String): List<SearchResult> = epubEngine?.search(query).orEmpty()

    override fun applyOptions(options: RenderOptions): Int = epubEngine?.applyOptions(options) ?: 0

    override fun close() {
        val engine = epubEngine
        epubEngine = null
        try {
            engine?.close()
        } finally {
            convertedFile?.delete()
            convertedFile = null
        }
    }
}
