package org.readera.openreadera.ui.library.components

/**
 * Fuente única de verdad para las capacidades de formato en la interfaz de usuario.
 * Distingue con precisión los formatos que cuentan con motor de lectura integrado
 * de aquellos que son indexados/catalogados por la aplicación sin motor disponible.
 */
object DocumentFormatCapabilities {

    /**
     * Formatos con motor de lectura activo en EngineManager:
     * - PDF: AndroidPdfEngine
     * - EPUB: EpubEngine
     * - MOBI / AZW / AZW3: MobiEpubEngine (conversión sin DRM)
     * - FB2: Fb2Engine
     * - TXT / MD / LOG: TxtEngine
     * - DOCX: DocxEngine
     * - CBZ: CbzEngine
     */
    private val READABLE_EXTENSIONS = setOf(
        "pdf", "epub", "mobi", "azw", "azw3", "fb2", "txt", "docx", "cbz", "md", "log"
    )

    /**
     * Formatos reconocidos/indexados por StorageScanner o agrupados en biblioteca
     * pero que no tienen motor de lectura en esta versión (UnsupportedDocumentEngine):
     * - DJVU / DJV
     * - DOC (Word binario heredado)
     * - RTF
     * - CBR (cómics en RAR)
     * - CHM
     * - ODT
     * - WORD (si no es docx)
     * - COMIC (si no es cbz)
     */
    private val INDEXED_ONLY_EXTENSIONS = setOf(
        "djvu", "djv", "doc", "rtf", "cbr", "chm", "odt", "word", "comic"
    )

    fun isReadable(format: String?): Boolean {
        if (format.isNullOrBlank()) return false
        val clean = format.trim().lowercase().removePrefix(".")
        return clean in READABLE_EXTENSIONS
    }

    fun isIndexedOnly(format: String?): Boolean {
        if (format.isNullOrBlank()) return false
        val clean = format.trim().lowercase().removePrefix(".")
        return clean in INDEXED_ONLY_EXTENSIONS || (!isReadable(clean) && clean != "unknown")
    }

    fun badgeText(format: String): String {
        val upper = format.trim().uppercase()
        return if (isReadable(format)) {
            upper
        } else {
            "$upper • Solo indexado"
        }
    }
}
