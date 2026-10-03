package org.readera.openreadera.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.readera.openreadera.data.model.DrawingStroke

@Dao
interface DrawingStrokeDao {
    @Query("SELECT * FROM drawing_strokes WHERE bookId = :bookId ORDER BY page ASC, createdAt ASC")
    fun getStrokesForBook(bookId: Long): Flow<List<DrawingStroke>>

    @Query("SELECT * FROM drawing_strokes ORDER BY bookId ASC, page ASC, createdAt ASC")
    suspend fun getAllStrokesList(): List<DrawingStroke>

    @Query("SELECT * FROM drawing_strokes WHERE bookId = :bookId AND page = :page ORDER BY createdAt ASC")
    fun getStrokesForPage(bookId: Long, page: Int): Flow<List<DrawingStroke>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stroke: DrawingStroke): Long

    @Delete
    suspend fun delete(stroke: DrawingStroke)

    @Query("DELETE FROM drawing_strokes WHERE id = (SELECT id FROM drawing_strokes WHERE bookId = :bookId AND page = :page ORDER BY createdAt DESC LIMIT 1)")
    suspend fun deleteLastStrokeForPage(bookId: Long, page: Int)

    @Query("DELETE FROM drawing_strokes WHERE bookId = :bookId AND page = :page")
    suspend fun deleteAllForPage(bookId: Long, page: Int)

    @Query("DELETE FROM drawing_strokes WHERE bookId = :bookId")
    suspend fun deleteAllForBook(bookId: Long)
}
