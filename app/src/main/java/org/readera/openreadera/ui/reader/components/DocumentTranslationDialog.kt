package org.readera.openreadera.ui.reader.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readera.openreadera.data.scanner.StorageScanner
import org.readera.openreadera.engine.EpubWriter
import org.readera.openreadera.translation.GoogleTranslateService
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentTranslationDialog(
    bookTitle: String,
    bookAuthor: String,
    currentPage: Int,
    totalPages: Int,
    getCurrentPageText: () -> String,
    getPageTextByIndex: (Int) -> String,
    onBookGenerated: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedMode by remember { mutableIntStateOf(0) } // 0: Página actual, 1: Libro/Paper completo
    var targetLang by remember { mutableStateOf("es") }
    var showLangMenu by remember { mutableStateOf(false) }

    // Page translation states
    var isTranslatingPage by remember { mutableStateOf(false) }
    var translatedPageText by remember { mutableStateOf("") }
    var pageError by remember { mutableStateOf<String?>(null) }
    var pageTranslationJob by remember { mutableStateOf<Job?>(null) }

    // Full book translation states
    var isTranslatingFull by remember { mutableStateOf(false) }
    var fullProgress by remember { mutableFloatStateOf(0f) }
    var fullStatusMessage by remember { mutableStateOf("") }
    var completedFile by remember { mutableStateOf<File?>(null) }
    var fullError by remember { mutableStateOf<String?>(null) }
    var fullTranslationJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(currentPage, targetLang) {
        pageTranslationJob?.cancel()
        pageTranslationJob = null
        isTranslatingPage = false
        translatedPageText = ""
        pageError = null
    }

    val langName = GoogleTranslateService.supportedLanguages.find { it.first == targetLang }?.second ?: "Español"

    Dialog(onDismissRequest = {
        if (!isTranslatingFull) onDismiss()
    }) {
        Surface(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 580.dp)
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Traducción de Documento",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = bookTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (!isTranslatingFull) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Target Language row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Idioma de destino:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )

                    Box {
                        AssistChip(
                            onClick = { if (!isTranslatingFull && !isTranslatingPage) showLangMenu = true },
                            label = { Text(langName, fontWeight = FontWeight.SemiBold) },
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                            enabled = !isTranslatingFull && !isTranslatingPage
                        )

                        DropdownMenu(
                            expanded = showLangMenu,
                            onDismissRequest = { showLangMenu = false }
                        ) {
                            GoogleTranslateService.supportedLanguages.forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        targetLang = code
                                        showLangMenu = false
                                        translatedPageText = ""
                                    }
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "Al traducir, el texto se envía a Google Translate. Se requiere conexión a Internet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Tabs: Página actual vs Libro completo
                TabRow(
                    selectedTabIndex = selectedMode,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = selectedMode == 0,
                        onClick = { if (!isTranslatingFull && !isTranslatingPage) selectedMode = 0 },
                        text = { Text("Página actual (${currentPage + 1})", fontWeight = FontWeight.Medium) },
                        enabled = !isTranslatingFull && !isTranslatingPage
                    )
                    Tab(
                        selected = selectedMode == 1,
                        onClick = { if (!isTranslatingFull && !isTranslatingPage) selectedMode = 1 },
                        text = { Text("Documento / Paper completo", fontWeight = FontWeight.Medium) },
                        enabled = !isTranslatingFull && !isTranslatingPage
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Content for Mode 0: Current page
                if (selectedMode == 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 340.dp)
                    ) {
                        if (translatedPageText.isBlank() && !isTranslatingPage) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.Language,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Traduce al instante el texto de la página visible.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = {
                                            isTranslatingPage = true
                                            pageError = null
                                            val language = targetLang
                                            pageTranslationJob = scope.launch {
                                                try {
                                                    val text = getCurrentPageText()
                                                    require(text.isNotBlank()) { "No se detectó texto extraíble en esta página." }
                                                    val translated = GoogleTranslateService.translateText(text, language).getOrThrow()
                                                    require(translated.isNotBlank()) { "El servicio no devolvió una traducción." }
                                                    translatedPageText = translated
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (error: Exception) {
                                                    pageError = error.message ?: "No se pudo traducir esta página."
                                                } finally {
                                                    if (pageTranslationJob === currentCoroutineContext()[Job]) isTranslatingPage = false
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Traducir esta página")
                                    }
                                }
                            }
                        } else if (isTranslatingPage) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("Traduciendo página con Google Translate...", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else {
                            // Translated result
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = translatedPageText,
                                        modifier = Modifier.padding(14.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        lineHeight = 22.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Traducción", translatedPageText))
                                        Toast.makeText(context, "Texto copiado", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Copiar")
                                }

                                OutlinedButton(
                                    onClick = { translatedPageText = "" },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Volver a traducir")
                                }
                            }
                        }

                        if (pageError != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = pageError ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else {
                    // Content for Mode 1: Full Document / Paper translation
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 340.dp)
                    ) {
                        if (!isTranslatingFull && completedFile == null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.AutoStories,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Traducción completa a nuevo libro electrónico",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Se revisarán las $totalPages páginas y se creará una nueva copia EPUB en ${langName.lowercase()}. Solo se traduce el texto extraíble; las páginas sin texto se omiten.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )

                                Spacer(modifier = Modifier.height(18.dp))

                                Button(
                                    onClick = {
                                        isTranslatingFull = true
                                        fullError = null
                                        fullProgress = 0.01f
                                        fullStatusMessage = "Iniciando traducción..."

                                        val language = targetLang
                                        fullTranslationJob = scope.launch {
                                            var output: File? = null
                                            var published = false
                                            try {
                                                val generated = withContext(Dispatchers.IO) {
                                                    val chapters = translateDocumentPages(
                                                        totalPages, getPageTextByIndex,
                                                        translate = { GoogleTranslateService.translateText(it, language) },
                                                        onProgress = { page ->
                                                            fullProgress = page.toFloat() / totalPages
                                                            fullStatusMessage = "Traduciendo página $page de $totalPages..."
                                                        }
                                                    )
                                                    fullStatusMessage = "Empaquetando libro en formato EPUB..."
                                                    val booksDir = StorageScanner.getAppBooksDirectory()
                                                    val safeTitle = bookTitle.replace(Regex("""[\\/:*?"<>|]"""), "_").take(40)
                                                    val targetEpub = File.createTempFile("[${language.uppercase()}] $safeTitle-", ".epub", booksDir)
                                                    output = targetEpub
                                                    EpubWriter.createEpub(
                                                        outputFile = targetEpub,
                                                        title = "[${language.uppercase()}] $bookTitle",
                                                        author = bookAuthor,
                                                        chapters = chapters,
                                                        language = language
                                                    ).getOrThrow()
                                                }
                                                completedFile = generated
                                                published = true
                                                onBookGenerated(generated)
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: Exception) {
                                                fullError = error.message ?: "No se pudo generar el documento traducido."
                                            } finally {
                                                if (!published) output?.delete()
                                                isTranslatingFull = false
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Comenzar traducción completa")
                                }
                            }
                        } else if (isTranslatingFull) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    progress = { fullProgress },
                                    modifier = Modifier.size(54.dp),
                                    strokeWidth = 5.dp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "${(fullProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = fullStatusMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                LinearProgressIndicator(
                                    progress = { fullProgress },
                                    modifier = Modifier
                                        .fillMaxWidth(0.8f)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(onClick = {
                                    fullTranslationJob?.cancel()
                                    onDismiss()
                                }) {
                                    Text("Cancelar traducción")
                                }
                            }
                        } else if (completedFile != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(52.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "¡Libro traducido con éxito!",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = completedFile?.name ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Guardado en ${completedFile?.parentFile?.path.orEmpty()}.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Button(
                                    onClick = {
                                        onDismiss()
                                    }
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Listo")
                                }
                            }
                        }

                        if (fullError != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = fullError ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Native text only: blank pages are skipped; a failed translation never becomes source-language output. */
internal suspend fun translateDocumentPages(
    totalPages: Int,
    getPageText: (Int) -> String,
    translate: suspend (String) -> Result<String>,
    onProgress: (Int) -> Unit
): List<Pair<String, String>> {
    val chapters = mutableListOf<Pair<String, String>>()
    for (page in 0 until totalPages) {
        currentCoroutineContext().ensureActive()
        onProgress(page + 1)
        val source = getPageText(page)
        if (source.isBlank()) continue
        val translated = translate(source).getOrElse { failure ->
            if (failure is CancellationException) throw failure
            throw IllegalStateException("No se pudo traducir la página ${page + 1}. No se ha generado una copia incompleta.", failure)
        }
        currentCoroutineContext().ensureActive()
        require(translated.isNotBlank()) { "La traducción de la página ${page + 1} está vacía." }
        chapters.add("Página ${page + 1}" to translated)
        delay(120)
    }
    require(chapters.isNotEmpty()) { "No se pudo extraer texto legible del documento." }
    return chapters
}
