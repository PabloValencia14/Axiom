package org.readera.openreadera.engine

import android.content.Context
import android.util.Log

class EngineManager(private val context: Context) {

    companion object {
        private const val TAG = "EngineManager"
        private val FICTIONBOOK_ROOT = Regex("""<\s*(?:[A-Za-z_][\w.-]*:)?fictionbook\b""", RegexOption.IGNORE_CASE)
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

            if (read >= 68 && String(header, 60, 8, Charsets.US_ASCII) == "BOOKMOBI") {
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

            val xmlHeader = ByteArray(4096)
            val xmlLength = java.io.FileInputStream(file).use { it.read(xmlHeader) }
            if (xmlLength > 0) {
                val xmlCharset = when {
                    xmlLength >= 2 && xmlHeader[0] == 0xFF.toByte() && xmlHeader[1] == 0xFE.toByte() -> Charsets.UTF_16LE
                    xmlLength >= 2 && xmlHeader[0] == 0xFE.toByte() && xmlHeader[1] == 0xFF.toByte() -> Charsets.UTF_16BE
                    xmlLength >= 2 && xmlHeader[0] == '<'.code.toByte() && xmlHeader[1] == 0.toByte() -> Charsets.UTF_16LE
                    xmlLength >= 2 && xmlHeader[0] == 0.toByte() && xmlHeader[1] == '<'.code.toByte() -> Charsets.UTF_16BE
                    else -> Charsets.UTF_8
                }
                val prefix = String(xmlHeader, 0, xmlLength, xmlCharset)
                if (FICTIONBOOK_ROOT.containsMatchIn(prefix)) {
                    return DocumentFormat.FB2
                }
            }
            val signature = String(header, 0, read.coerceAtLeast(0), Charsets.ISO_8859_1)
            if (signature.startsWith("AT&TFORM")) return DocumentFormat.DJVU
            if (signature.startsWith("{\\rtf")) return DocumentFormat.RTF
            if (signature.startsWith("ITSF")) return DocumentFormat.CHM
            if (signature.startsWith("Rar!")) return DocumentFormat.CBR
            if (read >= 4 && header[0] == 0xD0.toByte() && header[1] == 0xCF.toByte() &&
                header[2] == 0x11.toByte() && header[3] == 0xE0.toByte()
            ) return DocumentFormat.DOC
            if (signature.startsWith("\u0089PNG") || signature.startsWith("GIF8") ||
                (read >= 2 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) ||
                (read >= 12 && signature.startsWith("RIFF") && signature.substring(8).startsWith("WEBP"))
            ) return DocumentFormat.UNKNOWN
            val extension = file.extension.lowercase()
            if (extension == "txt" || extension == "md" || extension == "log") return DocumentFormat.TXT
            // Container formats require their native signature, not just a renamed suffix.
            when (extensionFormat) {
                DocumentFormat.PDF, DocumentFormat.EPUB, DocumentFormat.DOCX, DocumentFormat.FB2,
                DocumentFormat.MOBI, DocumentFormat.AZW, DocumentFormat.AZW3 -> return DocumentFormat.UNKNOWN
                else -> Unit
            }
        } catch (_: Exception) {
            return DocumentFormat.UNKNOWN
        }
        return extensionFormat.takeIf { it != DocumentFormat.UNKNOWN }
    }
    fun resolveDocumentFormat(path: String): DocumentFormat =
        detectFormatFromFile(path) ?: when (java.io.File(path).extension.lowercase()) {
            "md", "log" -> DocumentFormat.TXT
            else -> DocumentFormat.fromPath(path)
        }


    fun getEngineForDocument(path: String): DocumentEngine {
        val format = resolveDocumentFormat(path)
        Log.i(TAG, "Resolving engine for format: $format at $path")

        return when (format) {
            DocumentFormat.EPUB -> EpubEngine()
            DocumentFormat.PDF -> AndroidPdfEngine(context)
            DocumentFormat.TXT -> TxtEngine()
            DocumentFormat.FB2 -> Fb2Engine(context)
            DocumentFormat.DOCX -> DocxEngine(context)
            DocumentFormat.MOBI, DocumentFormat.AZW, DocumentFormat.AZW3 -> MobiEpubEngine(context)
            DocumentFormat.CBZ -> CbzEngine()
            DocumentFormat.UNKNOWN -> UnsupportedDocumentEngine(format)
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
