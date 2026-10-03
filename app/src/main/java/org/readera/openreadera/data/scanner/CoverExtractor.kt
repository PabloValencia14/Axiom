package org.readera.openreadera.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.repository.BookRepository
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class CoverExtractor(
    private val context: Context,
    private val repository: BookRepository
) {
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    companion object {
        private const val TAG = "CoverExtractor"
    }

    suspend fun extractAndSaveCover(book: Book): Book = withContext(Dispatchers.IO) {
        try {
            val file = File(book.filePath)
            if (!file.exists() || !file.canRead()) return@withContext book

            var updatedBook = book

            // 1. Calculate SHA-1 if missing
            val sha1 = if (book.sha1.isNullOrBlank()) {
                try {
                    computeSha1(file)
                } catch (e: Exception) {
                    null
                }
            } else {
                book.sha1
            }
            if (sha1 != book.sha1) {
                updatedBook = updatedBook.copy(sha1 = sha1)
            }

            // Target cover file based on SHA-1 (if available) or book ID
            val coverFileName = if (!sha1.isNullOrBlank()) "cover_${sha1}.jpg" else "cover_${book.id}.jpg"
            val targetCoverFile = File(coversDir, coverFileName)

            // 2. Check if official ReadEra cover exists for this book in ReadEra storage
            var officialCoverLoaded = false
            if (!sha1.isNullOrBlank() && sha1.length >= 2) {
                val primaryStorage = Environment.getExternalStorageDirectory()
                val readEraCoversDir = File(primaryStorage, "Documents/Conocimiento/04_Recursos/Readera/Covers")
                val subDir = File(readEraCoversDir, sha1.substring(0, 2))
                val officialCover = File(subDir, "$sha1-ReadEra.jpg")
                if (officialCover.exists() && officialCover.canRead() && officialCover.length() > 0) {
                    try {
                        officialCover.copyTo(targetCoverFile, overwrite = true)
                        updatedBook = updatedBook.copy(coverPath = targetCoverFile.absolutePath)
                        officialCoverLoaded = true
                        Log.i(TAG, "Applied official ReadEra cover for '${book.title}' ($sha1)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error copying official ReadEra cover", e)
                    }
                }
            }

            // 3. Fallback to format-specific extraction if no official ReadEra cover was found
            if (!officialCoverLoaded) {
                when (book.format.uppercase()) {
                    "PDF" -> {
                        extractPdfCover(file, targetCoverFile)
                        if (targetCoverFile.exists()) {
                            updatedBook = updatedBook.copy(coverPath = targetCoverFile.absolutePath)
                        }
                    }
                    "EPUB" -> {
                        val result = extractEpub(file, targetCoverFile)
                        var title = book.title
                        var author = book.author
                        if (!result.title.isNullOrBlank() && result.title.length > 1) {
                            title = result.title.trim()
                        }
                        if (!result.author.isNullOrBlank() && result.author.length > 1 && result.author != "Desconocido") {
                            author = result.author.trim()
                        }
                        val coverPath = if (targetCoverFile.exists()) targetCoverFile.absolutePath else book.coverPath

                        updatedBook = updatedBook.copy(
                            title = title,
                            author = author,
                            coverPath = coverPath,
                            language = result.language ?: book.language ?: "Español",
                            description = result.description ?: book.description,
                            genre = result.genre ?: book.genre,
                            series = result.series ?: book.series
                        )
                    }
                    "CÓMIC CBZ", "CBZ" -> {
                        extractCbzCover(file, targetCoverFile)
                        if (targetCoverFile.exists()) {
                            updatedBook = updatedBook.copy(coverPath = targetCoverFile.absolutePath)
                        }
                    }
                    else -> {
                        // For MOBI, TXT, etc.
                    }
                }
            } else {
                // If official cover was found, for EPUB also extract metadata (title, author, etc.) without overwriting cover
                if (book.format.uppercase() == "EPUB") {
                    val dummyTarget = File(context.cacheDir, "temp_cover_${sha1}.jpg")
                    try {
                        val result = extractEpub(file, dummyTarget)
                        var title = book.title
                        var author = book.author
                        if (!result.title.isNullOrBlank() && result.title.length > 1) {
                            title = result.title.trim()
                        }
                        if (!result.author.isNullOrBlank() && result.author.length > 1 && result.author != "Desconocido") {
                            author = result.author.trim()
                        }
                        updatedBook = updatedBook.copy(
                            title = title,
                            author = author,
                            language = result.language ?: book.language ?: "Español",
                            description = result.description ?: book.description,
                            genre = result.genre ?: book.genre,
                            series = result.series ?: book.series
                        )
                    } finally {
                        if (dummyTarget.exists()) dummyTarget.delete()
                    }
                }
            }

            if (updatedBook != book) {
                // Preserve concurrently changed progress, flags and reviews;
                // this extractor only owns cover/metadata fields.
                repository.updateCoverAndMetadata(updatedBook)
            }
            return@withContext updatedBook
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting cover for ${book.filePath}", e)
            return@withContext book
        }
    }

    private fun computeSha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered().use { fis ->
            val buffer = ByteArray(16384)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractPdfCover(pdfFile: File, targetCoverFile: File) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount > 0) {
                page = renderer.openPage(0)
                val targetWidth = 360
                val targetHeight = ((targetWidth.toFloat() / page.width) * page.height).toInt().coerceIn(360, 600)
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                FileOutputStream(targetCoverFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render PDF cover: ${e.message}")
        } finally {
            try { page?.close() } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    private fun decodeSampledCover(openStream: () -> InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val scale = minOf(360f / bounds.outWidth, 600f / bounds.outHeight)
        val requiredWidth = (bounds.outWidth * scale).toInt().coerceAtLeast(1)
        val requiredHeight = (bounds.outHeight * scale).toInt().coerceAtLeast(1)
        var sampleSize = 1
        while (sampleSize <= Int.MAX_VALUE / 2) {
            val nextSampleSize = sampleSize * 2
            if (bounds.outWidth / nextSampleSize < requiredWidth ||
                bounds.outHeight / nextSampleSize < requiredHeight
            ) break
            sampleSize = nextSampleSize
        }

        val bitmap = openStream().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: return null
        val outputScale = minOf(1f, 360f / bitmap.width, 600f / bitmap.height)
        if (outputScale >= 1f) return bitmap
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * outputScale).toInt().coerceAtLeast(1),
            (bitmap.height * outputScale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun extractCbzCover(cbzFile: File, targetCoverFile: File) {
        try {
            ZipFile(cbzFile).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp") && !it.name.startsWith("__MACOSX") }
                    .sortedBy { it.name }
                    .toList()
                val first = entries.firstOrNull() ?: return
                val bitmap = decodeSampledCover { zip.getInputStream(first) } ?: return
                try {
                    FileOutputStream(targetCoverFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting CBZ cover: ${e.message}")
        }
    }

    private data class EpubResult(
        val title: String? = null,
        val author: String? = null,
        val language: String? = null,
        val description: String? = null,
        val genre: String? = null,
        val series: String? = null,
        val coverSaved: Boolean = false
    )

    private fun extractEpub(epubFile: File, targetCoverFile: File): EpubResult {
        var title: String? = null
        var author: String? = null
        var language: String? = null
        var description: String? = null
        var genre: String? = null
        var series: String? = null
        var coverSaved = false

        try {
            ZipFile(epubFile).use { zip ->
                val containerEntry = zip.getEntry("META-INF/container.xml") ?: return EpubResult()
                val opfPath = getOpfPath(zip.getInputStream(containerEntry)) ?: return EpubResult()

                val opfEntry = zip.getEntry(opfPath) ?: zip.getEntry(opfPath.replace("\\", "/")) ?: return EpubResult()
                val opfDir = File(opfPath).parent?.replace("\\", "/") ?: ""

                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(zip.getInputStream(opfEntry), "UTF-8")

                var eventType = parser.eventType
                var coverId: String? = null
                var coverHref: String? = null
                val manifestItems = mutableMapOf<String, String>() // id -> href

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        when (parser.name.lowercase()) {
                            "dc:title", "title" -> {
                                if (title == null) {
                                    title = parser.nextText()
                                }
                            }
                            "dc:creator", "creator" -> {
                                if (author == null) {
                                    author = parser.nextText()
                                }
                            }
                            "dc:language", "language" -> {
                                if (language == null) {
                                    language = parser.nextText()
                                }
                            }
                            "dc:description", "description" -> {
                                if (description == null) {
                                    description = parser.nextText()
                                }
                            }
                            "dc:subject", "subject" -> {
                                if (genre == null) {
                                    genre = parser.nextText()
                                }
                            }
                            "meta" -> {
                                val name = parser.getAttributeValue(null, "name")
                                if (name.equals("cover", ignoreCase = true)) {
                                    coverId = parser.getAttributeValue(null, "content")
                                }
                                if (name.equals("calibre:series", ignoreCase = true)) {
                                    series = parser.getAttributeValue(null, "content")
                                }
                            }
                            "item" -> {
                                val id = parser.getAttributeValue(null, "id")
                                val href = parser.getAttributeValue(null, "href")
                                val properties = parser.getAttributeValue(null, "properties")
                                val mediaType = parser.getAttributeValue(null, "media-type")

                                if (id != null && href != null) {
                                    manifestItems[id] = href
                                }

                                if (properties != null && properties.contains("cover-image", ignoreCase = true) && href != null) {
                                    coverHref = href
                                } else if (id != null && (id.equals("cover", ignoreCase = true) || id.contains("cover-image", ignoreCase = true)) && href != null) {
                                    coverHref = href
                                } else if (coverHref == null && href != null && mediaType?.startsWith("image/") == true && href.contains("cover", ignoreCase = true)) {
                                    coverHref = href
                                }
                            }
                        }
                    }
                    eventType = parser.next()
                }

                if (coverHref == null && coverId != null) {
                    coverHref = manifestItems[coverId]
                }

                var coverEntry: ZipEntry? = null
                if (coverHref != null) {
                    val fullHref = if (opfDir.isNotEmpty()) "$opfDir/$coverHref" else coverHref
                    val cleanHref = fullHref.replace("//", "/").replace("/./", "/")
                    coverEntry = zip.getEntry(cleanHref) ?: zip.getEntry(coverHref)
                }

                // Fallback: search common cover entries
                if (coverEntry == null) {
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name.lowercase()
                        if ((name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) &&
                            (name.contains("cover") || name.contains("portada") || name.contains("front"))) {
                            coverEntry = entry
                            break
                        }
                    }
                }

                coverEntry?.let { entry ->
                    val bitmap = decodeSampledCover { zip.getInputStream(entry) }
                    if (bitmap != null) {
                        try {
                            FileOutputStream(targetCoverFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                            }
                            coverSaved = true
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse EPUB metadata or cover: ${e.message}")
        }

        return EpubResult(
            title = title,
            author = author,
            language = language,
            description = description,
            genre = genre,
            series = series,
            coverSaved = coverSaved
        )
    }

    private fun getOpfPath(containerStream: InputStream): String? {
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(containerStream, "UTF-8")
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.equals("rootfile", ignoreCase = true)) {
                    return parser.getAttributeValue(null, "full-path")
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {}
        return null
    }
}
