package org.readera.openreadera.ui.journal

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.ratings.BookReviewDialog
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingJournalScreen(
    books: List<Book>,
    onOpenBook: (Book) -> Unit,
    onUpdateReview: (Book, Float?, String?, String?) -> Unit
) {
    val context = LocalContext.current
    val reviewedBooks = remember(books) {
        books.filter { (it.rating ?: 0f) > 0f || !it.review.isNullOrBlank() }
            .sortedByDescending { it.reviewDate ?: it.lastOpened }
    }

    var selectedFilter by remember { mutableStateOf("Todos") }
    var editingBook by remember { mutableStateOf<Book?>(null) }

    val filteredBooks = remember(reviewedBooks, selectedFilter) {
        when (selectedFilter) {
            "5 Estrellas" -> reviewedBooks.filter { (it.rating ?: 0f) >= 4.7f }
            "4+ Estrellas" -> reviewedBooks.filter { (it.rating ?: 0f) >= 3.7f }
            "Con crítica" -> reviewedBooks.filter { !it.review.isNullOrBlank() }
            else -> reviewedBooks
        }
    }

    // Histogram calculations
    val totalCount = reviewedBooks.size
    val averageScore = if (totalCount > 0) {
        reviewedBooks.map { it.rating ?: 0f }.filter { it > 0f }.average()
    } else 0.0

    val starCounts = remember(reviewedBooks) {
        val counts = IntArray(5) // 1 to 5 stars
        for (b in reviewedBooks) {
            val r = (b.rating ?: 0f).toInt().coerceIn(1, 5)
            counts[r - 1]++
        }
        counts
    }
    val maxStarCount = (starCounts.maxOrNull() ?: 1).coerceAtLeast(1)

    val dateFormat = remember { SimpleDateFormat("d 'de' MMMM, yyyy", Locale("es", "ES")) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Diario de Lecturas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Tus valoraciones, estadísticas y críticas personales al estilo Letterboxd.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val isPhone = LocalConfiguration.current.screenWidthDp < 600

        Spacer(modifier = Modifier.height(16.dp))

        // Letterboxd Profile Statistics Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isPhone) {
                // Mobile: Stacked stats and histogram
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$totalCount", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                            Text("Libros valorados", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (averageScore > 0) String.format(Locale.US, "%.1f", averageScore) else "-",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(" / 5.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text("Nota media global", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Text("Distribución de valoraciones", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    for (star in 5 downTo 1) {
                        val count = starCounts[star - 1]
                        val fraction = count.toFloat() / maxStarCount.toFloat()

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("$star★", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(26.dp), color = Color(0xFFFFB300), fontWeight = FontWeight.Bold)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(fraction)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFFFFB300))
                                )
                            }
                            Text("$count", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(28.dp).padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Summary Stats
                    Column(modifier = Modifier.width(180.dp)) {
                        Text("$totalCount", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                        Text("Libros valorados", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (averageScore > 0) String.format(Locale.US, "%.1f", averageScore) else "-",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(" / 5.0", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                        }
                        Text("Nota media global", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    VerticalDivider(modifier = Modifier.height(100.dp).padding(horizontal = 16.dp))

                    // Right: Letterboxd Histogram
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Distribución de valoraciones", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))

                        for (star in 5 downTo 1) {
                            val count = starCounts[star - 1]
                            val fraction = count.toFloat() / maxStarCount.toFloat()

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("$star★", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(26.dp), color = Color(0xFFFFB300), fontWeight = FontWeight.Bold)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(fraction)
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(Color(0xFFFFB300))
                                    )
                                }
                                Text("$count", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(28.dp).padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Filter chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(listOf("Todos", "5 Estrellas", "4+ Estrellas", "Con crítica")) { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredBooks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.RateReview, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No hay libros valorados aún.", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Abre cualquier libro, pulsa '★ Evaluar' en sus detalles y escribe tu crítica.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = if (isPhone) GridCells.Fixed(1) else GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredBooks, key = { it.id }) { book ->
                    JournalBookCard(
                        book = book,
                        dateFormat = dateFormat,
                        onOpenBook = { onOpenBook(book) },
                        onEditReview = { editingBook = book },
                        onViewHardcover = {
                            val enc = URLEncoder.encode(book.title, "UTF-8")
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://hardcover.app/search?q=$enc"))
                            context.startActivity(intent)
                        },
                        onViewGoodreads = {
                            val enc = URLEncoder.encode("${book.title} ${book.author}", "UTF-8")
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.goodreads.com/search?q=$enc"))
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }

        // Review Dialog when editing from Journal
        editingBook?.let { book ->
            BookReviewDialog(
                book = book,
                onSaveReview = { r, rev, tags ->
                    onUpdateReview(book, r, rev, tags)
                    editingBook = null
                },
                onDismiss = { editingBook = null }
            )
        }
    }
}

@Composable
fun JournalBookCard(
    book: Book,
    dateFormat: SimpleDateFormat,
    onOpenBook: () -> Unit,
    onEditReview: () -> Unit,
    onViewHardcover: () -> Unit,
    onViewGoodreads: () -> Unit
) {
    val isPhone = LocalConfiguration.current.screenWidthDp < 600
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isPhone) Modifier.wrapContentHeight() else Modifier.height(260.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Cover
                Box(
                    modifier = Modifier
                        .width(70.dp)
                        .height(100.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!book.coverPath.isNullOrEmpty()) {
                        AsyncImage(
                            model = book.coverPath,
                            contentDescription = book.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Default.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Stars row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val rating = book.rating ?: 0f
                        for (i in 1..5) {
                            val isFull = rating >= i
                            val isHalf = rating >= i - 0.5f && rating < i
                            Icon(
                                imageVector = if (isFull) Icons.Default.Star else if (isHalf) Icons.Default.StarHalf else Icons.Default.StarBorder,
                                contentDescription = null,
                                tint = if (isFull || isHalf) Color(0xFFFFB300) else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = String.format(Locale.US, "%.1f", rating),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFB300)
                        )
                    }

                    // Tags
                    book.reviewTags?.let { tags ->
                        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            tags.split(",").take(2).forEach { t ->
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = t.trim(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Review text snippet
            if (!book.review.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    Text(
                        text = "“${book.review}”",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Bottom action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if ((book.reviewDate ?: 0L) > 0) dateFormat.format(Date(book.reviewDate!!)) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = onViewHardcover, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.OpenInNew, contentDescription = "Hardcover", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onEditReview, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Editar", modifier = Modifier.size(16.dp))
                    }
                    FilledTonalButton(
                        onClick = onOpenBook,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("LEER", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
