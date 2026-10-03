package org.readera.openreadera

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readera.openreadera.data.db.AppDatabase
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.BookCollectionCrossRef
import org.readera.openreadera.data.model.Collection

@RunWith(AndroidJUnit4::class)
class CollectionPersistenceTest {
    @Test
    fun categoryUpdatesPreservePersonalCollectionsAndAvoidRedundantWrites() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
                val dao = db.collectionDao()
                val book = db.bookDao().insert(Book(title = "Apuntes", filePath = "/test.pdf", format = "PDF"))
                val personal = dao.insert(Collection(name = "Mi asignatura"))
                val books = dao.insert(Collection(name = "Libros"))
                val study = dao.insert(Collection(name = "Documentos de estudio"))
                dao.setBookCollections(book, listOf(personal))
                // The legacy import must merge, including when repeated.
                repeat(2) { dao.addBookCollections(listOf(BookCollectionCrossRef(book, books))) }
                assertEquals(setOf(personal, books), dao.getCollectionsForBookSync(book).map { it.id }.toSet())
                dao.setAutomaticCategory(book, study, listOf(books, study))
                assertEquals(setOf(personal, study), dao.getCollectionsForBookSync(book).map { it.id }.toSet())
                fun changes(): Long = db.openHelper.writableDatabase.query("SELECT total_changes()")
                    .use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
                val before = changes()
                repeat(3) { dao.setAutomaticCategory(book, study, listOf(books, study)) }
                assertEquals("Unchanged categories must not write", before, changes())
                db.bookDao().moveToTrash(book)
                assertEquals(0, dao.getCollectionsWithCount().first().first { it.id == study }.bookCount)
                db.bookDao().restoreFromTrash(book)
                assertEquals(1, dao.getCollectionsWithCount().first().first { it.id == study }.bookCount)
        } finally {
            db.close()
        }
    }

    @Test
    fun readingNowIncludesOnlyBooksAndComicsFromAutomaticCollections() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val books = db.bookDao()
            val collections = db.collectionDao()
            val categories = listOf("Libros", "Novela-grafica", "Documentos de estudio", "Presentaciones", "Papers")
                .associateWith { collections.insert(Collection(name = it)) }
            val personal = collections.insert(Collection(name = "Personal"))
            val bookIds = listOf(
                books.insert(Book(title = "Book", filePath = "/book.epub", format = "EPUB", status = org.readera.openreadera.data.model.BookStatus.READING)),
                books.insert(Book(title = "Comic", filePath = "/comic.cbz", format = "CBZ", status = org.readera.openreadera.data.model.BookStatus.READING)),
                books.insert(Book(title = "Document", filePath = "/document.pdf", format = "PDF", status = org.readera.openreadera.data.model.BookStatus.READING)),
                books.insert(Book(title = "Presentation", filePath = "/presentation.pdf", format = "PDF", status = org.readera.openreadera.data.model.BookStatus.READING)),
                books.insert(Book(title = "Paper", filePath = "/paper.pdf", format = "PDF", status = org.readera.openreadera.data.model.BookStatus.READING)),
                books.insert(Book(title = "Uncategorized", filePath = "/unknown.epub", format = "EPUB", status = org.readera.openreadera.data.model.BookStatus.READING))
            )
            collections.setBookCollections(bookIds[0], listOf(categories.getValue("Libros"), personal))
            collections.setBookCollections(bookIds[1], listOf(categories.getValue("Libros"), categories.getValue("Novela-grafica")))
            collections.setBookCollections(bookIds[2], listOf(categories.getValue("Documentos de estudio")))
            collections.setBookCollections(bookIds[3], listOf(categories.getValue("Presentaciones")))
            collections.setBookCollections(bookIds[4], listOf(categories.getValue("Papers")))

            val readingNow = books.getReadingNow().first()
            assertEquals(setOf(bookIds[0], bookIds[1]), readingNow.map { it.id }.toSet())
            assertEquals("Stale dual-category membership must not duplicate a reading row", 2, readingNow.size)
        } finally {
            db.close()
        }
    }

    @Test
    fun failedCollectionEditRollsBackRemovedMemberships() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
                val dao = db.collectionDao()
                val book = db.bookDao().insert(Book(title = "Libro", filePath = "/test.epub", format = "EPUB"))
                val personal = dao.insert(Collection(name = "Personal"))
                dao.setBookCollections(book, listOf(personal))
                try {
                    dao.setBookCollections(book, listOf(Long.MAX_VALUE))
                    fail("An invalid collection must fail")
                } catch (_: SQLiteConstraintException) {
                    assertEquals(listOf(personal), dao.getCollectionsForBookSync(book).map { it.id })
                }
        } finally {
            db.close()
        }
    }
}
