package org.readera.openreadera.engine

import android.content.Context
import android.util.Log

class EngineManager(private val context: Context) {

    companion object {
        private const val TAG = "EngineManager"
    }


    private fun detectFormatFromFile(path: String): DocumentFormat? {
        val file = java.io.File(path)
        if (!file.exists() || !file.canRead() || file.length() < 2) return null
        val extensionFormat = DocumentFormat.fromPath(path)
        try {
            val header = ByteArray(68)
            val read = java.io.FileInputStream(file).use { it.read(header) }
            if (read >= 4 &&
                header[0] == 0x25.toByte() && header[1] == 0x50.toByte() &&
                header[2] == 0x44.toByte() && header[3] == 0x46.toByte()
            ) {
                return DocumentFormat.PDF
            }

            if (read >= 68 && header.sliceArray(60..67).toString(Charsets.US_ASCII) == "BOOKMOBI") {
                return when (extensionFormat) {
                    DocumentFormat.AZW -> DocumentFormat.AZW
                    DocumentFormat.AZW3 -> DocumentFormat.AZW3
                    else -> DocumentFormat.MOBI
                }
            }

            if (read >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) {
                return try {
                    java.util.zip.ZipFile(file).use { zip ->
                        val entries = zip.entries()
                        var hasEpubContainer = false
                        var hasDocxDocument = false
                        var hasImages = false
                        while (entries.hasMoreElements()) {
                            val name = entries.nextElement().name
                            if (name.equals("META-INF/container.xml", ignoreCase = true)) {
                                hasEpubContainer = true
                            }
                            if (name.equals("word/document.xml", ignoreCase = true)) {
                                hasDocxDocument = true
                            }
                            if (name.endsWith(".jpg", ignoreCase = true) ||
                                name.endsWith(".jpeg", ignoreCase = true) ||
                                name.endsWith(".png", ignoreCase = true) ||
                                name.endsWith(".webp", ignoreCase = true) ||
                                name.endsWith(".gif", ignoreCase = true)
                            ) {
                                hasImages = true
                            }
                        }
                        when {
                            hasEpubContainer -> DocumentFormat.EPUB
                            hasDocxDocument -> DocumentFormat.DOCX
                            hasImages -> DocumentFormat.CBZ
                            else -> DocumentFormat.UNKNOWN
                        }
                    }
                } catch (_: Exception) {
                    DocumentFormat.UNKNOWN
                }
            }

            if (extensionFormat != DocumentFormat.UNKNOWN) return extensionFormat

            val xmlHeader = ByteArray(4096)
            val xmlLength = java.io.FileInputStream(file).use { it.read(xmlHeader) }
            if (xmlLength > 0) {
                val prefix = xmlHeader.copyOf(xmlLength).toString(Charsets.UTF_8)
                if (Regex("""<\s*fictionbook\b""", RegexOption.IGNORE_CASE).containsMatchIn(prefix)) {
                    return DocumentFormat.FB2
                }
            }
        } catch (_: Exception) {
            return null
        }
        return extensionFormat.takeIf { it != DocumentFormat.UNKNOWN }
    }

    fun getEngineForDocument(path: String): DocumentEngine {
        val detected = detectFormatFromFile(path)
        val format = detected ?: DocumentFormat.fromPath(path)
        val extension = path.substringAfterLast('.', "").lowercase()
        Log.i(TAG, "Resolving engine for format: $format at $path (detectedByBytes=${detected != null})")

        return when (format) {
            DocumentFormat.EPUB -> EpubEngine()
            DocumentFormat.PDF -> AndroidPdfEngine(context)
            DocumentFormat.TXT -> TxtEngine()
            DocumentFormat.FB2 -> Fb2Engine(context)
            DocumentFormat.DOCX -> DocxEngine(context)
            DocumentFormat.MOBI, DocumentFormat.AZW, DocumentFormat.AZW3 -> MobiEpubEngine(context)
            DocumentFormat.CBZ -> CbzEngine()
            DocumentFormat.UNKNOWN -> when (extension) {
                "md", "log" -> TxtEngine()
                else -> UnsupportedDocumentEngine(format)
            }
            else -> UnsupportedDocumentEngine(format)
        }
    }
}



private class UnsupportedDocumentEngine(private val format: DocumentFormat) : DocumentEngine {
    override val isAvailable: Boolean = false
    override val engineName: String = "Formato no compatible: ${format.displayName}"

    override fun open(path: String, password: String?): Boolean = false
    override fun getPageCount(): Int = 0
    override fun getPageSize(pageIndex: Int): PageSize = PageSize(0f, 0f)
    override fun renderPage(pageIndex: Int, targetBitmap: android.graphics.Bitmap, options: RenderOptions): Boolean = false
    override fun getPageText(pageIndex: Int): String = ""
    override fun getOutline(): List<OutlineItem> = emptyList()
    override fun search(query: String): List<SearchResult> = emptyList()
    override fun close() = Unit
}
