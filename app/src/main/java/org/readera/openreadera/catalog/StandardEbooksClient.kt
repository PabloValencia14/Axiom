package org.readera.openreadera.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class StandardEbooksClient(
    private val downloader: CatalogDownloader,
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
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val results = mutableListOf<CatalogBook>()
        try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val url = "https://standardebooks.org/opds/all?query=$encoded"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Axiom/2.0")
                .build()

            val xml = client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            } ?: return@withContext emptyList()

            val doc = Jsoup.parse(xml, "", Parser.xmlParser())
            val entries = doc.select("entry")

            for (entry in entries) {
                val title = entry.select("title").text().trim()
                if (title.isBlank()) continue

                val author = entry.select("author name").text().trim()
                val summary = entry.select("summary").text().trim()
                val coverUrl = entry.select("link[rel*='image']").attr("href")

                // Find acquisition link for epub
                val epubLink = entry.select("link[rel*='acquisition'][type*='epub']").attr("href")
                val downloadUrl = if (epubLink.startsWith("http")) epubLink else if (epubLink.isNotBlank()) "https://standardebooks.org$epubLink" else ""

                val id = entry.select("id").text().trim().ifBlank { "se_${title.hashCode()}" }

                results.add(
                    CatalogBook(
                        id = "se_$id",
                        title = title,
                        author = if (author.isNotBlank()) author else "Standard Ebooks",
                        year = "",
                        language = "English",
                        extension = "EPUB",
                        filesize = "Alta Calidad",
                        rating = "★ 5.0",
                        coverUrl = if (coverUrl.startsWith("http")) coverUrl else if (coverUrl.isNotBlank()) "https://standardebooks.org$coverUrl" else "",
                        downloadUrl = downloadUrl,
                        description = if (summary.isNotBlank()) summary else "Edición digital de alta calidad tipográfica por Standard Ebooks.",
                        source = CatalogSource.STANDARD_EBOOKS
                    )
                )
            }
        } catch (_: Exception) {}

        results
    }

    suspend fun downloadBook(
        book: CatalogBook,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        downloader.download(book, onProgress)
    }
}
