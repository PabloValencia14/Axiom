package org.readera.openreadera.ui.reader.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.readera.openreadera.papers.CitationTarget
import org.readera.openreadera.papers.PaperReference

@Composable
fun ReferencePreviewDialog(
    target: CitationTarget,
    load: suspend (CitationTarget) -> List<PaperReference>,
    onPage: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var references by remember(target) { mutableStateOf<List<PaperReference>?>(null) }
    var error by remember(target) { mutableStateOf(false) }
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
                        Text("Bibliografía del propio PDF · sin conexión", style = MaterialTheme.typography.labelMedium)
                        references!!.forEach { reference ->
                            HorizontalDivider()
                            Text(reference.label?.let { "[$it] ${reference.text}" } ?: reference.text)
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
