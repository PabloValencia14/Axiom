package org.readera.openreadera.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.readera.openreadera.data.security.OpenRouterKeyStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class OpenRouterMessage(val role: String, val content: String)

class OpenRouterClient(private val keyStore: OpenRouterKeyStore) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    suspend fun complete(messages: List<OpenRouterMessage>): String = withContext(Dispatchers.IO) {
        require(messages.isNotEmpty() && messages.size <= MAX_MESSAGES) { "Message count is outside supported limits" }
        var totalChars = 0
        val jsonMessages = JSONArray()
        messages.forEach { message ->
            require(message.role in ROLES) { "Unsupported message role" }
            require(message.content.isNotBlank() && message.content.length <= MAX_MESSAGE_CHARS) {
                "Message content is outside supported limits"
            }
            totalChars += message.content.length
            require(totalChars <= MAX_TOTAL_CHARS) { "Conversation is too large" }
            jsonMessages.put(JSONObject().put("role", message.role).put("content", message.content))
        }
        val payload = JSONObject()
            .put("model", MODEL)
            .put("messages", jsonMessages)
            .toString()
        require(payload.length <= MAX_REQUEST_CHARS) { "Request is too large" }
        val key = keyStore.get() ?: throw IllegalStateException("Configure an OpenRouter API key first")
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("X-Title", APP_TITLE)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 429) throw OpenRouterRateLimitException()
            val body = response.body ?: throw IOException("OpenRouter returned an empty response")
            val text = body.byteStream().use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var totalBytes = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    if (totalBytes > MAX_RESPONSE_BYTES) throw IOException("OpenRouter response is too large")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            if (!response.isSuccessful) throw IOException("OpenRouter request failed (${response.code})")
            try {
                val content = JSONObject(text).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content")
                if (content.length > MAX_RESPONSE_CHARS) throw IOException("OpenRouter response is too large")
                content
            } catch (error: IOException) {
                throw error
            } catch (_: Exception) {
                throw IOException("OpenRouter returned an invalid response")
            }
        }
    }

    class OpenRouterRateLimitException : IOException("OpenRouter rate limit reached. Please try again later.")

    private companion object {
        const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        const val MODEL = "openrouter/free"
        const val APP_TITLE = "Axiom"
        const val MAX_MESSAGES = 40
        const val MAX_MESSAGE_CHARS = 16_384
        const val MAX_TOTAL_CHARS = 65_536
        const val MAX_REQUEST_CHARS = 100_000
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val MAX_RESPONSE_CHARS = 200_000
        val ROLES = setOf("system", "user", "assistant")
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
