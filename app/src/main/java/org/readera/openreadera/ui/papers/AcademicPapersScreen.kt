package org.readera.openreadera.ui.papers

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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.readera.openreadera.papers.AcademicPaper
import org.readera.openreadera.papers.PaperDownloadState
import org.readera.openreadera.data.scanner.StorageScanner
import java.io.File
import android.os.Environment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcademicPapersScreen(
    onOpenPaper: (String) -> Unit,
    viewModel: AcademicPapersViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    val isPhone = LocalConfiguration.current.screenWidthDp < 600

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (isPhone) {
                    // Phone: Full-width search bar
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { viewModel.updateQuery(it) },
                        placeholder = { Text("Buscar en arXiv (título, autor, tema)...") },
                        leadingIcon = {
                            Icon(Icons.Default.School, contentDescription = "Buscar papers", tint = MaterialTheme.colorScheme.primary)
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
                                    Icon(Icons.Default.ArrowForward, contentDescription = "Buscar")
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
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = { viewModel.updateQuery(it) },
                            placeholder = { Text("Buscar papers en arXiv (título, autor, tema, IA, cuántica)...") },
                            leadingIcon = {
                                Icon(Icons.Default.School, contentDescription = "Buscar papers", tint = MaterialTheme.colorScheme.primary)
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
                                        Icon(Icons.Default.ArrowForward, contentDescription = "Buscar")
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

                        Spacer(modifier = Modifier.width(12.dp))

                        AssistChip(
                            onClick = {},
                            leadingIcon = {
                                Icon(Icons.Default.AutoStories, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            },
                            label = {
                                Text("arXiv Open Access", maxLines = 1)
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Discipline filter chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isPhone) {
                        item {
                            AssistChip(
                                onClick = {},
                                leadingIcon = {
                                    Icon(Icons.Default.AutoStories, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                },
                                label = { Text("arXiv OA", maxLines = 1) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                    val categories = listOf(
                        "Todos",
                        "Informática / CS",
                        "Física",
                        "Matemáticas",
                        "Biología",
                        "Finanzas / Economía",
                        "Estadística"
                    )
                    items(categories) { cat ->
                        FilterChip(
                            selected = state.selectedCategory == cat,
                            onClick = { viewModel.setCategory(cat) },
                            label = { Text(cat) }
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
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Buscando artículos científicos en arXiv...", style = MaterialTheme.typography.bodyLarge)
                }
            } else if (state.papers.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Escribe un término para buscar papers académicos", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                LazyVerticalGrid(
                    columns = if (isPhone) GridCells.Fixed(1) else GridCells.Fixed(3),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.papers, key = { it.id }) { paper ->
                        val downloadState = state.downloadStates[paper.id]
                        AcademicPaperCard(
                            paper = paper,
                            downloadState = downloadState,
                            onDownloadClick = { viewModel.downloadPaper(paper) },
                            onOpenClick = {
                                val file = resolveDownloadedPaperFile(paper.title, paper.authors, downloadState?.savedFilePath)
                                file?.let { onOpenPaper(it.absolutePath) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AcademicPaperCard(
    paper: AcademicPaper,
    downloadState: PaperDownloadState?,
    onDownloadClick: () -> Unit,
    onOpenClick: () -> Unit
) {
    val isPhone = LocalConfiguration.current.screenWidthDp < 600
    val isLocalFileExisting = remember(paper.id, downloadState) {
        if (downloadState?.isCompleted == true) true
        else {
            resolveDownloadedPaperFile(paper.title, paper.authors, downloadState?.savedFilePath) != null
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
            .then(if (isPhone) Modifier.wrapContentHeight() else Modifier.height(230.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                if (isCompleted) {
                    onOpenClick()
                } else if (downloadState?.isDownloading != true) {
                    onDownloadClick()
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                // Header tags: Category & Year & PDF
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = paper.displayCategory,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (paper.publishedYear.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = paper.publishedYear,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = "PDF",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Title
                Text(
                    text = paper.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(3.dp))

                // Authors
                Text(
                    text = paper.displayAuthors,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Abstract snippet
                if (paper.summary.isNotBlank()) {
                    Text(
                        text = paper.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp
                    )
                }
            }

            // Download or Open Button
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                when {
                    isCompleted -> {
                        Button(
                            onClick = onOpenClick,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
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
                                Text("Descargando PDF...", style = MaterialTheme.typography.labelSmall)
                                Text("${(downloadState.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { downloadState.progress },
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
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("DESCARGAR PDF")
                        }
                    }
                }
            }
        }
    }
}

internal fun resolveDownloadedPaperFile(
    title: String,
    authors: List<String>,
    savedFilePath: String? = null
): File? {
    if (!savedFilePath.isNullOrBlank()) {
        val f = File(savedFilePath)
        if (f.exists()) return f
    }
    try {
        val booksDir = StorageScanner.getAppBooksDirectory()
        val legacyDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "OpenReadEra"
        )
        val dirs = listOf(booksDir, legacyDir).filter { it.exists() }

        val safeTitle = title.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s-]"), "").take(45).trim()
        val safeAuthor = authors.firstOrNull()?.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s-]"), "")?.take(25)?.trim() ?: "arXiv"

        for (dir in dirs) {
            val f1 = File(dir, "$safeAuthor - $safeTitle.pdf")
            if (f1.exists() && f1.length() > 0) return f1

            val f2 = File(dir, "$safeTitle - $safeAuthor.pdf")
            if (f2.exists() && f2.length() > 0) return f2

            val files = dir.listFiles() ?: continue
            val searchWord = safeTitle.split(" ").firstOrNull { it.length > 4 } ?: safeTitle.take(10)
            val match = files.firstOrNull { file ->
                file.isFile && file.length() > 0 && file.extension.equals("pdf", ignoreCase = true) &&
                (file.name.contains(searchWord, ignoreCase = true) || (safeTitle.isNotBlank() && file.name.contains(safeTitle.take(15), ignoreCase = true)))
            }
            if (match != null) return match
        }
    } catch (_: Exception) {}

    return null
}

