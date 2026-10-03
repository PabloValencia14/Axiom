package org.readera.openreadera.catalog

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object LibGenResolver {

    const val BASE_URL = "https://libgen.li"

    private val internalClient = OkHttpClient.Builder()
        .dns(BypassDns.instance)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun resolveByMd5(client: OkHttpClient = internalClient, md5: String): String? =
        resolveByMd5(CatalogRequestExecutor(client), md5)

    private fun resolveByMd5(executor: CatalogRequestExecutor, md5: String): String? {
        val cleanMd5 = md5.trim().lowercase()
        if (cleanMd5.length != 32) return null

        try {
            val adsUrl = "$BASE_URL/ads.php?md5=$cleanMd5"
            val req = Request.Builder()
                .url(CatalogUrlSecurity.checkedUrl(adsUrl))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Referer", "$BASE_URL/index.php?req=$cleanMd5")
                .build()

            val html = executor.execute(req).use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            } ?: return null

            val matcher = Pattern.compile("href=[\"'](get\\.php\\?md5=[^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
            if (matcher.find()) {
                val rel = matcher.group(1) ?: return null
                return "$BASE_URL/$rel"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    data class ResolvedDownload(
        val url: String,
        val extension: String
    )

    private val SUPPORTED_EXTENSIONS = listOf("epub", "pdf", "fb2", "txt", "cbz")

    fun searchAndResolveDownload(
        client: OkHttpClient = internalClient,
        title: String,
        preferredExtension: String? = "epub",
        preferredLanguage: String = "Spanish"
    ): ResolvedDownload? =
        searchAndResolveDownload(CatalogRequestExecutor(client), title, preferredExtension, preferredLanguage)

    private fun searchAndResolveDownload(
        executor: CatalogRequestExecutor,
        title: String,
        preferredExtension: String?,
        preferredLanguage: String
    ): ResolvedDownload? {
        val cleanTitle = title.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s]"), " ").trim()
        if (cleanTitle.isBlank()) return null

        try {
            val q = URLEncoder.encode(cleanTitle, "UTF-8")
            val searchUrl = "$BASE_URL/index.php?req=$q"
            val req = Request.Builder()
                .url(CatalogUrlSecurity.checkedUrl(searchUrl))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Referer", "$BASE_URL/")
                .build()

            val html = executor.execute(req).use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            } ?: return null
            val candidates = parseCandidates(html, cleanTitle, preferredExtension, preferredLanguage)

            for (candidate in candidates.take(6)) {
                val resolved = resolveByMd5(executor, candidate.md5)
                if (!resolved.isNullOrBlank()) {
                    return ResolvedDownload(url = resolved, extension = candidate.extension)
                }
            }

            // Fallback: if title has subtitles or parentheses, try just the main title (e.g. "Beckomberga. Oda a mi familia" -> "Beckomberga")
            val simplified = title.substringBefore('.').substringBefore(':').substringBefore('(').substringBefore('-').trim()
            if (simplified.length >= 3 && !simplified.equals(title.trim(), ignoreCase = true)) {
                return searchAndResolveDownload(executor, simplified, preferredExtension, preferredLanguage)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun parseCandidates(
        html: String,
        cleanTitle: String,
        preferredExtension: String?,
        preferredLanguage: String
    ): List<LibGenCandidate> {
        val candidates = mutableListOf<LibGenCandidate>()
        val prefExt = preferredExtension?.trim()?.lowercase()?.removePrefix(".") ?: "epub"
        val prefLang = preferredLanguage.trim().lowercase()

        val titleWords = cleanTitle.lowercase().split("\\s+".toRegex()).filter { it.length > 2 }

        val rowMatcher = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)

        while (rowMatcher.find()) {
            val rowHtml = rowMatcher.group(1) ?: continue
            val md5Matcher = Pattern.compile("md5=([a-fA-F0-9]{32})", Pattern.CASE_INSENSITIVE).matcher(rowHtml)
            if (!md5Matcher.find()) continue
            val md5 = md5Matcher.group(1) ?: continue

            val tdMatcher = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(rowHtml)
            val cells = mutableListOf<String>()
            while (tdMatcher.find()) {
                val raw = tdMatcher.group(1) ?: ""
                cells.add(raw.replace(Regex("<[^>]+>"), " ").trim())
            }

            var rowExt = ""
            var rowLang = ""
            for (cell in cells) {
                val lower = cell.lowercase()
                if (rowExt.isEmpty() && lower in SUPPORTED_EXTENSIONS) {
                    rowExt = lower
                }
                if (rowLang.isEmpty() && (lower in listOf("spanish", "español", "espanol", "castellano", "english", "french", "german", "italian", "portuguese", "russian") || lower.contains("spanish") || lower.contains("español"))) {
                    rowLang = cell
                }
            }

            if (rowExt.isEmpty()) continue

            var score = 0
            val rowLangLower = rowLang.lowercase()
            if (rowLangLower.contains(prefLang) || rowLangLower.contains("spanish") || rowLangLower.contains("español") || rowLangLower.contains("castellano")) {
                score += 200
            }
            if (rowExt == prefExt) {
                score += 100
            } else if (rowExt == "epub") {
                score += 80
            } else if (rowExt == "pdf") {
                score += 40
            }

            val rowTitle = cells.firstOrNull()?.lowercase() ?: ""
            val matchedWords = titleWords.count { rowTitle.contains(it) }
            score += matchedWords * 15

            candidates.add(LibGenCandidate(md5, rowExt, rowLang, rowTitle, score))
        }

        candidates.sortByDescending { it.score }
        return candidates
    }

    private data class LibGenCandidate(
        val md5: String,
        val extension: String,
        val language: String,
        val titleSnippet: String,
        val score: Int
    )

    fun searchAndResolve(client: OkHttpClient = internalClient, title: String): String? {
        return searchAndResolveDownload(client, title)?.url
    }
}
