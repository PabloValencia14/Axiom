package org.readera.openreadera.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import org.readera.openreadera.core.io.TransferLimits
import org.readera.openreadera.core.io.readBoundedText

class LibGenClient(
    private val downloader: CatalogDownloader,
    private val client: OkHttpClient = defaultClient()
) {
    private val requestExecutor = CatalogRequestExecutor(client)
    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(BypassDns.instance)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        private val MIRRORS = listOf("https://libgen.is", "https://libgen.rs", "https://libgen.li")
    }

    suspend fun search(
        query: String,
        extension: String = "Todos",
        language: String = "Todos"
    ): List<CatalogBook> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val results = mutableListOf<CatalogBook>()

        // 1. LibGen Non-Fiction / General Search
        for (mirror in MIRRORS) {
            try {
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                val url = "$mirror/search.php?req=$encoded&open=0&res=25&view=simple&phrase=1&column=def"

                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val html = requestExecutor.execute(req).use { resp ->
                    if (resp.isSuccessful) resp.body?.let { it.byteStream().readBoundedText(TransferLimits.CATALOG_PAGE, it.contentLength()) } else null
                } ?: continue

                val doc = Jsoup.parse(html)
                val rows = doc.select("table.c tbody tr, table[rules='rows'] tr")

                for (row in rows) {
                    val cols = row.select("td")
                    if (cols.size < 9) continue

                    val author = cols[1].text().trim()
                    val titleElem = cols[2].select("a").firstOrNull() ?: continue
                    val title = titleElem.text().trim()
                    if (title.isBlank() || title.equals("Title", ignoreCase = true)) continue

                    val year = cols.getOrNull(4)?.text()?.trim() ?: ""
                    val lang = cols.getOrNull(6)?.text()?.trim() ?: "Global"
                    val size = cols.getOrNull(7)?.text()?.trim() ?: ""
                    val ext = cols.getOrNull(8)?.text()?.trim()?.uppercase() ?: "EPUB"

                    val mirror1 = cols.getOrNull(9)?.select("a")?.firstOrNull()?.attr("href") ?: ""
                    val md5Match = Pattern.compile("([a-fA-F0-9]{32})").matcher(mirror1)
                    val md5 = if (md5Match.find()) md5Match.group(1) else ""

                    val bookId = "libgen_${if (md5.isNotBlank()) md5 else cols[0].text().trim()}"
                    val downloadUrl = if (mirror1.isNotBlank()) mirror1 else "$mirror/get.php?md5=$md5"

                    results.add(
                        CatalogBook(
                            id = bookId,
                            title = title,
                            author = if (author.isNotBlank()) author else "Autor LibGen",
                            year = year,
                            language = lang,
                            extension = ext,
                            filesize = size,
                            rating = "★ 4.9",
                            coverUrl = "",
                            downloadUrl = downloadUrl,
                            description = "Biblioteca libre Library Genesis.",
                            source = CatalogSource.LIBGEN
                        )
                    )
                }

                if (results.isNotEmpty()) break
            } catch (_: Exception) {}
        }

        results
    }

    suspend fun downloadBook(
        book: CatalogBook,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var directUrl = book.downloadUrl ?: ""
        if (directUrl.isBlank() || !directUrl.startsWith("http")) {
            val resolved = LibGenResolver.searchAndResolveDownload(
                client = client,
                title = book.title,
                preferredExtension = book.extension.ifBlank { "epub" },
                preferredLanguage = book.language ?: "Spanish"
            )
            if (resolved != null && resolved.url.isNotBlank()) {
                directUrl = resolved.url
            }
        }

        if (directUrl.contains("library.lol") || directUrl.contains("ads.php")) {
            try {
                intermediateDownloadUrl(directUrl)?.let { directUrl = it }
            } catch (_: Exception) {}
        }

        val resolvedBook = book.copy(downloadUrl = directUrl)
        downloader.download(resolvedBook, onProgress)
    }

    internal fun intermediateDownloadUrl(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        return requestExecutor.execute(request).use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            val html = body.byteStream().readBoundedText(TransferLimits.CATALOG_PAGE, body.contentLength())
            val doc = Jsoup.parse(html, response.request.url.toString())
            doc.select("a:contains(GET), a:contains(Cloudflare), a:contains(IPFS)").firstOrNull()
                ?.absUrl("href")?.takeIf(String::isNotBlank)
        }
    }
}
