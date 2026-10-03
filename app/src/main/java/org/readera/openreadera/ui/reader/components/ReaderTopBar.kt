package org.readera.openreadera.ui.reader.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    title: String,
    author: String,
    currentPage: Int,
    totalPages: Int,
    isBookmarked: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onAddQuote: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenSearch: () -> Unit,
    onStartTts: () -> Unit,
    canStartPresentation: Boolean,
    onStartPresentation: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReview: () -> Unit,
    onShareFile: () -> Unit,
    onAddToCollections: () -> Unit,
    onMoveToTrash: () -> Unit,
    onOpenDocumentDetails: () -> Unit,
    onOpenPdfAssistant: () -> Unit = {},
    onManagePdfPages: () -> Unit = {},
    onCreateSearchableCopy: () -> Unit = {},
    onExportPdfImages: () -> Unit = {},
    onExportPdfText: () -> Unit = {},
    onSignPdf: () -> Unit,
    isPdf: Boolean = false,
    onOpenTextSelector: () -> Unit = {},
    onOpenTranslate: () -> Unit = {},
    pageQuotesCount: Int = 0,
    isOled: Boolean = false,
    isWritingMode: Boolean = false,
    onWritingModeToggle: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showMoreMenu by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (isOled) Color(0xFF000000) else MaterialTheme.colorScheme.surface,
        tonalElevation = if (isOled) 0.dp else 4.dp
    ) {
        val isPhone = LocalConfiguration.current.screenWidthDp < 600

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Salir de la lectura"
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$author • Pág. ${currentPage + 1} de $totalPages",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!isPhone) {
                IconButton(onClick = onStartTts) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Leer en voz alta"
                    )
                }
            }

            IconButton(onClick = onOpenSearch) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Búsqueda de texto"
                )
            }

            if (!isPhone) {
            IconButton(onClick = onOpenTranslate) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = "Traducir libro / paper",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(onClick = onAddQuote) {
                BadgedBox(
                    badge = {
                        if (pageQuotesCount > 0) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Text("$pageQuotesCount")
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.EditNote,
                        contentDescription = "Añadir cita o nota",
                        tint = if (pageQuotesCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            IconButton(onClick = onOpenTextSelector) {
                Icon(
                    imageVector = Icons.Default.Highlight,
                    contentDescription = "Remarcar texto",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            }

            IconButton(onClick = onToggleBookmark) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = "Señalador",
                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(onClick = onOpenToc) {
                Icon(
                    imageVector = Icons.Default.FormatListBulleted,
                    contentDescription = "Contenidos / Señaladores / Citas"
                )
            }

            if (!isPhone) {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Configuraciones de lectura"
                    )
                }
            }

            // Herramientas de escritura (modo escritura persistente)
            if (!isPhone) {
                IconButton(
                    onClick = onWritingModeToggle,
                    modifier = Modifier.semantics {
                        role = Role.Button
                        selected = isWritingMode
                        contentDescription = "Herramientas de escritura"
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Draw,
                        contentDescription = "Herramientas de escritura",
                        tint = if (isWritingMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Más opciones (menú 3 puntos)
            Box {
                IconButton(
                    onClick = {
                        showMoreMenu = !showMoreMenu
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Más opciones",
                        tint = if (showMoreMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }

                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false }
                ) {
                    if (isPhone) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                            text = { Text("Configuración de lectura") },
                            onClick = {
                                showMoreMenu = false
                                onOpenSettings()
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.VolumeUp, contentDescription = null) },
                            text = { Text("Leer en voz alta") },
                            onClick = {
                                showMoreMenu = false
                                onStartTts()
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Draw,
                                    contentDescription = null,
                                    tint = if (isWritingMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            text = {
                                Text(
                                    text = "Herramientas de escritura",
                                    color = if (isWritingMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isWritingMode) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                showMoreMenu = false
                                onWritingModeToggle()
                            },
                            modifier = Modifier.semantics {
                                role = Role.Button
                                selected = isWritingMode
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Highlight, contentDescription = null) },
                            text = { Text("Remarcar texto") },
                            onClick = {
                                showMoreMenu = false
                                onOpenTextSelector()
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null) },
                            text = { Text("Traducir libro / paper") },
                            onClick = {
                                showMoreMenu = false
                                onOpenTranslate()
                            }
                        )
                    }
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.EditNote, contentDescription = null) },
                        text = { Text("Añadir cita o nota") },
                        onClick = {
                            showMoreMenu = false
                            onAddQuote()
                        }
                    )
                    if (isPdf) {
                        DropdownMenuItem(text = { Text("Asistente de IA") }, onClick = { showMoreMenu = false; onOpenPdfAssistant() })
                        DropdownMenuItem(text = { Text("Gestionar páginas") }, onClick = { showMoreMenu = false; onManagePdfPages() })
                        DropdownMenuItem(text = { Text("Firmar PDF") }, onClick = { showMoreMenu = false; onSignPdf() })
                        DropdownMenuItem(text = { Text("Crear copia con OCR") }, onClick = { showMoreMenu = false; onCreateSearchableCopy() })
                        DropdownMenuItem(text = { Text("Exportar imágenes (ZIP)") }, onClick = { showMoreMenu = false; onExportPdfImages() })
                        DropdownMenuItem(text = { Text("Exportar texto") }, onClick = { showMoreMenu = false; onExportPdfText() })
                    }
                    if (canStartPresentation) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Slideshow, contentDescription = null) },
                            text = { Text("Iniciar presentación") },
                            onClick = {
                                showMoreMenu = false
                                onStartPresentation()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Mi reseña") },
                        onClick = {
                            showMoreMenu = false
                            onOpenReview()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Compartir archivo") },
                        onClick = {
                            showMoreMenu = false
                            onShareFile()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Agregar a ...") },
                        onClick = {
                            showMoreMenu = false
                            onAddToCollections()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Enviar a la Papelera") },
                        onClick = {
                            showMoreMenu = false
                            onMoveToTrash()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Acerca del documento") },
                        onClick = {
                            showMoreMenu = false
                            onOpenDocumentDetails()
                        }
                    )
                }

            }
        }
    }
}
