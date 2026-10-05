package org.readera.openreadera.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

object GoogleTranslateService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single"

    val supportedLanguages = listOf(
        "es" to "Español",
        "en" to "Inglés",
        "fr" to "Francés",
        "de" to "Alemán",
        "it" to "Italiano",
        "pt" to "Portugués",
        "ru" to "Ruso",
        "zh" to "Chino",
        "ja" to "Japonés"
    )

    suspend fun translateText(
        text: String,
        targetLang: String = "es",
        sourceLang: String = "auto"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            if (text.isBlank()) return@withContext Result.success("")
            val translated = StringBuilder()
            for (chunk in translationRequestChunks(text)) {
                currentCoroutineContext().ensureActive()
                val core = chunk.trim()
                if (core.isEmpty()) {
                    translated.append(chunk)
                    continue
                }
                val leading = chunk.indexOfFirst { !it.isWhitespace() }
                val trailing = chunk.indexOfLast { !it.isWhitespace() } + 1
                val result = translateChunk(core, targetLang, sourceLang)
                if (result.isBlank()) throw IOException("El servicio no devolvió una traducción.")
                translated.append(chunk, 0, leading)
                translated.append(result.trim())
                translated.append(chunk, trailing, chunk.length)
            }
            Result.success(translated.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun translateChunk(chunk: String, targetLang: String, sourceLang: String): String {
        val formBody = FormBody.Builder()
            .add("client", "gtx")
            .add("sl", sourceLang)
            .add("tl", targetLang)
            .add("dt", "t")
            .add("q", chunk)
            .build()
        val request = Request.Builder()
            .url(TRANSLATE_URL)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .post(formBody)
            .build()
        return awaitTranslation(client.newCall(request))
    }

    internal suspend fun awaitTranslation(call: Call): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                val translated = runCatching {
                    response.use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} al traducir")
                        val json = JSONArray(resp.body?.string() ?: "")
                        val sentences = json.optJSONArray(0) ?: throw IOException("Respuesta de traducción inválida.")
                        buildString {
                            for (i in 0 until sentences.length()) {
                                val item = sentences.optJSONArray(i) ?: throw IOException("Segmento de traducción inválido.")
                                val segment = item.optString(0, "")
                                if (segment.isEmpty()) throw IOException("Segmento de traducción vacío.")
                                append(segment)
                            }
                        }
                    }
                }
                continuation.resumeWith(translated)
            }
        })
    }
}

/** Bounded POST bodies even for one huge paragraph; never split a UTF-16 surrogate pair. */
internal fun translationRequestChunks(text: String): Sequence<String> = sequence {
    var start = 0
    while (start < text.length) {
        var end = minOf(start + 2500, text.length)
        if (end < text.length) {
            if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            var boundary = end
            while (boundary > start + 1250 && !text[boundary - 1].isWhitespace()) boundary--
            if (boundary > start + 1250) end = boundary
        }
        yield(text.substring(start, end))
        start = end
    }
}
