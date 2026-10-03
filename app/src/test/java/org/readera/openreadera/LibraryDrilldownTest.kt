package org.readera.openreadera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.ui.library.resolveCurrentDrilldownBooks

class LibraryDrilldownTest {
    @Test
    fun authorDrilldownReflectsCurrentMetadataProgressAndFavoriteAndExcludesChangedOrRemovedBooks() {
        val currentActiveBooks = listOf(
            Book(
                id = 1, title = "Updated title", author = "A. Author", filePath = "/books/one.epub",
                format = "EPUB", currentPage = 12, isFavorite = true
            ),
            Book(id = 2, title = "New match", author = "A. Author", filePath = "/books/two.pdf", format = "PDF"),
            Book(id = 3, title = "Changed author", author = "B. Author", filePath = "/books/three.epub", format = "EPUB")
            // id 4 was moved to trash and is therefore absent from this active-book emission.
        )

        val currentMatches = resolveCurrentDrilldownBooks(
            currentActiveBooks, "A. Author", null, null, null
        )

        assertEquals(listOf(1L, 2L), currentMatches.map { it.id })
        assertEquals("Updated title", currentMatches.first().title)
        assertEquals(12, currentMatches.first().currentPage)
        assertTrue(currentMatches.first().isFavorite)
    }

    @Test
    fun seriesDrilldownUsesCurrentSeriesMembership() {
        val currentActiveBooks = listOf(
            Book(id = 1, title = "Moved into series", author = "A", filePath = "/one.epub", format = "EPUB", series = "Current"),
            Book(id = 2, title = "Moved out of series", author = "A", filePath = "/two.epub", format = "EPUB", series = "Other"),
            Book(id = 3, title = "New series match", author = "A", filePath = "/three.epub", format = "EPUB", series = "Current")
        )

        assertEquals(listOf(1L, 3L), resolveCurrentDrilldownBooks(
            currentActiveBooks, null, "Current", null, null
        ).map { it.id })
    }

    @Test
    fun resolvesNormalizedFormatAndFolderFromCurrentBooks() {
        val folderPath = File("books").absolutePath
        val currentBooks = listOf(
            Book(id = 1, title = "Updated", filePath = File(folderPath, "one.pdf").path, format = "pdf"),
            Book(id = 2, title = "New", filePath = File(folderPath, "two.PDF").path, format = "PDF"),
            Book(id = 3, title = "Elsewhere", filePath = File("other", "three.pdf").absolutePath, format = "PDF")
        )

        assertEquals(listOf(1L, 2L, 3L), resolveCurrentDrilldownBooks(
            currentBooks, null, null, "PDF", null
        ).map { it.id })
        assertEquals(listOf(1L, 2L), resolveCurrentDrilldownBooks(
            currentBooks, null, null, null, folderPath
        ).map { it.id })

    }
}
