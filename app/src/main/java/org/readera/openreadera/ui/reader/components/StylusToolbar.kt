package org.readera.openreadera.ui.reader.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.abs

/**
 * Persistent writing panel displayed when writing mode is active.
 * Inline, non-modal Surface hosting tool selection, contextual stroke previews, color/width choices,
 * history actions (Deshacer, Rehacer), clear confirmation dialog, and an explicit close button.
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
    Surface(
        modifier = modifier
            .widthIn(min = 280.dp, max = 340.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            WritingPanelHeader(onClose)
            WritingPanelContent(
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
            showHeader = false,
            textStatus = textStatus,
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun WritingPanelHeader(onClose: (() -> Unit)?) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Draw,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Herramientas de escritura",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (onClose != null) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.semantics {
                        role = Role.Button
                        contentDescription = "Cerrar escritura"
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cerrar escritura",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
}

/**
 * Reusable ink controls component.
 * Features 5 instrument modes, live stroke preview, contextual color and thickness swatches,
 * distinct history actions (Deshacer, Rehacer), and a guarded page-clear action with confirmation dialog.
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
    WritingPanelContent(
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
        showHeader = true,
        textStatus = null,
        modifier = modifier
            .widthIn(min = 280.dp, max = 340.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

/**
 * Shared writing content layout used by both [ReaderWritingPanel] and [ReaderInkControls].
 */
@Composable
private fun WritingPanelContent(
    currentTool: DrawingTool,
    currentColorHex: String,
    currentStrokeWidth: Float,
    canUndo: Boolean,
    canRedo: Boolean,
    canClear: Boolean,
    onToolSelected: (DrawingTool) -> Unit,
    onColorSelected: (String) -> Unit,
    onStrokeWidthSelected: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClearPage: () -> Unit,
    showHeader: Boolean,
    textStatus: String?,
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

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (showHeader) WritingPanelHeader(null)

        // Compact text/OCR status feedback
        if (!textStatus.isNullOrBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = textStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Instrument Selection Grid (5 modes)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Row 1: Pluma & Rotulador
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                InstrumentChip(
                    tool = DrawingTool.PEN,
                    label = "Pluma",
                    subtitle = "Presión variable",
                    icon = Icons.Default.Edit,
                    isSelected = currentTool == DrawingTool.PEN,
                    onSelect = { onToolSelected(DrawingTool.PEN) },
                    modifier = Modifier.weight(1f)
                )
                InstrumentChip(
                    tool = DrawingTool.MARKER,
                    label = "Rotulador",
                    subtitle = "Ancho uniforme",
                    icon = Icons.Default.Brush,
                    isSelected = currentTool == DrawingTool.MARKER,
                    onSelect = { onToolSelected(DrawingTool.MARKER) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 2: Resaltador libre & Resaltar texto
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                InstrumentChip(
                    tool = DrawingTool.FREE_HIGHLIGHTER,
                    label = "Resaltador libre",
                    subtitle = "Trazo libre",
                    icon = Icons.Default.Draw,
                    isSelected = currentTool == DrawingTool.FREE_HIGHLIGHTER,
                    onSelect = { onToolSelected(DrawingTool.FREE_HIGHLIGHTER) },
                    modifier = Modifier.weight(1f)
                )
                InstrumentChip(
                    tool = DrawingTool.HIGHLIGHTER,
                    label = "Resaltar texto",
                    subtitle = "Ajuste a texto",
                    icon = Icons.Default.Highlight,
                    isSelected = currentTool == DrawingTool.HIGHLIGHTER,
                    onSelect = { onToolSelected(DrawingTool.HIGHLIGHTER) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 3: Goma (full width whole-stroke)
            InstrumentChip(
                tool = DrawingTool.ERASER,
                label = "Goma",
                subtitle = "Borra trazos completos",
                icon = Icons.Default.AutoFixHigh,
                isSelected = currentTool == DrawingTool.ERASER,
                onSelect = { onToolSelected(DrawingTool.ERASER) },
                fullWidth = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Contextual Controls: Eraser Info vs (Stroke Preview + Colors + Widths)
        if (currentTool == DrawingTool.ERASER) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Goma de trazo completo",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Toca o desliza sobre un trazo para borrarlo por completo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            // Live Stroke Preview
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when (currentTool) {
                                DrawingTool.PEN -> "Pluma: sensible a presión"
                                DrawingTool.MARKER -> "Rotulador: ancho uniforme"
                                DrawingTool.FREE_HIGHLIGHTER -> "Resaltador libre"
                                DrawingTool.HIGHLIGHTER -> "Resaltar texto"
                                DrawingTool.ERASER -> "Goma"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${currentStrokeWidth.toInt()} pt",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeColor = try {
                                Color(android.graphics.Color.parseColor(currentColorHex))
                            } catch (_: Exception) {
                                Color(0xFFFACC15)
                            }
                            val widthPx = currentStrokeWidth * density
                            val w = size.width
                            val h = size.height

                            when (currentTool) {
                                DrawingTool.PEN -> {
                                    // Tapered/pressure-varying stroke along cubic Bezier
                                    val p0 = Offset(w * 0.1f, h * 0.65f)
                                    val p1 = Offset(w * 0.35f, h * 0.15f)
                                    val p2 = Offset(w * 0.65f, h * 0.85f)
                                    val p3 = Offset(w * 0.9f, h * 0.35f)
                                    val steps = 36
                                    var prev = p0
                                    for (i in 1..steps) {
                                        val t = i / steps.toFloat()
                                        val oneMinusT = 1f - t
                                        val curr = Offset(
                                            x = oneMinusT * oneMinusT * oneMinusT * p0.x +
                                                3f * oneMinusT * oneMinusT * t * p1.x +
                                                3f * oneMinusT * t * t * p2.x +
                                                t * t * t * p3.x,
                                            y = oneMinusT * oneMinusT * oneMinusT * p0.y +
                                                3f * oneMinusT * oneMinusT * t * p1.y +
                                                3f * oneMinusT * t * t * p2.y +
                                                t * t * t * p3.y
                                        )
                                        val pressureFactor = 0.35f + 0.95f * sin(PI.toFloat() * t)
                                        val segWidth = (widthPx * pressureFactor).coerceIn(1.5f, 18f)
                                        drawLine(
                                            color = strokeColor,
                                            start = prev,
                                            end = curr,
                                            strokeWidth = segWidth,
                                            cap = StrokeCap.Round
                                        )
                                        prev = curr
                                    }
                                }
                                DrawingTool.MARKER -> {
                                    val path = Path().apply {
                                        moveTo(w * 0.1f, h * 0.5f)
                                        cubicTo(
                                            w * 0.35f, h * 0.2f,
                                            w * 0.65f, h * 0.8f,
                                            w * 0.9f, h * 0.5f
                                        )
                                    }
                                    drawPath(
                                        path = path,
                                        color = strokeColor,
                                        style = Stroke(
                                            width = widthPx.coerceIn(3f, 20f),
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round
                                        )
                                    )
                                }
                                DrawingTool.FREE_HIGHLIGHTER -> {
                                    val path = Path().apply {
                                        moveTo(w * 0.1f, h * 0.45f)
                                        cubicTo(
                                            w * 0.35f, h * 0.25f,
                                            w * 0.65f, h * 0.75f,
                                            w * 0.9f, h * 0.55f
                                        )
                                    }
                                    drawPath(
                                        path = path,
                                        color = strokeColor.copy(alpha = 0.50f),
                                        style = Stroke(
                                            width = (widthPx * 0.65f).coerceIn(8f, 24f),
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round
                                        )
                                    )
                                }
                                DrawingTool.HIGHLIGHTER -> {
                                    val rectHeight = (widthPx * 0.55f).coerceIn(12f, 22f)
                                    drawRoundRect(
                                        color = strokeColor.copy(alpha = 0.45f),
                                        topLeft = Offset(w * 0.12f, h * 0.5f - rectHeight / 2),
                                        size = Size(w * 0.76f, rectHeight),
                                        cornerRadius = CornerRadius(4f, 4f)
                                    )
                                }
                                DrawingTool.ERASER -> {}
                            }
                        }
                    }
                }
            }

            // Color Swatches
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp)
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
                            .size(48.dp)
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
                                .size(34.dp)
                                .clip(CircleShape)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            width = 2.5.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape
                                        )
                                    } else {
                                        Modifier.border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                            shape = CircleShape
                                        )
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 24.dp else 26.dp)
                                    .clip(CircleShape)
                                    .background(parsed)
                                    .border(
                                        width = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = checkColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Stroke Width Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                widths.forEach { (widthValue, label) ->
                    val isSelected = abs(currentStrokeWidth - widthValue) < 0.5f
                    val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow
                    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface
                    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
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
                            ) { onStrokeWidthSelected(widthValue) }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            val barHeight = when (currentTool) {
                                DrawingTool.PEN -> widthValue.coerceIn(2f, 8f).dp
                                DrawingTool.MARKER -> (widthValue * 0.4f).coerceIn(3f, 8f).dp
                                else -> (widthValue * 0.25f).coerceIn(3f, 8f).dp
                            }
                            Box(
                                modifier = Modifier
                                    .width(24.dp)
                                    .height(barHeight)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(contentColor)
                            )
                            Text(
                                text = "$label • ${widthValue.toInt()} pt",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = contentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(vertical = 2.dp)
        )

        // History Actions & Clear Page
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Deshacer trazo
            InkActionButton(
                icon = Icons.Default.Undo,
                label = "Deshacer",
                contentDesc = "Deshacer trazo",
                enabled = canUndo,
                onClick = onUndo,
                modifier = Modifier.weight(1f)
            )

            // Rehacer trazo
            InkActionButton(
                icon = Icons.Default.Redo,
                label = "Rehacer",
                contentDesc = "Rehacer trazo",
                enabled = canRedo,
                onClick = onRedo,
                modifier = Modifier.weight(1f)
            )

            // Borrar trazos de la página (destructive, requires confirmation)
            InkActionButton(
                icon = Icons.Default.DeleteOutline,
                label = "Borrar",
                contentDesc = "Borrar trazos de la página",
                enabled = canClear,
                isDestructive = true,
                onClick = { showClearConfirmDialog = true },
                modifier = Modifier.weight(1f)
            )
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
 * Selectable instrument button representing one of the 5 writing tool modes.
 */
@Composable
private fun InstrumentChip(
    tool: DrawingTool,
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    fullWidth: Boolean = false,
    subtitle: String? = null
) {
    val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
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
                contentDescription = label
            }
            .clickable(
                role = Role.RadioButton,
                onClickLabel = "Seleccionar $label",
                onClick = onSelect
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            if (fullWidth && subtitle != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "• $subtitle",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Column(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (isSelected) contentColor.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Action button for undo, redo, and page-clear with distinct visual enabled/disabled states.
 */
@Composable
private fun InkActionButton(
    icon: ImageVector,
    label: String,
    contentDesc: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false
) {
    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        isDestructive -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        isDestructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val borderColor = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        isDestructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }

    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDesc,
                onClick = onClick
            )
            .clearAndSetSemantics {
                contentDescription = contentDesc
                role = Role.Button
                if (enabled) onClick(label = contentDesc) { onClick(); true } else disabled()
            }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}
