package org.readera.openreadera.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.BookStatus

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE isTrash = 0 ORDER BY lastOpened DESC, dateAdded DESC")
    fun getAllBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE isTrash = 0 ORDER BY lastOpened DESC, dateAdded DESC")
    suspend fun getAllBooksList(): List<Book>

    @Query("SELECT * FROM books ORDER BY lastOpened DESC, dateAdded DESC")
    suspend fun getAllBooksIncludingTrashList(): List<Book>

    @Query("""
        SELECT DISTINCT books.* FROM books
        INNER JOIN book_collections ON book_collections.bookId = books.id
        INNER JOIN collections ON collections.id = book_collections.collectionId
        WHERE books.isTrash = 0 AND books.status = 'READING'
          AND collections.name IN ('Libros', 'Novela-grafica')
        ORDER BY books.lastOpened DESC
    """)
    fun getReadingNow(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE isTrash = 0 AND isFavorite = 1 ORDER BY lastOpened DESC, title ASC")
    fun getFavorites(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE isTrash = 0 AND (isToRead = 1 OR status = 'TO_READ') ORDER BY dateAdded DESC")
    fun getToRead(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE isTrash = 0 AND (isHaveRead = 1 OR status = 'HAVE_READ') ORDER BY lastOpened DESC")
    fun getHaveRead(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE isTrash = 1 ORDER BY dateAdded DESC")
    fun getTrashBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun getBookById(id: Long): Flow<Book?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookByIdSync(id: Long): Book?

    @Query("SELECT * FROM books WHERE filePath = :path LIMIT 1")
    suspend fun getBookByPath(path: String): Book?

    @Query("SELECT * FROM books WHERE isTrash = 0 AND (title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%') ORDER BY title ASC")
    fun searchBooks(query: String): Flow<List<Book>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: Book): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(books: List<Book>): List<Long>

    @Update
    suspend fun update(book: Book)

    @Query("""
        UPDATE books SET
        title = CASE WHEN title = '' OR title = 'Desconocido' OR title = :fileTitle THEN :title ELSE title END,
        author = CASE WHEN author = '' OR author IN ('Desconocido', 'Autor desconocido') THEN :author ELSE author END,
        coverPath = :coverPath, sha1 = :sha1,
        language = COALESCE(language, :language), genre = COALESCE(genre, :genre),
        description = COALESCE(description, :description), series = COALESCE(series, :series)
        WHERE id = :id
    """)
    suspend fun updateCoverAndMetadata(
        id: Long,
        fileTitle: String,
        title: String,
        author: String,
        coverPath: String?,
        sha1: String?,
        language: String?,
        genre: String?,
        description: String?,
        series: String?
    )

    @Query("UPDATE books SET currentPage = :page, progressPercent = :progress, totalPages = CASE WHEN :totalPages > 1 THEN :totalPages ELSE totalPages END, lastOpened = :lastOpened, status = 'READING' WHERE id = :id")
    suspend fun updateProgress(id: Long, page: Int, progress: Float, totalPages: Int = 1, lastOpened: Long = System.currentTimeMillis())

    @Query("UPDATE books SET status = 'UNREAD' WHERE id = :id")
    suspend fun removeFromReadingNow(id: Long)

    @Query("UPDATE books SET status = 'READING', lastOpened = :now WHERE id = :id")
    suspend fun markAsReading(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE books SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query("UPDATE books SET isFavorite = CASE WHEN isFavorite = 1 THEN 0 ELSE 1 END WHERE id = :id")
    suspend fun toggleFavorite(id: Long)

    @Query("UPDATE books SET isToRead = CASE WHEN isToRead = 1 THEN 0 ELSE 1 END, status = CASE WHEN isToRead = 0 THEN 'TO_READ' ELSE status END WHERE id = :id")
    suspend fun toggleToRead(id: Long)

    @Query("UPDATE books SET isHaveRead = CASE WHEN isHaveRead = 1 THEN 0 ELSE 1 END, status = CASE WHEN isHaveRead = 0 THEN 'HAVE_READ' ELSE status END WHERE id = :id")
    suspend fun toggleHaveRead(id: Long)

    @Query("UPDATE books SET isTrash = 1 WHERE id = :id")
    suspend fun moveToTrash(id: Long)

    @Query("UPDATE books SET isTrash = 0 WHERE id = :id")
    suspend fun restoreFromTrash(id: Long)

    @Query("DELETE FROM books WHERE isTrash = 1")
    suspend fun emptyTrash()

    @Query("UPDATE books SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: BookStatus)

    @Query("UPDATE books SET status = 'UNREAD' WHERE isTrash = 0 AND status = 'READING'")
    suspend fun clearReadingNow()

    @Query("UPDATE books SET isFavorite = 0 WHERE isTrash = 0")
    suspend fun clearFavorites()

    @Query("UPDATE books SET isToRead = 0, status = CASE WHEN status = 'TO_READ' THEN 'UNREAD' ELSE status END WHERE isTrash = 0")
    suspend fun clearToRead()

    @Query("UPDATE books SET isHaveRead = 0, status = CASE WHEN status = 'HAVE_READ' THEN 'UNREAD' ELSE status END WHERE isTrash = 0")
    suspend fun clearHaveRead()

    @Query("UPDATE books SET title = :title, author = :author, series = :series, annotation = :annotation, language = :language WHERE id = :id")
    suspend fun updateMetadata(id: Long, title: String, author: String, series: String?, annotation: String?, language: String?)

    @Query("SELECT * FROM books WHERE isTrash = 0 AND rating IS NOT NULL AND rating > 0 ORDER BY reviewDate DESC, lastOpened DESC")
    fun getReviewedBooks(): Flow<List<Book>>

    @Query("UPDATE books SET rating = :rating, review = :review, reviewDate = :reviewDate, reviewTags = :reviewTags WHERE id = :id")
    suspend fun updateReview(id: Long, rating: Float?, review: String?, reviewDate: Long? = System.currentTimeMillis(), reviewTags: String? = null)

    @Query("DELETE FROM books WHERE filePath NOT LIKE :prefix || '%'")
    suspend fun deleteBooksNotInDirectory(prefix: String)

    @Query("DELETE FROM books WHERE filePath NOT LIKE '%' || :keyword || '%'")
    suspend fun deleteBooksNotContainingPath(keyword: String)

    @Query("DELETE FROM books")
    suspend fun deleteAllBooks()

    @Delete
    suspend fun delete(book: Book)
}
