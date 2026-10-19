package org.readera.openreadera.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.readera.openreadera.data.model.Bookmark

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND deletedAt = 0 ORDER BY page ASC, createdAt DESC")
    fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE deletedAt = 0 ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    suspend fun getAllBookmarksList(): List<Bookmark>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: Bookmark): Long

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun byId(id: Long): Bookmark?

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun annotationBook(id: Long): org.readera.openreadera.data.model.Book?

    @Update
    suspend fun mergeRecord(bookmark: Bookmark)

    @Transaction
    suspend fun update(bookmark: Bookmark) {
        val old = byId(bookmark.id) ?: return
        if (old.deletedAt > 0L) return
        val book = annotationBook(old.bookId) ?: return
        val stableId = org.readera.openreadera.data.model.AnnotationIdentity.of(book, old)
        mergeRecord(bookmark.copy(syncId = stableId, updatedAt = maxOf(System.currentTimeMillis(), old.updatedAt + 1), deletedAt = old.deletedAt))
    }

    @Transaction
    suspend fun delete(bookmark: Bookmark) {
        val old = byId(bookmark.id) ?: return
        if (old.deletedAt > 0L) return
        val book = annotationBook(old.bookId) ?: return
        val now = maxOf(System.currentTimeMillis(), old.updatedAt + 1)
        mergeRecord(old.copy(title = "", snippet = "", syncId = org.readera.openreadera.data.model.AnnotationIdentity.of(book, old), updatedAt = now, deletedAt = now))
    }

    @Transaction
    suspend fun deleteAllForBook(bookId: Long) {
        getAllBookmarksList().filter { it.bookId == bookId && it.deletedAt == 0L }.forEach { delete(it) }
    }

    @Transaction
    suspend fun deleteByPage(bookId: Long, page: Int) {
        getAllBookmarksList().filter { it.bookId == bookId && it.page == page && it.deletedAt == 0L }.forEach { delete(it) }
    }
}
