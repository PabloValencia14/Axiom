package org.readera.openreadera.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readera.openreadera.data.db.CollectionWithCount
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.BookStatus
import org.readera.openreadera.data.model.Bookmark
import org.readera.openreadera.data.model.Collection as BookCollection
import org.readera.openreadera.data.model.Quote
import org.readera.openreadera.data.repository.BookRepository
import org.readera.openreadera.ui.library.components.*
import org.readera.openreadera.ui.reader.components.BookmarkEditDialog
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailsScreen(
    bookId: Long,
    repository: BookRepository,
    onBack: () -> Unit,
    onRead: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bookState by repository.getBookById(bookId).collectAsState(initial = null)
    val bookmarksState by repository.getBookmarksForBook(bookId).collectAsState(initial = emptyList<Bookmark>())
    val quotesState by repository.getQuotesForBook(bookId).collectAsState(initial = emptyList<Quote>())
    val allCollectionsWithCount by repository.getCollectionsWithCount().collectAsState(initial = emptyList<CollectionWithCount>())
    val bookCollections: List<BookCollection> by repository.getCollectionsForBook(bookId).collectAsState(initial = emptyList<BookCollection>())

    var showMetadataEditor by remember { mutableStateOf(false) }
    var showReviewDialog by remember { mutableStateOf(false) }
    var showCollectionsDialog by remember { mutableStateOf(false) }
    var showCreateCollectionDialog by remember { mutableStateOf(false) }
    var bookmarkToEdit by remember { mutableStateOf<Bookmark?>(null) }

    val book = bookState
    if (book == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri == null) {
            scope.launch { snackbarHostState.showSnackbar("Exportación cancelada") }
        } else {
            scope.launch {
                val succeeded = runCatching {
                    withContext(Dispatchers.IO) {
                        val output = requireNotNull(context.contentResolver.openOutputStream(uri)) {
                            "No se pudo abrir el documento"
                        }
                        OutputStreamWriter(output, Charsets.UTF_8).use {
                            it.write(formatBookAnnotationsMarkdown(book, quotesState, bookmarksState))
                        }
                    }
                }.isSuccess
                snackbarHostState.showSnackbar(
                    if (succeeded) "Exportación completada" else "No se pudo exportar el documento"
                )
            }
        }
    }

    fun exportFileName(title: String): String {
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.')
        return "${safeTitle.ifBlank { "book" }}.md"
    }

    val formattedSize = remember(book.fileSize) {
        val mb = book.fileSize / (1024.0 * 1024.0)
        if (mb >= 1.0) "%.1f MB".format(mb) else "${book.fileSize / 1024} KB"
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Acerca del documento",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    Button(
                        onClick = { onRead(book.id) },
                        modifier = Modifier.padding(end = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.MenuBook, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Leer", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        val isPhone = LocalConfiguration.current.screenWidthDp < 600
        val coverWidth = if (isPhone) 100.dp else 150.dp
        val coverHeight = if (isPhone) 145.dp else 210.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = if (isPhone) 12.dp else 24.dp, vertical = if (isPhone) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main Header Card: Cover + Primary info + Action Row
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(if (isPhone) 12.dp else 20.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            // Cover image
                            Box(
                                modifier = Modifier
                                    .width(coverWidth)
                                    .height(coverHeight)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
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
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Book,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(if (isPhone) 36.dp else 48.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = book.format.uppercase(),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(if (isPhone) 12.dp else 24.dp))

                            // Metadata details
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = book.title,
                                    style = if (isPhone) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 3,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Autor: ${book.author}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                if (bookCollections.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Colecciones: ${bookCollections.joinToString { it.name }}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                                if (!book.language.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Idioma: ${book.language}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${book.format.uppercase()}, $formattedSize, Archivos: ${book.fileCount}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                if (book.progressPercent > 0f) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        LinearProgressIndicator(
                                            progress = { (book.progressPercent / 100f).coerceIn(0f, 1f) },
                                            modifier = Modifier
                                                .fillMaxWidth(if (isPhone) 0.55f else 0.4f)
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${book.progressPercent.toInt()}% (Pág. ${book.currentPage + 1}/${book.totalPages})",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                if (book.lastOpened > 0L) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Última lectura: ${dateFormat.format(Date(book.lastOpened))}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))

                        // Quick Actions Row
                        val btnModifier = if (isPhone) Modifier.size(36.dp) else Modifier
                        val iconModifier = if (isPhone) Modifier.size(20.dp) else Modifier
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Favoritos
                            IconButton(onClick = { scope.launch { repository.toggleFavorite(book.id) } }, modifier = btnModifier) {
                                Icon(
                                    imageVector = if (book.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                                    contentDescription = "Favoritos",
                                    tint = if (book.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = iconModifier
                                )
                            }
                            // Para Leer
                            IconButton(onClick = { scope.launch { repository.toggleToRead(book.id) } }, modifier = btnModifier) {
                                Icon(
                                    imageVector = if (book.isToRead) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                                    contentDescription = "Para leer",
                                    tint = if (book.isToRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = iconModifier
                                )
                            }
                            // Leídos
                            IconButton(onClick = { scope.launch { repository.toggleHaveRead(book.id) } }, modifier = btnModifier) {
                                Icon(
                                    imageVector = if (book.isHaveRead) Icons.Default.CheckCircle else Icons.Outlined.CheckCircleOutline,
                                    contentDescription = "Leídos",
                                    tint = if (book.isHaveRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = iconModifier
                                )
                            }
                            // Quitar/Añadir a Leyendo ahora
                            if (book.status == BookStatus.READING) {
                                IconButton(onClick = { scope.launch { repository.removeFromReadingNow(book.id) } }, modifier = btnModifier) {
                                    Icon(
                                        imageVector = Icons.Default.BookmarkRemove,
                                        contentDescription = "Quitar de Leyendo ahora",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = iconModifier
                                    )
                                }
                            } else {
                                IconButton(onClick = { scope.launch { repository.markAsReading(book.id) } }, modifier = btnModifier) {
                                    Icon(
                                        imageVector = Icons.Default.BookmarkAdd,
                                        contentDescription = "Añadir a Leyendo ahora",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = iconModifier
                                    )
                                }
                            }
                            // Colecciones
                            if (isPhone) {
                                IconButton(onClick = { showCollectionsDialog = true }, modifier = btnModifier) {
                                    BadgedBox(badge = {
                                        if (bookCollections.isNotEmpty()) {
                                            Badge { Text("${bookCollections.size}") }
                                        }
                                    }) {
                                        Icon(
                                            Icons.Default.FolderSpecial,
                                            contentDescription = "Colecciones",
                                            modifier = iconModifier,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                TextButton(onClick = { showCollectionsDialog = true }) {
                                    Icon(Icons.Default.FolderSpecial, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Colecciones (${bookCollections.size})")
                                }
                            }
                            // Compartir
                            IconButton(
                                onClick = {
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
                                },
                                modifier = btnModifier
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Compartir", modifier = iconModifier)
                            }
                            // Sync the complete library backup, not only this book file.
                            val detailsSyncManager = remember { org.readera.openreadera.sync.GoogleDriveSyncManager(context) }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        if (!detailsSyncManager.isDriveConnected()) {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Conecta Google Drive desde la pantalla de sincronización",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            detailsSyncManager.performSync()
                                                .onSuccess {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Copia completa sincronizada (${it.booksSynced} libros)",
                                                        android.widget.Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                                .onFailure {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Error al sincronizar: ${it.localizedMessage}",
                                                        android.widget.Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                        }
                                    }
                                },
                                modifier = btnModifier
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudSync,
                                    contentDescription = "Sincronizar toda la biblioteca",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = iconModifier
                                )
                            }
                            // Enviar a la papelera
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        repository.moveToTrash(book.id)
                                        onBack()
                                    }
                                },
                                modifier = btnModifier
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Papelera", modifier = iconModifier)
                            }
                            // Editar
                            IconButton(onClick = { showMetadataEditor = true }, modifier = btnModifier) {
                                Icon(Icons.Default.Edit, contentDescription = "Editar", modifier = iconModifier)
                            }
                        }
                    }
                }
            }

            // Description / Synopsis section
            if (!book.description.isNullOrBlank()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Descripción",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = book.description ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Annotation section
            if (!book.annotation.isNullOrBlank()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Anotación",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = book.annotation ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // File Technical Details Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Información del archivo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        DetailRow(label = "Identificador de archivo", value = "#${book.id}")
                        if (!book.sha1.isNullOrBlank()) {
                            DetailRow(label = "SHA-1", value = book.sha1)
                        }
                        DetailRow(label = "Ruta física", value = book.filePath)
                        if (!book.series.isNullOrBlank()) {
                            DetailRow(label = "Serie", value = book.series)
                        }
                        if (!book.genre.isNullOrBlank()) {
                            DetailRow(label = "Género", value = book.genre)
                        }
                        DetailRow(label = "Fecha de incorporación", value = dateFormat.format(Date(book.dateAdded)))
                    }
                }
            }

            // Review section ("Mi reseña" - Letterboxd style)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Mi reseña",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = { showReviewDialog = true }) {
                                Icon(Icons.Default.StarRate, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (book.rating != null && book.rating > 0f) "Editar crítica" else "Evaluar")
                            }
                        }
                        if (book.rating != null && book.rating > 0f) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val r = book.rating ?: 0f
                                for (i in 1..5) {
                                    val isFull = r >= i
                                    val isHalf = r >= i - 0.5f && r < i
                                    Icon(
                                        imageVector = if (isFull) Icons.Default.Star else if (isHalf) Icons.Default.StarHalf else Icons.Default.StarBorder,
                                        contentDescription = null,
                                        tint = Color(0xFFFFB300),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = String.format(Locale.US, "%.1f / 5.0", r),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFFB300)
                                )
                            }
                            book.reviewTags?.let { tags ->
                                Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    tags.split(",").forEach { tag ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = tag.trim(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            if (!book.review.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "“${book.review}”",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "Aún no ha valorado este documento.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(8.dp))

                        // Social community review links
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Comunidad:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            FilledTonalButton(
                                onClick = {
                                    val enc = java.net.URLEncoder.encode(book.title, "UTF-8")
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://hardcover.app/search?q=$enc"))
                                    context.startActivity(intent)
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("Hardcover", style = MaterialTheme.typography.labelSmall)
                            }
                            FilledTonalButton(
                                onClick = {
                                    val enc = java.net.URLEncoder.encode("${book.title} ${book.author}", "UTF-8")
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.goodreads.com/search?q=$enc"))
                                    context.startActivity(intent)
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("Goodreads", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Bookmarks / Señaladores section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Señaladores (${bookmarksState.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (bookmarksState.isEmpty()) {
                            Text(
                                text = "Para agregar un señalador, golpee la esquina superior derecha mientras lee.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            bookmarksState.forEach { bm ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { bookmarkToEdit = bm }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = bm.title, fontWeight = FontWeight.SemiBold)
                                        if (bm.snippet.isNotBlank()) {
                                            Text(
                                                text = bm.snippet,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Text(
                                        text = "Pág. ${bm.page + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    IconButton(onClick = { bookmarkToEdit = bm }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Editar señalador")
                                    }
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                }
            }

            // Quotes and notes section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Citas y notas (${quotesState.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedButton(onClick = { exportLauncher.launch(exportFileName(book.title)) }) {
                            Icon(Icons.Default.FileDownload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Exportar citas y marcadores a Markdown")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (quotesState.isEmpty()) {
                            Text(
                                text = "Todas las citas agregadas estarán aquí.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            quotesState.forEach { q ->
                                val quoteColor = try {
                                    Color(android.graphics.Color.parseColor(q.colorHex))
                                } catch (_: Exception) {
                                    MaterialTheme.colorScheme.primary
                                }
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = quoteColor.copy(alpha = 0.15f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Página ${q.page + 1}",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = quoteColor
                                            )
                                            IconButton(
                                                onClick = { scope.launch { repository.deleteQuote(q) } },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "Eliminar", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        Text(text = "“${q.text}”", style = MaterialTheme.typography.bodyMedium)
                                        if (q.note.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "Nota: ${q.note}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (showMetadataEditor) {
        MetadataEditorDialog(
            book = book,
            onSave = { title, author, series, annotation, language ->
                scope.launch {
                    repository.updateMetadata(book.id, title, author, series, annotation, language)
                }
            },
            onDismiss = { showMetadataEditor = false }
        )
    }

    if (showReviewDialog) {
        org.readera.openreadera.ratings.BookReviewDialog(
            book = book,
            onSaveReview = { rating, review, tags ->
                scope.launch {
                    repository.updateReview(book.id, rating, review, System.currentTimeMillis(), tags)
                }
            },
            onDismiss = { showReviewDialog = false }
        )
    }

    if (showCollectionsDialog) {
        BookCollectionsDialog(
            bookTitle = book.title,
            allCollections = allCollectionsWithCount,
            initialSelectedIds = bookCollections.map { it.id }.toSet(),
            onSave = { selectedIds ->
                scope.launch {
                    repository.setBookCollections(book.id, selectedIds)
                }
            },
            onCreateNewCollection = {
                showCreateCollectionDialog = true
            },
            onDismiss = { showCollectionsDialog = false }
        )
    }

    if (showCreateCollectionDialog) {
        CreateCollectionDialog(
            onConfirm = { name ->
                scope.launch {
                    repository.addCollection(name)
                }
            },
            onDismiss = { showCreateCollectionDialog = false }
        )
    }

    bookmarkToEdit?.let { bm ->
        BookmarkEditDialog(
            bookmark = bm,
            onSave = { newName ->
                scope.launch {
                    repository.updateBookmark(bm.copy(title = newName))
                }
                bookmarkToEdit = null
            },
            onDelete = {
                scope.launch {
                    repository.deleteBookmark(bm)
                }
                bookmarkToEdit = null
            },
            onDismiss = { bookmarkToEdit = null }
        )
    }

}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(180.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}
