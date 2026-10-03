package org.readera.openreadera.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import org.readera.openreadera.data.db.*
import org.readera.openreadera.data.model.*
import org.readera.openreadera.data.model.Collection as BookCollection

internal object DrawingStrokeSyncGate {
    val mutex = Mutex()
}

class BookRepository(
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao,
    private val quoteDao: QuoteDao,
    private val collectionDao: CollectionDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val drawingStrokeDao: DrawingStrokeDao? = null
) {
    // Books
    fun getAllBooks(): Flow<List<Book>> = bookDao.getAllBooks()
    suspend fun getAllBooksList(): List<Book> = bookDao.getAllBooksList()
    fun getReadingNow(): Flow<List<Book>> = bookDao.getReadingNow()
    fun getFavorites(): Flow<List<Book>> = bookDao.getFavorites()
    fun getToRead(): Flow<List<Book>> = bookDao.getToRead()
    fun getHaveRead(): Flow<List<Book>> = bookDao.getHaveRead()
    fun getTrashBooks(): Flow<List<Book>> = bookDao.getTrashBooks()
    fun getBookById(id: Long): Flow<Book?> = bookDao.getBookById(id)
    suspend fun getBookByIdSync(id: Long): Book? = bookDao.getBookByIdSync(id)
    suspend fun getBookByPath(path: String): Book? = bookDao.getBookByPath(path)
    fun searchBooks(query: String): Flow<List<Book>> = bookDao.searchBooks(query)

    suspend fun insertBook(book: Book): Long = bookDao.insert(book)
    suspend fun insertAll(books: List<Book>) = bookDao.insertAll(books)
    suspend fun updateBook(book: Book) = bookDao.update(book)
    suspend fun updateCoverAndMetadata(book: Book) = bookDao.updateCoverAndMetadata(
        id = book.id,
        title = book.title,
        author = book.author,
        coverPath = book.coverPath,
        sha1 = book.sha1,
        language = book.language,
        genre = book.genre,
        description = book.description,
        series = book.series
    )
    suspend fun updateProgress(id: Long, page: Int, progress: Float, totalPages: Int = 1) = bookDao.updateProgress(id, page, progress, totalPages)
    suspend fun toggleFavorite(id: Long) = bookDao.toggleFavorite(id)
    suspend fun toggleToRead(id: Long) = bookDao.toggleToRead(id)
    suspend fun toggleHaveRead(id: Long) = bookDao.toggleHaveRead(id)
    suspend fun moveToTrash(id: Long) = bookDao.moveToTrash(id)
    suspend fun restoreFromTrash(id: Long) = bookDao.restoreFromTrash(id)
    suspend fun emptyTrash() = bookDao.emptyTrash()
    suspend fun clearReadingNow() = bookDao.clearReadingNow()
    suspend fun removeFromReadingNow(id: Long) = bookDao.removeFromReadingNow(id)
    suspend fun markAsReading(id: Long) = bookDao.markAsReading(id)
    suspend fun restoreTresCuerpos() = bookDao.restoreTresCuerpos()
    suspend fun clearFavorites() = bookDao.clearFavorites()
    suspend fun clearToRead() = bookDao.clearToRead()
    suspend fun clearHaveRead() = bookDao.clearHaveRead()
    suspend fun updateMetadata(id: Long, title: String, author: String, series: String?, annotation: String?, language: String?) =
        bookDao.updateMetadata(id, title, author, series, annotation, language)
    suspend fun updateReview(id: Long, rating: Float?, review: String?) = bookDao.updateReview(id, rating, review)
    suspend fun updateStatus(id: Long, status: BookStatus) = bookDao.setStatus(id, status)
    suspend fun deleteBook(book: Book) = bookDao.delete(book)

    // Bookmarks
    fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>> = bookmarkDao.getBookmarksForBook(bookId)
    fun getAllBookmarks(): Flow<List<Bookmark>> = bookmarkDao.getAllBookmarks()
    suspend fun addBookmark(bookmark: Bookmark): Long = bookmarkDao.insert(bookmark)
    suspend fun updateBookmark(bookmark: Bookmark) = bookmarkDao.update(bookmark)
    suspend fun deleteBookmark(bookmark: Bookmark) = bookmarkDao.delete(bookmark)
    suspend fun deleteBookmarkByPage(bookId: Long, page: Int) = bookmarkDao.deleteByPage(bookId, page)
    suspend fun deleteAllBookmarksForBook(bookId: Long) = bookmarkDao.deleteAllForBook(bookId)

    // Quotes
    fun getQuotesForBook(bookId: Long): Flow<List<Quote>> = quoteDao.getQuotesForBook(bookId)
    fun getAllQuotes(): Flow<List<Quote>> = quoteDao.getAllQuotes()
    suspend fun addQuote(quote: Quote): Long = quoteDao.insert(quote)
    suspend fun updateQuote(quote: Quote) = quoteDao.update(quote)
    suspend fun deleteQuote(quote: Quote) = quoteDao.delete(quote)
    suspend fun deleteAllQuotesForBook(bookId: Long) = quoteDao.deleteAllForBook(bookId)

    // Drawing Strokes (Stylus Inking)
    fun getStrokesForBook(bookId: Long): Flow<List<DrawingStroke>> =
        drawingStrokeDao?.getStrokesForBook(bookId) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    fun getStrokesForPage(bookId: Long, page: Int): Flow<List<DrawingStroke>> =
        drawingStrokeDao?.getStrokesForPage(bookId, page) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun addDrawingStroke(stroke: DrawingStroke): Long =
        drawingStrokeDao?.insert(stroke) ?: -1L
    suspend fun deleteDrawingStroke(stroke: DrawingStroke) =
        drawingStrokeDao?.delete(stroke)
    suspend fun deleteLastStrokeForPage(bookId: Long, page: Int) =
        drawingStrokeDao?.deleteLastStrokeForPage(bookId, page)
    suspend fun deleteAllStrokesForPage(bookId: Long, page: Int) =
        drawingStrokeDao?.deleteAllForPage(bookId, page)
    suspend fun deleteAllStrokesForBook(bookId: Long) =
        drawingStrokeDao?.deleteAllForBook(bookId)

    // Collections
    fun getAllCollections(): Flow<List<BookCollection>> = collectionDao.getAllCollections()
    suspend fun getAllCollectionsList(): List<BookCollection> = collectionDao.getAllCollectionsList()
    fun getCollectionsWithCount(): Flow<List<CollectionWithCount>> = collectionDao.getCollectionsWithCount()
    fun getAllBookCollections(): Flow<List<BookCollectionCrossRef>> = collectionDao.getAllBookCollections()
    suspend fun getAllBookCollectionsSync(): List<BookCollectionCrossRef> = collectionDao.getAllBookCollectionsSync()
    fun getCollectionsForBook(bookId: Long): Flow<List<BookCollection>> = collectionDao.getCollectionsForBook(bookId)
    suspend fun getCollectionsForBookSync(bookId: Long): List<BookCollection> = collectionDao.getCollectionsForBookSync(bookId)
    suspend fun getCollectionByName(name: String): BookCollection? = collectionDao.getCollectionByName(name)
    fun getBooksInCollection(collectionId: Long): Flow<List<Book>> = collectionDao.getBooksInCollection(collectionId)
    suspend fun addCollection(name: String): Long = collectionDao.insert(BookCollection(name = name))
    suspend fun addBookToCollection(bookId: Long, collectionId: Long) =
        collectionDao.addBookToCollection(BookCollectionCrossRef(bookId, collectionId))
    suspend fun removeBookFromCollection(bookId: Long, collectionId: Long) =
        collectionDao.removeBookFromCollection(bookId, collectionId)
    suspend fun setBookCollections(bookId: Long, collectionIds: List<Long>) =
        collectionDao.setBookCollections(bookId, collectionIds)
    suspend fun addBookCollections(crossRefs: List<BookCollectionCrossRef>) =
        collectionDao.addBookCollections(crossRefs)
    suspend fun setAutomaticCategory(bookId: Long, categoryId: Long, automaticIds: List<Long>) =
        collectionDao.setAutomaticCategory(bookId, categoryId, automaticIds)
    suspend fun deleteAllCollections() = collectionDao.deleteAllCollections()
    suspend fun deleteAllBookCollections() = collectionDao.deleteAllBookCollections()
    suspend fun replaceAllBookCollections(crossRefs: List<BookCollectionCrossRef>) =
        collectionDao.replaceAllBookCollections(crossRefs)
    suspend fun deleteBooksNotInDirectory(prefix: String) = bookDao.deleteBooksNotInDirectory(prefix)
    suspend fun deleteBooksNotContainingPath(keyword: String) = bookDao.deleteBooksNotContainingPath(keyword)
    suspend fun deleteAllBooks() = bookDao.deleteAllBooks()

    // Search History
    fun getSearchHistory(): Flow<List<SearchQuery>> = searchHistoryDao.getRecentQueries()
    suspend fun addSearchQuery(query: String) {
        if (query.isNotBlank()) {
            searchHistoryDao.insertQuery(SearchQuery(query = query.trim()))
        }
    }
    suspend fun clearSearchHistory() = searchHistoryDao.clearHistory()

    // Reviews & Ratings
    fun getReviewedBooks(): Flow<List<Book>> = bookDao.getReviewedBooks()
    suspend fun updateReview(bookId: Long, rating: Float?, review: String?, reviewDate: Long? = System.currentTimeMillis(), reviewTags: String? = null) =
        bookDao.updateReview(bookId, rating, review, reviewDate, reviewTags)
}
