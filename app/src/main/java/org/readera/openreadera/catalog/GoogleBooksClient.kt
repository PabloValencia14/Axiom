package org.readera.openreadera.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class GoogleBooksClient(
    private val downloader: CatalogDownloader? = null,
    private val client: OkHttpClient = defaultClient()
) {

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(BypassDns.instance)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun search(query: String): List<CatalogBook> = withContext(Dispatchers.IO) {
        val list = mutableListOf<CatalogBook>()
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            // Search Google Books API with Spanish language priority
            val url = "https://www.googleapis.com/books/v1/volumes?q=$encoded&langRestrict=es&maxResults=20"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .build()

            val body = client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() ?: "" else ""
            }

            if (body.startsWith("{")) {
                val json = JSONObject(body)
                val items = json.optJSONArray("items")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val id = item.optString("id", "")
                        val vol = item.optJSONObject("volumeInfo") ?: continue

                        val title = vol.optString("title", "Sin título")
                        val authorsArray = vol.optJSONArray("authors")
                        val authors = if (authorsArray != null && authorsArray.length() > 0) {
                            (0 until authorsArray.length()).map { authorsArray.getString(it) }.joinToString(", ")
                        } else {
                            "Autor desconocido"
                        }

                        val publisher = vol.optString("publisher", "")
                        val publishedDate = vol.optString("publishedDate", "")
                        val description = vol.optString("description", "")
                        val rawCover = vol.optJSONObject("imageLinks")?.optString("thumbnail")
                            ?: vol.optJSONObject("imageLinks")?.optString("smallThumbnail")
                        val coverUrl = rawCover?.replace("http://", "https://")

                        val rawLang = vol.optString("language", "es")
                        val cleanLang = if (rawLang.equals("es", true)) "Español" else rawLang.uppercase()

                        val pageCount = vol.optInt("pageCount", 0)
                        val filesizeStr = if (pageCount > 0) "$pageCount págs." else "Google Books"

                        list.add(
                            CatalogBook(
                                id = "gbooks_$id",
                                title = title,
                                author = authors,
                                year = publishedDate.take(4),
                                language = cleanLang,
                                extension = "EPUB",
                                filesize = filesizeStr,
                                rating = "★ 4.9",
                                coverUrl = coverUrl,
                                downloadUrl = null, // Will be resolved dynamically upon download
                                description = if (description.isNotBlank()) description else "Obra indexada en Google Books (${if (publisher.isNotBlank()) "$publisher, " else ""}$publishedDate).",
                                source = CatalogSource.GOOGLE_BOOKS
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        list
    }

    suspend fun downloadBook(
        book: CatalogBook,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        if (downloader == null) {
            return@withContext Result.failure(Exception("Descargador no disponible"))
        }

        // 1. First attempt: resolve directly from LibGen CDN with Spanish priority
        try {
            val resolved = LibGenResolver.searchAndResolveDownload(
                client = client,
                title = book.title,
                preferredExtension = "epub",
                preferredLanguage = "Spanish"
            )
            if (resolved != null && resolved.url.isNotBlank()) {
                val resolvedBook = book.copy(downloadUrl = resolved.url, extension = resolved.extension)
                val res = downloader.download(resolvedBook, onProgress)
                if (res.isSuccess) return@withContext res
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Second attempt: title + author in LibGen CDN
        if (book.author.isNotBlank() && !book.author.contains("desconocido", true)) {
            try {
                val query = "${book.title} ${book.author}"
                val resolved = LibGenResolver.searchAndResolveDownload(
                    client = client,
                    title = query,
                    preferredExtension = "epub",
                    preferredLanguage = "Spanish"
                )
                if (resolved != null && resolved.url.isNotBlank()) {
                    val resolvedBook = book.copy(downloadUrl = resolved.url, extension = resolved.extension)
                    val res = downloader.download(resolvedBook, onProgress)
                    if (res.isSuccess) return@withContext res
                }
            } catch (_: Exception) {}
        }

        // 3. Third attempt: Open Access Repositories (Internet Archive, Gutenberg)
        try {
            val fallback = downloader.downloadFallbackByTitle(book, onProgress)
            if (fallback.isSuccess) return@withContext fallback
        } catch (_: Exception) {}

        Result.failure(
            Exception(
                "No se encontró descarga directa para '${book.title}'. Prueba buscando en LibGen, Anna's Archive o Z-Library."
            )
        )
    }
}
