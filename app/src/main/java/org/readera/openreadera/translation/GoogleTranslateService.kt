package org.readera.openreadera.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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

    /**
     * Translates a block of text into [targetLang].
     * Supports multi-paragraph text by chunking if needed.
     */
    suspend fun translateText(
        text: String,
        targetLang: String = "es",
        sourceLang: String = "auto"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val clean = text.trim()
            if (clean.isBlank()) return@withContext Result.success("")

            // For texts under 2500 characters, translate in a single POST request
            if (clean.length <= 2500) {
                val res = translateChunk(clean, targetLang, sourceLang)
                return@withContext Result.success(res)
            }

            // For longer texts, chunk by paragraphs/sentences
            val paragraphs = clean.split("\n\n")
            val translatedParagraphs = mutableListOf<String>()
            val currentChunk = StringBuilder()

            for (p in paragraphs) {
                if (currentChunk.length + p.length > 2000) {
                    val res = translateChunk(currentChunk.toString(), targetLang, sourceLang)
                    translatedParagraphs.add(res)
                    currentChunk.clear()
                }
                if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
                currentChunk.append(p)
            }
            if (currentChunk.isNotEmpty()) {
                val res = translateChunk(currentChunk.toString(), targetLang, sourceLang)
                translatedParagraphs.add(res)
            }

            Result.success(translatedParagraphs.joinToString("\n\n"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun translateChunk(chunk: String, targetLang: String, sourceLang: String): String {
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

        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("HTTP ${resp.code} al traducir")
            }
            val body = resp.body?.string() ?: ""
            val json = JSONArray(body)
            val sentences = json.optJSONArray(0) ?: return ""

            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val item = sentences.optJSONArray(i)
                if (item != null) {
                    val translatedSegment = item.optString(0, "")
                    sb.append(translatedSegment)
                }
            }
            return sb.toString()
        }
    }
}
