package org.readera.openreadera.ui.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.readera.openreadera.catalog.OpdsAcquisition
import org.readera.openreadera.catalog.OpdsCatalog
import org.readera.openreadera.catalog.OpdsCatalogRepository
import org.readera.openreadera.catalog.OpdsEntry
import org.readera.openreadera.catalog.OpdsFeedPage

data class OpdsDownloadState(
    val progress: Float = 0f,
    val isDownloading: Boolean = false,
    val isCompleted: Boolean = false,
    val localFilePath: String? = null,
    val error: String? = null
)

data class OpdsCatalogUiState(
    val catalogs: List<OpdsCatalog> = emptyList(),
    val selectedCatalogId: String? = null,
    val feedPage: OpdsFeedPage? = null,
    val query: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val downloads: Map<String, OpdsDownloadState> = emptyMap(),
    val isAddDialogVisible: Boolean = false
) {

    fun getDownloadState(entry: OpdsEntry, acquisition: OpdsAcquisition): OpdsDownloadState? =
        downloads["${entry.id}|${acquisition.href}"]
}

class OpdsCatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = OpdsCatalogRepository(application)

    private val _uiState = MutableStateFlow(OpdsCatalogUiState())
    val uiState: StateFlow<OpdsCatalogUiState> = _uiState.asStateFlow()

    // History of visited hrefs for breadcrumb / back navigation within feeds
    private val browseHistory = mutableListOf<String?>()
    private var feedRequestGeneration = 0L
    private var isSearchResults = false

    init {
        loadCatalogs()
    }

    private fun loadCatalogs() {
        val list = repository.getCatalogs()
        _uiState.update { it.copy(catalogs = list) }
    }

    fun setAddDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(isAddDialogVisible = visible) }
    }

    fun selectCatalog(id: String) {
        if (_uiState.value.catalogs.none { it.id == id }) return
        browseHistory.clear()
        isSearchResults = false
        _uiState.update {
            it.copy(
                selectedCatalogId = id,
                feedPage = null,
                query = "",
                isLoading = true,
                errorMessage = null
            )
        }
        browse(null)
    }

    fun showCatalogList() {
        feedRequestGeneration++
        isSearchResults = false
        browseHistory.clear()
        _uiState.update {
            it.copy(
                selectedCatalogId = null,
                feedPage = null,
                query = "",
                isLoading = false,
                errorMessage = null
            )
        }
    }

    fun addCatalog(title: String, feedUrl: String, username: String? = null, password: String? = null) {
        val cleanTitle = title.trim()
        val cleanUrl = feedUrl.trim()
        if (cleanTitle.isBlank()) {
            _uiState.update { it.copy(errorMessage = "El título del catálogo no puede estar vacío.") }
            return
        }
        if (cleanUrl.isBlank() || !cleanUrl.startsWith("https://", ignoreCase = true)) {
            _uiState.update { it.copy(errorMessage = "La URL del catálogo debe usar HTTPS obligatoriamente.") }
            return
        }

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                val (added, refreshed) = withContext(Dispatchers.IO) {
                    val added = repository.addCatalog(
                        title = cleanTitle,
                        feedUrl = cleanUrl,
                        username = username?.trim()?.ifBlank { null },
                        password = password?.ifBlank { null }
                    )
                    added to repository.getCatalogs()
                }
                _uiState.update {
                    it.copy(
                        catalogs = refreshed,
                        isAddDialogVisible = false,
                        isLoading = false,
                        statusMessage = "Catálogo \"${added.title}\" añadido correctamente.",
                        errorMessage = null
                    )
                }
                selectCatalog(added.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "No se pudo guardar el catálogo. Revisa la URL HTTPS y las credenciales."
                    )
                }
            }
        }
    }

    fun deleteCatalog(id: String) {
        if (_uiState.value.selectedCatalogId == id) {
            feedRequestGeneration++
            isSearchResults = false
        }
        viewModelScope.launch {
            try {
                val (catToDelete, refreshed) = withContext(Dispatchers.IO) {
                    val catToDelete = _uiState.value.catalogs.firstOrNull { it.id == id }
                    repository.deleteCatalog(id)
                    catToDelete to repository.getCatalogs()
                }
                _uiState.update { state ->
                    val wasSelected = state.selectedCatalogId == id
                    if (wasSelected) {
                        browseHistory.clear()
                    }
                    state.copy(
                        catalogs = refreshed,
                        selectedCatalogId = if (wasSelected) null else state.selectedCatalogId,
                        feedPage = if (wasSelected) null else state.feedPage,
                        statusMessage = catToDelete?.let { "Catálogo \"${it.title}\" eliminado." }
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = "No se pudo eliminar el catálogo."
                    )
                }
            }
        }
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }
        if (query.isBlank() && _uiState.value.feedPage != null) browse(null)
    }

    fun browse(href: String? = null) {
        val catalogId = _uiState.value.selectedCatalogId ?: return
        isSearchResults = false
        val requestGeneration = ++feedRequestGeneration
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val page = repository.browse(catalogId, href)
                if (requestGeneration != feedRequestGeneration ||
                    catalogId != _uiState.value.selectedCatalogId
                ) return@launch
                if (href == null) {
                    browseHistory.clear()
                    browseHistory.add(null)
                } else if (browseHistory.isEmpty() || browseHistory.last() != href) {
                    browseHistory.add(href)
                }
                _uiState.update {
                    it.copy(feedPage = page, isLoading = false, errorMessage = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (requestGeneration != feedRequestGeneration ||
                    catalogId != _uiState.value.selectedCatalogId
                ) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "No se pudo cargar el catálogo. Comprueba la conexión o las credenciales."
                    )
                }
            }
        }
    }

    fun navigateBack(): Boolean {
        if (isSearchResults) {
            isSearchResults = false
            browse(browseHistory.lastOrNull())
            return true
        }
        if (browseHistory.size > 1) {
            browseHistory.removeAt(browseHistory.lastIndex)
            browse(browseHistory.last())
            return true
        }
        if (_uiState.value.selectedCatalogId != null) {
            showCatalogList()
            return true
        }
        return false
    }

    fun search() {
        val catalogId = _uiState.value.selectedCatalogId ?: return
        val searchUrl = _uiState.value.feedPage?.searchUrl
        val query = _uiState.value.query.trim()

        if (searchUrl == null) {
            _uiState.update {
                it.copy(errorMessage = "Este catálogo no admite búsqueda remota OpenSearch.")
            }
            return
        }
        if (query.isBlank()) {
            browse(null)
            return
        }

        val requestGeneration = ++feedRequestGeneration
        isSearchResults = true
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val page = repository.search(catalogId, searchUrl, query)
                if (requestGeneration != feedRequestGeneration ||
                    catalogId != _uiState.value.selectedCatalogId
                ) return@launch
                val searchablePage = if (page.searchUrl == null) page.copy(searchUrl = searchUrl) else page
                _uiState.update {
                    it.copy(feedPage = searchablePage, isLoading = false, errorMessage = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (requestGeneration != feedRequestGeneration ||
                    catalogId != _uiState.value.selectedCatalogId
                ) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "No se pudo realizar la búsqueda."
                    )
                }
            }
        }
    }

    fun download(entry: OpdsEntry, acquisition: OpdsAcquisition) {
        val catalogId = _uiState.value.selectedCatalogId ?: return
        val key = "${entry.id}|${acquisition.href}"

        if (_uiState.value.downloads[key]?.isDownloading == true) {
            return
        }

        _uiState.update { state ->
            val updated = state.downloads.toMutableMap()
            updated[key] = OpdsDownloadState(progress = 0f, isDownloading = true)
            state.copy(
                downloads = updated,
                statusMessage = "Descargando \"${entry.title}\"..."
            )
        }

        viewModelScope.launch {
            val result = repository.download(catalogId, entry, acquisition) { progress ->
                _uiState.update { state ->
                    val updated = state.downloads.toMutableMap()
                    val curr = updated[key] ?: OpdsDownloadState()
                    updated[key] = curr.copy(
                        progress = progress.coerceIn(0f, 1f),
                        isDownloading = true
                    )
                    state.copy(downloads = updated)
                }
            }

            result.fold(
                onSuccess = { file ->
                    _uiState.update { state ->
                        val updated = state.downloads.toMutableMap()
                        updated[key] = OpdsDownloadState(
                            progress = 1f,
                            isDownloading = false,
                            isCompleted = true,
                            localFilePath = file.absolutePath
                        )
                        state.copy(
                            downloads = updated,
                            statusMessage = "\"${entry.title}\" descargado correctamente."
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { state ->
                        val updated = state.downloads.toMutableMap()
                        updated[key] = OpdsDownloadState(
                            isDownloading = false,
                            isCompleted = false,
                            error = error.localizedMessage ?: error.message
                        )
                        state.copy(
                            downloads = updated,
                            errorMessage = "Error al descargar \"${entry.title}\": ${error.localizedMessage ?: error.message}"
                        )
                    }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, statusMessage = null) }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}
