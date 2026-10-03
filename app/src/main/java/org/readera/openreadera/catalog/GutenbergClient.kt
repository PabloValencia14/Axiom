package org.readera.openreadera.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

import java.io.File

class GutenbergClient(
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
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = if (encoded.isBlank()) {
                "https://gutendex.com/books/?popular"
            } else {
                "https://gutendex.com/books/?search=$encoded"
            }

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Axiom/2.0 (Android; EBookReader)")
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext emptyList()

            if (resp.isSuccessful && body.startsWith("{")) {
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: return@withContext emptyList()

                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val id = item.optLong("id").toString()
                    val title = item.optString("title", "Sin título")

                    val authorsArray = item.optJSONArray("authors")
                    val authors = mutableListOf<String>()
                    if (authorsArray != null) {
                        for (a in 0 until authorsArray.length()) {
                            val authorObj = authorsArray.getJSONObject(a)
                            val rawName = authorObj.optString("name", "")
                            if (rawName.contains(",")) {
                                val parts = rawName.split(",").map { it.trim() }
                                authors.add("${parts.getOrNull(1).orEmpty()} ${parts.getOrNull(0).orEmpty()}".trim())
                            } else if (rawName.isNotBlank()) {
                                authors.add(rawName)
                            }
                        }
                    }
                    val authorName = if (authors.isNotEmpty()) authors.joinToString(", ") else "Autor clásico"

                    val langs = item.optJSONArray("languages")
                    val lang = if (langs != null && langs.length() > 0) langs.optString(0, "es").uppercase() else "ES"

                    val formats = item.optJSONObject("formats")
                    var coverUrl: String? = null
                    var downloadUrl: String? = null
                    var ext = "EPUB"

                    if (formats != null) {
                        coverUrl = formats.optString("image/jpeg").takeIf { it.isNotBlank() }
                        val epub = formats.optString("application/epub+zip").takeIf { it.isNotBlank() }
                        val mobi = formats.optString("application/x-mobipocket-ebook").takeIf { it.isNotBlank() }
                        val html = formats.optString("text/html").takeIf { it.isNotBlank() }
                        val txt = formats.optString("text/plain; charset=utf-8").takeIf { it.isNotBlank() }

                        when {
                            !epub.isNullOrBlank() -> {
                                downloadUrl = epub
                                ext = "EPUB"
                            }
                            !mobi.isNullOrBlank() -> {
                                downloadUrl = mobi
                                ext = "MOBI"
                            }
                            !txt.isNullOrBlank() -> {
                                downloadUrl = txt
                                ext = "TXT"
                            }
                            else -> downloadUrl = html
                        }
                    }

                    val summaries = item.optJSONArray("summaries")
                    val summary = if (summaries != null && summaries.length() > 0) summaries.optString(0) else null

                    val downloads = item.optInt("download_count", 0)

                    list.add(
                        CatalogBook(
                            id = "gutenberg_$id",
                            title = title,
                            author = authorName,
                            language = lang,
                            extension = ext,
                            filesize = "Dominio Público",
                            rating = "★ ${String.format("%.1f", 4.5 + ((downloads % 5) * 0.1))}",
                            coverUrl = coverUrl,
                            downloadUrl = downloadUrl,
                            description = summary,
                            source = CatalogSource.GUTENBERG
                        )
                    )
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
        if (downloader != null) {
            downloader.download(book, onProgress)
        } else {
            Result.failure(Exception("Descargador no inicializado para Gutenberg."))
        }
    }
}
