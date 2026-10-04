package org.readera.openreadera.data.scanner

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.BookCollectionCrossRef
import org.readera.openreadera.data.model.BookStatus
import org.readera.openreadera.data.model.Bookmark
import org.readera.openreadera.data.repository.BookRepository
import org.readera.openreadera.engine.DocumentFormat
import java.io.File
import java.util.zip.ZipFile

class StorageScanner(
    private val context: Context,
    private val repository: BookRepository
) {
    companion object {
        private const val TAG = "StorageScanner"
        val SUPPORTED_EXTENSIONS = setOf(
            "pdf", "epub", "mobi", "azw", "azw3", "djvu", "djv",
            "fb2", "txt", "doc", "docx", "rtf", "cbr", "cbz", "chm"
        )

        fun getAppBooksDirectory(): File {
            val primaryStorage = Environment.getExternalStorageDirectory()
            val readEraDir = File(primaryStorage, "Documents/Conocimiento/04_Recursos/Readera/Libros")
            if (readEraDir.exists() && readEraDir.isDirectory) {
                return readEraDir
            }
            val appDir = File(primaryStorage, "Documents/OpenReadEra/Libros")
            if (!appDir.exists()) {
                appDir.mkdirs()
            }
            return appDir
        }

        suspend fun scanSingleFile(context: Context, file: File): Book? {
            val app = context.applicationContext as? org.readera.openreadera.OpenReadEraApplication
            return app?.storageScanner?.scanSingleFile(file)
        }
    }

    private val coverExtractor = CoverExtractor(context, repository)
    private val categoryClassifier = DocumentCategoryClassifier(context)
    private val scanPrefs = context.getSharedPreferences("storage_scanner_prefs", Context.MODE_PRIVATE)
    private val categoryMutex = Mutex()
    private val classifiedBooks = mutableMapOf<Long, Pair<CategoryInputs, DocumentCategory>>()

    private data class CategoryInputs(
        val path: String,
        val length: Long,
        val modified: Long,
        val title: String,
        val genre: String?,
        val series: String?,
        val format: String,
        val manual: DocumentCategory?
    )

    private val scanMutex = Mutex()
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(0)
    val scanProgress: StateFlow<Int> = _scanProgress.asStateFlow()

    private var lastScanTimestamp: Long? = null

    // Explicit library/settings refreshes bypass the automatic resume throttle.
    suspend fun scanStorage(force: Boolean = true) = withContext(Dispatchers.IO) {
        if (!scanMutex.tryLock()) {
            Log.i(TAG, "Storage scan is already in progress, skipping concurrent call")
            return@withContext
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && lastScanTimestamp?.let { now - it < 30_000L } == true) {
            Log.d(TAG, "Storage scan throttled (<30s since last scan)")
            scanMutex.unlock()
            return@withContext
        }
        _isScanning.value = true
        _scanProgress.value = 0

        try {
            val primaryStorage = Environment.getExternalStorageDirectory()
            val readEraDir = File(primaryStorage, "Documents/Conocimiento/04_Recursos/Readera/Libros")
            val appBooksDir = getAppBooksDirectory()
            val legacyDownloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OpenReadEra")

            // Automatically migrate any books found in legacy Downloads/OpenReadEra into the main library directory
            if (legacyDownloadDir.exists() && legacyDownloadDir.isDirectory && legacyDownloadDir.canonicalPath != appBooksDir.canonicalPath) {
                legacyDownloadDir.listFiles()?.forEach { f ->
                    if (f.isFile && SUPPORTED_EXTENSIONS.contains(f.extension.lowercase())) {
                        try {
                            val targetFile = File(appBooksDir, f.name)
                            if (!targetFile.exists()) {
                                f.copyTo(targetFile)
                                f.delete()
                                Log.i(TAG, "Migrated book from Downloads to library folder: ${f.name}")
                            }
                        } catch (me: Exception) {
                            Log.w(TAG, "Failed to migrate ${f.name}", me)
                        }
                    }
                }
            }

            val rootDirs = mutableListOf<File>()
            if (appBooksDir.exists() && appBooksDir.isDirectory) {
                rootDirs.add(appBooksDir)
            }
            if (legacyDownloadDir.exists() && legacyDownloadDir.isDirectory && legacyDownloadDir.canonicalPath != appBooksDir.canonicalPath) {
                rootDirs.add(legacyDownloadDir)
            }
            if (rootDirs.isEmpty()) {
                val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (docs != null && docs.exists()) rootDirs.add(docs)
                if (downloads != null && downloads.exists()) rootDirs.add(downloads)
            }

            // Do not delete database records just because a path is temporarily
            // unavailable. Moving a file, unplugging storage, or a transient
            // permission failure must not cascade-delete its annotations.
            val inventory = repository.getAllBooksIncludingTrashList()
            reconcileMissingBooks(inventory)
            val booksByPath = inventory.associateBy { it.filePath }.toMutableMap()

            val discoveredFiles = mutableListOf<File>()

            for (root in rootDirs) {
                scanDirectory(root, discoveredFiles)
            }

            Log.i(TAG, "Discovered ${discoveredFiles.size} documents during storage scan")

            for (file in discoveredFiles) {
                if (file.absolutePath !in booksByPath) {
                    val book = indexedBook(file)
                    val bookId = repository.insertBook(book)
                    booksByPath[book.filePath] = book.copy(id = bookId)
                }
            }

            // One pass also repairs covers of books outside the traversed roots.
            // Trash participates in reconciliation, but is never re-imported or enriched.
            val metadataEdits = scanPrefs.edit()
            for (book in booksByPath.values) {
                if (!book.isTrash) enrichMissingMetadata(book, metadataEdits)
            }
            metadataEdits.apply()

            // Sync ReadEra collections and reading states if ReadEra folder is present
            if (readEraDir.exists() && readEraDir.isDirectory) {
                syncReadEraCollectionsAndState(readEraDir)
            }

            // Keep the automatic categories separate from user-created collections.
            classifyAllBooks()

            try {
                val syncManager = org.readera.openreadera.sync.GoogleDriveSyncManager(context)
                if (syncManager.isDriveConnected() && syncManager.isAutoSyncEnabled) {
                    syncManager.triggerImmediateBackgroundSync()
                }
            } catch (_: Exception) {}
            lastScanTimestamp = SystemClock.elapsedRealtime()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during storage scan", e)
        } finally {
            _isScanning.value = false
            scanMutex.unlock()
        }
    }

    private fun reconcileMissingBooks(currentBooks: List<Book>) {
        try {
            for (b in currentBooks) {
                val f = File(b.filePath)
                if (!f.exists()) {
                    Log.w(TAG, "Keeping unavailable book in DB: ${b.title} (${b.filePath})")
                }
            }

            // Clean up orphan cover files in internal files/covers
            val coversDir = File(context.filesDir, "covers")
            if (coversDir.exists() && coversDir.isDirectory) {
                val validCoverNames = currentBooks.mapNotNull { it.coverPath?.let { p -> File(p).name } }.toSet()
                coversDir.listFiles()?.forEach { cf ->
                    if (cf.isFile && !validCoverNames.contains(cf.name)) {
                        cf.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reconciling unavailable books and covers", e)
        }
    }

    private suspend fun syncReadEraCollectionsAndState(readEraDir: File) {
        try {
            Log.i(TAG, "Starting syncReadEraCollectionsAndState...")

            // 1. Locate ReadEra backup file from Backups directory or readEraDir
            val backupsDir = File(readEraDir.parentFile, "Backups")
            val candidateFiles = mutableListOf<File>()
            if (backupsDir.exists() && backupsDir.isDirectory) {
                backupsDir.listFiles()?.filter { it.extension.equals("bak", ignoreCase = true) }?.let { candidateFiles.addAll(it) }
            }
            readEraDir.listFiles()?.filter { it.extension.equals("bak", ignoreCase = true) }?.let { candidateFiles.addAll(it) }

            val backupFile = candidateFiles.find { it.name.contains("libros_13", ignoreCase = true) }
                ?: candidateFiles.find { it.name.contains("limpia", ignoreCase = true) }
                ?: candidateFiles.maxByOrNull { it.lastModified() }

            Log.i(TAG, "ReadEra backup file resolved to: ${backupFile?.absolutePath}")

            // 2. Restore progress, bookmarks, and status from ReadEra backup ONCE on initial migration
            val scanPrefs = context.getSharedPreferences("storage_scanner_prefs", Context.MODE_PRIVATE)
            val backupImported = scanPrefs.getBoolean("readera_backup_imported_v1", false)

            if (!backupImported && backupFile != null && backupFile.exists() && backupFile.canRead()) {
                try {
                    ZipFile(backupFile).use { zip ->
                        val entry = zip.getEntry("library.json")
                        if (entry != null) {
                            zip.getInputStream(entry).bufferedReader().use { reader ->
                                val jsonStr = reader.readText()
                                val rootObj = JSONObject(jsonStr)
                                val docsArr = rootObj.optJSONArray("docs")
                                if (docsArr != null) {
                                    val currentBooks = repository.getAllBooksList()
                                    for (i in 0 until docsArr.length()) {
                                        val docObj = docsArr.getJSONObject(i)
                                        val uri = docObj.optString("uri")
                                        val dataObj = docObj.optJSONObject("data") ?: continue
                                        val docTitle = dataObj.optString("doc_title", "")
                                        val docAuthors = dataObj.optString("doc_authors", "")
                                        val lastReadTime = dataObj.optLong("doc_last_read_time", 0L)
                                        val posStr = dataObj.optString("doc_position", "")
                                        val docFileNameTitle = dataObj.optString("doc_file_name_title", "")

                                        val matchedBook = currentBooks.find { b ->
                                            (uri.isNotBlank() && b.sha1?.let { uri.contains(it, ignoreCase = true) } == true) ||
                                            (docFileNameTitle.isNotBlank() && File(b.filePath).nameWithoutExtension.startsWith(docFileNameTitle.take(15), ignoreCase = true)) ||
                                            (docTitle.isNotBlank() && (b.title.contains(docTitle, ignoreCase = true) || docTitle.contains(b.title, ignoreCase = true)))
                                        }

                                        if (matchedBook != null) {
                                            var updated = matchedBook
                                            if (docTitle.isNotBlank() && (updated.title == "Desconocido" || updated.title.startsWith(File(updated.filePath).nameWithoutExtension))) {
                                                updated = updated.copy(title = docTitle)
                                            }
                                            if (docAuthors.isNotBlank() && (updated.author == "Desconocido" || updated.author == "Autor desconocido")) {
                                                updated = updated.copy(author = docAuthors)
                                            }
                                            // Only restore reading progress if the user hasn't already read the book in OpenReadEra!
                                            if (updated.currentPage == 0 && updated.lastOpened == 0L && lastReadTime > 0L) {
                                                var curPage = updated.currentPage
                                                var totPages = updated.totalPages
                                                var progPct = updated.progressPercent
                                                if (posStr.isNotBlank()) {
                                                    try {
                                                        val posObj = JSONObject(posStr)
                                                        val page = posObj.optInt("page", 0)
                                                        val pagesCount = posObj.optInt("pagesCount", 0)
                                                        val ratio = posObj.optDouble("ratio", -1.0)
                                                        if (page > 0) curPage = page
                                                        if (pagesCount > 0) totPages = pagesCount
                                                        if (ratio > 0) progPct = (ratio * 100).toFloat()
                                                    } catch (_: Exception) {}
                                                }
                                                updated = updated.copy(
                                                    lastOpened = lastReadTime,
                                                    currentPage = curPage,
                                                    totalPages = totPages,
                                                    progressPercent = progPct,
                                                    status = BookStatus.READING
                                                )
                                            }
                                            repository.updateBook(updated)

                                            // Import bookmarks only on initial migration if user had no reading activity yet
                                            val bmarksArr = docObj.optJSONArray("bookmarks")
                                            if (bmarksArr != null && bmarksArr.length() > 0 && updated.currentPage == 0 && updated.lastOpened == 0L) {
                                                repository.deleteAllBookmarksForBook(updated.id)
                                                for (j in 0 until bmarksArr.length()) {
                                                    val bmObj = bmarksArr.getJSONObject(j)
                                                    val page = bmObj.optInt("note_page", 0)
                                                    val note = bmObj.optString("note_body", "Señalador")
                                                    val time = bmObj.optLong("note_insert_time", System.currentTimeMillis())
                                                    if (page > 0) {
                                                        repository.addBookmark(
                                                            Bookmark(
                                                                bookId = updated.id,
                                                                page = page,
                                                                title = note,
                                                                createdAt = time
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    scanPrefs.edit().putBoolean("readera_backup_imported_v1", true).apply()
                    Log.i(TAG, "ReadEra backup successfully imported once and marked completed.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error importing ReadEra backup JSON", e)
                }
            }

            // Legacy seed is additive and one-time: rescans must preserve user collections.
            if (scanPrefs.getBoolean("readera_collections_seeded_v2", false)) return

            // 3. Ensure known titles and authors for graphic novels
            val intermediateBooks = repository.getAllBooksList()
            for (b in intermediateBooks) {
                val fn = File(b.filePath).name.lowercase()
                var modified = false
                var nb = b
                if (fn.contains("pers") && (nb.author == "Desconocido" || nb.author.isBlank())) {
                    nb = nb.copy(title = "Persépolis", author = "Marjane Satrapi")
                    modified = true
                } else if (fn.contains("vendetta") && (nb.author == "Desconocido" || nb.author.isBlank())) {
                    nb = nb.copy(title = "V de Vendetta", author = "Alan Moore")
                    modified = true
                }
                if (modified) repository.updateBook(nb)
            }

            // 4. Ensure all 5 ReadEra collections exist
            val standardCollections = listOf(
                "Ciencia-ficcion",
                "Cómics",
                "Libros",
                "Literatura",
                "Novela-grafica"
            )
            for (name in standardCollections) {
                val existing = repository.getCollectionByName(name)
                if (existing == null) {
                    repository.addCollection(name)
                }
            }

            val currentCollections = repository.getAllCollectionsList()
            fun findCollId(target: String): Long? {
                return currentCollections.find { it.name.equals(target, ignoreCase = true) }?.id
                    ?: currentCollections.find { it.name.contains(target.take(4), ignoreCase = true) }?.id
            }

            val cfId = findCollId("Ciencia-ficcion")
            val comId = findCollId("Cómics")
            val libId = findCollId("Libros")
            val litId = findCollId("Literatura")
            val novId = findCollId("Novela-grafica")

            Log.i(TAG, "Collections: cf=$cfId, com=$comId, lib=$libId, lit=$litId, nov=$novId")

            // 5. Merge legacy assignments without deleting any existing memberships.
            val allBooks = repository.getAllBooksList()
            val crossRefs = mutableListOf<BookCollectionCrossRef>()
            for (book in allBooks) {
                val raw = (File(book.filePath).name + " " + book.title).lowercase()
                val assignedColls = mutableListOf<Long>()

                val isSciFi = raw.contains("1984") ||
                              raw.contains("tres cue") ||
                              raw.contains("tres cuerpos") ||
                              raw.contains("exhala") ||
                              raw.contains("fahrenheit") ||
                              raw.contains("451") ||
                              raw.contains("materia oscura") ||
                              raw.contains("hail mary") ||
                              raw.contains("mundo feliz")

                val isLit = raw.contains("extranjero") ||
                            raw.contains("tiempo perdido") ||
                            raw.contains("ceguera") ||
                            raw.contains("karamazov")

                val isComic = raw.contains("pers") ||
                              raw.contains("vendetta")

                if (isSciFi) {
                    cfId?.let { assignedColls.add(it) }
                    libId?.let { assignedColls.add(it) }
                }
                if (isLit) {
                    litId?.let { assignedColls.add(it) }
                    libId?.let { assignedColls.add(it) }
                }
                if (isComic) {
                    comId?.let { assignedColls.add(it) }
                    novId?.let { assignedColls.add(it) }
                }

                for (cId in assignedColls.distinct()) {
                    crossRefs.add(BookCollectionCrossRef(bookId = book.id, collectionId = cId))
                }
                Log.i(TAG, "Assigned book ${book.id} (${book.title}) to colls: $assignedColls")
            }
            repository.addBookCollections(crossRefs)
            scanPrefs.edit().putBoolean("readera_collections_seeded_v2", true).apply()
            Log.i(TAG, "Merged ${crossRefs.size} legacy collection cross-references.")

            Log.i(TAG, "syncReadEraCollectionsAndState finished successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing ReadEra collections and state", e)
        }
    }

    private fun metadataStamp(book: Book): String {
        val file = File(book.filePath)
        val coverExists = book.coverPath?.let { File(it).isFile } == true
        return "${book.filePath}:${file.length()}:${file.lastModified()}:${book.coverPath.orEmpty()}:$coverExists"
    }

    private suspend fun enrichMissingMetadata(
        book: Book,
        edits: android.content.SharedPreferences.Editor
    ) {
        val file = File(book.filePath)
        if (!file.isFile || !file.canRead()) return
        val needsMetadata = book.author == "Desconocido" || book.author == "Autor desconocido" || book.author.isBlank()
        val needsCover = book.coverPath?.let { !File(it).isFile } ?: true
        if (!needsMetadata && !needsCover) return
        val key = "metadata_attempt_v1_${book.id}"
        if (scanPrefs.getString(key, null) == metadataStamp(book)) return
        val enriched = coverExtractor.extractAndSaveCover(book)
        // A coverless/unsupported document is a completed attempt, not a reason
        // to parse or hash that same unchanged file on every resume.
        edits.putString(key, metadataStamp(enriched))
    }

    suspend fun classifyBook(book: Book): Book = withContext(Dispatchers.IO) {
        val current = repository.getBookByIdSync(book.id) ?: book
        val edits = scanPrefs.edit()
        enrichMissingMetadata(current, edits)
        edits.apply()
        // The extractor only fills placeholders in Room; use the persisted
        // metadata rather than its candidate so user edits drive classification.
        val enriched = repository.getBookByIdSync(book.id) ?: current
        val categoryIds = ensureAutomaticCategoryCollections()
        applyAutomaticCategory(enriched, categoryIds, repository.getCollectionsForBookSync(book.id).map { it.id }.toSet())
        enriched
    }

    private suspend fun classifyAllBooks() {
        val categoryIds = ensureAutomaticCategoryCollections()
        val books = repository.getAllBooksList()
        val memberships = repository.getAllBookCollectionsSync().groupBy { it.bookId }
        categoryMutex.withLock {
            classifiedBooks.keys.retainAll(books.map { it.id }.toSet())
        }
        for (book in books) {
            applyAutomaticCategory(book, categoryIds, memberships[book.id].orEmpty().map { it.collectionId }.toSet())
        }
    }

    private suspend fun ensureAutomaticCategoryCollections(): Map<DocumentCategory, Long> {
        val ids = linkedMapOf<DocumentCategory, Long>()
        for (category in DocumentCategory.entries) {
            if (repository.getCollectionByName(category.collectionName) == null) {
                repository.addCollection(category.collectionName)
            }
            repository.getCollectionByName(category.collectionName)?.let { ids[category] = it.id }
        }
        return ids
    }

    private suspend fun applyAutomaticCategory(
        book: Book,
        categoryIds: Map<DocumentCategory, Long>,
        memberships: Set<Long>
    ) = categoryMutex.withLock {
        if (book.id == 0L) return@withLock
        val file = File(book.filePath)
        val inputs = CategoryInputs(
            book.filePath, file.length(), file.lastModified(), book.title,
            book.genre, book.series, book.format,
            DocumentCategoryClassifier.manualCategory(context, book)
        )
        val previous = classifiedBooks[book.id]
        val category = if (previous?.first == inputs) {
            previous.second
        } else {
            categoryClassifier.classify(book).category
        }
        categoryIds[category]?.let { collectionId ->
            if (collectionId !in memberships || memberships.count { it in categoryIds.values } != 1) {
                repository.setAutomaticCategory(book.id, collectionId, categoryIds.values.toList())
            }
            if (previous?.first != inputs) classifiedBooks[book.id] = inputs to category
        }
    }

    suspend fun scanSingleFile(file: File): Book? = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead()) return@withContext null
        val ext = file.extension.lowercase()
        if (!SUPPORTED_EXTENSIONS.contains(ext)) return@withContext null

        val existing = repository.getBookByPath(file.absolutePath)
        val book = existing ?: indexedBook(file)

        val bookId = if (existing == null) {
            repository.insertBook(book)
        } else {
            existing.id
        }
        val savedBook = repository.getBookByIdSync(bookId) ?: book.copy(id = bookId)
        classifyBook(savedBook)
    }

    private fun indexedBook(file: File) = Book(
        title = file.nameWithoutExtension.replace('_', ' ').trim(),
        author = "Desconocido",
        filePath = file.absolutePath,
        format = DocumentFormat.fromExtension(file.extension.lowercase()).displayName,
        fileSize = file.length(),
        status = BookStatus.UNREAD,
        dateAdded = file.lastModified()
    )

    private fun scanDirectory(dir: File, discovered: MutableList<File>) {
        if (!dir.exists() || !dir.canRead() || dir.name.startsWith(".")) return

        // Skip android system cache and app data directories to prevent slow scans
        val name = dir.name.lowercase()
        if (name == "android" || name == ".thumbnails" || name == "cache") return

        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                scanDirectory(file, discovered)
            } else {
                val ext = file.extension.lowercase()
                if (SUPPORTED_EXTENSIONS.contains(ext)) {
                    discovered.add(file)
                    _scanProgress.value = discovered.size
                }
            }
        }
    }
}
