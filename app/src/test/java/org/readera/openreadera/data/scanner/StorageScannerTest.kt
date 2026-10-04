package org.readera.openreadera.data.scanner

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readera.openreadera.data.db.AppDatabase
import org.readera.openreadera.data.db.BookDao
import org.readera.openreadera.data.db.CollectionDao
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.Bookmark
import org.readera.openreadera.data.repository.BookRepository
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StorageScannerTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: BookRepository
    private lateinit var scanner: StorageScanner
    private lateinit var root: File
    private lateinit var internalRoot: File
    private var pathLookups = 0
    private var idLookups = 0
    private var automaticWrites = 0

    @Before
    fun setUp() {
        val base = RuntimeEnvironment.getApplication()
        val token = System.nanoTime().toString()
        internalRoot = File(base.cacheDir, "scanner-internal-$token").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = internalRoot
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("scanner_test_${token}_$name", mode)
        }
        root = File(StorageScanner.getAppBooksDirectory(), "scanner-test-$token").apply { mkdirs() }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val delegate = database.bookDao()
        val observedDao = object : BookDao by delegate {
            override suspend fun getBookByPath(path: String): Book? {
                pathLookups++
                return delegate.getBookByPath(path)
            }
            override suspend fun getBookByIdSync(id: Long): Book? {
                idLookups++
                return delegate.getBookByIdSync(id)
            }
        }
        val collectionDelegate = database.collectionDao()
        val observedCollections = object : CollectionDao by collectionDelegate {
            override suspend fun setAutomaticCategory(bookId: Long, categoryId: Long, automaticIds: List<Long>) {
                automaticWrites++
                collectionDelegate.setAutomaticCategory(bookId, categoryId, automaticIds)
            }
        }
        repository = BookRepository(observedDao, database.bookmarkDao(), database.quoteDao(),
            observedCollections, database.searchHistoryDao())
        scanner = StorageScanner(context, repository)
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
        internalRoot.deleteRecursively()
    }

    @Test
    fun emptyAutomaticScanIsThrottledButExplicitRefreshFindsNewFiles() = runBlocking {
        scanner.scanStorage(force = false)
        assertTrue(repository.getAllBooksList().isEmpty())
        File(root, "new-after-empty.txt").writeText("New document")

        scanner.scanStorage(force = false)
        assertTrue(repository.getAllBooksList().isEmpty())
        scanner.scanStorage()
        assertEquals("new-after-empty", repository.getAllBooksList().single().title)
        assertEquals(0, pathLookups)
        assertEquals(0, idLookups)
    }

    @Test
    fun rescansPreserveUnavailableBooksAnnotationsUserMetadataAndTrashCovers() = runBlocking {
        val present = File(root, "present.txt").apply { writeText("Stored document") }
        val trashFile = File(root, "trash.txt").apply { writeText("Trashed document") }
        val cover = File(internalRoot, "covers/trash.jpg").apply {
            parentFile!!.mkdirs()
            writeText("Referenced cover")
        }
        val orphan = File(cover.parentFile, "orphan.jpg").apply { writeText("Orphan cover") }
        val presentId = repository.insertBook(Book(title = "Edited title", author = "My author",
            filePath = present.absolutePath, format = "Texto", currentPage = 12, progressPercent = 42f,
            isFavorite = true, series = "My series", language = "My language", review = "My review"))
        val missingId = repository.insertBook(Book(title = "Temporarily unavailable", filePath =
            File(root, "missing.txt").absolutePath, format = "Texto", currentPage = 7))
        val trashId = repository.insertBook(Book(title = "In trash", filePath = trashFile.absolutePath,
            format = "Texto", isTrash = true, coverPath = cover.absolutePath))
        repository.addBookmark(Bookmark(bookId = missingId, page = 7, title = "Keep this annotation"))
        val customId = repository.addCollection("My collection")
        repository.addBookToCollection(presentId, customId)
        val original = repository.getBookByIdSync(presentId)!!
        pathLookups = 0
        idLookups = 0

        scanner.scanStorage()
        automaticWrites = 0
        scanner.scanStorage()

        assertEquals(0, pathLookups)
        assertEquals(0, idLookups)
        assertEquals(0, automaticWrites)
        val books = repository.getAllBooksIncludingTrashList().associateBy { it.id }
        assertEquals(3, books.size)
        assertEquals(original.copy(sha1 = books[presentId]!!.sha1), books[presentId])
        assertEquals(7, books[missingId]!!.currentPage)
        assertTrue(books[trashId]!!.isTrash)
        assertTrue(cover.isFile)
        assertFalse(orphan.exists())
        assertEquals("Keep this annotation", repository.getBookmarksForBook(missingId).first().single().title)
        assertTrue(repository.getCollectionsForBookSync(presentId).any { it.id == customId })
    }

    @Test
    fun coverlessAttemptsSurviveScannerRecreationAndChangedFilesInvalidateThem() = runBlocking {
        val file = File(root, "coverless.txt").apply { writeText("Original content") }
        scanner.scanStorage()
        val original = repository.getAllBooksList().single()
        assertNotNull(original.sha1)
        assertNull(original.coverPath)
        // Simulate a sync metadata edit. An unchanged coverless document must
        // not be reopened/rehashed merely because no generated cover exists.
        repository.updateBook(original.copy(sha1 = null, title = "My edited title"))
        scanner = StorageScanner(context, repository)
        scanner.scanStorage()
        assertNull(repository.getAllBooksList().single().sha1)

        file.appendText(" changed length")
        scanner.scanStorage()
        val changed = repository.getAllBooksList().single()
        assertNotNull(changed.sha1)
        assertNotEquals(original.sha1, changed.sha1)
        assertEquals("My edited title", changed.title)
    }

    @Test
    fun missingCoverRepairFillsOnlyPlaceholdersAndManualCategorySurvivesRescans() = runBlocking {
        val file = File(root, "metadata.epub")
        writeCoverlessEpub(file)
        val id = repository.insertBook(Book(title = "My edited title", author = "My edited author",
            filePath = file.absolutePath, format = "EPUB", series = "My series", language = "My language",
            genre = "My genre", description = "My description", currentPage = 9, isFavorite = true))
        val customId = repository.addCollection("My personal collection")
        repository.addBookToCollection(id, customId)
        val stored = repository.getBookByIdSync(id)!!
        DocumentCategoryClassifier.setManualCategory(context, stored, DocumentCategory.PAPER)

        scanner.scanStorage()
        scanner.scanStorage()
        val enriched = repository.getAllBooksList().single()
        assertEquals(stored.copy(sha1 = enriched.sha1), enriched)
        assertNotNull(enriched.sha1)
        assertEquals(setOf("My personal collection", "Papers"),
            repository.getCollectionsForBookSync(id).map { it.name }.toSet())

        repository.updateMetadata(id, "Diapositivas de redes", "My edited author", "My series", null, "My language")
        scanner.scanStorage()
        assertTrue(repository.getCollectionsForBookSync(id).any { it.name == "Papers" })
        DocumentCategoryClassifier.setManualCategory(context, enriched, null)
        scanner.scanStorage()
        assertEquals(setOf("My personal collection", "Presentaciones"),
            repository.getCollectionsForBookSync(id).map { it.name }.toSet())
    }

    @Test
    fun newCoverlessEpubStillReceivesExtractedMetadata() = runBlocking {
        writeCoverlessEpub(File(root, "metadata-default.epub"))
        scanner.scanStorage()
        val book = repository.getAllBooksList().single()
        assertEquals("Extracted title", book.title)
        assertEquals("Extracted author", book.author)
        assertEquals("Extracted language", book.language)
        assertEquals("Extracted genre", book.genre)
        assertEquals("Extracted description", book.description)
        assertEquals("Extracted series", book.series)
        assertNotNull(book.sha1)
        assertNull(book.coverPath)
    }

    private fun writeCoverlessEpub(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("book.opf"))
            zip.write("""<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>Extracted title</dc:title><dc:creator>Extracted author</dc:creator><dc:language>Extracted language</dc:language><dc:description>Extracted description</dc:description><dc:subject>Extracted genre</dc:subject><meta name="calibre:series" content="Extracted series"/></metadata><manifest/></package>""".toByteArray())
            zip.closeEntry()
        }
    }
}
