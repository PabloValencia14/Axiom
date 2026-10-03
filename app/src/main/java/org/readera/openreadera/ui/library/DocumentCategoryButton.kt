package org.readera.openreadera.ui.library

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.readera.openreadera.OpenReadEraApplication
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.scanner.DocumentCategory
import org.readera.openreadera.data.scanner.DocumentCategoryClassifier

@Composable
internal fun DocumentCategoryButton(book: Book) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val prefs = remember(context) { context.getSharedPreferences(DocumentCategoryClassifier.CACHE_PREFS, Context.MODE_PRIVATE) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val decision = remember(book, revision) { DocumentCategoryClassifier.cachedOrQuick(context, book) }
    val manual = remember(book, revision) { DocumentCategoryClassifier.manualCategory(context, book) }
    var open by remember(book.id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { error = null; open = true }, contentPadding = PaddingValues(horizontal = 0.dp)) {
        Text("${decision.category.displayName}${if (manual != null) " · Manual" else ""}", style = MaterialTheme.typography.labelSmall)
    }
    if (open) {
        fun save(category: DocumentCategory?) {
            scope.launch {
                saving = true
                error = null
                try {
                    DocumentCategoryClassifier.setManualCategory(context, book, category)
                    (context.applicationContext as OpenReadEraApplication).storageScanner.classifyBook(book)
                    open = false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = "No se pudo actualizar la categoría. Vuelve a intentarlo."
                } finally {
                    saving = false
                }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!saving) open = false },
            title = { Text("Categoría del documento") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Tu elección se conservará al volver a escanear la biblioteca.")
                    TextButton(onClick = { save(null) }, enabled = !saving) {
                        Text(if (manual == null) "✓ Automática" else "Volver a automática")
                    }
                    DocumentCategory.entries.forEach { category ->
                        TextButton(onClick = { save(category) }, enabled = !saving) {
                            Text("${if (manual == category) "✓ " else ""}${category.displayName}")
                        }
                    }
                    if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }, enabled = !saving) { Text("Cerrar") } }
        )
    }
}
