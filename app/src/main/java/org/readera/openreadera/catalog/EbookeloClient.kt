package org.readera.openreadera.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class EbookeloClient(
    private val context: Context,
    private val downloader: CatalogDownloader,
    private val client: OkHttpClient = defaultClient()
) {

    companion object {
        const val BASE_URL = "https://ww2.ebookelo.com"

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
        if (cleanQuery.isBlank()) {
            return@withContext getPopularSpanishBooks()
        }

        try {
            val encodedQuery = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
            val req = Request.Builder()
                .url("$BASE_URL/search/$encodedQuery/page/1")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                .header("Referer", "$BASE_URL/")
                .build()

            val html = client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            } ?: return@withContext emptyList()

            // Pattern for cards:
            // <a href="/ebook/84966/la-victoria-de-orwell" ...> ... <h3 class="title">...</h3> <span class="autor">...</span>
            val itemRegex = Pattern.compile(
                "<a\\s+href=\"(/ebook/(\\d+)/[^\"]+)\"[^>]*>.*?<h3\\s+class=\"title\">([^<]+)</h3>\\s*<span\\s+class=\"autor\">([^<]+)</span>(?:.*?<div\\s+class=\"idioma\">.*?<span>([^<]+)</span>)?",
                Pattern.CASE_INSENSITIVE or Pattern.DOTALL
            )
            val matcher = itemRegex.matcher(html)

            while (matcher.find()) {
                val relUrl = matcher.group(1) ?: ""
                val bookId = matcher.group(2) ?: ""
                val title = matcher.group(3)?.trim() ?: "Sin título"
                val author = matcher.group(4)?.trim() ?: "Autor desconocido"
                val rawLang = matcher.group(5)?.trim() ?: "Español"
                val cleanLang = if (rawLang.contains("espanol", true) || rawLang.contains("español", true) || rawLang.isBlank()) "Español" else rawLang

                val coverUrl = "$BASE_URL/images/cover/t/$bookId.jpg"
                val downloadUrl = "$BASE_URL/download/$bookId/epub"

                list.add(
                    CatalogBook(
                        id = "ebookelo_$bookId",
                        title = title,
                        author = author,
                        language = cleanLang,
                        extension = "EPUB",
                        filesize = "eBookelo",
                        rating = "★ 4.9",
                        coverUrl = coverUrl,
                        downloadUrl = downloadUrl,
                        description = "Libro en el catálogo en español de Ebookelo. Descarga directa automática vía API.",
                        source = CatalogSource.EBOOKELO
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
        // 1. If downloadUrl is a direct file link (not ebookelo download page or demo link), try downloading it
        if (!book.downloadUrl.isNullOrBlank() && !book.downloadUrl.contains("/download/") && !book.downloadUrl.contains("archive.org/download/1984")) {
            val directRes = downloader.download(book, onProgress)
            if (directRes.isSuccess) return@withContext directRes
        }

        val langPref = if (book.language.contains("espanol", true) || book.language.contains("español", true) || book.language.isBlank()) "Spanish" else book.language

        // 2. Resolve direct high-speed stream for this book title via LibGen CDN with Spanish language priority
        try {
            val resolved = LibGenResolver.searchAndResolveDownload(
                client = client,
                title = book.title,
                preferredExtension = "epub",
                preferredLanguage = langPref
            )
            if (resolved != null && resolved.url.isNotBlank()) {
                val resolvedBook = book.copy(downloadUrl = resolved.url, extension = resolved.extension)
                val dlRes = downloader.download(resolvedBook, onProgress)
                if (dlRes.isSuccess) return@withContext dlRes
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Try searching with Title + Author in LibGen CDN
        if (book.author.isNotBlank() && !book.author.contains("desconocido", true)) {
            try {
                val query = "${book.title} ${book.author}"
                val resolved = LibGenResolver.searchAndResolveDownload(
                    client = client,
                    title = query,
                    preferredExtension = "epub",
                    preferredLanguage = langPref
                )
                if (resolved != null && resolved.url.isNotBlank()) {
                    val resolvedBook = book.copy(downloadUrl = resolved.url, extension = resolved.extension)
                    val dlRes = downloader.download(resolvedBook, onProgress)
                    if (dlRes.isSuccess) return@withContext dlRes
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 4. Fallback: Search and download this exact book from multi-engine open repositories (IA, Gutenberg)
        try {
            val fallback = downloader.downloadFallbackByTitle(book, onProgress)
            if (fallback.isSuccess) return@withContext fallback
        } catch (_: Exception) {}

        Result.failure(Exception("No se pudo descargar '${book.title}'. Prueba buscando el título en los otros motores del catálogo."))
    }

    private fun getPopularSpanishBooks(): List<CatalogBook> = listOf(
        CatalogBook(
            id = "ebookelo_52354",
            title = "Beckomberga. Oda a mi familia",
            author = "Sara Stridsberg",
            language = "Español",
            extension = "EPUB",
            filesize = "1.2 MB",
            rating = "★ 4.9",
            coverUrl = "$BASE_URL/images/cover/t/52354.jpg",
            downloadUrl = null,
            description = "Novela galardonada de Sara Stridsberg sobre la memoria, el amor filial y la supervivencia emocional. Obra matriz del relato 'Bucear en verano'.",
            source = CatalogSource.EBOOKELO
        ),
        CatalogBook(
            id = "ebookelo_67317",
            title = "La facultad de sueños",
            author = "Sara Stridsberg",
            language = "Español",
            extension = "EPUB",
            filesize = "1.4 MB",
            rating = "★ 4.8",
            coverUrl = "$BASE_URL/images/cover/t/67317.jpg",
            downloadUrl = null,
            description = "Aclamada novela de Sara Stridsberg, Premio de Literatura del Consejo Nórdico.",
            source = CatalogSource.EBOOKELO
        ),
        CatalogBook(
            id = "ebookelo_85039",
            title = "Cien años de soledad",
            author = "Gabriel García Márquez",
            language = "Español",
            extension = "EPUB",
            filesize = "2.1 MB",
            rating = "★ 5.0",
            coverUrl = "https://covers.openlibrary.org/b/id/8231856-L.jpg",
            downloadUrl = null,
            description = "La obra maestra de Gabriel García Márquez y del realismo mágico hispanoamericano.",
            source = CatalogSource.EBOOKELO
        ),
        CatalogBook(
            id = "ebookelo_cervantes",
            title = "Don Quijote de la Mancha",
            author = "Miguel de Cervantes",
            language = "Español",
            extension = "EPUB",
            filesize = "1.8 MB",
            rating = "★ 5.0",
            coverUrl = "https://covers.openlibrary.org/b/id/8236467-L.jpg",
            downloadUrl = "https://www.gutenberg.org/cache/epub/2000/pg2000.epub",
            description = "La obra cumbre de la literatura en lengua castellana.",
            source = CatalogSource.EBOOKELO
        ),
        CatalogBook(
            id = "ebookelo_1984",
            title = "1984",
            author = "George Orwell",
            language = "Español",
            extension = "EPUB",
            filesize = "1.1 MB",
            rating = "★ 4.9",
            coverUrl = "https://covers.openlibrary.org/b/id/7222246-L.jpg",
            downloadUrl = null,
            description = "Clásica novela distópica sobre la vigilancia masiva y el totalitarismo.",
            source = CatalogSource.EBOOKELO
        )
    )
}
