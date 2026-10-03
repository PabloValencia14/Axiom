package org.readera.openreadera.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.readera.openreadera.zlib.ZLibBook
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class AnnasArchiveClient(
    private val context: Context,
    private val downloader: CatalogDownloader
) {
    companion object {
        const val PRIMARY_MIRROR = "https://annas-archive.gl"
        const val BACKUP_MIRROR = "https://annas-archive.pk"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(BypassDns.instance)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val client: OkHttpClient = defaultClient()

    suspend fun search(
        query: String,
        extension: String? = null,
        language: String? = null
    ): List<CatalogBook> = withContext(Dispatchers.IO) {
        val list = mutableListOf<CatalogBook>()
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return@withContext getPopularBooks()
        }

        try {
            val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
            val extParam = if (!extension.isNullOrBlank() && extension != "Todos") "&ext=${extension.lowercase()}" else ""
            val langParam = if (!language.isNullOrBlank() && language != "Todos los idiomas") {
                if (language.equals("Español", ignoreCase = true)) "&lang=es" else "&lang=en"
            } else ""

            val targetUrl = "$PRIMARY_MIRROR/search?q=$encodedQuery$extParam$langParam"
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/122.0.0.0")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .build()

            val html = client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() ?: "" else ""
            }

            // Parse MD5 entries: <a href="/md5/([a-fA-F0-9]{32})"[^>]*> ... <h3>Title</h3> ...
            val itemPattern = Pattern.compile(
                "<a\\s+href=\"/md5/([a-fA-F0-9]{32})\"[^>]*>.*?<h3[^>]*>([^<]+)</h3>(?:.*?<div[^>]*italic[^>]*>([^<]+)</div>)?",
                Pattern.CASE_INSENSITIVE or Pattern.DOTALL
            )
            val matcher = itemPattern.matcher(html)

            while (matcher.find()) {
                val md5 = matcher.group(1) ?: continue
                val title = matcher.group(2)?.trim() ?: "Sin título"
                val author = matcher.group(3)?.trim() ?: "Autor en Anna's Archive"

                val directDownloadUrl = "https://library.lol/main/$md5"
                val coverUrl = "$PRIMARY_MIRROR/covers/$md5.jpg"

                list.add(
                    CatalogBook(
                        id = "annas_$md5",
                        title = title,
                        author = author,
                        language = language ?: "Español",
                        extension = extension ?: "EPUB",
                        filesize = "Anna's Archive",
                        rating = "★ 4.9",
                        coverUrl = coverUrl,
                        downloadUrl = directDownloadUrl,
                        description = "Libro indexado por Anna's Archive. Descarga directa automática vía API.",
                        source = CatalogSource.ANNAS_ARCHIVE
                    )
                )
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
        val md5 = book.id.removePrefix("annas_").trim()

        // 1. Resolve direct download from LibGen CDN by MD5
        if (md5.length == 32) {
            try {
                val resolvedUrl = LibGenResolver.resolveByMd5(client, md5)
                if (!resolvedUrl.isNullOrBlank()) {
                    val testBook = book.copy(downloadUrl = resolvedUrl)
                    val res = downloader.download(testBook, onProgress)
                    if (res.isSuccess) return@withContext res
                }
            } catch (_: Exception) {}
        }

        // 2. Resolve direct download from LibGen CDN by title
        try {
            val resolvedByTitle = LibGenResolver.searchAndResolve(client, book.title)
            if (!resolvedByTitle.isNullOrBlank()) {
                val testBook = book.copy(downloadUrl = resolvedByTitle)
                val res = downloader.download(testBook, onProgress)
                if (res.isSuccess) return@withContext res
            }
        } catch (_: Exception) {}

        // 3. Try original downloadUrl if provided
        if (!book.downloadUrl.isNullOrBlank()) {
            try {
                val directRes = downloader.download(book, onProgress)
                if (directRes.isSuccess) return@withContext directRes
            } catch (_: Exception) {}
        }

        // 4. Fallback: search open catalogs across Internet Archive & Gutenberg
        try {
            val fallback = downloader.downloadFallbackByTitle(book, onProgress)
            if (fallback.isSuccess) return@withContext fallback
        } catch (_: Exception) {}

        Result.failure(Exception("No se pudo descargar '${book.title}' desde los servidores de Anna's Archive. Por favor, prueba con otra fuente."))
    }

    private fun resolveLibraryLolDownload(pageUrl: String): String? {
        return try {
            val req = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val resp = client.newCall(req).execute()
            val html = resp.body?.string() ?: return null
            val matcher = Pattern.compile("<a\\s+href=\"([^\"]+)\"[^>]*>GET</a>", Pattern.CASE_INSENSITIVE).matcher(html)
            if (matcher.find()) {
                matcher.group(1)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun getPopularBooks(): List<CatalogBook> = listOf(
        CatalogBook(
            id = "annas_cervantes_quijote",
            title = "Don Quijote de la Mancha",
            author = "Miguel de Cervantes Saavedra",
            language = "Español",
            extension = "EPUB",
            filesize = "1.8 MB",
            rating = "★ 5.0",
            coverUrl = "https://covers.openlibrary.org/b/id/8236467-L.jpg",
            downloadUrl = "https://www.gutenberg.org/cache/epub/2000/pg2000.epub",
            description = "La gran obra maestra de la literatura española universal.",
            source = CatalogSource.ANNAS_ARCHIVE
        ),
        CatalogBook(
            id = "annas_orwell_1984",
            title = "1984",
            author = "George Orwell",
            language = "Español",
            extension = "EPUB",
            filesize = "1.1 MB",
            rating = "★ 4.9",
            coverUrl = "https://covers.openlibrary.org/b/id/7222246-L.jpg",
            downloadUrl = "https://archive.org/download/1984-orwell-george/1984-orwell-george.epub",
            description = "Clásica novela distópica sobre la vigilancia masiva y el totalitarismo.",
            source = CatalogSource.ANNAS_ARCHIVE
        ),
        CatalogBook(
            id = "annas_garcia_marquez",
            title = "Cien años de soledad",
            author = "Gabriel García Márquez",
            language = "Español",
            extension = "EPUB",
            filesize = "2.4 MB",
            rating = "★ 5.0",
            coverUrl = "https://covers.openlibrary.org/b/id/8231856-L.jpg",
            downloadUrl = "https://archive.org/download/cien-anos-de-soledad/cien-anos-de-soledad.epub",
            description = "Historia de la familia Buendía en la aldea mítica de Macondo.",
            source = CatalogSource.ANNAS_ARCHIVE
        )
    )
}
