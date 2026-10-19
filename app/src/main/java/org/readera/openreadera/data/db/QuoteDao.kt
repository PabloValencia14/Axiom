package org.readera.openreadera.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.readera.openreadera.data.model.Quote

@Dao
interface QuoteDao {
    @Query("SELECT * FROM quotes WHERE bookId = :bookId AND deletedAt = 0 ORDER BY page ASC, createdAt DESC")
    fun getQuotesForBook(bookId: Long): Flow<List<Quote>>

    @Query("SELECT * FROM quotes WHERE deletedAt = 0 ORDER BY createdAt DESC")
    fun getAllQuotes(): Flow<List<Quote>>

    @Query("SELECT * FROM quotes ORDER BY createdAt DESC")
    suspend fun getAllQuotesList(): List<Quote>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(quote: Quote): Long

    @Query("SELECT * FROM quotes WHERE id = :id")
    suspend fun byId(id: Long): Quote?

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun annotationBook(id: Long): org.readera.openreadera.data.model.Book?

    @Update
    suspend fun mergeRecord(quote: Quote)

    @Transaction
    suspend fun update(quote: Quote) {
        val old = byId(quote.id) ?: return
        if (old.deletedAt > 0L) return
        val book = annotationBook(old.bookId) ?: return
        val stableId = org.readera.openreadera.data.model.AnnotationIdentity.of(book, old)
        mergeRecord(quote.copy(syncId = stableId, updatedAt = maxOf(System.currentTimeMillis(), old.updatedAt + 1), deletedAt = old.deletedAt))
    }

    @Transaction
    suspend fun delete(quote: Quote) {
        val old = byId(quote.id) ?: return
        if (old.deletedAt > 0L) return
        val book = annotationBook(old.bookId) ?: return
        val now = maxOf(System.currentTimeMillis(), old.updatedAt + 1)
        mergeRecord(old.copy(text = "", note = "", syncId = org.readera.openreadera.data.model.AnnotationIdentity.of(book, old), updatedAt = now, deletedAt = now))
    }

    @Transaction
    suspend fun deleteAllForBook(bookId: Long) {
        getAllQuotesList().filter { it.bookId == bookId && it.deletedAt == 0L }.forEach { delete(it) }
    }
}
