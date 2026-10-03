package org.readera.openreadera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.ui.library.matchesLibraryQuery

class LibrarySearchTest {
    private val book = Book(
        title = "The Left Hand of Darkness",
        author = "Ursula K. Le Guin",
        filePath = "/library/science-fiction.epub",
        format = "EPUB",
        series = "Hainish Cycle",
        language = "English",
        genre = "Science Fiction",
        description = "A novel about Gethen"
    )

    @Test
    fun matchesSearchableMetadataAndIgnoresMissingOptionalFields() {
        listOf("Hainish", "English", "epub", "Science Fiction", "Gethen", "science-fiction").forEach {
            assertTrue("Expected query '$it' to match", book.matchesLibraryQuery(it))
        }
        assertTrue(book.copy(series = null, language = null, genre = null, description = null)
            .matchesLibraryQuery("The Left Hand"))
        assertFalse(book.copy(series = null, language = null, genre = null, description = null)
            .matchesLibraryQuery("Gethen"))
        assertTrue(book.matchesLibraryQuery("  "))
    }
}
