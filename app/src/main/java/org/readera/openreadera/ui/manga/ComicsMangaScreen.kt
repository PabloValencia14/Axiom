package org.readera.openreadera.ui.manga

import android.os.Environment
import android.widget.Toast
import java.io.File
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readera.openreadera.data.scanner.StorageScanner
import org.readera.openreadera.manga.MangaChapter
import org.readera.openreadera.manga.MangaDexClient
import org.readera.openreadera.manga.MangaItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicsMangaScreen(
    onOpenCbz: (filePath: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("Berserk") }
    var selectedLanguage by remember { mutableStateOf("es") } // "es", "en", "all"
    var isSearching by remember { mutableStateOf(false) }
    var mangaList by remember { mutableStateOf<List<MangaItem>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchRequestId by remember { mutableIntStateOf(0) }

    // Selected manga for viewing chapters
    var activeManga by remember { mutableStateOf<MangaItem?>(null) }

    fun performSearch(query: String) {
        if (query.isBlank()) return
        val requestId = ++searchRequestId
        isSearching = true
        searchError = null
        scope.launch {
            val res = MangaDexClient.searchManga(query)
            if (requestId != searchRequestId) return@launch
            res.onSuccess {
                mangaList = it
                isSearching = false
            }.onFailure {
                searchError = it.localizedMessage ?: "Error al buscar en MangaDex"
                isSearching = false
            }
        }
    }

    LaunchedEffect(Unit) {
        performSearch(searchQuery)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Search Header
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Buscar cómics y manga (ej. One Piece, Naruto...)") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )

                    Button(
                        onClick = { performSearch(searchQuery) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Buscar")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Popular suggestions chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        Text(
                            text = "Populares:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val suggestions = listOf("One Piece", "Berserk", "Chainsaw Man", "Solo Leveling", "Naruto", "Dragon Ball")
                    items(suggestions) { tag ->
                        SuggestionChip(
                            onClick = {
                                searchQuery = tag
                                performSearch(tag)
                            },
                            label = { Text(tag) }
                        )
                    }
                }
            }
        }

        // Manga Results Grid
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            if (isSearching) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Buscando en catálogo de cómics y manga...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (searchError != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(searchError ?: "", color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(onClick = { performSearch(searchQuery) }) {
                            Text("Reintentar")
                        }
                    }
                }
            } else if (mangaList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No se encontraron resultados para «$searchQuery».", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 175.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(mangaList, key = { it.id }) { manga ->
                        MangaCard(
                            manga = manga,
                            onClick = { activeManga = manga }
                        )
                    }
                }
            }
        }
    }

    // Chapters Bottom Sheet
    if (activeManga != null) {
        MangaChaptersSheet(
            manga = activeManga!!,
            preferredLanguage = selectedLanguage,
            onLanguageChange = { selectedLanguage = it },
            onOpenCbz = onOpenCbz,
            onDismiss = { activeManga = null }
        )
    }
}

@Composable
private fun MangaCard(
    manga: MangaItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                if (manga.coverUrl != null) {
                    AsyncImage(
                        model = manga.coverUrl,
                        contentDescription = manga.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                }

                if (manga.status.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Text(
                            text = manga.status.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = manga.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (manga.tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = manga.tags.take(2).joinToString(" • "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaChaptersSheet(
    manga: MangaItem,
    preferredLanguage: String,
    onLanguageChange: (String) -> Unit,
    onOpenCbz: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var chapters by remember { mutableStateOf<List<MangaChapter>>(emptyList()) }
    var isLoadingChapters by remember { mutableStateOf(true) }
    var chaptersError by remember { mutableStateOf<String?>(null) }

    // Downloading state: chapterId -> (progressFloat, statusString)
    val downloadingChapters = remember { mutableStateMapOf<String, Pair<Float, String>>() }
    val downloadedFiles = remember { mutableStateMapOf<String, String>() }

    fun loadChapters(lang: String) {
        isLoadingChapters = true
        chaptersError = null
        scope.launch {
            val res = MangaDexClient.getChapters(manga.id, lang)
            res.onSuccess {
                chapters = it
                isLoadingChapters = false
            }.onFailure {
                chaptersError = it.localizedMessage ?: "Error al obtener capítulos"
                isLoadingChapters = false
            }
        }
    }

    LaunchedEffect(manga.id, preferredLanguage) {
        loadChapters(preferredLanguage)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        modifier = Modifier.fillMaxHeight(0.9f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (manga.coverUrl != null) {
                    AsyncImage(
                        model = manga.coverUrl,
                        contentDescription = manga.title,
                        modifier = Modifier
                            .size(70.dp, 100.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = manga.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = manga.description.ifBlank { "Sin sinopsis disponible." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Language Filter Segmented Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Capítulos disponibles:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = preferredLanguage == "es",
                        onClick = { onLanguageChange("es") },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                    ) {
                        Text("Español")
                    }
                    SegmentedButton(
                        selected = preferredLanguage == "en",
                        onClick = { onLanguageChange("en") },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                    ) {
                        Text("Inglés")
                    }
                    SegmentedButton(
                        selected = preferredLanguage == "all",
                        onClick = { onLanguageChange("all") },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                    ) {
                        Text("Todos")
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider()

            // Chapters List
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (isLoadingChapters) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (chaptersError != null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(chaptersError ?: "", color = MaterialTheme.colorScheme.error)
                    }
                } else if (chapters.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No se encontraron capítulos descargables en este idioma.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(chapters, key = { it.id }) { chapter ->
                            val downloadState = downloadingChapters[chapter.id]

                            // Check disk for downloaded file
                            val safeManga = manga.title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(40)
                            val safeChNum = chapter.chapter.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "1" }
                            val mangaDir = File(
                                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OpenReadEra/Manga"),
                                safeManga
                            )
                            val localCbz = File(mangaDir, "Capitulo_${safeChNum}.cbz")
                            val isDownloaded = (localCbz.exists() && localCbz.length() > 1024) || downloadedFiles.containsKey(chapter.id)
                            val resolvedPath = downloadedFiles[chapter.id] ?: if (isDownloaded) localCbz.absolutePath else null

                            val chNum = chapter.chapter.takeIf { it.isNotBlank() && it != "null" } ?: ""
                            val chLabel = if (chNum.isNotBlank()) "Capítulo $chNum" else "Capítulo"
                            val chTitle = chapter.title.takeIf { it.isNotBlank() && it != "null" }
                            val titleText = if (chTitle != null) "$chLabel: $chTitle" else chLabel

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = titleText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "${chapter.pagesCount} páginas • Idioma: ${chapter.language.uppercase()}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        if (downloadState != null) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            LinearProgressIndicator(
                                                progress = { downloadState.first },
                                                modifier = Modifier
                                                    .fillMaxWidth(0.9f)
                                                    .height(4.dp)
                                            )
                                            Text(
                                                text = downloadState.second,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    if (resolvedPath != null) {
                                        FilledTonalButton(
                                            onClick = {
                                                onDismiss()
                                                onOpenCbz(resolvedPath)
                                            }
                                        ) {
                                            Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Leer CBZ")
                                        }
                                    } else if (downloadState == null) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    downloadingChapters[chapter.id] = 0.05f to "Iniciando..."
                                                    val res = MangaDexClient.downloadChapterAsCbz(
                                                        mangaTitle = manga.title,
                                                        chapter = chapter,
                                                        onProgress = { prog, status ->
                                                            downloadingChapters[chapter.id] = prog to status
                                                        }
                                                    )
                                                    res.onSuccess { file ->
                                                        downloadingChapters.remove(chapter.id)
                                                        downloadedFiles[chapter.id] = file.absolutePath
                                                        Toast.makeText(context, "Capítulo descargado en formato .CBZ", Toast.LENGTH_SHORT).show()

                                                        // Scan into library Room DB
                                                        withContext(Dispatchers.IO) {
                                                            StorageScanner.scanSingleFile(context, file)
                                                        }
                                                    }.onFailure { err ->
                                                        downloadingChapters.remove(chapter.id)
                                                        Toast.makeText(context, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Descargar")
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
}
