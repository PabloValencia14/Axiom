package org.readera.openreadera.ui.catalog

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.readera.openreadera.catalog.*

private fun formatMediaType(mediaType: String): String {
    val lower = mediaType.lowercase()
    return when {
        "epub" in lower -> "EPUB"
        "pdf" in lower -> "PDF"
        "mobi" in lower || "azw" in lower -> "MOBI"
        "fb2" in lower || "fictionbook" in lower -> "FB2"
        "wordprocessingml" in lower || "docx" in lower -> "DOCX"
        "cbz" in lower || "comic" in lower -> "CBZ"
        "plain" in lower || "text/" in lower -> "TXT"
        else -> mediaType.substringAfterLast('/').substringAfterLast('+').uppercase().take(6)
    }
}

private fun feedHostLabel(feedUrl: String): String = runCatching {
    val uri = Uri.parse(feedUrl)
    val host = uri.host ?: return@runCatching "Feed HTTPS"
    val port = uri.port.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
    "https://$host$port"
}.getOrDefault("Feed HTTPS")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpdsCatalogScreen(
    onOpenBook: (String) -> Unit,
    viewModel: OpdsCatalogViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    var catalogToDelete by remember { mutableStateOf<OpdsCatalog?>(null) }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        val currentCatalog = state.catalogs.firstOrNull { it.id == state.selectedCatalogId }
                        val titleText = if (state.selectedCatalogId != null) {
                            state.feedPage?.title?.ifBlank { null } ?: currentCatalog?.title ?: "Catálogo OPDS"
                        } else {
                            "Catálogos OPDS"
                        }
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitleText = if (state.selectedCatalogId != null) {
                            currentCatalog?.let { feedHostLabel(it.feedUrl) } ?: "Navegación segura por HTTPS"
                        } else {
                            "Feeds personales y bibliotecas remotas"
                        }
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    if (state.selectedCatalogId != null) {
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.navigateBack()
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Retroceder en el catálogo"
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.RssFeed,
                            contentDescription = "Catálogos OPDS",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.setAddDialogVisible(true) }
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Añadir nuevo catálogo OPDS"
                        )
                    }
                    if (state.selectedCatalogId != null) {
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.showCatalogList()
                            }
                        ) {
                            Icon(
                                Icons.Default.Dns,
                                contentDescription = "Ver lista de catálogos"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (state.selectedCatalogId == null) {
                // View A: Catalog Management List
                CatalogsListView(
                    catalogs = state.catalogs,
                    onSelectCatalog = { viewModel.selectCatalog(it.id) },
                    onDeleteCatalog = { catalogToDelete = it },
                    onAddCatalogClick = { viewModel.setAddDialogVisible(true) }
                )
            } else {
                // View B: Selected Catalog Feed Browsing
                CatalogFeedBrowsingView(
                    state = state,
                    onQueryChange = { viewModel.updateQuery(it) },
                    onSearch = {
                        focusManager.clearFocus()
                        viewModel.search()
                    },
                    onBrowse = { viewModel.browse(it) },
                    onDownload = { entry, acq -> viewModel.download(entry, acq) },
                    onOpenBook = onOpenBook,
                    onRetryRoot = { viewModel.browse(null) },
                    onBackToCatalogs = { viewModel.showCatalogList() }
                )
            }
        }
    }

    // Add Catalog Dialog
    if (state.isAddDialogVisible) {
        AddCatalogDialog(
            onDismiss = { viewModel.setAddDialogVisible(false) },
            onConfirm = { title, url, user, pass ->
                viewModel.addCatalog(title, url, user, pass)
            }
        )
    }

    // Delete Catalog Confirmation Dialog
    catalogToDelete?.let { cat ->
        AlertDialog(
            onDismissRequest = { catalogToDelete = null },
            icon = {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Eliminar catálogo") },
            text = {
                Text("¿Deseas eliminar el catálogo \"${cat.title}\"? Se borrarán también las credenciales guardadas en el dispositivo.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCatalog(cat.id)
                        catalogToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { catalogToDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun CatalogsListView(
    catalogs: List<OpdsCatalog>,
    onSelectCatalog: (OpdsCatalog) -> Unit,
    onDeleteCatalog: (OpdsCatalog) -> Unit,
    onAddCatalogClick: () -> Unit
) {
    if (catalogs.isEmpty()) {
        // Explicit Empty State for Catalogs
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.RssFeed,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Sin catálogos OPDS configurados",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Añade tus bibliotecas personales (Calibre, Standard Ebooks, Project Gutenberg) para explorar y descargar libros directamente mediante conexiones seguras HTTPS.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onAddCatalogClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Añadir primer catálogo")
                    }
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tus catálogos guardados (${catalogs.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onAddCatalogClick) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Añadir")
                    }
                }
            }

            items(catalogs, key = { it.id }) { catalog ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectCatalog(catalog) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = "Conexión HTTPS segura",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = catalog.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = feedHostLabel(catalog.feedUrl),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = { onDeleteCatalog(catalog) }
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "Eliminar catálogo ${catalog.title}",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }

                        IconButton(
                            onClick = { onSelectCatalog(catalog) }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Explorar catálogo ${catalog.title}"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogFeedBrowsingView(
    state: OpdsCatalogUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onBrowse: (String?) -> Unit,
    onDownload: (OpdsEntry, OpdsAcquisition) -> Unit,
    onOpenBook: (String) -> Unit,
    onRetryRoot: () -> Unit,
    onBackToCatalogs: () -> Unit
) {
    val page = state.feedPage

    Column(modifier = Modifier.fillMaxSize()) {
        // Search & No-Search UI section
        if (page?.searchUrl != null) {
            // Feed provides OpenSearch
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    placeholder = {
                        Text(
                            text = "Buscar en este catálogo...",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Buscar en catálogo",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Limpiar búsqueda")
                                }
                            }
                            IconButton(onClick = onSearch) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Ejecutar búsqueda"
                                )
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else if (page != null) {
            // Explicit No-Search State UI
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.SearchOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Este feed no proporciona motor de búsqueda OpenSearch. Navega por las secciones a continuación.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Main content area
        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isLoading -> {
                    // Explicit Loading State
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Cargando catálogo OPDS...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                state.errorMessage != null && page == null -> {
                    // Explicit Error State
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Error al conectar con el catálogo",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = state.errorMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(onClick = onBackToCatalogs) {
                                        Text("Catálogos")
                                    }
                                    Button(onClick = onRetryRoot) {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Reintentar")
                                    }
                                }
                            }
                        }
                    }
                }

                page != null -> {
                    FeedContentList(
                        page = page,
                        state = state,
                        onBrowse = onBrowse,
                        onDownload = onDownload,
                        onOpenBook = onOpenBook
                    )
                }

                else -> {
                    // Fallback empty
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No hay contenido disponible.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedContentList(
    page: OpdsFeedPage,
    state: OpdsCatalogUiState,
    onBrowse: (String?) -> Unit,
    onDownload: (OpdsEntry, OpdsAcquisition) -> Unit,
    onOpenBook: (String) -> Unit
) {
    if (page.entries.isEmpty() && page.navigationLinks.isEmpty()) {
        // Explicit Empty Feed State
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.FolderOff,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No se encontraron libros ni categorías en esta sección.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Navigation links chips
        if (page.navigationLinks.isNotEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Secciones y categorías (${page.navigationLinks.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(page.navigationLinks) { link ->
                            AssistChip(
                                onClick = { onBrowse(link.href) },
                                label = { Text(link.title.ifBlank { "Subsección" }) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                shape = RoundedCornerShape(16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                }
            }
        }

        // Entries header
        if (page.entries.isNotEmpty()) {
            item {
                Text(
                    text = "Libros y documentos (${page.entries.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Entries list
        items(page.entries, key = { it.id }) { entry ->
            OpdsEntryCard(
                entry = entry,
                state = state,
                onBrowse = onBrowse,
                onDownload = onDownload,
                onOpenBook = onOpenBook
            )
        }

        // Next page pagination button
        if (page.nextUrl != null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    FilledTonalButton(
                        onClick = { onBrowse(page.nextUrl) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cargar página siguiente")
                    }
                }
            }
        }
    }
}

@Composable
private fun OpdsEntryCard(
    entry: OpdsEntry,
    state: OpdsCatalogUiState,
    onBrowse: (String?) -> Unit,
    onDownload: (OpdsEntry, OpdsAcquisition) -> Unit,
    onOpenBook: (String) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Title
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = if (isExpanded) 10 else 2,
                overflow = TextOverflow.Ellipsis
            )

            // Author
            if (entry.author.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Summary
            if (entry.summary.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (isExpanded) 20 else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { isExpanded = !isExpanded }
                )
                if (entry.summary.length > 120) {
                    Text(
                        text = if (isExpanded) "Ver menos" else "Ver más",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { isExpanded = !isExpanded }
                            .padding(top = 2.dp)
                    )
                }
            }

            // Sub-navigation link if any
            if (!entry.navigationUrl.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onBrowse(entry.navigationUrl) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Ver subcategoría / enlaces")
                }
            }

            // Acquisitions
            if (entry.acquisitions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(entry.acquisitions) { acquisition ->
                        val formatLabel = formatMediaType(acquisition.mediaType)
                        val dlState = state.getDownloadState(entry, acquisition)

                        when {
                            dlState?.isCompleted == true && dlState.localFilePath != null -> {
                                // Completed: Open button
                                Button(
                                    onClick = { onOpenBook(dlState.localFilePath) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.MenuBook,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Abrir $formatLabel")
                                }
                            }

                            dlState?.isDownloading == true -> {
                                // Downloading progress
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        val percent = (dlState.progress * 100).toInt()
                                        Text(
                                            text = "$formatLabel $percent%",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }

                            dlState?.error != null -> {
                                // Error retry button
                                OutlinedButton(
                                    onClick = { onDownload(entry, acquisition) },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Reintentar $formatLabel")
                                }
                            }

                            else -> {
                                // Ready to download
                                FilledTonalButton(
                                    onClick = { onDownload(entry, acquisition) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Descargar $formatLabel")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddCatalogDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, feedUrl: String, username: String?, password: String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var feedUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir catálogo OPDS") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Introduce la dirección del catálogo. Por seguridad, solo se admiten conexiones cifradas por HTTPS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        validationError = null
                    },
                    label = { Text("Nombre del catálogo") },
                    placeholder = { Text("Ej. Mi Calibre / Standard Ebooks") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = feedUrl,
                    onValueChange = {
                        feedUrl = it
                        validationError = null
                    },
                    label = { Text("URL del feed OPDS (HTTPS)") },
                    placeholder = { Text("https://servidor.com/opds") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (feedUrl.startsWith("https://", ignoreCase = true)) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Usuario (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña (opcional)") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Ocultar contraseña" else "Mostrar contraseña"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Las credenciales se cifran con AES-GCM en el almacén de claves del dispositivo (Android KeyStore) y no se guardan en texto plano.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                validationError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanTitle = title.trim()
                    val cleanUrl = feedUrl.trim()
                    if (cleanTitle.isBlank()) {
                        validationError = "El nombre del catálogo no puede estar vacío."
                        return@Button
                    }
                    if (cleanUrl.isBlank() || !cleanUrl.startsWith("https://", ignoreCase = true)) {
                        validationError = "La URL debe comenzar obligatoriamente con https://"
                        return@Button
                    }
                    onConfirm(
                        cleanTitle,
                        cleanUrl,
                        username.trim().ifBlank { null },
                        password.ifBlank { null }
                    )
                }
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
