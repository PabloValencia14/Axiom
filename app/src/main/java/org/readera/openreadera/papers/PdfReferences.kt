package org.readera.openreadera.papers

import java.text.Normalizer

data class CitationTarget(val text: String, val start: Int, val end: Int)
data class PaperReference(val label: String?, val text: String, val page: Int)

/** Local bibliography lookup; ambiguous matches remain visible rather than inventing metadata. */
object PdfReferences {
    private val year = Regex("\\b(?:18|19|20)\\d{2}[a-z]?\\b")
    private val citation = Regex(
        "\\[[\\d,;\\s–—-]+]|\\([^()]{0,200}(?:18|19|20)\\d{2}[a-z]?[^()]{0,200}\\)|" +
            "\\b[\\p{L}][\\p{L}'’–-]+(?:\\s+et\\s+al\\.?)?\\s*,?\\s+(?:18|19|20)\\d{2}[a-z]?\\b"
    )
    private val heading = Regex("(?im)^\\s*(?:\\d+\\.?\\s+)?(?:references|bibliography|referencias|bibliografía)\\s*$")
    private val numbered = Regex("^\\s*(?:\\[(\\d{1,4})]|(\\d{1,4})[.)])\\s+(.+)$")
    private val authorStart = Regex("^(?:[\\p{Lu}][\\p{L}.'’–-]*\\s+){1,4}[\\p{Lu}][\\p{L}'’–-]+(?:,|\\s+and\\s)|^[\\p{Lu}][\\p{L}'’–-]+[,.(]\\s*(?:18|19|20)\\d{2}|^[\\p{Lu}][\\p{L}'’–-]+,\\s*[\\p{Lu}]")

    fun at(text: String, offset: Int): CitationTarget? {
        for (match in citation.findAll(text)) {
            var start = match.range.first
            if (Regex("\\(\\s*(?:18|19|20)\\d{2}[a-z]?\\s*\\)").matches(match.value)) {
                val prefix = text.substring(maxOf(0, start - 90), start)
                Regex("[\\p{L}][\\p{L}'’–-]+(?:\\s+(?:and|&)\\s+[\\p{L}][\\p{L}'’–-]+)?(?:\\s+et\\s+al\\.?)?\\s*$").find(prefix)?.let {
                    start -= prefix.length - it.range.first
                }
            }
            val end = match.range.last + 1
            if (offset in start until end) return CitationTarget(text.substring(start, end), start, end)
        }
        return null
    }

    fun parse(pages: List<Pair<Int, String>>): List<PaperReference> {
        val output = mutableListOf<PaperReference>()
        var inReferences = false
        var text = StringBuilder()
        var label: String? = null
        var style: String? = null
        var page = 0
        fun flush() {
            val body = text.toString().replace(Regex("(?<=\\p{L})-\\n(?=\\p{Ll})"), "")
                .replace(Regex("\\s+"), " ").trim()
            if (body.isNotEmpty() && (label != null || year.containsMatchIn(body))) output += PaperReference(label, body, page)
            text = StringBuilder()
        }
        for ((pageNumber, source) in pages) {
            val header = heading.find(source)
            if (!inReferences && header == null) continue
            val body = if (!inReferences) source.substring(header!!.range.last + 1) else source
            inReferences = true
            for (rawLine in body.lines()) {
                val line = rawLine.trim()
                if (Regex("(?i)^(?:appendix|appendices|apéndice)(?:\\s|$)").containsMatchIn(line)) {
                    flush(); return output
                }
                if (line.isEmpty()) continue
                val candidate = numbered.matchEntire(line)
                if (style == null) style = when {
                    candidate == null -> "author"
                    candidate.groupValues[1].isNotBlank() -> "bracket"
                    candidate.groupValues[2].toInt() < 1800 -> "number"
                    else -> "author"
                }
                val number = candidate?.takeIf {
                    (style == "bracket" && it.groupValues[1].isNotBlank()) ||
                        (style == "number" && it.groupValues[2].isNotBlank())
                }
                if (number != null || (label == null && year.containsMatchIn(text) && authorStart.containsMatchIn(line))) {
                    flush()
                    label = number?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
                    page = pageNumber
                } else if (text.isEmpty()) page = pageNumber
                if (line.isNotEmpty()) text.append(number?.groupValues?.get(3) ?: line).append('\n')
            }
        }
        flush()
        return output.take(2_000)
    }

    fun resolve(target: CitationTarget, references: List<PaperReference>): List<PaperReference> {
        if (target.text.startsWith("[")) {
            val labels = linkedSetOf<String>()
            for (piece in target.text.removeSurrounding("[", "]").split(',', ';')) {
                val range = Regex("\\s*(\\d{1,4})\\s*[–—-]\\s*(\\d{1,4})\\s*").matchEntire(piece)
                if (range != null) {
                    val first = range.groupValues[1].toInt(); val last = range.groupValues[2].toInt()
                    if (last in first..first + 50) (first..last).forEach { labels += it.toString() }
                } else piece.trim().takeIf { it.matches(Regex("\\d{1,4}")) }?.let(labels::add)
            }
            return references.filter { it.label in labels }
        }
        return target.text.trim('(', ')').split(';').flatMap { part ->
            val date = year.find(part) ?: return@flatMap emptyList()
            val dates = year.findAll(part).map { it.value }.toSet()
            val author = part.substring(0, date.range.first).replace(Regex("(?i)et\\s+al\\.?"), "")
                .replace(Regex("\\s+"), " ").trim(' ', ',', '(')
            if (author.isBlank()) return@flatMap emptyList()
            val names = normal(author).split(Regex("\\s+(?:and|y)\\s+|\\s*&\\s*")).filter(String::isNotBlank)
            references.filter { ref ->
                val refYear = year.find(ref.text) ?: return@filter false
                val authors = normal(ref.text.substring(0, refYear.range.first))
                refYear.value in dates && names.all { name ->
                    Regex("(?<!\\p{L})${Regex.escape(name)}(?!\\p{L})").containsMatchIn(authors)
                }
            }
        }.distinct().take(30)
    }

    private fun normal(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase(java.util.Locale.ROOT)
}
