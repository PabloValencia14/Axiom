package org.readera.openreadera.papers

import android.content.Context
import android.os.Environment
import android.util.Log
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.readera.openreadera.data.scanner.StorageScanner
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class AcademicPapersClient(private val context: Context) {

    companion object {
        private const val TAG = "AcademicPapersClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun searchPapers(
        query: String,
        category: String? = null
    ): List<AcademicPaper> = withContext(Dispatchers.IO) {
        val results = mutableListOf<AcademicPaper>()
        try {
            val cleanQ = query.trim().ifBlank { "artificial intelligence" }
            val catPrefix = when (category) {
                "Informática / CS" -> "cat:cs* AND "
                "Física" -> "cat:physics* AND "
                "Matemáticas" -> "cat:math* AND "
                "Biología" -> "cat:q-bio* AND "
                "Finanzas / Economía" -> "cat:q-fin* AND "
                "Estadística" -> "cat:stat* AND "
                else -> ""
            }

            val searchQuery = if (catPrefix.isNotBlank()) "${catPrefix}all:$cleanQ" else "all:$cleanQ"
            val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
            val url = "https://export.arxiv.org/api/query?search_query=$encodedQuery&start=0&max_results=25&sortBy=relevance&sortOrder=descending"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Axiom Academic)")
                .build()

            val resp = client.newCall(req).execute()
            val xml = resp.body?.string() ?: ""

            if (resp.isSuccessful && xml.isNotBlank()) {
                val entryPattern = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL)
                val entryMatcher = entryPattern.matcher(xml)

                while (entryMatcher.find()) {
                    val entryContent = entryMatcher.group(1) ?: continue

                    // Title
                    val titleMatch = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL).matcher(entryContent)
                    val rawTitle = if (titleMatch.find()) titleMatch.group(1)?.replace(Regex("""\s+"""), " ")?.trim() ?: "Sin título" else "Sin título"

                    // Id / arXiv Link
                    val idMatch = Pattern.compile("<id>(.*?)</id>", Pattern.DOTALL).matcher(entryContent)
                    val rawId = if (idMatch.find()) idMatch.group(1)?.trim() ?: "" else ""
                    val cleanId = rawId.substringAfterLast("/abs/").substringAfterLast("/")

                    // Summary
                    val sumMatch = Pattern.compile("<summary>(.*?)</summary>", Pattern.DOTALL).matcher(entryContent)
                    val rawSummary = if (sumMatch.find()) sumMatch.group(1)?.replace(Regex("""\s+"""), " ")?.trim() ?: "" else ""

                    // Published year
                    val pubMatch = Pattern.compile("<published>(.*?)</published>", Pattern.DOTALL).matcher(entryContent)
                    val pubDate = if (pubMatch.find()) pubMatch.group(1)?.trim() ?: "" else ""
                    val year = if (pubDate.length >= 4) pubDate.take(4) else ""

                    // Authors
                    val authors = mutableListOf<String>()
                    val authorPattern = Pattern.compile("<author>\\s*<name>(.*?)</name>\\s*</author>", Pattern.DOTALL)
                    val authMatcher = authorPattern.matcher(entryContent)
                    while (authMatcher.find()) {
                        authMatcher.group(1)?.trim()?.let { authors.add(it) }
                    }

                    // Primary category
                    val catMatch = Pattern.compile("<arxiv:primary_category[^>]*term=\"([^\"]+)\"", Pattern.DOTALL).matcher(entryContent)
                    val primaryCat = if (catMatch.find()) catMatch.group(1) ?: "cs" else "cs"

                    // PDF URL
                    val pdfMatch = Pattern.compile("<link[^>]*title=\"pdf\"[^>]*href=\"([^\"]+)\"", Pattern.DOTALL).matcher(entryContent)
                    var pdfUrl = if (pdfMatch.find()) pdfMatch.group(1) ?: "" else ""
                    if (pdfUrl.isBlank() && cleanId.isNotBlank()) {
                        pdfUrl = "https://arxiv.org/pdf/$cleanId.pdf"
                    }
                    if (pdfUrl.startsWith("http://")) {
                        pdfUrl = pdfUrl.replace("http://", "https://")
                    }
                    if (!pdfUrl.endsWith(".pdf") && !pdfUrl.contains(".pdf")) {
                        pdfUrl = "$pdfUrl.pdf"
                    }

                    if (rawTitle.isNotBlank() && cleanId.isNotBlank()) {
                        results.add(
                            AcademicPaper(
                                id = cleanId,
                                title = rawTitle,
                                authors = authors,
                                publishedYear = year,
                                summary = rawSummary,
                                primaryCategory = primaryCat,
                                pdfUrl = pdfUrl
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying arXiv API", e)
        }

        if (results.isEmpty()) {
            results.addAll(getFallbackPapers(query))
        }

        results
    }

    suspend fun downloadPaper(
        paper: AcademicPaper,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val downloadDir = StorageScanner.getAppBooksDirectory()

            val safeTitle = paper.title.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s-]"), "").take(45).trim()
            val safeAuthor = paper.authors.firstOrNull()?.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s-]"), "")?.take(25)?.trim() ?: "arXiv"
            val targetFile = File(downloadDir, "$safeAuthor - $safeTitle.pdf")

            val req = Request.Builder()
                .url(paper.pdfUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Xiaomi Pad 7)")
                .build()

            val tempFile = File.createTempFile("paper-", ".pdf", downloadDir)
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful || resp.body == null) {
                        return@withContext Result.failure(Exception("Error al descargar el paper (HTTP ${resp.code})"))
                    }
                    val body = resp.body!!
                    val length = body.contentLength()
                    val buffer = ByteArray(8192)
                    var totalBytes = 0L
                    FileOutputStream(tempFile).use { output ->
                        body.byteStream().use { input ->
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalBytes += bytesRead
                                if (length > 0) onProgress((totalBytes.toFloat() / length.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                Os.rename(tempFile.absolutePath, targetFile.absolutePath)
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
            onProgress(1f)

            // Index in Room database so it opens immediately in reader
            StorageScanner.scanSingleFile(context, targetFile)

            try {
                val syncManager = org.readera.openreadera.sync.GoogleDriveSyncManager(context)
                if (syncManager.isDriveConnected() && syncManager.isAutoSyncEnabled) {
                    syncManager.triggerImmediateBackgroundSync()
                }
            } catch (_: Exception) {}

            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading paper ${paper.title}", e)
            Result.failure(e)
        }
    }

    private fun getFallbackPapers(query: String = ""): List<AcademicPaper> {
        return listOf(
            AcademicPaper(
                id = "1706.03762",
                title = "Attention Is All You Need",
                authors = listOf("Ashish Vaswani", "Noam Shazeer", "Niki Parmar", "Jakob Uszkoreit", "Llion Jones", "Aidan N. Gomez", "Łukasz Kaiser", "Illia Polosukhin"),
                publishedYear = "2017",
                summary = "The dominant sequence transduction models are based on complex recurrent or convolutional neural networks that include an encoder and a decoder. We propose the Transformer, a novel architecture based solely on attention mechanisms.",
                primaryCategory = "cs.CL",
                pdfUrl = "https://arxiv.org/pdf/1706.03762.pdf"
            ),
            AcademicPaper(
                id = "1512.03385",
                title = "Deep Residual Learning for Image Recognition (ResNet)",
                authors = listOf("Kaiming He", "Xiangyu Zhang", "Shaoqing Ren", "Jian Sun"),
                publishedYear = "2015",
                summary = "Deeper neural networks are more difficult to train. We present a residual learning framework to ease the training of networks that are substantially deeper than those used previously.",
                primaryCategory = "cs.CV",
                pdfUrl = "https://arxiv.org/pdf/1512.03385.pdf"
            ),
            AcademicPaper(
                id = "1406.2661",
                title = "Generative Adversarial Nets (GANs)",
                authors = listOf("Ian J. Goodfellow", "Jean Pouget-Abadie", "Mehdi Mirza", "Bing Xu", "David Warde-Farley", "Sherjil Ozair", "Aaron Courville", "Yoshua Bengio"),
                publishedYear = "2014",
                summary = "We propose a new framework for estimating generative models via an adversarial process, in which we simultaneously train two models: a generative model G that captures the data distribution, and a discriminative model D.",
                primaryCategory = "stat.ML",
                pdfUrl = "https://arxiv.org/pdf/1406.2661.pdf"
            ),
            AcademicPaper(
                id = "1810.04805",
                title = "BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding",
                authors = listOf("Jacob Devlin", "Ming-Wei Chang", "Kenton Lee", "Kristina Toutanova"),
                publishedYear = "2018",
                summary = "We introduce a new language representation model called BERT, which stands for Bidirectional Encoder Representations from Transformers. BERT obtains new state-of-the-art results on eleven NLP tasks.",
                primaryCategory = "cs.CL",
                pdfUrl = "https://arxiv.org/pdf/1810.04805.pdf"
            ),
            AcademicPaper(
                id = "2005.14165",
                title = "Language Models are Few-Shot Learners (GPT-3)",
                authors = listOf("Tom B. Brown", "Benjamin Mann", "Nick Ryder", "Melanie Subbiah", "Jared Kaplan", "Dario Amodei"),
                publishedYear = "2020",
                summary = "Recent work has demonstrated substantial gains on many NLP tasks and benchmarks through pre-training on a large corpus of text followed by fine-tuning on a specific task. We demonstrate that scaling language models greatly improves few-shot performance.",
                primaryCategory = "cs.CL",
                pdfUrl = "https://arxiv.org/pdf/2005.14165.pdf"
            )
        )
    }
}
