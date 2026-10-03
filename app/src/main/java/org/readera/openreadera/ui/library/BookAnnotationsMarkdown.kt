package org.readera.openreadera.ui.library

import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.Bookmark
import org.readera.openreadera.data.model.Quote

internal fun formatBookAnnotationsMarkdown(
    book: Book,
    quotes: List<Quote>,
    bookmarks: List<Bookmark>
): String = buildString {
    append("# ").append(markdownInline(book.title)).append("\n\n")
    append("**Autor:** ").append(markdownInline(book.author)).append("\n\n")

    append("## Citas y notas\n\n")
    if (quotes.isEmpty()) append("_No hay citas ni notas._\n\n")
    quotes.forEach { quote ->
        append("### Página ").append(quote.page + 1).append("\n\n")
        append(markdownQuote(quote.text)).append("\n\n")
        if (quote.note.isNotBlank()) {
            append("**Nota:**\n\n").append(markdownQuote(quote.note)).append("\n\n")
        }
    }

    append("## Marcadores\n\n")
    if (bookmarks.isEmpty()) append("_No hay marcadores._\n")
    bookmarks.forEachIndexed { index, bookmark ->
        append("### ").append(index + 1).append(". ").append(markdownInline(bookmark.title)).append("\n\n")
        append("**Página:** ").append(bookmark.page + 1).append("\n\n")
        if (bookmark.snippet.isNotBlank()) {
            append(markdownQuote(bookmark.snippet)).append("\n\n")
        }
    }
}

private fun markdownQuote(value: String): String = value
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .split('\n')
    .joinToString("\n") { line -> "> ${line.replace("\\", "\\\\")}" }

private fun markdownInline(value: String): String = value
    .replace("\\", "\\\\")
    .replace("`", "\\`")
    .replace("*", "\\*")
    .replace("_", "\\_")
    .replace("[", "\\[")
    .replace("]", "\\]")
    .replace("\r\n", " ")
    .replace('\r', ' ')
    .replace('\n', ' ')
