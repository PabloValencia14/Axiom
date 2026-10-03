package org.readera.openreadera.sync

import org.json.JSONObject
import org.readera.openreadera.data.db.AppDatabase
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.BookSyncIdentity
import java.io.File

internal class DriveSyncStateTransfer(
    private val database: AppDatabase,
    private val attachments: DriveAttachmentPipeline
) {
    suspend fun restoreRemoteAttachments(remoteState: JSONObject): JSONObject =
        rehydrateRemoteAttachments(
            remoteState,
            database.bookDao().getAllBooksIncludingTrashList(),
            attachments
        )

    suspend fun uploadLocalAttachments(
        books: List<Book>,
        remoteState: JSONObject?
    ): Map<Long, BookAttachmentRefs> {
        val remoteAttachments = remoteBookAttachments(remoteState)
        val result = mutableMapOf<Long, BookAttachmentRefs>()
        for (book in books) {
            val previous = remoteAttachments[BookSyncIdentity.of(book)]
                ?: remoteAttachments[BookSyncIdentity.legacy(book)]
            val bookFile = book.filePath.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile)
            val coverFile = book.coverPath?.let(::File)?.takeIf(File::isFile)
            val bookRef = if (bookFile != null) {
                attachments.upload(DriveAttachmentPipeline.source(bookFile))
            } else previous?.book
            val coverRef = if (coverFile != null) {
                attachments.upload(DriveAttachmentPipeline.source(coverFile))
            } else previous?.cover
            result[book.id] = BookAttachmentRefs(bookRef, coverRef)
        }
        return result
    }


    private fun remoteBookAttachments(root: JSONObject?): Map<String, BookAttachmentRefs> {
        val books = root?.optJSONArray("books") ?: return emptyMap()
        return buildMap {
            for (index in 0 until books.length()) {
                val remote = books.optJSONObject(index) ?: continue
                val syncId = DriveSyncSnapshotCodec.bookSyncId(remote)
                val references = BookAttachmentRefs(
                    DriveSyncSnapshotCodec.attachment(remote, "bookAttachment"),
                    DriveSyncSnapshotCodec.attachment(remote, "coverAttachment")
                )
                put(syncId, references)
                put(DriveSyncSnapshotCodec.legacyBookSyncId(remote), references)
            }
        }
    }

}
internal suspend fun rehydrateRemoteAttachments(
    remoteState: JSONObject,
    localBooks: List<Book>,
    attachments: DriveAttachmentPipeline
): JSONObject {
    val root = DriveSyncSnapshotCodec.migrateToCurrent(remoteState.toString())
    val localBySyncId = localBooks.associateBy(BookSyncIdentity::of)
    val localByLegacy = localBooks.groupBy(BookSyncIdentity::legacy)
    val books = root.optJSONArray("books") ?: return root
    for (index in 0 until books.length()) {
        val remote = books.optJSONObject(index) ?: continue
        val remoteSyncId = DriveSyncSnapshotCodec.bookSyncId(remote)
        val local = localBySyncId[remoteSyncId]
            ?: localByLegacy[DriveSyncSnapshotCodec.legacyBookSyncId(remote)]
                ?.firstOrNull { it.sha1.equals(remote.optString("sha1"), ignoreCase = true) }
            ?: localByLegacy[DriveSyncSnapshotCodec.legacyBookSyncId(remote)]?.firstOrNull()
        val localBookFile = local?.filePath?.let(::File)?.takeIf(File::isFile)
        val bookFile = resolveAttachment(
            DriveSyncSnapshotCodec.attachment(remote, "bookAttachment"),
            localBookFile,
            DriveAttachmentPipeline.Kind.BOOK,
            legacyBookFileName(remote),
            attachments
        )
        if (bookFile != null) remote.put("filePath", bookFile.absolutePath)
        else remote.remove("filePath")

        val localCoverFile = local?.coverPath?.let(::File)?.takeIf(File::isFile)
        val coverFile = resolveAttachment(
            DriveSyncSnapshotCodec.attachment(remote, "coverAttachment"),
            localCoverFile,
            DriveAttachmentPipeline.Kind.COVER,
            null,
            attachments
        )
        if (coverFile != null) remote.put("coverPath", coverFile.absolutePath)
        else remote.remove("coverPath")
    }
    return root
}

private suspend fun resolveAttachment(
    remote: DriveAttachmentRef?,
    localFile: File?,
    kind: DriveAttachmentPipeline.Kind,
    legacyName: String?,
    attachments: DriveAttachmentPipeline
): File? {
    if (remote == null) {
        if (localFile != null) return localFile
        return legacyName?.let {
            attachments.restoreLegacy(it, it.substringAfterLast('.', ""), kind)
        }
    }
    if (localFile == null) return attachments.restore(remote, kind)

    val localHash = DriveAttachmentPipeline.sha256(DriveAttachmentPipeline.source(localFile))
    if (localHash == remote.sha256) return localFile
    if (remote.modifiedAt > localFile.lastModified()) return attachments.restore(remote, kind)
    return localFile
}

private fun legacyBookFileName(remote: JSONObject): String? {
    val title = remote.optString("title").trim().takeIf(String::isNotBlank) ?: return null
    val format = remote.optString("format").substringAfterLast(' ').lowercase()
        .filter(Char::isLetterOrDigit).takeIf(String::isNotBlank) ?: return null
    return "$title.$format"
}
