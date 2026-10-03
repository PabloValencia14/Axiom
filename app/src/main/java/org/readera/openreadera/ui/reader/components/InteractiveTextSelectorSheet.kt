package org.readera.openreadera.ui.reader.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteractiveTextSelectorSheet(
    isVisible: Boolean,
    pageText: String,
    page: Int,
    bookTitle: String,
    bookAuthor: String,
    onSaveQuote: (text: String, note: String, colorHex: String) -> Unit,
    onPlayTts: (String) -> Unit,
    onDismiss: () -> Unit,
    textStatus: String? = null
) {
    if (!isVisible) return

    var selectedSnippet by remember(page, pageText) { mutableStateOf("") }
    var showNoteDialog by remember { mutableStateOf(false) }
    var showManualNoteDialog by remember { mutableStateOf(false) }
    var showQuoteCardDialog by remember { mutableStateOf(false) }
    var showDictDialog by remember { mutableStateOf(false) }
    var dictInitialTab by remember { mutableIntStateOf(0) }
    var activeDictTerm by remember { mutableStateOf("") }

    val paragraphs = remember(pageText) {
        if (pageText.isBlank()) emptyList()
        else {
            val doubleBreak = pageText.split(Regex("""\r?\n\s*\r?\n"""))
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (doubleBreak.size > 1) {
                doubleBreak
            } else {
                val singleBreak = pageText.split(Regex("""\r?\n"""))
                    .map { it.trim() }
                    .filter { it.length > 20 }
                if (singleBreak.size > 1) singleBreak
                else listOf(pageText.trim())
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.fillMaxHeight(0.85f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Remarcado y Citas (Pág. ${page + 1})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Toca un fragmento para resaltar o escribe una nota",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = { showManualNoteDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Nota manual", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }
            }
            if (paragraphs.isNotEmpty() && !textStatus.isNullOrBlank()) {
                Text(textStatus, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Floating action bar if a snippet or paragraph is selected
            if (selectedSnippet.isNotBlank()) {
                TextHighlightSelectionBar(
                    isVisible = true,
                    selectedText = selectedSnippet,
                    bookTitle = bookTitle,
                    bookAuthor = bookAuthor,
                    page = page,
                    onHighlightColor = { colorHex ->
                        onSaveQuote(selectedSnippet, "", colorHex)
                        selectedSnippet = ""
                    },
                    onAddNote = {
                        showNoteDialog = true
                    },
                    onPlayTts = onPlayTts,
                    onCreateQuoteCard = {
                        showQuoteCardDialog = true
                    },
                    onShowDictionary = { text ->
                        activeDictTerm = text
                        dictInitialTab = 0
                        showDictDialog = true
                    },
                    onTranslate = { text ->
                        activeDictTerm = text
                        dictInitialTab = 1
                        showDictDialog = true
                    },
                    onDismiss = { selectedSnippet = "" }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Text paragraphs list
            if (paragraphs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notes,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = textStatus ?: "No se detectó texto seleccionable en esta página.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { showManualNoteDialog = true }) {
                            Icon(Icons.Default.EditNote, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Añadir nota manual para esta página")
                        }
                    }
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        itemsIndexed(paragraphs) { _, paragraph ->
                            val isSelected = selectedSnippet == paragraph
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedSnippet = if (isSelected) "" else paragraph
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FormatQuote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = paragraph,
                                        style = MaterialTheme.typography.bodyMedium,
                                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNoteDialog) {
        AddQuoteDialog(
            initialText = selectedSnippet,
            page = page,
            onDismiss = { showNoteDialog = false },
            onSaveQuote = { text, note, colorHex ->
                onSaveQuote(text, note, colorHex)
                showNoteDialog = false
                selectedSnippet = ""
            }
        )
    }

    if (showManualNoteDialog) {
        AddQuoteDialog(
            initialText = "",
            page = page,
            onDismiss = { showManualNoteDialog = false },
            onSaveQuote = { text, note, colorHex ->
                onSaveQuote(text, note, colorHex)
                showManualNoteDialog = false
            }
        )
    }

    if (showQuoteCardDialog) {
        QuoteCardDialog(
            quoteText = selectedSnippet,
            bookTitle = bookTitle,
            bookAuthor = bookAuthor,
            page = page,
            onDismiss = { showQuoteCardDialog = false }
        )
    }

    if (showDictDialog) {
        DictionaryTranslationDialog(
            term = activeDictTerm,
            initialTab = dictInitialTab,
            onPlayTts = onPlayTts,
            onDismiss = { showDictDialog = false }
        )
    }
}
