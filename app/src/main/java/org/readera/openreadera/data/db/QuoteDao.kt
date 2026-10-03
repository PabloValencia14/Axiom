package org.readera.openreadera.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.readera.openreadera.data.model.Quote

@Dao
interface QuoteDao {
    @Query("SELECT * FROM quotes WHERE bookId = :bookId ORDER BY page ASC, createdAt DESC")
    fun getQuotesForBook(bookId: Long): Flow<List<Quote>>

    @Query("SELECT * FROM quotes ORDER BY createdAt DESC")
    fun getAllQuotes(): Flow<List<Quote>>

    @Query("SELECT * FROM quotes ORDER BY createdAt DESC")
    suspend fun getAllQuotesList(): List<Quote>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(quote: Quote): Long

    @Update
    suspend fun update(quote: Quote)

    @Delete
    suspend fun delete(quote: Quote)

    @Query("DELETE FROM quotes WHERE bookId = :bookId")
    suspend fun deleteAllForBook(bookId: Long)
}
