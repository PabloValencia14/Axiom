package org.readera.openreadera

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readera.openreadera.data.db.AppDatabase
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.sync.GoogleDriveSyncManager

@RunWith(AndroidJUnit4::class)
class SyncMergeTest {

    @Test
    fun remoteStateMergesProgressAnnotationsAndCollectionsWithoutDuplicates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val localId = db.bookDao().insert(
                Book(
                    title = "Libro local",
                    author = "Autor",
                    filePath = "/books/libro.pdf",
                    format = "PDF",
                    sha1 = "abc123",
                    currentPage = 1,
                    lastOpened = 100L
                )
            )

            val remote = JSONObject().apply {
                put("version", 2)
                put("books", JSONArray().put(JSONObject().apply {
                    put("id", 42L)
                    put("sha1", "abc123")
                    put("filePath", "/other-device/libro.pdf")
                    put("title", "Libro remoto")
                    put("author", "Autor")
                    put("format", "PDF")
                    put("currentPage", 7)
                    put("totalPages", 20)
                    put("progressPercent", 35.0)
                    put("lastOpened", 200L)
                    put("status", "READING")
                    put("rating", 4.0)
                }))
                put("bookmarks", JSONArray().put(JSONObject().apply {
                    put("bookId", 42L)
                    put("page", 7)
                    put("title", "Importante")
                    put("snippet", "Texto remoto")
                }))
                put("quotes", JSONArray().put(JSONObject().apply {
                    put("bookId", 42L)
                    put("page", 7)
                    put("text", "Una cita")
                    put("note", "Repasar")
                }))
                put("collections", JSONArray().put(JSONObject().apply {
                    put("id", 8L)
                    put("name", "Examen")
                }))
                put("bookCollections", JSONArray().put(JSONObject().apply {
                    put("bookId", 42L)
                    put("collectionId", 8L)
                }))
            }

            val manager = GoogleDriveSyncManager(context, db)
            val first = manager.mergeRemoteState(remote.toString())
            val second = manager.mergeRemoteState(remote.toString())

            val book = db.bookDao().getBookByIdSync(localId)!!
            assertEquals("Libro remoto", book.title)
            assertEquals(7, book.currentPage)
            assertEquals(200L, book.lastOpened)
            assertEquals(1, db.bookmarkDao().getAllBookmarksList().size)
            assertEquals(1, db.quoteDao().getAllQuotesList().size)
            assertEquals(0, second.bookmarksAdded)
            assertEquals(0, second.quotesAdded)
            assertTrue(first.collectionsAdded == 1)
            assertEquals(1, db.collectionDao().getCollectionsForBookSync(localId).size)
        } finally {
            db.close()
        }
    }
}
