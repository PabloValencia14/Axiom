package org.readera.openreadera.ratings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.readera.openreadera.data.model.Book
import java.net.URLEncoder

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookReviewDialog(
    book: Book,
    onSaveReview: (rating: Float?, review: String?, tags: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var rating by remember { mutableFloatStateOf(book.rating ?: 0f) }
    var reviewText by remember { mutableStateOf(book.review ?: "") }

    val initialTags = remember {
        book.reviewTags?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }
    var selectedTags by remember { mutableStateOf(initialTags) }

    val availableTags = listOf("Obra maestra", "Favorito", "Relectura", "Imprescindible", "Recomendado", "Entretenido", "No ficción")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Valorar y criticar documento", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(book.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Star rating selector
                Text("Tu puntuación:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 1..5) {
                        val isFull = rating >= i
                        val isHalf = rating >= i - 0.5f && rating < i

                        IconButton(
                            onClick = {
                                rating = if (rating == i.toFloat()) (i - 0.5f) else i.toFloat()
                            },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = if (isFull) Icons.Default.Star else if (isHalf) Icons.Default.StarHalf else Icons.Default.StarBorder,
                                contentDescription = "$i estrellas",
                                tint = if (isFull || isHalf) Color(0xFFFFB300) else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }

                if (rating > 0f) {
                    Text(
                        text = "$rating de 5.0 estrellas",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFB300),
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Tags selection
                Text("Etiquetas:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for (tag in availableTags) {
                        val isSelected = selectedTags.contains(tag)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedTags = if (isSelected) selectedTags - tag else selectedTags + tag
                            },
                            label = { Text(tag) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Review field
                OutlinedTextField(
                    value = reviewText,
                    onValueChange = { reviewText = it },
                    label = { Text("Tu crítica o reseña personal") },
                    placeholder = { Text("Escribe qué te pareció el libro, ideas clave, citas favoritas...") },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    maxLines = 6
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Community links
                Text("Comunidad y opiniones externas:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val enc = URLEncoder.encode(book.title, "UTF-8")
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://hardcover.app/search?q=$enc"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Hardcover", style = MaterialTheme.typography.labelSmall)
                    }

                    FilledTonalButton(
                        onClick = {
                            val enc = URLEncoder.encode("${book.title} ${book.author}", "UTF-8")
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.goodreads.com/search?q=$enc"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Goodreads", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val tagString = if (selectedTags.isNotEmpty()) selectedTags.joinToString(",") else null
                    onSaveReview(if (rating > 0f) rating else null, reviewText.ifBlank { null }, tagString)
                    onDismiss()
                }
            ) {
                Text("Guardar crítica")
            }
        },
        dismissButton = {
            Row {
                if ((book.rating ?: 0f) > 0f || !book.review.isNullOrBlank()) {
                    TextButton(
                        onClick = {
                            onSaveReview(null, null, null)
                            onDismiss()
                        }
                    ) {
                        Text("Eliminar", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancelar")
                }
            }
        }
    )
}
