package org.readera.openreadera.translation

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readera.openreadera.data.pdf.PdfDocumentTranslator
import org.readera.openreadera.engine.DocumentFormat
import org.readera.openreadera.engine.EngineManager
import org.readera.openreadera.engine.MobiConverter
import java.io.File
import java.util.UUID

/** Owns routing and all-or-nothing file publication; adapters never index or sync a document. */
class DocumentTranslationCoordinator internal constructor(
    private val cacheDirectory: File,
    private val resolveFormat: (String) -> DocumentFormat,
    private val translateNative: suspend (ResolvedTranslationSource, File, String, NativeTextTranslator, (Int, Int) -> Unit) -> Unit,
    private val convertKindle: (File, File) -> Boolean
) {
    constructor(context: Context) : this(
        context.cacheDir,
        EngineManager(context)::resolveDocumentFormat,
        { source, output, language, translate, progress ->
            if (source.format == DocumentFormat.PDF) {
                PdfDocumentTranslator(context).translateCopy(source.file, output, language, translate, progress)
            } else {
                PackageDocumentTranslator(context).translateCopy(source, output, language, translate, progress)
            }
        },
        { source, output ->
            MobiConverter.isAvailable && MobiConverter.nativeConvertMobiToEpub(source.absolutePath, output.absolutePath)
        }
    )

    fun resolveSource(path: String): ResolvedTranslationSource {
        val file = File(path).canonicalFile
        if (!file.isFile || !file.canRead()) throw TranslationRejectedException("No se puede leer el archivo original.")
        val format = resolveFormat(file.path)
        val suffix = when (format) {
            DocumentFormat.PDF, DocumentFormat.EPUB, DocumentFormat.DOCX, DocumentFormat.FB2 -> format.extension
            DocumentFormat.TXT -> file.extension.lowercase().takeIf { it in setOf("txt", "md", "log") } ?: "txt"
            DocumentFormat.MOBI, DocumentFormat.AZW, DocumentFormat.AZW3 -> format.extension
            else -> throw TranslationRejectedException(
                "${format.displayName}: no admite una copia traducida con texto nativo seleccionable. No se reconstruyen escaneos ni imágenes."
            )
        }
        return ResolvedTranslationSource(file, format, suffix)
    }

    suspend fun translateCopy(
        source: ResolvedTranslationSource,
        outputDirectory: File,
        title: String,
        targetLanguage: String,
        kindleConversionConfirmed: Boolean = false,
        translate: NativeTextTranslator,
        onProgress: (Int, Int) -> Unit
    ): File {
        var partial: File? = null
        var converted: File? = null
        var finalCopy: File? = null
        try {
            return withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val actual = resolveSource(source.file.path)
                if (actual != source) throw TranslationRejectedException("El formato del original cambió. Vuelve a abrir la traducción para confirmar el formato real.")
                val nativeSource = if (requiresKindleConversion(actual.format)) {
                    if (!kindleConversionConfirmed) throw TranslationRejectedException("Confirma explícitamente la conversión de Kindle a EPUB para esta exportación.")
                    val temp = File.createTempFile("kindle-translation-", ".epub", cacheDirectory)
                    converted = temp
                    if (!convertKindle(actual.file, temp)) throw TranslationRejectedException("No se pudo convertir el Kindle sin DRM a EPUB. El original no se ha modificado.")
                    currentCoroutineContext().ensureActive()
                    if (resolveFormat(temp.path) != DocumentFormat.EPUB) throw TranslationRejectedException("La conversión Kindle no produjo un EPUB válido.")
                    ResolvedTranslationSource(temp, DocumentFormat.EPUB, "epub")
                } else actual
                if (!outputDirectory.isDirectory && !outputDirectory.mkdirs()) throw java.io.IOException("No se puede crear la carpeta de destino.")
                val part = File(outputDirectory, ".native-translation-${UUID.randomUUID()}.part")
                if (part.exists()) throw java.io.IOException("La ruta temporal ya existe.")
                partial = part
                val checkedTranslator: NativeTextTranslator = { text, language ->
                    currentCoroutineContext().ensureActive()
                    val result = translate(text, language)
                    val translated = result.getOrThrow()
                    currentCoroutineContext().ensureActive()
                    if (translated.isBlank()) throw TranslationRejectedException("El proveedor devolvió una traducción vacía. No se ha generado una copia incompleta.")
                    result
                }
                translateNative(nativeSource, part, targetLanguage, checkedTranslator, onProgress)
                currentCoroutineContext().ensureActive()
                val partialLength = part.length()
                if (!part.isFile || partialLength == 0L) throw TranslationRejectedException("No se generó una copia traducida válida.")
                val safeTitle = title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(40).ifBlank { "Documento" }
                val safeLanguage = targetLanguage.filter { it.isLetterOrDigit() || it == '-' }.take(16)
                val destination = File(outputDirectory, "[${safeLanguage.uppercase()}] $safeTitle-${UUID.randomUUID()}.${nativeSource.nativeExtension}")
                // Reserve exclusively, then rename only over our own reservation (API 21+).
                if (!destination.createNewFile()) throw java.io.IOException("La copia de destino ya existe.")
                finalCopy = destination
                Os.rename(part.absolutePath, destination.absolutePath)
                if (part.exists()) {
                    if (!destination.delete() || !part.renameTo(destination)) {
                        throw java.io.IOException("No se pudo publicar la copia traducida.")
                    }
                }
                if (!destination.isFile || destination.length() != partialLength) {
                    throw java.io.IOException("La copia traducida publicada está incompleta.")
                }
                currentCoroutineContext().ensureActive()
                destination
            }
        } catch (failure: Throwable) {
            finalCopy?.delete()
            throw failure
        } finally {
            partial?.delete()
            converted?.delete()
        }
    }

    companion object {
        fun requiresKindleConversion(format: DocumentFormat): Boolean =
            format == DocumentFormat.MOBI || format == DocumentFormat.AZW || format == DocumentFormat.AZW3
    }
}
