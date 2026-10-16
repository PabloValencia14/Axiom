package org.readera.openreadera.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import java.io.Closeable

/** One document owns one client; only model downloads use the network. */
class OnDeviceDocumentTranslator(sourceLanguage: String, targetLanguage: String) : Closeable {
    private val source = requireNotNull(TranslateLanguage.fromLanguageTag(sourceLanguage)) { "Idioma de origen no compatible" }
    private val target = requireNotNull(TranslateLanguage.fromLanguageTag(targetLanguage)) { "Idioma de destino no compatible" }
    private val client = if (source == target) null else Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(source).setTargetLanguage(target).build())
    private var prepared = false

    suspend fun prepare() {
        currentCoroutineContext().ensureActive()
        if (!prepared && source != target) {
            checkNotNull(client).downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).await()
            prepared = true
        }
    }

    suspend fun translateText(text: String): Result<String> = try {
        currentCoroutineContext().ensureActive()
        if (source == target || text.isBlank()) Result.success(text) else {
            prepare()
            val translated = StringBuilder()
            for (chunk in translationRequestChunks(text)) {
                currentCoroutineContext().ensureActive()
                val body = chunk.trim()
                if (body.isEmpty()) translated.append(chunk) else {
                    val response = checkNotNull(client).translate(body).await()
                    currentCoroutineContext().ensureActive()
                    check(response.isNotBlank()) { "El modelo local devolvió una traducción vacía" }
                    translated.append(chunk.takeWhile(Char::isWhitespace))
                    translated.append(response.trim())
                    translated.append(chunk.takeLastWhile(Char::isWhitespace))
                }
            }
            Result.success(translated.toString())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    override fun close() { client?.close() }
}
