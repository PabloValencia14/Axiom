package org.readera.openreadera.data.model

import java.util.Locale

internal object BookSyncIdentity {
    fun of(book: Book): String = book.syncId.ifBlank { legacy(book.title, book.author, book.sha1) }

    fun legacy(book: Book): String = legacy(book.title, book.author, book.sha1)

    fun legacy(title: String, author: String, sha1: String?): String =
        if (!sha1.isNullOrBlank()) "sha1:${sha1.lowercase(Locale.ROOT)}"
        else "meta:${normalize(title)}|${normalize(author)}"

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
