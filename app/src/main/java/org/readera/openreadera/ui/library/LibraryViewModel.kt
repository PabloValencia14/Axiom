package org.readera.openreadera.ui.library

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.readera.openreadera.data.db.CollectionWithCount
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.BookCollectionCrossRef
import org.readera.openreadera.data.model.Collection
import org.readera.openreadera.data.model.Quote
import org.readera.openreadera.data.model.SearchQuery
import org.readera.openreadera.data.repository.BookRepository
import org.readera.openreadera.data.scanner.StorageScanner
import kotlinx.coroutines.Dispatchers
import java.io.File
internal fun Book.matchesLibraryQuery(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return title.contains(needle, ignoreCase = true) ||
        author.contains(needle, ignoreCase = true) ||
        filePath.contains(needle, ignoreCase = true) ||
        series?.contains(needle, ignoreCase = true) == true ||
        format.contains(needle, ignoreCase = true) ||
        language?.contains(needle, ignoreCase = true) == true ||
        genre?.contains(needle, ignoreCase = true) == true ||
        description?.contains(needle, ignoreCase = true) == true
}

@OptIn(FlowPreview::class)
internal fun Flow<String>.debouncedLibraryQuery(): Flow<String> =
    debounce { query -> if (query.isBlank()) 0L else 250L }.distinctUntilChanged()

internal fun filterAndSortLibraryBooks(
    books: List<Book>,
    filterCriteria: FilterCriteria,
    searchQuery: String,
    sortCriterion: SortCriterion,
    allBookCollections: List<BookCollectionCrossRef>
): List<Book> {
    val assignedBookIds = if (filterCriteria.withoutCollection) {
        allBookCollections.mapTo(HashSet()) { it.bookId }
    } else {
        emptySet()
    }
    val query = searchQuery.trim()
    val targetBooks = if (filterCriteria.isEmpty && query.isEmpty()) books else books.filter { book ->
        (filterCriteria.activeFormats.isEmpty() || book.format.uppercase() in filterCriteria.activeFormats) &&
            (!filterCriteria.withoutAuthor || book.author.isBlank() || book.author == "Autor desconocido" || book.author == "Desconocido") &&
            (!filterCriteria.withoutSeries || book.series.isNullOrBlank()) &&
            (!filterCriteria.withoutCollection || book.id !in assignedBookIds) &&
            (!filterCriteria.unreadOnly || (!book.isHaveRead && book.currentPage == 0)) &&
            book.matchesLibraryQuery(query)
    }
    return when (sortCriterion) {
        SortCriterion.NAME -> targetBooks.sortedWith { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
        SortCriterion.FILE_NAME -> targetBooks.map { it to File(it.filePath).name }
            .sortedWith { a, b -> a.second.compareTo(b.second, ignoreCase = true) }.map { it.first }
        SortCriterion.FILE_FORMAT -> targetBooks.sortedWith { a, b -> a.format.compareTo(b.format, ignoreCase = true) }
        SortCriterion.FILE_SIZE -> targetBooks.sortedWith { a, b -> b.fileSize.compareTo(a.fileSize) }
        SortCriterion.DATE_MODIFIED -> targetBooks.sortedWith { a, b -> b.dateAdded.compareTo(a.dateAdded) }
        SortCriterion.DATE_READ -> targetBooks.sortedWith { a, b -> b.lastOpened.compareTo(a.lastOpened) }
    }
}



enum class LibrarySection(val title: String) {
    READING_NOW("Leyendo ahora"),
    ALL_DOCUMENTS("Libros y documentos"),
    FAVORITES("Favoritos"),
    TO_READ("Para leer"),
    HAVE_READ("Leídos"),
    AUTHORS("Autores"),
    SERIES("Series"),
    COLLECTIONS("Colecciones"),
    FORMATS("Formatos"),
    FOLDERS("Carpetas"),
    QUOTES("Citas y notas"),
    DOWNLOADS("Descargas"),
    ZLIBRARY("Descargar Libros"),
    OPDS_CATALOGS("Catálogos OPDS"),
    ACADEMIC_PAPERS("Papers Académicos"),
    COMICS_MANGA("Cómics y Manga"),
    JOURNAL("Diario de Lecturas"),
    READING_STATS("Estadísticas de lectura"),
    DRIVE_SYNC("Google Drive"),
    TRASH("Papelera")
}

enum class SortCriterion(val label: String) {
    NAME("Nombre"),
    FILE_NAME("Nombre de archivo"),
    FILE_FORMAT("Formato de archivo"),
    FILE_SIZE("Tamaño de archivo"),
    DATE_MODIFIED("Fecha de modificación"),
    DATE_READ("Fecha de lectura")
}

data class FilterCriteria(
    val activeFormats: Set<String> = emptySet(),
    val withoutAuthor: Boolean = false,
    val withoutSeries: Boolean = false,
    val withoutCollection: Boolean = false,
    val unreadOnly: Boolean = false
) {
    val isEmpty: Boolean
        get() = activeFormats.isEmpty() && !withoutAuthor && !withoutSeries && !withoutCollection && !unreadOnly
}

data class AuthorItem(
    val displayName: String,
    val rawName: String,
    val bookCount: Int,
    val books: List<Book>
)

data class SeriesItem(
    val name: String,
    val bookCount: Int,
    val books: List<Book>
)

data class FormatItem(
    val format: String,
    val bookCount: Int,
    val books: List<Book>
)

data class FolderItem(
    val folderName: String,
    val folderPath: String,
    val bookCount: Int,
    val books: List<Book>
)

internal data class LibraryBookGroups(
    val authors: List<AuthorItem>,
    val series: List<SeriesItem>,
    val formats: List<FormatItem>,
    val folders: List<FolderItem>
)

internal fun resolveCurrentDrilldownBooks(
    allActiveBooks: List<Book>,
    authorRawName: String?,
    seriesName: String?,
    format: String?,
    folderPath: String?
): List<Book> = when {
    authorRawName != null -> allActiveBooks.filter { it.author == authorRawName }
    seriesName != null -> allActiveBooks.filter { it.series == seriesName }
    format != null -> allActiveBooks.filter { it.format.equals(format, ignoreCase = true) }
    folderPath != null -> allActiveBooks.filter {
        (File(it.filePath).parent ?: "/storage/emulated/0") == folderPath
    }
    else -> allActiveBooks
}

private fun deriveLibraryBookGroups(allActiveBooks: List<Book>): LibraryBookGroups {
    val authors = allActiveBooks.groupBy { it.author }.map { (rawName, books) ->
        AuthorItem(LibraryViewModel.formatAuthorLastNameFirst(rawName), rawName, books.size, books)
    }.sortedBy { it.displayName.lowercase() }
    val series = allActiveBooks.filter { !it.series.isNullOrBlank() }.groupBy { it.series!! }
        .map { (name, books) -> SeriesItem(name, books.size, books) }
        .sortedBy { it.name.lowercase() }
    val formatNames = listOf(
        "EPUB", "PDF", "WORD", "DOC", "DOCX", "ODT", "TXT", "MOBI",
        "RTF", "DJVU", "FB2", "CHM", "AZW", "COMIC", "CBR", "CBZ"
    )
    val formats = allActiveBooks.groupBy { it.format.uppercase() }.let { groups ->
        formatNames.map { format ->
            val books = groups[format] ?: emptyList()
            FormatItem(format, books.size, books)
        }
    }
    val folders = allActiveBooks.groupBy {
        try {
            File(it.filePath).parent ?: "/storage/emulated/0"
        } catch (_: Exception) {
            "/storage/emulated/0"
        }
    }.map { (path, books) ->
        FolderItem(File(path).name.ifBlank { path }, path, books.size, books)
    }.sortedBy { it.folderName.lowercase() }
    return LibraryBookGroups(authors, series, formats, folders)
}

data class LibraryUiState(
    val currentSection: LibrarySection = LibrarySection.ALL_DOCUMENTS,
    val books: List<Book> = emptyList(),
    val allActiveBooks: List<Book> = emptyList(),
    val readingNowBooks: List<Book> = emptyList(),
    val trashBooks: List<Book> = emptyList(),
    val authors: List<AuthorItem> = emptyList(),
    val series: List<SeriesItem> = emptyList(),
    val collections: List<CollectionWithCount> = emptyList(),
    val formats: List<FormatItem> = emptyList(),
    val folders: List<FolderItem> = emptyList(),
    val searchHistory: List<SearchQuery> = emptyList(),
    val sortCriterion: SortCriterion = SortCriterion.NAME,
    val filterCriteria: FilterCriteria = FilterCriteria(),
    val searchQuery: String = "",
    val isSearchOpen: Boolean = false,
    val selectedAuthor: AuthorItem? = null,
    val selectedSeries: SeriesItem? = null,
    val selectedCollection: CollectionWithCount? = null,
    val selectedFormat: FormatItem? = null,
    val selectedFolder: FolderItem? = null,
    val isScanning: Boolean = false,
    val scanCount: Int = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: BookRepository,
    private val scanner: StorageScanner
) : ViewModel() {

    private val _currentSection = MutableStateFlow(LibrarySection.ALL_DOCUMENTS)
    private val _sortCriterion = MutableStateFlow(SortCriterion.NAME)
    private val _filterCriteria = MutableStateFlow(FilterCriteria())
    private val _searchQuery = MutableStateFlow("")
    private val _isSearchOpen = MutableStateFlow(false)

    private val _selectedAuthor = MutableStateFlow<AuthorItem?>(null)
    private val _selectedSeries = MutableStateFlow<SeriesItem?>(null)
    private val _selectedCollection = MutableStateFlow<CollectionWithCount?>(null)
    private val _selectedFormat = MutableStateFlow<FormatItem?>(null)
    private val _selectedFolder = MutableStateFlow<FolderItem?>(null)

    private val libraryResults: Flow<LibraryUiState> = combine(
        repository.getAllBooks().distinctUntilChanged()
            .map { books -> books to deriveLibraryBookGroups(books) }
            .flowOn(Dispatchers.Default),
        repository.getReadingNow(),
        repository.getTrashBooks(),
        repository.getCollectionsWithCount(),
        repository.getSearchHistory(),
        repository.getAllBookCollections(),
        _currentSection,
        _sortCriterion,
        _filterCriteria,
        _searchQuery.debouncedLibraryQuery(),
        _isSearchOpen,
        combine(
            _selectedAuthor,
            _selectedSeries,
            _selectedCollection,
            _selectedFormat,
            _selectedFolder
        ) { a, s, c, f, fol -> DrilldownState(a, s, c, f, fol) },
        scanner.isScanning
    ) { args: Array<Any?> -> args }.mapLatest { args ->
        withContext(Dispatchers.Default) {
        @Suppress("UNCHECKED_CAST")
        val activeBooksAndGroups = args[0] as Pair<List<Book>, LibraryBookGroups>
        val (allActiveBooks, bookGroups) = activeBooksAndGroups
        @Suppress("UNCHECKED_CAST")
        val readingNow = args[1] as List<Book>
        @Suppress("UNCHECKED_CAST")
        val trashBooks = args[2] as List<Book>
        @Suppress("UNCHECKED_CAST")
        val collections = args[3] as List<CollectionWithCount>
        @Suppress("UNCHECKED_CAST")
        val searchHistory = args[4] as List<SearchQuery>
        @Suppress("UNCHECKED_CAST")
        val allBookCollections = args[5] as List<BookCollectionCrossRef>
        val currentSection = args[6] as LibrarySection
        val sortCriterion = args[7] as SortCriterion
        val filterCriteria = args[8] as FilterCriteria
        val searchQuery = args[9] as String
        val isSearchOpen = args[10] as Boolean
        val drilldown = args[11] as DrilldownState
        val isScanning = args[12] as Boolean

        // Build Author items: LastName FirstName — Count
        val authorsList = bookGroups.authors
        val seriesList = bookGroups.series
        val formatsList = bookGroups.formats
        val foldersList = bookGroups.folders

        var targetBooks = when {
            drilldown.selectedAuthor != null -> resolveCurrentDrilldownBooks(
                allActiveBooks, drilldown.selectedAuthor.rawName, null, null, null
            )
            drilldown.selectedSeries != null -> resolveCurrentDrilldownBooks(
                allActiveBooks, null, drilldown.selectedSeries.name, null, null
            )
            drilldown.selectedCollection != null -> {
                val targetBookIds = allBookCollections
                    .filter { it.collectionId == drilldown.selectedCollection.id }
                    .map { it.bookId }
                    .toSet()
                allActiveBooks.filter { it.id in targetBookIds }
            }
            drilldown.selectedFormat != null -> resolveCurrentDrilldownBooks(
                allActiveBooks, null, null, drilldown.selectedFormat.format, null
            )
            drilldown.selectedFolder != null -> resolveCurrentDrilldownBooks(
                allActiveBooks, null, null, null, drilldown.selectedFolder.folderPath
            )
            else -> when (currentSection) {
                LibrarySection.READING_NOW -> readingNow
                LibrarySection.ALL_DOCUMENTS -> allActiveBooks
                LibrarySection.FAVORITES -> allActiveBooks.filter { it.isFavorite }
                LibrarySection.TO_READ -> allActiveBooks.filter { it.isToRead }
                LibrarySection.HAVE_READ -> allActiveBooks.filter { it.isHaveRead }
                LibrarySection.TRASH -> trashBooks
                LibrarySection.DOWNLOADS -> allActiveBooks.filter {
                    it.filePath.contains("/Download", ignoreCase = true) ||
                    it.filePath.contains("OpenReadEra", ignoreCase = true)
                }
                else -> allActiveBooks
            }
        }


        targetBooks = filterAndSortLibraryBooks(
            targetBooks, filterCriteria, searchQuery, sortCriterion, allBookCollections
        )

        LibraryUiState(
            currentSection = currentSection,
            books = targetBooks,
            allActiveBooks = allActiveBooks,
            readingNowBooks = readingNow,
            trashBooks = trashBooks,
            authors = authorsList,
            series = seriesList,
            collections = collections,
            formats = formatsList,
            folders = foldersList,
            searchHistory = searchHistory,
            sortCriterion = sortCriterion,
            filterCriteria = filterCriteria,
            searchQuery = searchQuery,
            isSearchOpen = isSearchOpen,
            selectedAuthor = drilldown.selectedAuthor?.let { selected ->
                bookGroups.authors.firstOrNull { it.rawName == selected.rawName }
                    ?: selected.copy(bookCount = 0, books = emptyList())
            },
            selectedSeries = drilldown.selectedSeries?.let { selected ->
                bookGroups.series.firstOrNull { it.name == selected.name }
                    ?: selected.copy(bookCount = 0, books = emptyList())
            },
            selectedCollection = drilldown.selectedCollection,
            selectedFormat = drilldown.selectedFormat?.let { selected ->
                bookGroups.formats.firstOrNull { it.format == selected.format.uppercase() }
                    ?: selected.copy(bookCount = 0, books = emptyList())
            },
            selectedFolder = drilldown.selectedFolder?.let { selected ->
                bookGroups.folders.firstOrNull { it.folderPath == selected.folderPath }
                    ?: selected.copy(bookCount = 0, books = emptyList())
            },
            isScanning = isScanning,
            scanCount = allActiveBooks.size
        )
        }
    }

    // Keep text-field edits immediate; only matching waits for the quiet period.
    val uiState: StateFlow<LibraryUiState> = combine(libraryResults, _searchQuery) { state, query ->
        state.copy(searchQuery = query)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        LibraryUiState()
    )

    fun setSection(section: LibrarySection) {
        _currentSection.value = section
        clearDrilldown()
        if (section == LibrarySection.ZLIBRARY) {
            _isSearchOpen.value = false
        }
    }

    fun setSortCriterion(criterion: SortCriterion) {
        _sortCriterion.value = criterion
    }

    fun setFilterCriteria(criteria: FilterCriteria) {
        _filterCriteria.value = criteria
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSearchOpen(isOpen: Boolean) {
        _isSearchOpen.value = isOpen
        if (!isOpen) {
            _searchQuery.value = ""
        }
    }

    fun submitSearch(query: String) {
        if (query.isNotBlank()) {
            _searchQuery.value = query
            viewModelScope.launch {
                repository.addSearchQuery(query)
            }
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            repository.clearSearchHistory()
        }
    }

    fun selectAuthor(author: AuthorItem?) {
        _selectedAuthor.value = author
    }

    fun selectSeries(series: SeriesItem?) {
        _selectedSeries.value = series
    }

    fun selectCollection(collection: CollectionWithCount?) {
        _selectedCollection.value = collection
    }

    fun selectFormat(format: FormatItem?) {
        _selectedFormat.value = format
    }

    fun selectFolder(folder: FolderItem?) {
        _selectedFolder.value = folder
    }

    fun clearDrilldown() {
        _selectedAuthor.value = null
        _selectedSeries.value = null
        _selectedCollection.value = null
        _selectedFormat.value = null
        _selectedFolder.value = null
    }

    // Direct Card Actions
    fun toggleFavorite(bookId: Long) {
        viewModelScope.launch {
            repository.toggleFavorite(bookId)
        }
    }

    fun toggleToRead(bookId: Long) {
        viewModelScope.launch {
            repository.toggleToRead(bookId)
        }
    }

    fun toggleHaveRead(bookId: Long) {
        viewModelScope.launch {
            repository.toggleHaveRead(bookId)
        }
    }

    fun moveToTrash(bookId: Long) {
        viewModelScope.launch {
            repository.moveToTrash(bookId)
        }
    }

    fun removeFromReadingNow(bookId: Long) {
        viewModelScope.launch {
            repository.removeFromReadingNow(bookId)
        }
    }

    fun markAsReading(bookId: Long) {
        viewModelScope.launch {
            repository.markAsReading(bookId)
        }
    }

    fun restoreFromTrash(bookId: Long) {
        viewModelScope.launch {
            repository.restoreFromTrash(bookId)
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            repository.emptyTrash()
        }
    }

    fun clearCurrentSection() {
        viewModelScope.launch {
            when (_currentSection.value) {
                LibrarySection.READING_NOW -> repository.clearReadingNow()
                LibrarySection.FAVORITES -> repository.clearFavorites()
                LibrarySection.TO_READ -> repository.clearToRead()
                LibrarySection.HAVE_READ -> repository.clearHaveRead()
                else -> {}
            }
        }
    }

    fun createCollection(name: String) {
        if (name.isNotBlank()) {
            viewModelScope.launch {
                repository.addCollection(name.trim())
            }
        }
    }

    fun setBookCollections(bookId: Long, collectionIds: List<Long>) {
        viewModelScope.launch {
            repository.setBookCollections(bookId, collectionIds)
        }
    }

    fun triggerScan() {
        viewModelScope.launch {
            scanner.scanStorage()
        }
    }

    suspend fun getCollectionsForBook(bookId: Long): List<Collection> {
        return repository.getCollectionsForBookSync(bookId)
    }

    fun updateReview(bookId: Long, rating: Float?, review: String?, tags: String? = null) {
        viewModelScope.launch {
            repository.updateReview(bookId, rating, review, System.currentTimeMillis(), tags)
        }
    }

    suspend fun getBookByPath(path: String): Book? {
        return repository.getBookByPath(path)
    }

    suspend fun getOrScanBookByPath(path: String): Book? {
        val existing = repository.getBookByPath(path)
        if (existing != null) return existing
        val file = File(path)
        if (file.exists()) {
            return scanner.scanSingleFile(file)
        }
        return null
    }

    val allQuotes: Flow<List<Quote>> = repository.getAllQuotes()

    fun deleteQuote(quote: Quote) {
        viewModelScope.launch {
            repository.deleteQuote(quote)
        }
    }

    fun updateQuote(quote: Quote) {
        viewModelScope.launch {
            repository.updateQuote(quote)
        }
    }

    fun openQuoteInReader(quote: Quote, onOpenReader: (Long) -> Unit) {
        viewModelScope.launch {
            val book = repository.getBookByIdSync(quote.bookId)
            if (book != null) {
                repository.updateProgress(book.id, quote.page, book.progressPercent, book.totalPages)
                onOpenReader(book.id)
            }
        }
    }

    fun rescan() {
        viewModelScope.launch {
            scanner.scanStorage()
        }
    }

    companion object {
        fun formatAuthorLastNameFirst(rawAuthor: String): String {
            val trimmed = rawAuthor.trim()
            if (trimmed.isBlank() || trimmed == "Autor desconocido") return "Autor desconocido"
            if (trimmed.contains(",")) return trimmed // Already has comma
            val parts = trimmed.split("\\s+".toRegex()).filter { it.isNotBlank() }
            return if (parts.size >= 2) {
                val lastName = parts.last()
                val firstNames = parts.dropLast(1).joinToString(" ")
                "$lastName $firstNames"
            } else {
                trimmed
            }
        }
    }
}

private data class DrilldownState(
    val selectedAuthor: AuthorItem?,
    val selectedSeries: SeriesItem?,
    val selectedCollection: CollectionWithCount?,
    val selectedFormat: FormatItem?,
    val selectedFolder: FolderItem?
)
