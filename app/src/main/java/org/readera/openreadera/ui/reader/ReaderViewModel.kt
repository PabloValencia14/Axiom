package org.readera.openreadera.ui.reader

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import org.readera.openreadera.data.model.*
import org.readera.openreadera.data.model.Collection as BookCollection
import org.readera.openreadera.data.db.CollectionWithCount
import org.readera.openreadera.data.preferences.ReaderPreferences
import org.readera.openreadera.data.repository.BookRepository
import org.readera.openreadera.data.repository.DrawingStrokeSyncGate
import org.readera.openreadera.data.scanner.StorageScanner
import org.readera.openreadera.data.scanner.PageOcrRecognizer
import org.readera.openreadera.data.stats.ReadingStatsManager
import org.readera.openreadera.engine.*
import org.readera.openreadera.tts.NaturalTtsPlayer
import org.readera.openreadera.ui.reader.components.DrawingTool
import org.readera.openreadera.ui.reader.components.ReaderRenderCache
import org.readera.openreadera.ui.reader.components.readerPrefetchPages
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.sqrt

data class ReaderPageTextState(
    val layout: PageTextLayout? = null,
    val status: String = "Preparando texto…",
    val isLoading: Boolean = true
)

data class ReaderUiState(
    val book: Book? = null,
    val currentPage: Int = 0,
    val totalPages: Int = 1,
    val estimatedMinutesRemaining: Int = 0,
    val isControlsVisible: Boolean = false,
    val currentPageBitmap: Bitmap? = null,
    val secondPageBitmap: Bitmap? = null,
    val isLoadingPage: Boolean = false,
    val readingAnchorPage: Int? = null,
    val settings: ReaderSettings = ReaderSettings(),
    val outline: List<OutlineItem> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val quotes: List<Quote> = emptyList(),
    val strokes: List<DrawingStroke> = emptyList(),
    val activeInkPage: Int = 0,
    val removedInkStrokes: List<DrawingStroke> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val writingMode: Boolean = false,
    val textLayoutGeneration: Int = 0,
    val pageText: Map<Int, ReaderPageTextState> = emptyMap(),
    val currentDrawingTool: DrawingTool = DrawingTool.PEN,
    val currentStrokeColorHex: String = "#FACC15",
    val currentStrokeWidth: Float = 4f,
    val isTtsActive: Boolean = false,
    val isTtsPlaying: Boolean = false,
    val ttsSleepTimerMinutes: Int? = null,
    val ttsText: String = "",
    val searchResults: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null
)

class ReaderViewModel(
    private val bookId: Long,
    private val repository: BookRepository,
    private val preferences: ReaderPreferences,
    private val engineManager: EngineManager,
    private val context: Context
) : ViewModel() {

    val allCollectionsWithCount: Flow<List<CollectionWithCount>> = repository.getCollectionsWithCount()
    val currentBookCollections: Flow<List<BookCollection>> = repository.getCollectionsForBook(bookId)

    private val _uiState = MutableStateFlow(
        ReaderUiState(
            currentDrawingTool = runCatching { DrawingTool.valueOf(preferences.drawingToolName()) }
                .getOrDefault(DrawingTool.PEN),
            currentStrokeColorHex = preferences.drawingColorHex(),
            currentStrokeWidth = preferences.drawingStrokeWidth().coerceIn(1f, 32f),
        )
    )
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    val appThemeMode: StateFlow<AppThemeMode> = preferences.appThemeMode

    private var engine: DocumentEngine? = null
    private var activeRenderJob: Job? = null
    private var prefetchJob: Job? = null
    private var renderOptionsJob: Job? = null
    private var searchJob: Job? = null
    private var ttsStartJob: Job? = null
    private var searchGeneration = 0
    private val persistentScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val drawingMutationLock = Any()
    private var lastDrawingMutation: Job? = null
    private val redoStack = java.util.concurrent.ConcurrentLinkedDeque<DrawingStroke>()
    private var navigationDirection = 1
    private val renderCache = ReaderRenderCache((Runtime.getRuntime().maxMemory() / 4)
        .coerceIn(32L * 1024 * 1024, 128L * 1024 * 1024).toInt())

    private val ttsSleepTimer = TtsSleepTimer(viewModelScope, ::stopTts)
    // Filled on the existing IO render path, never by Compose. Bitmap rounding is not page geometry.
    private val pageAspectRatios = ConcurrentHashMap<Int, Float>()
    private val pageTextCache = object : LruCache<Int, ReaderPageTextState>(2 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: ReaderPageTextState): Int =
            ((value.layout?.text?.length ?: 0) * 2 + (value.layout?.words?.size ?: 0) * 64 + 128)
    }
    private val pageTextJobs = mutableMapOf<Int, Job>()
    private var requestedTextPages: Set<Int> = emptySet()
    private val ocrMutex = Mutex()
    private val ocrDelegate = lazy { PageOcrRecognizer() }

    private fun invalidatePageText() {
        pageTextJobs.values.forEach(Job::cancel)
        pageTextJobs.clear()
        pageTextCache.evictAll()
        _uiState.update { it.copy(textLayoutGeneration = it.textLayoutGeneration + 1, pageText = emptyMap()) }
    }

    /** Visible pages only; neither display bitmaps nor zoom are recognition cache keys. */
    fun requestPageText(pages: Set<Int>) {
        val docEngine = engine ?: return
        requestedTextPages = pages.filter { it in 0 until _uiState.value.totalPages }.toSet()
        val obsolete = pageTextJobs.keys - requestedTextPages
        obsolete.forEach { pageTextJobs.remove(it)?.cancel() }
        _uiState.update { it.copy(pageText = it.pageText.filterKeys(requestedTextPages::contains)) }
        for (page in requestedTextPages) {
            val cached = pageTextCache.get(page)
            if (cached != null) {
                _uiState.update { it.copy(pageText = it.pageText + (page to cached)) }
                continue
            }
            if (pageTextJobs.containsKey(page)) continue
            val generation = _uiState.value.textLayoutGeneration
            val options = createRenderOptions(_uiState.value.settings).copy(
                nightMode = false, oledMode = false, twilightMode = false)
            _uiState.update { it.copy(pageText = it.pageText + (page to ReaderPageTextState())) }
            pageTextJobs[page] = viewModelScope.launch {
                var native: PageTextLayout? = null
                try {
                    val extracted = withContext(Dispatchers.IO) {
                        synchronized(docEngine) { docEngine.getPageTextLayout(page) }
                    }
                    native = extracted
                    val layout = if (extracted.text.isNotBlank() && extracted.words.isNotEmpty()) extracted else {
                        if (engine === docEngine && generation == _uiState.value.textLayoutGeneration &&
                            page in requestedTextPages) {
                            _uiState.update { it.copy(pageText = it.pageText +
                                (page to ReaderPageTextState(status = "Reconociendo texto en el dispositivo…"))) }
                        }
                        val recognized = withContext(Dispatchers.IO) {
                            ocrMutex.withLock {
                                currentCoroutineContext().ensureActive()
                                val bitmap = renderPageForOcr(page, docEngine, options, currentCoroutineContext())
                                try { ocrDelegate.value.recognize(bitmap) } finally { bitmap.recycle() }
                            }
                        }
                        if (recognized.text.isBlank() && extracted.text.isNotBlank()) extracted else recognized
                    }
                    currentCoroutineContext().ensureActive()
                    val resolved = ReaderPageTextState(layout, when {
                        layout.text.isBlank() -> "No se detectó texto en esta página"
                        layout.words.isEmpty() -> "Texto disponible; no se pudo localizar para resaltar"
                        layout.source == PageTextSource.OCR -> "Texto reconocido · OCR local"
                        else -> "Texto del documento disponible"
                    }, isLoading = false)
                    publishPageText(page, generation, docEngine, resolved)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    publishPageText(page, generation, docEngine, ReaderPageTextState(
                        layout = native?.takeIf { it.text.isNotBlank() },
                        status = "No se pudo reconocer el texto de esta página", isLoading = false))
                } finally {
                    if (pageTextJobs[page] === currentCoroutineContext()[Job]) pageTextJobs.remove(page)
                }
            }
        }
    }

    private fun publishPageText(page: Int, generation: Int, docEngine: DocumentEngine, value: ReaderPageTextState) {
        if (engine !== docEngine || generation != _uiState.value.textLayoutGeneration) return
        pageTextCache.put(page, value)
        if (page in requestedTextPages) _uiState.update { it.copy(pageText = it.pageText + (page to value)) }
    }

    private fun renderPageForOcr(
        page: Int, docEngine: DocumentEngine, options: RenderOptions, renderContext: kotlin.coroutines.CoroutineContext
    ): Bitmap = synchronized(docEngine) {
                renderContext.ensureActive()
                val size = docEngine.getPageSize(page)
                require(size.width.isFinite() && size.height.isFinite() && size.width > 0f && size.height > 0f)
                val scale = minOf(2400f / maxOf(size.width, size.height),
                    sqrt(4_000_000.0 / (size.width.toDouble() * size.height)).toFloat())
                val bitmap = Bitmap.createBitmap((size.width * scale).toInt().coerceAtLeast(1),
                    (size.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    check(docEngine.renderPage(page, bitmap, options)) { "Page render failed" }
                    renderContext.ensureActive()
                    bitmap
                } catch (failure: Throwable) {
                    bitmap.recycle()
                    throw failure
                }
        }

    private fun invalidateRenderedPages() {
        activeRenderJob?.cancel()
        prefetchJob?.cancel()
        renderCache.clear()
    }
    private val statsManager by lazy { ReadingStatsManager(context) }
    private var lastPageTurnTimeMs = System.currentTimeMillis()

    private val naturalTtsPlayerDelegate: Lazy<NaturalTtsPlayer> = lazy {
        NaturalTtsPlayer(
            context = context,
            onSentenceChanged = { _, sentence ->
                _uiState.update { it.copy(ttsText = sentence) }
            },
            onStateChanged = { isPlaying ->
                val active = naturalTtsPlayer.isActiveForPlayback
                if (!active) ttsSleepTimer.cancel()
                _uiState.update {
                    it.copy(
                        isTtsPlaying = isPlaying,
                        isTtsActive = active,
                        ttsSleepTimerMinutes = if (active) it.ttsSleepTimerMinutes else null
                    )
                }
            },
            onError = { message -> _uiState.update { it.copy(error = message) } },
            initialSpeechRate = preferences.ttsSpeechRate.value,
            initialOnlineTtsEnabled = preferences.useNeuralTtsOnline.value
        )
    }
    private val naturalTtsPlayer: NaturalTtsPlayer by naturalTtsPlayerDelegate

    private fun trackPageReadingTime() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastPageTurnTimeMs
        lastPageTurnTimeMs = now
        if (elapsed in 3_000..300_000) {
            statsManager.recordReadingTime(elapsed, pagesTurned = 1)
        }
    }

    private fun calculateEstimatedTimeRemaining(currentPage: Int, totalPages: Int): Int {
        val remainingPages = maxOf(0, totalPages - currentPage - 1)
        val remainingWords = remainingPages * 250
        return maxOf(1, remainingWords / 220)
    }

    fun toggleBionicReading() {
        val current = _uiState.value.settings.bionicReading
        preferences.updateBionicReading(!current)
    }

    private var isSystemDark: Boolean = run {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    fun setSystemDark(isDark: Boolean) {
        if (isSystemDark != isDark) {
            isSystemDark = isDark
            if (_uiState.value.settings.theme == ReaderColorTheme.SYSTEM) {
                applyThemeAndRerender()
            }
        }
    }

    private fun getEffectiveTheme(theme: ReaderColorTheme): ReaderColorTheme {
        return if (theme == ReaderColorTheme.SYSTEM) {
            val appMode = preferences.appThemeMode.value
            if (appMode == AppThemeMode.OLED) {
                ReaderColorTheme.OLED
            } else if (isSystemDark) {
                ReaderColorTheme.NIGHT
            } else {
                ReaderColorTheme.DAY
            }
        } else {
            theme
        }
    }

    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0
    private var currentTargetAspectRatio: Float = 0f
    private var pdfQualityZoom: Float = 1f
    private var windowedMode: Boolean = false
    private var presentationMode: Boolean = false

    private fun effectiveViewMode(settings: ReaderSettings = _uiState.value.settings): ReaderViewMode {
        return if (windowedMode || presentationMode) ReaderViewMode.PAGED else settings.viewMode
    }

    private fun isDoublePageMode(settings: ReaderSettings = _uiState.value.settings): Boolean {
        return effectiveViewMode(settings) == ReaderViewMode.DOUBLE_PAGE
    }

    fun setWindowedMode(enabled: Boolean) {
        if (windowedMode == enabled) return
        windowedMode = enabled
        invalidateRenderedPages()
        if (engine !is AndroidPdfEngine) {
            currentTargetAspectRatio = 0f
            recalculateAspectRatioAndRepaginateIfNeeded()
        } else {
            renderCurrentPage()
        }
    }
    fun setPresentationMode(enabled: Boolean) {
        if (enabled && _uiState.value.book?.format?.equals("PDF", ignoreCase = true) != true) return
        presentationMode = enabled
        invalidateRenderedPages()
        if (engine !is AndroidPdfEngine) {
            currentTargetAspectRatio = 0f
            recalculateAspectRatioAndRepaginateIfNeeded()
        } else {
            renderCurrentPage()
        }
    }

    fun setViewportDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (viewportWidth == width && viewportHeight == height) return
        viewportWidth = width
        viewportHeight = height
        if (engine is AndroidPdfEngine || engine is CbzEngine) {
            invalidateRenderedPages()
            renderCurrentPage()
            return
        }
        recalculateAspectRatioAndRepaginateIfNeeded()
    }

    fun setZoomScale(scale: Float) {
        val requested = scale.coerceIn(1f, 5f)
        val nextQuality = when {
            requested <= 1.25f -> 1f
            requested <= 1.75f -> 1.5f
            else -> 2f
        }
        if (engine !is AndroidPdfEngine || nextQuality == pdfQualityZoom) return
        pdfQualityZoom = nextQuality
        invalidateRenderedPages()
        renderCurrentPage()
    }

    private fun getTargetAspectRatioForMode(viewMode: ReaderViewMode, width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 1600f / 1200f
        return when (viewMode) {
            ReaderViewMode.DOUBLE_PAGE -> (height.toFloat() * 2f) / width.toFloat()
            ReaderViewMode.VERTICAL_SCROLL -> 1.4f
            else -> height.toFloat() / width.toFloat()
        }
    }

    private fun getCurrentProgressRatio(): Float {
        val oldTotal = _uiState.value.totalPages
        val oldPage = _uiState.value.currentPage
        if (oldTotal > 1 && oldPage > 0) {
            return (oldPage.toFloat() / (oldTotal - 1)).coerceIn(0f, 1f)
        }
        val b = _uiState.value.book
        if (b != null) {
            if (b.totalPages > 1 && b.currentPage > 0) {
                return (b.currentPage.toFloat() / (b.totalPages - 1)).coerceIn(0f, 1f)
            }
            if (b.progressPercent > 0f) {
                return (b.progressPercent / 100f).coerceIn(0f, 1f)
            }
        }
        return 0f
    }

    private fun recalculateAspectRatioAndRepaginateIfNeeded() {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        // PDF and comic dimensions belong to the document, not reflow aspect ratio.
        if (engine is AndroidPdfEngine || engine is CbzEngine) return
        val newRatio = getTargetAspectRatioForMode(effectiveViewMode(), viewportWidth, viewportHeight)
        if (kotlin.math.abs(newRatio - currentTargetAspectRatio) > 0.02f) {
            currentTargetAspectRatio = newRatio
            val currentEngine = engine
            if (currentEngine != null) {
                applyOptionsAndRender(currentEngine)
            }
        }
    }

    private fun createRenderOptions(settings: ReaderSettings): RenderOptions {
        val effective = getEffectiveTheme(settings.theme)
        return RenderOptions(
            nightMode = effective == ReaderColorTheme.NIGHT,
            oledMode = effective == ReaderColorTheme.OLED,
            twilightMode = effective == ReaderColorTheme.SEPIA,
            fontSizeSp = settings.fontSizeSp,
            fontFamily = settings.fontFamily,
            lineSpacing = settings.lineSpacing,
            marginHorizontalDp = settings.marginHorizontalDp,
            bionicReading = settings.bionicReading,
            fontBold = settings.fontBold,
            targetAspectRatio = currentTargetAspectRatio
        )
    }

    private fun applyThemeAndRerender() {
        val currentEngine = engine ?: return
        if (currentEngine is AndroidPdfEngine) {
            invalidateRenderedPages()
            renderCurrentPage()
        } else {
            applyOptionsAndRender(currentEngine)
        }
    }

    private fun applyOptionsAndRender(currentEngine: DocumentEngine) {
        renderOptionsJob?.cancel()
        invalidateRenderedPages()
        val options = createRenderOptions(_uiState.value.settings)
        renderOptionsJob = viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoadingPage = true, error = null) }
                val count = withContext(Dispatchers.IO) {
                    val optionsContext = currentCoroutineContext()
                    synchronized(currentEngine) {
                        optionsContext.ensureActive()
                        currentEngine.applyOptions(options).coerceAtLeast(1)
                    }
                }
                // Navigation while reflow was running still uses the previous page count.
                val oldPage = _uiState.value.currentPage
                val progress = getCurrentProgressRatio()
                val page = if (progress > 0f) (progress * (count - 1)).toInt().coerceIn(0, count - 1)
                    else oldPage.coerceIn(0, count - 1)
                invalidatePageText()
                pageAspectRatios.clear()
                _uiState.update {
                    it.copy(totalPages = count, currentPage = page,
                        activeInkPage = if (oldPage == page) it.activeInkPage else page,
                        currentPageBitmap = if (oldPage == page) it.currentPageBitmap else null,
                        secondPageBitmap = if (oldPage == page) it.secondPageBitmap else null,
                        estimatedMinutesRemaining = calculateEstimatedTimeRemaining(page, count)).withInkActions()
                }
                renderOptionsJob = null
                renderCurrentPage()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Unable to apply reader options", error)
                _uiState.update { it.copy(isLoadingPage = false, error = "No se pudo actualizar la configuración de lectura") }
            } finally {
                if (renderOptionsJob === currentCoroutineContext()[Job]) renderOptionsJob = null
            }
        }
    }

    companion object {
        private const val TAG = "ReaderViewModel"
        private const val MAX_PDF_PAGE_PIXELS = 16L * 1024 * 1024
    }

    init {
        loadBookAndOpen()
        observePreferences()
        observeAppTheme()
        observeTtsSettings()
        observeBookmarks()
        observeQuotes()
        observeStrokes()
        observeHardwareEvents()
    }

    private fun observeAppTheme() {
        viewModelScope.launch {
            preferences.appThemeMode.collect {
                if (_uiState.value.settings.theme == ReaderColorTheme.SYSTEM) {
                    applyThemeAndRerender()
                }
            }
        }
    }

    private fun observeTtsSettings() {
        viewModelScope.launch {
            preferences.ttsSpeechRate.collect { rate ->
                if (naturalTtsPlayerDelegate.isInitialized()) {
                    naturalTtsPlayer.setSpeechRate(rate)
                }
            }
        }
        viewModelScope.launch {
            preferences.useNeuralTtsOnline.collect { enabled ->
                if (naturalTtsPlayerDelegate.isInitialized()) {
                    naturalTtsPlayer.setOnlineTtsEnabled(enabled)
                }
            }
        }
    }

    private fun loadBookAndOpen() {
        viewModelScope.launch {
            repository.markAsReading(bookId)
            val book = repository.getBookByIdSync(bookId)
            if (book == null) {
                _uiState.update { it.copy(error = "No se ha encontrado el libro") }
                return@launch
            }

            _uiState.update { it.copy(book = book, currentPage = book.currentPage, isLoadingPage = true) }

            withContext(Dispatchers.IO) {
                var docEngine: DocumentEngine? = null
                try {
                    val activeEngine = engineManager.getEngineForDocument(book.filePath)
                    docEngine = activeEngine
                    val opened = try {
                        activeEngine.open(book.filePath)
                    } catch (openError: Exception) {
                        if (openError is CancellationException) throw openError
                        Log.w(TAG, "Error opening ${activeEngine::class.simpleName}", openError)
                        false
                    }

                    if (!opened) {
                        val unsupported = activeEngine.engineName.takeIf {
                            it.startsWith("Formato no compatible")
                        }
                        try {
                            activeEngine.close()
                        } catch (_: Exception) {
                        }
                        docEngine = null
                        _uiState.update {
                            it.copy(
                                isLoadingPage = false,
                                error = unsupported ?: "No se pudo abrir ${book.format}: archivo dañado o formato incompatible"
                            )
                        }
                        return@withContext
                    }

                    if (currentTargetAspectRatio > 0.2f) {
                        val options = createRenderOptions(_uiState.value.settings)
                        activeEngine.applyOptions(options)
                    }
                    val count = maxOf(1, activeEngine.getPageCount())
                    val outline = activeEngine.getOutline()

                    val targetPage = if (activeEngine is AndroidPdfEngine) {
                        book.currentPage.coerceIn(0, count - 1)
                    } else if (book.totalPages > 1 && count > 1 && book.currentPage > 0) {
                        val ratio = (book.currentPage.toFloat() / (book.totalPages - 1)).coerceIn(0f, 1f)
                        (ratio * (count - 1)).toInt().coerceIn(0, count - 1)
                    } else if (book.progressPercent > 0f && count > 1) {
                        ((book.progressPercent / 100f) * (count - 1)).toInt().coerceIn(0, count - 1)
                    } else {
                        book.currentPage.coerceIn(0, count - 1)
                    }

                    val remaining = calculateEstimatedTimeRemaining(targetPage, count)
                    withContext(Dispatchers.Main.immediate) {
                        engine = activeEngine
                        _uiState.update {
                            it.copy(
                                totalPages = count,
                                currentPage = targetPage,
                                outline = outline,
                                estimatedMinutesRemaining = remaining
                            )
                        }
                        renderCurrentPage()
                    }
                    docEngine = null
                } catch (e: Exception) {
                    if (engine === docEngine) engine = null
                    try {
                        docEngine?.close()
                    } catch (_: Exception) {
                    }
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Error initializing reader engine", e)
                    _uiState.update {
                        it.copy(isLoadingPage = false, error = e.message ?: "No se pudo abrir ${book.format}")
                    }
                }
            }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferences.settings.collect { settings ->
                val previousSettings = _uiState.value.settings
                val prevViewMode = previousSettings.viewMode
                _uiState.update {
                    it.copy(settings = settings,
                        activeInkPage = if (prevViewMode != settings.viewMode) it.currentPage else it.activeInkPage)
                        .withInkActions()
                }
                if (prevViewMode != settings.viewMode && viewportWidth > 0 && viewportHeight > 0) {
                    currentTargetAspectRatio = getTargetAspectRatioForMode(effectiveViewMode(settings), viewportWidth, viewportHeight)
                }
                val currentEngine = engine
                if (currentEngine is AndroidPdfEngine) {
                    val sameColors = getEffectiveTheme(previousSettings.theme) == getEffectiveTheme(settings.theme)
                    val samePageWidth = isDoublePageMode(previousSettings) == isDoublePageMode(settings)
                    // Brightness, font, spacing and margins do not change PDF pixels.
                    if (sameColors && samePageWidth) return@collect
                    invalidateRenderedPages()
                    renderCurrentPage()
                    return@collect
                }
                val samePixels = createRenderOptions(previousSettings) == createRenderOptions(settings)
                if (samePixels && prevViewMode == settings.viewMode) return@collect
                if (currentEngine != null) applyOptionsAndRender(currentEngine)
                else invalidateRenderedPages()
            }
        }
    }

    private fun observeBookmarks() {
        viewModelScope.launch {
            repository.getBookmarksForBook(bookId).collect { bookmarks ->
                _uiState.update { it.copy(bookmarks = bookmarks) }
            }
        }
    }

    private fun observeQuotes() {
        viewModelScope.launch {
            repository.getQuotesForBook(bookId).collect { quotes ->
                _uiState.update { it.copy(quotes = quotes) }
            }
        }
    }


    private fun isStrokeOnPage(stroke: DrawingStroke, page: Int): Boolean {
        val range = engine?.getPageTextRange(page)
        val anchor = stroke.pageAnchor()
        return if (anchor != null && range != null) range.contains(anchor) else stroke.page == page
    }

    private fun strokesOnPage(strokes: Iterable<DrawingStroke>, page: Int): List<DrawingStroke> =
        strokes.filter { isStrokeOnPage(it, page) }

    fun strokesForPage(page: Int): List<DrawingStroke> =
        strokesOnPage(_uiState.value.strokes, page)

    fun strokesForRemovedPage(page: Int): List<DrawingStroke> =
        strokesOnPage(_uiState.value.removedInkStrokes, page)

    private fun actionPage(state: ReaderUiState): Int =
        if (state.activeInkPage == state.currentPage ||
            (isDoublePageMode(state.settings) && state.secondPageBitmap != null &&
                state.activeInkPage == state.currentPage + 1)) state.activeInkPage else state.currentPage

    private fun ReaderUiState.withInkActions(strokes: List<DrawingStroke> = this.strokes): ReaderUiState {
        val page = actionPage(this)
        return copy(strokes = strokes, activeInkPage = page,
            removedInkStrokes = removedInkStrokes.filter {
                isStrokeOnPage(it, currentPage) || (isDoublePageMode(settings) &&
                    secondPageBitmap != null && isStrokeOnPage(it, currentPage + 1))
            },
            canUndo = strokesOnPage(strokes, page).isNotEmpty(),
            canRedo = strokesOnPage(redoStack, page).isNotEmpty())
    }

    fun setActiveInkPage(page: Int) {
        _uiState.update { state -> state.copy(activeInkPage = page).withInkActions() }
    }

    fun acknowledgeRemovedInkStrokes(removed: List<DrawingStroke>) {
        val ids = removed.mapTo(HashSet()) { it.id }
        _uiState.update { state -> state.copy(removedInkStrokes = state.removedInkStrokes.filterNot { it.id in ids }) }
    }

    private suspend fun refreshInkState(removed: List<DrawingStroke> = emptyList(), restoredId: Long? = null) {
        val saved = repository.getStrokesForBook(bookId).first()
        _uiState.update {
            it.copy(removedInkStrokes = (it.removedInkStrokes + removed).filterNot { stroke ->
                stroke.id == restoredId
            }).withInkActions(saved)
        }
    }

    private fun observeStrokes() {
        viewModelScope.launch {
            repository.getStrokesForBook(bookId).collect {
                // A queued mutation may have overtaken this Flow emission. Read the database
                // under the same gate, rather than publishing an older snapshot over its result.
                DrawingStrokeSyncGate.mutex.withLock { refreshInkState() }
            }
        }
    }

    private fun observeHardwareEvents() {
        viewModelScope.launch {
            ReaderEventBus.events.collect { event ->
                if (event.isStylus && _uiState.value.writingMode) return@collect
                when (event.event) {
                    ReaderHardwareEvent.PAGE_NEXT -> nextPage()
                    ReaderHardwareEvent.PAGE_PREVIOUS -> previousPage()
                }
            }
        }
    }

    fun toggleControls() {
        _uiState.update { it.copy(isControlsVisible = !it.isControlsVisible) }
    }

    fun setWritingMode(enabled: Boolean) {
        _uiState.update { it.copy(writingMode = enabled) }
    }

    fun nextPage() {
        val state = _uiState.value
        val step = if (isDoublePageMode(state.settings)) 2 else 1
        if (state.currentPage + step < state.totalPages) {
            goToPage(state.currentPage + step)
        } else if (state.currentPage < state.totalPages - 1) {
            goToPage(state.totalPages - 1)
        }
    }

    fun previousPage() {
        val state = _uiState.value
        val step = if (isDoublePageMode(state.settings)) 2 else 1
        if (state.currentPage - step >= 0) {
            goToPage(state.currentPage - step)
        } else if (state.currentPage > 0) {
            goToPage(0)
        }
    }

    fun goToPage(page: Int) {
        val total = _uiState.value.totalPages
        val validPage = page.coerceIn(0, maxOf(0, total - 1))
        val currentState = _uiState.value
        val isDouble = isDoublePageMode(currentState.settings)

        if (validPage == currentState.currentPage && currentState.currentPageBitmap != null) return
        if (validPage != currentState.currentPage) {
            navigationDirection = if (validPage > currentState.currentPage) 1 else -1
        }

        trackPageReadingTime()
        val remainingMin = calculateEstimatedTimeRemaining(validPage, total)

        // Set or keep reading anchor point ("Punto de lectura original") for visual checkpoint
        val anchor = currentState.readingAnchorPage ?: currentState.currentPage

        // 1. Cancel previous in-flight jobs immediately so rapid taps never queue or stall
        activeRenderJob?.cancel()

        prefetchJob?.cancel()

        // 2. Check Tier 1 (High-Res Cache) for instantaneous 0ms crisp display
        val highFirst = renderCache.get(validPage)
        val highSecond = if (isDouble && validPage + 1 < total) renderCache.get(validPage + 1) else null
        val isFullHighHit = highFirst != null && (!isDouble || validPage + 1 >= total || highSecond != null)

        if (isFullHighHit) {
            _uiState.update {
                it.copy(
                    currentPage = validPage,
                    activeInkPage = validPage,
                    currentPageBitmap = highFirst,
                    secondPageBitmap = highSecond,
                    isLoadingPage = false,
                    error = null,
                    readingAnchorPage = anchor,
                    estimatedMinutesRemaining = remainingMin,
                    canUndo = strokesOnPage(it.strokes, validPage).isNotEmpty(),
                    canRedo = strokesOnPage(redoStack, validPage).isNotEmpty()
                ).withInkActions()
            }
            saveProgressNow(validPage)
            schedulePrefetch(validPage)
            return
        }

        // 3. Check Tier 2 (Preview / Puntos Cache - "páginas no cargadas por completo")
        val prevFirst = highFirst ?: renderCache.get(validPage, preview = true)
        val prevSecond = if (isDouble && validPage + 1 < total) (highSecond ?: renderCache.get(validPage + 1, preview = true)) else null
        val hasPreview = prevFirst != null && (!isDouble || validPage + 1 >= total || prevSecond != null)

        if (hasPreview) {
            _uiState.update {
                it.copy(
                    currentPage = validPage,
                    activeInkPage = validPage,
                    currentPageBitmap = prevFirst,
                    secondPageBitmap = prevSecond,
                    isLoadingPage = false,
                    error = null,
                    readingAnchorPage = anchor,
                    estimatedMinutesRemaining = remainingMin,
                    canUndo = strokesOnPage(it.strokes, validPage).isNotEmpty(),
                    canRedo = strokesOnPage(redoStack, validPage).isNotEmpty()
                ).withInkActions()
            }
            saveProgressNow(validPage)
            renderCurrentPageWithFastPreview(validPage)
            return
        }

        // 4. Cache miss on both tiers (e.g. huge jump on slider)
        // Drop the old page immediately: its pixels and ink must not be labeled as the new page.
        _uiState.update {
            it.copy(
                currentPage = validPage,
                activeInkPage = validPage,
                currentPageBitmap = null,
                secondPageBitmap = null,
                error = null,
                isLoadingPage = true,
                readingAnchorPage = anchor,
                estimatedMinutesRemaining = remainingMin,
                canUndo = strokesOnPage(it.strokes, validPage).isNotEmpty(),
                canRedo = strokesOnPage(redoStack, validPage).isNotEmpty()
            ).withInkActions()
        }
        saveProgressNow(validPage)
        renderCurrentPageWithFastPreview(validPage)
    }

    fun resetReadingAnchor() {
        _uiState.update { it.copy(readingAnchorPage = null) }
    }

    fun saveProgressNow(page: Int = _uiState.value.currentPage) {
        val total = _uiState.value.totalPages
        val book = _uiState.value.book
        val bookTotal = book?.totalPages ?: 1

        // If totalPages is not initialized (> 1) and page is 0, but book previously had saved progress,
        // do not accidentally overwrite with 0!
        if (total <= 1 && page == 0 && (book?.currentPage ?: 0) > 0) {
            Log.d(TAG, "Skipping saveProgressNow: totalPages is <= 1 and page is 0 while book had saved currentPage > 0")
            return
        }

        val effectiveTotal = if (total > 1) total else bookTotal
        val progress = if (effectiveTotal > 1) (page.toFloat() / (effectiveTotal - 1)) * 100f else 0f

        _uiState.update {
            it.copy(
                book = it.book?.copy(
                    currentPage = page,
                    totalPages = effectiveTotal,
                    progressPercent = progress
                )
            )
        }

        persistentScope.launch {
            awaitDrawingMutations()
            try {
                repository.updateProgress(bookId, page, progress, effectiveTotal)
                Log.d(TAG, "Reading progress saved immediately: bookId=$bookId, page=$page, total=$effectiveTotal ($progress%)")
                val syncManager = org.readera.openreadera.sync.GoogleDriveSyncManager(context)
                if (syncManager.isDriveConnected() && syncManager.isAutoSyncEnabled) {
                    syncManager.triggerImmediateBackgroundSync()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving reading progress for book $bookId", e)
            }
        }
    }

    fun renderCurrentPage() {
        renderCurrentPageWithFastPreview(_uiState.value.currentPage)
    }

    private fun renderCurrentPageWithFastPreview(targetPage: Int) {
        val currentEngine = engine ?: return
        if (renderOptionsJob?.isActive == true) return
        val state = _uiState.value
        val settings = state.settings
        val isDouble = isDoublePageMode(settings)
        val needsSecond = isDouble && targetPage + 1 < state.totalPages

        activeRenderJob?.cancel()
        prefetchJob?.cancel()
        activeRenderJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingPage = true, error = null) }
            val options = createRenderOptions(settings)
            val cachedFirst = renderCache.get(targetPage)
            val cachedSecond = if (needsSecond) renderCache.get(targetPage + 1) else null
            val previewFirst = cachedFirst ?: renderCache.get(targetPage, preview = true)
                ?: renderSinglePageToBitmap(targetPage, currentEngine, options, isDouble, isPreview = true)
                    ?.also { renderCache.put(targetPage, it, preview = true) }
            val previewSecond = if (needsSecond) {
                cachedSecond ?: renderCache.get(targetPage + 1, preview = true)
                    ?: renderSinglePageToBitmap(targetPage + 1, currentEngine, options, isDouble, isPreview = true)
                        ?.also { renderCache.put(targetPage + 1, it, preview = true) }
            } else null
            if (previewFirst != null) {
                _uiState.update {
                    it.copy(currentPageBitmap = previewFirst, secondPageBitmap = previewSecond,
                        isLoadingPage = false).withInkActions()
                }
            }

            val fullFirst = cachedFirst ?: renderSinglePageToBitmap(targetPage, currentEngine, options, isDouble)
                ?.also { renderCache.put(targetPage, it) }
            val fullSecond = if (needsSecond) {
                cachedSecond ?: renderSinglePageToBitmap(targetPage + 1, currentEngine, options, isDouble)
                    ?.also { renderCache.put(targetPage + 1, it) }
            } else null
            ensureActive()
            _uiState.update {
                it.copy(currentPageBitmap = fullFirst ?: previewFirst,
                    secondPageBitmap = fullSecond ?: previewSecond,
                    isLoadingPage = false,
                    error = if (fullFirst == null || (needsSecond && fullSecond == null))
                        "No se pudo cargar la página ${if (fullFirst == null) targetPage + 1 else targetPage + 2}"
                    else null).withInkActions()
            }
            if (fullFirst != null && (!needsSecond || fullSecond != null)) schedulePrefetch(targetPage)
        }
    }

    private fun schedulePrefetch(centerPage: Int) {
        prefetchJob?.cancel()
        val currentEngine = engine ?: return
        val state = _uiState.value
        val isDouble = isDoublePageMode(state.settings)
        val step = if (isDouble) 2 else 1
        val pages = readerPrefetchPages(centerPage, state.totalPages, isDouble, navigationDirection)
        renderCache.retainPages((pages + (centerPage until minOf(centerPage + step, state.totalPages))).toSet())
        val options = createRenderOptions(state.settings)
        // Publish on Main after cancellable IO; never let obsolete work repopulate the cache.
        prefetchJob = viewModelScope.launch {
            // Only the next spread gets full resolution, and not while zoomed deeply.
            if (pdfQualityZoom < 2f) {
                val nextSpread = centerPage + navigationDirection * step
                for (page in nextSpread until nextSpread + step) {
                    if (page !in 0 until state.totalPages || renderCache.get(page) != null) continue
                    ensureActive()
                    val bitmap = renderSinglePageToBitmap(page, currentEngine, options, isDouble, isPrefetch = true)
                    if (bitmap != null) renderCache.put(page, bitmap)
                }
            }
            for (page in pages) {
                ensureActive()
                if (renderCache.get(page) != null || renderCache.get(page, preview = true) != null) continue
                val bitmap = renderSinglePageToBitmap(page, currentEngine, options, isDouble,
                    isPreview = true, isPrefetch = true)
                if (bitmap != null) renderCache.put(page, bitmap, preview = true)
            }
        }
    }

    private suspend fun renderSinglePageToBitmap(
        page: Int,
        docEngine: DocumentEngine,
        options: RenderOptions,
        isDouble: Boolean,
        isPreview: Boolean = false,
        isPrefetch: Boolean = false
    ): Bitmap? = withContext(Dispatchers.IO) {
        val renderContext = currentCoroutineContext()
        var allocated: Bitmap? = null
        try {
            synchronized(docEngine) {
                renderContext.ensureActive()
                val pageSize = docEngine.getPageSize(page)
                require(pageSize.width.isFinite() && pageSize.height.isFinite() &&
                    pageSize.width > 0f && pageSize.height > 0f) { "Invalid page dimensions" }
                pageAspectRatios[page] = pageSize.width / maxOf(1f, pageSize.height)
                val aspectRatio = pageSize.height / maxOf(1f, pageSize.width)
                val baseWidth = if (isPreview) {
                    if (isDouble) 480 else 600
                } else if (docEngine is AndroidPdfEngine) {
                    val availableWidth = if (viewportWidth > 0) viewportWidth else if (isDouble) 3200 else 2400
                    val pageSlotWidth = if (isDouble) (availableWidth / 2f) else availableWidth.toFloat()
                    val pageSlotHeight = if (viewportHeight > 0) viewportHeight.toFloat() else pageSlotWidth
                    val fitWidth = minOf(pageSlotWidth, pageSlotHeight / aspectRatio.coerceAtLeast(0.01f))
                    val qualityWidth = ceil(fitWidth * 1.5f * pdfQualityZoom).toInt()
                    qualityWidth.coerceIn(1, if (isDouble) 3200 else 4096)
                } else {
                    if (isDouble) 1600 else 2400
                }
                val naturalHeight = (baseWidth * aspectRatio).toInt().coerceAtLeast(1)
                val maxHeight = if (isPreview) 3000 else 8000
                val maxPixels = minOf(MAX_PDF_PAGE_PIXELS,
                    renderCache.maxBytes.toLong() / (4 * if (isDouble) 2 else 1))
                val renderScale = minOf(1f, maxHeight.toFloat() / naturalHeight,
                    sqrt(maxPixels.toDouble() / (baseWidth.toDouble() * naturalHeight)).toFloat())
                val bitmapWidth = (baseWidth * renderScale).toInt().coerceAtLeast(1)
                val height = (naturalHeight * renderScale).toInt().coerceAtLeast(1)
                // Android PdfRenderer rejects RGB_565, including its preview path.
                val config = if (isPreview && docEngine !is AndroidPdfEngine) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                val bytes = bitmapWidth.toLong() * height * if (config == Bitmap.Config.RGB_565) 2 else 4
                if (isPrefetch && !renderCache.canFit(page, bytes, isPreview)) return@synchronized null
                val bitmap = Bitmap.createBitmap(bitmapWidth, height, config).apply {
                    density = if (isPreview) 160 else 480
                }
                allocated = bitmap
                val success = docEngine.renderPage(page, bitmap, options)
                renderContext.ensureActive()
                if (success) bitmap else {
                    bitmap.recycle()
                    null
                }
            }
        } catch (cancelled: CancellationException) {
            allocated?.recycle()
            throw cancelled
        } catch (e: Exception) {
            allocated?.recycle()
            Log.w(TAG, "Error rendering page $page for cache (preview=$isPreview)", e)
            null
        }
    }

    fun updateTheme(theme: ReaderColorTheme) {
        preferences.updateTheme(theme)
    }

    fun toggleThemeDayNight() {
        val current = _uiState.value.settings.theme
        val nextTheme = when (current) {
            ReaderColorTheme.SYSTEM -> {
                val appMode = preferences.appThemeMode.value
                if (appMode == AppThemeMode.OLED || isSystemDark) ReaderColorTheme.DAY else ReaderColorTheme.NIGHT
            }
            ReaderColorTheme.DAY -> ReaderColorTheme.NIGHT
            ReaderColorTheme.NIGHT -> ReaderColorTheme.OLED
            ReaderColorTheme.OLED -> ReaderColorTheme.DAY
            ReaderColorTheme.SEPIA -> ReaderColorTheme.NIGHT
        }
        updateTheme(nextTheme)
    }

    fun updateViewMode(mode: ReaderViewMode) {
        preferences.updateViewMode(mode)
    }

    fun updateFontSize(size: Int) {
        preferences.updateFontSize(size)
    }

    fun updateLineSpacing(spacing: Float) {
        preferences.updateLineSpacing(spacing)
    }

    fun updateMargin(marginDp: Int) {
        preferences.updateMargin(marginDp)
    }

    fun updateFontFamily(fontFamily: String) {
        preferences.updateFontFamily(fontFamily)
    }

    fun updateFontBold(enabled: Boolean) {
        preferences.updateFontBold(enabled)
    }

    fun updateBrightness(brightness: Float) {
        preferences.updateBrightness(brightness)
    }

    fun toggleBookmark() {
        val state = _uiState.value
        val page = state.currentPage
        val existing = state.bookmarks.find { it.page == page }

        viewModelScope.launch {
            if (existing != null) {
                repository.deleteBookmark(existing)
            } else {
                repository.addBookmark(
                    Bookmark(
                        bookId = bookId,
                        page = page,
                        title = "Página ${page + 1}",
                        snippet = "Marcador guardado en la página ${page + 1}"
                    )
                )
            }
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            repository.deleteBookmark(bookmark)
        }
    }

    fun deleteAllBookmarks() {
        viewModelScope.launch {
            repository.deleteAllBookmarksForBook(bookId)
        }
    }

    fun addQuote(text: String, note: String, colorHex: String, page: Int = _uiState.value.currentPage) {
        viewModelScope.launch {
            repository.addQuote(
                Quote(
                    bookId = bookId,
                    page = page,
                    text = text,
                    note = note,
                    colorHex = colorHex
                )
            )
        }
    }

    fun updateQuote(quote: Quote) {
        viewModelScope.launch {
            repository.updateQuote(quote)
        }
    }

    fun deleteQuote(quote: Quote) {
        viewModelScope.launch {
            repository.deleteQuote(quote)
        }
    }

    fun deleteAllQuotes() {
        viewModelScope.launch {
            repository.deleteAllQuotesForBook(bookId)
        }
    }

    // Stylus Inking & Drawing methods

    fun setDrawingTool(tool: DrawingTool) {
        val state = _uiState.value
        val width = if (tool == state.currentDrawingTool || tool == DrawingTool.ERASER) state.currentStrokeWidth
        else when (tool) {
            DrawingTool.PEN -> 4f
            DrawingTool.MARKER -> 12f
            DrawingTool.HIGHLIGHTER, DrawingTool.FREE_HIGHLIGHTER -> 20f
            DrawingTool.ERASER -> state.currentStrokeWidth
        }
        preferences.updateDrawingToolName(tool.name)
        preferences.updateDrawingStrokeWidth(width)
        _uiState.update { it.copy(currentDrawingTool = tool, currentStrokeWidth = width) }
    }

    fun setDrawingColor(hex: String) {
        preferences.updateDrawingColorHex(hex)
        _uiState.update { it.copy(currentStrokeColorHex = hex) }
    }

    fun setDrawingStrokeWidth(width: Float) {
        val safeWidth = width.coerceIn(1f, 32f)
        preferences.updateDrawingStrokeWidth(safeWidth)
        _uiState.update { it.copy(currentStrokeWidth = safeWidth) }
    }

    private fun enqueueDrawingMutation(block: suspend () -> Unit) {
        synchronized(drawingMutationLock) {
            val previous = lastDrawingMutation
            lastDrawingMutation = persistentScope.launch {
                previous?.join()
                DrawingStrokeSyncGate.mutex.withLock { block() }
            }
        }
    }

    private suspend fun awaitDrawingMutations() {
        val pending = synchronized(drawingMutationLock) { lastDrawingMutation }
        pending?.join()
    }


    fun onStrokeFinished(stroke: DrawingStroke) {
        setActiveInkPage(stroke.page)
        val strokeWithBook = stroke.copy(bookId = bookId)
        enqueueDrawingMutation {
            val insertedId = repository.addDrawingStroke(strokeWithBook)
            check(insertedId > 0L) { "The drawing stroke was not saved" }
            redoStack.removeAll { isStrokeOnPage(it, stroke.page) }
            refreshInkState()
        }
    }

    fun deleteStroke(stroke: DrawingStroke) {
        enqueueDrawingMutation {
            val saved = repository.getStrokesForBook(bookId).first().firstOrNull { it.id == stroke.id }
                ?: return@enqueueDrawingMutation
            repository.deleteDrawingStroke(saved)
            redoStack.add(saved)
            refreshInkState(removed = listOf(saved))
        }
    }


    fun undoLastStroke(page: Int = actionPage(_uiState.value)) {
        enqueueDrawingMutation {
            val saved = repository.getStrokesForBook(bookId).first()
            val strokeToUndo = strokesOnPage(saved, page)
                .maxWithOrNull(compareBy<DrawingStroke> { it.createdAt }.thenBy { it.id })
                ?: return@enqueueDrawingMutation
            repository.deleteDrawingStroke(strokeToUndo)
            redoStack.add(strokeToUndo)
            refreshInkState(removed = listOf(strokeToUndo))
        }
    }

    fun redoLastStroke(page: Int = actionPage(_uiState.value)) {
        enqueueDrawingMutation {
            val strokeToRedo = redoStack.lastOrNull { isStrokeOnPage(it, page) }
                ?: return@enqueueDrawingMutation
            val restoredStroke = strokeToRedo.copy(
                id = 0L, createdAt = maxOf(System.currentTimeMillis(), strokeToRedo.createdAt + 1L))
            val insertedId = repository.addDrawingStroke(restoredStroke)
            check(insertedId > 0L) { "The drawing stroke was not restored" }
            redoStack.remove(strokeToRedo)
            refreshInkState(restoredId = strokeToRedo.id)
        }
    }

    fun clearPageStrokes(page: Int = actionPage(_uiState.value)) {
        enqueueDrawingMutation {
            val pageStrokes = strokesOnPage(repository.getStrokesForBook(bookId).first(), page)
            if (pageStrokes.isEmpty()) return@enqueueDrawingMutation
            pageStrokes.forEach { repository.deleteDrawingStroke(it) }
            redoStack.addAll(pageStrokes)
            refreshInkState(removed = pageStrokes)
        }
    }

    /** Whole-document export calls this on IO; it does not trigger a background whole-book OCR scan. */
    fun getNativePageTextForExport(pageIndex: Int): String =
        engine?.let { synchronized(it) { it.getPageText(pageIndex) } }.orEmpty()


    fun getPageAspectRatio(pageIndex: Int): Float = pageAspectRatios[pageIndex] ?: 0f

    fun getPageTextRange(pageIndex: Int): PageTextRange? = engine?.getPageTextRange(pageIndex)

    fun scanGeneratedBook(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                checkNotNull(StorageScanner.scanSingleFile(context, file)) { "Generated document was not indexed" }
                val syncManager = org.readera.openreadera.sync.GoogleDriveSyncManager(context)
                if (syncManager.isDriveConnected() && syncManager.isAutoSyncEnabled) {
                    syncManager.triggerImmediateBackgroundSync()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Unable to index generated document", error)
                _uiState.update { it.copy(error = "La copia se guardó, pero no se pudo añadir a la biblioteca.") }
            }
        }
    }

    fun updateReview(rating: Float, review: String) {
        viewModelScope.launch {
            repository.updateReview(bookId, rating, review)
        }
    }

    fun moveToTrash() {
        viewModelScope.launch {
            repository.moveToTrash(bookId)
        }
    }

    fun addToReadingNow() {
        viewModelScope.launch {
            repository.markAsReading(bookId)
        }
    }

    fun saveCollections(collectionIds: List<Long>) {
        viewModelScope.launch {
            repository.setBookCollections(bookId, collectionIds)
        }
    }

    fun createCollection(name: String) {
        viewModelScope.launch {
            val clean = name.trim()
            if (clean.isNotEmpty()) repository.addCollection(clean)
        }
    }

    fun search(query: String) {
        val normalizedQuery = query.trim()
        val generation = ++searchGeneration
        searchJob?.cancel()
        if (normalizedQuery.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }

        val currentEngine = engine ?: return
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, error = null) }
            try {
                val results = withContext(Dispatchers.IO) {
                    val searchContext = currentCoroutineContext()
                    synchronized(currentEngine) {
                        searchContext.ensureActive()
                        currentEngine.search(normalizedQuery)
                    }
                }
                if (generation == searchGeneration) {
                    _uiState.update { it.copy(searchResults = results) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Document search failed", error)
                if (generation == searchGeneration) {
                    _uiState.update { it.copy(searchResults = emptyList(), error = "No se pudo buscar en el documento") }
                }
            } finally {
                if (generation == searchGeneration) {
                    _uiState.update { it.copy(isSearching = false) }
                }
            }
        }
    }

    fun startTts(customText: String? = null) {
        val currentEngine = engine ?: return
        val page = _uiState.value.currentPage
        val recognizedText = _uiState.value.pageText[page]?.layout?.text.orEmpty()
        ttsStartJob?.cancel()
        ttsStartJob = viewModelScope.launch {
            try {
                val textToRead = if (!customText.isNullOrBlank()) customText else {
                    val nativeText = withContext(Dispatchers.IO) {
                        val speechContext = currentCoroutineContext()
                        synchronized(currentEngine) {
                            speechContext.ensureActive()
                            currentEngine.getPageText(page)
                        }
                    }
                    nativeText.ifBlank { recognizedText }
                }
                ensureActive()
                if (textToRead.isBlank()) {
                    _uiState.update { it.copy(error = "No hay texto disponible para leer. Espera al reconocimiento de la página o selecciona un fragmento.") }
                    return@launch
                }
                _uiState.update {
                    it.copy(isTtsActive = true, isTtsPlaying = true, ttsText = textToRead, error = null)
                }
                naturalTtsPlayer.start(textToRead)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Unable to prepare speech for page $page", error)
                _uiState.update { it.copy(error = "No se pudo preparar la lectura de esta página") }
            }
        }
    }


    fun setTtsSleepTimer(minutes: Int?) {
        if (minutes == null) {
            ttsSleepTimer.cancel()
            _uiState.update { it.copy(ttsSleepTimerMinutes = null) }
        } else {
            ttsSleepTimer.start(minutes)
            _uiState.update { it.copy(ttsSleepTimerMinutes = minutes) }
        }
    }

    fun pauseTts() {
        naturalTtsPlayer.pause()
        _uiState.update { it.copy(isTtsPlaying = false) }
    }

    fun resumeTts() {
        naturalTtsPlayer.resume()
        _uiState.update { it.copy(isTtsPlaying = true) }
    }

    fun stopTts() {
        ttsStartJob?.cancel()
        ttsSleepTimer.cancel()
        if (naturalTtsPlayerDelegate.isInitialized()) naturalTtsPlayer.stop()
        _uiState.update { it.copy(isTtsActive = false, isTtsPlaying = false, ttsSleepTimerMinutes = null) }
    }

    fun nextTtsSentence() {
        naturalTtsPlayer.nextSentence()
    }

    fun previousTtsSentence() {
        naturalTtsPlayer.previousSentence()
    }

    override fun onCleared() {
        ttsSleepTimer.cancel()
        saveProgressNow()
        super.onCleared()
        activeRenderJob?.cancel()
        prefetchJob?.cancel()
        renderOptionsJob?.cancel()
        ttsStartJob?.cancel()
        invalidatePageText()
        if (ocrDelegate.isInitialized()) ocrDelegate.value.close()
        renderCache.clear()
        val closingEngine = engine
        engine = null
        persistentScope.launch { closingEngine?.let { synchronized(it) { it.close() } } }
        if (naturalTtsPlayerDelegate.isInitialized()) naturalTtsPlayer.release()
    }
}
