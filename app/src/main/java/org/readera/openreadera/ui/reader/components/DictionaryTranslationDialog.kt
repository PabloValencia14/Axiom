package org.readera.openreadera.ui.reader.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.readera.openreadera.translation.GoogleTranslateService
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryTranslationDialog(
    term: String,
    initialTab: Int = 0, // 0 = Diccionario/Wiki, 1 = Traducción
    onPlayTts: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(initialTab) }

    // Dictionary state
    var isWikiLoading by remember { mutableStateOf(true) }
    var wikiTitle by remember { mutableStateOf("") }
    var wikiSummary by remember { mutableStateOf("") }
    var wikiImageUrl by remember { mutableStateOf<String?>(null) }
    var wikiError by remember { mutableStateOf<String?>(null) }

    // Translation state
    var isTranslating by remember { mutableStateOf(false) }
    var selectedTargetLang by remember { mutableStateOf("es") }
    var translatedText by remember { mutableStateOf("") }
    var translationError by remember { mutableStateOf<String?>(null) }
    var showLangDropdown by remember { mutableStateOf(false) }

    // Fetch Wikipedia
    LaunchedEffect(term) {
        if (term.isBlank()) return@LaunchedEffect
        isWikiLoading = true
        wikiError = null
        withContext(Dispatchers.IO) {
            try {
                val cleanTerm = term.trim().take(80)
                val encoded = URLEncoder.encode(cleanTerm, "UTF-8")
                val url = "https://es.wikipedia.org/api/rest_v1/page/summary/$encoded"
                val client = OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Axiom/1.0 (Android Tablet; Reading Assistant)")
                    .build()

                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val json = JSONObject(body)
                        val title = json.optString("title", cleanTerm)
                        val extract = json.optString("extract", "")
                        val thumb = json.optJSONObject("thumbnail")?.optString("source")

                        if (extract.isNotBlank()) {
                            wikiTitle = title
                            wikiSummary = extract
                            wikiImageUrl = thumb
                        } else {
                            wikiError = "No se encontró definición directa para «$cleanTerm»."
                        }
                    } else if (resp.code == 404) {
                        val searchUrl = "https://es.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json"
                        val searchReq = Request.Builder().url(searchUrl).header("User-Agent", "Axiom/1.0").build()
                        client.newCall(searchReq).execute().use { searchResp ->
                            if (searchResp.isSuccessful) {
                                val searchBody = searchResp.body?.string() ?: ""
                                val sJson = JSONObject(searchBody)
                                val sList = sJson.optJSONObject("query")?.optJSONArray("search")
                                if (sList != null && sList.length() > 0) {
                                    val first = sList.getJSONObject(0)
                                    wikiTitle = first.optString("title")
                                    val rawSnippet = first.optString("snippet")
                                        .replace("<span class=\"searchmatch\">", "")
                                        .replace("</span>", "")
                                    wikiSummary = "$rawSnippet..."
                                } else {
                                    wikiError = "Sin resultados en la enciclopedia para «$cleanTerm»."
                                }
                            } else {
                                wikiError = "Sin resultados en la enciclopedia para «$cleanTerm»."
                            }
                        }
                    } else {
                        wikiError = "Error al consultar la enciclopedia (HTTP ${resp.code})"
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                wikiError = "Error de conexión: ${e.localizedMessage ?: "Verifique internet"}"
            } finally {
                isWikiLoading = false
            }
        }
    }

    // Fetch Translation
    LaunchedEffect(term, selectedTargetLang) {
        if (term.isBlank()) return@LaunchedEffect
        isTranslating = true
        translationError = null
        val res = GoogleTranslateService.translateText(term, selectedTargetLang)
        res.onSuccess {
            translatedText = it
            isTranslating = false
        }.onFailure {
            translationError = it.localizedMessage ?: "Error en la traducción"
            isTranslating = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .widthIn(min = 340.dp, max = 560.dp)
                .fillMaxWidth(0.9f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Dialog Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = term.trim(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Consulta inteligente",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Dual Tabs: Enciclopedia / Traducción
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Enciclopedia", fontWeight = FontWeight.Medium)
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Traducción", fontWeight = FontWeight.Medium)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tab Content
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (selectedTab == 0) {
                        // Wikipedia / Dictionary Tab
                        if (isWikiLoading) {
                            Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else if (wikiError != null && wikiSummary.isBlank()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.SearchOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = wikiError ?: "No se encontró información.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(onClick = { selectedTab = 1 }) {
                                    Text("Ver traducción en su lugar")
                                }
                            }
                        } else {
                            Column {
                                if (wikiImageUrl != null) {
                                    AsyncImage(
                                        model = wikiImageUrl,
                                        contentDescription = wikiTitle,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(140.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }

                                if (wikiTitle.isNotBlank()) {
                                    Text(
                                        text = wikiTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                }

                                Text(
                                    text = wikiSummary,
                                    style = MaterialTheme.typography.bodyLarge,
                                    lineHeight = 24.sp
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Wikipedia", "$wikiTitle: $wikiSummary"))
                                            Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Copiar")
                                    }

                                    FilledTonalButton(
                                        onClick = { onPlayTts(wikiSummary) }
                                    ) {
                                        Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Escuchar")
                                    }
                                }
                            }
                        }
                    } else {
                        // Translation Tab
                        Column {
                            // Target Language Selector
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Traducir al:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Box {
                                    val currentLangName = GoogleTranslateService.supportedLanguages
                                        .find { it.first == selectedTargetLang }?.second ?: "Español"

                                    AssistChip(
                                        onClick = { showLangDropdown = true },
                                        label = { Text(currentLangName, fontWeight = FontWeight.SemiBold) },
                                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) }
                                    )

                                    DropdownMenu(
                                        expanded = showLangDropdown,
                                        onDismissRequest = { showLangDropdown = false }
                                    ) {
                                        GoogleTranslateService.supportedLanguages.forEach { (code, name) ->
                                            DropdownMenuItem(
                                                text = { Text(name) },
                                                onClick = {
                                                    selectedTargetLang = code
                                                    showLangDropdown = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            if (isTranslating) {
                                Box(modifier = Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else if (translationError != null) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = translationError ?: "Error",
                                        modifier = Modifier.padding(12.dp),
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            } else {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = translatedText.ifBlank { "Sin texto traducido." },
                                        modifier = Modifier.padding(14.dp),
                                        style = MaterialTheme.typography.bodyLarge,
                                        lineHeight = 24.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Traducción", translatedText))
                                            Toast.makeText(context, "Traducción copiada", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Copiar")
                                    }

                                    FilledTonalButton(
                                        onClick = { onPlayTts(translatedText) }
                                    ) {
                                        Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Escuchar")
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
