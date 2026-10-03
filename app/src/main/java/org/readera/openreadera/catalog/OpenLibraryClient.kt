package org.readera.openreadera.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

import java.io.File

class OpenLibraryClient(
    private val downloader: CatalogDownloader? = null,
    private val client: OkHttpClient = defaultClient()
) {

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(BypassDns.instance)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun search(query: String): List<CatalogBook> = withContext(Dispatchers.IO) {
        val list = mutableListOf<CatalogBook>()
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        // 1. Primary: Internet Archive Open Library API (direct and highly accessible)
        try {
            val sanitized = cleanQuery.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s]"), " ").trim()
            val encoded = URLEncoder.encode("($sanitized) AND mediatype:(texts)", "UTF-8")
            val url = "https://archive.org/advancedsearch.php?q=$encoded&fl[]=identifier,title,creator,year,description,format&output=json&rows=25"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Axiom/2.0 (Android; EBookReader)")
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (resp.isSuccessful && body.startsWith("{")) {
                val json = JSONObject(body)
                val docs = json.optJSONObject("response")?.optJSONArray("docs")

                if (docs != null) {
                    for (i in 0 until docs.length()) {
                        val doc = docs.getJSONObject(i)
                        val id = doc.optString("identifier", "")
                        if (id.isBlank()) continue

                        val title = doc.optString("title", "Sin título")
                        val creator = doc.optString("creator", "Autor de Open Library")
                        val year = doc.optString("year", "")
                        val desc = doc.optString("description", "Libro de acceso abierto catalogado en Internet Archive / Open Library.")

                        val coverUrl = "https://archive.org/services/img/$id"
                        val downloadUrl = "https://archive.org/download/$id/$id.epub"

                        list.add(
                            CatalogBook(
                                id = "openlibrary_$id",
                                title = title,
                                author = creator,
                                year = year,
                                language = "Global",
                                extension = "EPUB",
                                filesize = "Open Access",
                                rating = "★ 4.8",
                                coverUrl = coverUrl,
                                downloadUrl = downloadUrl,
                                description = desc,
                                source = CatalogSource.OPEN_LIBRARY
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Secondary fallback: try openlibrary.org if archive.org returned no results
        if (list.isEmpty()) {
            try {
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                val url = "https://openlibrary.org/search.json?q=$encoded&limit=20"
                val req = Request.Builder().url(url).header("User-Agent", "Axiom/2.0").build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                if (resp.isSuccessful && body.startsWith("{")) {
                    val json = JSONObject(body)
                    val docs = json.optJSONArray("docs")
                    if (docs != null) {
                        for (i in 0 until docs.length()) {
                            val doc = docs.getJSONObject(i)
                            val key = doc.optString("key", "").replace("/works/", "")
                            val title = doc.optString("title", "Sin título")
                            val authors = doc.optJSONArray("author_name")?.optString(0, "Desconocido") ?: "Desconocido"
                            val coverId = doc.optLong("cover_i", 0L)
                            val coverUrl = if (coverId > 0) "https://covers.openlibrary.org/b/id/$coverId-M.jpg" else null
                            val ia = doc.optJSONArray("ia")?.optString(0)
                            val dlUrl = if (!ia.isNullOrBlank()) "https://archive.org/download/$ia/$ia.epub" else "https://openlibrary.org/works/$key"

                            list.add(
                                CatalogBook(
                                    id = "openlibrary_${key.ifBlank { i.toString() }}",
                                    title = title,
                                    author = authors,
                                    language = "Global",
                                    extension = "EPUB",
                                    filesize = "Open Access",
                                    rating = "★ 4.6",
                                    coverUrl = coverUrl,
                                    downloadUrl = dlUrl,
                                    description = "Obra catalogada en Open Library / Internet Archive.",
                                    source = CatalogSource.OPEN_LIBRARY
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        list
    }

    suspend fun downloadBook(
        book: CatalogBook,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        if (downloader != null) {
            // If downloadUrl is a web page, fallback to title search in Open Access
            if (book.downloadUrl.isNullOrBlank() || book.downloadUrl.contains("openlibrary.org/works/")) {
                downloader.downloadFallbackByTitle(book, onProgress)
            } else {
                val res = downloader.download(book, onProgress)
                if (res.isSuccess) res else downloader.downloadFallbackByTitle(book, onProgress)
            }
        } else {
            Result.failure(Exception("Descargador no inicializado para Open Library."))
        }
    }
}
