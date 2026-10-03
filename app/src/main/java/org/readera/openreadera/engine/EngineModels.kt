package org.readera.openreadera.engine

import android.graphics.RectF
import org.readera.openreadera.data.model.DrawingPageAnchor

data class PageSize(
    val width: Float,
    val height: Float
)

enum class PageTextSource { NATIVE, OCR }

/** UTF-16 offsets into [PageTextLayout.text]; bounds follow the rendered page in 0..1. */
data class PageTextWord(
    val startUtf16: Int,
    val endUtf16: Int,
    val bounds: RectF,
    val lineIndex: Int
)

data class PageTextLayout(
    val text: String,
    val words: List<PageTextWord>,
    val source: PageTextSource = PageTextSource.NATIVE
)

data class PageTextRange(
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int
) {
    fun contains(anchor: DrawingPageAnchor): Boolean =
        chapterIndex == anchor.chapterIndex && anchor.textOffset in startOffset until endOffset
}

data class OutlineItem(
    val title: String,
    val page: Int,
    val level: Int = 0,
    val children: List<OutlineItem> = emptyList()
)

data class SearchResult(
    val page: Int,
    val snippet: String,
    val hitboxes: List<RectF> = emptyList()
)

data class RenderOptions(
    val zoom: Float = 1.0f,
    val rotation: Int = 0,
    val nightMode: Boolean = false,
    val twilightMode: Boolean = false,
    val oledMode: Boolean = false,
    val consoleMode: Boolean = false,
    val fontSizeSp: Int = 16,
    val fontFamily: String = "SansSerif",
    val lineSpacing: Float = 1.2f,
    val marginHorizontalDp: Int = 16,
    val smartCrop: Boolean = false,
    val bionicReading: Boolean = false,
    val targetAspectRatio: Float = 0f,
    val fontBold: Boolean = false
)

enum class DocumentFormat(val extension: String, val displayName: String) {
    PDF("pdf", "PDF"),
    EPUB("epub", "EPUB"),
    MOBI("mobi", "MOBI"),
    AZW("azw", "Kindle AZW"),
    AZW3("azw3", "Kindle AZW3"),
    DJVU("djvu", "DjVu"),
    DJV("djv", "DjVu"),
    FB2("fb2", "FictionBook"),
    TXT("txt", "Texto"),
    DOC("doc", "Word DOC"),
    DOCX("docx", "Word DOCX"),
    RTF("rtf", "RTF"),
    CBR("cbr", "Cómic CBR"),
    CBZ("cbz", "Cómic CBZ"),
    CHM("chm", "CHM"),
    UNKNOWN("", "Documento");

    companion object {
        fun fromExtension(ext: String): DocumentFormat {
            val clean = ext.trim().lowercase().removePrefix(".")
            return entries.find { it.extension == clean } ?: UNKNOWN
        }

        fun fromPath(path: String): DocumentFormat {
            val ext = path.substringAfterLast('.', "")
            return fromExtension(ext)
        }
    }
}
