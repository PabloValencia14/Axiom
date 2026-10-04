package org.readera.openreadera.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readera.openreadera.data.model.Book
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipFile

enum class DocumentCategory(val collectionName: String, val displayName: String) {
    BOOK("Libros", "Libro"),
    GRAPHIC_NOVEL("Novela-grafica", "Novela gráfica"),
    DOCUMENT("Documentos de estudio", "Documento"),
    PRESENTATION("Presentaciones", "Presentación"),
    PAPER("Papers", "Paper")
}

data class CategoryDecision(
    val category: DocumentCategory,
    val confidence: Float,
    val reason: String
)

/** Classifies locally from format, metadata, archive contents and a small PDF preview. */
class DocumentCategoryClassifier(context: Context) {
    private val cache = context.applicationContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)

    suspend fun classify(book: Book): CategoryDecision = withContext(Dispatchers.IO) {
        manualDecision(cache, book)?.let { return@withContext it }
        val key = cacheKey(book.filePath)
        val signature = signature(book)
        cached(cache.getString(key, null), signature)?.let { return@withContext it }

        // ponytail: heuristic classifier; replace with an opt-in ML model only if false positives justify its cost.
        val quick = quick(book)
        val decision = when {
            quick.confidence >= 0.9f -> quick
            isGraphicEpub(book) -> CategoryDecision(DocumentCategory.GRAPHIC_NOVEL, 0.84f, "EPUB con muchas imágenes")
            else -> inspectPdf(book, quick)
        }
        cache.edit().putString(key, serialize(signature, decision)).apply()
        decision
    }

    companion object {
        const val CACHE_PREFS = "document_category_cache"
        private const val CACHE_VERSION = "v7"

        fun quick(book: Book): CategoryDecision {
            val format = book.format.lowercase(Locale.ROOT)
            val extension = File(book.filePath).extension.lowercase(Locale.ROOT)
            val haystack = normalize(
                listOf(
                    book.title,
                    File(book.filePath).nameWithoutExtension,
                    book.genre,
                    book.series
                ).filterNotNull().joinToString(" ")
            )

            if (extension == "cbr" || extension == "cbz" || format.contains("comic") || format.contains("cómic")) {
                return CategoryDecision(DocumentCategory.GRAPHIC_NOVEL, 0.99f, "Formato de cómic")
            }
            if (containsAny(haystack, GRAPHIC_TERMS)) {
                return CategoryDecision(DocumentCategory.GRAPHIC_NOVEL, 0.93f, "Título o metadatos de novela gráfica")
            }
            if (containsAny(haystack, PRESENTATION_TERMS)) {
                return CategoryDecision(DocumentCategory.PRESENTATION, 0.9f, "Título o metadatos de presentación")
            }
            if (containsAny(haystack, PAPER_TERMS)) {
                return CategoryDecision(DocumentCategory.PAPER, 0.9f, "Título o metadatos académicos")
            }
            if (containsAny(haystack, DOCUMENT_TERMS)) {
                return CategoryDecision(DocumentCategory.DOCUMENT, 0.9f, "Título o metadatos de estudio")
            }
            if (containsAny(haystack, BOOK_TERMS)) {
                return CategoryDecision(DocumentCategory.BOOK, 0.82f, "Título o metadatos de libro")
            }
            if (extension in DOCUMENT_EXTENSIONS || format in DOCUMENT_FORMATS) {
                return CategoryDecision(DocumentCategory.DOCUMENT, 0.58f, "Formato habitual de documento")
            }
            return CategoryDecision(DocumentCategory.BOOK, 0.6f, "Formato y metadatos sin señal dominante")
        }

        fun cachedOrQuick(context: Context, book: Book): CategoryDecision {
            val prefs = context.applicationContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            manualDecision(prefs, book)?.let { return it }
            return cached(prefs.getString(cacheKey(book.filePath), null), signature(book)) ?: quick(book)
        }

        fun manualCategory(context: Context, book: Book): DocumentCategory? =
            manualDecision(context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE), book)?.category

        suspend fun setManualCategory(context: Context, book: Book, category: DocumentCategory?) = withContext(Dispatchers.IO) {
            setManualCategoryBlocking(context, book, category)
        }

        internal fun setManualCategoryBlocking(context: Context, book: Book, category: DocumentCategory?) {
            val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            check(prefs.edit().putString("manual_${cacheKey(book.filePath)}", category?.name).commit()) {
                "No se pudo guardar la categoría"
            }
        }

        private fun manualDecision(prefs: android.content.SharedPreferences, book: Book): CategoryDecision? {
            val name = prefs.getString("manual_${cacheKey(book.filePath)}", null)
            val category = when (name) {
                "STUDY_DOCUMENT" -> DocumentCategory.DOCUMENT
                else -> DocumentCategory.entries.find { it.name == name } ?: return null
            }
            return CategoryDecision(category, 1f, "Elegida por ti")
        }

        private val GRAPHIC_TERMS = listOf(
            "comic", "comics", "manga", "manhwa", "tebeo", "tebeos", "novela grafica",
            "graphic novel", "bande dessinee", "bandes dessinees", "persepolis", "vendetta"
        )
        private val PRESENTATION_TERMS = listOf(
            "slide", "slides", "diapositiva", "diapositivas", "presentacion", "presentation"
        )
        private val PAPER_TERMS = listOf(
            "paper", "papers", "academic", "academico", "article", "articulo", "research",
            "arxiv", "ieee", "tesis", "thesis", "dissertation"
        )
        private val DOCUMENT_TERMS = listOf(
            "apuntes", "lecture", "tema", "practica", "ejercicio", "manual", "course", "clase",
            "informe", "report", "study", "estudio", "redes", "arquitectura", "algorit",
            "programacion", "matematic"
        )
        private val BOOK_TERMS = listOf(
            "novel", "novela", "libro", "book", "roman", "fiction", "ficcion", "poesia", "poetry",
            "cuento", "story", "literatura", "literature", "thriller", "fantasy", "fantasia", "sci fi"
        )
        private val DOCUMENT_EXTENSIONS = setOf("pdf", "djvu", "djv", "doc", "docx", "rtf", "txt", "chm")
        private val DOCUMENT_FORMATS = setOf("pdf", "djvu", "word doc", "word docx", "rtf", "texto", "chm")
        private val PREFIX_TERMS = setOf("algorit", "matematic")

        private fun inspectPdf(book: Book, quick: CategoryDecision): CategoryDecision {
            if (File(book.filePath).extension.lowercase(Locale.ROOT) != "pdf") return quick
            val signal = inspectPdfFile(File(book.filePath)) ?: return quick
            if (quick.category == DocumentCategory.GRAPHIC_NOVEL ||
                quick.category == DocumentCategory.PRESENTATION ||
                quick.category == DocumentCategory.PAPER
            ) return quick
            if (signal.aspectRatio > 1.35f) {
                return CategoryDecision(DocumentCategory.PRESENTATION, 0.84f, "PDF apaisado, probable presentación")
            }
            if (quick.category != DocumentCategory.DOCUMENT || quick.confidence < 0.8f) {
                if (signal.inkRatio > 0.72f && signal.colorRatio > 0.16f) {
                    return CategoryDecision(DocumentCategory.GRAPHIC_NOVEL, 0.76f, "PDF con página visualmente ilustrada")
                }
            }
            return quick
        }

        private fun isGraphicEpub(book: Book): Boolean {
            if (File(book.filePath).extension.lowercase(Locale.ROOT) != "epub") return false
            return try {
                ZipFile(book.filePath).use { zip ->
                    var imageCount = 0
                    var imageBytes = 0L
                    var textBytes = 0L
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name.lowercase(Locale.ROOT)
                        val size = entry.size.coerceAtLeast(0L)
                        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) {
                            imageCount++
                            imageBytes += size
                        } else if (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) {
                            textBytes += size
                        }
                    }
                    imageCount >= 40 && textBytes < 300_000L && (textBytes == 0L || imageBytes >= textBytes * 2)
                }
            } catch (_: Exception) {
                false
            }
        }

        private fun inspectPdfFile(file: File): PdfSignal? {
            if (!file.exists() || !file.canRead()) return null
            return try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        if (renderer.pageCount == 0) return null
                        renderer.openPage(0).use { page ->
                            val width = 160
                            val height = (width * page.height.toFloat() / page.width.coerceAtLeast(1)).toInt().coerceIn(80, 260)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            var samples = 0
                            var ink = 0
                            var colour = 0
                            var y = 0
                            while (y < height) {
                                var x = 0
                                while (x < width) {
                                    val pixel = bitmap.getPixel(x, y)
                                    val red = Color.red(pixel)
                                    val green = Color.green(pixel)
                                    val blue = Color.blue(pixel)
                                    if (red < 242 || green < 242 || blue < 242) ink++
                                    if (maxOf(red, green, blue) - minOf(red, green, blue) > 45 && minOf(red, green, blue) < 230) colour++
                                    samples++
                                    x += 3
                                }
                                y += 3
                            }
                            bitmap.recycle()
                            PdfSignal(
                                aspectRatio = page.width.toFloat() / page.height.coerceAtLeast(1),
                                inkRatio = ink.toFloat() / samples.coerceAtLeast(1),
                                colorRatio = colour.toFloat() / samples.coerceAtLeast(1)
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")

        private fun containsAny(value: String, terms: List<String>): Boolean {
            val normalized = normalize(value)
            val tokens = normalized.split(Regex("[^a-z0-9]+"))
            return terms.any { rawTerm ->
                val term = normalize(rawTerm)
                if (term.contains(' ')) {
                    normalized.contains(term)
                } else {
                    tokens.any { token -> token == term || (term in PREFIX_TERMS && token.startsWith(term)) }
                }
            }
        }

        private fun signature(book: Book): String {
            val file = File(book.filePath)
            return "$CACHE_VERSION:${file.length()}:${file.lastModified()}:${listOf(book.title, book.genre, book.series, book.format).hashCode()}"
        }

        private fun cacheKey(path: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray())
            return "category_${digest.joinToString("") { "%02x".format(it) }}"
        }

        private fun serialize(signature: String, decision: CategoryDecision): String =
            "$signature|${decision.category.name}|${decision.confidence}|${decision.reason.replace('|', ' ')}"

        private fun cached(raw: String?, signature: String): CategoryDecision? {
            val parts = raw?.split('|', limit = 4) ?: return null
            if (parts.size < 4 || parts[0] != signature) return null
            val category = DocumentCategory.entries.find { it.name == parts[1] } ?: return null
            return CategoryDecision(category, parts[2].toFloatOrNull() ?: return null, parts[3])
        }

        private data class PdfSignal(val aspectRatio: Float, val inkRatio: Float, val colorRatio: Float)
    }
}
