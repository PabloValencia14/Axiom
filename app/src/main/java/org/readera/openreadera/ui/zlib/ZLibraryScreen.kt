package org.readera.openreadera.ui.zlib

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import org.readera.openreadera.catalog.CatalogSource
import org.readera.openreadera.data.scanner.StorageScanner
import org.readera.openreadera.zlib.ZLibBook
import org.readera.openreadera.zlib.ZLibDownloadState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZLibraryScreen(
    onOpenBook: (String) -> Unit,
    viewModel: ZLibraryViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Unified Search Bar: searches all 5 engines concurrently via API
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { viewModel.updateQuery(it) },
                        placeholder = {
                            Text(
                                "Buscar en todos los motores (LibGen, Z-Lib, Anna's, Ebookelo, Gutenberg)...",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Buscar",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (state.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.updateQuery("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                                    }
                                }
                                IconButton(onClick = {
                                    focusManager.clearFocus()
                                    viewModel.search()
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Buscar")
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focusManager.clearFocus()
                            viewModel.search()
                        }),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Account & Settings dialog button (Z-Library settings/account)
                    FilledTonalIconButton(
                        onClick = { viewModel.openAccountDialog(true) }
                    ) {
                        Badge(
                            containerColor = if (state.isUserLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        ) {
                            Icon(
                                if (state.isUserLoggedIn) Icons.Default.AccountCircle else Icons.Default.Settings,
                                contentDescription = "Configuración Z-Library"
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Unified Engine, Format & Language Filter Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val sources = listOf(
                        CatalogSource.ALL to "Todos los motores",
                        CatalogSource.LIBGEN to "LibGen",
                        CatalogSource.Z_LIBRARY to "Z-Lib",
                        CatalogSource.ANNAS_ARCHIVE to "Anna's",
                        CatalogSource.EBOOKELO to "Ebookelo",
                        CatalogSource.STANDARD_EBOOKS to "Std Ebooks",
                        CatalogSource.GUTENBERG to "Gutenberg",
                        CatalogSource.OPEN_LIBRARY to "OpenLib"
                    )
                    items(sources) { (src, label) ->
                        FilterChip(
                            selected = state.selectedSource == src,
                            onClick = { viewModel.setSource(src) },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                    item {
                        VerticalDivider(modifier = Modifier.height(20.dp).padding(horizontal = 4.dp))
                    }
                    val formats = listOf("Todos", "EPUB", "PDF", "MOBI", "FB2", "TXT")
                    items(formats) { ext ->
                        FilterChip(
                            selected = state.selectedExtension == ext,
                            onClick = { viewModel.setExtension(ext) },
                            label = { Text(ext, fontSize = 11.sp) }
                        )
                    }
                    item {
                        VerticalDivider(modifier = Modifier.height(20.dp).padding(horizontal = 4.dp))
                    }
                    val languages = listOf("Todos", "Spanish", "English")
                    items(languages) { lang ->
                        FilterChip(
                            selected = state.selectedLanguage == lang,
                            onClick = { viewModel.setLanguage(lang) },
                            label = {
                                Text(
                                    if (lang == "Spanish") "Español" else if (lang == "English") "Inglés" else "Todos los idiomas",
                                    fontSize = 11.sp
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Fixed suggested search queries
                val recommendations = listOf(
                    "Bucear en verano",
                    "Sara Stridsberg",
                    "Beckomberga",
                    "El bosque oscuro",
                    "El marciano",
                    "Rebelión en la granja",
                    "La peste",
                    "Crónicas marcianas",
                    "Crimen y castigo",
                    "La metamorfosis",
                    "Dune",
                    "Watchmen"
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.padding(end = 2.dp)
                        ) {
                            Text(
                                "Búsquedas sugeridas",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                    items(recommendations) { rec ->
                        SuggestionChip(
                            onClick = {
                                viewModel.updateQuery(rec)
                                viewModel.search()
                            },
                            label = { Text(rec, fontSize = 11.sp) }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (state.isLoading) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Buscando libros simultáneamente en LibGen, Z-Lib, Anna's, Ebookelo, Standard Ebooks...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            } else if (state.books.isEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No se encontraron libros para '${state.searchQuery}'.",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Prueba con otro autor o título. La búsqueda consulta todos los motores en paralelo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Responsive Grid: 3 columns on tablet landscape/wide, 2 columns on medium, 1 on phone
                val columns = when {
                    screenWidthDp >= 900 -> 3
                    screenWidthDp >= 600 -> 2
                    else -> 1
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.books, key = { "${it.source.name}_${it.id}" }) { book ->
                        val downloadState = state.downloadStates[book.id]
                        ZLibBookCard(
                            book = book,
                            downloadState = downloadState,
                            onDownloadClick = { viewModel.downloadBook(book) },
                            onOpenBookClick = {
                                val file = resolveDownloadedBookFile(book.title, book.author, book.extension, downloadState?.savedFilePath)
                                file?.let { onOpenBook(it.absolutePath) }
                            }
                        )
                    }
                }
            }

            // Auth Prompt Dialog when tapping Download on Z-Library restricted book without session
            if (state.showAuthPromptDialog && state.pendingDownloadBook != null) {
                val book = state.pendingDownloadBook!!
                AlertDialog(
                    onDismissRequest = { viewModel.dismissAuthPrompt() },
                    icon = { Icon(Icons.Default.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    title = { Text("Iniciar sesión en Z-Library") },
                    text = {
                        Column {
                            Text(
                                "Para descargar '${book.title}' desde Z-Library, necesitas iniciar sesión o configurar tu token (remix_userid y remix_userkey).",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Consejo: Los libros de Ebookelo, Anna's, Gutenberg y Open Library se descargan directamente vía API sin necesidad de cuenta.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.dismissAuthPrompt()
                                viewModel.openAccountDialog(true)
                            }
                        ) {
                            Text("Configurar cuenta / Token")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissAuthPrompt() }) {
                            Text("Cancelar")
                        }
                    }
                )
            }



            // Account & Settings Dialog
            if (state.showAccountDialog) {
                ZLibAccountDialog(
                    isLoggedIn = state.isUserLoggedIn,
                    userEmail = state.userEmail,
                    userName = state.userName,
                    downloadsLeft = state.downloadsLeft,
                    currentMirror = state.customMirror,
                    onLogin = { email, pass -> viewModel.login(email, pass) },
                    onLoginWithToken = { id, key -> viewModel.loginWithToken(id, key) },
                    onLogout = { viewModel.logout() },
                    onUpdateMirror = { viewModel.updateMirror(it) },
                    onDismiss = { viewModel.openAccountDialog(false) }
                )
            }
        }
    }
}

@Composable
fun ZLibBookCard(
    book: ZLibBook,
    downloadState: ZLibDownloadState?,
    onDownloadClick: () -> Unit,
    onOpenBookClick: () -> Unit
) {
    val isLocalFileExisting = remember(book.id, downloadState) {
        if (downloadState?.isCompleted == true) true
        else {
            resolveDownloadedBookFile(book.title, book.author, book.extension, downloadState?.savedFilePath) != null
        }
    }
    val isCompleted = downloadState?.isCompleted == true || isLocalFileExisting

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                if (isCompleted) {
                    onOpenBookClick()
                } else if (downloadState?.isDownloading != true) {
                    onDownloadClick()
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Book Cover - Clickable to open book if downloaded
            Box(
                modifier = Modifier
                    .width(95.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                    .clickable {
                        if (isCompleted) {
                            onOpenBookClick()
                        } else if (downloadState?.isDownloading != true) {
                            onDownloadClick()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (!book.coverUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = book.coverUrl,
                        contentDescription = book.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(book.extension, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }

                if (isCompleted) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(10.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("LISTO", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info & Download Button
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    // Source Provider Badge + Format Badge Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(book.source.badgeColor).copy(alpha = 0.18f)
                        ) {
                            Text(
                                text = when (book.source) {
                                    CatalogSource.LIBGEN -> "LibGen"
                                    CatalogSource.Z_LIBRARY -> "Z-Lib"
                                    CatalogSource.ANNAS_ARCHIVE -> "Anna's"
                                    CatalogSource.EBOOKELO -> "Ebookelo"
                                    CatalogSource.STANDARD_EBOOKS -> "Std Ebooks"
                                    CatalogSource.GUTENBERG -> "Gutenberg"
                                    CatalogSource.OPEN_LIBRARY -> "OpenLib"
                                    CatalogSource.GOOGLE_BOOKS -> "Google Books"
                                    CatalogSource.ALL -> "Online"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Bold,
                                color = Color(book.source.badgeColor),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = book.extension,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }

                        if (book.filesize.isNotBlank() && !book.filesize.contains("eBookelo", true)) {
                            Text(
                                text = book.filesize,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        if (book.rating.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(11.dp))
                                Spacer(modifier = Modifier.width(1.dp))
                                Text(book.rating.replace("★", "").trim(), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
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
                }

                // Download / Open Button with Direct Progress
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when {
                        isCompleted -> {
                            Button(
                                onClick = onOpenBookClick,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("LEER AHORA")
                            }
                        }
                        downloadState?.isDownloading == true -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Descargando vía API...", style = MaterialTheme.typography.labelSmall)
                                    Text("${(downloadState.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { downloadState.progress },
                                    color = Color(book.source.badgeColor),
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                )
                            }
                        }
                        else -> {
                            FilledTonalButton(
                                onClick = onDownloadClick,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("DESCARGAR", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ZLibAccountDialog(
    isLoggedIn: Boolean,
    userEmail: String?,
    userName: String?,
    downloadsLeft: Int,
    currentMirror: String,
    onLogin: (String, String) -> Unit,
    onLoginWithToken: (String, String) -> Unit,
    onLogout: () -> Unit,
    onUpdateMirror: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var tokenUserId by remember { mutableStateOf("") }
    var tokenUserKey by remember { mutableStateOf("") }
    var mirrorInput by remember { mutableStateOf(currentMirror) }
    var tabIndex by remember { mutableStateOf(0) }
    var showLoginForm by remember { mutableStateOf(!isLoggedIn) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isLoggedIn && !showLoginForm) "Cuenta Z-Library" else "Iniciar Sesión en Z-Library")
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isLoggedIn && !showLoginForm) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Conectado como:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Text(
                                userName ?: userEmail ?: "Usuario",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (!userEmail.isNullOrBlank() && userName != userEmail) {
                                Text(
                                    userEmail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Descargas disponibles hoy: $downloadsLeft",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { showLoginForm = true },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cambiar de Cuenta / Reingresar")
                    }

                    Button(
                        onClick = onLogout,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cerrar Sesión")
                    }
                } else {
                    TabRow(selectedTabIndex = tabIndex) {
                        Tab(
                            selected = tabIndex == 0,
                            onClick = { tabIndex = 0 },
                            text = { Text("Login", fontSize = 12.sp) }
                        )
                        Tab(
                            selected = tabIndex == 1,
                            onClick = { tabIndex = 1 },
                            text = { Text("Token / Cookie", fontSize = 12.sp) }
                        )
                        Tab(
                            selected = tabIndex == 2,
                            onClick = { tabIndex = 2 },
                            text = { Text("Mirror", fontSize = 12.sp) }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    when (tabIndex) {
                        0 -> {
                            Text(
                                "Inicia sesión con tu cuenta oficial de Z-Library (singlelogin.rs):",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text("Email") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Contraseña") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { onLogin(email.trim(), password.trim()) },
                                enabled = email.isNotBlank() && password.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Iniciar Sesión")
                            }
                        }
                        1 -> {
                            Text(
                                "O introduce tus claves de cookie de Z-Library (remix_userid y remix_userkey):",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = tokenUserId,
                                onValueChange = { tokenUserId = it },
                                label = { Text("remix_userid") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = tokenUserKey,
                                onValueChange = { tokenUserKey = it },
                                label = { Text("remix_userkey") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { onLoginWithToken(tokenUserId.trim(), tokenUserKey.trim()) },
                                enabled = tokenUserId.isNotBlank() && tokenUserKey.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Guardar Token")
                            }
                        }
                        2 -> {
                            Text(
                                "Configura el dominio mirror activo de Z-Library (por defecto singlelogin.rs):",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = mirrorInput,
                                onValueChange = { mirrorInput = it },
                                label = { Text("URL del Mirror") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { onUpdateMirror(mirrorInput.trim()) },
                                enabled = mirrorInput.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Actualizar Mirror")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}

/**
 * Searches the local download directory for any already downloaded book matching title and author
 */
private fun resolveDownloadedBookFile(
    title: String,
    author: String,
    extension: String,
    savedPath: String?
): File? {
    if (!savedPath.isNullOrBlank()) {
        val f = File(savedPath)
        if (f.exists() && f.length() > 0) return f
    }

    try {
        val booksDir = StorageScanner.getAppBooksDirectory()
        val legacyDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "OpenReadEra"
        )
        val dirs = listOf(booksDir, legacyDir).filter { it.exists() }

        val safeTitle = title.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s-]"), "").take(40).trim().lowercase()
        val safeAuthor = author.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s-]"), "").take(30).trim().lowercase()
        val ext = extension.lowercase()

        for (dir in dirs) {
            val direct = File(dir, "$safeTitle - $safeAuthor.$ext")
            if (direct.exists() && direct.length() > 0) return direct

            val match = dir.listFiles()?.firstOrNull { file ->
                val name = file.name.lowercase()
                (name.contains(safeTitle.take(15)) || name.contains(title.take(15).lowercase())) &&
                (file.extension.equals(ext, ignoreCase = true) || file.extension in listOf("epub", "pdf", "mobi", "fb2"))
            }
            if (match != null && match.length() > 0) return match
        }
    } catch (_: Exception) {}

    return null
}
