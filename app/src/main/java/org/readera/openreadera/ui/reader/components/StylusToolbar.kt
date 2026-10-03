package org.readera.openreadera.ui.reader.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Compact floating writing panel displayed over the reading page when writing mode is active.
 * Concepts-inspired instrument strip hosting 5 distinct tool icons, contextual stroke width samples,
 * color swatches, nonmodal history actions (Deshacer, Rehacer, Borrar), guarded clear-confirm dialog,
 * OCR/text status indicator, and explicit close button.
 * All visible controls in the bar are glyphs, stroke samples, and color discs ONLY (no visible text labels).
 */
@Composable
fun ReaderWritingPanel(
    currentTool: DrawingTool,
    currentColorHex: String,
    currentStrokeWidth: Float,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    canClear: Boolean = false,
    onToolSelected: (DrawingTool) -> Unit,
    onColorSelected: (String) -> Unit,
    onStrokeWidthSelected: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit = {},
    onClearPage: () -> Unit,
    onClose: () -> Unit,
    textStatus: String? = null,
    modifier: Modifier = Modifier
) {
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val colors = remember {
        listOf(
            "#FACC15" to "Amarillo",
            "#4ADE80" to "Verde",
            "#60A5FA" to "Azul",
            "#F87171" to "Coral",
            "#FB923C" to "Naranja",
            "#18181B" to "Negro"
        )
    }

    val widths = remember(currentTool) {
        when (currentTool) {
            DrawingTool.PEN -> listOf(
                2f to "Fino",
                4f to "Medio",
                8f to "Grueso"
            )
            DrawingTool.MARKER -> listOf(
                6f to "Fino",
                12f to "Medio",
                20f to "Grueso"
            )
            DrawingTool.HIGHLIGHTER, DrawingTool.FREE_HIGHLIGHTER -> listOf(
                12f to "Fino",
                20f to "Medio",
                32f to "Ancho"
            )
            DrawingTool.ERASER -> emptyList()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { /* consume taps on the panel boundary to prevent ink gestures underneath */ }
            }
    ) {
        val maxBarWidth = maxWidth
        val isWide = maxBarWidth >= 760.dp

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            if (isWide) {
                // Wide single-row compact capsule bar
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Group 1: 5 tool chips
                    InstrumentIconChips(
                        currentTool = currentTool,
                        onToolSelected = onToolSelected
                    )

                    VerticalBarDivider()

                    // Groups 2 & 3: Width samples and Colors (hidden when currentTool == ERASER)
                    if (currentTool != DrawingTool.ERASER) {
                        StrokeWidthSwatches(
                            currentTool = currentTool,
                            widths = widths,
                            currentStrokeWidth = currentStrokeWidth,
                            currentColorHex = currentColorHex,
                            onStrokeWidthSelected = onStrokeWidthSelected
                        )

                        VerticalBarDivider()

                        ColorDiscSwatches(
                            colors = colors,
                            currentColorHex = currentColorHex,
                            onColorSelected = onColorSelected
                        )

                        VerticalBarDivider()
                    }

                    // Group 4: History Actions (Undo, Redo, Clear)
                    HistoryActionButtons(
                        canUndo = canUndo,
                        canRedo = canRedo,
                        canClear = canClear,
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onClearRequest = { showClearConfirmDialog = true }
                    )

                    // Group 5: OCR status indicator (if textStatus present)
                    if (!textStatus.isNullOrBlank()) {
                        VerticalBarDivider()
                        OcrStatusIcon(textStatus = textStatus)
                    }

                    VerticalBarDivider()

                    // Group 6: Close button
                    CloseButton(onClose = onClose)
                }
            } else {
                // Narrow / Compact layout (<= 144dp height, 2 rows with horizontal scroll if needed)
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Row 1: Tools + History + Status (scrollable) + Pinned Close Button (ALWAYS visible)
                    Row(
                        modifier = Modifier.widthIn(max = maxBarWidth),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            InstrumentIconChips(
                                currentTool = currentTool,
                                onToolSelected = onToolSelected
                            )

                            VerticalBarDivider()

                            HistoryActionButtons(
                                canUndo = canUndo,
                                canRedo = canRedo,
                                canClear = canClear,
                                onUndo = onUndo,
                                onRedo = onRedo,
                                onClearRequest = { showClearConfirmDialog = true }
                            )

                            if (!textStatus.isNullOrBlank()) {
                                VerticalBarDivider()
                                OcrStatusIcon(textStatus = textStatus)
                            }
                        }

                        VerticalBarDivider()

                        CloseButton(onClose = onClose)
                    }

                    // Row 2: Widths + Colors (only when currentTool != ERASER)
                    if (currentTool != DrawingTool.ERASER) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            StrokeWidthSwatches(
                                currentTool = currentTool,
                                widths = widths,
                                currentStrokeWidth = currentStrokeWidth,
                                currentColorHex = currentColorHex,
                                onStrokeWidthSelected = onStrokeWidthSelected
                            )

                            VerticalBarDivider()

                            ColorDiscSwatches(
                                colors = colors,
                                currentColorHex = currentColorHex,
                                onColorSelected = onColorSelected
                            )
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog for clearing page strokes
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = {
                Text(
                    text = "¿Borrar trazos?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Se borrarán los trazos de la página activa.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmDialog = false
                        onClearPage()
                    }
                ) {
                    Text(
                        text = "Borrar",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirmDialog = false }
                ) {
                    Text(
                        text = "Cancelar",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

/**
 * Reusable ink controls component.
 * Provides the same compact floating instrument strip.
 */
@Composable
fun ReaderInkControls(
    currentTool: DrawingTool,
    currentColorHex: String,
    currentStrokeWidth: Float,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    canClear: Boolean = false,
    onToolSelected: (DrawingTool) -> Unit,
    onColorSelected: (String) -> Unit,
    onStrokeWidthSelected: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit = {},
    onClearPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    ReaderWritingPanel(
        currentTool = currentTool,
        currentColorHex = currentColorHex,
        currentStrokeWidth = currentStrokeWidth,
        canUndo = canUndo,
        canRedo = canRedo,
        canClear = canClear,
        onToolSelected = onToolSelected,
        onColorSelected = onColorSelected,
        onStrokeWidthSelected = onStrokeWidthSelected,
        onUndo = onUndo,
        onRedo = onRedo,
        onClearPage = onClearPage,
        onClose = {},
        textStatus = null,
        modifier = modifier
    )
}

@Composable
private fun InstrumentIconChips(
    currentTool: DrawingTool,
    onToolSelected: (DrawingTool) -> Unit
) {
    val instruments = remember {
        listOf(
            DrawingTool.PEN to ("Pluma" to Icons.Default.Edit),
            DrawingTool.MARKER to ("Rotulador" to Icons.Default.Brush),
            DrawingTool.FREE_HIGHLIGHTER to ("Resaltador libre" to Icons.Default.Draw),
            DrawingTool.HIGHLIGHTER to ("Resaltar texto" to Icons.Default.Highlight),
            DrawingTool.ERASER to ("Goma" to Icons.Default.AutoFixHigh)
        )
    }

    instruments.forEach { (tool, pair) ->
        val (label, icon) = pair
        val isSelected = currentTool == tool
        val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else Color.Transparent
        val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
            else Color.Transparent

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(containerColor)
                .then(
                    if (isSelected) Modifier.border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
                    else Modifier
                )
                .semantics {
                    role = Role.RadioButton
                    selected = isSelected
                    contentDescription = label
                }
                .clickable(
                    role = Role.RadioButton,
                    onClickLabel = "Seleccionar $label"
                ) { onToolSelected(tool) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun StrokeWidthSwatches(
    currentTool: DrawingTool,
    widths: List<Pair<Float, String>>,
    currentStrokeWidth: Float,
    currentColorHex: String,
    onStrokeWidthSelected: (Float) -> Unit
) {
    val strokeColor = remember(currentColorHex, currentTool) {
        val parsed = try {
            Color(android.graphics.Color.parseColor(currentColorHex))
        } catch (_: Exception) {
            Color(0xFFFACC15)
        }
        if (currentTool == DrawingTool.HIGHLIGHTER || currentTool == DrawingTool.FREE_HIGHLIGHTER) {
            parsed.copy(alpha = 0.65f)
        } else {
            parsed
        }
    }

    widths.forEach { (widthValue, label) ->
        val isSelected = abs(currentStrokeWidth - widthValue) < 0.5f
        val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
        val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

        val sampleHeight = when (currentTool) {
            DrawingTool.PEN -> when (widthValue) {
                2f -> 2.dp
                4f -> 4.dp
                else -> 7.dp
            }
            DrawingTool.MARKER -> when (widthValue) {
                6f -> 3.5.dp
                12f -> 6.5.dp
                else -> 10.dp
            }
            else -> when (widthValue) {
                12f -> 5.dp
                20f -> 8.5.dp
                else -> 13.dp
            }
        }

        Box(
            modifier = Modifier
                .width(38.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(containerColor)
                .border(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(8.dp)
                )
                .semantics {
                    role = Role.RadioButton
                    selected = isSelected
                    contentDescription = "Grosor $label"
                }
                .clickable(
                    role = Role.RadioButton,
                    onClickLabel = "Seleccionar grosor $label"
                ) { onStrokeWidthSelected(widthValue) },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(22.dp)
                    .height(sampleHeight)
                    .clip(RoundedCornerShape(sampleHeight / 2))
                    .background(strokeColor)
            )
        }
    }
}

@Composable
private fun ColorDiscSwatches(
    colors: List<Pair<String, String>>,
    currentColorHex: String,
    onColorSelected: (String) -> Unit
) {
    colors.forEach { (hex, name) ->
        val isSelected = currentColorHex.equals(hex, ignoreCase = true)
        val parsed = remember(hex) {
            try {
                Color(android.graphics.Color.parseColor(hex))
            } catch (_: Exception) {
                Color.Gray
            }
        }
        val checkColor = if (parsed == Color(0xFF18181B)) Color.White else Color.Black

        Box(
            modifier = Modifier
                .size(38.dp)
                .semantics {
                    role = Role.RadioButton
                    selected = isSelected
                    contentDescription = "Color $name"
                }
                .clickable(
                    role = Role.RadioButton,
                    onClickLabel = "Seleccionar color $name"
                ) { onColorSelected(hex) },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            )
                        } else {
                            Modifier.border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                shape = CircleShape
                            )
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 20.dp else 22.dp)
                        .clip(CircleShape)
                        .background(parsed)
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = checkColor,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryActionButtons(
    canUndo: Boolean,
    canRedo: Boolean,
    canClear: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClearRequest: () -> Unit
) {
    InkActionIconButton(
        icon = Icons.Default.Undo,
        contentDesc = "Deshacer trazo",
        enabled = canUndo,
        onClick = onUndo
    )

    InkActionIconButton(
        icon = Icons.Default.Redo,
        contentDesc = "Rehacer trazo",
        enabled = canRedo,
        onClick = onRedo
    )

    InkActionIconButton(
        icon = Icons.Default.DeleteOutline,
        contentDesc = "Borrar trazos de la página",
        enabled = canClear,
        isDestructive = true,
        onClick = onClearRequest
    )
}

@Composable
private fun InkActionIconButton(
    icon: ImageVector,
    contentDesc: String,
    enabled: Boolean,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        isDestructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDesc,
                onClick = onClick
            )
            .clearAndSetSemantics {
                contentDescription = contentDesc
                role = Role.Button
                if (enabled) {
                    onClick(label = contentDesc) { onClick(); true }
                } else {
                    disabled()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun OcrStatusIcon(textStatus: String) {
    val isLoading = remember(textStatus) {
        textStatus.contains("Preparando", ignoreCase = true) ||
            textStatus.contains("Cargando", ignoreCase = true)
    }
    val isBlankOrEmpty = remember(textStatus) {
        textStatus.contains("No se detectó", ignoreCase = true) ||
            textStatus.contains("no se pudo", ignoreCase = true)
    }
    val isRecognized = remember(textStatus) {
        textStatus.contains("reconocido", ignoreCase = true) ||
            textStatus.contains("disponible", ignoreCase = true)
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .semantics {
                contentDescription = textStatus
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            isBlankOrEmpty -> {
                Icon(
                    imageVector = Icons.Default.SearchOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp)
                )
            }
            isRecognized -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .semantics {
                role = Role.Button
                contentDescription = "Cerrar escritura"
            }
            .clickable(
                role = Role.Button,
                onClickLabel = "Cerrar escritura",
                onClick = onClose
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun VerticalBarDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    )
}
