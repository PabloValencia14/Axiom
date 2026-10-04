package org.readera.openreadera.ui.reader.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DocumentTranslationTest {
    @Test
    fun completeDocumentDoesNotStopAtTwoHundredPagesAndSkipsOnlyBlankText() = runTest {
        val visited = mutableListOf<Int>()
        val progress = mutableListOf<Int>()
        val chapters = translateDocumentPages(
            totalPages = 205,
            getPageText = { page -> visited.add(page); if (page == 17) " " else "source $page" },
            translate = { Result.success("translated $it") },
            onProgress = progress::add
        )
        assertEquals((0 until 205).toList(), visited)
        assertEquals((1..205).toList(), progress)
        assertEquals(204, chapters.size)
        assertEquals("Página 205" to "translated source 204", chapters.last())
        assertFalse(chapters.any { it.first == "Página 18" })
    }

    @Test
    fun failureStopsWithoutSubstitutingSourceOrProcessingLaterPages() = runTest {
        val visited = mutableListOf<Int>()
        val failure = runCatching {
            translateDocumentPages(5,
                getPageText = { page -> visited.add(page); "page $page" },
                translate = { if (it == "page 2") Result.failure(IllegalStateException("offline")) else Result.success("translated") },
                onProgress = {}
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("página 3"))
        assertEquals(listOf(0, 1, 2), visited)
    }

    @Test
    fun cancellationAndEmptyServiceOutputNeverProduceAnIncompleteDocument() = runTest {
        val cancelled = CancellationException("dismissed")
        val cancellation = runCatching {
            translateDocumentPages(2, { "source" }, { Result.failure(cancelled) }, {})
        }.exceptionOrNull()
        assertSame(cancelled, cancellation)
        val empty = runCatching {
            translateDocumentPages(2, { "source" }, { Result.success("") }, {})
        }.exceptionOrNull()
        assertTrue(empty is IllegalArgumentException)
        val blankDocument = runCatching {
            translateDocumentPages(2, { "" }, { error("Blank pages must not be sent") }, {})
        }.exceptionOrNull()
        assertTrue(blankDocument is IllegalArgumentException)
    }
}
