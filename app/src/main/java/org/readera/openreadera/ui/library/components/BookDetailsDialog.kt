package org.readera.openreadera.ui.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.readera.openreadera.data.model.Book
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import org.readera.openreadera.data.model.BookStatus

@Composable
fun BookDetailsDialog(
    book: Book,
    onDismiss: () -> Unit,
    onOpenBook: () -> Unit,
    onRemoveFromReadingNow: (() -> Unit)? = null,
    onMarkAsReading: (() -> Unit)? = null
) {
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    val formattedSize = if (book.fileSize > 1024 * 1024) {
        String.format(Locale.getDefault(), "%.2f MB", book.fileSize / (1024.0 * 1024.0))
    } else {
        String.format(Locale.getDefault(), "%.1f KB", book.fileSize / 1024.0)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Información del libro",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    // Thumbnail
                    Box(
                        modifier = Modifier
                            .size(width = 80.dp, height = 115.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (book.coverPath != null && File(book.coverPath).exists()) {
                            AsyncImage(
                                model = book.coverPath,
                                contentDescription = book.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = book.author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = book.format.uppercase(),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                DetailRow(label = "Progreso", value = "${book.progressPercent.toInt()}% (pág. ${book.currentPage + 1} de ${book.totalPages})")
                DetailRow(label = "Tamaño", value = formattedSize)
                DetailRow(label = "Ruta", value = book.filePath)
                DetailRow(label = "Añadido", value = dateFormat.format(Date(book.dateAdded)))
                if (book.lastOpened > 0) {
                    DetailRow(label = "Última lectura", value = dateFormat.format(Date(book.lastOpened)))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                onOpenBook()
            }) {
                Text("Abrir libro")
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (book.status == BookStatus.READING && onRemoveFromReadingNow != null) {
                    TextButton(onClick = {
                        onRemoveFromReadingNow()
                        onDismiss()
                    }) {
                        Text("Quitar de Leyendo", color = MaterialTheme.colorScheme.error)
                    }
                } else if (book.status != BookStatus.READING && onMarkAsReading != null) {
                    TextButton(onClick = {
                        onMarkAsReading()
                        onDismiss()
                    }) {
                        Text("Añadir a Leyendo")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cerrar")
                }
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
