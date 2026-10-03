package org.readera.openreadera.ui.papers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.readera.openreadera.papers.AcademicPaper
import org.readera.openreadera.papers.AcademicPapersClient
import org.readera.openreadera.papers.PaperDownloadState
import java.io.File

data class PapersUiState(
    val searchQuery: String = "",
    val selectedCategory: String = "Todos",
    val isLoading: Boolean = false,
    val papers: List<AcademicPaper> = emptyList(),
    val downloadStates: Map<String, PaperDownloadState> = emptyMap(),
    val statusMessage: String? = null,
    val lastDownloadedFile: File? = null
)

class AcademicPapersViewModel(application: Application) : AndroidViewModel(application) {

    private val client = AcademicPapersClient(application)

    private val _uiState = MutableStateFlow(PapersUiState())
    val uiState: StateFlow<PapersUiState> = _uiState.asStateFlow()

    init {
        search()
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setCategory(category: String) {
        _uiState.update { it.copy(selectedCategory = category) }
        search()
    }

    fun search() {
        val q = _uiState.value.searchQuery.trim()
        val cat = _uiState.value.selectedCategory
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }
            val results = client.searchPapers(
                query = q.ifEmpty { "artificial intelligence deep learning" },
                category = if (cat == "Todos") null else cat
            )
            _uiState.update {
                it.copy(
                    isLoading = false,
                    papers = results,
                    statusMessage = if (results.isEmpty()) "No se encontraron papers para esta búsqueda." else null
                )
            }
        }
    }

    fun downloadPaper(paper: AcademicPaper) {
        viewModelScope.launch {
            _uiState.update { state ->
                val downloads = state.downloadStates.toMutableMap()
                downloads[paper.id] = PaperDownloadState(
                    paperId = paper.id,
                    progress = 0.05f,
                    isDownloading = true
                )
                state.copy(
                    downloadStates = downloads,
                    statusMessage = "Descargando '${paper.title}'..."
                )
            }

            val result = client.downloadPaper(paper) { progress ->
                _uiState.update { state ->
                    val downloads = state.downloadStates.toMutableMap()
                    downloads[paper.id] = PaperDownloadState(
                        paperId = paper.id,
                        progress = progress,
                        isDownloading = progress < 1f,
                        isCompleted = progress >= 1f
                    )
                    state.copy(downloadStates = downloads)
                }
            }

            result.onSuccess { file ->
                _uiState.update { state ->
                    val downloads = state.downloadStates.toMutableMap()
                    downloads[paper.id] = PaperDownloadState(
                        paperId = paper.id,
                        progress = 1f,
                        isDownloading = false,
                        isCompleted = true,
                        savedFilePath = file.absolutePath
                    )
                    state.copy(
                        downloadStates = downloads,
                        statusMessage = "¡Paper '${paper.title}' descargado con éxito!",
                        lastDownloadedFile = file
                    )
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    val downloads = state.downloadStates.toMutableMap()
                    downloads[paper.id] = PaperDownloadState(
                        paperId = paper.id,
                        isDownloading = false,
                        error = error.localizedMessage
                    )
                    state.copy(
                        downloadStates = downloads,
                        statusMessage = "Error al descargar el paper: ${error.localizedMessage ?: "desconocido"}"
                    )
                }
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}
