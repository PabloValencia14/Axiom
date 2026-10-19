package org.readera.openreadera.data.model

import java.security.MessageDigest

/** Matches v3 snapshots for migrated rows; new rows keep their UUID through edits and deletion. */
internal object AnnotationIdentity {
    fun of(book: Book, quote: Quote): String = quote.syncId.ifBlank {
        legacy(BookSyncIdentity.of(book), quote.page, quote.text, quote.note, quote.colorHex, quote.createdAt)
    }

    fun of(book: Book, bookmark: Bookmark): String = bookmark.syncId.ifBlank {
        legacy(BookSyncIdentity.of(book), bookmark.page, bookmark.title, bookmark.snippet, bookmark.createdAt)
    }

    private fun legacy(vararg values: Any): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
