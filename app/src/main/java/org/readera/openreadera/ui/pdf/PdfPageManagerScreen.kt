package org.readera.openreadera.ui.pdf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class PdfPageEditPlan(
    val pageOrder: List<Int>,
    val deletedPageIndices: Set<Int>,
    val rotations: Map<Int, Int>
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PdfPageManagerScreen(
    title: String,
    pageCount: Int,
    isSaving: Boolean,
    error: String?,
    onSave: (PdfPageEditPlan) -> Unit,
    onExtract: (List<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    val safePageCount = pageCount.coerceAtLeast(0)
    val resolvedTitle = remember(title) {
        title.trim().ifEmpty { "Documento PDF" }
    }

    var pageOrder by remember(safePageCount) {
        mutableStateOf((0 until safePageCount).toList())
    }
    var deletedPageIndices by remember(safePageCount) {
        mutableStateOf(emptySet<Int>())
    }
    var rotations by remember(safePageCount) {
        mutableStateOf(emptyMap<Int, Int>())
    }
    var selectedPageIndices by remember(safePageCount) {
        mutableStateOf(emptySet<Int>())
    }
    var showBulkDeleteConfirm by remember(safePageCount) {
        mutableStateOf(false)
    }

    val activePages = remember(pageOrder, deletedPageIndices) {
        pageOrder.filterNot { it in deletedPageIndices }
    }
    val selectedActivePages = remember(activePages, selectedPageIndices) {
        activePages.filter { it in selectedPageIndices }
    }
    val hasValidDocument = safePageCount > 0 && activePages.isNotEmpty()
    val wouldDeleteAllPages = hasValidDocument &&
        selectedActivePages.isNotEmpty() &&
        selectedActivePages.size >= activePages.size
    val canBulkDelete = hasValidDocument &&
        !isSaving &&
        selectedActivePages.isNotEmpty() &&
        selectedActivePages.size < activePages.size
    val canExtractSelected = hasValidDocument &&
        !isSaving &&
        selectedActivePages.isNotEmpty()

    val hasAnyEdits = remember(pageOrder, deletedPageIndices, rotations, safePageCount) {
        deletedPageIndices.isNotEmpty() ||
            rotations.any { (idx, deg) -> idx !in deletedPageIndices && ((deg % 360 + 360) % 360) != 0 } ||
            pageOrder != (0 until safePageCount).toList()
    }

    fun moveActivePage(visibleIndex: Int, delta: Int) {
        if (isSaving) return
        val targetVisibleIndex = visibleIndex + delta
        if (visibleIndex !in activePages.indices || targetVisibleIndex !in activePages.indices) return
        val sourceOriginalIndex = activePages[visibleIndex]
        val targetOriginalIndex = activePages[targetVisibleIndex]
        val posA = pageOrder.indexOf(sourceOriginalIndex)
        val posB = pageOrder.indexOf(targetOriginalIndex)
        if (posA < 0 || posB < 0) return

        val updatedOrder = pageOrder.toMutableList()
        updatedOrder[posA] = targetOriginalIndex
        updatedOrder[posB] = sourceOriginalIndex
        pageOrder = updatedOrder
    }

    fun rotatePage90(originalPageIndex: Int) {
        if (isSaving || originalPageIndex in deletedPageIndices) return
        val currentDegrees = rotations[originalPageIndex] ?: 0
        val nextDegrees = (currentDegrees + 90) % 360
        rotations = if (nextDegrees == 0) {
            rotations - originalPageIndex
        } else {
            rotations + (originalPageIndex to nextDegrees)
        }
    }

    fun togglePageSelection(originalPageIndex: Int) {
        if (isSaving || originalPageIndex in deletedPageIndices) return
        selectedPageIndices = if (originalPageIndex in selectedPageIndices) {
            selectedPageIndices - originalPageIndex
        } else {
            selectedPageIndices + originalPageIndex
        }
    }

    fun resetAllEdits() {
        if (isSaving) return
        pageOrder = (0 until safePageCount).toList()
        deletedPageIndices = emptySet()
        rotations = emptyMap()
        selectedPageIndices = emptySet()
    }

    fun buildCurrentPlan(): PdfPageEditPlan {
        val normalizedRotations = rotations.filter { (idx, degrees) ->
            idx in 0 until safePageCount &&
                idx !in deletedPageIndices &&
                ((degrees % 360 + 360) % 360) != 0
        }
        return PdfPageEditPlan(
            pageOrder = pageOrder,
            deletedPageIndices = deletedPageIndices,
            rotations = normalizedRotations
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Organizar páginas PDF",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = resolvedTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isSaving,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver al documento"
                        )
                    }
                },
                actions = {
                    if (hasAnyEdits && hasValidDocument) {
                        TextButton(
                            onClick = { resetAllEdits() },
                            enabled = !isSaving,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Restore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Restablecer",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 4.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = 840.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            enabled = !isSaving,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Cancelar", fontWeight = FontWeight.Medium)
                        }

                        Button(
                            onClick = {
                                if (hasValidDocument && !isSaving) {
                                    onSave(buildCurrentPlan())
                                }
                            },
                            enabled = hasValidDocument && !isSaving,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isSaving) "Guardando copia…" else "Guardar como copia PDF",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(12.dp))

                // Save-as-copy & Plan Summary Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Modo de copia segura: el archivo PDF original no se modifica",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Páginas conservadas: ${activePages.size} de $safePageCount · Eliminadas: ${deletedPageIndices.size} · Rotadas: ${rotations.count { it.key !in deletedPageIndices && it.value != 0 }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Loading banner
                if (isSaving) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Procesando y guardando la nueva copia del PDF…",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Error banner
                if (!error.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bulk selection, extraction & deletion bar
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val allSelected = hasValidDocument && selectedActivePages.size == activePages.size
                    OutlinedButton(
                        onClick = {
                            selectedPageIndices = if (allSelected) {
                                emptySet()
                            } else {
                                activePages.toSet()
                            }
                        },
                        enabled = hasValidDocument && !isSaving,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(
                            imageVector = if (allSelected) {
                                Icons.Default.CheckBoxOutlineBlank
                            } else {
                                Icons.Default.CheckBox
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (allSelected) "Limpiar selección" else "Seleccionar todas",
                            fontWeight = FontWeight.Medium
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            if (canExtractSelected) {
                                onExtract(selectedActivePages)
                            }
                        },
                        enabled = canExtractSelected,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Extraer seleccionadas (${selectedActivePages.size})",
                            fontWeight = FontWeight.Medium
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            if (canBulkDelete) {
                                showBulkDeleteConfirm = true
                            }
                        },
                        enabled = canBulkDelete,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Eliminar seleccionadas (${selectedActivePages.size})",
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (wouldDeleteAllPages) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "No se pueden eliminar todas las páginas; la copia debe conservar al menos una página.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()

                // Page List or Empty State
                if (!hasValidDocument) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Documento sin páginas editables",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Este archivo PDF no contiene páginas disponibles para organizar, rotar o extraer.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(
                            items = activePages,
                            key = { _, originalIndex -> originalIndex }
                        ) { visiblePosition, originalIndex ->
                            val isSelected = originalIndex in selectedPageIndices
                            val rotationDegrees = rotations[originalIndex] ?: 0
                            val canMoveUp = !isSaving && visiblePosition > 0
                            val canMoveDown = !isSaving && visiblePosition < activePages.lastIndex

                            PdfPageRowCard(
                                originalPageIndex = originalIndex,
                                visiblePosition = visiblePosition,
                                totalActivePages = activePages.size,
                                rotationDegrees = rotationDegrees,
                                isSelected = isSelected,
                                isSaving = isSaving,
                                canMoveUp = canMoveUp,
                                canMoveDown = canMoveDown,
                                onToggleSelect = { togglePageSelection(originalIndex) },
                                onMoveUp = { moveActivePage(visiblePosition, -1) },
                                onMoveDown = { moveActivePage(visiblePosition, 1) },
                                onRotate90 = { rotatePage90(originalIndex) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showBulkDeleteConfirm) {
        val countToDelete = selectedActivePages.size
        val remainingAfterDelete = activePages.size - countToDelete
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = "Confirmar eliminación de páginas",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "¿Deseas marcar $countToDelete página(s) seleccionada(s) para eliminarlas en la nueva copia del PDF?"
                    )
                    Text(
                        text = "Quedarán $remainingAfterDelete página(s) en el documento resultante. El archivo original no sufrirá cambios.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedActivePages.isNotEmpty() && selectedActivePages.size < activePages.size) {
                            deletedPageIndices = deletedPageIndices + selectedActivePages
                            rotations = rotations - selectedActivePages.toSet()
                            selectedPageIndices = selectedPageIndices - selectedActivePages.toSet()
                        }
                        showBulkDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Eliminar en la copia", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBulkDeleteConfirm = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun PdfPageRowCard(
    originalPageIndex: Int,
    visiblePosition: Int,
    totalActivePages: Int,
    rotationDegrees: Int,
    isSelected: Boolean,
    isSaving: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggleSelect: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRotate90: () -> Unit
) {
    val originalPageNumber = originalPageIndex + 1
    val currentPageNumber = visiblePosition + 1

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = if (isSelected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    enabled = !isSaving,
                    modifier = Modifier.semantics {
                        contentDescription = "Seleccionar página original $originalPageNumber"
                    }
                )
            }

            // Miniature Page Sheet Preview Badge
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(width = 44.dp, height = 54.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(rotationDegrees.toFloat())
                    )
                    Text(
                        text = "#$currentPageNumber",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Página original $originalPageNumber",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Posición en copia: $currentPageNumber de $totalActivePages",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (rotationDegrees != 0) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        contentColor = if (rotationDegrees != 0) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    ) {
                        Text(
                            text = "Rotación: ${rotationDegrees}°",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Subir página original $originalPageNumber"
                    )
                }

                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Bajar página original $originalPageNumber"
                    )
                }

                IconButton(
                    onClick = onRotate90,
                    enabled = !isSaving,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.RotateRight,
                        contentDescription = "Rotar 90 grados la página original $originalPageNumber"
                    )
                }
            }
        }
    }
}
