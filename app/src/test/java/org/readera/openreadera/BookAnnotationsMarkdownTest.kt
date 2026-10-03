package org.readera.openreadera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.Bookmark
import org.readera.openreadera.data.model.Quote
import org.readera.openreadera.ui.library.formatBookAnnotationsMarkdown

class BookAnnotationsMarkdownTest {
    private val book = Book(title = "A [Book]", author = "A. Author", filePath = "", format = "EPUB")

    @Test
    fun includesBookMetadataAllAnnotationsAndOneBasedPages() {
        val markdown = formatBookAnnotationsMarkdown(
            book,
            listOf(Quote(bookId = book.id, page = 0, text = "First quote", note = "A note"),
                Quote(bookId = book.id, page = 4, text = "Second quote")),
            listOf(Bookmark(bookId = book.id, page = 2, title = "Chapter 1", snippet = "A passage"))
        )

        assertTrue(markdown.contains("# A \\[Book\\]"))
        assertTrue(markdown.contains("**Autor:** A. Author"))
        assertTrue(markdown.contains("### Página 1\n\n> First quote\n\n**Nota:**\n\n> A note"))
        assertTrue(markdown.contains("### Página 5\n\n> Second quote"))
        assertTrue(markdown.contains("### 1. Chapter 1\n\n**Página:** 3\n\n> A passage"))
    }

    @Test
    fun emptySectionsAndMultilineContentRemainReadableMarkdown() {
        val empty = formatBookAnnotationsMarkdown(book, emptyList(), emptyList())
        assertTrue(empty.contains("_No hay citas ni notas._"))
        assertTrue(empty.contains("_No hay marcadores._"))

        val markdown = formatBookAnnotationsMarkdown(
            book,
            listOf(Quote(bookId = book.id, page = 1, text = "line one\n# line two", note = "")),
            listOf(Bookmark(bookId = book.id, page = 0, title = "A *title*", snippet = "first\r\nsecond"))
        )
        assertTrue(markdown.contains("> line one\n> # line two"))
        assertTrue(markdown.contains("### 1. A \\*title\\*"))
        assertEquals(1, "first\n> second".toRegex().findAll(markdown).count())
    }
}
