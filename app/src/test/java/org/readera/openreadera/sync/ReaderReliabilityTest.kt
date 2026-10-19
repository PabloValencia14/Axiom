package org.readera.openreadera.sync

import android.content.Context
import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.readera.openreadera.data.db.AppDatabase
import org.readera.openreadera.data.model.*

@RunWith(RobolectricTestRunner::class)
class ReaderReliabilityTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun togglesLeaveListsAndCompletedBooksSurviveReopening() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.bookDao()
            val id = dao.insert(Book(title = "Test", filePath = "/test.pdf", format = "PDF"))
            dao.toggleToRead(id); dao.toggleToRead(id)
            assertTrue(dao.getToRead().first().isEmpty())
            dao.toggleHaveRead(id); dao.toggleHaveRead(id)
            assertTrue(dao.getHaveRead().first().isEmpty())
            dao.toggleToRead(id); dao.toggleHaveRead(id)
            assertTrue(dao.getToRead().first().isEmpty())
            dao.updateProgress(id, 2, 20f, 10)
            assertEquals(BookStatus.HAVE_READ, dao.getBookByIdSync(id)!!.status)
            assertEquals(1, dao.getHaveRead().first().size)
        } finally { db.close() }
    }

    @Test fun staleSnapshotsCannotResurrectDeletedAnnotationsOrDuplicateAnEdit() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val book = Book(title = "Test", filePath = "/test.pdf", format = "PDF")
            val id = db.bookDao().insert(book)
            val quote = Quote(bookId = id, page = 2, text = "Text", note = "Old", createdAt = 10L)
            val quoteId = db.quoteDao().insert(quote)
            val bookmark = Bookmark(bookId = id, page = 2, title = "Bookmark", createdAt = 10L)
            val bookmarkId = db.bookmarkDao().insert(bookmark)
            fun remote(note: String, updated: Long, deleted: Long = 0L) = JSONObject().apply {
                put("version", 4)
                put("books", JSONArray().put(JSONObject().put("id", 42).put("syncId", book.syncId)
                    .put("title", book.title).put("format", "PDF")))
                put("quotes", JSONArray().put(JSONObject().put("bookSyncId", book.syncId)
                    .put("syncId", quote.syncId).put("page", 2).put("text", "Text").put("note", note)
                    .put("createdAt", 10L).put("updatedAt", updated).put("deletedAt", deleted)))
                put("bookmarks", JSONArray().put(JSONObject().put("bookSyncId", book.syncId)
                    .put("syncId", bookmark.syncId).put("page", 2).put("title", "Bookmark").put("createdAt", 10L)))
            }
            val manager = GoogleDriveSyncManager(context, db)
            db.quoteDao().update(quote.copy(id = quoteId, note = "Edited", colorHex = "#ffffff"))
            manager.mergeRemoteState(remote("Old", 0L).toString())
            assertEquals("Edited", db.quoteDao().getAllQuotesList().single().note)
            assertEquals("#ffffff", db.quoteDao().getAllQuotesList().single().colorHex)
            db.quoteDao().delete(quote.copy(id = quoteId))
            db.bookmarkDao().delete(bookmark.copy(id = bookmarkId))
            repeat(2) { manager.mergeRemoteState(remote("Old", 0L).toString()) }
            manager.mergeRemoteState(remote("Offline edit after deletion", System.currentTimeMillis() + 100_000).toString())
            assertTrue(db.quoteDao().getAllQuotes().first().isEmpty())
            assertTrue(db.bookmarkDao().getAllBookmarks().first().isEmpty())
            assertEquals(1, db.quoteDao().getAllQuotesList().size)
            assertEquals("", db.quoteDao().getAllQuotesList().single().text)
            assertEquals(1, db.bookmarkDao().getAllBookmarksList().size)
        } finally { db.close() }
    }

    @Test fun readingAndMetadataVersionsMergeIndependently() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val book = Book(title = "Local edit", filePath = "/test.pdf", format = "PDF",
                metadataUpdatedAt = 300L, lastOpened = 100L, isFavorite = true, series = "Series", review = "Review", rating = 4f)
            val id = db.bookDao().insert(book)
            val remote = JSONObject().put("version", 4).put("books", JSONArray().put(JSONObject()
                .put("id", 42L).put("syncId", book.syncId).put("title", "Old title")
                .put("metadataUpdatedAt", 200L).put("lastOpened", 400L)
                .put("currentPage", 9).put("totalPages", 20).put("isFavorite", false)))
            val manager = GoogleDriveSyncManager(context, db)
            manager.mergeRemoteState(remote.toString())
            val merged = db.bookDao().getBookByIdSync(id)!!
            assertEquals("Local edit", merged.title)
            assertTrue(merged.isFavorite)
            assertEquals(9, merged.currentPage)
            assertEquals(BookStatus.READING, merged.status)
            remote.getJSONArray("books").getJSONObject(0).put("metadataUpdatedAt", 500L)
                .put("lastOpened", 50L).put("title", "New title")
                .put("series", JSONObject.NULL).put("review", JSONObject.NULL).put("rating", JSONObject.NULL)
            manager.mergeRemoteState(remote.toString())
            assertEquals("New title", db.bookDao().getBookByIdSync(id)!!.title)
            assertNull(db.bookDao().getBookByIdSync(id)!!.series)
            assertNull(db.bookDao().getBookByIdSync(id)!!.review)
            assertNull(db.bookDao().getBookByIdSync(id)!!.rating)
            assertEquals(9, db.bookDao().getBookByIdSync(id)!!.currentPage)
        } finally { db.close() }
    }
}
