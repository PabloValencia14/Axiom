package org.readera.openreadera.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.BookCollectionCrossRef
import org.readera.openreadera.data.model.Collection

data class CollectionWithCount(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val bookCount: Int
)

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY name ASC")
    fun getAllCollections(): Flow<List<Collection>>

    @Query("SELECT * FROM collections ORDER BY name ASC")
    suspend fun getAllCollectionsList(): List<Collection>

    @Query("""
        SELECT collections.id, collections.name, collections.createdAt, COUNT(books.id) AS bookCount 
        FROM collections 
        LEFT JOIN book_collections ON collections.id = book_collections.collectionId 
        LEFT JOIN books ON books.id = book_collections.bookId AND books.isTrash = 0
        GROUP BY collections.id 
        ORDER BY collections.name ASC
    """)
    fun getCollectionsWithCount(): Flow<List<CollectionWithCount>>

    @Query("""
        SELECT collections.* FROM collections 
        INNER JOIN book_collections ON collections.id = book_collections.collectionId 
        WHERE book_collections.bookId = :bookId 
        ORDER BY collections.name ASC
    """)
    fun getCollectionsForBook(bookId: Long): Flow<List<Collection>>

    @Query("""
        SELECT collections.* FROM collections 
        INNER JOIN book_collections ON collections.id = book_collections.collectionId 
        WHERE book_collections.bookId = :bookId 
        ORDER BY collections.name ASC
    """)
    suspend fun getCollectionsForBookSync(bookId: Long): List<Collection>

    @Query("""
        SELECT books.* FROM books 
        INNER JOIN book_collections ON books.id = book_collections.bookId 
        WHERE book_collections.collectionId = :collectionId AND books.isTrash = 0 
        ORDER BY books.title ASC
    """)
    fun getBooksInCollection(collectionId: Long): Flow<List<Book>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(collection: Collection): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(collections: List<Collection>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBookToCollection(crossRef: BookCollectionCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBookCollections(crossRefs: List<BookCollectionCrossRef>)

    @Transaction
    suspend fun setBookCollections(bookId: Long, collectionIds: List<Long>) {
        val current = getCollectionsForBookSync(bookId).map { it.id }.toSet()
        val desired = collectionIds.toSet()
        for (id in current - desired) removeBookFromCollection(bookId, id)
        addBookCollections((desired - current).map { BookCollectionCrossRef(bookId, it) })
    }

    @Transaction
    suspend fun setAutomaticCategory(bookId: Long, categoryId: Long, automaticIds: List<Long>) {
        require(categoryId in automaticIds)
        val current = getCollectionsForBookSync(bookId).map { it.id }.toSet()
        for (id in current.intersect(automaticIds.toSet()) - categoryId) {
            removeBookFromCollection(bookId, id)
        }
        if (categoryId !in current) addBookToCollection(BookCollectionCrossRef(bookId, categoryId))
    }

    @Transaction
    suspend fun replaceAllBookCollections(crossRefs: List<BookCollectionCrossRef>) {
        deleteAllBookCollections()
        addBookCollections(crossRefs)
    }

    @Query("DELETE FROM book_collections WHERE bookId = :bookId AND collectionId = :collectionId")
    suspend fun removeBookFromCollection(bookId: Long, collectionId: Long)

    @Query("DELETE FROM book_collections WHERE bookId = :bookId")
    suspend fun removeAllCollectionsForBook(bookId: Long)

    @Query("SELECT * FROM book_collections")
    fun getAllBookCollections(): Flow<List<BookCollectionCrossRef>>

    @Query("SELECT * FROM book_collections")
    suspend fun getAllBookCollectionsSync(): List<BookCollectionCrossRef>

    @Query("SELECT * FROM collections WHERE name = :name LIMIT 1")
    suspend fun getCollectionByName(name: String): Collection?

    @Query("DELETE FROM collections")
    suspend fun deleteAllCollections()

    @Query("DELETE FROM book_collections")
    suspend fun deleteAllBookCollections()

    @Delete
    suspend fun delete(collection: Collection)
}
