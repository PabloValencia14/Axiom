package org.readera.openreadera.ui.library

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.BookStatus
import org.readera.openreadera.data.model.Quote
import org.readera.openreadera.ui.reader.components.AddQuoteDialog
import org.readera.openreadera.R
import org.readera.openreadera.ui.library.components.*
import org.readera.openreadera.ui.zlib.ZLibraryScreen
import org.readera.openreadera.ui.catalog.OpdsCatalogScreen
import org.readera.openreadera.ui.papers.AcademicPapersScreen
import org.readera.openreadera.ui.journal.ReadingJournalScreen
import org.readera.openreadera.ui.sync.DriveSyncScreen
import org.readera.openreadera.ui.stats.ReadingStatsScreen
import org.readera.openreadera.ui.manga.ComicsMangaScreen
import org.readera.openreadera.data.scanner.DocumentScanProcessor
import org.readera.openreadera.data.pdf.PdfDocumentEditor
import org.readera.openreadera.data.scanner.StorageScanner
import org.readera.openreadera.data.scanner.DocumentCategoryClassifier
import org.readera.openreadera.OpenReadEraApplication
import org.readera.openreadera.data.preferences.ReaderPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenBookDetails: (Long) -> Unit,
    onOpenReader: (Long) -> Unit = {},
    onOpenFile: (Uri) -> Unit = {},
    onOpenSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val preferences = remember(context) {
        (context.applicationContext as? OpenReadEraApplication)?.preferences ?: ReaderPreferences(context)
    }
    val isCompactMode by preferences.libraryCompactMode.collectAsState()
    // Dialog states
    var showSortDialog by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showFeedbackDialog by remember { mutableStateOf(false) }
    var showClearListDialog by remember { mutableStateOf(false) }
    var showEmptyTrashDialog by remember { mutableStateOf(false) }
    var showCreateCollectionDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var bookToMoveToTrash by remember { mutableStateOf<Book?>(null) }

    // Handlers for reading now and trash
    val handleRemoveFromReadingNow: (Book) -> Unit = { book ->
        viewModel.removeFromReadingNow(book.id)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Se quitó \"${book.title}\" de Leyendo ahora (el progreso se conserva)",
                actionLabel = "DESHACER",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.markAsReading(book.id)
            }
        }
    }

    val handleMoveToTrash: (Long) -> Unit = { bookId ->
        val b = state.allActiveBooks.find { it.id == bookId } ?: state.books.find { it.id == bookId }
        if (b != null) {
            bookToMoveToTrash = b
        } else {
            viewModel.moveToTrash(bookId)
        }
    }

    // Book action dialogs
    var bookForCollections by remember { mutableStateOf<Book?>(null) }
    var bookForEditMetadata by remember { mutableStateOf<Book?>(null) }
    var quoteToEdit by remember { mutableStateOf<Quote?>(null) }

    // SAF file picker for "Abrir un solo archivo"
    val openSingleFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onOpenFile(uri)
        }
    }

    val scanProcessor = remember(context) { DocumentScanProcessor(context) }
    val pdfEditor = remember(context) { PdfDocumentEditor(context) }
    var isProcessingIntake by remember { mutableStateOf(false) }
    var intakeStatusMessage by remember { mutableStateOf<String?>(null) }
    var intakeProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val documentScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_CANCELED) {
            return@rememberLauncherForActivityResult
        }
        if (activityResult.resultCode != Activity.RESULT_OK) {
            scope.launch {
                snackbarHostState.showSnackbar("No se pudo completar el escaneo del documento")
            }
            return@rememberLauncherForActivityResult
        }
        val scanResult = runCatching {
            GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
        }.getOrNull()
        val scannedPdfUri = scanResult?.pdf?.uri
        if (scannedPdfUri == null) {
            scope.launch {
                snackbarHostState.showSnackbar("El escáner no devolvió ningún archivo PDF")
            }
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            isProcessingIntake = true
            intakeStatusMessage = "Importando documento escaneado..."
            intakeProgress = null
            var importedFile: File? = null
            var ocrDestination: File? = null
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val rawFile = scanProcessor.importScannedPdf(scannedPdfUri, "Escaneo_$timestamp")
                importedFile = rawFile
                val scansDir = rawFile.parentFile ?: File(context.filesDir, "scans")
                val targetOcrFile = uniqueScanOutputFile(scansDir, "${rawFile.nameWithoutExtension}_ocr.pdf")
                ocrDestination = targetOcrFile

                intakeStatusMessage = "Reconociendo texto (OCR)..."
                var ocrFailed = false
                val finalPdfFile = try {
                    val searchable = scanProcessor.makeSearchableCopy(
                        source = rawFile,
                        destination = targetOcrFile,
                        onProgress = { current, total ->
                            scope.launch {
                                if (isProcessingIntake) {
                                    intakeProgress = current to total
                                    intakeStatusMessage = "Reconociendo texto (OCR $current/$total)..."
                                }
                            }
                        }
                    )
                    if (searchable.isFile && searchable.length() > 0L && searchable.absolutePath != rawFile.absolutePath) {
                        rawFile.delete()
                    }
                    searchable
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ocrFailed = true
                    if (targetOcrFile.exists()) {
                        targetOcrFile.delete()
                    }
                    rawFile
                }

                intakeProgress = null
                intakeStatusMessage = "Registrando documento en la biblioteca..."
                val registeredBook = StorageScanner.scanSingleFile(context, finalPdfFile)
                if (registeredBook != null) {
                    if (ocrFailed) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Escaneo guardado sin texto buscable (no se pudo completar el OCR)",
                                duration = SnackbarDuration.Long
                            )
                        }
                    }
                    onOpenReader(registeredBook.id)
                } else {
                    snackbarHostState.showSnackbar("No se pudo registrar el documento escaneado en la biblioteca")
                }
            } catch (e: CancellationException) {
                ocrDestination?.takeIf { it.exists() }?.delete()
                throw e
            } catch (_: Exception) {
                ocrDestination?.takeIf { it.exists() }?.delete()
                importedFile?.takeIf { it.exists() }?.delete()
                snackbarHostState.showSnackbar("Error al importar el documento escaneado")
            } finally {
                isProcessingIntake = false
                intakeStatusMessage = null
                intakeProgress = null
            }
        }
    }

    val imagesToPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) {
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            isProcessingIntake = true
            intakeProgress = null
            intakeStatusMessage = "Creando PDF desde ${uris.size} ${if (uris.size == 1) "imagen" else "imágenes"}..."
            var generatedPdf: File? = null
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val pdfFile = scanProcessor.imageUrisToPdf(uris, "Imagenes_$timestamp")
                generatedPdf = pdfFile
                intakeStatusMessage = "Registrando PDF en la biblioteca..."
                val registeredBook = StorageScanner.scanSingleFile(context, pdfFile)
                if (registeredBook != null) {
                    onOpenReader(registeredBook.id)
                } else {
                    snackbarHostState.showSnackbar("No se pudo registrar el PDF creado en la biblioteca")
                }
            } catch (e: CancellationException) {
                generatedPdf?.takeIf { it.exists() }?.delete()
                throw e
            } catch (_: Exception) {
                generatedPdf?.takeIf { it.exists() }?.delete()
                snackbarHostState.showSnackbar("No se pudo crear el PDF desde las imágenes seleccionadas")
            } finally {
                isProcessingIntake = false
                intakeStatusMessage = null
                intakeProgress = null
            }
        }
    }

    val mergePdfsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) {
            return@rememberLauncherForActivityResult
        }
        if (uris.size < 2) {
            scope.launch { snackbarHostState.showSnackbar("Selecciona al menos dos archivos PDF para combinarlos") }
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            isProcessingIntake = true
            intakeProgress = null
            intakeStatusMessage = "Importando ${uris.size} archivos PDF..."
            val importedFiles = ArrayList<File>(uris.size)
            var mergedPdf: File? = null
            var keepOutput = false
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                uris.forEachIndexed { index, uri ->
                    intakeStatusMessage = "Importando PDF ${index + 1} de ${uris.size}..."
                    importedFiles += scanProcessor.importScannedPdf(uri, "Combinacion_${timestamp}_${index + 1}")
                }
                val output = uniqueScanOutputFile(
                    File(context.filesDir, "pdf-tools"),
                    "Combinado_$timestamp.pdf"
                )
                mergedPdf = output
                intakeStatusMessage = "Combinando páginas..."
                pdfEditor.merge(importedFiles, output)
                intakeStatusMessage = "Registrando PDF combinado..."
                val registeredBook = StorageScanner.scanSingleFile(context, output)
                if (registeredBook != null) {
                    keepOutput = true
                    onOpenReader(registeredBook.id)
                } else {
                    snackbarHostState.showSnackbar("No se pudo registrar el PDF combinado en la biblioteca")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snackbarHostState.showSnackbar("No se pudieron combinar los archivos PDF seleccionados")
            } finally {
                importedFiles.forEach { it.delete() }
                if (!keepOutput) mergedPdf?.delete()
                isProcessingIntake = false
                intakeStatusMessage = null
                intakeProgress = null
            }
        }
    }
    val startMergePdfs: () -> Unit = {
        if (!isProcessingIntake) {
            runCatching { mergePdfsLauncher.launch(arrayOf("application/pdf")) }
                .onFailure {
                    scope.launch { snackbarHostState.showSnackbar("No se pudo abrir el selector de PDF") }
                }
        }
    }



    val startDocumentScan: () -> Unit = {
        if (!isProcessingIntake) {
            val activity = context.findActivity()
            if (activity == null) {
                scope.launch {
                    snackbarHostState.showSnackbar("No se pudo acceder a la actividad para iniciar el escáner")
                }
            } else {
                runCatching {
                    val options = GmsDocumentScannerOptions.Builder()
                        .setGalleryImportAllowed(true)
                        .setPageLimit(50)
                        .setResultFormats(RESULT_FORMAT_PDF)
                        .build()
                    GmsDocumentScanning.getClient(options)
                        .getStartScanIntent(activity)
                        .addOnSuccessListener { intentSender ->
                            runCatching {
                                documentScannerLauncher.launch(
                                    IntentSenderRequest.Builder(intentSender).build()
                                )
                            }.onFailure {
                                scope.launch {
                                    snackbarHostState.showSnackbar("No se pudo abrir el escáner de documentos")
                                }
                            }
                        }
                        .addOnFailureListener {
                            scope.launch {
                                snackbarHostState.showSnackbar("El escáner de documentos no está disponible en este dispositivo")
                            }
                        }
                }.onFailure {
                    scope.launch {
                        snackbarHostState.showSnackbar("No se pudo iniciar Google Play Services para escanear")
                    }
                }
            }
        }
    }

    val startImagesToPdf: () -> Unit = {
        if (!isProcessingIntake) {
            runCatching {
                imagesToPdfLauncher.launch(arrayOf("image/*"))
            }.onFailure {
                scope.launch {
                    snackbarHostState.showSnackbar("No se pudo abrir el selector de imágenes")
                }
            }
        }
    }

    // Classic Navigation Drawer
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                // Drawer Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = RoundedCornerShape(11.dp),
                        color = Color.White
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.axiom_launcher_mark),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Axiom",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        DrawerNavEntry("Leyendo ahora", Icons.AutoMirrored.Filled.MenuBook, state.currentSection == LibrarySection.READING_NOW) {
                            viewModel.setSection(LibrarySection.READING_NOW)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Libros y documentos", Icons.Default.LibraryBooks, state.currentSection == LibrarySection.ALL_DOCUMENTS) {
                            viewModel.setSection(LibrarySection.ALL_DOCUMENTS)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Favoritos", Icons.Default.Star, state.currentSection == LibrarySection.FAVORITES) {
                            viewModel.setSection(LibrarySection.FAVORITES)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Para leer", Icons.Default.BookmarkBorder, state.currentSection == LibrarySection.TO_READ) {
                            viewModel.setSection(LibrarySection.TO_READ)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Leídos", Icons.Default.DoneAll, state.currentSection == LibrarySection.HAVE_READ) {
                            viewModel.setSection(LibrarySection.HAVE_READ)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Autores", Icons.Default.Person, state.currentSection == LibrarySection.AUTHORS) {
                            viewModel.setSection(LibrarySection.AUTHORS)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Series", Icons.Default.ViewCarousel, state.currentSection == LibrarySection.SERIES) {
                            viewModel.setSection(LibrarySection.SERIES)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Categorías y colecciones", Icons.Default.FolderSpecial, state.currentSection == LibrarySection.COLLECTIONS) {
                            viewModel.setSection(LibrarySection.COLLECTIONS)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Formatos", Icons.Default.Description, state.currentSection == LibrarySection.FORMATS) {
                            viewModel.setSection(LibrarySection.FORMATS)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Carpetas", Icons.Default.Folder, state.currentSection == LibrarySection.FOLDERS) {
                            viewModel.setSection(LibrarySection.FOLDERS)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Citas y notas", Icons.Default.FormatQuote, state.currentSection == LibrarySection.QUOTES) {
                            viewModel.setSection(LibrarySection.QUOTES)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Descargas", Icons.Default.Download, state.currentSection == LibrarySection.DOWNLOADS) {
                            viewModel.setSection(LibrarySection.DOWNLOADS)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Descargar Libros", Icons.Default.CloudDownload, state.currentSection == LibrarySection.ZLIBRARY) {
                            viewModel.setSection(LibrarySection.ZLIBRARY)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Catálogos OPDS", Icons.Default.RssFeed, state.currentSection == LibrarySection.OPDS_CATALOGS) {
                            viewModel.setSection(LibrarySection.OPDS_CATALOGS)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Papers Académicos (arXiv)", Icons.Default.School, state.currentSection == LibrarySection.ACADEMIC_PAPERS) {
                            viewModel.setSection(LibrarySection.ACADEMIC_PAPERS)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Cómics y Manga", Icons.Default.CollectionsBookmark, state.currentSection == LibrarySection.COMICS_MANGA) {
                            viewModel.setSection(LibrarySection.COMICS_MANGA)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Diario de Lecturas", Icons.Default.RateReview, state.currentSection == LibrarySection.JOURNAL) {
                            viewModel.setSection(LibrarySection.JOURNAL)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Estadísticas de lectura", Icons.Default.BarChart, state.currentSection == LibrarySection.READING_STATS) {
                            viewModel.setSection(LibrarySection.READING_STATS)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Sincronización Drive", Icons.Default.CloudSync, state.currentSection == LibrarySection.DRIVE_SYNC) {
                            viewModel.setSection(LibrarySection.DRIVE_SYNC)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        DrawerNavEntry("Papelera", Icons.Default.Delete, state.currentSection == LibrarySection.TRASH) {
                            viewModel.setSection(LibrarySection.TRASH)
                            scope.launch { drawerState.close() }
                        }
                    }
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                    item {
                        DrawerNavEntry("Configuraciones", Icons.Default.Settings, false) {
                            scope.launch { drawerState.close() }
                            onOpenSettings()
                        }
                    }
                    item {
                        DrawerNavEntry("Enviar comentario", Icons.Default.Feedback, false) {
                            scope.launch { drawerState.close() }
                            showFeedbackDialog = true
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TopAppBar(
                        title = {
                            if (state.selectedCollection != null) {
                                Column {
                                    Text("Colecciones", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(state.selectedCollection?.name ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                }
                            } else if (state.selectedAuthor != null) {
                                Column {
                                    Text("Autores", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(state.selectedAuthor?.displayName ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                }
                            } else if (state.selectedSeries != null) {
                                Column {
                                    Text("Series", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(state.selectedSeries?.name ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                }
                            } else if (state.selectedFormat != null) {
                                Column {
                                    Text("Formatos", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(state.selectedFormat?.format ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                }
                            } else if (state.selectedFolder != null) {
                                Column {
                                    Text("Carpetas", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(state.selectedFolder?.folderName ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(16.dp),
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color.White
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.axiom_launcher_mark),
                                                contentDescription = null,
                                                tint = Color.Unspecified,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(1.dp)
                                            )
                                        }
                                        Text(
                                            text = "Axiom",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = state.currentSection.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            if (state.selectedCollection != null || state.selectedAuthor != null || state.selectedSeries != null || state.selectedFormat != null || state.selectedFolder != null) {
                                IconButton(onClick = { viewModel.clearDrilldown() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                                }
                            } else {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Menú lateral")
                                }
                            }
                        },
                        actions = {
                            val isBookListSection = state.selectedCollection != null ||
                                state.selectedAuthor != null ||
                                state.selectedSeries != null ||
                                state.selectedFormat != null ||
                                state.selectedFolder != null ||
                                state.currentSection in setOf(
                                    LibrarySection.READING_NOW,
                                    LibrarySection.ALL_DOCUMENTS,
                                    LibrarySection.FAVORITES,
                                    LibrarySection.TO_READ,
                                    LibrarySection.HAVE_READ,
                                    LibrarySection.DOWNLOADS
                                )

                            // Papelera vacía action on top
                            if (state.currentSection == LibrarySection.TRASH && state.trashBooks.isNotEmpty()) {
                                TextButton(onClick = { showEmptyTrashDialog = true }) {
                                    Text("PAPELERA VACÍA", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                }
                            }

                            // View mode toggle: Grid vs Compact list
                            if (isBookListSection) {
                                IconButton(
                                    onClick = { preferences.updateLibraryCompactMode(!isCompactMode) }
                                ) {
                                    Icon(
                                        imageVector = if (isCompactMode) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                                        contentDescription = if (isCompactMode) "Cambiar a cuadrícula" else "Cambiar a lista compacta",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            // Search button
                            if (state.currentSection != LibrarySection.ZLIBRARY) {
                                val searchPlaceholder = when {
                                    state.selectedAuthor != null -> "Buscar libros del autor"
                                    state.selectedSeries != null -> "Buscar libros de la serie"
                                    state.selectedCollection != null -> "Buscar libros de la colección"
                                    state.selectedFormat != null -> "Buscar libros del formato"
                                    state.selectedFolder != null -> "Buscar libros de la carpeta"
                                    state.currentSection == LibrarySection.AUTHORS -> "Buscar autores"
                                    state.currentSection == LibrarySection.SERIES -> "Buscar series"
                                    state.currentSection == LibrarySection.COLLECTIONS -> "Buscar colecciones"
                                    state.currentSection == LibrarySection.FORMATS -> "Buscar formatos"
                                    state.currentSection == LibrarySection.FOLDERS -> "Buscar carpetas"
                                    state.currentSection == LibrarySection.QUOTES -> "Buscar citas y notas"
                                    state.currentSection == LibrarySection.FAVORITES -> "Buscar en favoritos"
                                    state.currentSection == LibrarySection.TO_READ -> "Buscar en para leer"
                                    state.currentSection == LibrarySection.HAVE_READ -> "Buscar en leídos"
                                    state.currentSection == LibrarySection.READING_NOW -> "Buscar en leyendo ahora"
                                    state.currentSection == LibrarySection.TRASH -> "Buscar en papelera"
                                    else -> "Buscar libros y documentos"
                                }
                                IconButton(onClick = { viewModel.setSearchOpen(!state.isSearchOpen) }) {
                                    Icon(Icons.Default.Search, contentDescription = searchPlaceholder)
                                }
                            }

                            // Section 3-dots overflow menu
                            var showMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Más opciones")
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                if (isBookListSection) {
                                    DropdownMenuItem(
                                        text = { Text(if (isCompactMode) "Vista en cuadrícula" else "Vista compacta") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (isCompactMode) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            preferences.updateLibraryCompactMode(!isCompactMode)
                                        }
                                    )
                                }
                                when (state.currentSection) {
                                    LibrarySection.READING_NOW, LibrarySection.FAVORITES, LibrarySection.TO_READ, LibrarySection.HAVE_READ -> {
                                        DropdownMenuItem(
                                            text = { Text("Abrir un solo archivo") },
                                            onClick = {
                                                showMenu = false
                                                openSingleFileLauncher.launch(arrayOf("*/*"))
                                            }
                                        )
                                        DocumentIntakeMenuItems(
                                            enabled = !isProcessingIntake,
                                            onScanDocument = {
                                                showMenu = false
                                                startDocumentScan()
                                            },
                                            onImagesToPdf = {
                                                showMenu = false
                                                startImagesToPdf()
                                            },
                                            onMergePdfs = {
                                                showMenu = false
                                                startMergePdfs()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Limpiar la lista") },
                                            onClick = {
                                                showMenu = false
                                                showClearListDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Ordenados por") },
                                            onClick = {
                                                showMenu = false
                                                showSortDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Filtrar") },
                                            onClick = {
                                                showMenu = false
                                                showFilterDialog = true
                                            }
                                        )
                                    }
                                    LibrarySection.ALL_DOCUMENTS -> {
                                        DropdownMenuItem(
                                            text = { Text("Abrir un solo archivo") },
                                            onClick = {
                                                showMenu = false
                                                openSingleFileLauncher.launch(arrayOf("*/*"))
                                            }
                                        )
                                        DocumentIntakeMenuItems(
                                            enabled = !isProcessingIntake,
                                            onScanDocument = {
                                                showMenu = false
                                                startDocumentScan()
                                            },
                                            onImagesToPdf = {
                                                showMenu = false
                                                startImagesToPdf()
                                            },
                                            onMergePdfs = {
                                                showMenu = false
                                                startMergePdfs()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Ordenados por") },
                                            onClick = {
                                                showMenu = false
                                                showSortDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Filtrar") },
                                            onClick = {
                                                showMenu = false
                                                showFilterDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Recuperar todas las cubiertas") },
                                            onClick = {
                                                showMenu = false
                                                viewModel.triggerScan()
                                            }
                                        )
                                    }
                                    LibrarySection.AUTHORS, LibrarySection.SERIES -> {
                                        DropdownMenuItem(
                                            text = { Text("Comenzar búsqueda") },
                                            onClick = {
                                                showMenu = false
                                                viewModel.setSearchOpen(true)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Abrir un solo archivo") },
                                            onClick = {
                                                showMenu = false
                                                openSingleFileLauncher.launch(arrayOf("*/*"))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Ordenados por") },
                                            onClick = {
                                                showMenu = false
                                                showSortDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Editar") },
                                            onClick = { showMenu = false }
                                        )
                                    }
                                    LibrarySection.COLLECTIONS -> {
                                        DropdownMenuItem(
                                            text = { Text("Abrir un solo archivo") },
                                            onClick = {
                                                showMenu = false
                                                openSingleFileLauncher.launch(arrayOf("*/*"))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Editar") },
                                            onClick = { showMenu = false }
                                        )
                                    }
                                    LibrarySection.FORMATS -> {
                                        DropdownMenuItem(
                                            text = { Text("Comenzar búsqueda") },
                                            onClick = {
                                                showMenu = false
                                                viewModel.setSearchOpen(true)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Abrir un solo archivo") },
                                            onClick = {
                                                showMenu = false
                                                openSingleFileLauncher.launch(arrayOf("*/*"))
                                            }
                                        )
                                    }
                                    LibrarySection.FOLDERS -> {
                                        DropdownMenuItem(
                                            text = { Text("Abrir un solo archivo") },
                                            onClick = {
                                                showMenu = false
                                                openSingleFileLauncher.launch(arrayOf("*/*"))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Ordenados por") },
                                            onClick = {
                                                showMenu = false
                                                showSortDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Ocultar eliminados") },
                                            onClick = { showMenu = false }
                                        )
                                    }
                                    LibrarySection.DOWNLOADS -> {
                                        DropdownMenuItem(
                                            text = { Text("Abrir un solo archivo") },
                                            onClick = {
                                                showMenu = false
                                                openSingleFileLauncher.launch(arrayOf("*/*"))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Crear carpeta") },
                                            onClick = { showMenu = false }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Ordenados por") },
                                            onClick = {
                                                showMenu = false
                                                showSortDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Filtrar") },
                                            onClick = {
                                                showMenu = false
                                                showFilterDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Ocultar eliminados") },
                                            onClick = { showMenu = false }
                                        )
                                    }
                                    LibrarySection.TRASH -> {
                                        DropdownMenuItem(
                                            text = { Text("Abrir un solo archivo") },
                                            onClick = {
                                                showMenu = false
                                                openSingleFileLauncher.launch(arrayOf("*/*"))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Ordenados por") },
                                            onClick = {
                                                showMenu = false
                                                showSortDialog = true
                                            }
                                        )
                                    }
                                    else -> {
                                        DropdownMenuItem(
                                            text = { Text("Abrir un solo archivo") },
                                            onClick = {
                                                showMenu = false
                                                openSingleFileLauncher.launch(arrayOf("*/*"))
                                            }
                                        )
                                        DocumentIntakeMenuItems(
                                            enabled = !isProcessingIntake,
                                            onScanDocument = {
                                                showMenu = false
                                                startDocumentScan()
                                            },
                                            onImagesToPdf = {
                                                showMenu = false
                                                startImagesToPdf()
                                            },
                                            onMergePdfs = {
                                                showMenu = false
                                                startMergePdfs()
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    )

                    // Contextual Search View with History
                    AnimatedVisibility(visible = state.isSearchOpen && state.currentSection != LibrarySection.ZLIBRARY) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                val searchPlaceholder = when {
                                    state.selectedAuthor != null -> "Buscar libros del autor"
                                    state.selectedSeries != null -> "Buscar libros de la serie"
                                    state.selectedCollection != null -> "Buscar libros de la colección"
                                    state.selectedFormat != null -> "Buscar libros del formato"
                                    state.selectedFolder != null -> "Buscar libros de la carpeta"
                                    state.currentSection == LibrarySection.AUTHORS -> "Buscar autores"
                                    state.currentSection == LibrarySection.SERIES -> "Buscar series"
                                    state.currentSection == LibrarySection.COLLECTIONS -> "Buscar colecciones"
                                    state.currentSection == LibrarySection.FORMATS -> "Buscar formatos"
                                    state.currentSection == LibrarySection.FOLDERS -> "Buscar carpetas"
                                    state.currentSection == LibrarySection.QUOTES -> "Buscar citas y notas"
                                    state.currentSection == LibrarySection.FAVORITES -> "Buscar en favoritos"
                                    state.currentSection == LibrarySection.TO_READ -> "Buscar en para leer"
                                    state.currentSection == LibrarySection.HAVE_READ -> "Buscar en leídos"
                                    state.currentSection == LibrarySection.READING_NOW -> "Buscar en leyendo ahora"
                                    state.currentSection == LibrarySection.TRASH -> "Buscar en papelera"
                                    else -> "Buscar libros y documentos"
                                }
                                OutlinedTextField(
                                    value = state.searchQuery,
                                    onValueChange = { viewModel.setSearchQuery(it) },
                                    placeholder = { Text(searchPlaceholder) },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = searchPlaceholder) },
                                    trailingIcon = {
                                        if (state.searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                                Icon(Icons.Default.Close, contentDescription = "Limpiar búsqueda")
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                // Search History
                                if (state.searchQuery.isEmpty() && state.searchHistory.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Búsquedas recientes",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    LazyColumn(modifier = Modifier.heightIn(max = 140.dp)) {
                                        items(state.searchHistory) { queryItem ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { viewModel.submitSearch(queryItem.query) }
                                                    .padding(vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(queryItem.query, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = { viewModel.clearSearchHistory() }) {
                                            Text("Limpiar historial", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = isProcessingIntake) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.2.dp,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = intakeStatusMessage ?: "Procesando documento...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    intakeProgress?.let { (current, total) ->
                                        if (total > 0) {
                                            Text(
                                                text = "$current/$total",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                                val progressPair = intakeProgress
                                if (progressPair != null && progressPair.second > 0) {
                                    LinearProgressIndicator(
                                        progress = {
                                            (progressPair.first.toFloat() / progressPair.second.toFloat())
                                                .coerceIn(0f, 1f)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = state.isScanning,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (state.scanCount > 0) {
                                        "Buscando libros en el dispositivo... (${state.scanCount} encontrados)"
                                    } else {
                                        "Buscando libros y documentos en el almacenamiento..."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when {
                    // Drilldown: Selected Collection
                    state.selectedCollection != null -> {
                        BookGridView(
                            books = state.books,
                            showBackTile = true,
                            onBackTileClick = { viewModel.clearDrilldown() },
                            onOpenBookDetails = onOpenBookDetails,
                            onOpenReader = onOpenReader,
                            onToggleFavorite = { viewModel.toggleFavorite(it) },
                            onToggleToRead = { viewModel.toggleToRead(it) },
                            onToggleHaveRead = { viewModel.toggleHaveRead(it) },
                            onOpenCollections = { bookForCollections = it },
                            onMoveToTrash = handleMoveToTrash,
                            onEdit = { bookForEditMetadata = it },
                            onRemoveFromReadingNow = handleRemoveFromReadingNow,
                            isCompact = isCompactMode
                        )
                    }
                    // Drilldown: Selected Author
                    state.selectedAuthor != null -> {
                        BookGridView(
                            books = state.books,
                            showBackTile = false,
                            onBackTileClick = {},
                            onOpenBookDetails = onOpenBookDetails,
                            onOpenReader = onOpenReader,
                            onToggleFavorite = { viewModel.toggleFavorite(it) },
                            onToggleToRead = { viewModel.toggleToRead(it) },
                            onToggleHaveRead = { viewModel.toggleHaveRead(it) },
                            onOpenCollections = { bookForCollections = it },
                            onMoveToTrash = handleMoveToTrash,
                            onEdit = { bookForEditMetadata = it },
                            onRemoveFromReadingNow = handleRemoveFromReadingNow,
                            isCompact = isCompactMode
                        )
                    }
                    // Drilldown: Selected Series
                    state.selectedSeries != null -> {
                        BookGridView(
                            books = state.books,
                            showBackTile = false,
                            onBackTileClick = {},
                            onOpenBookDetails = onOpenBookDetails,
                            onOpenReader = onOpenReader,
                            onToggleFavorite = { viewModel.toggleFavorite(it) },
                            onToggleToRead = { viewModel.toggleToRead(it) },
                            onToggleHaveRead = { viewModel.toggleHaveRead(it) },
                            onOpenCollections = { bookForCollections = it },
                            onMoveToTrash = handleMoveToTrash,
                            onEdit = { bookForEditMetadata = it },
                            onRemoveFromReadingNow = handleRemoveFromReadingNow,
                            isCompact = isCompactMode
                        )
                    }
                    // Drilldown: Selected Format
                    state.selectedFormat != null -> {
                        val selectedFormat = checkNotNull(state.selectedFormat)
                        val isIndexedOnly = DocumentFormatCapabilities.isIndexedOnly(selectedFormat.format)
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (isIndexedOnly) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = "Aviso de formato: Los archivos en ${selectedFormat.format} están indexados para organización en la biblioteca, pero Axiom no incluye motor de lectura en esta versión.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            BookGridView(
                                books = state.books,
                                showBackTile = false,
                                onBackTileClick = {},
                                onOpenBookDetails = onOpenBookDetails,
                                onOpenReader = onOpenReader,
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onToggleToRead = { viewModel.toggleToRead(it) },
                                onToggleHaveRead = { viewModel.toggleHaveRead(it) },
                                onOpenCollections = { bookForCollections = it },
                                onMoveToTrash = handleMoveToTrash,
                                onEdit = { bookForEditMetadata = it },
                                onRemoveFromReadingNow = handleRemoveFromReadingNow,
                                isCompact = isCompactMode
                            )
                        }
                    }
                    // Drilldown: Selected Folder
                    state.selectedFolder != null -> {
                        BookGridView(
                            books = state.books,
                            showBackTile = false,
                            onBackTileClick = {},
                            onOpenBookDetails = onOpenBookDetails,
                            onOpenReader = onOpenReader,
                            onToggleFavorite = { viewModel.toggleFavorite(it) },
                            onToggleToRead = { viewModel.toggleToRead(it) },
                            onToggleHaveRead = { viewModel.toggleHaveRead(it) },
                            onOpenCollections = { bookForCollections = it },
                            onMoveToTrash = handleMoveToTrash,
                            onEdit = { bookForEditMetadata = it },
                            onRemoveFromReadingNow = handleRemoveFromReadingNow,
                            isCompact = isCompactMode
                        )
                    }
                    // Authors Section: 3-column compact cells
                    state.currentSection == LibrarySection.AUTHORS -> {
                        AuthorsView(
                            authors = state.authors,
                            onSelectAuthor = { viewModel.selectAuthor(it) }
                        )
                    }
                    // Series Section: 3-column compact cells
                    state.currentSection == LibrarySection.SERIES -> {
                        if (state.series.isEmpty()) {
                            EmptyStateView(
                                icon = Icons.Outlined.AutoStories,
                                title = "Sin series detectadas",
                                message = "Las series de libros encontradas en el almacenamiento del dispositivo se agruparán automáticamente aquí."
                            )
                        } else {
                            SeriesView(
                                series = state.series,
                                onSelectSeries = { viewModel.selectSeries(it) }
                            )
                        }
                    }
                    // Collections Section: 3-column compact cells
                    state.currentSection == LibrarySection.COLLECTIONS -> {
                        CollectionsView(
                            collections = state.collections,
                            onSelectCollection = { viewModel.selectCollection(it) },
                            onCreateNewCollection = { showCreateCollectionDialog = true }
                        )
                    }
                    // Formats Section: 3-column matrix of formats
                    state.currentSection == LibrarySection.FORMATS -> {
                        FormatsView(
                            formats = state.formats,
                            totalFiles = state.allActiveBooks.size,
                            onSelectFormat = { viewModel.selectFormat(it) }
                        )
                    }
                    // Folders Section: Storage explorer
                    state.currentSection == LibrarySection.FOLDERS -> {
                        FoldersView(
                            folders = state.folders,
                            onSelectFolder = { viewModel.selectFolder(it) }
                        )
                    }
                    // Quotes & Notes Section: All annotations across the library
                    state.currentSection == LibrarySection.QUOTES -> {
                        val allQuotesList by viewModel.allQuotes.collectAsState(initial = emptyList())
                        QuotesLibraryView(
                            quotes = allQuotesList,
                            books = state.allActiveBooks.ifEmpty { state.books },
                            searchQuery = state.searchQuery,
                            onOpenQuote = { quote ->
                                viewModel.openQuoteInReader(quote, onOpenReader)
                            },
                            onDeleteQuote = { quote ->
                                viewModel.deleteQuote(quote)
                            },
                            onEditQuote = { quote ->
                                quoteToEdit = quote
                            }
                        )
                    }
                    // Downloads Section: Downloaded books or permission/Z-Lib gateway
                    state.currentSection == LibrarySection.DOWNLOADS -> {
                        if (state.books.isEmpty()) {
                            DownloadsPermissionView(
                                onOpenPermissionSettings = {
                                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    try {
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                        context.startActivity(fallback)
                                    }
                                },
                                onOpenZLibrary = {
                                    viewModel.setSection(LibrarySection.ZLIBRARY)
                                }
                            )
                        } else {
                            BookGridView(
                                books = state.books,
                                showBackTile = false,
                                onBackTileClick = {},
                                onOpenBookDetails = onOpenBookDetails,
                                onOpenReader = onOpenReader,
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onToggleToRead = { viewModel.toggleToRead(it) },
                                onToggleHaveRead = { viewModel.toggleHaveRead(it) },
                                onOpenCollections = { bookForCollections = it },
                                onMoveToTrash = { viewModel.moveToTrash(it) },
                                onEdit = { bookForEditMetadata = it },
                                isCompact = isCompactMode
                            )
                        }
                    }
                    // Z-Library Section: Download books directly
                    state.currentSection == LibrarySection.ZLIBRARY -> {
                        ZLibraryScreen(
                            onOpenBook = { filePath ->
                                scope.launch {
                                    val book = viewModel.getOrScanBookByPath(filePath)
                                    if (book != null) {
                                        onOpenReader(book.id)
                                    }
                                }
                            }
                        )
                    }
                    // OPDS Catalogs Section: Browse personal OPDS feeds
                    state.currentSection == LibrarySection.OPDS_CATALOGS -> {
                        OpdsCatalogScreen(
                            onOpenBook = { filePath ->
                                scope.launch {
                                    val book = viewModel.getOrScanBookByPath(filePath)
                                    if (book != null) {
                                        onOpenReader(book.id)
                                    }
                                }
                            }
                        )
                    }
                    // Academic Papers Section: Search and download papers from arXiv
                    state.currentSection == LibrarySection.ACADEMIC_PAPERS -> {
                        AcademicPapersScreen(
                            onOpenPaper = { filePath ->
                                scope.launch {
                                    val book = viewModel.getOrScanBookByPath(filePath)
                                    if (book != null) {
                                        onOpenReader(book.id)
                                    }
                                }
                            }
                        )
                    }
                    // Reading Journal Section: Letterboxd-style reading diary
                    state.currentSection == LibrarySection.JOURNAL -> {
                        ReadingJournalScreen(
                            books = state.allActiveBooks.ifEmpty { state.books },
                            onOpenBook = { book -> onOpenBookDetails(book.id) },
                            onUpdateReview = { book, rating, review, tags ->
                                viewModel.updateReview(book.id, rating, review, tags)
                            }
                        )
                    }
                    // Comics and Manga Section
                    state.currentSection == LibrarySection.COMICS_MANGA -> {
                        ComicsMangaScreen(
                            onOpenCbz = { filePath ->
                                scope.launch {
                                    android.util.Log.i("ComicsManga", "onOpenCbz requested for: $filePath")
                                    val book = viewModel.getOrScanBookByPath(filePath)
                                        ?: StorageScanner.scanSingleFile(context, File(filePath))
                                    if (book != null) {
                                        android.util.Log.i("ComicsManga", "Opening reader with book ID ${book.id}")
                                        onOpenReader(book.id)
                                    } else {
                                        android.util.Log.e("ComicsManga", "Failed to scan or find book for $filePath")
                                    }
                                }
                            }
                        )
                    }
                    // Reading Stats Section: Dedicated page accessible from drawer
                    state.currentSection == LibrarySection.READING_STATS -> {
                        ReadingStatsScreen()
                    }
                    // Drive Sync Section: Google Drive sync
                    state.currentSection == LibrarySection.DRIVE_SYNC -> {
                        DriveSyncScreen(
                            books = state.allActiveBooks.ifEmpty { state.books },
                            onOpenBook = { book -> onOpenBookDetails(book.id) }
                        )
                    }
                    // Trash Section: Logical trash
                    state.currentSection == LibrarySection.TRASH -> {
                        if (state.trashBooks.isEmpty()) {
                            EmptyStateView(
                                icon = Icons.Outlined.DeleteOutline,
                                title = "La Papelera está vacía",
                                message = "Los libros y documentos que envíes a la Papelera se conservarán aquí antes de su eliminación definitiva."
                            )
                        } else {
                            TrashGridView(
                                books = state.trashBooks,
                                onRestore = { viewModel.restoreFromTrash(it) }
                            )
                        }
                    }
                    // Standard book list sections: Leyendo ahora, Libros y documentos, Favoritos, Para leer, Leídos
                    else -> {
                        if (state.books.isEmpty()) {
                            if (state.searchQuery.isNotBlank()) {
                                EmptyStateView(
                                    icon = Icons.Outlined.SearchOff,
                                    title = "Sin resultados",
                                    message = "No se encontraron libros ni documentos que coincidan con \"${state.searchQuery}\".",
                                    actionLabel = "Limpiar búsqueda",
                                    onAction = { viewModel.setSearchQuery("") }
                                )
                            } else {
                                when (state.currentSection) {
                                    LibrarySection.FAVORITES -> EmptyStateView(
                                        icon = Icons.Outlined.Star,
                                        title = "Sin favoritos aún",
                                        message = "Los libros y documentos que marque con una estrella como Favoritos aparecerán aquí."
                                    )
                                    LibrarySection.TO_READ -> EmptyStateView(
                                        icon = Icons.Outlined.BookmarkBorder,
                                        title = "Lista de lectura vacía",
                                        message = "Los libros y documentos que agregue a 'Para leer' estarán guardados aquí."
                                    )
                                    LibrarySection.HAVE_READ -> EmptyStateView(
                                        icon = Icons.Outlined.CheckCircleOutline,
                                        title = "Sin libros leídos",
                                        message = "Los libros y documentos que marque como leídos aparecerán aquí con su registro."
                                    )
                                    LibrarySection.READING_NOW -> EmptyStateView(
                                        icon = Icons.AutoMirrored.Filled.MenuBook,
                                        title = "No estás leyendo ningún libro",
                                        message = "Los libros y documentos que comience a leer aparecerán aquí para retomar su lectura rápidamente.",
                                        actionLabel = "Ver todos los documentos",
                                        onAction = { viewModel.setSection(LibrarySection.ALL_DOCUMENTS) }
                                    )
                                    else -> EmptyStateView(
                                        icon = Icons.Outlined.FolderOpen,
                                        title = "No se encontraron libros",
                                        message = "No se encontraron documentos compatibles en el almacenamiento local. Puedes iniciar una búsqueda o importar archivos.",
                                        actionLabel = "Examinar archivos",
                                        onAction = { viewModel.triggerScan() }
                                    )
                                }
                            }
                        } else {
                            BookGridView(
                                books = state.books,
                                showBackTile = false,
                                onBackTileClick = {},
                                onOpenBookDetails = onOpenBookDetails,
                                onOpenReader = onOpenReader,
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onToggleToRead = { viewModel.toggleToRead(it) },
                                onToggleHaveRead = { viewModel.toggleHaveRead(it) },
                                onOpenCollections = { bookForCollections = it },
                                onMoveToTrash = handleMoveToTrash,
                                onEdit = { bookForEditMetadata = it },
                                onRemoveFromReadingNow = handleRemoveFromReadingNow,
                                isCompact = isCompactMode
                            )
                    }
                    }
                }
            }
        }
    }

    // Dialogs
    if (showSortDialog) {
        SortDialog(
            currentSort = state.sortCriterion,
            onSelectSort = { viewModel.setSortCriterion(it) },
            onDismiss = { showSortDialog = false }
        )
    }

    if (showFilterDialog) {
        FilterDialog(
            initialCriteria = state.filterCriteria,
            totalResults = state.books.size,
            onApply = { viewModel.setFilterCriteria(it) },
            onDismiss = { showFilterDialog = false }
        )
    }

    if (showFeedbackDialog) {
        SendFeedbackDialog(onDismiss = { showFeedbackDialog = false })
    }

    if (showClearListDialog) {
        ConfirmationDialog(
            title = "Limpiar la lista",
            message = "¿Está seguro de que desea limpiar la lista de '${state.currentSection.title}'? Esta acción solo restablece la lista lógica, no se eliminarán archivos físicos.",
            confirmText = "LIMPIAR",
            onConfirm = { viewModel.clearCurrentSection() },
            onDismiss = { showClearListDialog = false }
        )
    }

    if (showEmptyTrashDialog) {
        ConfirmationDialog(
            title = "Vaciar Papelera",
            message = "¿Está seguro de que desea vaciar la Papelera? Se eliminarán las entradas lógicas.",
            confirmText = "VACIAR",
            isDestructive = true,
            onConfirm = { viewModel.emptyTrash() },
            onDismiss = { showEmptyTrashDialog = false }
        )
    }

    if (showCreateCollectionDialog) {
        CreateCollectionDialog(
            onConfirm = { name -> viewModel.createCollection(name) },
            onDismiss = { showCreateCollectionDialog = false }
        )
    }

    bookToMoveToTrash?.let { targetBook ->
        AlertDialog(
            onDismissRequest = { bookToMoveToTrash = null },
            title = { Text("¿Enviar a la Papelera?") },
            text = { Text("¿Desea enviar \"${targetBook.title}\" a la Papelera? Podrá restaurarlo en cualquier momento desde la sección Papelera.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.moveToTrash(targetBook.id)
                        bookToMoveToTrash = null
                        scope.launch {
                            val res = snackbarHostState.showSnackbar(
                                message = "\"${targetBook.title}\" enviado a la Papelera",
                                actionLabel = "DESHACER",
                                duration = SnackbarDuration.Short
                            )
                            if (res == SnackbarResult.ActionPerformed) {
                                viewModel.restoreFromTrash(targetBook.id)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Enviar a la Papelera")
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToMoveToTrash = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    bookForCollections?.let { b ->
        var currentBookCollectionIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
        LaunchedEffect(b.id) {
            val colls = viewModel.getCollectionsForBook(b.id)
            currentBookCollectionIds = colls.map { it.id }.toSet()
        }
        BookCollectionsDialog(
            bookTitle = b.title,
            allCollections = state.collections,
            initialSelectedIds = currentBookCollectionIds,
            onSave = { selectedIds ->
                viewModel.setBookCollections(b.id, selectedIds)
                bookForCollections = null
            },
            onCreateNewCollection = {
                showCreateCollectionDialog = true
            },
            onDismiss = { bookForCollections = null }
        )
    }

    bookForEditMetadata?.let { b ->
        MetadataEditorDialog(
            book = b,
            onSave = { title, author, series, annotation, language ->
                scope.launch {
                    // Update in repo
                    bookForEditMetadata = null
                }
            },
            onDismiss = { bookForEditMetadata = null }
        )
    }

    quoteToEdit?.let { q ->
        AddQuoteDialog(
            initialText = q.text,
            page = q.page,
            quoteToEdit = q,
            onDismiss = { quoteToEdit = null },
            onSaveQuote = { updatedText, updatedNote, updatedColor ->
                viewModel.updateQuote(q.copy(text = updatedText, note = updatedNote, colorHex = updatedColor))
                quoteToEdit = null
            }
        )
    }
}

@Composable
private fun DrawerNavEntry(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        icon = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        selected = selected,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .heightIn(min = 48.dp)
    )
}

@Composable
private fun BookGridView(
    books: List<Book>,
    showBackTile: Boolean,
    onBackTileClick: () -> Unit,
    onOpenBookDetails: (Long) -> Unit,
    onOpenReader: (Long) -> Unit = {},
    onToggleFavorite: (Long) -> Unit,
    onToggleToRead: (Long) -> Unit,
    onToggleHaveRead: (Long) -> Unit,
    onOpenCollections: (Book) -> Unit,
    onMoveToTrash: (Long) -> Unit,
    onEdit: (Book) -> Unit,
    onRemoveFromReadingNow: ((Book) -> Unit)? = null,
    isCompact: Boolean = false
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp
    val isTablet = screenWidth >= 600
    val isWide = screenWidth >= 840

    val columns = if (isCompact) {
        if (isTablet) GridCells.Adaptive(minSize = 360.dp) else GridCells.Fixed(1)
    } else {
        GridCells.Adaptive(minSize = if (screenWidth < 360) 240.dp else 275.dp)
    }
    val horizontalPadding = when {
        isWide -> 24.dp
        isTablet -> 16.dp
        else -> 12.dp
    }
    val contentPadding = if (isCompact) {
        PaddingValues(horizontal = horizontalPadding, vertical = 8.dp)
    } else {
        PaddingValues(horizontal = horizontalPadding, vertical = 12.dp)
    }
    val verticalSpacing = if (isCompact) 8.dp else 12.dp

    LazyVerticalGrid(
        columns = columns,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        modifier = Modifier.fillMaxSize()
    ) {
        if (showBackTile) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isCompact) 56.dp else 100.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onBackTileClick() },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Volver",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        items(books, key = { it.id }) { book ->
            ReadEraBookCard(
                book = book,
                onClick = { onOpenBookDetails(book.id) },
                onOpenReader = { onOpenReader(book.id) },
                onToggleFavorite = { onToggleFavorite(book.id) },
                onToggleToRead = { onToggleToRead(book.id) },
                onToggleHaveRead = { onToggleHaveRead(book.id) },
                onOpenCollections = { onOpenCollections(book) },
                onMoveToTrash = { onMoveToTrash(book.id) },
                onEdit = { onEdit(book) },
                onRemoveFromReadingNow = if (onRemoveFromReadingNow != null) { { onRemoveFromReadingNow(book) } } else null,
                isCompact = isCompact
            )
        }
    }
}

@Composable
private fun BookActions(
    book: Book,
    onToggleFavorite: () -> Unit,
    onToggleToRead: () -> Unit,
    onToggleHaveRead: () -> Unit,
    onOpenCollections: () -> Unit,
    onMoveToTrash: () -> Unit,
    onEdit: () -> Unit,
    onRemoveFromReadingNow: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Favorito: acción inmediata, siempre visible (área táctil >=48dp)
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (book.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                contentDescription = if (book.isFavorite) "Quitar de Favoritos" else "Agregar a Favoritos",
                tint = if (book.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        // Desbordamiento: menú con etiquetas en español para todas las demás acciones (>=48dp)
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Más opciones de ${book.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                // 1. Para leer
                DropdownMenuItem(
                    text = { Text(if (book.isToRead) "Quitar de Para leer" else "Para leer") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (book.isToRead) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = null,
                            tint = if (book.isToRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {
                        showMenu = false
                        onToggleToRead()
                    }
                )

                // 2. Leídos
                DropdownMenuItem(
                    text = { Text(if (book.isHaveRead) "Marcar como no leído" else "Leídos") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (book.isHaveRead) Icons.Default.CheckCircle else Icons.Outlined.CheckCircleOutline,
                            contentDescription = null,
                            tint = if (book.isHaveRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {
                        showMenu = false
                        onToggleHaveRead()
                    }
                )

                // 3. Colecciones
                DropdownMenuItem(
                    text = { Text("Colecciones") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.FolderSpecial,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {
                        showMenu = false
                        onOpenCollections()
                    }
                )

                // 4. Compartir
                DropdownMenuItem(
                    text = { Text("Compartir") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {
                        showMenu = false
                        val file = File(book.filePath)
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
                            context.startActivity(Intent.createChooser(shareIntent, "Compartir ${book.title}"))
                        }
                    }
                )

                // 5. Quitar de Leyendo ahora (si aplica)
                if (book.status == BookStatus.READING && onRemoveFromReadingNow != null) {
                    DropdownMenuItem(
                        text = { Text("Quitar de Leyendo ahora") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.BookmarkRemove,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            showMenu = false
                            onRemoveFromReadingNow()
                        }
                    )
                }

                // 6. Editar información
                DropdownMenuItem(
                    text = { Text("Editar información") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {
                        showMenu = false
                        onEdit()
                    }
                )

                HorizontalDivider()

                // 7. Enviar a la Papelera (destructivo, conserva confirmación)
                DropdownMenuItem(
                    text = { Text("Enviar a la Papelera", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        showMenu = false
                        onMoveToTrash()
                    }
                )
            }
        }
    }
}

@Composable
private fun ReadEraBookCard(
    book: Book,
    onClick: () -> Unit,
    onOpenReader: () -> Unit = {},
    onToggleFavorite: () -> Unit,
    onToggleToRead: () -> Unit,
    onToggleHaveRead: () -> Unit,
    onOpenCollections: () -> Unit,
    onMoveToTrash: () -> Unit,
    onEdit: () -> Unit,
    onRemoveFromReadingNow: (() -> Unit)? = null,
    isCompact: Boolean = false
) {
    val isPhone = LocalConfiguration.current.screenWidthDp < 600
    val coverWidth = if (isPhone) 76.dp else 84.dp
    val coverHeight = if (isPhone) 110.dp else 122.dp
    val formattedSize = remember(book.fileSize) {
        val mb = book.fileSize / (1024.0 * 1024.0)
        if (mb >= 1.0) "%.1f MB".format(mb) else "${book.fileSize / 1024} KB"
    }
    val isReadable = DocumentFormatCapabilities.isReadable(book.format)

    if (isCompact) {
        // Modo Lista Compacta: fila horizontal densa, portada 44x64dp, sin desbordamientos
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onClick() },
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Portada pequeña (44x64dp)
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(64.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { onOpenReader() },
                    contentAlignment = Alignment.Center
                ) {
                    if (!book.coverPath.isNullOrBlank() && File(book.coverPath).exists()) {
                        AsyncImage(
                            model = File(book.coverPath),
                            contentDescription = book.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Book,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = book.format.uppercase(),
                                 style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                 fontWeight = FontWeight.Bold,
                                 color = MaterialTheme.colorScheme.primary
                             )
                         }
                     }
                 }

                Spacer(modifier = Modifier.width(10.dp))

                // Metadatos compactos: Título, Autor, Formato/tamaño, Progreso
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = if (isReadable) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp),
                            border = if (!isReadable) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
                        ) {
                            Text(
                                text = DocumentFormatCapabilities.badgeText(book.format),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isReadable) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                        Text(
                            text = formattedSize,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DocumentCategoryButton(book)
                    }
                    if (book.progressPercent > 0f) {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { (book.progressPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                        )
                    }
                }

                // Acciones reutilizadas (Favorito + Más opciones)
                BookActions(
                    book = book,
                    onToggleFavorite = onToggleFavorite,
                    onToggleToRead = onToggleToRead,
                    onToggleHaveRead = onToggleHaveRead,
                    onOpenCollections = onOpenCollections,
                    onMoveToTrash = onMoveToTrash,
                    onEdit = onEdit,
                    onRemoveFromReadingNow = onRemoveFromReadingNow
                )
            }
        }
    } else {
        // Modo Cuadrícula: tarjeta densa responsiva con barra inferior simplificada (solo 2 iconos)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onClick() },
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    // Portada de libro (clic directo para abrir lector)
                    Box(
                        modifier = Modifier
                            .width(coverWidth)
                            .height(coverHeight)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { onOpenReader() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (!book.coverPath.isNullOrBlank() && File(book.coverPath).exists()) {
                            AsyncImage(
                                model = File(book.coverPath),
                                contentDescription = book.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Book,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = book.format.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Metadatos: Título, Autor, Formato/tamaño, Categoría, Progreso
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(coverHeight),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = book.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = book.author,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    color = if (isReadable) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(4.dp),
                                    border = if (!isReadable) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
                                ) {
                                    Text(
                                        text = DocumentFormatCapabilities.badgeText(book.format),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isReadable) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = formattedSize,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!isReadable) {
                                Text(
                                    text = "Formato indexado",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            DocumentCategoryButton(book)
                            if (book.progressPercent > 0f) {
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { (book.progressPercent / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Pie de tarjeta simplificado: solo Favorito + Desbordamiento (ambos >=48dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BookActions(
                        book = book,
                        onToggleFavorite = onToggleFavorite,
                        onToggleToRead = onToggleToRead,
                        onToggleHaveRead = onToggleHaveRead,
                        onOpenCollections = onOpenCollections,
                        onMoveToTrash = onMoveToTrash,
                        onEdit = onEdit,
                        onRemoveFromReadingNow = onRemoveFromReadingNow
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthorsView(
    authors: List<AuthorItem>,
    onSelectAuthor: (AuthorItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(authors) { author ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectAuthor(author) },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${author.displayName} — ${author.bookCount}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesView(
    series: List<SeriesItem>,
    onSelectSeries: (SeriesItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(series) { s ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectSeries(s) },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${s.name} — ${s.bookCount}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionsView(
    collections: List<org.readera.openreadera.data.db.CollectionWithCount>,
    onSelectCollection: (org.readera.openreadera.data.db.CollectionWithCount) -> Unit,
    onCreateNewCollection: () -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(collections) { coll ->
            val displayName = when (coll.name) {
                "Novela-grafica" -> "Novela gráfica"
                "Ciencia-ficcion" -> "Ciencia ficción"
                else -> coll.name
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectCollection(coll) },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "$displayName — ${coll.bookCount}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCreateNewCollection() },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Crear nueva colección",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun FormatsView(
    formats: List<FormatItem>,
    totalFiles: Int,
    onSelectFormat: (FormatItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Formatos en la biblioteca",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Total de archivos detectados: $totalFiles. Los formatos con lector integrado se abren directamente; los formatos indexados se catalogan para organización en biblioteca.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(formats) { fmt ->
                val isReadable = DocumentFormatCapabilities.isReadable(fmt.format)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = fmt.bookCount > 0) { onSelectFormat(fmt) },
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (fmt.bookCount > 0) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerLowest
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (fmt.bookCount > 0) 0.8f else 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = fmt.format,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (fmt.bookCount > 0) FontWeight.Bold else FontWeight.Normal,
                                color = if (fmt.bookCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                            )
                            Surface(
                                shape = CircleShape,
                                color = if (fmt.bookCount > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = "${fmt.bookCount}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (fmt.bookCount > 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isReadable) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = if (isReadable) "Lector integrado" else "Solo indexado",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (isReadable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoldersView(
    folders: List<FolderItem>,
    onSelectFolder: (FolderItem) -> Unit
) {
    val isPhone = LocalConfiguration.current.screenWidthDp < 600
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Carpetas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Pantalla de Inicio", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text("Almacenamiento: /storage/emulated/0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Las carpetas compatibles se detectan automáticamente al actualizar la biblioteca.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 240.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(folders) { folder ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectFolder(folder) },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = folder.folderName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${folder.bookCount} documentos",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsPermissionView(
    onOpenPermissionSettings: () -> Unit,
    onOpenZLibrary: (() -> Unit)? = null
) {
    val isPhone = LocalConfiguration.current.screenWidthDp < 600
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Explora las descargas locales o busca y descarga libros directamente desde Z-Library.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(24.dp))
            if (isPhone) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onOpenPermissionSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("CONFIGURACIÓN DE ARCHIVOS", fontWeight = FontWeight.Bold)
                    }
                    if (onOpenZLibrary != null) {
                        FilledTonalButton(
                            onClick = onOpenZLibrary,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("BUSCAR EN Z-LIBRARY", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onOpenPermissionSettings) {
                        Text("CONFIGURACIÓN DE ARCHIVOS", fontWeight = FontWeight.Bold)
                    }
                    if (onOpenZLibrary != null) {
                        FilledTonalButton(onClick = onOpenZLibrary) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("BUSCAR EN Z-LIBRARY", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashGridView(
    books: List<Book>,
    onRestore: (Long) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 270.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(books, key = { it.id }) { book ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onRestore(book.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restaurar")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStateView(
    icon: ImageVector = Icons.AutoMirrored.Filled.MenuBook,
    title: String? = null,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.widthIn(max = 440.dp)
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            if (title != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(20.dp))
                FilledTonalButton(
                    onClick = onAction,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(actionLabel, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun QuotesLibraryView(
    quotes: List<Quote>,
    books: List<Book>,
    searchQuery: String,
    onOpenQuote: (Quote) -> Unit,
    onDeleteQuote: (Quote) -> Unit,
    onEditQuote: (Quote) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var filterWithNotesOnly by remember { mutableStateOf(false) }
    var selectedColorFilter by remember { mutableStateOf<String?>(null) }

    val booksById = remember(books) { books.associateBy { it.id } }

    val filteredQuotes = remember(quotes, searchQuery, filterWithNotesOnly, selectedColorFilter, booksById) {
        quotes.filter { q ->
            val book = booksById[q.bookId]
            val matchesSearch = if (searchQuery.isBlank()) true else {
                val qLower = searchQuery.trim().lowercase()
                q.text.lowercase().contains(qLower) ||
                q.note.lowercase().contains(qLower) ||
                (book?.title?.lowercase()?.contains(qLower) == true) ||
                (book?.author?.lowercase()?.contains(qLower) == true)
            }
            val matchesNotesOnly = if (filterWithNotesOnly) q.note.isNotBlank() else true
            val matchesColor = if (selectedColorFilter != null) {
                q.colorHex.equals(selectedColorFilter, ignoreCase = true)
            } else true

            matchesSearch && matchesNotesOnly && matchesColor
        }
    }

    if (quotes.isEmpty()) {
        EmptyStateView(
            icon = Icons.Outlined.FormatQuote,
            title = "Sin citas ni notas aún",
            message = "Al leer cualquier libro o documento, pulsa el botón de citas o mantén pulsado el texto para guardar tus reflexiones y fragmentos destacados aquí."
        )
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = !filterWithNotesOnly && selectedColorFilter == null,
                    onClick = {
                        filterWithNotesOnly = false
                        selectedColorFilter = null
                    },
                    label = { Text("Todas (${quotes.size})") }
                )
                FilterChip(
                    selected = filterWithNotesOnly,
                    onClick = { filterWithNotesOnly = !filterWithNotesOnly },
                    label = { Text("Solo notas") },
                    leadingIcon = {
                        Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
            }

            if (filteredQuotes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No se encontraron citas con los filtros aplicados.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredQuotes, key = { it.id }) { quote ->
                        val book = booksById[quote.bookId]
                        QuoteLibraryCard(
                            quote = quote,
                            book = book,
                            onOpen = { onOpenQuote(quote) },
                            onEdit = { onEditQuote(quote) },
                            onDelete = { onDeleteQuote(quote) },
                            onCopy = {
                                val shareText = "“${quote.text}”" + (if (quote.note.isNotBlank()) "\n\nNota: ${quote.note}" else "") + (book?.let { "\n— ${it.title}, pág. ${quote.page + 1}" } ?: "")
                                clipboardManager.setText(AnnotatedString(shareText))
                                android.widget.Toast.makeText(context, "Cita copiada al portapapeles", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onShare = {
                                val shareText = "“${quote.text}”" + (if (quote.note.isNotBlank()) "\n\nNota: ${quote.note}" else "") + (book?.let { "\n— ${it.title}, pág. ${quote.page + 1}" } ?: "")
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                }
                                context.startActivity(Intent.createChooser(intent, "Compartir cita"))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteLibraryCard(
    quote: Quote,
    book: Book?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    val quoteColor = remember(quote.colorHex) {
        try {
            Color(android.graphics.Color.parseColor(quote.colorHex))
        } catch (_: Exception) {
            Color(0xFFFFE082)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // Color accent bar on the left
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(quoteColor)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(14.dp)
            ) {
                // Book Title & Page chip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Book,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = book?.title ?: "Libro #${quote.bookId}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = "Pág. ${quote.page + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (book?.author?.isNotBlank() == true && book.author != "Autor desconocido") {
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Highlighted quote text
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = quoteColor.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "“${quote.text}”",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                // Personal Note (if any)
                if (quote.note.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.EditNote,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = quote.note,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom actions row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dateFormatted = remember(quote.createdAt) {
                        java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(quote.createdAt))
                    }
                    Text(
                        text = dateFormatted,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Share, contentDescription = "Compartir", modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "Editar", modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentIntakeMenuItems(
    enabled: Boolean,
    onScanDocument: () -> Unit,
    onImagesToPdf: () -> Unit,
    onMergePdfs: () -> Unit
) {
    DropdownMenuItem(
        text = { Text("Escanear documento") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.DocumentScanner,
                contentDescription = null
            )
        },
        enabled = enabled,
        onClick = onScanDocument
    )
    DropdownMenuItem(
        text = { Text("Crear PDF desde imágenes") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null
            )
        },
        enabled = enabled,
        onClick = onImagesToPdf
    )
    DropdownMenuItem(
        text = { Text("Combinar PDFs") },
        enabled = enabled,
        onClick = onMergePdfs
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun uniqueScanOutputFile(directory: File, fileName: String): File {
    if (!directory.isDirectory) {
        directory.mkdirs()
    }
    val baseName = fileName.substringBeforeLast('.', fileName).ifBlank { "scan" }
    val extension = fileName.substringAfterLast('.', "pdf").ifBlank { "pdf" }
    var candidate = File(directory, "$baseName.$extension")
    var suffix = 2
    while (candidate.exists()) {
        candidate = File(directory, "$baseName-$suffix.$extension")
        suffix++
    }
    return candidate
}
