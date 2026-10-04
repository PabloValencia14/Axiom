package org.readera.openreadera.sync

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.readera.openreadera.data.db.AppDatabase
import org.readera.openreadera.data.repository.DrawingStrokeSyncGate
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.BookStatus
import org.readera.openreadera.data.model.BookSyncIdentity
import org.readera.openreadera.data.model.Bookmark
import org.readera.openreadera.data.model.Collection
import org.readera.openreadera.data.model.DrawingStroke
import org.readera.openreadera.data.model.Quote
import org.readera.openreadera.data.model.SearchQuery
import org.readera.openreadera.data.model.StrokeToolType
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.readera.openreadera.core.io.TransferLimits
import org.readera.openreadera.core.io.readBoundedText
import org.readera.openreadera.data.importer.DocumentLocationPolicy

data class DriveSyncStats(
    val booksSynced: Int,
    val bookmarksSynced: Int,
    val quotesSynced: Int,
    val lastSyncTimestamp: Long,
    val restoredBooks: Int = 0,
    val mergedDrawings: Int = 0,
    val mergedCollections: Int = 0
)

internal data class MergeStats(
    val restoredBooks: Int = 0,
    val bookmarksAdded: Int = 0,
    val quotesAdded: Int = 0,
    val drawingsAdded: Int = 0,
    val collectionsAdded: Int = 0,
    val drawingTombstones: Set<String> = emptySet()
)

class GoogleDriveSyncManager(
    private val context: Context,
    private val database: AppDatabase? = null
) {

    companion object {
        // Manual sync and the periodic worker may be created by different
        // callers in the same process. Serialize them to avoid last-writer
        // wins races while merging the shared snapshot.
        private val processSyncMutex = Mutex()
        private const val STATE_FILE_NAME = "openreadera_sync_state.json"
    }

    private val db = database ?: AppDatabase.getInstance(context)
    val authManager = GoogleAuthManager(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("drive_sync_data", Context.MODE_PRIVATE)
    private var activeSession: DriveAccountGuard.Session? = null

    private fun execute(request: Request): Response =
        checkNotNull(activeSession) { "No active Drive identity" }.execute(client, request)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    var isAutoSyncEnabled: Boolean
        get() = prefs.getBoolean("auto_sync_enabled", true)
        set(v) = prefs.edit().putBoolean("auto_sync_enabled", v).apply()


    fun getLastSyncTime(): Long = prefs.getLong("last_sync_timestamp", 0L)

    // Folder selection
    fun getTargetFolderName(): String = prefs.getString("target_folder_name", "OpenReadEra") ?: "OpenReadEra"

    fun setTargetFolderName(name: String) = DriveAccountGuard.updateIdentity {
        val clean = name.trim().ifBlank { "OpenReadEra" }
        prefs.edit().putString("target_folder_name", clean)
            .remove("target_folder_id").remove("target_folder_account").apply()
    }

    fun getTargetFolderId(): String? = DriveAccountGuard.locked {
        val account = authManager.getSelectedProfile()?.email ?: return@locked null
        prefs.getString("target_folder_id", null).takeIf {
            prefs.getString("target_folder_account", null) == account
        }
    }

    fun setTargetFolderId(id: String) {
        val session = checkNotNull(activeSession) { "No active Drive identity" }
        session.commit {
            prefs.edit().putString("target_folder_id", id)
                .putString("target_folder_account", authManager.getSelectedProfile()?.email).apply()
        }
    }

    fun getAvailableFolderPresets(): List<String> = listOf(
        "OpenReadEra",
        "Mis Libros",
        "Biblioteca",
        "Lecturas Tablet",
        "Documentos PDF"
    )

    // Storage Access Framework (SAF) - Direct Google Drive Folder Connection
    fun getSafFolderUri(): Uri? {
        val str = prefs.getString("saf_folder_uri", null) ?: return null
        return try {
            Uri.parse(str)
        } catch (_: Exception) {
            null
        }
    }

    fun getSafFolderName(): String? = prefs.getString("saf_folder_name", null)

    fun saveSafFolder(uri: Uri, displayName: String) = DriveAccountGuard.updateIdentity {
        val cleanName = displayName.trim().ifBlank { "Google Drive" }
        prefs.edit().putString("saf_folder_uri", uri.toString()).putString("saf_folder_name", cleanName)
            .putString("target_folder_name", cleanName)
            .remove("target_folder_id").remove("target_folder_account").apply()
    }

    fun disconnectSafFolder() {
        val currentUri = DriveAccountGuard.updateIdentity {
            val uri = getSafFolderUri()
            prefs.edit().remove("saf_folder_uri").remove("saf_folder_name").apply()
            uri
        }
        if (currentUri != null) {
            try {
                val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(currentUri, flags)
            } catch (_: Exception) {}
        }
    }

    fun isDriveConnected(): Boolean {
        return getSafFolderUri() != null || (authManager.getUserProfile()?.accessToken?.startsWith("ya29") == true)
    }


    suspend fun performSync(): Result<DriveSyncStats> = processSyncMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                authManager.getUserProfile()
                val (session, profile) = DriveAccountGuard.locked {
                    val selected = authManager.getSelectedProfile()
                    DriveAccountGuard.capture() to selected
                }
                val job = checkNotNull(currentCoroutineContext()[Job])
                session.attach(job)
                activeSession = session
                var token = profile?.accessToken
                if (profile != null && (token.isNullOrBlank() || !token.startsWith("ya29"))) {
                    authManager.fetchOAuthToken(profile.email).getOrNull()?.let { token = it }
                }

                val remoteState = readRemoteState(token)
                val remoteRoot = remoteState?.takeIf(String::isNotBlank)
                    ?.let(DriveSyncSnapshotCodec::migrateToCurrent)
                val pipeline = DriveAttachmentPipeline(
                    context.filesDir,
                    DriveRemoteBlobStore(
                        context = context,
                        safTreeUri = getSafFolderUri(),
                        oauthToken = token,
                        targetFolderName = getTargetFolderName(),
                        client = client,
                        session = session,
                        resolveFolderId = { folderName ->
                            val activeToken = token?.takeIf { it.startsWith("ya29") }
                                ?: error("No hay una sesión OAuth de Drive activa")
                            getOrCreateDriveFolder(activeToken, folderName)
                        }
                    ),
                    session
                )
                val transfer = DriveSyncStateTransfer(db, pipeline, DocumentLocationPolicy.forContext(context))

                val (mergeStats, synchronizedStrokes) = DrawingStrokeSyncGate.mutex.withLock {
                    val localDrawingTombstones = collectLocalDrawingTombstones()
                    val hydratedRemote = remoteRoot?.let { transfer.restoreRemoteAttachments(it) }
                    val stats = if (hydratedRemote == null) {
                        MergeStats(drawingTombstones = localDrawingTombstones)
                    } else {
                        session.transaction {
                            db.withTransaction {
                                session.checkCurrent()
                                mergeRemoteState(hydratedRemote.toString(), localDrawingTombstones).also {
                                    currentCoroutineContext().ensureActive()
                                    session.checkCurrent()
                                }
                            }
                        }
                    }
                    stats to db.drawingStrokeDao().getAllStrokesList()
                }

                session.checkCurrent()
                val safeSettings = session.commit {
                    remoteRoot?.optJSONObject("readerPreferences")?.let {
                        DriveSyncSafeState.applyReaderPreferences(context, it)
                    }
                    val readingStats = DriveSyncSafeState.mergeAndApplyReadingStats(context, remoteRoot?.optJSONObject("readingStats"))
                    DriveSyncSafeState.mergeAndApplySyncPreferences(context, remoteRoot?.optJSONObject("syncPreferences"))
                    val opdsCatalogs = DriveSyncSafeState.mergeAndApplyOpdsCatalogs(context, remoteRoot?.optJSONArray("opdsCatalogs"))
                    val zLibraryPreferences = DriveSyncSafeState.mergeAndApplyZLibraryPreferences(context, remoteRoot?.optJSONObject("zLibraryPreferences"))
                    Triple(readingStats, opdsCatalogs, zLibraryPreferences)
                }
                val (readingStats, opdsCatalogs, zLibraryPreferences) = safeSettings
                val manualCategoryOverrides = DriveSyncSafeState.mergeAndApplyManualCategoryOverrides(
                    context, db, remoteRoot?.optJSONArray("manualCategoryOverrides"), session
                )

                val books = db.bookDao().getAllBooksIncludingTrashList()
                val attachmentsByBookId = transfer.uploadLocalAttachments(books, remoteRoot)
                val now = System.currentTimeMillis()
                val jsonString = DriveSyncSnapshotCodec.encode(
                    DriveSnapshotContents(
                        books = books,
                        bookmarks = db.bookmarkDao().getAllBookmarksList(),
                        quotes = db.quoteDao().getAllQuotesList(),
                        drawingStrokes = synchronizedStrokes,
                        collections = db.collectionDao().getAllCollectionsList(),
                        bookCollections = db.collectionDao().getAllBookCollectionsSync(),
                        searchQueries = db.searchHistoryDao().getAllQueriesList(),
                        attachmentsByBookId = attachmentsByBookId,
                        drawingTombstones = mergeStats.drawingTombstones,
                        readerPreferences = DriveSyncSafeState.readerPreferences(context),
                        readingStats = readingStats,
                        syncPreferences = DriveSyncSafeState.syncPreferences(context),
                        opdsCatalogs = opdsCatalogs,
                        zLibraryPreferences = zLibraryPreferences,
                        manualCategoryOverrides = manualCategoryOverrides,
                        timestamp = now
                    )
                )

                // Publish JSON only after all referenced blobs have been written and verified.
                writeRemoteState(token, jsonString)
                session.commit {
                    File(context.filesDir, STATE_FILE_NAME).writeText(jsonString)
                    prefs.edit().putLong("last_sync_timestamp", now).apply()
                }

                Result.success(
                    DriveSyncStats(
                        booksSynced = books.size,
                        bookmarksSynced = mergeStats.bookmarksAdded,
                        quotesSynced = mergeStats.quotesAdded,
                        lastSyncTimestamp = now,
                        restoredBooks = mergeStats.restoredBooks,
                        mergedDrawings = mergeStats.drawingsAdded,
                        mergedCollections = mergeStats.collectionsAdded
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                currentCoroutineContext()[Job]?.let { activeSession?.detach(it) }
                activeSession = null
            }
        }
    }

    private fun readRemoteState(token: String?): String? {
        val safUri = getSafFolderUri()
        if (safUri != null) {
            val folder = DocumentFile.fromTreeUri(context, safUri)
                ?: throw IllegalStateException("La carpeta de sincronización ya no está disponible")
            val stateFile = folder.findFile(STATE_FILE_NAME) ?: return null
            val input = context.contentResolver.openInputStream(stateFile.uri)
                ?: throw IllegalStateException("No se pudo leer el estado de sincronización")
            return input.readBoundedText(TransferLimits.SNAPSHOT, stateFile.length())
        }

        if (token.isNullOrBlank() || !token.startsWith("ya29")) return null
        val fileId = findRemoteStateFile(token) ?: return null
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media".toHttpUrl()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Error leyendo estado de Drive (${response.code})")
            }
            return response.body?.let { it.byteStream().readBoundedText(TransferLimits.SNAPSHOT, it.contentLength()) }
                ?: throw IllegalStateException("Drive devolvió un estado vacío")
        }
    }

    private suspend fun writeRemoteState(token: String?, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= TransferLimits.SNAPSHOT) { "Snapshot too large" }
        val safUri = getSafFolderUri()
        if (safUri != null) {
            val folder = DocumentFile.fromTreeUri(context, safUri)
                ?: throw IllegalStateException("La carpeta de sincronización ya no está disponible")
            if (!folder.canWrite()) {
                throw IllegalStateException("La carpeta de sincronización no permite escribir")
            }

            // Write the complete document first. A process death must not
            // leave the shared JSON truncated halfway through a sync.
            val tempName = "$STATE_FILE_NAME.tmp-${System.nanoTime()}"
            val temp = checkNotNull(activeSession).commit {
                folder.createFile("application/json", tempName)
                    ?: throw IllegalStateException("No se pudo crear el temporal de sincronización")
            }
            try {
                val output = context.contentResolver.openOutputStream(temp.uri)
                    ?: throw IllegalStateException("No se pudo abrir el temporal de sincronización")
                val session = checkNotNull(activeSession)
                val job = currentCoroutineContext()[Job]
                output.use { out -> session.withOutput(out, job) { it.write(bytes) } }

                checkNotNull(activeSession).commit {
                    val previous = folder.findFile(STATE_FILE_NAME)
                    if (previous != null && !previous.delete()) {
                        throw IllegalStateException("No se pudo sustituir el estado anterior de sincronización")
                    }
                    if (!temp.renameTo(STATE_FILE_NAME)) {
                        throw IllegalStateException("No se pudo finalizar el estado de sincronización")
                    }
                }
            } catch (error: Exception) {
                temp.delete()
                throw error
            }
            return
        }

        if (!token.isNullOrBlank() && token.startsWith("ya29")) {
            uploadAppDataToDrive(token, bytes)
            return
        }
        throw IllegalStateException("No hay un destino de sincronización conectado")
    }

    private fun findRemoteStateFile(token: String): String? {
        val query = "'appDataFolder' in parents and name='$STATE_FILE_NAME' and trashed=false"
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("spaces", "appData")
            .addQueryParameter("orderBy", "modifiedTime desc")
            .addQueryParameter("pageSize", "1")
            .addQueryParameter("fields", "files(id,name,modifiedTime)")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string().orEmpty()
                throw IllegalStateException("Error consultando estado de Drive (${response.code}): $detail")
            }
            val files = JSONObject(response.body?.string().orEmpty()).optJSONArray("files")
            return files?.takeIf { it.length() > 0 }?.getJSONObject(0)?.optString("id")
                ?.takeIf { it.isNotBlank() }
        }
    }

    internal suspend fun mergeRemoteState(
        jsonString: String,
        localDrawingTombstones: Set<String> = emptySet()
    ): MergeStats {
        val parsed = JSONObject(jsonString)
        val root = if (parsed.optInt("version", 1) == DriveSyncSnapshotCodec.CURRENT_VERSION) {
            parsed
        } else {
            DriveSyncSnapshotCodec.migrateToCurrent(jsonString)
        }
        val drawingTombstones = parseDrawingTombstones(root.optJSONArray("drawingStrokeTombstones"))
        drawingTombstones.addAll(localDrawingTombstones.filter(::isDrawingTombstoneKey))

        val localBooks = db.bookDao().getAllBooksIncludingTrashList()
        val booksByKey = localBooks.groupBy(::bookKey).mapValues { it.value.first() }.toMutableMap()
        val booksBySyncId = localBooks.associateBy { it.syncId }.toMutableMap()
        val remoteToLocalBook = mutableMapOf<Long, Long>()
        val remoteBookSyncToLocal = mutableMapOf<String, Long>()
        var restoredBooks = 0

        root.optJSONArray("books")?.let { books ->
            for (index in 0 until books.length()) {
                val remote = books.optJSONObject(index) ?: continue
                val syncId = DriveSyncSnapshotCodec.bookSyncId(remote)
                val key = remoteBookKey(remote)
                val remotePath = remote.optString("filePath").takeIf { it.isNotBlank() }
                    ?.let(::File)?.takeIf(File::isFile)
                val remoteCover = remote.optString("coverPath").takeIf { it.isNotBlank() }
                    ?.let(::File)?.takeIf(File::isFile)
                val local = booksBySyncId[syncId] ?: booksByKey[key]
                if (local == null && remotePath == null) continue

                val resolved = if (local == null) {
                    val restored = bookFromRemote(remote).copy(
                        syncId = syncId,
                        filePath = requireNotNull(remotePath).absolutePath,
                        coverPath = remoteCover?.absolutePath
                    )
                    val id = db.bookDao().insert(restored)
                    val saved = db.bookDao().getBookByIdSync(id) ?: restored.copy(id = id)
                    booksByKey[bookKey(saved)] = saved
                    booksBySyncId[saved.syncId] = saved
                    restoredBooks++
                    saved
                } else {
                    val oldKey = bookKey(local)
                    val metadata = mergeBook(local, remote)
                    val merged = metadata.copy(
                        syncId = syncId,
                        filePath = remotePath?.absolutePath ?: local.filePath,
                        coverPath = remoteCover?.absolutePath ?: local.coverPath
                    )
                    if (merged != local) db.bookDao().update(merged)
                    if (local.syncId != syncId) booksBySyncId.remove(local.syncId)
                    booksBySyncId[syncId] = merged
                    booksByKey.remove(oldKey)
                    booksByKey[bookKey(merged)] = merged
                    merged
                }

                val remoteId = remote.optLong("id", -1L)
                if (remoteId > 0L) remoteToLocalBook[remoteId] = resolved.id
                remoteBookSyncToLocal[syncId] = resolved.id
            }
        }

        fun localBookId(remote: JSONObject): Long? =
            remoteBookSyncToLocal[remote.optString("bookSyncId")]
                ?: remoteToLocalBook[remote.optLong("bookId", -1L)]

        var bookmarksAdded = 0
        val bookmarks = db.bookmarkDao().getAllBookmarksList().toMutableList()
        root.optJSONArray("bookmarks")?.let { array ->
            for (index in 0 until array.length()) {
                val remote = array.optJSONObject(index) ?: continue
                val bookId = localBookId(remote) ?: continue
                val candidate = Bookmark(
                    bookId = bookId,
                    page = remote.optInt("page", 0),
                    title = remote.optString("title", "Señalador"),
                    snippet = remote.optString("snippet", ""),
                    createdAt = remote.optLong("createdAt", System.currentTimeMillis())
                )
                if (bookmarks.none {
                        it.bookId == candidate.bookId && it.page == candidate.page &&
                            it.title == candidate.title && it.snippet == candidate.snippet
                    }) {
                    val id = db.bookmarkDao().insert(candidate)
                    bookmarks.add(candidate.copy(id = id))
                    bookmarksAdded++
                }
            }
        }

        var quotesAdded = 0
        val quotes = db.quoteDao().getAllQuotesList().toMutableList()
        root.optJSONArray("quotes")?.let { array ->
            for (index in 0 until array.length()) {
                val remote = array.optJSONObject(index) ?: continue
                val bookId = localBookId(remote) ?: continue
                val candidate = Quote(
                    bookId = bookId,
                    page = remote.optInt("page", 0),
                    text = remote.optString("text", ""),
                    note = remote.optString("note", ""),
                    colorHex = remote.optString("colorHex", "#FFE082"),
                    createdAt = remote.optLong("createdAt", System.currentTimeMillis())
                )
                if (quotes.none {
                        it.bookId == candidate.bookId && it.page == candidate.page &&
                            it.text == candidate.text && it.note == candidate.note
                    }) {
                    val id = db.quoteDao().insert(candidate)
                    quotes.add(candidate.copy(id = id))
                    quotesAdded++
                }
            }
        }

        var drawingsAdded = 0
        val localBooksById = db.bookDao().getAllBooksIncludingTrashList().associateBy { it.id }
        val localBookKeys = localBooksById.mapValues { (_, book) -> bookKey(book) }
        val strokes = db.drawingStrokeDao().getAllStrokesList().toMutableList()
        val deletedStrokes = strokes.filter { stroke ->
            localBookKeys[stroke.bookId]?.let { drawingStrokeKey(it, stroke) in drawingTombstones } == true
        }
        deletedStrokes.forEach { db.drawingStrokeDao().delete(it) }
        strokes.removeAll(deletedStrokes.toSet())
        root.optJSONArray("drawingStrokes")?.let { array ->
            for (index in 0 until array.length()) {
                val remote = array.optJSONObject(index) ?: continue
                val bookId = localBookId(remote) ?: continue
                val bookKey = localBookKeys[bookId] ?: continue
                val candidate = drawingStrokeFromJson(remote, bookId)
                if (drawingStrokeKey(bookKey, candidate) in drawingTombstones) continue
                if (strokes.none {
                        it.bookId == candidate.bookId && it.page == candidate.page &&
                            it.toolType == candidate.toolType && it.pointsJson == candidate.pointsJson
                    }) {
                    val id = db.drawingStrokeDao().insert(candidate)
                    strokes.add(candidate.copy(id = id))
                    drawingsAdded++
                }
            }
        }

        val localCollections = db.collectionDao().getAllCollectionsList().toMutableList()
        val remoteToLocalCollection = mutableMapOf<Long, Long>()
        val remoteCollectionSyncToLocal = mutableMapOf<String, Long>()
        var collectionsAdded = 0
        root.optJSONArray("collections")?.let { array ->
            for (index in 0 until array.length()) {
                val remote = array.optJSONObject(index) ?: continue
                val name = remote.optString("name").trim()
                val remoteId = remote.optLong("id", -1L)
                val syncId = remote.optString("syncId")
                if (name.isBlank()) continue
                val existing = localCollections.firstOrNull { it.name.equals(name, ignoreCase = true) }
                val local = existing ?: run {
                    val id = db.collectionDao().insert(
                        Collection(name = name, createdAt = remote.optLong("createdAt", System.currentTimeMillis()))
                    )
                    db.collectionDao().getCollectionByName(name) ?: Collection(
                        id = id, name = name, createdAt = remote.optLong("createdAt", System.currentTimeMillis())
                    )
                }
                if (existing == null) collectionsAdded++
                if (existing == null) localCollections.add(local)
                if (remoteId > 0L) remoteToLocalCollection[remoteId] = local.id
                if (syncId.isNotBlank()) remoteCollectionSyncToLocal[syncId] = local.id
            }
        }

        root.optJSONArray("bookCollections")?.let { array ->
            for (index in 0 until array.length()) {
                val remote = array.optJSONObject(index) ?: continue
                val bookId = localBookId(remote) ?: continue
                val collectionId = remoteCollectionSyncToLocal[remote.optString("collectionSyncId")]
                    ?: remoteToLocalCollection[remote.optLong("collectionId", -1L)]
                    ?: continue
                db.collectionDao().addBookToCollection(
                    org.readera.openreadera.data.model.BookCollectionCrossRef(bookId, collectionId)
                )
            }
        }

        val existingQueries = db.searchHistoryDao().getAllQueriesList().associateBy { it.query }
        root.optJSONArray("searchHistory")?.let { array ->
            for (index in 0 until array.length()) {
                val remote = array.optJSONObject(index) ?: continue
                val query = remote.optString("query").takeIf(String::isNotBlank) ?: continue
                val timestamp = remote.optLong("timestamp", 0L)
                if (timestamp > (existingQueries[query]?.timestamp ?: Long.MIN_VALUE)) {
                    db.searchHistoryDao().insertQuery(SearchQuery(query = query, timestamp = timestamp))
                }
            }
        }

        return MergeStats(
            restoredBooks = restoredBooks,
            bookmarksAdded = bookmarksAdded,
            quotesAdded = quotesAdded,
            drawingsAdded = drawingsAdded,
            collectionsAdded = collectionsAdded,
            drawingTombstones = drawingTombstones
        )
    }

    private fun bookKey(book: Book): String = BookSyncIdentity.legacy(book)

    private fun remoteBookKey(book: JSONObject): String =
        BookSyncIdentity.legacy(book.optString("title"), book.optString("author"), book.optString("sha1"))

    private suspend fun collectLocalDrawingTombstones(): Set<String> {
        val cacheFile = File(context.filesDir, "openreadera_sync_state.json")
        if (!cacheFile.isFile) return emptySet()

        val previousState = JSONObject(cacheFile.inputStream().readBoundedText(TransferLimits.SNAPSHOT, cacheFile.length()))
        val tombstones = parseDrawingTombstones(previousState.optJSONArray("drawingStrokeTombstones"))
        val previousBookKeys = mutableMapOf<Long, String>()
        previousState.optJSONArray("books")?.let { books ->
            for (index in 0 until books.length()) {
                val book = books.optJSONObject(index) ?: continue
                val id = book.optLong("id", -1L)
                if (id > 0L) previousBookKeys[id] = remoteBookKey(book)
            }
        }

        val localBookKeys = db.bookDao().getAllBooksIncludingTrashList().associate { it.id to bookKey(it) }
        val localStrokeKeys = db.drawingStrokeDao().getAllStrokesList().mapNotNull { stroke ->
            localBookKeys[stroke.bookId]?.let { drawingStrokeKey(it, stroke) }
        }.toSet()
        previousState.optJSONArray("drawingStrokes")?.let { strokes ->
            for (index in 0 until strokes.length()) {
                val remote = strokes.optJSONObject(index) ?: continue
                val stableBookKey = previousBookKeys[remote.optLong("bookId", -1L)] ?: continue
                val stroke = drawingStrokeFromJson(remote, remote.optLong("bookId", -1L))
                val key = drawingStrokeKey(stableBookKey, stroke)
                if (key !in localStrokeKeys) tombstones.add(key)
            }
        }
        return tombstones
    }

    private fun drawingStrokeFromJson(remote: JSONObject, bookId: Long): DrawingStroke {
        val tool = runCatching {
            StrokeToolType.valueOf(remote.optString("toolType", StrokeToolType.PEN.name))
        }.getOrDefault(StrokeToolType.PEN)
        return DrawingStroke(
            bookId = bookId,
            page = remote.optInt("page", 0),
            toolType = tool,
            colorHex = remote.optString("colorHex", "#FFEB3B"),
            strokeWidth = remote.optDouble("strokeWidth", 4.0).toFloat(),
            pointsJson = remote.optString("pointsJson", ""),
            createdAt = remote.optLong("createdAt", System.currentTimeMillis())
        )
    }

    private fun parseDrawingTombstones(values: JSONArray?): MutableSet<String> =
        mutableSetOf<String>().apply {
            if (values == null) return@apply
            for (index in 0 until values.length()) {
                val key = values.optString(index)
                if (isDrawingTombstoneKey(key)) add(key)
            }
        }

    private fun isDrawingTombstoneKey(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun drawingStrokeKey(bookIdentity: String, stroke: DrawingStroke): String {
        val identity = listOf(
            bookIdentity,
            stroke.page.toString(),
            stroke.toolType.name,
            stroke.colorHex,
            stroke.strokeWidth.toString(),
            stroke.createdAt.toString(),
            stroke.pointsJson
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }


    private fun bookFromRemote(remote: JSONObject): Book {
        val format = remote.optString("format", "Documento").ifBlank { "Documento" }
        val filePath = remote.optString("filePath").takeIf { it.isNotBlank() && File(it).isFile }
            ?: error("El archivo del libro sincronizado no está disponible")
        return Book(
            syncId = DriveSyncSnapshotCodec.bookSyncId(remote),
            title = remote.optString("title", "Documento sincronizado"),
            author = remote.optString("author", "Autor desconocido"),
            filePath = filePath,
            format = format,
            fileSize = remote.optLong("fileSize", 0L),
            coverPath = remote.optString("coverPath").takeIf { it.isNotBlank() && File(it).isFile },
            currentPage = remote.optInt("currentPage", 0),
            totalPages = remote.optInt("totalPages", 1).coerceAtLeast(1),
            progressPercent = remote.optDouble("progressPercent", 0.0).toFloat(),
            isFavorite = remote.optBoolean("isFavorite", false),
            isToRead = remote.optBoolean("isToRead", false),
            isHaveRead = remote.optBoolean("isHaveRead", false),
            isTrash = remote.optBoolean("isTrash", false),
            status = parseStatus(remote.optString("status")),
            dateAdded = remote.optLong("dateAdded", System.currentTimeMillis()),
            lastOpened = remote.optLong("lastOpened", 0L),
            series = remoteText(remote, "series", null),
            seriesIndex = remote.optInt("seriesIndex", 0)
                .takeIf { remote.has("seriesIndex") && !remote.isNull("seriesIndex") },
            collectionName = remoteText(remote, "collectionName", null),
            sha1 = remoteText(remote, "sha1", null),
            language = remoteText(remote, "language", null),
            genre = remoteText(remote, "genre", null),
            description = remoteText(remote, "description", null),
            annotation = remoteText(remote, "annotation", null),
            review = remoteText(remote, "review", null),
            rating = remote.optDouble("rating", 0.0).toFloat().takeIf { it > 0f },
            reviewDate = remote.optLong("reviewDate", 0L)
                .takeIf { remote.has("reviewDate") && !remote.isNull("reviewDate") },
            reviewTags = remoteText(remote, "reviewTags", null),
            fileCount = remote.optInt("fileCount", 1).coerceAtLeast(1)
        )
    }

    private fun mergeBook(local: Book, remote: JSONObject): Book {
        val remoteLastOpened = remote.optLong("lastOpened", 0L)
        val remoteWins = remoteLastOpened > local.lastOpened ||
            (remoteLastOpened == local.lastOpened && local.lastOpened == 0L)
        if (!remoteWins) return local

        return local.copy(
            title = remote.optString("title", local.title),
            author = remote.optString("author", local.author),
            format = remote.optString("format", local.format),
            fileSize = remote.optLong("fileSize", local.fileSize),
            currentPage = remote.optInt("currentPage", local.currentPage),
            totalPages = remote.optInt("totalPages", local.totalPages).coerceAtLeast(1),
            progressPercent = remote.optDouble("progressPercent", local.progressPercent.toDouble()).toFloat(),
            isFavorite = remote.optBoolean("isFavorite", local.isFavorite),
            isToRead = remote.optBoolean("isToRead", local.isToRead),
            isHaveRead = remote.optBoolean("isHaveRead", local.isHaveRead),
            isTrash = remote.optBoolean("isTrash", local.isTrash),
            status = parseStatus(remote.optString("status"), local.status),
            dateAdded = remote.optLong("dateAdded", local.dateAdded),
            lastOpened = remote.optLong("lastOpened", local.lastOpened),
            series = remoteText(remote, "series", local.series),
            seriesIndex = if (remote.has("seriesIndex") && !remote.isNull("seriesIndex")) {
                remote.optInt("seriesIndex")
            } else local.seriesIndex,
            collectionName = remoteText(remote, "collectionName", local.collectionName),
            sha1 = remoteText(remote, "sha1", local.sha1),
            language = remoteText(remote, "language", local.language),
            genre = remoteText(remote, "genre", local.genre),
            description = remoteText(remote, "description", local.description),
            annotation = remoteText(remote, "annotation", local.annotation),
            review = remoteText(remote, "review", local.review),
            rating = remote.optDouble("rating", (local.rating ?: 0f).toDouble()).toFloat().takeIf { it > 0f },
            reviewDate = if (remote.has("reviewDate") && !remote.isNull("reviewDate")) {
                remote.optLong("reviewDate")
            } else local.reviewDate,
            reviewTags = remoteText(remote, "reviewTags", local.reviewTags),
            fileCount = remote.optInt("fileCount", local.fileCount).coerceAtLeast(1)
        )
    }

    private fun remoteText(remote: JSONObject, name: String, current: String?): String? =
        if (!remote.has(name) || remote.isNull(name)) current else remote.optString(name).takeIf { it.isNotBlank() }

    private fun parseStatus(value: String, fallback: BookStatus = BookStatus.UNREAD): BookStatus =
        runCatching { BookStatus.valueOf(value) }.getOrDefault(fallback)

    private fun uploadAppDataToDrive(token: String, content: ByteArray) {
        val boundary = "-------OpenReadEraBoundary"
        val existingId = findRemoteStateFile(token)
        val metadata = JSONObject().apply {
            put("name", STATE_FILE_NAME)
            if (existingId == null) put("parents", JSONArray().put("appDataFolder"))
        }.toString()
        val multipartBody = MultipartBody.Builder(boundary)
            .setType("multipart/related".toMediaType())
            .addPart(Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                metadata.toRequestBody("application/json".toMediaType()))
            .addPart(Headers.headersOf("Content-Type", "application/json"),
                content.toRequestBody("application/json".toMediaType()))
            .build()

        val req = Request.Builder()
            .url(
                if (existingId == null) {
                    "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
                } else {
                    "https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=multipart"
                }
            )
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "multipart/related; boundary=$boundary")
            .post(multipartBody)
            .build()

        execute(req).use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string().orEmpty()
                throw IllegalStateException("Error de Drive (${response.code}): $detail")
            }
        }
    }

    suspend fun getOrCreateDriveFolder(token: String, folderName: String): String = withContext(Dispatchers.IO) {
        val session = checkNotNull(activeSession) { "No active Drive identity" }
        session.checkCurrent()
        require(authManager.getUserProfile()?.accessToken == token) { "OAuth account changed" }
        val cachedId = getTargetFolderId()
        if (!cachedId.isNullOrBlank() && !cachedId.startsWith("folder_")) {
            return@withContext cachedId
        }

        try {
            // 1. Search if folder already exists in Drive
            val searchUrl = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter(
                    "q",
                    "mimeType='application/vnd.google-apps.folder' and name='${escapeDriveQuery(folderName)}' and trashed=false"
                )
                .addQueryParameter("fields", "files(id,name)")
                .build()

            val searchReq = Request.Builder()
                .url(searchUrl)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            execute(searchReq).use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    val files = json.optJSONArray("files")
                    if (files != null && files.length() > 0) {
                        val folderId = files.getJSONObject(0).getString("id")
                        setTargetFolderId(folderId)
                        return@withContext folderId
                    }
                }
            }

            // 2. Folder does not exist, create it
            val createJson = JSONObject().apply {
                put("name", folderName)
                put("mimeType", "application/vnd.google-apps.folder")
            }

            val createReq = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json; charset=UTF-8")
                .post(createJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            execute(createReq).use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    val folderId = json.getString("id")
                    setTargetFolderId(folderId)
                    return@withContext folderId
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback to Drive root if custom folder could not be created/found
        "root"
    }



    private fun escapeDriveQuery(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    fun triggerImmediateBackgroundSync() {
        DriveSyncWorker.enqueueImmediateSync(context)
    }
}
