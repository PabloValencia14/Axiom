package org.readera.openreadera.catalog

import android.content.Context
import android.util.Xml
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.readera.openreadera.OpenReadEraApplication
import org.readera.openreadera.data.scanner.StorageScanner
import java.io.File
import java.util.concurrent.TimeUnit
import org.readera.openreadera.core.io.TransferLimits
import org.readera.openreadera.core.io.copyBounded

private val FICTION_BOOK_ROOT = Regex("""<(?:[\w.-]+:)?fictionbook\b""", RegexOption.IGNORE_CASE)

class CatalogDownloader(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(BypassDns.instance)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
) {
    private val requestExecutor = CatalogRequestExecutor(client)

    suspend fun download(
        book: CatalogBook,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val url = book.downloadUrl
        if (url.isNullOrBlank()) {
            return@withContext Result.failure(Exception("No se encontró enlace de descarga directo para '${book.title}'."))
        }

        val downloadDir = StorageScanner.getAppBooksDirectory().apply { mkdirs() }
        val safeTitle = book.title.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s-]"), "").take(40).trim().ifBlank { "Libro" }
        val safeAuthor = book.author.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s-]"), "").take(30).trim().ifBlank { "Autor" }
        val ext = book.extension.lowercase().filter(Char::isLetterOrDigit).take(8).ifBlank { "epub" }
        val tempFile = try {
            File.createTempFile(".catalog-", ".$ext", downloadDir)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }

        var finalFile: File? = null
        try {
            val reqBuilder = Request.Builder()
                .url(CatalogUrlSecurity.checkedUrl(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

            if (url.contains("libgen.li") || url.contains("booksdl.lc") || url.contains("get.php")) {
                reqBuilder.header("Referer", "https://libgen.li/ads.php")
            } else if (url.contains("ebookelo.com")) {
                reqBuilder.header("Referer", "https://ww2.ebookelo.com/")
            }
            val req = reqBuilder.build()

            var totalBytes = 0L
            requestExecutor.execute(req).use { resp ->
                if (!resp.isSuccessful || resp.body == null) {
                    tempFile.delete()
                    return@withContext Result.failure(
                        Exception("Error del servidor (HTTP ${resp.code}) al descargar '${book.title}'.")
                    )
                }

                val body = resp.body!!
                val contentType = body.contentType()?.toString() ?: ""
                // Check if it's an HTML error/login page instead of a book file
                if (contentType.contains("text/html", true) && body.contentLength() in 1..40_000) {
                    tempFile.delete()
                    return@withContext Result.failure(
                        Exception("El servidor devolvió una página HTML en lugar del archivo de libro para '${book.title}'.")
                    )
                }

                val length = body.contentLength()
                TransferLimits.checkAdvertised(length, TransferLimits.DOCUMENT)
                tempFile.outputStream().use { fos ->
                    body.byteStream().use { ins ->
                        totalBytes = copyBounded(ins, fos, TransferLimits.DOCUMENT) { copied ->
                            if (length > 0) onProgress((copied.toFloat() / length).coerceIn(0f, 1f))
                        }
                    }
                }
                onProgress(1f)
            }

            if (totalBytes == 0L || tempFile.length() == 0L) {
                tempFile.delete()
                return@withContext Result.failure(Exception("El archivo descargado está vacío."))
            }

            // Strict integrity check 2: verify not an HTML error or captcha page
            val headBytes = ByteArray(1024)
            val bytesRead = tempFile.inputStream().use { it.read(headBytes) }
            val headStr = String(headBytes, 0, maxOf(0, bytesRead), Charsets.UTF_8).lowercase()
            if (headStr.contains("<!doctype html") || headStr.contains("<html") || headStr.contains("<head") ||
                headStr.contains("cloudflare") || headStr.contains("404 not found") || headStr.contains("access denied")) {
                tempFile.delete()
                return@withContext Result.failure(
                    Exception("El servidor devolvió una página de error web en lugar del libro.")
                )
            }

            // Detect actual format from magic bytes
            val detectedExt = detectFormatExtension(tempFile)

            // Strict integrity check 3: validate structure (EPUB ZIP or PDF header)
            if (detectedExt == "epub" || (detectedExt == null && ext == "epub")) {
                try {
                    java.util.zip.ZipFile(tempFile).use { zip ->
                        if (zip.size() == 0) {
                            throw Exception("El archivo zip está vacío")
                        }
                        val hasContainer = zip.getEntry("META-INF/container.xml") != null
                        val hasMime = zip.getEntry("mimetype") != null
                        if (!hasContainer && !hasMime) {
                            throw Exception("Estructura EPUB no válida (falta container.xml)")
                        }
                    }
                } catch (ze: Exception) {
                    tempFile.delete()
                    return@withContext Result.failure(
                        Exception("El archivo EPUB descargado está dañado: ${ze.localizedMessage}")
                    )
                }
            } else if (detectedExt == "pdf" || (detectedExt == null && ext == "pdf")) {
                if (!headStr.startsWith("%pdf")) {
                    tempFile.delete()
                    return@withContext Result.failure(
                        Exception("El archivo PDF descargado no tiene una cabecera válida.")
                    )
                }
            }

            if (detectedExt == null) {
                tempFile.delete()
                return@withContext Result.failure(Exception("El archivo descargado no es un formato compatible."))
            }
            val detected = detectedExt
            val targetExt = if (detected == "mobi" && ext.lowercase() in listOf("mobi", "azw", "azw3")) ext.lowercase() else detected
            val importedFile = importDownloadedFile(tempFile, book.title, book.author, targetExt)
            finalFile = importedFile
            tempFile.delete()

            // Auto-index file into Axiom database
            val scanned = StorageScanner.scanSingleFile(context, importedFile)
                ?: error("Downloaded file could not be indexed")

            // Download and attach cover if present
            if (!book.coverUrl.isNullOrBlank()) {
                try {
                    val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
                    val coverFileName = if (!scanned.sha1.isNullOrBlank()) "cover_${scanned.sha1}.jpg" else "cover_${scanned.id}.jpg"
                    val coverFile = File(coversDir, coverFileName)

                    if (downloadCover(book.coverUrl, coverFile)) {
                        val app = context.applicationContext as? OpenReadEraApplication
                        app?.repository?.updateBook(scanned.copy(
                            coverPath = coverFile.absolutePath,
                            title = if (scanned.title == "Desconocido" || scanned.title.isBlank()) book.title else scanned.title,
                            author = if (scanned.author == "Desconocido" || scanned.author.isBlank()) book.author else scanned.author
                        ))
                    }
                } catch (ce: Exception) {
                    ce.printStackTrace()
                }
            }

            try {
                val syncManager = org.readera.openreadera.sync.GoogleDriveSyncManager(context)
                if (syncManager.isDriveConnected() && syncManager.isAutoSyncEnabled) {
                    syncManager.triggerImmediateBackgroundSync()
                }
            } catch (_: Exception) {}

            Result.success(importedFile)
        } catch (e: Exception) {
            tempFile.delete()
            finalFile?.delete()
            Result.failure(e)
        }
    }

    internal fun downloadCover(url: String, target: File, byteLimit: Long = TransferLimits.COVER): Boolean {
        val request = Request.Builder().url(CatalogUrlSecurity.checkedUrl(url))
            .header("User-Agent", "Mozilla/5.0").build()
        val temporary = File.createTempFile(".catalog-cover-", ".tmp", target.parentFile)
        try {
            return requestExecutor.execute(request).use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) return@use false
                TransferLimits.checkAdvertised(body.contentLength(), byteLimit)
                body.byteStream().use { input ->
                    temporary.outputStream().use { copyBounded(input, it, byteLimit) }
                }
                if (temporary.length() == 0L) return@use false
                check(temporary.renameTo(target)) { "Cannot finalize cover" }
                true
            }
        } finally {
            temporary.delete()
        }
    }

    internal fun detectFormatExtension(file: File): String? {
        if (!file.exists() || !file.canRead() || file.length() == 0L) return null
        try {
            val header = ByteArray(68)
            val read = java.io.FileInputStream(file).use { input ->
                var count = 0
                while (count < header.size) {
                    val next = input.read(header, count, header.size - count)
                    if (next < 0) break
                    count += next
                }
                count
            }
            if (read < 1) return null

            // PDF: %PDF
            if (read >= 4 && header[0] == 0x25.toByte() && header[1] == 0x50.toByte() && header[2] == 0x44.toByte() && header[3] == 0x46.toByte()) {
                return "pdf"
            }

            if (read >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) {
                return java.util.zip.ZipFile(file).use { zip ->
                    var hasDocx = false
                    var hasEpubContainer = false
                    var hasImages = false
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name
                        hasDocx = hasDocx || name.equals("word/document.xml", ignoreCase = true)
                        hasEpubContainer = hasEpubContainer || name.equals("META-INF/container.xml", ignoreCase = true)
                        hasImages = hasImages || name.endsWith(".jpg", true) ||
                            name.endsWith(".jpeg", true) || name.endsWith(".png", true) || name.endsWith(".webp", true)
                    }
                    when {
                        hasDocx -> "docx"
                        hasEpubContainer -> "epub"
                        hasImages -> "cbz"
                        else -> null
                    }
                }
            }

            // MOBI: BOOKMOBI at offset 60
            if (read >= 68 &&
                header[60] == 'B'.code.toByte() && header[61] == 'O'.code.toByte() &&
                header[62] == 'O'.code.toByte() && header[63] == 'K'.code.toByte() &&
                header[64] == 'M'.code.toByte() && header[65] == 'O'.code.toByte() &&
                header[66] == 'B'.code.toByte() && header[67] == 'I'.code.toByte()
            ) {
                return "mobi"
            }

            val prefix = StringBuilder()
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            java.io.InputStreamReader(java.io.FileInputStream(file), decoder).use { reader ->
                val chars = CharArray(4096); var n: Int
                while (reader.read(chars).also { n = it } != -1) {
                    for (i in 0 until n) {
                        val c = chars[i]
                        if (c == '\u0000' || (c < ' ' && c != '\n' && c != '\r' && c != '\t')) return null
                    }
                    if (prefix.length < 4096) prefix.append(chars, 0, minOf(n, 4096 - prefix.length))
                }
            }
            val text = prefix.toString().trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            if (FICTION_BOOK_ROOT.containsMatchIn(text)) {
                val xml = Xml.newPullParser()
                xml.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                xml.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                var rootSeen = false
                java.io.FileInputStream(file).use { input ->
                    xml.setInput(input, "UTF-8")
                    while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (!rootSeen && xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                            if (!xml.name.equals("fictionbook", true)) return null
                            rootSeen = true
                        }
                    }
                }
                return if (rootSeen) "fb2" else null
            }
            if (text.contains("<html", ignoreCase = true) ||
                text.contains("<!doctype html", ignoreCase = true) ||
                text.startsWith("<?xml", ignoreCase = true)
            ) return null
            return "txt"
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    suspend fun downloadFallbackByTitle(
        book: CatalogBook,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        // 1. First attempt: LibGen (fast CDN stream for both Spanish and international books)
        try {
            val langPref = if (book.language.contains("espanol", true) || book.language.contains("español", true) || book.language.isBlank()) "Spanish" else "English"
            val resolved = LibGenResolver.searchAndResolveDownload(
                client = client,
                title = book.title,
                preferredExtension = book.extension,
                preferredLanguage = langPref
            )
            if (resolved != null && resolved.url.isNotBlank()) {
                val fallbackBook = book.copy(downloadUrl = resolved.url, extension = resolved.extension)
                val res = download(fallbackBook, onProgress)
                if (res.isSuccess) return@withContext res
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Second attempt: Internet Archive Open Access
        try {
            val cleanTitle = book.title.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s]"), " ").trim()
            val encoded = java.net.URLEncoder.encode("title:($cleanTitle) AND mediatype:(texts)", "UTF-8")
            val iaUrl = "https://archive.org/advancedsearch.php?q=$encoded&fl[]=identifier,title,format&output=json&rows=5"
            val req = Request.Builder()
                .url(iaUrl)
                .header("User-Agent", "Axiom/2.0")
                .build()
            val body = requestExecutor.execute(req).use { resp ->
                if (resp.isSuccessful) resp.body?.string() ?: "" else ""
            }
            if (body.startsWith("{")) {
                val json = org.json.JSONObject(body)
                val docs = json.optJSONObject("response")?.optJSONArray("docs")
                if (docs != null && docs.length() > 0) {
                    for (i in 0 until docs.length()) {
                        val doc = docs.getJSONObject(i)
                        val id = doc.optString("identifier", "")
                        if (id.isNotBlank()) {
                            val directEpub = "https://archive.org/download/$id/$id.epub"
                            val fallbackBook = book.copy(downloadUrl = directEpub, extension = "EPUB")
                            val dlRes = download(fallbackBook, onProgress)
                            if (dlRes.isSuccess) return@withContext dlRes
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Third attempt: Project Gutenberg (Gutendex)
        try {
            val query = java.net.URLEncoder.encode(book.title, "UTF-8")
            val gutenUrl = "https://gutendex.com/books/?search=$query"
            val req = Request.Builder().url(CatalogUrlSecurity.checkedUrl(gutenUrl)).build()
            val body = requestExecutor.execute(req).use { resp ->
                if (resp.isSuccessful) resp.body?.string() ?: "" else ""
            }
            if (body.startsWith("{")) {
                val json = org.json.JSONObject(body)
                val results = json.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val formats = results.getJSONObject(0).optJSONObject("formats")
                    val epub = formats?.optString("application/epub+zip")
                    if (!epub.isNullOrBlank()) {
                        val fallbackBook = book.copy(downloadUrl = epub, extension = "EPUB")
                        val gutenRes = download(fallbackBook, onProgress)
                        if (gutenRes.isSuccess) return@withContext gutenRes
                    }
                }
            }
        } catch (_: Exception) {}

        Result.failure(Exception("No se encontró una copia descargable en repositorios abiertos para '${book.title}'."))
    }
}
internal fun importDownloadedFile(source: File, title: String, author: String, extension: String): File {
    val directory = StorageScanner.getAppBooksDirectory().apply { mkdirs() }
    val safeTitle = title.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s-]"), "").take(40).trim().ifBlank { "Libro" }
    val safeAuthor = author.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s-]"), "").take(30).trim().ifBlank { "Autor" }
    val ext = extension.lowercase().removePrefix(".").ifBlank { error("Missing file extension") }
    val destination = reserveCatalogDestination(directory, safeTitle, safeAuthor, ext)
    try {
        source.inputStream().use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        return destination
    } catch (failure: Exception) {
        destination.delete()
        throw failure
    }
}
internal fun reserveCatalogDestination(directory: File, safeTitle: String, safeAuthor: String, extension: String): File {
    var index = 0
    while (true) {
        val suffix = if (index == 0) "" else " ($index)"
        val candidate = File(directory, "$safeTitle - $safeAuthor$suffix.$extension")
        if (candidate.createNewFile()) return candidate
        index++
    }
}
