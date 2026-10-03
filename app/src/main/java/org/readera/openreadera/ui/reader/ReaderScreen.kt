package org.readera.openreadera.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.WindowManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import org.readera.openreadera.data.model.AppThemeMode
import org.readera.openreadera.data.model.ReaderColorTheme
import org.readera.openreadera.data.model.ReaderViewMode
import org.readera.openreadera.ui.library.components.BookCollectionsDialog
import org.readera.openreadera.ui.library.components.CreateCollectionDialog
import org.readera.openreadera.ui.library.components.ReviewDialog
import org.readera.openreadera.ui.reader.components.*
import org.readera.openreadera.ui.theme.NightBackground
import org.readera.openreadera.ui.theme.OledBackground
import org.readera.openreadera.ui.theme.SepiaBackground
import java.io.File
import kotlinx.coroutines.CancellationException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.readera.openreadera.data.ai.OpenRouterClient
import org.readera.openreadera.data.pdf.PdfDocumentEditor
import org.readera.openreadera.data.pdf.PdfVisualSignature
import org.readera.openreadera.data.scanner.DocumentScanProcessor
import org.readera.openreadera.data.scanner.StorageScanner
import org.readera.openreadera.data.security.OpenRouterKeyStore
import org.readera.openreadera.ui.pdf.PdfPageManagerScreen
import org.readera.openreadera.ui.pdf.PdfSignatureDialog
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.FileOutputStream

private fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onOpenDocumentDetails: () -> Unit,
    onOpenReader: (Long) -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    isWindowed: Boolean = false
) {
    val state by viewModel.uiState.collectAsState()
    val allCollections by viewModel.allCollectionsWithCount.collectAsState(initial = emptyList())
    val currentCollections by viewModel.currentBookCollections.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var isPresenting by remember { mutableStateOf(false) }
    var presentationControlsVisible by remember { mutableStateOf(false) }
    var presentationBlackout by remember { mutableStateOf(false) }
    var laserPointerEnabled by remember { mutableStateOf(false) }
    var laserPointerPosition by remember { mutableStateOf<Offset?>(null) }
    var presentationPenEnabled by remember { mutableStateOf(false) }
    var presentationAnnotations by remember {
        mutableStateOf<Map<Int, List<PresentationStroke>>>(emptyMap())
    }
    var presentationStartedAt by remember { mutableLongStateOf(0L) }
    var presentationElapsedSeconds by remember { mutableLongStateOf(0L) }
    var presentationActivity by remember { mutableLongStateOf(0L) }
    val exitPresentation = {
        isPresenting = false
        presentationControlsVisible = false
        presentationBlackout = false
        laserPointerEnabled = false
        presentationPenEnabled = false
        laserPointerPosition = null
        presentationAnnotations = emptyMap()
        viewModel.setPresentationMode(false)
    }

    DisposableEffect(state.isControlsVisible, isWindowed, isPresenting) {
        val window = activity?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (isPresenting) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else if (state.isControlsVisible || isWindowed) {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val w = activity?.window
            if (w != null) {
                val insetsController = WindowCompat.getInsetsController(w, w.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    DisposableEffect(activity, isPresenting) {
        val window = activity?.window
        val wasKeepScreenOn = window != null &&
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (isPresenting) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (isPresenting && !wasKeepScreenOn) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.setPresentationMode(false)
            presentationAnnotations = emptyMap()
        }
    }

    LaunchedEffect(isPresenting, presentationStartedAt) {
        if (isPresenting) {
            while (true) {
                presentationElapsedSeconds =
                    (SystemClock.elapsedRealtime() - presentationStartedAt) / 1_000L
                delay(1_000L)
            }
        } else {
            presentationElapsedSeconds = 0L
        }
    }

    LaunchedEffect(
        isPresenting,
        presentationControlsVisible,
        laserPointerEnabled,
        presentationPenEnabled,
        presentationActivity,
        state.currentPage
    ) {
        if (isPresenting && presentationControlsVisible && !laserPointerEnabled && !presentationPenEnabled) {
            delay(4_000L)
            presentationControlsVisible = false
        }
    }

    // Auto-save reading progress on pause, stop or screen disposal
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                viewModel.saveProgressNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            viewModel.saveProgressNow()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler {
        if (isPresenting) {
            exitPresentation()
        } else if (state.isControlsVisible && !isWindowed) {
            viewModel.toggleControls()
        } else {
            viewModel.saveProgressNow()
            onBack()
        }
    }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showTocSheet by remember { mutableStateOf(false) }
    var showSearchSheet by remember { mutableStateOf(false) }
    var showAddQuoteDialog by remember { mutableStateOf(false) }
    var quoteWithoutText by remember(state.book?.id, state.currentPage, showAddQuoteDialog) { mutableStateOf(false) }
    var showTextSelectorSheet by remember { mutableStateOf(false) }
    var showReviewDialog by remember { mutableStateOf(false) }
    var showCollectionsDialog by remember { mutableStateOf(false) }
    var showCreateCollectionDialog by remember { mutableStateOf(false) }
    var showTrashConfirmDialog by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var movementLocked by remember { mutableStateOf(false) }

    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(state.currentPage) {
        zoomScale = 1.0f
        panOffset = Offset.Zero
        viewModel.setZoomScale(1.0f)
    }

    val applyZoom: (Float) -> Unit = { newScale ->
        val clamped = newScale.coerceIn(1.0f, 5.0f)
        if (clamped <= 1.05f) {
            zoomScale = 1.0f
            panOffset = Offset.Zero
        } else {
            val maxPanX = (viewportSize.width * (clamped - 1f)) / 2f
            val maxPanY = (viewportSize.height * (clamped - 1f)) / 2f
            zoomScale = clamped
            panOffset = Offset(
                panOffset.x.coerceIn(-maxPanX, maxPanX),
                panOffset.y.coerceIn(-maxPanY, maxPanY)
            )
        }
    }


    val isPdf = remember(state.book?.format) {
        state.book?.format?.uppercase() == "PDF"
    }

    val isFixedDoc = remember(state.book?.format) {
        when (state.book?.format?.uppercase()) {
            "PDF", "CBZ", "CBR", "DJVU", "DJV" -> true
            else -> false
        }
    }
    val scope = rememberCoroutineScope()
    val processor = remember(context) { DocumentScanProcessor(context) }
    val pdfEditor = remember(context) { PdfDocumentEditor(context) }
    val keyStore = remember(context) { OpenRouterKeyStore(context) }
    val openRouter = remember(keyStore) { OpenRouterClient(keyStore) }
    var showPdfAssistant by remember { mutableStateOf(false) }
    var showMissingAiKey by remember { mutableStateOf(false) }
    var assistantText by remember { mutableStateOf("") }
    var loadingAssistantText by remember { mutableStateOf(false) }
    var showPageManager by remember { mutableStateOf(false) }
    var showPdfSignature by remember { mutableStateOf(false) }
    var signatureError by remember { mutableStateOf<String?>(null) }
    var pdfPageCount by remember { mutableIntStateOf(0) }
    var pdfSaving by remember { mutableStateOf(false) }
    var pdfToolError by remember { mutableStateOf<String?>(null) }
    var pdfToolStatus by remember { mutableStateOf<String?>(null) }
    var pdfProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    BackHandler(enabled = showPageManager) {
        if (!pdfSaving) {
            showPageManager = false
            pdfToolError = null
        }
    }

    val exportTextLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val book = state.book ?: return@launch
            var exported: File? = null
            pdfSaving = true
            try {
                exported = processor.pdfToText(File(book.filePath))
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        exported!!.inputStream().use { it.copyTo(output) }
                    } ?: error("No se pudo abrir el destino")
                }
                pdfToolStatus = "Texto exportado correctamente"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { pdfToolError = error.message ?: "No se pudo exportar el texto" }
            finally {
                exported?.delete()
                pdfSaving = false
            }
        }
    }
    val exportImagesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            val book = state.book ?: return@launch
            val exportToken = System.nanoTime()
            val tempDir = File(context.cacheDir, "pdf-images-$exportToken")
            val zipFile = File(context.cacheDir, "pdf-images-$exportToken.zip")
            pdfSaving = true
            try {
                val images = processor.pdfToImages(File(book.filePath), tempDir)
                withContext(Dispatchers.IO) {
                    ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                        images.forEach { image ->
                            zip.putNextEntry(ZipEntry(image.name))
                            image.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        zipFile.inputStream().use { it.copyTo(output) }
                    } ?: error("No se pudo abrir el destino")
                }
                pdfToolStatus = "Imágenes exportadas correctamente"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { pdfToolError = error.message ?: "No se pudo exportar el PDF" }
            finally {
                tempDir.deleteRecursively()
                zipFile.delete()
                pdfSaving = false
            }
        }
    }
    val outputPdf: (String) -> File = { suffix ->
        val dir = File(context.filesDir, "pdf-tools")
        check(dir.isDirectory || dir.mkdirs()) { "No se pudo crear la carpeta de PDF" }
        var output: File
        do {
            output = File(dir, "${System.nanoTime()}-$suffix.pdf")
        } while (output.exists())
        output
    }
    val registerAndOpen: suspend (File) -> Unit = { file ->
        val book = StorageScanner.scanSingleFile(context, file)
        if (book != null) onOpenReader(book.id) else error("No se pudo añadir el archivo a la biblioteca")
    }
    LaunchedEffect(showPdfAssistant, state.book?.id) {
        if (showPdfAssistant) {
            loadingAssistantText = true
            try {
                val book = state.book
                assistantText = if (book == null) "" else {
                    pdfEditor.extractDocumentText(File(book.filePath), 12_000)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                assistantText = ""
            } finally {
                loadingAssistantText = false
            }
        }
    }
    LaunchedEffect(pdfToolStatus, pdfToolError) {
        val message = pdfToolError ?: pdfToolStatus ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        if (pdfToolError != null) pdfToolError = null else pdfToolStatus = null
    }

    val isDark = isSystemInDarkTheme()

    LaunchedEffect(isDark) {
        viewModel.setSystemDark(isDark)
    }

    LaunchedEffect(isWindowed) {
        viewModel.setWindowedMode(isWindowed)
    }

    val appThemeMode by viewModel.appThemeMode.collectAsState()

    val effectiveTheme = when (state.settings.theme) {
        ReaderColorTheme.SYSTEM -> {
            if (appThemeMode == AppThemeMode.OLED) ReaderColorTheme.OLED
            else if (isDark) ReaderColorTheme.NIGHT
            else ReaderColorTheme.DAY
        }
        else -> state.settings.theme
    }

    val isOled = effectiveTheme == ReaderColorTheme.OLED || appThemeMode == AppThemeMode.OLED
    val effectiveViewMode = if (isWindowed || isPresenting) ReaderViewMode.PAGED else state.settings.viewMode
    val navigationIsStylus = remember { booleanArrayOf(false, false) }
    val writingScrollGuard = remember(state.writingMode) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (state.writingMode && navigationIsStylus[0]) available else Offset.Zero
            override suspend fun onPreFling(available: Velocity): Velocity =
                if (state.writingMode && navigationIsStylus[1]) available else Velocity.Zero
        }
    }
    LaunchedEffect(viewModel, state.book?.id, state.currentPage, state.totalPages, effectiveViewMode,
        state.textLayoutGeneration, state.currentPageBitmap != null) {
        if (state.currentPageBitmap != null) viewModel.requestPageText(
            if (effectiveViewMode == ReaderViewMode.DOUBLE_PAGE && state.currentPage + 1 < state.totalPages)
                setOf(state.currentPage, state.currentPage + 1) else setOf(state.currentPage))
    }
    val currentPageText = state.pageText[state.currentPage]
    val activePageText = state.pageText[state.activeInkPage] ?: currentPageText
    LaunchedEffect(viewModel, state.book?.id, state.currentPage, effectiveViewMode) {
        viewModel.setActiveInkPage(state.currentPage)
    }

    val backgroundColor = when (effectiveTheme) {
        ReaderColorTheme.DAY -> Color(0xFFFFFFFF)
        ReaderColorTheme.SEPIA -> SepiaBackground
        ReaderColorTheme.NIGHT -> NightBackground
        ReaderColorTheme.OLED -> Color(0xFF000000)
        ReaderColorTheme.SYSTEM -> if (appThemeMode == AppThemeMode.OLED) Color(0xFF000000) else if (isDark) NightBackground else Color(0xFFFFFFFF)
    }

    var topBarHeightPx by remember { mutableIntStateOf(0) }
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }

    val showTopBar = isWindowed || (state.isControlsVisible && !isPresenting)
    val showBottomBar = state.isControlsVisible && !isWindowed && !isPresenting

    val density = LocalDensity.current
    val estimatedTopBarPx = remember(density) { with(density) { 80.dp.roundToPx() } }
    val estimatedBottomBarPx = remember(density) { with(density) { 150.dp.roundToPx() } }

    val effectiveTopBarHeight = if (isFixedDoc && showTopBar) {
        if (topBarHeightPx > 0) topBarHeightPx else estimatedTopBarPx
    } else 0

    val effectiveBottomBarHeight = if (isFixedDoc && showBottomBar) {
        if (bottomBarHeightPx > 0) bottomBarHeightPx else estimatedBottomBarPx
    } else 0

    val readingTopPadding = with(density) { effectiveTopBarHeight.toDp() }
    val readingBottomPadding = with(density) { effectiveBottomBarHeight.toDp() }
    val writingEndPadding = if (state.writingMode && !isPresenting &&
        LocalConfiguration.current.screenWidthDp >= 600) 364.dp else 0.dp

    LaunchedEffect(viewportSize) {
        if (zoomScale > 1.02f && viewportSize.width > 0 && viewportSize.height > 0) {
            val maxPanX = (viewportSize.width * (zoomScale - 1f)) / 2f
            val maxPanY = (viewportSize.height * (zoomScale - 1f)) / 2f
            panOffset = Offset(
                panOffset.x.coerceIn(-maxPanX, maxPanX),
                panOffset.y.coerceIn(-maxPanY, maxPanY)
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .onSizeChanged {
                if (!isFixedDoc) {
                    viewportSize = it
                    viewModel.setViewportDimensions(it.width, it.height)
                }
            }
            // En presentación, los toques laterales avanzan página; el centro muestra los controles.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val down = event.changes.firstOrNull { it.pressed && !it.previousPressed }
                        if (down != null && event.changes.none { it.previousPressed }) {
                            navigationIsStylus[0] = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                            navigationIsStylus[1] = navigationIsStylus[0]
                        }
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        if (final.changes.none { it.pressed }) navigationIsStylus[0] = false
                    }
                }
            }
            .pointerInput(isPresenting, laserPointerEnabled, presentationPenEnabled, state.writingMode) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    if (state.writingMode && (down.type == PointerType.Stylus || down.type == PointerType.Eraser))
                        return@awaitEachGesture
                    val offset = up.position
                        val width = size.width
                        val height = size.height
                        if (isPresenting) {
                            if (laserPointerEnabled || presentationPenEnabled) {
                                return@awaitEachGesture
                            }
                            when {
                                offset.x < width * 0.30f -> {
                                    viewModel.previousPage()
                                    presentationControlsVisible = true
                                    presentationActivity++
                                }
                                offset.x > width * 0.70f -> {
                                    viewModel.nextPage()
                                    presentationControlsVisible = true
                                    presentationActivity++
                                }
                                else -> presentationControlsVisible = !presentationControlsVisible
                            }
                        } else {
                            when {
                                offset.x < width * 0.15f && offset.y < height * 0.15f ->
                                    viewModel.toggleThemeDayNight()
                                offset.x > width * 0.85f && offset.y < height * 0.15f ->
                                    viewModel.toggleBookmark()
                                offset.x < width * 0.22f -> viewModel.previousPage()
                                offset.x > width * 0.78f -> viewModel.nextPage()
                                else -> viewModel.toggleControls()
                            }
                        }
                    }
            }
            .pointerInput(viewportSize) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    var includesNonTouch = firstDown.type != PointerType.Touch
                    var pastTouchSlop = false
                    var accumulatedPan = Offset.Zero
                    var accumulatedZoom = 1f
                    val touchSlop = viewConfiguration.touchSlop
                    do {
                        val event = awaitPointerEvent()
                        includesNonTouch = includesNonTouch ||
                            event.changes.any { it.type != PointerType.Touch }
                        val consumed = event.changes.any { it.isConsumed }
                        if (!includesNonTouch && !consumed) {
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            if (!pastTouchSlop) {
                                accumulatedPan += pan
                                accumulatedZoom *= zoom
                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                if (accumulatedPan.getDistance() > touchSlop ||
                                    abs(1f - accumulatedZoom) * centroidSize > touchSlop
                                ) {
                                    pastTouchSlop = true
                                }
                            }
                            if (pastTouchSlop) {
                                val newScale = (zoomScale * zoom).coerceIn(1.0f, 5.0f)
                                applyZoom(newScale)
                                if (newScale <= 1.02f) {
                                    panOffset = Offset.Zero
                                } else {
                                    val maxPanX = (viewportSize.width * (newScale - 1f)) / 2f
                                    val maxPanY = (viewportSize.height * (newScale - 1f)) / 2f
                                    val nextPanX = (panOffset.x + pan.x).coerceIn(-maxPanX, maxPanX)
                                    val nextPanY = (panOffset.y + pan.y).coerceIn(-maxPanY, maxPanY)
                                    panOffset = Offset(nextPanX, nextPanY)
                                }
                                event.changes.forEach { change ->
                                    if (change.position != change.previousPosition) change.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        // Main reading content with hardware-accelerated Zoom & Pan graphics layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = readingTopPadding, bottom = readingBottomPadding)
                .padding(end = writingEndPadding)
                .clipToBounds()
                .nestedScroll(writingScrollGuard)
                .onSizeChanged { size ->
                    if (isFixedDoc && size.width > 0 && size.height > 0) {
                        viewportSize = size
                        viewModel.setViewportDimensions(size.width, size.height)
                    }
                }
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = panOffset.x
                    translationY = panOffset.y
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                state.isLoadingPage && state.currentPageBitmap == null -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                state.currentPageBitmap != null -> {
                    when (effectiveViewMode) {
                        ReaderViewMode.VERTICAL_SCROLL -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = state.settings.marginHorizontalDp.dp)
                                ) {
                                    Image(
                                        bitmap = state.currentPageBitmap!!.asImageBitmap(),
                                        contentDescription = "Página ${state.currentPage + 1}",
                                        contentScale = ContentScale.Fit,
                                        filterQuality = FilterQuality.High,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    StylusInkingLayer(
                                        modifier = Modifier.matchParentSize(),
                                        page = state.currentPage,
                                        savedStrokes = viewModel.strokesForPage(state.currentPage),
                                        pageAspectRatio = viewModel.getPageAspectRatio(state.currentPage),
                                        pageTextRange = viewModel.getPageTextRange(state.currentPage),
                                        pageTextLayout = state.pageText[state.currentPage]?.layout,
                                        writingMode = state.writingMode,
                                        inputScale = zoomScale,
                                        currentTool = state.currentDrawingTool,
                                        currentColorHex = state.currentStrokeColorHex,
                                        currentStrokeWidth = state.currentStrokeWidth,
                                        onStrokeFinished = { viewModel.onStrokeFinished(it) },
                                        onDeleteStroke = { viewModel.deleteStroke(it) },
                                        onPageActive = viewModel::setActiveInkPage,
                                        removedStrokes = viewModel.strokesForRemovedPage(state.currentPage),
                                        onRemovedStrokesApplied = viewModel::acknowledgeRemovedInkStrokes,
                                    )
                                }
                            }
                        }
                        ReaderViewMode.DOUBLE_PAGE -> {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = state.currentPageBitmap!!.asImageBitmap(),
                                        contentDescription = "Página ${state.currentPage + 1}",
                                        contentScale = ContentScale.Fit,
                                        filterQuality = FilterQuality.High,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    StylusInkingLayer(
                                        page = state.currentPage,
                                        savedStrokes = viewModel.strokesForPage(state.currentPage),
                                        pageAspectRatio = viewModel.getPageAspectRatio(state.currentPage),
                                        pageTextRange = viewModel.getPageTextRange(state.currentPage),
                                        pageTextLayout = state.pageText[state.currentPage]?.layout,
                                        writingMode = state.writingMode,
                                        inputScale = zoomScale,
                                        currentTool = state.currentDrawingTool,
                                        currentColorHex = state.currentStrokeColorHex,
                                        currentStrokeWidth = state.currentStrokeWidth,
                                        onStrokeFinished = { viewModel.onStrokeFinished(it) },
                                        onDeleteStroke = { viewModel.deleteStroke(it) },
                                        onPageActive = viewModel::setActiveInkPage,
                                        removedStrokes = viewModel.strokesForRemovedPage(state.currentPage),
                                        onRemovedStrokesApplied = viewModel::acknowledgeRemovedInkStrokes,
                                    )
                                }

                                Spacer(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(
                                            if (isOled) Color.Transparent
                                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                        )
                                )

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (state.secondPageBitmap != null) {
                                        Image(
                                            bitmap = state.secondPageBitmap!!.asImageBitmap(),
                                            contentDescription = "Página ${state.currentPage + 2}",
                                            contentScale = ContentScale.Fit,
                                            filterQuality = FilterQuality.High,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        StylusInkingLayer(
                                            page = state.currentPage + 1,
                                            savedStrokes = viewModel.strokesForPage(state.currentPage + 1),
                                            pageAspectRatio = viewModel.getPageAspectRatio(state.currentPage + 1),
                                            pageTextRange = viewModel.getPageTextRange(state.currentPage + 1),
                                            pageTextLayout = state.pageText[state.currentPage + 1]?.layout,
                                            writingMode = state.writingMode,
                                            inputScale = zoomScale,
                                            currentTool = state.currentDrawingTool,
                                            currentColorHex = state.currentStrokeColorHex,
                                            currentStrokeWidth = state.currentStrokeWidth,
                                            onStrokeFinished = { viewModel.onStrokeFinished(it) },
                                            onDeleteStroke = { viewModel.deleteStroke(it) },
                                            onPageActive = viewModel::setActiveInkPage,
                                            removedStrokes = viewModel.strokesForRemovedPage(state.currentPage + 1),
                                            onRemovedStrokesApplied = viewModel::acknowledgeRemovedInkStrokes,
                                        )
                                    }
                                }
                            }
                        }
                        else -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = state.currentPageBitmap!!.asImageBitmap(),
                                    contentDescription = "Página ${state.currentPage + 1}",
                                    contentScale = ContentScale.Fit,
                                    filterQuality = FilterQuality.High,
                                    modifier = Modifier.fillMaxSize()
                                )
                                StylusInkingLayer(
                                    page = state.currentPage,
                                    savedStrokes = viewModel.strokesForPage(state.currentPage),
                                    pageAspectRatio = viewModel.getPageAspectRatio(state.currentPage),
                                    pageTextRange = viewModel.getPageTextRange(state.currentPage),
                                    pageTextLayout = state.pageText[state.currentPage]?.layout,
                                    writingMode = state.writingMode,
                                    inputScale = zoomScale,
                                    currentTool = state.currentDrawingTool,
                                    currentColorHex = state.currentStrokeColorHex,
                                    currentStrokeWidth = state.currentStrokeWidth,
                                    onStrokeFinished = { viewModel.onStrokeFinished(it) },
                                    onDeleteStroke = { viewModel.deleteStroke(it) },
                                    onPageActive = viewModel::setActiveInkPage,
                                    removedStrokes = viewModel.strokesForRemovedPage(state.currentPage),
                                    onRemovedStrokesApplied = viewModel::acknowledgeRemovedInkStrokes,
                                )
                            }
                        }
                    }
                }
                state.error != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = "Error al cargar la página",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.error ?: "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // Brillo desde el borde izquierdo, disponible al leer.
        if (zoomScale <= 1.05f) {
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            val delta = -dragAmount / 600f
                            val newBrightness = (state.settings.brightness + delta).coerceIn(0.1f, 1.0f)
                            viewModel.updateBrightness(newBrightness)
                        }
                    }
            )
        }

        // Top Bar Overlay
        AnimatedVisibility(
            visible = isWindowed || (state.isControlsVisible && !isPresenting),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            if (isWindowed) {
                WindowedReaderBar(
                    title = state.book?.title ?: "Documento",
                    currentPage = state.currentPage,
                    totalPages = state.totalPages,
                    onBack = {
                        viewModel.saveProgressNow()
                        onBack()
                    },
                    onPreviousPage = { viewModel.previousPage() },
                    onNextPage = { viewModel.nextPage() },
                    modifier = Modifier.onSizeChanged { size ->
                        if (size.height > 0) {
                            topBarHeightPx = size.height
                        }
                    }
                )
            } else {
                val isCurrentPageBookmarked = state.bookmarks.any { it.page == state.currentPage }
                ReaderTopBar(
                    title = state.book?.title ?: "Documento",
                    author = state.book?.author ?: "Autor desconocido",
                    currentPage = state.currentPage,
                    totalPages = state.totalPages,
                    isBookmarked = isCurrentPageBookmarked,
                    onBack = {
                        viewModel.saveProgressNow()
                        onBack()
                    },
                    onToggleBookmark = { viewModel.toggleBookmark() },
                    onAddQuote = { showAddQuoteDialog = true },
                    onOpenToc = { showTocSheet = true },
                    onOpenSearch = { showSearchSheet = true },
                    onOpenPdfAssistant = {
                        scope.launch {
                            val hasKey = withContext(Dispatchers.IO) {
                                runCatching { keyStore.hasKey() }.getOrDefault(false)
                            }
                            if (hasKey) showPdfAssistant = true else showMissingAiKey = true
                        }
                    },
                    onManagePdfPages = {
                        val file = state.book?.let { File(it.filePath) }
                        if (file == null) pdfToolError = "Documento no disponible"
                        else scope.launch {
                            try {
                                pdfPageCount = pdfEditor.pageCount(file)
                                pdfToolError = null
                                showPageManager = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                pdfToolError = error.message ?: "No se pudo abrir el PDF"
                            }
                        }
                    },
                    onCreateSearchableCopy = {
                        val book = state.book
                        if (book == null) pdfToolError = "Documento no disponible"
                        else scope.launch {
                            pdfSaving = true
                            try {
                                val output = outputPdf("ocr")
                                processor.makeSearchableCopy(File(book.filePath), output) { done, total ->
                                    scope.launch { pdfProgress = done to total }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                pdfToolError = error.message ?: "No se pudo crear la copia OCR"
                            } finally {
                                pdfSaving = false
                                pdfProgress = null
                            }
                        }
                    },
                    onExportPdfImages = { state.book?.let { exportImagesLauncher.launch("${it.title}.zip") } },
                    onExportPdfText = { state.book?.let { exportTextLauncher.launch("${it.title}.txt") } },
                    onSignPdf = {
                        val file = state.book?.let { File(it.filePath) }
                        if (file == null) {
                            pdfToolError = "Documento no disponible"
                        } else scope.launch {
                            try {
                                val pageCount = pdfEditor.pageCount(file)
                                check(pageCount > 0) { "El PDF no contiene páginas" }
                                pdfPageCount = pageCount
                                signatureError = null
                                showPdfSignature = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                pdfToolError = "No se pudo leer el PDF. Comprueba que el archivo siga disponible."
                            }
                        }
                    },
                    canStartPresentation = isPdf,
                    isPdf = isPdf,
                    onStartTts = { viewModel.startTts() },
                    onStartPresentation = {
                        if (!isPdf) return@ReaderTopBar
                        viewModel.setPresentationMode(true)
                        isPresenting = true
                        presentationControlsVisible = true
                        presentationBlackout = false
                        laserPointerEnabled = false
                        presentationPenEnabled = false
                        laserPointerPosition = null
                        presentationAnnotations = emptyMap()
                        presentationStartedAt = SystemClock.elapsedRealtime()
                        presentationElapsedSeconds = 0L
                        applyZoom(1f)
                    },
                    onOpenSettings = { showSettingsSheet = true },
                    onOpenReview = { showReviewDialog = true },
                    onOpenTextSelector = { showTextSelectorSheet = true },
                    onOpenTranslate = { showTranslateDialog = true },
                    pageQuotesCount = state.quotes.count { it.page == state.currentPage },
                    isWritingMode = state.writingMode,
                    onWritingModeToggle = { viewModel.setWritingMode(!state.writingMode) },
                    onShareFile = {
                        state.book?.let { b ->
                            val file = File(b.filePath)
                            if (file.exists()) {
                                val uri = try {
                                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                } catch (_: Exception) {
                                    Uri.fromFile(file)
                                }
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Compartir ${b.title}"))
                            }
                        }
                    },
                    onAddToCollections = { showCollectionsDialog = true },
                    onMoveToTrash = {
                        viewModel.moveToTrash()
                        onBack()
                    },
                    onOpenDocumentDetails = onOpenDocumentDetails,
                    isOled = isOled,
                    modifier = Modifier.onSizeChanged { size ->
                        if (size.height > 0) {
                            topBarHeightPx = size.height
                        }
                    }
                )
            }
        }

        // Floating Page Notes Badge indicator
        val currentPageQuotes = remember(state.quotes, state.currentPage) {
            state.quotes.filter { it.page == state.currentPage }
        }
        if (currentPageQuotes.isNotEmpty() && state.isControlsVisible && !isWindowed && !isPresenting) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 58.dp, end = 16.dp)
                    .clickable { showTocSheet = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.EditNote,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${currentPageQuotes.size} nota${if (currentPageQuotes.size > 1) "s" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        if (state.writingMode && !isPresenting) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().safeDrawingPadding()
                    .padding(top = readingTopPadding, bottom = readingBottomPadding, end = 12.dp, start = 12.dp)
            ) {
                val wide = maxWidth >= 600.dp
                ReaderWritingPanel(
                    currentTool = state.currentDrawingTool,
                    currentColorHex = state.currentStrokeColorHex,
                    currentStrokeWidth = state.currentStrokeWidth,
                    canUndo = state.canUndo,
                    canRedo = state.canRedo,
                    canClear = state.canUndo,
                    onToolSelected = viewModel::setDrawingTool,
                    onColorSelected = viewModel::setDrawingColor,
                    onStrokeWidthSelected = viewModel::setDrawingStrokeWidth,
                    onUndo = { viewModel.undoLastStroke() },
                    onRedo = { viewModel.redoLastStroke() },
                    onClearPage = { viewModel.clearPageStrokes() },
                    onClose = { viewModel.setWritingMode(false) },
                    textStatus = activePageText?.status ?: "Preparando texto…",
                    modifier = Modifier.align(if (wide) Alignment.CenterEnd else Alignment.BottomEnd)
                        .widthIn(max = 340.dp)
                        .heightIn(max = if (wide) maxHeight else maxHeight * 0.48f)
                )
            }
        }
        // Bottom Bar Overlay
        AnimatedVisibility(
            visible = state.isControlsVisible && !isWindowed && !isPresenting,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ReaderBottomBar(
                currentPage = state.currentPage,
                totalPages = state.totalPages,
                currentTheme = effectiveTheme,
                fontSize = state.settings.fontSizeSp,
                brightness = state.settings.brightness,
                isPdf = isPdf,
                movementLocked = movementLocked,
                isOled = isOled,
                onPageChange = { if (!state.writingMode || !navigationIsStylus[0]) viewModel.goToPage(it) },
                onNextPage = { if (!state.writingMode || !navigationIsStylus[0]) viewModel.nextPage() },
                onPreviousPage = { if (!state.writingMode || !navigationIsStylus[0]) viewModel.previousPage() },
                onThemeChange = { viewModel.updateTheme(it) },
                onFontSizeChange = { viewModel.updateFontSize(it) },
                onBrightnessChange = { viewModel.updateBrightness(it) },
                onToggleOrientation = {
                    (context as? Activity)?.let { currentActivity ->
                        currentActivity.requestedOrientation = if (
                            currentActivity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        ) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                },
                onToggleMovementLock = { movementLocked = !movementLocked },
                onAddToHistory = { viewModel.addToReadingNow() },
                estimatedMinutesRemaining = state.estimatedMinutesRemaining,
                readingAnchorPage = state.readingAnchorPage,
                onReturnToAnchor = {
                    state.readingAnchorPage?.takeIf { !state.writingMode || !navigationIsStylus[0] }?.let {
                        viewModel.goToPage(it)
                        viewModel.resetReadingAnchor()
                    }
                },
                modifier = Modifier.onSizeChanged { size ->
                    if (size.height > 0) {
                        bottomBarHeightPx = size.height
                    }
                }
            )
        }


        // TTS Player floating bar
        if (state.isTtsActive) {
            TtsPlayerBar(
                isPlaying = state.isTtsPlaying,
                currentText = state.ttsText,
                sleepTimerMinutes = state.ttsSleepTimerMinutes,
                onSelectSleepTimer = { minutes -> viewModel.setTtsSleepTimer(minutes) },
                onTogglePlay = {
                    if (state.isTtsPlaying) viewModel.pauseTts() else viewModel.resumeTts()
                },
                onNext = { viewModel.nextTtsSentence() },
                onPrevious = { viewModel.previousTtsSentence() },
                onStop = { viewModel.stopTts() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (isPresenting) 112.dp else if (state.isControlsVisible && !isWindowed) 180.dp else 24.dp)
            )
        }

        // Floating Zoom Control Pill (appears whenever zoomed in > 1.05x)
        AnimatedVisibility(
            visible = zoomScale > 1.05f,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (state.isControlsVisible && !isWindowed) 185.dp else 28.dp)
        ) {
            ZoomControlPill(
                zoomScale = zoomScale,
                isOled = isOled,
                onZoomIn = { applyZoom(zoomScale + 0.25f) },
                onZoomOut = { applyZoom(zoomScale - 0.25f) },
                onResetZoom = { applyZoom(1.0f) }
            )
        }
        if (isPresenting) {
            val presentationPenColor = remember(state.currentStrokeColorHex) {
                try {
                    Color(android.graphics.Color.parseColor(state.currentStrokeColorHex))
                } catch (_: Exception) {
                    Color(0xFFFFD54F)
                }
            }
            val density = LocalDensity.current
            val presentationPenStrokeWidth = remember(state.currentStrokeWidth, density) {
                with(density) {
                    (state.currentStrokeWidth.takeIf { it in 1f..32f } ?: 4f).dp.toPx()
                }
            }

            PresentationAnnotationLayer(
                enabled = presentationPenEnabled && !presentationBlackout,
                strokes = presentationAnnotations[state.currentPage] ?: emptyList(),
                currentColor = presentationPenColor,
                strokeWidth = presentationPenStrokeWidth,
                onStrokeFinished = { stroke ->
                    val page = state.currentPage
                    val currentList = presentationAnnotations[page] ?: emptyList()
                    presentationAnnotations = presentationAnnotations + (page to (currentList + stroke))
                    presentationActivity++
                }
            )

            if (presentationBlackout) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }

            PresentationLaserPointer(
                enabled = laserPointerEnabled,
                position = laserPointerPosition,
                onPositionChanged = { laserPointerPosition = it }
            )

            AnimatedVisibility(
                visible = presentationControlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
            ) {
                PresentationControlBar(
                    title = state.book?.title ?: "Documento",
                    currentPage = state.currentPage + 1,
                    totalPages = state.totalPages,
                    elapsedSeconds = presentationElapsedSeconds,
                    canGoPrevious = state.currentPage > 0,
                    canGoNext = state.currentPage < state.totalPages - 1,
                    isBlackout = presentationBlackout,
                    isLaserPointerEnabled = laserPointerEnabled,
                    isPenEnabled = presentationPenEnabled,
                    canClearAnnotations = !presentationAnnotations[state.currentPage].isNullOrEmpty(),
                    onExit = exitPresentation,
                    onPreviousPage = {
                        viewModel.previousPage()
                        presentationActivity++
                    },
                    onNextPage = {
                        viewModel.nextPage()
                        presentationActivity++
                    },
                    onToggleBlackout = {
                        presentationBlackout = !presentationBlackout
                        presentationActivity++
                    },
                    onToggleLaserPointer = {
                        val next = !laserPointerEnabled
                        laserPointerEnabled = next
                        if (next) {
                            presentationPenEnabled = false
                        }
                        laserPointerPosition = null
                        presentationControlsVisible = true
                        presentationActivity++
                    },
                    onTogglePen = {
                        val next = !presentationPenEnabled
                        presentationPenEnabled = next
                        if (next) {
                            laserPointerEnabled = false
                            laserPointerPosition = null
                        }
                        presentationControlsVisible = true
                        presentationActivity++
                    },
                    onClearAnnotations = {
                        presentationAnnotations = presentationAnnotations - state.currentPage
                        presentationActivity++
                    }
                )
            }
        }
    }

    // Modal Sheets & Dialogs
    if (showSettingsSheet) {
        ReadingSettingsSheet(
            settings = state.settings,
            isPdf = isPdf,
            zoomScale = zoomScale,
            onZoomChange = { applyZoom(it) },
            onDismiss = { showSettingsSheet = false },
            onThemeChange = { viewModel.updateTheme(it) },
            onViewModeChange = { viewModel.updateViewMode(it) },
            onFontSizeChange = { viewModel.updateFontSize(it) },
            onLineSpacingChange = { viewModel.updateLineSpacing(it) },
            onMarginChange = { viewModel.updateMargin(it) },
            onToggleFontBold = { viewModel.updateFontBold(it) },
            onFontFamilyChange = { viewModel.updateFontFamily(it) },
            onToggleBionicReading = { viewModel.toggleBionicReading() },
            onOpenGeneralSettings = {
                showSettingsSheet = false
                // Opens general settings
            }
        )
    }

    if (showTocSheet) {
        TocBookmarksSheet(
            outline = state.outline,
            bookmarks = state.bookmarks,
            quotes = state.quotes,
            currentPage = state.currentPage,
            onDismiss = { showTocSheet = false },
            onNavigateToPage = { viewModel.goToPage(it) },
            onDeleteBookmark = { viewModel.deleteBookmark(it) },
            onDeleteQuote = { viewModel.deleteQuote(it) },
            onUpdateQuote = { viewModel.updateQuote(it) },
            onAddQuoteOnPage = { showAddQuoteDialog = true },
            onDeleteAllBookmarks = { viewModel.deleteAllBookmarks() },
            onDeleteAllQuotes = { viewModel.deleteAllQuotes() }
        )
    }

    if (showSearchSheet) {
        SearchSheet(
            searchResults = state.searchResults,
            isSearching = state.isSearching,
            onSearch = { viewModel.search(it) },
            onDismiss = { showSearchSheet = false },
            onNavigateToPage = { page ->
                viewModel.goToPage(page)
                showSearchSheet = false
            }
        )
    }

    state.book?.let { currentBook ->
        if (showCollectionsDialog) {
            BookCollectionsDialog(
                bookTitle = currentBook.title,
                allCollections = allCollections,
                initialSelectedIds = currentCollections.map { it.id }.toSet(),
                onSave = viewModel::saveCollections,
                onCreateNewCollection = {
                    showCollectionsDialog = false
                    showCreateCollectionDialog = true
                },
                onDismiss = { showCollectionsDialog = false }
            )
        }
    }

    if (showCreateCollectionDialog) {
        CreateCollectionDialog(
            onConfirm = { name ->
                viewModel.createCollection(name)
                showCreateCollectionDialog = false
            },
            onDismiss = { showCreateCollectionDialog = false }
        )
    }

    if (showAddQuoteDialog) {
        val resolvedText = currentPageText?.layout?.text.orEmpty()
        if ((currentPageText == null || currentPageText.isLoading || resolvedText.isBlank()) && !quoteWithoutText) {
            AlertDialog(
                onDismissRequest = { showAddQuoteDialog = false },
                title = { Text("Texto de la página") },
                text = { Text(currentPageText?.status ?: "Preparando texto…") },
                confirmButton = {
                    if (currentPageText?.isLoading == false) {
                        TextButton(onClick = { quoteWithoutText = true }) { Text("Añadir nota manual") }
                    } else TextButton(onClick = { showAddQuoteDialog = false }) { Text("Cerrar") }
                },
                dismissButton = {
                    if (currentPageText?.isLoading == false) {
                        TextButton(onClick = { showAddQuoteDialog = false }) { Text("Cerrar") }
                    }
                }
            )
        } else {

        AddQuoteDialog(
            initialText = resolvedText.take(250).trim(),
            page = state.currentPage,
            onDismiss = { showAddQuoteDialog = false },
            onSaveQuote = { text, note, colorHex ->
                viewModel.addQuote(text, note, colorHex)
                showAddQuoteDialog = false
            }
        )
        }
    }

    if (showReviewDialog) {
        ReviewDialog(
            initialRating = state.book?.rating ?: 0f,
            initialReview = state.book?.review ?: "",
            onSave = { rating, review ->
                viewModel.updateReview(rating, review)
            },
            onDismiss = { showReviewDialog = false }
        )
    }

    if (showTextSelectorSheet) {
        val resolvedText = currentPageText?.layout?.text.orEmpty()
        InteractiveTextSelectorSheet(
            isVisible = showTextSelectorSheet,
            pageText = resolvedText,
            textStatus = currentPageText?.status ?: "Preparando texto…",
            page = state.currentPage,
            bookTitle = state.book?.title ?: "Documento",
            bookAuthor = state.book?.author ?: "Autor desconocido",
            onSaveQuote = { text, note, colorHex ->
                viewModel.addQuote(text, note, colorHex)
                showTextSelectorSheet = false
            },
            onPlayTts = { text ->
                viewModel.startTts(text)
            },
            onDismiss = { showTextSelectorSheet = false }
        )
    }

    if (showTranslateDialog) {
        DocumentTranslationDialog(
            bookTitle = state.book?.title ?: "Documento",
            bookAuthor = state.book?.author ?: "Autor desconocido",
            currentPage = state.currentPage,
            totalPages = state.totalPages,
            getCurrentPageText = { currentPageText?.layout?.text.orEmpty() },
            getPageTextByIndex = viewModel::getNativePageTextForExport,
            onBookGenerated = { file ->
                viewModel.scanGeneratedBook(file)
            },
            onDismiss = { showTranslateDialog = false }
        )
    }
    if (showMissingAiKey) {
        AlertDialog(
            onDismissRequest = { showMissingAiKey = false },
            title = { Text("Configura OpenRouter") },
            text = { Text("Necesitas una clave API para usar el asistente. Puedes configurarla en Ajustes.") },
            confirmButton = {
                TextButton(onClick = {
                    showMissingAiKey = false
                    onOpenAppSettings()
                }) { Text("Abrir Ajustes") }
            },
            dismissButton = {
                TextButton(onClick = { showMissingAiKey = false }) { Text("Cancelar") }
            }
        )
    }
    if (showPageManager) {
        val book = state.book
        if (book != null) {
            PdfPageManagerScreen(
                title = book.title,
                pageCount = pdfPageCount,
                isSaving = pdfSaving,
                error = pdfToolError,
                onSave = { plan ->
                    scope.launch {
                        pdfSaving = true
                        try {
                            val output = outputPdf("edited")
                            pdfEditor.applyPagePlan(
                                File(book.filePath), plan.pageOrder, plan.deletedPageIndices, plan.rotations, output
                            )
                            showPageManager = false
                            registerAndOpen(output)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            pdfToolError = error.message ?: "Plan de páginas no válido"
                        } finally { pdfSaving = false }
                    }
                },
                onExtract = { indices ->
                    scope.launch {
                        pdfSaving = true
                        try {
                            val output = outputPdf("extract")
                            pdfEditor.extractPages(File(book.filePath), indices, output)
                            showPageManager = false
                            registerAndOpen(output)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            pdfToolError = error.message ?: "No se pudieron extraer las páginas"
                        } finally { pdfSaving = false }
                    }
                },
                onDismiss = { if (!pdfSaving) { showPageManager = false; pdfToolError = null } }
            )
        } else showPageManager = false
    }
    if (showPdfSignature) {
        PdfSignatureDialog(
            pageCount = pdfPageCount,
            initialPage = state.currentPage.coerceIn(0, (pdfPageCount - 1).coerceAtLeast(0)),
            isSaving = pdfSaving,
            errorMessage = signatureError,
            onDismiss = {
                if (!pdfSaving) {
                    showPdfSignature = false
                    signatureError = null
                }
            },
            onSave = { signature: PdfVisualSignature ->
                val book = state.book
                if (book == null) {
                    signatureError = "Documento no disponible"
                } else scope.launch {
                    pdfSaving = true
                    signatureError = null
                    try {
                        val output = outputPdf("firma-visual")
                        pdfEditor.addVisualSignature(File(book.filePath), signature, output)
                        registerAndOpen(output)
                        showPdfSignature = false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        signatureError = "No se pudo guardar la copia. Vuelve a dibujar la firma e inténtalo de nuevo."
                    } finally {
                        pdfSaving = false
                    }
                }
            }
        )
    }
    if (showPdfAssistant) {
        if (loadingAssistantText) {
            AlertDialog(onDismissRequest = { showPdfAssistant = false }, confirmButton = {}, title = { Text("Preparando documento…") },
                text = { CircularProgressIndicator() })
        } else {
            PdfAiAssistantSheet(
                documentTitle = state.book?.title ?: "Documento",
                documentText = assistantText,
                client = openRouter,
                onDismiss = { showPdfAssistant = false }
            )
        }
    }
    if (pdfSaving && !showPageManager && !showPdfSignature && pdfProgress == null) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Procesando PDF…") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text("La operación puede tardar en documentos grandes.")
                }
            }
        )
    }

    if (pdfProgress != null) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Creando copia con OCR") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(progress = { pdfProgress!!.first.toFloat() / pdfProgress!!.second.coerceAtLeast(1) })
                    Text("Página ${pdfProgress!!.first} de ${pdfProgress!!.second}")
                }
            }
        )
    }
}

@Composable
private fun WindowedReaderBar(
    title: String,
    currentPage: Int,
    totalPages: Int,
    onBack: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${currentPage + 1}/$totalPages",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(
                onClick = onPreviousPage,
                enabled = currentPage > 0,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Página anterior")
            }
            IconButton(
                onClick = onNextPage,
                enabled = totalPages <= 0 || currentPage < totalPages - 1,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Página siguiente")
            }
        }
    }
}

@Composable
fun ZoomControlPill(
    zoomScale: Float,
    isOled: Boolean = false,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = if (isOled) Color(0xFF000000) else MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp).copy(alpha = 0.94f),
        tonalElevation = if (isOled) 0.dp else 6.dp,
        shadowElevation = if (isOled) 0.dp else 8.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isOled) Color(0xFF2E2E2E) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onZoomOut,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Reducir zoom",
                    tint = if (isOled) Color(0xFFEDEDED) else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }

            Surface(
                onClick = onResetZoom,
                shape = RoundedCornerShape(12.dp),
                color = if (isOled) Color(0xFF161616) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                modifier = Modifier.height(28.dp)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${(zoomScale * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (isOled) Color(0xFFEEEEEE) else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            IconButton(
                onClick = onZoomIn,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Aumentar zoom",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }

            VerticalDivider(
                modifier = Modifier
                    .height(20.dp)
                    .padding(horizontal = 2.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            TextButton(
                onClick = onResetZoom,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "1x",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
