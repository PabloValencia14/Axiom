package org.readera.openreadera.ui.reader.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.readera.openreadera.papers.CitationTarget
import org.readera.openreadera.papers.PaperReference

@Composable
fun ReferencePreviewDialog(
    target: CitationTarget,
    load: suspend (CitationTarget) -> List<PaperReference>,
    onOpenPaper: suspend (PaperReference, (Float) -> Unit) -> Unit,
    onPage: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var references by remember(target) { mutableStateOf<List<PaperReference>?>(null) }
    var error by remember(target) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var downloading by remember(target) { mutableStateOf<PaperReference?>(null) }
    var progress by remember(target) { mutableFloatStateOf(0f) }
    var downloadError by remember(target) { mutableStateOf<String?>(null) }
    var failedReference by remember(target) { mutableStateOf<PaperReference?>(null) }
    LaunchedEffect(target) {
        try { references = load(target) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Referencias · ${target.text}", maxLines = 3) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    error -> Text("No se pudo consultar la bibliografía de este documento.")
                    references == null -> { CircularProgressIndicator(); Text("Buscando en la bibliografía del PDF…") }
                    references!!.isEmpty() -> Text("No se ha podido identificar esta cita en la bibliografía. No se han consultado servicios externos.")
                    else -> {
                        Text("Referencia del propio PDF · abrir el artículo requiere Internet", style = MaterialTheme.typography.labelMedium)
                        references!!.forEach { reference ->
                            HorizontalDivider()
                            Text(reference.label?.let { "[$it] ${reference.text}" } ?: reference.text)
                            Button(enabled = downloading == null, onClick = {
                                downloading = reference
                                progress = 0f
                                downloadError = null
                                failedReference = null
                                scope.launch {
                                    try { onOpenPaper(reference) { progress = it } }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) {
                                        failedReference = reference
                                        downloadError = failure.message ?: "No se pudo abrir el artículo citado."
                                    }
                                    finally { downloading = null }
                                }
                            }) {
                                Text(if (downloading != reference) "Abrir paper completo" else if (progress == 0f)
                                    "Buscando PDF…" else "Descargando ${(progress * 100).toInt()} %…")
                            }
                            if (downloading == reference) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (failedReference == reference) downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            TextButton(onClick = { onPage(reference.page) }) {
                                Text("Ver en página ${reference.page + 1}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}
