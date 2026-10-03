package org.readera.openreadera.manga

import android.content.Context
import android.os.Environment
import android.util.Log
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class MangaItem(
    val id: String,
    val title: String,
    val description: String,
    val coverUrl: String?,
    val status: String,
    val tags: List<String>
)

data class MangaChapter(
    val id: String,
    val chapter: String,
    val title: String,
    val language: String,
    val pagesCount: Int
)

object MangaDexClient {

    private const val TAG = "MangaDexClient"
    private const val BASE_URL = "https://api.mangadex.org"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun searchManga(query: String, limit: Int = 24): Result<List<MangaItem>> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$BASE_URL/manga?title=$encoded&limit=$limit&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive&order[relevance]=desc"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Axiom/1.0 (Android Tablet; Reading Assistant)")
                .build()

            val response = client.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${resp.code} en MangaDex"))
                }
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: return@withContext Result.success(emptyList())

                val results = mutableListOf<MangaItem>()
                for (i in 0 until data.length()) {
                    val m = data.getJSONObject(i)
                    val id = m.getString("id")
                    val attrs = m.getJSONObject("attributes")

                    // Extract title (prefer 'en', or first available)
                    val titleObj = attrs.optJSONObject("title")
                    val title = titleObj?.optString("en")
                        ?: titleObj?.optString("es")
                        ?: titleObj?.optString("ja-ro")
                        ?: titleObj?.keys()?.asSequence()?.firstOrNull()?.let { titleObj.optString(it) }
                        ?: "Manga sin título"

                    // Extract description (prefer 'es', then 'en')
                    val descObj = attrs.optJSONObject("description")
                    val desc = descObj?.optString("es")
                        ?: descObj?.optString("en")
                        ?: ""

                    val status = attrs.optString("status", "")

                    // Extract tags
                    val tagsList = mutableListOf<String>()
                    val tagsArr = attrs.optJSONArray("tags")
                    if (tagsArr != null) {
                        for (t in 0 until minOf(4, tagsArr.length())) {
                            val tagAttr = tagsArr.getJSONObject(t).optJSONObject("attributes")
                            val tagName = tagAttr?.optJSONObject("name")?.optString("en")
                            if (!tagName.isNullOrBlank()) tagsList.add(tagName)
                        }
                    }

                    // Extract cover art from relationships
                    var coverFileName: String? = null
                    val rels = m.optJSONArray("relationships")
                    if (rels != null) {
                        for (r in 0 until rels.length()) {
                            val rel = rels.getJSONObject(r)
                            if (rel.optString("type") == "cover_art") {
                                coverFileName = rel.optJSONObject("attributes")?.optString("fileName")
                            }
                        }
                    }

                    val coverUrl = if (coverFileName != null) {
                        "https://uploads.mangadex.org/covers/$id/$coverFileName.512.jpg"
                    } else null

                    results.add(
                        MangaItem(
                            id = id,
                            title = title,
                            description = desc,
                            coverUrl = coverUrl,
                            status = status,
                            tags = tagsList
                        )
                    )
                }

                Result.success(results)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching MangaDex", e)
            Result.failure(e)
        }
    }

    suspend fun getChapters(mangaId: String, preferredLanguage: String = "es"): Result<List<MangaChapter>> = withContext(Dispatchers.IO) {
        try {
            val langParams = when (preferredLanguage) {
                "es" -> "&translatedLanguage[]=es&translatedLanguage[]=es-la"
                "en" -> "&translatedLanguage[]=en"
                else -> "&translatedLanguage[]=es&translatedLanguage[]=es-la&translatedLanguage[]=en"
            }
            val url = "$BASE_URL/manga/$mangaId/feed?limit=100&order[chapter]=desc$langParams"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Axiom/1.0 (Android Tablet; Reading Assistant)")
                .build()

            val response = client.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${resp.code} al obtener capítulos"))
                }
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: return@withContext Result.success(emptyList())

                val chapters = mutableListOf<MangaChapter>()
                for (i in 0 until data.length()) {
                    val ch = data.getJSONObject(i)
                    val id = ch.getString("id")
                    val attrs = ch.getJSONObject("attributes")

                    val externalUrl = attrs.optString("externalUrl", "")
                    val pages = attrs.optInt("pages", 0)

                    // Skip external redirects if there are no pages hosted on MangaDex
                    if (externalUrl.isNotBlank() && pages == 0) continue

                    val chNum = if (attrs.isNull("chapter")) "1" else attrs.optString("chapter", "1").takeIf { it != "null" } ?: "1"
                    val chTitle = if (attrs.isNull("title")) "" else attrs.optString("title", "").takeIf { it != "null" } ?: ""
                    val lang = attrs.optString("translatedLanguage", "es")

                    chapters.add(
                        MangaChapter(
                            id = id,
                            chapter = chNum,
                            title = chTitle,
                            language = lang,
                            pagesCount = pages
                        )
                    )
                }

                // If no chapters in selected language, retry with all available
                if (chapters.isEmpty() && preferredLanguage != "all") {
                    return@withContext getChapters(mangaId, "all")
                }

                Result.success(chapters)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting chapters for $mangaId", e)
            Result.failure(e)
        }
    }

    suspend fun downloadChapterAsCbz(
        mangaTitle: String,
        chapter: MangaChapter,
        onProgress: (progress: Float, status: String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            onProgress(0.05f, "Solicitando servidor de descarga...")
            val serverUrl = "$BASE_URL/at-home/server/${chapter.id}"
            val serverReq = Request.Builder()
                .url(serverUrl)
                .header("User-Agent", "Axiom/1.0 (Android Tablet)")
                .build()

            val serverResp = client.newCall(serverReq).execute()
            val serverJson = serverResp.use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Error al conectar con servidor MangaDex (HTTP ${response.code})"))
                }
                JSONObject(response.body?.string() ?: "")
            }
            val baseUrl = serverJson.getString("baseUrl")
            val chapterObj = serverJson.getJSONObject("chapter")
            val hash = chapterObj.getString("hash")
            val dataArr = chapterObj.getJSONArray("data")

            val totalPages = dataArr.length()
            if (totalPages == 0) {
                return@withContext Result.failure(Exception("El capítulo no contiene páginas descargables"))
            }

            val safeManga = mangaTitle.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(40)
            val safeChNum = chapter.chapter.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "1" }
            val mangaDir = File(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OpenReadEra/Manga"),
                safeManga
            ).apply { mkdirs() }

            val targetCbz = File(mangaDir, "Capitulo_${safeChNum}.cbz")
            val tempCbz = File.createTempFile("chapter-", ".cbz", mangaDir)
            try {
                ZipOutputStream(FileOutputStream(tempCbz)).use { zos ->
                    val buffer = ByteArray(8192)
                    for (p in 0 until totalPages) {
                        val fileName = dataArr.getString(p)
                        val imageUrl = "$baseUrl/data/$hash/$fileName"
                        val progress = 0.1f + (p.toFloat() / totalPages.toFloat()) * 0.85f
                        onProgress(progress, "Descargando página ${p + 1} de $totalPages...")
                        val imgReq = Request.Builder()
                            .url(imageUrl)
                            .header("User-Agent", "Axiom/1.0")
                            .build()
                        client.newCall(imgReq).execute().use { imgResp ->
                            if (!imgResp.isSuccessful) throw Exception("Error al descargar página (HTTP ${imgResp.code})")
                            val body = imgResp.body ?: throw Exception("Página sin contenido")
                            val ext = fileName.substringAfterLast('.', "jpg")
                            zos.putNextEntry(ZipEntry(String.format("%04d.%s", p + 1, ext)))
                            body.byteStream().use { input ->
                                var count: Int
                                while (input.read(buffer).also { count = it } != -1) zos.write(buffer, 0, count)
                            }
                            zos.closeEntry()
                        }
                    }
                }
                Os.rename(tempCbz.absolutePath, targetCbz.absolutePath)
            } finally {
                if (tempCbz.exists()) tempCbz.delete()
            }
            onProgress(1.0f, "¡Capítulo completado!")
            Result.success(targetCbz)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading chapter ${chapter.id}", e)
            Result.failure(e)
        }
    }
}
