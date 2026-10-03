package org.readera.openreadera.ui.reader.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import org.readera.openreadera.data.model.Bookmark
import org.readera.openreadera.data.model.Quote
import org.readera.openreadera.engine.OutlineItem
import org.readera.openreadera.ui.library.components.ConfirmationDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocBookmarksSheet(
    outline: List<OutlineItem>,
    bookmarks: List<Bookmark>,
    quotes: List<Quote>,
    currentPage: Int,
    onDismiss: () -> Unit,
    onNavigateToPage: (Int) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onDeleteQuote: (Quote) -> Unit,
    onUpdateQuote: (Quote) -> Unit = {},
    onAddQuoteOnPage: (() -> Unit)? = null,
    onDeleteAllBookmarks: () -> Unit,
    onDeleteAllQuotes: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showConfirmDeleteAllDialog by remember { mutableStateOf(false) }

    var quoteToEdit by remember { mutableStateOf<Quote?>(null) }
    var filterOnlyCurrentPage by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.weight(1f)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("CONTENIDOS", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("SEÑALADORES (${bookmarks.size})", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("CITAS (${quotes.size})", fontWeight = FontWeight.Bold) }
                    )
                }

                if (selectedTab > 0) {
                    Box {
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Opciones")
                        }
                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false }
                        ) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                text = { Text("Compartir todo") },
                                onClick = {
                                    showOptionsMenu = false
                                    val textToShare = if (selectedTab == 1) {
                                        bookmarks.joinToString("\n\n") { "Pág. ${it.page + 1}: ${it.title} - ${it.snippet}" }
                                    } else {
                                        quotes.joinToString("\n\n") { "Pág. ${it.page + 1}: \"${it.text}\" ${if (it.note.isNotBlank()) "(${it.note})" else ""}" }
                                    }
                                    if (textToShare.isNotBlank()) {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, textToShare)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Compartir"))
                                    }
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                text = { Text("Eliminar todo", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showOptionsMenu = false
                                    showConfirmDeleteAllDialog = true
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when (selectedTab) {
                0 -> {
                    if (outline.isEmpty()) {
                        EmptyTabMessage("Este documento no contiene tabla de contenidos.")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(outline) { item ->
                                val isCurrent = item.page == currentPage
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onNavigateToPage(item.page)
                                            onDismiss()
                                        }
                                        .padding(vertical = 12.dp, horizontal = (item.level * 16).dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${item.page + 1}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                HorizontalDivider(thickness = 0.5.dp)
                            }
                        }
                    }
                }
                1 -> {
                    if (bookmarks.isEmpty()) {
                        EmptyTabMessage("Para agregar un señalador, toca el icono de marcador en la barra superior o la esquina de la página.")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(bookmarks) { bookmark ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            onNavigateToPage(bookmark.page)
                                            onDismiss()
                                        },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .padding(12.dp)
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = bookmark.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (bookmark.snippet.isNotBlank()) {
                                                Text(
                                                    text = bookmark.snippet,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Text(
                                            text = "Pág. ${bookmark.page + 1}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                        IconButton(onClick = { onDeleteBookmark(bookmark) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Eliminar marcador")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    val pageQuotes = remember(quotes, currentPage) { quotes.filter { it.page == currentPage } }
                    val displayQuotes = if (filterOnlyCurrentPage) pageQuotes else quotes

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Filter chips and Add Quote action
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !filterOnlyCurrentPage,
                                    onClick = { filterOnlyCurrentPage = false },
                                    label = { Text("Todas (${quotes.size})") }
                                )
                                FilterChip(
                                    selected = filterOnlyCurrentPage,
                                    onClick = { filterOnlyCurrentPage = true },
                                    label = { Text("Esta pág. (${pageQuotes.size})") }
                                )
                            }

                            if (onAddQuoteOnPage != null) {
                                FilledTonalButton(
                                    onClick = {
                                        onDismiss()
                                        onAddQuoteOnPage()
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Añadir nota", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        if (displayQuotes.isEmpty()) {
                            val emptyMsg = if (filterOnlyCurrentPage)
                                "No hay citas ni notas en la página ${currentPage + 1}."
                            else
                                "Todas las citas y notas agregadas estarán aquí."
                            EmptyTabMessage(emptyMsg)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 24.dp)
                            ) {
                                items(displayQuotes) { quote ->
                                    val quoteColor = try {
                                        Color(android.graphics.Color.parseColor(quote.colorHex))
                                    } catch (_: Exception) {
                                        MaterialTheme.colorScheme.primary
                                    }

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onNavigateToPage(quote.page)
                                                onDismiss()
                                            },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = quoteColor.copy(alpha = 0.12f)
                                        )
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(14.dp)
                                                        .clip(CircleShape)
                                                        .background(quoteColor)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Página ${quote.page + 1}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.weight(1f)
                                                )

                                                // Edit button
                                                IconButton(
                                                    onClick = { quoteToEdit = quote },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Edit,
                                                        contentDescription = "Editar nota",
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }

                                                // Copy button
                                                IconButton(
                                                    onClick = {
                                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                        val fullText = buildString {
                                                            append("«${quote.text}»")
                                                            if (quote.note.isNotBlank()) {
                                                                append("\nNota: ${quote.note}")
                                                            }
                                                            append("\n(Pág. ${quote.page + 1})")
                                                        }
                                                        clipboard.setPrimaryClip(ClipData.newPlainText("Cita", fullText))
                                                        Toast.makeText(context, "Cita copiada", Toast.LENGTH_SHORT).show()
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.ContentCopy,
                                                        contentDescription = "Copiar cita",
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }

                                                // Delete button
                                                IconButton(
                                                    onClick = { onDeleteQuote(quote) },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = "Eliminar cita",
                                                        modifier = Modifier.size(18.dp),
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(6.dp))

                                            Text(
                                                text = "“${quote.text}”",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                            )

                                            if (quote.note.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Comment,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(14.dp),
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = quote.note,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontWeight = FontWeight.Medium
                                                        )
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
            }
        }
    }

    if (quoteToEdit != null) {
        AddQuoteDialog(
            quoteToEdit = quoteToEdit,
            page = quoteToEdit!!.page,
            onDismiss = { quoteToEdit = null },
            onSaveQuote = { text, note, colorHex ->
                onUpdateQuote(quoteToEdit!!.copy(text = text, note = note, colorHex = colorHex))
                quoteToEdit = null
            }
        )
    }

    if (showConfirmDeleteAllDialog) {
        val itemName = if (selectedTab == 1) "los señaladores" else "las citas"
        ConfirmationDialog(
            title = "Eliminar todo",
            message = "¿Está seguro de que desea eliminar todos $itemName de este documento?",
            confirmText = "ELIMINAR TODO",
            isDestructive = true,
            onConfirm = {
                if (selectedTab == 1) onDeleteAllBookmarks() else onDeleteAllQuotes()
            },
            onDismiss = { showConfirmDeleteAllDialog = false }
        )
    }
}

@Composable
fun EmptyTabMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
