package org.readera.openreadera.ui.library.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.readera.openreadera.data.db.CollectionWithCount
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.ui.library.FilterCriteria
import org.readera.openreadera.ui.library.SortCriterion

@Composable
fun SortDialog(
    currentSort: SortCriterion,
    onSelectSort: (SortCriterion) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Ordenados por",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                SortCriterion.values().forEach { criterion ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectSort(criterion)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentSort == criterion,
                            onClick = {
                                onSelectSort(criterion)
                                onDismiss()
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = criterion.label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CERRAR")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterDialog(
    initialCriteria: FilterCriteria,
    totalResults: Int,
    onApply: (FilterCriteria) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedFormats by remember { mutableStateOf(initialCriteria.activeFormats) }
    var withoutAuthor by remember { mutableStateOf(initialCriteria.withoutAuthor) }
    var withoutSeries by remember { mutableStateOf(initialCriteria.withoutSeries) }
    var withoutCollection by remember { mutableStateOf(initialCriteria.withoutCollection) }
    var unreadOnly by remember { mutableStateOf(initialCriteria.unreadOnly) }

    val allFormats = listOf(
        "PDF", "EPUB", "WORD", "DOC", "DOCX", "ODT", "TXT", "MOBI",
        "RTF", "DJVU", "FB2", "CHM", "AZW", "COMIC", "CBR", "CBZ"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Filtrar",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                item {
                    Text(
                        text = "Formatos de archivo",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        allFormats.forEach { fmt ->
                            val isSelected = selectedFormats.contains(fmt)
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedFormats = if (isSelected) {
                                        selectedFormats - fmt
                                    } else {
                                        selectedFormats + fmt
                                    }
                                },
                                label = { Text(fmt) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Filtros de metadatos",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = withoutAuthor,
                            onClick = { withoutAuthor = !withoutAuthor },
                            label = { Text("Sin autor") }
                        )
                        FilterChip(
                            selected = withoutSeries,
                            onClick = { withoutSeries = !withoutSeries },
                            label = { Text("Sin serie") }
                        )
                        FilterChip(
                            selected = withoutCollection,
                            onClick = { withoutCollection = !withoutCollection },
                            label = { Text("Sin colección") }
                        )
                        FilterChip(
                            selected = unreadOnly,
                            onClick = { unreadOnly = !unreadOnly },
                            label = { Text("No leídos") }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Total de resultados encontrados: $totalResults",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCELAR")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onApply(
                        FilterCriteria(
                            activeFormats = selectedFormats,
                            withoutAuthor = withoutAuthor,
                            withoutSeries = withoutSeries,
                            withoutCollection = withoutCollection,
                            unreadOnly = unreadOnly
                        )
                    )
                    onDismiss()
                }
            ) {
                Text("APLICAR")
            }
        }
    )
}

@Composable
fun BookCollectionsDialog(
    bookTitle: String,
    allCollections: List<CollectionWithCount>,
    initialSelectedIds: Set<Long>,
    onSave: (List<Long>) -> Unit,
    onCreateNewCollection: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedIds by remember { mutableStateOf(initialSelectedIds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Colecciones",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
            ) {
                TextButton(
                    onClick = onCreateNewCollection,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Crear nueva colección")
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(allCollections) { coll ->
                        val isChecked = selectedIds.contains(coll.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds = if (isChecked) {
                                        selectedIds - coll.id
                                    } else {
                                        selectedIds + coll.id
                                    }
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) selectedIds + coll.id else selectedIds - coll.id
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = coll.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(selectedIds.toList())
                    onDismiss()
                }
            ) {
                Text("ACEPTAR")
            }
        }
    )
}

@Composable
fun CreateCollectionDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Nueva colección",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nombre de la colección") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name.trim())
                        onDismiss()
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text("CREAR")
            }
        }
    )
}

@Composable
fun MetadataEditorDialog(
    book: Book,
    onSave: (title: String, author: String, series: String?, annotation: String?, language: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(book.title) }
    var author by remember { mutableStateOf(book.author) }
    var series by remember { mutableStateOf(book.series ?: "") }
    var annotation by remember { mutableStateOf(book.annotation ?: "") }
    var language by remember { mutableStateOf(book.language ?: "Español") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Editar metadatos",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Título") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = author,
                        onValueChange = { author = it },
                        label = { Text("Autor") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = series,
                        onValueChange = { series = it },
                        label = { Text("Series") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = annotation,
                        onValueChange = { annotation = it },
                        label = { Text("Anotación") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
                item {
                    OutlinedTextField(
                        value = language,
                        onValueChange = { language = it },
                        label = { Text("Idioma del documento") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCELAR")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        title.trim(),
                        author.trim(),
                        series.trim().ifEmpty { null },
                        annotation.trim().ifEmpty { null },
                        language.trim().ifEmpty { null }
                    )
                    onDismiss()
                }
            ) {
                Text("GUARDAR")
            }
        }
    )
}

@Composable
fun ReviewDialog(
    initialRating: Float,
    initialReview: String,
    onSave: (rating: Float, review: String) -> Unit,
    onDismiss: () -> Unit
) {
    var rating by remember { mutableStateOf(if (initialRating > 0f) initialRating else 0f) }
    var reviewText by remember { mutableStateOf(initialReview) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Evalúe este documento",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (rating > 0f) "Calificación: ${rating.toInt()} estrellas" else "Indefinido",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for (i in 1..5) {
                        IconButton(onClick = { rating = i.toFloat() }) {
                            Icon(
                                imageVector = if (i <= rating) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = "$i estrellas",
                                tint = if (i <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = reviewText,
                    onValueChange = { reviewText = it },
                    label = { Text("Escriba su reseña") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCELAR")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(rating, reviewText.trim())
                    onDismiss()
                }
            ) {
                Text("GUARDAR")
            }
        }
    )
}

@Composable
fun SendFeedbackDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Comentarios sobre ReadEra",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "Para enviar sugerencias, reportar errores o solicitar ayuda, comuníquese con:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "support@readera.org",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CERRAR")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:support@readera.org")
                        putExtra(Intent.EXTRA_SUBJECT, "Comentarios sobre ReadEra")
                    }
                    try {
                        context.startActivity(Intent.createChooser(intent, "Enviar correo"))
                    } catch (_: Exception) {}
                    onDismiss()
                }
            ) {
                Text("ESCRIBA UN EMAIL")
            }
        }
    )
}

@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmText: String = "ACEPTAR",
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = title, fontWeight = FontWeight.Bold)
        },
        text = {
            Text(text = message)
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCELAR")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = if (isDestructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
            ) {
                Text(confirmText)
            }
        }
    )
}
