package org.readera.openreadera.sync

import org.json.JSONArray
import org.json.JSONObject
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.BookSyncIdentity
import org.readera.openreadera.data.model.BookCollectionCrossRef
import org.readera.openreadera.data.model.Bookmark
import org.readera.openreadera.data.model.Collection
import org.readera.openreadera.data.model.DrawingStroke
import org.readera.openreadera.data.model.Quote
import org.readera.openreadera.data.model.SearchQuery
import java.security.MessageDigest
import java.util.Locale

internal data class BookAttachmentRefs(
    val book: DriveAttachmentRef?,
    val cover: DriveAttachmentRef?
)

internal data class DriveSnapshotContents(
    val books: List<Book>,
    val bookmarks: List<Bookmark>,
    val quotes: List<Quote>,
    val drawingStrokes: List<DrawingStroke>,
    val collections: List<Collection>,
    val bookCollections: List<BookCollectionCrossRef>,
    val searchQueries: List<SearchQuery>,
    val attachmentsByBookId: Map<Long, BookAttachmentRefs>,
    val drawingTombstones: Set<String>,
    val readerPreferences: JSONObject,
    val readingStats: JSONObject,
    val syncPreferences: JSONObject,
    val opdsCatalogs: JSONArray,
    val zLibraryPreferences: JSONObject,
    val manualCategoryOverrides: JSONArray,
    val timestamp: Long
)

internal object DriveSyncSnapshotCodec {
    const val CURRENT_VERSION = 3

    fun encode(contents: DriveSnapshotContents): String {
        val bookIds = contents.books.associate { it.id to BookSyncIdentity.of(it) }
        val collectionIds = contents.collections.associate { it.id to collectionSyncId(it) }
        val root = JSONObject().apply {
            put("version", CURRENT_VERSION)
            put("timestamp", contents.timestamp)
            put("books", JSONArray().apply {
                contents.books.sortedBy { bookIds[it.id].orEmpty() }.forEach { book ->
                    val refs = contents.attachmentsByBookId[book.id]
                    val bookSyncId = bookIds.getValue(book.id)
                    put(JSONObject().apply {
                        put("id", book.id)
                        put("syncId", bookSyncId)
                        put("title", book.title)
                        put("author", book.author)
                        put("format", book.format)
                        put("fileSize", book.fileSize)
                        put("currentPage", book.currentPage)
                        put("totalPages", book.totalPages)
                        put("progressPercent", book.progressPercent.toDouble())
                        put("isFavorite", book.isFavorite)
                        put("isToRead", book.isToRead)
                        put("isHaveRead", book.isHaveRead)
                        put("isTrash", book.isTrash)
                        put("status", book.status.name)
                        put("dateAdded", book.dateAdded)
                        put("lastOpened", book.lastOpened)
                        putNullable("series", book.series)
                        putNullable("seriesIndex", book.seriesIndex)
                        putNullable("collectionName", book.collectionName)
                        putNullable("sha1", book.sha1)
                        putNullable("language", book.language)
                        putNullable("genre", book.genre)
                        putNullable("description", book.description)
                        putNullable("annotation", book.annotation)
                        putNullable("review", book.review)
                        putNullable("rating", book.rating?.toDouble())
                        putNullable("reviewDate", book.reviewDate)
                        putNullable("reviewTags", book.reviewTags)
                        put("fileCount", book.fileCount)
                        refs?.book?.let { put("bookAttachment", it.toJson()) }
                        refs?.cover?.let { put("coverAttachment", it.toJson()) }
                    })
                }
            })
            put("bookmarks", JSONArray().apply {
                contents.bookmarks.forEach { bookmark ->
                    val bookSyncId = bookIds[bookmark.bookId] ?: return@forEach
                    put(JSONObject().apply {
                        put("id", bookmark.id)
                        put("bookId", bookmark.bookId)
                        put("bookSyncId", bookSyncId)
                        put("syncId", stableId(bookSyncId, bookmark.page, bookmark.title, bookmark.snippet, bookmark.createdAt))
                        put("page", bookmark.page)
                        put("title", bookmark.title)
                        put("snippet", bookmark.snippet)
                        put("createdAt", bookmark.createdAt)
                    })
                }
            })
            put("quotes", JSONArray().apply {
                contents.quotes.forEach { quote ->
                    val bookSyncId = bookIds[quote.bookId] ?: return@forEach
                    put(JSONObject().apply {
                        put("id", quote.id)
                        put("bookId", quote.bookId)
                        put("bookSyncId", bookSyncId)
                        put("syncId", stableId(bookSyncId, quote.page, quote.text, quote.note, quote.colorHex, quote.createdAt))
                        put("page", quote.page)
                        put("text", quote.text)
                        put("note", quote.note)
                        put("colorHex", quote.colorHex)
                        put("createdAt", quote.createdAt)
                    })
                }
            })
            put("drawingStrokes", JSONArray().apply {
                contents.drawingStrokes.forEach { stroke ->
                    val bookSyncId = bookIds[stroke.bookId] ?: return@forEach
                    put(JSONObject().apply {
                        put("id", stroke.id)
                        put("bookId", stroke.bookId)
                        put("bookSyncId", bookSyncId)
                        put("syncId", stableId(bookSyncId, stroke.page, stroke.toolType.name, stroke.colorHex, stroke.strokeWidth, stroke.pointsJson, stroke.createdAt))
                        put("page", stroke.page)
                        put("toolType", stroke.toolType.name)
                        put("colorHex", stroke.colorHex)
                        put("strokeWidth", stroke.strokeWidth.toDouble())
                        put("pointsJson", stroke.pointsJson)
                        put("createdAt", stroke.createdAt)
                    })
                }
            })
            put("drawingStrokeTombstones", JSONArray().apply {
                contents.drawingTombstones.sorted().forEach(::put)
            })
            put("collections", JSONArray().apply {
                contents.collections.forEach { collection ->
                    put(JSONObject().apply {
                        put("id", collection.id)
                        put("syncId", collectionSyncId(collection))
                        put("name", collection.name)
                        put("createdAt", collection.createdAt)
                    })
                }
            })
            put("bookCollections", JSONArray().apply {
                contents.bookCollections.forEach { relation ->
                    val bookSyncId = bookIds[relation.bookId] ?: return@forEach
                    val collectionSyncId = collectionIds[relation.collectionId] ?: return@forEach
                    put(JSONObject().apply {
                        put("bookId", relation.bookId)
                        put("bookSyncId", bookSyncId)
                        put("collectionId", relation.collectionId)
                        put("collectionSyncId", collectionSyncId)
                    })
                }
            })
            put("searchHistory", JSONArray().apply {
                contents.searchQueries.sortedBy { it.query }.forEach { query ->
                    put(JSONObject().apply {
                        put("query", query.query)
                        put("timestamp", query.timestamp)
                    })
                }
            })
            put("readerPreferences", JSONObject(contents.readerPreferences.toString()))
            put("readingStats", JSONObject(contents.readingStats.toString()))
            put("syncPreferences", JSONObject(contents.syncPreferences.toString()))
            put("opdsCatalogs", JSONArray(contents.opdsCatalogs.toString()))
            put("zLibraryPreferences", JSONObject(contents.zLibraryPreferences.toString()))
            put("manualCategoryOverrides", JSONArray(contents.manualCategoryOverrides.toString()))
        }
        return root.toString()
    }

    fun migrateToCurrent(json: String): JSONObject {
        val original = JSONObject(json)
        val sourceVersion = original.optInt("version", 1)
        require(sourceVersion in 1..CURRENT_VERSION) {
            "Estado de sincronización no compatible (versión $sourceVersion)"
        }

        val root = JSONObject(original.toString())
        val remoteBookIds = mutableMapOf<Long, String>()
        root.optJSONArray("books")?.let { books ->
            for (index in 0 until books.length()) {
                val book = books.optJSONObject(index) ?: continue
                val syncId = book.optString("syncId").takeIf(String::isNotBlank)
                    ?: legacyBookSyncId(book)
                book.put("syncId", syncId)
                remoteBookIds[book.optLong("id", -1L)] = syncId
                // Remote filesystem locations and SAF references are never portable.
                book.remove("filePath")
                book.remove("coverPath")
            }
        }
        listOf("bookmarks", "quotes", "drawingStrokes").forEach { table ->
            root.optJSONArray(table)?.let { values ->
                for (index in 0 until values.length()) {
                    val value = values.optJSONObject(index) ?: continue
                    val bookId = value.optLong("bookId", -1L)
                    val bookSyncId = value.optString("bookSyncId").takeIf(String::isNotBlank)
                        ?: remoteBookIds[bookId]
                    if (bookSyncId != null) value.put("bookSyncId", bookSyncId)
                    if (!value.has("syncId") && bookSyncId != null) {
                        value.put("syncId", legacyEntitySyncId(table, bookSyncId, value))
                    }
                }
            }
        }
        val remoteCollectionIds = mutableMapOf<Long, String>()
        root.optJSONArray("collections")?.let { values ->
            for (index in 0 until values.length()) {
                val collection = values.optJSONObject(index) ?: continue
                val syncId = collection.optString("syncId").takeIf(String::isNotBlank)
                    ?: stableId(normalize(collection.optString("name")), collection.optLong("createdAt", 0L))
                collection.put("syncId", syncId)
                remoteCollectionIds[collection.optLong("id", -1L)] = syncId
            }
        }
        root.optJSONArray("bookCollections")?.let { values ->
            for (index in 0 until values.length()) {
                val relation = values.optJSONObject(index) ?: continue
                remoteBookIds[relation.optLong("bookId", -1L)]?.let { relation.put("bookSyncId", it) }
                remoteCollectionIds[relation.optLong("collectionId", -1L)]?.let { relation.put("collectionSyncId", it) }
            }
        }
        root.put("version", CURRENT_VERSION)
        root.put("migrationSourceVersion", sourceVersion)
        return root
    }

    fun bookSyncId(remote: JSONObject): String =
        remote.optString("syncId").takeIf(String::isNotBlank) ?: legacyBookSyncId(remote)

    fun legacyBookSyncId(remote: JSONObject): String {
        val sha1 = remote.optString("sha1").trim()
        return if (sha1.isNotBlank()) "sha1:${sha1.lowercase(Locale.ROOT)}"
        else "meta:${normalize(remote.optString("title"))}|${normalize(remote.optString("author"))}"
    }

    fun attachment(remoteBook: JSONObject, field: String): DriveAttachmentRef? {
        val json = remoteBook.optJSONObject(field) ?: return null
        return DriveAttachmentRef(
            sha256 = json.getString("sha256"),
            remoteName = json.getString("remoteName"),
            size = json.getLong("size"),
            extension = json.optString("extension"),
            mimeType = json.optString("mimeType", "application/octet-stream"),
            modifiedAt = json.optLong("modifiedAt", 0L)
        )
    }

    fun collectionSyncId(collection: Collection): String =
        stableId(normalize(collection.name), collection.createdAt)

    private fun DriveAttachmentRef.toJson() = JSONObject().apply {
        put("sha256", sha256)
        put("remoteName", remoteName)
        put("size", size)
        put("extension", extension)
        put("mimeType", mimeType)
        put("modifiedAt", modifiedAt)
    }

    private fun JSONObject.putNullable(name: String, value: Any?) {
        put(name, value ?: JSONObject.NULL)
    }

    private fun stableId(vararg parts: Any?): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("\u0000") { it?.toString().orEmpty() }.toByteArray(Charsets.UTF_8))
        .toHex()

    private fun legacyEntitySyncId(table: String, bookSyncId: String, value: JSONObject): String = when (table) {
        "bookmarks" -> stableId(bookSyncId, value.optInt("page"), value.optString("title"), value.optString("snippet"), value.optLong("createdAt"))
        "quotes" -> stableId(bookSyncId, value.optInt("page"), value.optString("text"), value.optString("note"), value.optString("colorHex"), value.optLong("createdAt"))
        else -> stableId(bookSyncId, value.optInt("page"), value.optString("toolType"), value.optString("colorHex"), value.optDouble("strokeWidth"), value.optString("pointsJson"), value.optLong("createdAt"))
    }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append("0123456789abcdef"[value ushr 4])
            append("0123456789abcdef"[value and 0xf])
        }
    }
}
