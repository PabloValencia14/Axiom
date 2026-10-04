package org.readera.openreadera

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.readera.openreadera.data.model.BookCollectionCrossRef
import org.readera.openreadera.ui.library.FilterCriteria
import org.readera.openreadera.ui.library.SortCriterion
import org.readera.openreadera.ui.library.debouncedLibraryQuery
import org.readera.openreadera.ui.library.filterAndSortLibraryBooks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.ui.library.matchesLibraryQuery

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun filtersIntersectCurrentMembershipAndProgressWithoutMutatingInventory() {
        val books = listOf(
            book.copy(id = 1, author = "Desconocido", series = null, currentPage = 0),
            book.copy(id = 2, author = "Autor desconocido", series = null, currentPage = 0),
            book.copy(id = 3, author = "", series = null, currentPage = 4),
            book.copy(id = 4, author = "", series = null, isHaveRead = true),
            book.copy(id = 5, author = "", series = "A series")
        )
        val results = filterAndSortLibraryBooks(
            books,
            FilterCriteria(setOf("EPUB"), withoutAuthor = true, withoutSeries = true,
                withoutCollection = true, unreadOnly = true),
            "  Gethen  ", SortCriterion.NAME,
            listOf(BookCollectionCrossRef(bookId = 1, collectionId = 10))
        )
        assertEquals(listOf(2L), results.map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), books.map { it.id })
    }

    @Test
    fun allSortChoicesUseTheirOwnKeyAndKeepCaseInsensitiveTiesStable() {
        val books = listOf(
            book.copy(id = 1, title = "beta", filePath = "/books/Z.epub", format = "EPUB",
                fileSize = 1, dateAdded = 3, lastOpened = 1),
            book.copy(id = 2, title = "Alpha", filePath = "/books/a.pdf", format = "PDF",
                fileSize = 3, dateAdded = 1, lastOpened = 3),
            book.copy(id = 3, title = "alpha", filePath = "/books/B.cbz", format = "CBZ",
                fileSize = 2, dateAdded = 2, lastOpened = 2)
        )
        val expected = mapOf(
            SortCriterion.NAME to listOf(2L, 3L, 1L),
            SortCriterion.FILE_NAME to listOf(2L, 3L, 1L),
            SortCriterion.FILE_FORMAT to listOf(3L, 1L, 2L),
            SortCriterion.FILE_SIZE to listOf(2L, 3L, 1L),
            SortCriterion.DATE_MODIFIED to listOf(1L, 3L, 2L),
            SortCriterion.DATE_READ to listOf(2L, 3L, 1L)
        )
        for ((sort, ids) in expected) {
            assertEquals(sort.name, ids, filterAndSortLibraryBooks(
                books, FilterCriteria(), "", sort, emptyList()
            ).map { it.id })
        }
    }

    @Test
    fun searchDebouncesOnlyTypingAndUsesLatestFilterAndSortAndClearsImmediately() = runTest {
        val query = MutableStateFlow("")
        val sort = MutableStateFlow(SortCriterion.NAME)
        val filters = MutableStateFlow(FilterCriteria())
        val books = listOf(
            book.copy(id = 1, title = "Left A", format = "PDF", fileSize = 1),
            book.copy(id = 2, title = "Left B", format = "EPUB", fileSize = 3),
            book.copy(id = 3, title = "Left C", format = "EPUB", fileSize = 2)
        )
        val emissions = mutableListOf<Pair<String, List<Long>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            combine(query.debouncedLibraryQuery(), sort, filters) { text, criterion, criteria ->
                text to filterAndSortLibraryBooks(books, criteria, text, criterion, emptyList()).map { it.id }
            }.collect { emissions.add(it) }
        }
        runCurrent()
        query.value = "does not match"
        runCurrent()
        advanceTimeBy(150)
        query.value = "Left"
        sort.value = SortCriterion.FILE_SIZE
        filters.value = FilterCriteria(activeFormats = setOf("EPUB"))
        runCurrent()
        assertEquals("" to listOf(2L, 3L), emissions.last())
        advanceTimeBy(249)
        runCurrent()
        assertFalse(emissions.any { it.first == "Left" })
        advanceTimeBy(1)
        runCurrent()
        assertEquals("Left" to listOf(2L, 3L), emissions.last())
        assertFalse(emissions.any { it.first == "does not match" })

        query.value = "pending"
        runCurrent()
        query.value = ""
        runCurrent()
        assertEquals("" to listOf(2L, 3L), emissions.last())
        advanceTimeBy(250)
        runCurrent()
        assertFalse(emissions.any { it.first == "pending" })
    }
}
