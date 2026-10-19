package org.readera.openreadera.papers

internal fun paperFileName(id: String, title: String, authors: List<String>): String {
    fun safe(text: String, length: Int) = text.replace(Regex("[^\\p{L}\\p{N}\\s-]"), "").take(length).trim()
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(id.removePrefix("arxiv:").toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
    return "${safe(authors.firstOrNull() ?: "Paper", 25)} - ${safe(title, 45)}-$digest.pdf"
}

data class AcademicPaper(
    val id: String,
    val title: String,
    val authors: List<String>,
    val publishedYear: String,
    val summary: String,
    val primaryCategory: String,
    val categories: List<String> = emptyList(),
    val pdfUrl: String,
    val journalRef: String? = null,
    val doi: String? = null
) {
    val displayAuthors: String
        get() = when {
            authors.isEmpty() -> "Autor desconocido"
            authors.size == 1 -> authors[0]
            authors.size == 2 -> "${authors[0]} y ${authors[1]}"
            else -> "${authors[0]} et al. (${authors.size} autores)"
        }

    val displayCategory: String
        get() = when {
            primaryCategory.startsWith("cs") -> "Informática / CS"
            primaryCategory.startsWith("math") -> "Matemáticas"
            primaryCategory.startsWith("physics") -> "Física"
            primaryCategory.startsWith("q-bio") -> "Biología Cuantitativa"
            primaryCategory.startsWith("q-fin") -> "Finanzas Cuantitativas"
            primaryCategory.startsWith("stat") -> "Estadística"
            primaryCategory.startsWith("econ") -> "Economía"
            else -> primaryCategory.ifBlank { "Paper Académico" }
        }
}

data class PaperDownloadState(
    val paperId: String,
    val progress: Float = 0f,
    val isDownloading: Boolean = false,
    val isCompleted: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null
)
