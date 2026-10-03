package org.readera.openreadera.data.scanner

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
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

    private val scanMutex = Mutex()
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(0)
    val scanProgress: StateFlow<Int> = _scanProgress.asStateFlow()

    private var lastScanTimestamp: Long = 0L

    suspend fun scanStorage() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastScanTimestamp < 30_000L && repository.getAllBooksList().isNotEmpty()) {
            Log.d(TAG, "Storage scan throttled (<30s since last scan)")
            return@withContext
        }
        if (!scanMutex.tryLock()) {
            Log.i(TAG, "Storage scan is already in progress, skipping concurrent call")
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
            purgeMissingBooks()

            val discoveredBooks = mutableListOf<Book>()

            for (root in rootDirs) {
                scanDirectory(root, discoveredBooks)
            }

            Log.i(TAG, "Discovered ${discoveredBooks.size} documents during storage scan")

            for (book in discoveredBooks) {
                val existing = repository.getBookByPath(book.filePath)
                val bookId = if (existing == null) {
                    repository.insertBook(book)
                } else {
                    existing.id
                }
                val currentBook = existing ?: book.copy(id = bookId)
                if (existing == null || currentBook.coverPath.isNullOrBlank() ||
                    !File(currentBook.coverPath).isFile
                ) {
                    coverExtractor.extractAndSaveCover(currentBook)
                }
            }

            // Sync ReadEra collections and reading states if ReadEra folder is present
            if (readEraDir.exists() && readEraDir.isDirectory) {
                syncReadEraCollectionsAndState(readEraDir)
            }

            // Also check any already stored books
            extractMissingCovers()

            // Keep the automatic categories separate from user-created collections.
            classifyAllBooks()

            try {
                val syncManager = org.readera.openreadera.sync.GoogleDriveSyncManager(context)
                if (syncManager.isDriveConnected() && syncManager.isAutoSyncEnabled) {
                    syncManager.triggerImmediateBackgroundSync()
                }
            } catch (_: Exception) {}
            lastScanTimestamp = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Error during storage scan", e)
        } finally {
            _isScanning.value = false
            scanMutex.unlock()
        }
    }

    private suspend fun purgeMissingBooks() {
        try {
            val currentBooks = repository.getAllBooksList()
            for (b in currentBooks) {
                val f = File(b.filePath)
                if (!f.exists()) {
                    Log.w(TAG, "Keeping unavailable book in DB: ${b.title} (${b.filePath})")
                }
            }

            // Clean up orphan cover files in internal files/covers
            val coversDir = File(context.filesDir, "covers")
            if (coversDir.exists() && coversDir.isDirectory) {
                val validBooks = repository.getAllBooksList()
                val validCoverNames = validBooks.mapNotNull { it.coverPath?.let { p -> File(p).name } }.toSet()
                coversDir.listFiles()?.forEach { cf ->
                    if (cf.isFile && !validCoverNames.contains(cf.name)) {
                        cf.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error purging missing books", e)
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

    suspend fun extractMissingCovers() = withContext(Dispatchers.IO) {
        try {
            val allBooks = repository.getAllBooksList()
            for (book in allBooks) {
                if (book.coverPath == null || !File(book.coverPath).exists() || book.author == "Desconocido") {
                    coverExtractor.extractAndSaveCover(book)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating missing covers", e)
        }
    }

    suspend fun classifyBook(book: Book): Book = withContext(Dispatchers.IO) {
        val categoryIds = ensureAutomaticCategoryCollections()
        val current = repository.getBookByIdSync(book.id) ?: book
        val enriched = if (current.coverPath == null || !File(current.coverPath).exists()) {
            coverExtractor.extractAndSaveCover(current)
        } else {
            current
        }
        applyAutomaticCategory(enriched, categoryIds)
        repository.getBookByIdSync(enriched.id) ?: enriched
    }

    private suspend fun classifyAllBooks() {
        val categoryIds = ensureAutomaticCategoryCollections()
        for (book in repository.getAllBooksList()) {
            val current = repository.getBookByIdSync(book.id) ?: book
            applyAutomaticCategory(current, categoryIds)
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

    private suspend fun applyAutomaticCategory(book: Book, categoryIds: Map<DocumentCategory, Long>) {
        if (book.id == 0L) return
        val decision = categoryClassifier.classify(book)
        categoryIds[decision.category]?.let { collectionId ->
            repository.setAutomaticCategory(book.id, collectionId, categoryIds.values.toList())
        }
        Log.i(TAG, "Automatic category for '${book.title}': ${decision.category.displayName} (${decision.confidence})")
    }

    suspend fun scanSingleFile(file: File): Book? = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead()) return@withContext null
        val ext = file.extension.lowercase()
        if (!SUPPORTED_EXTENSIONS.contains(ext)) return@withContext null

        val format = DocumentFormat.fromExtension(ext)
        val title = file.nameWithoutExtension.replace('_', ' ').trim()
        val existing = repository.getBookByPath(file.absolutePath)
        val book = existing ?: Book(
            title = title,
            author = "Desconocido",
            filePath = file.absolutePath,
            format = format.displayName,
            fileSize = file.length(),
            status = BookStatus.UNREAD,
            dateAdded = file.lastModified()
        )

        val bookId = if (existing == null) {
            repository.insertBook(book)
        } else {
            existing.id
        }
        val savedBook = repository.getBookByIdSync(bookId) ?: book.copy(id = bookId)
        classifyBook(savedBook)
    }

    private fun scanDirectory(dir: File, discovered: MutableList<Book>) {
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
                    val format = DocumentFormat.fromExtension(ext)
                    val title = file.nameWithoutExtension.replace('_', ' ').trim()
                    val book = Book(
                        title = title,
                        author = "Desconocido",
                        filePath = file.absolutePath,
                        format = format.displayName,
                        fileSize = file.length(),
                        status = BookStatus.UNREAD,
                        dateAdded = file.lastModified()
                    )
                    discovered.add(book)
                    _scanProgress.value = discovered.size
                }
            }
        }
    }
}
