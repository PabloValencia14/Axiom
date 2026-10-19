package org.readera.openreadera.papers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import org.readera.openreadera.catalog.CatalogRequestExecutor
import java.text.Normalizer
import java.util.Locale

/** Resolves a specific citation, never substituting the catalog's suggested papers. */
internal class ReferencedPaperResolver(private val requests: CatalogRequestExecutor) {
    suspend fun resolve(reference: PaperReference): AcademicPaper = withContext(Dispatchers.IO) {
        val text = reference.text
        val year = YEAR.find(text) ?: error("La referencia no contiene un año identificable.")
        val tail = text.substring(year.range.last + 1).trimStart(' ', '.', ',', ')')
        val title = tail.substringBefore(". ").trim().take(350)
        require(title.length >= 5) { "No se pudo identificar el título del artículo citado." }
        val authors = text.substring(0, year.range.first).trim().trimEnd('.', ',', '(')
        ARXIV.find(text)?.groupValues?.get(1)?.let { id ->
            return@withContext AcademicPaper("arxiv:$id", title, listOf(authors), year.value.take(4), "", "",
                pdfUrl = "https://arxiv.org/pdf/$id")
        }
        Regex("https://[^\\s<>]+").findAll(text).map { it.value.trimEnd('.', ',', ';', ')') }
            .firstOrNull { it.substringBefore('?').endsWith(".pdf", true) }?.let { pdf ->
                return@withContext AcademicPaper(pdf, title, listOf(authors), year.value.take(4), "", "", pdfUrl = pdf)
            }
        val doi = DOI.find(text)?.value?.trimEnd('.', ',', ';')
        val url = "https://api.openalex.org/works".toHttpUrl().newBuilder()
            .addQueryParameter("per-page", "5")
            .addQueryParameter("select", "id,title,publication_year,doi,authorships,best_oa_location,locations")
            .apply {
                if (doi != null) addQueryParameter("filter", "doi:https://doi.org/$doi")
                else addQueryParameter("search", title)
            }.build()
        val data = requests.execute(Request.Builder().url(url).header("User-Agent", "Axiom/Android (reference lookup)").build()).use { response ->
            check(response.isSuccessful) { "No se pudo consultar el artículo (HTTP ${response.code}). Inténtalo más tarde." }
            val body = response.body ?: error("El buscador no devolvió datos.")
            require(body.contentLength() <= 1_048_576) { "La respuesta del buscador es demasiado grande." }
            val buffer = okio.Buffer()
            val source = body.source()
            while (buffer.size <= 1_048_576) {
                if (source.read(buffer, 1_048_577 - buffer.size) == -1L) break
            }
            require(buffer.size <= 1_048_576) { "La respuesta del buscador es demasiado grande." }
            JSONObject(buffer.readUtf8()).getJSONArray("results")
        }
        val matches = (0 until data.length()).map { data.getJSONObject(it) }.filter { work ->
            if (doi != null) work.optString("doi").equals("https://doi.org/$doi", true)
            else matches(reference, work)
        }.distinctBy { it.optString("doi").takeIf { doi -> doi.startsWith("https://doi.org/") } ?: it.getString("id") }
        check(matches.size == 1) {
            if (matches.isEmpty()) "No se ha encontrado una coincidencia fiable para este artículo."
            else "Hay varias coincidencias para esta referencia; no se ha descargado ningún artículo."
        }
        val work = matches.single()
        val locations = listOfNotNull(work.optJSONObject("best_oa_location")) +
            work.optJSONArray("locations")?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } }.orEmpty()
        val pdf = locations.firstNotNullOfOrNull { location ->
            location.optString("pdf_url").takeIf { location.optBoolean("is_oa") && it.startsWith("https://") }
        } ?: error("Este artículo no ofrece un PDF de acceso abierto disponible para descargar.")
        val names = work.optJSONArray("authorships")?.let { array ->
            (0 until array.length()).mapNotNull { array.getJSONObject(it).optJSONObject("author")?.optString("display_name") }
        }.orEmpty()
        AcademicPaper(work.getString("id"), work.getString("title"), names, work.optInt("publication_year").toString(),
            "", "", pdfUrl = pdf, doi = work.optString("doi").takeIf { it.startsWith("https://doi.org/") })
    }

    companion object {
        private val YEAR = Regex("\\b(?:18|19|20)\\d{2}[a-z]?\\b")
        private val ARXIV = Regex("(?:arxiv\\s*:\\s*|\\barxiv\\.org/(?:abs|pdf)/)(\\d{4}\\.\\d{4,5}(?:v\\d+)?|[a-z-]+(?:\\.[a-z]{2})?/\\d{7}(?:v\\d+)?)", RegexOption.IGNORE_CASE)
        private val DOI = Regex("10\\.\\d{4,9}/[-._;()/:a-z0-9]+", RegexOption.IGNORE_CASE)

        internal fun matches(reference: PaperReference, work: JSONObject): Boolean {
            val year = YEAR.find(reference.text) ?: return false
            if (work.optInt("publication_year") != year.value.take(4).toInt()) return false
            val title = normalized(work.optString("title"))
            val citedTitle = reference.text.substring(year.range.last + 1).trimStart(' ', '.', ',', ')').substringBefore(". ")
            if (title.length < 8 || normalized(citedTitle) != title) return false
            val citedAuthors = Normalizer.normalize(reference.text.substring(0, year.range.first), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            val authors = work.optJSONArray("authorships") ?: return false
            return (0 until authors.length()).any { i ->
                val name = authors.getJSONObject(i).optJSONObject("author")?.optString("display_name").orEmpty()
                val surname = normalized(name.substringBefore(',').substringAfterLast(' '))
                surname.length >= 3 && surname in citedAuthors
            }
        }

        private fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
    }
}
