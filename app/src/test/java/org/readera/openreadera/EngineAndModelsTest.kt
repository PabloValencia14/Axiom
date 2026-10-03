package org.readera.openreadera

import org.junit.Assert.*
import org.junit.Test
import org.readera.openreadera.data.model.*
import org.readera.openreadera.data.scanner.DocumentCategory
import org.readera.openreadera.data.scanner.DocumentCategoryClassifier
import org.readera.openreadera.engine.*

class EngineAndModelsTest {

    @Test
    fun testDocumentFormatResolution() {
        assertEquals(DocumentFormat.PDF, DocumentFormat.fromExtension("pdf"))
        assertEquals(DocumentFormat.PDF, DocumentFormat.fromExtension(".PDF"))
        assertEquals(DocumentFormat.PDF, DocumentFormat.fromPath("/storage/emulated/0/Books/sample.pdf"))

        assertEquals(DocumentFormat.EPUB, DocumentFormat.fromPath("book.epub"))
        assertEquals(DocumentFormat.MOBI, DocumentFormat.fromPath("document.mobi"))
        assertEquals(DocumentFormat.DJVU, DocumentFormat.fromPath("paper.djvu"))
        assertEquals(DocumentFormat.FB2, DocumentFormat.fromPath("story.fb2"))
        assertEquals(DocumentFormat.CBR, DocumentFormat.fromPath("comic.cbr"))
        assertEquals(DocumentFormat.CBZ, DocumentFormat.fromPath("comic.cbz"))
    }

    @Test
    fun testBookEntityAndProgress() {
        val book = Book(
            id = 1L,
            title = "Don Quijote de la Mancha",
            author = "Miguel de Cervantes",
            filePath = "/storage/emulated/0/Books/quijote.pdf",
            format = "PDF",
            fileSize = 1048576L,
            currentPage = 50,
            totalPages = 500,
            progressPercent = 10.0f,
            status = BookStatus.READING
        )

        assertEquals("Don Quijote de la Mancha", book.title)
        assertEquals(50, book.currentPage)
        assertEquals(500, book.totalPages)
        assertEquals(10.0f, book.progressPercent, 0.01f)
        assertEquals(BookStatus.READING, book.status)
    }

    @Test
    fun testReaderSettingsDefaults() {
        val settings = ReaderSettings()
        assertEquals(ReaderColorTheme.SYSTEM, settings.theme)
        assertEquals(ReaderViewMode.PAGED, settings.viewMode)
        assertEquals(16, settings.fontSizeSp)
        assertEquals(1.2f, settings.lineSpacing, 0.01f)
    }

    @Test
    fun testOutlineHierarchy() {
        val chapter1 = OutlineItem(title = "Capítulo 1", page = 0, level = 0)
        val section1_1 = OutlineItem(title = "Sección 1.1", page = 5, level = 1)
        val root = OutlineItem(
            title = "Parte 1",
            page = 0,
            level = 0,
            children = listOf(chapter1, section1_1)
        )

        assertEquals("Parte 1", root.title)
        assertEquals(2, root.children.size)
        assertEquals("Sección 1.1", root.children[1].title)
        assertEquals(1, root.children[1].level)
    }

    @Test
    fun testBookmarkAndQuoteCreation() {
        val bookmark = Bookmark(
            bookId = 1L,
            page = 42,
            title = "Página 43",
            snippet = "En un lugar de la Mancha..."
        )
        assertEquals(1L, bookmark.bookId)
        assertEquals(42, bookmark.page)

        val quote = Quote(
            bookId = 1L,
            page = 42,
            text = "En un lugar de la Mancha de cuyo nombre no quiero acordarme",
            note = "Cita célebre de inicio",
            colorHex = "#FFE082"
        )
        assertEquals("#FFE082", quote.colorHex)
        assertTrue(quote.text.contains("Mancha"))
    }

    @Test
    fun testAutomaticDocumentCategoriesUseFormatAndMetadata() {
        val comic = Book(title = "Batman", filePath = "/books/batman.cbz", format = "Cómic CBZ")
        val document = Book(title = "Tema 1 Redes", filePath = "/docs/tema1.pdf", format = "PDF")
        val presentation = Book(title = "Diapositivas de redes", filePath = "/docs/slides.pdf", format = "PDF")
        val paper = Book(title = "Research paper on arXiv", filePath = "/docs/arxiv.pdf", format = "PDF")
        val genericPdf = Book(title = "Untitled", filePath = "/docs/file.pdf", format = "PDF")
        val novel = Book(title = "Novela histórica", filePath = "/books/novela.epub", format = "EPUB")

        assertEquals(DocumentCategory.GRAPHIC_NOVEL, DocumentCategoryClassifier.quick(comic).category)
        assertEquals(DocumentCategory.DOCUMENT, DocumentCategoryClassifier.quick(document).category)
        assertEquals(DocumentCategory.PRESENTATION, DocumentCategoryClassifier.quick(presentation).category)
        assertEquals(DocumentCategory.PAPER, DocumentCategoryClassifier.quick(paper).category)
        assertEquals(DocumentCategory.DOCUMENT, DocumentCategoryClassifier.quick(genericPdf).category)
        assertEquals(DocumentCategory.BOOK, DocumentCategoryClassifier.quick(novel).category)
        assertEquals("Documentos de estudio", DocumentCategory.DOCUMENT.collectionName)
        assertEquals("Documento", DocumentCategory.DOCUMENT.displayName)
        assertEquals("Presentaciones", DocumentCategory.PRESENTATION.collectionName)
        assertEquals("Presentación", DocumentCategory.PRESENTATION.displayName)
        assertEquals("Papers", DocumentCategory.PAPER.collectionName)
        assertEquals("Paper", DocumentCategory.PAPER.displayName)
    }
}
