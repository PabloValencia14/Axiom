package org.readera.openreadera.ui.zlib

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.readera.openreadera.catalog.*
import org.readera.openreadera.zlib.ZLibBook
import org.readera.openreadera.zlib.ZLibDownloadState
import org.readera.openreadera.zlib.ZLibraryClient
import java.io.File

data class ZLibUiState(
    val searchQuery: String = "",
    val selectedSource: CatalogSource = CatalogSource.ALL,
    val selectedExtension: String = "Todos",
    val selectedLanguage: String = "Todos",
    val isLoading: Boolean = false,
    val books: List<ZLibBook> = emptyList(),
    val downloadStates: Map<String, ZLibDownloadState> = emptyMap(),
    val showAccountDialog: Boolean = false,
    val isUserLoggedIn: Boolean = false,
    val userEmail: String? = null,
    val userName: String? = null,
    val customMirror: String = "https://singlelogin.rs",
    val downloadsLeft: Int = 10,
    val statusMessage: String? = null,
    val lastDownloadedBookFile: File? = null,
    val showAuthPromptDialog: Boolean = false,
    val pendingDownloadBook: ZLibBook? = null
)

class ZLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val catalogDownloader = CatalogDownloader(application)
    private val zlibClient = ZLibraryClient(application)
    private val libgenClient = LibGenClient(downloader = catalogDownloader)
    private val standardEbooksClient = StandardEbooksClient(downloader = catalogDownloader)
    private val ebookeloClient = EbookeloClient(application, downloader = catalogDownloader)
    private val annasArchiveClient = AnnasArchiveClient(application, downloader = catalogDownloader)
    private val gutenbergClient = GutenbergClient(downloader = catalogDownloader)
    private val openLibraryClient = OpenLibraryClient(downloader = catalogDownloader)
    private val googleBooksClient = GoogleBooksClient(downloader = catalogDownloader)

    private val _uiState = MutableStateFlow(
        ZLibUiState(
            isUserLoggedIn = zlibClient.isLoggedIn,
            userEmail = zlibClient.getUserEmail(),
            userName = zlibClient.getUserName(),
            customMirror = zlibClient.mirror,
            downloadsLeft = zlibClient.getDownloadsLimit() - zlibClient.getDownloadsToday(),
            books = emptyList()
        )
    )
    val uiState: StateFlow<ZLibUiState> = _uiState.asStateFlow()

    init {
        // Initial popular search across all unified engines
        search()
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            search()
        }
    }

    fun setSource(source: CatalogSource) {
        _uiState.update { it.copy(selectedSource = source) }
        search()
    }

    fun setExtension(extension: String) {
        _uiState.update { it.copy(selectedExtension = extension) }
        search()
    }

    fun setLanguage(language: String) {
        _uiState.update { it.copy(selectedLanguage = language) }
        search()
    }

    fun search() {
        val q = _uiState.value.searchQuery.trim()

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }

            val combined = mutableListOf<ZLibBook>()

            try {
                // Curated books matching user's library and special queries
                val curatedResults = CuratedCatalog.searchCurated(q)

                // Concurrent multi-engine queries across ALL providers simultaneously
                val libgenDeferred = async {
                    libgenClient.search(
                        query = q,
                        extension = _uiState.value.selectedExtension,
                        language = _uiState.value.selectedLanguage
                    )
                }
                val ebookeloDeferred = async { ebookeloClient.search(q) }
                val gbooksDeferred = async { googleBooksClient.search(q) }
                val standardEbooksDeferred = async { standardEbooksClient.search(q) }
                val gutenbergDeferred = async { gutenbergClient.search(q) }
                val openLibDeferred = async { openLibraryClient.search(q) }
                val annasDeferred = async {
                    annasArchiveClient.search(
                        query = q,
                        extension = _uiState.value.selectedExtension,
                        language = _uiState.value.selectedLanguage
                    )
                }
                val zlibDeferred = async {
                    zlibClient.searchBooks(
                        query = q,
                        extension = _uiState.value.selectedExtension,
                        language = _uiState.value.selectedLanguage
                    )
                }

                val libgenResults = try { libgenDeferred.await() } catch (_: Exception) { emptyList() }
                val ebookeloResults = try { ebookeloDeferred.await() } catch (_: Exception) { emptyList() }
                val gbooksResults = try { gbooksDeferred.await() } catch (_: Exception) { emptyList() }
                val standardEbooksResults = try { standardEbooksDeferred.await() } catch (_: Exception) { emptyList() }
                val gutenbergResults = try { gutenbergDeferred.await() } catch (_: Exception) { emptyList() }
                val annasResults = try { annasDeferred.await() } catch (_: Exception) { emptyList() }
                val openLibResults = try { openLibDeferred.await() } catch (_: Exception) { emptyList() }
                val zlibResults = try { zlibDeferred.await() } catch (_: Exception) { emptyList() }

                // Merge in balanced order: Curated + LibGen + Ebookelo (Spanish) + Google Books + Standard Ebooks + Gutenberg + Anna's Archive + Z-Library + OpenLibrary
                combined.addAll(curatedResults)
                combined.addAll(libgenResults)
                combined.addAll(ebookeloResults)
                combined.addAll(gbooksResults)
                combined.addAll(standardEbooksResults)
                combined.addAll(gutenbergResults)
                combined.addAll(annasResults)
                combined.addAll(zlibResults.map { it.copy(source = CatalogSource.Z_LIBRARY) })
                combined.addAll(openLibResults)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Filter by format if selected
            val formatFiltered = if (_uiState.value.selectedExtension == "Todos") {
                combined
            } else {
                combined.filter { it.extension.equals(_uiState.value.selectedExtension, ignoreCase = true) }
            }

            // Filter by language if selected
            val langFiltered = if (_uiState.value.selectedLanguage == "Todos" || _uiState.value.selectedLanguage == "Todos los idiomas") {
                formatFiltered
            } else if (_uiState.value.selectedLanguage.contains("Español", ignoreCase = true) || _uiState.value.selectedLanguage.equals("Spanish", ignoreCase = true)) {
                formatFiltered.filter {
                    it.language?.contains("Español", ignoreCase = true) == true ||
                    it.language?.contains("Spanish", ignoreCase = true) == true ||
                    it.language?.contains("ES", ignoreCase = true) == true ||
                    it.source == CatalogSource.EBOOKELO
                }
            } else if (_uiState.value.selectedLanguage.contains("Inglés", ignoreCase = true) || _uiState.value.selectedLanguage.equals("English", ignoreCase = true)) {
                formatFiltered.filter {
                    it.language?.contains("English", ignoreCase = true) == true ||
                    it.language?.contains("Inglés", ignoreCase = true) == true ||
                    it.language?.contains("EN", ignoreCase = true) == true
                }
            } else {
                formatFiltered
            }

            // Deduplicate by title+author (case-insensitive)
            val distinctBooks = langFiltered.distinctBy {
                "${it.title.lowercase().trim()}_${it.author.lowercase().trim()}"
            }

            // Filter by source if specific engine selected
            val sourceFiltered = if (_uiState.value.selectedSource == CatalogSource.ALL) {
                distinctBooks
            } else {
                distinctBooks.filter { it.source == _uiState.value.selectedSource }
            }

            // Smart Relevance Ranking & Noise Filtering
            val finalBooks = if (q.isNotBlank()) {
                val scored = sourceFiltered.map { book ->
                    Pair(book, calculateRelevanceScore(book, q))
                }
                val relevant = scored.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
                if (relevant.isNotEmpty()) {
                    relevant
                } else {
                    sourceFiltered
                }
            } else {
                sourceFiltered
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    books = finalBooks,
                    statusMessage = if (finalBooks.isEmpty()) "No se encontraron libros para la búsqueda." else null
                )
            }
        }
    }

    private fun calculateRelevanceScore(book: ZLibBook, query: String): Int {
        if (query.isBlank()) return 0
        val q = query.lowercase().trim()
        val title = book.title.lowercase().trim()
        val author = book.author.lowercase().trim()
        val desc = (book.description ?: "").lowercase().trim()

        // 0. Curated books: highest priority boost
        if (book.id.startsWith("curated_")) {
            if (book.id == "curated_bucear_verano" && (q.contains("bucear") || q.contains("verano") || q.contains("dyksommar") || q.contains("stridsberg"))) {
                return 30000
            }
            if (title.contains(q) || author.contains(q)) {
                return 20000
            }
        }

        // 1. Exact match with title
        if (title == q) return 10000

        // 2. Title starts with query
        if (title.startsWith(q)) return 5000

        // 3. Title contains exact query phrase
        if (title.contains(q)) return 3000

        // 4. Token analysis
        val stopWords = setOf(
            "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "en", "a", "al", "y", "o", "con", "por", "para", "the", "a", "an", "and", "or", "in", "on", "of", "to"
        )
        val tokens = q.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() }
        val significantTokens = tokens.filter { it.length > 2 && !stopWords.contains(it) }
        val effectiveTokens = if (significantTokens.isNotEmpty()) significantTokens else tokens

        var score = 0
        var matchedTitleTokens = 0
        var matchedAuthorTokens = 0
        var matchedDescTokens = 0
        val titleWords = title.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() }

        for (token in effectiveTokens) {
            if (title.contains(token)) {
                matchedTitleTokens++
                score += 300
                if (titleWords.contains(token)) {
                    score += 200 // exact word boundary match
                }
            } else if (author.contains(token)) {
                matchedAuthorTokens++
                score += 250
            } else if (desc.contains(token)) {
                matchedDescTokens++
                score += 80
            }
        }

        // Heavy multiplier if all significant tokens appear in the title
        if (matchedTitleTokens == effectiveTokens.size && effectiveTokens.isNotEmpty()) {
            score += 2000
        }

        // Multiplier if author matches all tokens (e.g. searching author name)
        if (matchedAuthorTokens == effectiveTokens.size && effectiveTokens.isNotEmpty()) {
            score += 1500
        }

        // Only drop if query had tokens and 0 tokens matched in title, author, or description
        if (matchedTitleTokens == 0 && matchedAuthorTokens == 0 && matchedDescTokens == 0 && effectiveTokens.isNotEmpty()) {
            return -1000 // drop completely unrelated books
        }

        return score
    }

    fun downloadBook(book: ZLibBook) {
        val isDirectDownload = !book.downloadUrl.isNullOrBlank() && !book.downloadUrl.contains("/book/")

        // If it's a Z-Library restricted book requiring login and user is not logged in
        if (book.source == CatalogSource.Z_LIBRARY && !isDirectDownload && !_uiState.value.isUserLoggedIn) {
            _uiState.update { it.copy(showAuthPromptDialog = true, pendingDownloadBook = book) }
            return
        }

        executeDownload(book)
    }

    fun confirmDownloadPublicFallback() {
        val book = _uiState.value.pendingDownloadBook ?: return
        _uiState.update { it.copy(showAuthPromptDialog = false, pendingDownloadBook = null) }
        executeDownload(book)
    }

    fun dismissAuthPrompt() {
        _uiState.update { it.copy(showAuthPromptDialog = false, pendingDownloadBook = null) }
    }

    private fun executeDownload(book: ZLibBook) {
        viewModelScope.launch {
            _uiState.update { state ->
                val downloads = state.downloadStates.toMutableMap()
                downloads[book.id] = ZLibDownloadState(
                    bookId = book.id,
                    progress = 0.05f,
                    isDownloading = true
                )
                state.copy(
                    downloadStates = downloads,
                    statusMessage = "Descargando '${book.title}' vía API (${book.source.displayName})..."
                )
            }

            // Dedicated native API client for each site
            val result = when (book.source) {
                CatalogSource.Z_LIBRARY -> {
                    val zlibRes = if (book.downloadUrl.isNullOrBlank() || book.downloadUrl.contains("/book/")) {
                        zlibClient.downloadBook(book) { progress ->
                            updateDownloadProgress(book.id, progress)
                        }
                    } else {
                        catalogDownloader.download(book) { progress ->
                            updateDownloadProgress(book.id, progress)
                        }
                    }

                    if (zlibRes.isFailure) {
                        val annasRes = try {
                            _uiState.update { it.copy(statusMessage = "Buscando copia alternativa en Anna's Archive...") }
                            val found = annasArchiveClient.search(book.title)
                            val candidate = found.firstOrNull { 
                                it.title.contains(book.title.take(15), ignoreCase = true) 
                            }
                            if (candidate != null) {
                                _uiState.update { it.copy(statusMessage = "Descargando '${candidate.title}' vía Anna's Archive...") }
                                annasArchiveClient.downloadBook(candidate) { progress ->
                                    updateDownloadProgress(book.id, progress)
                                }
                            } else null
                        } catch (_: Exception) { null }

                        if (annasRes != null && annasRes.isSuccess) {
                            annasRes
                        } else {
                            try {
                                _uiState.update { it.copy(statusMessage = "Buscando copia en repositorio abierto...") }
                                catalogDownloader.downloadFallbackByTitle(book) { progress ->
                                    updateDownloadProgress(book.id, progress)
                                }
                            } catch (_: Exception) { zlibRes }
                        }
                    } else {
                        zlibRes
                    }
                }
                CatalogSource.LIBGEN -> {
                    val libgenRes = libgenClient.downloadBook(book) { progress ->
                        updateDownloadProgress(book.id, progress)
                    }
                    if (libgenRes.isFailure) {
                        try {
                            _uiState.update { it.copy(statusMessage = "Buscando copia alternativa en Anna's Archive...") }
                            val found = annasArchiveClient.search(book.title)
                            val candidate = found.firstOrNull { 
                                it.title.contains(book.title.take(15), ignoreCase = true) 
                            }
                            if (candidate != null) {
                                annasArchiveClient.downloadBook(candidate) { progress ->
                                    updateDownloadProgress(book.id, progress)
                                }
                            } else {
                                catalogDownloader.downloadFallbackByTitle(book) { progress ->
                                    updateDownloadProgress(book.id, progress)
                                }
                            }
                        } catch (_: Exception) { libgenRes }
                    } else {
                        libgenRes
                    }
                }
                CatalogSource.STANDARD_EBOOKS -> {
                    standardEbooksClient.downloadBook(book) { progress ->
                        updateDownloadProgress(book.id, progress)
                    }
                }
                CatalogSource.EBOOKELO -> {
                    ebookeloClient.downloadBook(book) { progress ->
                        updateDownloadProgress(book.id, progress)
                    }
                }
                CatalogSource.GUTENBERG -> {
                    gutenbergClient.downloadBook(book) { progress ->
                        updateDownloadProgress(book.id, progress)
                    }
                }
                CatalogSource.OPEN_LIBRARY -> {
                    openLibraryClient.downloadBook(book) { progress ->
                        updateDownloadProgress(book.id, progress)
                    }
                }
                CatalogSource.GOOGLE_BOOKS -> {
                    googleBooksClient.downloadBook(book) { progress ->
                        updateDownloadProgress(book.id, progress)
                    }
                }
                CatalogSource.ANNAS_ARCHIVE -> {
                    annasArchiveClient.downloadBook(book) { progress ->
                        updateDownloadProgress(book.id, progress)
                    }
                }
                else -> {
                    catalogDownloader.download(book) { progress ->
                        updateDownloadProgress(book.id, progress)
                    }
                }
            }

            result.onSuccess { file ->
                _uiState.update { state ->
                    val downloads = state.downloadStates.toMutableMap()
                    downloads[book.id] = ZLibDownloadState(
                        bookId = book.id,
                        progress = 1f,
                        isDownloading = false,
                        isCompleted = true,
                        savedFilePath = file.absolutePath
                    )
                    state.copy(
                        downloadStates = downloads,
                        statusMessage = "¡'${book.title}' descargado e importado con éxito a tu biblioteca!",
                        lastDownloadedBookFile = file
                    )
                }
            }.onFailure { error ->
                val errorMsg = error.localizedMessage ?: "Error al descargar el libro"
                _uiState.update { state ->
                    val downloads = state.downloadStates.toMutableMap()
                    downloads[book.id] = ZLibDownloadState(
                        bookId = book.id,
                        isDownloading = false,
                        error = errorMsg
                    )
                    state.copy(
                        downloadStates = downloads,
                        statusMessage = "Error al descargar '${book.title}': $errorMsg"
                    )
                }
            }
        }
    }

    private fun updateDownloadProgress(bookId: String, progress: Float) {
        _uiState.update { state ->
            val downloads = state.downloadStates.toMutableMap()
            val current = downloads[bookId] ?: ZLibDownloadState(bookId = bookId)
            downloads[bookId] = current.copy(
                progress = progress,
                isDownloading = progress < 1f
            )
            state.copy(downloadStates = downloads)
        }
    }

    fun openAccountDialog(open: Boolean) {
        _uiState.update { it.copy(showAccountDialog = open) }
    }

    fun login(email: String, pass: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Iniciando sesión en Z-Library...") }
            val res = zlibClient.login(email, pass)
            res.onSuccess { prof ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isUserLoggedIn = true,
                        userEmail = prof.email,
                        userName = prof.name,
                        downloadsLeft = prof.downloadsLimit - prof.downloadsToday,
                        statusMessage = "¡Sesión iniciada como ${prof.name.ifBlank { prof.email }}!",
                        showAccountDialog = false
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Error al iniciar sesión: ${err.localizedMessage}"
                    )
                }
            }
        }
    }

    fun loginWithToken(userId: String, userKey: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Validando token con Z-Library...") }
            val res = zlibClient.loginWithToken(userId, userKey)
            res.onSuccess { prof ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isUserLoggedIn = true,
                        userEmail = prof.email,
                        userName = prof.name,
                        downloadsLeft = prof.downloadsLimit - prof.downloadsToday,
                        statusMessage = "¡Token verificado correctamente!",
                        showAccountDialog = false
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Error al validar token: ${err.localizedMessage}"
                    )
                }
            }
        }
    }

    fun logout() {
        zlibClient.logout()
        _uiState.update {
            it.copy(
                isUserLoggedIn = false,
                userEmail = null,
                userName = null,
                downloadsLeft = 10,
                statusMessage = "Sesión cerrada en Z-Library."
            )
        }
    }

    fun updateMirror(newMirror: String) {
        zlibClient.mirror = newMirror
        _uiState.update {
            it.copy(
                customMirror = zlibClient.mirror,
                statusMessage = "Mirror actualizado a ${zlibClient.mirror}"
            )
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}
